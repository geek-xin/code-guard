package com.geek.codeguard.project.service;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.project.git.GitService;
import com.geek.codeguard.project.model.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ProjectService {

    private final JsonStore jsonStore;
    private final GitService gitService;
    /** 每个项目一把锁，防止并发 git 操作同一工作区 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> projectLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public ProjectService(JsonStore jsonStore, GitService gitService) {
        this.jsonStore = jsonStore;
        this.gitService = gitService;
    }

    public List<Project> list() {
        return jsonStore.readList(jsonStore.paths().repositories, Project.class);
    }

    public Project get(String id) {
        return find(id).orElseThrow(() -> new BusinessException(ErrorCodeEnum.PROJECT_NOT_FOUND));
    }

    public java.util.Optional<Project> find(String id) {
        return list().stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public Project create(Project project) {
        if (!StringUtils.hasText(project.getName())) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "项目名称不能为空");
        }
        if (list().stream().anyMatch(p -> p.getName().equalsIgnoreCase(project.getName()))) {
            throw new BusinessException(ErrorCodeEnum.PROJECT_NAME_EXISTS);
        }
        project.setId(UUID.randomUUID().toString());
        project.setEnabled(project.isEnabled());
        // 默认开启定时同步（每 60 分钟），保持代码最新
        if (project.getSyncIntervalMinutes() == null || project.getSyncIntervalMinutes() <= 0) {
            project.setSyncIntervalMinutes(60);
        }
        project.setAutoSyncEnabled(true);
        // 默认开启漏洞自动扫描（每 3 小时一次）
        if (project.getScanIntervalMinutes() == null || project.getScanIntervalMinutes() <= 0) {
            project.setScanIntervalMinutes(180);
        }
        project.setAutoScanEnabled(true);
        project.setSyncStatus("PENDING");
        project.setCreatedAt(Instant.now());
        project.setUpdatedAt(Instant.now());
        validateAndNormalize(project);
        project.setTokenConfigured(project.getToken() != null && !project.getToken().isBlank());
        save(project);
        return project;
    }

    public Project update(String id, Project update) {
        Project existing = get(id);
        if (update.getName() != null) {
            list().stream().filter(p -> !p.getId().equals(id) && p.getName().equalsIgnoreCase(update.getName()))
                    .findFirst().ifPresent(p -> {
                        throw new BusinessException(ErrorCodeEnum.PROJECT_NAME_EXISTS);
                    });
            existing.setName(update.getName());
        }
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getSource() != null) existing.setSource(update.getSource());
        if (update.getRepoUrl() != null) existing.setRepoUrl(update.getRepoUrl());
        if (update.getBranch() != null) existing.setBranch(update.getBranch());
        if (update.getLocalPath() != null) existing.setLocalPath(update.getLocalPath());
        if (update.getToken() != null) {
            if (update.getToken().isBlank()) {
                existing.setToken(null);
                existing.setTokenConfigured(false);
            } else {
                existing.setToken(update.getToken());
                existing.setTokenConfigured(true);
            }
        }
        if (update.getScheduleCron() != null) existing.setScheduleCron(update.getScheduleCron());
        if (update.isScheduleEnabled() != existing.isScheduleEnabled()) existing.setScheduleEnabled(update.isScheduleEnabled());
        if (update.isAutoSyncEnabled() != existing.isAutoSyncEnabled()) existing.setAutoSyncEnabled(update.isAutoSyncEnabled());
        if (update.getSyncIntervalMinutes() != null && update.getSyncIntervalMinutes() > 0) {
            existing.setSyncIntervalMinutes(update.getSyncIntervalMinutes());
        }
        if (update.isAutoScanEnabled() != existing.isAutoScanEnabled()) existing.setAutoScanEnabled(update.isAutoScanEnabled());
        if (update.getScanIntervalMinutes() != null && update.getScanIntervalMinutes() > 0) {
            existing.setScanIntervalMinutes(update.getScanIntervalMinutes());
        }
        if (update.isEnabled() != existing.isEnabled()) existing.setEnabled(update.isEnabled());
        validateAndNormalize(existing);
        existing.setUpdatedAt(Instant.now());
        save(existing);
        return existing;
    }

    private void validateAndNormalize(Project p) {
        if (Project.SOURCE_LOCAL.equals(p.getSource())) {
            if (!StringUtils.hasText(p.getLocalPath())) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "本地项目需要填写源码目录");
            }
            Path dir = Path.of(p.getLocalPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                throw new BusinessException(ErrorCodeEnum.SOURCE_NOT_FOUND, "本地源码目录不存在: " + dir);
            }
            p.setLocalPath(dir.toString());
        } else {
            if (!StringUtils.hasText(p.getRepoUrl())) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "远程项目需要填写仓库地址");
            }
            if (!p.getRepoUrl().startsWith("http://") && !p.getRepoUrl().startsWith("https://")
                    && !p.getRepoUrl().startsWith("git@")) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "仓库地址格式不正确");
            }
            if (!StringUtils.hasText(p.getBranch())) {
                p.setBranch("main");
            }
        }
    }

    public void save(Project project) {
        Path file = jsonStore.paths().repositories.resolve(project.getId() + ".json");
        jsonStore.write(file, project);
    }

    public void delete(String id) {
        Project p = get(id);
        jsonStore.delete(jsonStore.paths().repositories.resolve(id + ".json"));
        // 清理工作区代码
        try {
            gitService.deleteWorkspace(id);
        } catch (Exception e) {
            log.warn("清理工作区失败: {}", e.getMessage());
        }
    }

    /** 拉取/克隆代码到工作区（按项目互斥，防止并发 git 操作） */
    public Path syncCode(Project project) {
        Object lock = projectLocks.computeIfAbsent(project.getId(), k -> new Object());
        synchronized (lock) {
            if (Project.SOURCE_LOCAL.equals(project.getSource())) {
                Path dir = Path.of(project.getLocalPath()).toAbsolutePath().normalize();
                if (!Files.isDirectory(dir)) {
                    throw new BusinessException(ErrorCodeEnum.SOURCE_NOT_FOUND, "本地源码目录不存在: " + dir);
                }
                return dir;
            }
            String token = project.getToken();
            return gitService.syncRepo(project.getId(), project.getRepoUrl(), project.getBranch(), token);
        }
    }

    public void updateSyncStatus(Project p, String status, String message) {
        p.setSyncStatus(status);
        p.setSyncMessage(message);
        p.setUpdatedAt(Instant.now());
        save(p);
    }

    /** 项目级统计汇总（供列表展示） */
    public List<Project> listWithStats() {
        return list();
    }

    public static List<String> normalizeTokenMask(Project p) {
        return new ArrayList<>();
    }
}
