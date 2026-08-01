package com.geek.codeguard.project.service;

import com.geek.codeguard.project.model.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代码定时同步：按项目配置的间隔（默认 60 分钟）自动拉取最新代码。
 * 与手动同步、扫描拉取通过 ProjectService 的 per-project 锁互斥。
 */
@Service
@Slf4j
public class ScheduledSyncService {

    private static final int DEFAULT_INTERVAL_MINUTES = 60;

    private final ProjectService projectService;
    private final ExecutorService executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ScheduledSyncService(ProjectService projectService) {
        this.projectService = projectService;
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "auto-sync-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    /** 每 30 秒轮询一次到期的项目 */
    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    public void poll() {
        Instant now = Instant.now();
        for (Project project : projectService.list()) {
            if (!project.isEnabled() || !project.isAutoSyncEnabled()) {
                continue;
            }
            int interval = project.getSyncIntervalMinutes() == null || project.getSyncIntervalMinutes() <= 0
                    ? DEFAULT_INTERVAL_MINUTES : project.getSyncIntervalMinutes();
            Instant last = project.getLastSyncAt() != null ? project.getLastSyncAt()
                    : project.getCreatedAt() != null ? project.getCreatedAt() : now.minus(interval, java.time.temporal.ChronoUnit.MINUTES);
            if (last != null && now.isBefore(last.plus(Duration.ofMinutes(interval)))) {
                continue;
            }
            if ("SYNCING".equals(project.getSyncStatus()) || inFlight.contains(project.getId())) {
                continue;
            }
            if (Project.SOURCE_LOCAL.equals(project.getSource())) {
                // 本地目录无需同步（直接扫描最新内容），但记录同步时间避免频繁空转
                project.setLastSyncAt(now);
                projectService.save(project);
                continue;
            }
            trigger(project);
        }
    }

    private void trigger(Project project) {
        if (!inFlight.add(project.getId())) {
            return;
        }
        executor.submit(() -> {
            try {
                log.info("定时同步开始: {} ({})", project.getName(), project.getId());
                Project p = projectService.get(project.getId());
                projectService.updateSyncStatus(p, "SYNCING", "定时同步中...");
                projectService.syncCode(p);
                p.setLastSyncAt(Instant.now());
                projectService.updateSyncStatus(p, "READY", "代码已就绪（定时同步）");
                log.info("定时同步完成: {}", project.getName());
            } catch (Exception e) {
                log.warn("定时同步失败 {}: {}", project.getName(), e.getMessage());
                try {
                    Project p = projectService.get(project.getId());
                    projectService.updateSyncStatus(p, "ERROR", "定时同步失败: " + e.getMessage());
                } catch (Exception ignored) {
                }
            } finally {
                inFlight.remove(project.getId());
            }
        });
    }
}
