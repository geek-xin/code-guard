package com.geek.codeguard.project.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
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

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Data
    public static class ProjectRequest {
        @NotBlank(message = "项目名称不能为空")
        private String name;
        private String description;
        @NotBlank(message = "源码来源不能为空")
        private String source;
        private String repoUrl;
        private String branch;
        private String localPath;
        private String token;
        private String scheduleCron;
        private boolean scheduleEnabled;
        private boolean enabled = true;
    }

    @GetMapping
    public Mono<Result<List<Project>>> list() {
        return Mono.just(Result.success(projectService.listWithStats()));
    }

    @GetMapping("/{id}")
    public Mono<Result<Project>> get(@PathVariable String id) {
        return Mono.just(Result.success(projectService.get(id)));
    }

    @PostMapping
    public Mono<Result<Project>> create(@Valid @RequestBody ProjectRequest req) {
        return Mono.fromCallable(() -> {
            Project project = Project.builder()
                    .name(req.getName().trim())
                    .description(req.getDescription())
                    .source(req.getSource().toUpperCase())
                    .repoUrl(req.getRepoUrl())
                    .branch(req.getBranch())
                    .localPath(req.getLocalPath())
                    .token(req.getToken())
                    .scheduleCron(req.getScheduleCron())
                    .scheduleEnabled(req.isScheduleEnabled())
                    .enabled(req.isEnabled())
                    .build();
            return projectService.create(project);
        }).map(Result::success);
    }

    @PutMapping("/{id}")
    public Mono<Result<Project>> update(@PathVariable String id, @RequestBody ProjectRequest req) {
        return Mono.fromCallable(() -> {
            Project update = Project.builder()
                    .name(req.getName())
                    .description(req.getDescription())
                    .source(req.getSource() == null ? null : req.getSource().toUpperCase())
                    .repoUrl(req.getRepoUrl())
                    .branch(req.getBranch())
                    .localPath(req.getLocalPath())
                    .token(req.getToken())
                    .scheduleCron(req.getScheduleCron())
                    .scheduleEnabled(req.isScheduleEnabled())
                    .enabled(req.isEnabled())
                    .build();
            return projectService.update(id, update);
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
