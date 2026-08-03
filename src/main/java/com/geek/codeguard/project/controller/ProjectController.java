package com.geek.codeguard.project.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.project.git.GitRemoteService;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import com.geek.codeguard.settings.service.SettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@Slf4j
public class ProjectController {

    private final ProjectService projectService;
    private final GitRemoteService gitRemoteService;
    private final SettingsService settingsService;

    public ProjectController(ProjectService projectService, GitRemoteService gitRemoteService,
                             SettingsService settingsService) {
        this.projectService = projectService;
        this.gitRemoteService = gitRemoteService;
        this.settingsService = settingsService;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        return tags.stream().map(String::trim).filter(t -> !t.isBlank()).distinct().toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 接口响应脱敏：Token 只存本地、绝不返回给前端 */
    private static Project sanitize(Project p) {
        if (p != null) {
            p.setToken(null);
        }
        return p;
    }

    @Data
    public static class ProjectRequest {
        @NotBlank(message = "项目名称不能为空")
        private String name;
        private String alias;
        private List<String> tags;
        private String group;
        private String description;
        @NotBlank(message = "源码来源不能为空")
        private String source;
        private String repoUrl;
        private String branch;
        private String localPath;
        private String token;
        private String scheduleCron;
        private boolean scheduleEnabled;
        private boolean emailNotify;
        private List<String> emails;
        private boolean autoSyncEnabled = true;
        private Integer syncIntervalMinutes;
        private boolean agentReviewEnabled;
        private boolean autoScanEnabled = true;
        private Integer scanIntervalMinutes;
        private boolean enabled = true;
    }

    @Data
    public static class BranchListRequest {
        /** GITHUB / GITLAB */
        private String source;
        /** 仓库地址（HTTPS 或 SSH） */
        private String repoUrl;
        /** 访问令牌：GitHub 私有仓库可选，GitLab 必填 */
        private String token;
    }

    /** 查询远程仓库分支列表（GitHub / GitLab），供添加项目时选择拉取分支 */
    @PostMapping("/branches")
    public Mono<Result<GitRemoteService.BranchList>> branches(@RequestBody BranchListRequest req) {
        return Mono.fromCallable(() -> {
            String token = req.getToken();
            // 未在表单填写令牌时，复用「设置」中的全局 GitHub/GitLab 令牌
            if (token == null || token.isBlank()) {
                token = settingsService.effectiveGitToken(req.getSource());
            }
            return Result.success(gitRemoteService.listBranches(
                    req.getSource(), req.getRepoUrl(), token));
        });
    }

    @GetMapping
    public Mono<Result<List<Project>>> list() {
        return Mono.just(Result.success(projectService.list().stream()
                .map(ProjectController::sanitize).toList()));
    }

    /** 导出全部工程配置（脱敏 JSON，不含令牌明文），可再导入恢复 */
    @GetMapping("/export")
    public Mono<Result<Map<String, Object>>> exportConfig() {
        return Mono.just(Result.success(projectService.exportConfig()));
    }

    @Data
    public static class ImportRequest {
        private Integer version;
        private java.util.List<Project> projects;
    }

    /** 导入工程配置（批量创建，同名跳过，单条失败不影响其余） */
    @PostMapping("/import")
    public Mono<Result<Map<String, Object>>> importConfig(@RequestBody ImportRequest req) {
        return Mono.fromCallable(() -> Result.success(
                projectService.importConfig(req == null ? null : req.getProjects())));
    }

    @GetMapping("/{id}")
    public Mono<Result<Project>> get(@PathVariable String id) {
        return Mono.just(Result.success(sanitize(projectService.get(id))));
    }

    @PostMapping
    public Mono<Result<Project>> create(@Valid @RequestBody ProjectRequest req) {
        return Mono.fromCallable(() -> {
            Project project = Project.builder()
                    .name(req.getName().trim())
                    .alias(blankToNull(req.getAlias()))
                    .tags(normalizeTags(req.getTags()))
                    .group(blankToNull(req.getGroup()))
                    .description(req.getDescription())
                    .source(req.getSource().toUpperCase())
                    .repoUrl(req.getRepoUrl())
                    .branch(req.getBranch())
                    .localPath(req.getLocalPath())
                    .token(req.getToken())
                    .scheduleCron(req.getScheduleCron())
                    .scheduleEnabled(req.isScheduleEnabled())
                    .emailNotify(req.isEmailNotify())
                    .emails(req.getEmails() == null ? null : req.getEmails().stream()
                            .map(String::trim).filter(e -> !e.isBlank()).toList())
                    .autoSyncEnabled(req.isAutoSyncEnabled())
                    .syncIntervalMinutes(req.getSyncIntervalMinutes())
                    .agentReviewEnabled(req.isAgentReviewEnabled())
                    .autoScanEnabled(req.isAutoScanEnabled())
                    .scanIntervalMinutes(req.getScanIntervalMinutes())
                    .enabled(req.isEnabled())
                    .build();
            return sanitize(projectService.create(project));
        }).map(Result::success);
    }

    @PutMapping("/{id}")
    public Mono<Result<Project>> update(@PathVariable String id, @RequestBody ProjectRequest req) {
        return Mono.fromCallable(() -> {
            Project update = Project.builder()
                    .name(req.getName())
                    .alias(blankToNull(req.getAlias()))
                    .tags(normalizeTags(req.getTags()))
                    .group(req.getGroup())
                    .description(req.getDescription())
                    .source(req.getSource() == null ? null : req.getSource().toUpperCase())
                    .repoUrl(req.getRepoUrl())
                    .branch(req.getBranch())
                    .localPath(req.getLocalPath())
                    .token(req.getToken())
                    .scheduleCron(req.getScheduleCron())
                    .scheduleEnabled(req.isScheduleEnabled())
                    .emailNotify(req.isEmailNotify())
                    .emails(req.getEmails())
                    .autoSyncEnabled(req.isAutoSyncEnabled())
                    .syncIntervalMinutes(req.getSyncIntervalMinutes())
                    .agentReviewEnabled(req.isAgentReviewEnabled())
                    .autoScanEnabled(req.isAutoScanEnabled())
                    .scanIntervalMinutes(req.getScanIntervalMinutes())
                    .enabled(req.isEnabled())
                    .build();
            return sanitize(projectService.update(id, update));
        }).map(Result::success);
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            projectService.delete(id);
            return Result.<Void>success();
        });
    }

    /** 同步代码（拉取/克隆），返回最新状态 */
    @PostMapping("/{id}/sync")
    public Mono<Result<Map<String, String>>> sync(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            Project p = projectService.get(id);
            projectService.updateSyncStatus(p, "SYNCING", "正在拉取代码...");
            try {
                projectService.syncCode(p);
                projectService.updateSyncStatus(p, "READY", "代码已就绪");
                return Map.of("status", "READY", "message", "代码已就绪");
            } catch (Exception e) {
                projectService.updateSyncStatus(p, "ERROR", e.getMessage());
                throw e;
            }
        }).map(Result::success);
    }
}
