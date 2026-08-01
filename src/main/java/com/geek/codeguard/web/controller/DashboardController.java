package com.geek.codeguard.web.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import com.geek.codeguard.scan.service.ScanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ProjectService projectService;
    private final ScanService scanService;

    public DashboardController(ProjectService projectService, ScanService scanService) {
        this.projectService = projectService;
        this.scanService = scanService;
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Object>>> stats() {
        return Mono.fromCallable(() -> {
            List<Project> projects = projectService.list();
            List<ScanRecord> scans = scanService.listScans(null);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalProjects", projects.size());
            stats.put("scannedProjects", (int) projects.stream().filter(p -> p.getLastScanAt() != null).count());
            stats.put("totalScans", scans.size());
            stats.put("runningScans", (int) scans.stream().filter(s -> "RUNNING".equals(s.getStatus())).count());
            stats.put("failedScans", (int) scans.stream().filter(s -> "FAILED".equals(s.getStatus())).count());

            // 汇总每个项目最近一次扫描的发现
            int total = 0, critical = 0, high = 0, medium = 0, low = 0, info = 0;
            Map<String, Integer> byEngine = new LinkedHashMap<>();
            for (Project p : projects) {
                Map<String, Object> s = p.getLastScanStats();
                if (s == null) {
                    continue;
                }
                total += num(s.get("total"));
                critical += num(s.get("critical"));
                high += num(s.get("high"));
                medium += num(s.get("medium"));
                low += num(s.get("low"));
                info += num(s.get("info"));
                Object eng = s.get("byEngine");
                if (eng instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> byEngine.merge(String.valueOf(k), ((Number) v).intValue(), Integer::sum));
                }
            }
            stats.put("findings", total);
            stats.put("critical", critical);
            stats.put("high", high);
            stats.put("medium", medium);
            stats.put("low", low);
            stats.put("info", info);
            stats.put("byEngine", byEngine);

            List<ScanRecord> recent = scans.stream().limit(10).toList();
            stats.put("recentScans", recent);
            return stats;
        }).map(Result::success);
    }

    /** 最近 N 天每日新增漏洞数趋势 */
    @GetMapping("/trend")
    public Mono<Result<List<Map<String, Object>>>> trend(@RequestParam(defaultValue = "14") int days) {
        return Mono.fromCallable(() -> {
            List<ScanRecord> scans = scanService.listScans(null).stream()
                    .filter(s -> s.getFinishedAt() != null && "COMPLETED".equals(s.getStatus()))
                    .toList();
            Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
            for (ScanRecord s : scans) {
                String day = s.getFinishedAt().substring(0, 10);
                Map<String, Object> row = byDay.computeIfAbsent(day, k -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("date", k);
                    r.put("total", 0);
                    r.put("critical", 0);
                    r.put("high", 0);
                    r.put("medium", 0);
                    r.put("low", 0);
                    return r;
                });
                Map<String, Object> sum = s.getSummary();
                if (sum != null) {
                    row.put("total", (Integer) row.get("total") + num(sum.get("total")));
                    row.put("critical", (Integer) row.get("critical") + num(sum.get("critical")));
                    row.put("high", (Integer) row.get("high") + num(sum.get("high")));
                    row.put("medium", (Integer) row.get("medium") + num(sum.get("medium")));
                    row.put("low", (Integer) row.get("low") + num(sum.get("low")));
                }
            }
            // 按日期升序
            List<Map<String, Object>> result = new java.util.ArrayList<>(byDay.values());
            result.sort((a, b) -> String.valueOf(a.get("date")).compareTo(String.valueOf(b.get("date"))));
            return result;
        }).map(Result::success);
    }

    private int num(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
