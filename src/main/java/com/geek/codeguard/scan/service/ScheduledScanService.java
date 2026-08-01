package com.geek.codeguard.scan.service;

import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 漏洞自动扫描调度：
 * - 默认按间隔触发（新项目每 3 小时自动扫描一次）
 * - 配置了 cron（定时扫描）的项目优先按 cron 执行（向后兼容）
 */
@Service
@Slf4j
public class ScheduledScanService {

    private static final int DEFAULT_SCAN_INTERVAL_MINUTES = 180; // 3 小时

    private final ProjectService projectService;
    private final ScanService scanService;
    private final Map<String, Instant> lastCronTrigger = new ConcurrentHashMap<>();

    public ScheduledScanService(ProjectService projectService, ScanService scanService) {
        this.projectService = projectService;
        this.scanService = scanService;
    }

    @Scheduled(fixedDelayString = "#{@codeGuardProperties.scheduler.pollIntervalMs}", initialDelay = 15000)
    public void poll() {
        Instant now = Instant.now();
        for (Project project : projectService.list()) {
            if (!project.isEnabled()) {
                continue;
            }
            boolean hasCron = project.isScheduleEnabled()
                    && project.getScheduleCron() != null && !project.getScheduleCron().isBlank();
            if (hasCron) {
                tryCronTrigger(project, now);
            } else if (project.isAutoScanEnabled()) {
                tryIntervalTrigger(project, now);
            }
        }
    }

    /** 间隔模式：距上次扫描（或创建）超过间隔即触发，默认 3 小时 */
    private void tryIntervalTrigger(Project project, Instant now) {
        int interval = project.getScanIntervalMinutes() == null || project.getScanIntervalMinutes() <= 0
                ? DEFAULT_SCAN_INTERVAL_MINUTES : project.getScanIntervalMinutes();
        Instant base = project.getLastScanAt() != null ? project.getLastScanAt()
                : project.getCreatedAt() != null ? project.getCreatedAt() : null;
        if (base == null || now.isBefore(base.plus(Duration.ofMinutes(interval)))) {
            return;
        }
        log.info("触发自动扫描（间隔 {} 分钟）: {}", interval, project.getName());
        trigger(project);
    }

    /** cron 模式：表达式匹配后触发（防重复） */
    private void tryCronTrigger(Project project, Instant now) {
        try {
            CronExpression expr = CronExpression.parse(project.getScheduleCron());
            Instant last = lastCronTrigger.getOrDefault(project.getId(), now.minusSeconds(1));
            Instant next = expr.next(last);
            if (next != null && !next.isAfter(now)) {
                lastCronTrigger.put(project.getId(), now);
                log.info("触发定时扫描（cron {}）: {}", project.getScheduleCron(), project.getName());
                trigger(project);
            }
        } catch (IllegalArgumentException e) {
            log.warn("项目 {} 的 cron 表达式无效: {}", project.getName(), project.getScheduleCron());
        }
    }

    private void trigger(Project project) {
        try {
            scanService.startScan(project.getId(), "SCHEDULED", "ALL");
        } catch (Exception e) {
            log.warn("自动扫描启动失败 {}: {}", project.getName(), e.getMessage());
        }
    }
}
