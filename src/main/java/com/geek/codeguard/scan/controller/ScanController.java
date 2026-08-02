package com.geek.codeguard.scan.controller;

import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.common.constants.CommonConstants;
import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.github.GitHubIssueService;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import com.geek.codeguard.scan.service.ScanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scans")
public class ScanController {

    private final ScanService scanService;
    private final ProjectService projectService;
    private final GitHubIssueService githubIssueService;

    public ScanController(ScanService scanService, ProjectService projectService,
                          GitHubIssueService githubIssueService) {
        this.scanService = scanService;
        this.projectService = projectService;
        this.githubIssueService = githubIssueService;
    }

    @Data
    public static class StartScanRequest {
        @NotBlank(message = "项目不能为空")
        private String projectId;
        private String trigger = "MANUAL";
        private String scope = "ALL";
    }

    @PostMapping
    public Mono<Result<ScanRecord>> start(@Valid @RequestBody StartScanRequest req) {
        return Mono.fromCallable(() -> scanService.startScan(req.getProjectId(), req.getTrigger(), req.getScope()))
                .map(Result::success);
    }

    @GetMapping
    public Mono<Result<List<ScanRecord>>> list(@RequestParam(required = false) String projectId) {
        return Mono.just(Result.success(scanService.listScans(projectId)));
    }

    @GetMapping("/{id}")
    public Mono<Result<ScanRecord>> get(@PathVariable String id) {
        return Mono.just(Result.success(scanService.getScan(id)));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> events(@PathVariable String id) {
        return scanService.events(id);
    }

    @GetMapping("/{id}/findings")
    public Mono<Result<List<ScanFinding>>> findings(@PathVariable String id,
                                                    @RequestParam(required = false) String severity,
                                                    @RequestParam(required = false) String engine,
                                                    @RequestParam(required = false) String category,
                                                    @RequestParam(required = false) Integer limit) {
        return Mono.just(Result.success(scanService.getFindings(id, severity, engine, category, limit)));
    }

    @GetMapping("/{id}/findings/{findingId}")
    public Mono<Result<ScanFinding>> finding(@PathVariable String id, @PathVariable String findingId) {
        return Mono.just(Result.success(scanService.getFinding(id, findingId)));
    }

    /** 启动 AI 审查任务（异步，跨会话可见） */
    /** 将本次扫描发现提交为 GitHub Issue */
    @PostMapping("/{id}/github-issue")
    public Mono<Result<Map<String, Object>>> githubIssue(@PathVariable String id,
                                                         org.springframework.web.server.ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            var scan = scanService.getScan(id);
            Project project = projectService.get(scan.getProjectId());
            var findings = scanService.getFindings(id, null, null, null, null);
            User user = (User) exchange.getAttribute(CommonConstants.CURRENT_USER_ATTR);
            String token = project.getToken() != null ? project.getToken()
                    : user != null ? user.getAccessToken() : null;
            return githubIssueService.createIssue(project, scan, findings, token);
        }).map(Result::success);
    }

    @PostMapping("/{id}/agent-review")
    public Mono<Result<Map<String, Object>>> agentReview(@PathVariable String id) {
        return Mono.fromCallable(() -> scanService.startAgentReview(id)).map(Result::success);
    }

    /** 停止 AI 审查任务 */
    @PostMapping("/{id}/agent-review/stop")
    public Mono<Result<Map<String, Object>>> stopAgentReview(@PathVariable String id) {
        return Mono.just(Result.success(scanService.stopAgentReview(id)));
    }

    /** 查询 AI 审查任务状态 */
    @GetMapping("/{id}/agent-review/status")
    public Mono<Result<Map<String, Object>>> agentReviewStatus(@PathVariable String id) {
        return Mono.just(Result.success(scanService.agentReviewStatus(id)));
    }

    /** AI 审查实时思考过程（SSE） */
    @GetMapping(value = "/{id}/agent-review/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> agentReviewEvents(@PathVariable String id) {
        return scanService.agentReviewEvents(id);
    }

    @PostMapping("/{id}/stop")
    public Mono<Result<Void>> stop(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            scanService.stopScan(id);
            return Result.<Void>success();
        });
    }
}
