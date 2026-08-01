package com.geek.codeguard.scan.service;

import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时扫描调度器：按项目配置的 cron 表达式触发扫描。
 */
@Service
@Slf4j
public class ScheduledScanService {

    private final ProjectService projectService;
    private final ScanService scanService;
    private final Map<String, Instant> lastTrigger = new ConcurrentHashMap<>();

    public ScheduledScanService(ProjectService projectService, ScanService scanService) {
        this.projectService = projectService;
        this.scanService = scanService;
    }

    @Scheduled(fixedDelayString = "#{@codeGuardProperties.scheduler.pollIntervalMs}", initialDelay = 15000)
    public void poll() {
        Instant now = Instant.now();
        for (Project project : projectService.list()) {
            if (!project.isEnabled() || !project.isScheduleEnabled()) {
                continue;
            }
            String cron = project.getScheduleCron();
            if (cron == null || cron.isBlank()) {
                continue;
            }
            try {
                CronExpression expr = CronExpression.parse(cron);
                Instant last = lastTrigger.getOrDefault(project.getId(), now.minusSeconds(1));
                Instant next = expr.next(last);
                if (next != null && !next.isAfter(now)) {
                    lastTrigger.put(project.getId(), now);
                    log.info("触发定时扫描: {} ({})", project.getName(), cron);
                    try {
                        scanService.startScan(project.getId(), "SCHEDULED", "ALL");
                    } catch (Exception e) {
                        log.warn("定时扫描启动失败 {}: {}", project.getName(), e.getMessage());
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("项目 {} 的 cron 表达式无效: {}", project.getName(), cron);
            }
        }
    }
}
