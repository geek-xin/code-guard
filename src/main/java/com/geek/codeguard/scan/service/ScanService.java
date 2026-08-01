package com.geek.codeguard.scan.service;

import com.geek.codeguard.agent.ReviewAgentService;
import com.geek.codeguard.mail.MailService;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.project.service.ProjectService;
import com.geek.codeguard.sast.service.SastRuleEngine;
import com.geek.codeguard.sca.service.ScaService;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * 扫描编排器：CLONE -> DETECT -> SCA -> SAST -> AGENT -> DONE
 * 实时扫描通过 SSE 推送进度；每次扫描结果持久化为 JSON。
 */
@Service
@Slf4j
public class ScanService implements ScanProgressListener {

    private final JsonStore jsonStore;
    private final ProjectService projectService;
    private final ScaService scaService;
    private final SastRuleEngine sastRuleEngine;
    private final ReviewAgentService reviewAgentService;
    private final ProjectFileScanner fileScanner;
    private final MailService mailService;
    private final CodeGuardProperties props;

    private final ExecutorService executor;
    private final Map<String, Sinks.Many<Map<String, Object>>> sinks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<String, String> runningProjectScan = new ConcurrentHashMap<>();

    public ScanService(JsonStore jsonStore, ProjectService projectService, ScaService scaService,
                       SastRuleEngine sastRuleEngine, ReviewAgentService reviewAgentService,
                       ProjectFileScanner fileScanner, MailService mailService, CodeGuardProperties props) {
        this.jsonStore = jsonStore;
        this.projectService = projectService;
        this.scaService = scaService;
        this.sastRuleEngine = sastRuleEngine;
        this.reviewAgentService = reviewAgentService;
        this.fileScanner = fileScanner;
        this.mailService = mailService;
        this.props = props;
        int concurrency = Math.max(1, props.getScanConcurrency());
        this.executor = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "scan-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ============ 启动扫描 ============

    public ScanRecord startScan(String projectId, String trigger, String scope) {
        Project project = projectService.get(projectId);
        String existing = runningProjectScan.get(projectId);
        if (existing != null) {
            throw new BusinessException(ErrorCodeEnum.SCAN_RUNNING, "该项目正在扫描中，请稍后再试");
        }
        java.util.Map<String, ScanRecord.StageProgress> initialStages = new java.util.LinkedHashMap<>();
        initialStages.put("CLONE", stage("RUNNING"));
        initialStages.put("DETECT", stage("PENDING"));
        initialStages.put("SCA", stage("PENDING"));
        initialStages.put("SAST", stage("PENDING"));
        initialStages.put("AGENT", stage("PENDING"));
        ScanRecord record = ScanRecord.builder()
                .id(UUID.randomUUID().toString())
                .projectId(projectId)
                .projectName(project.getName())
                .trigger(trigger)
                .scope(scope == null ? "ALL" : scope)
                .status("RUNNING")
                .startedAt(now())
                .stages(initialStages)
                .build();
        saveRecord(record);
        projectService.find(projectId).ifPresent(p -> {
            p.setLastScanId(record.getId());
            p.setLastScanAt(Instant.now());
            p.setLastScanStatus("RUNNING");
            projectService.save(p);
        });

        Sinks.Many<Map<String, Object>> sink = Sinks.many().multicast().onBackpressureBuffer(4096);
        sinks.put(record.getId(), sink);
        cancelFlags.put(record.getId(), new AtomicBoolean(false));
        runningProjectScan.put(projectId, record.getId());

        emit(record.getId(), "scan-start", Map.of("scanId", record.getId(), "projectName", project.getName()));
        String scanId = record.getId();
        executor.submit(() -> runPipeline(scanId, project, record, scope));
        return record;
    }

    private void runPipeline(String scanId, Project project, ScanRecord record, String scope) {
        List<ScanFinding> findings = new ArrayList<>();
        List<String> allScopes = List.of("SCA", "SAST", "AGENT");
        List<String> activeScopes = "ALL".equalsIgnoreCase(scope) ? allScopes : List.of(scope.toUpperCase());
        currentScanId.set(scanId);
        try {
            // ---------- 阶段 1：拉取代码 ----------
            updateStage(record, "CLONE", "RUNNING", "正在拉取代码...");
            emit(scanId, "stage", Map.of("stage", "CLONE", "status", "RUNNING", "message", "正在拉取代码..."));
            Path root;
            try {
                root = projectService.syncCode(project);
                projectService.updateSyncStatus(project, "READY", "代码已就绪");
            } catch (Exception e) {
                projectService.updateSyncStatus(project, "ERROR", e.getMessage());
                throw new BusinessException(ErrorCodeEnum.CLONE_FAILED, "代码拉取失败: " + e.getMessage());
            }
            if (cancelled(scanId)) { finishStopped(scanId, record); return; }
            updateStage(record, "CLONE", "COMPLETED", "代码就绪: " + root);
            emit(scanId, "stage", Map.of("stage", "CLONE", "status", "COMPLETED"));

            // ---------- 阶段 2：环境探测 ----------
            updateStage(record, "DETECT", "RUNNING", "正在识别语言与依赖清单...");
            emit(scanId, "stage", Map.of("stage", "DETECT", "status", "RUNNING"));
            Map<String, Object> detect = detectProject(root);
            updateStage(record, "DETECT", "COMPLETED", "识别到 " + detect.get("languages") + " 种语言");
            emit(scanId, "stage", Map.of("stage", "DETECT", "status", "COMPLETED", "message", "识别到 " + detect.get("languages") + " 种语言"));
            record.setSummary(detect);

            // ---------- 阶段 3+：分析 ----------
            if (activeScopes.contains("SCA")) {
                findings.addAll(scaService.scan(root, project.getId(), scanId, this));
            } else {
                updateStage(record, "SCA", "COMPLETED", "已跳过");
            }
            if (cancelled(scanId)) { finishStopped(scanId, record); return; }

            if (activeScopes.contains("SAST")) {
                findings.addAll(sastRuleEngine.scan(root, project.getId(), scanId, this));
            } else {
                updateStage(record, "SAST", "COMPLETED", "已跳过");
            }
            if (cancelled(scanId)) { finishStopped(scanId, record); return; }

            if (activeScopes.contains("AGENT")) {
                updateStage(record, "AGENT", "RUNNING", "Code Review Agent 正在生成审查意见...");
                emit(scanId, "stage", Map.of("stage", "AGENT", "status", "RUNNING"));
                String review = reviewAgentService.review(project.getName(), findings);
                if (review != null) {
                    record.setAgentReview(review);
                    emit(scanId, "agent-review", Map.of("content", review));
                } else {
                    updateStage(record, "AGENT", "COMPLETED", "未配置 Agent API Key 或调用失败，已跳过");
                }
            } else {
                updateStage(record, "AGENT", "COMPLETED", "已跳过");
            }

            // ---------- 收尾 ----------
            Map<String, Object> summary = buildSummary(record, findings);
            record.setSummary(summary);
            record.setStatus("COMPLETED");
            record.setMessage("扫描完成");
            record.setFinishedAt(now());
            record.setDurationMs((int) (Instant.parse(record.getStartedAt()).until(Instant.now(), java.time.temporal.ChronoUnit.MILLIS)));
            saveFindings(scanId, findings);
            saveRecord(record);

            project.setLastScanId(scanId);
            project.setLastScanAt(Instant.now());
            project.setLastScanStatus("COMPLETED");
            project.setLastScanStats(summary);
            projectService.save(project);
            emit(scanId, "done", Map.of("scanId", scanId, "status", "COMPLETED", "summary", summary));
            log.info("扫描完成 {} - {} : {} 个发现", project.getName(), scanId, findings.size());
            // 邮件推送报告（多邮箱，默认 PDF 附件）
            if (project.isEmailNotify() && project.getEmails() != null && !project.getEmails().isEmpty()) {
                try {
                    mailService.sendScanReport(project, record, findings);
                } catch (Exception e) {
                    log.warn("邮件推送失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("扫描失败 {}: {}", scanId, e.getMessage(), e);
            record.setStatus("FAILED");
            record.setMessage(e.getMessage() == null ? "扫描失败" : e.getMessage());
            record.setFinishedAt(now());
            updateStage(record, currentStage(record), "FAILED", e.getMessage());
            saveRecord(record);
            project.setLastScanStatus("FAILED");
            project.setSyncMessage(e.getMessage());
            projectService.save(project);
            emit(scanId, "error", Map.of("scanId", scanId, "message", e.getMessage()));
        } finally {
            currentScanId.remove();
            cleanup(scanId);
        }
    }

    private String currentStage(ScanRecord record) {
        if (record.getStages() == null) return "CLONE";
        for (String s : List.of("CLONE", "DETECT", "SCA", "SAST", "AGENT")) {
            var st = record.getStages().get(s);
            if (st != null && "RUNNING".equals(st.getStatus())) {
                return s;
            }
        }
        return "CLONE";
    }

    private void finishStopped(String scanId, ScanRecord record) {
        record.setStatus("STOPPED");
        record.setMessage("扫描已手动停止");
        record.setFinishedAt(now());
        saveRecord(record);
        projectService.find(record.getProjectId()).ifPresent(p -> {
            p.setLastScanStatus("STOPPED");
            projectService.save(p);
        });
        emit(scanId, "done", Map.of("scanId", scanId, "status", "STOPPED"));
    }

    private void cleanup(String scanId) {
        AtomicBoolean flag = cancelFlags.remove(scanId);
        sinks.remove(scanId);
        runningProjectScan.entrySet().removeIf(e -> e.getValue().equals(scanId));
        if (flag != null) {
            flag.set(false);
        }
    }

    private boolean cancelled(String scanId) {
        AtomicBoolean flag = cancelFlags.get(scanId);
        return flag != null && flag.get();
    }

    public void stopScan(String scanId) {
        AtomicBoolean flag = cancelFlags.get(scanId);
        if (flag != null) {
            flag.set(true);
        }
    }

    // ============ 进度回调 ============

    @Override
    public void onStage(String stage, String status, String message) {
        String scanId = currentScanId.get();
        if (scanId == null) {
            return;
        }
        ScanRecord record = getScan(scanId);
        updateStage(record, stage, status, message);
    }

    private void updateStage(ScanRecord record, String stage, String status, String message) {
        if (record.getStages() == null || !(record.getStages() instanceof LinkedHashMap)) {
            record.setStages(new LinkedHashMap<>(record.getStages() == null ? Map.of() : record.getStages()));
        }
        ScanRecord.StageProgress sp = record.getStages().get(stage);
        if (sp == null) {
            sp = ScanRecord.StageProgress.builder().status(status).current(0).total(0).message(message).build();
        } else {
            sp.setStatus(status);
            sp.setMessage(message);
        }
        record.getStages().put(stage, sp);
        saveRecord(record);
        emit(record.getId(), "stage", Map.of("stage", stage, "status", status, "message", message));
    }

    private void updateProgress(String scanId, String stage, int current, int total, String message) {
        emit(scanId, "progress", Map.of("stage", stage, "current", current, "total", total, "message", message));
    }

    @Override
    public void onProgress(String stage, int current, int total, String message) {
        String scanId = currentScanId.get();
        if (scanId == null) {
            return;
        }
        updateProgress(scanId, stage, current, total, message);
    }

    private final ThreadLocal<String> currentScanId = new ThreadLocal<>();

    @Override
    public void onFinding(ScanFinding finding) {
        emit(finding.getScanId(), "finding", finding);
    }

    // ============ 查询 ============

    public List<ScanRecord> listScans(String projectId) {
        Predicate<ScanRecord> filter = projectId == null || projectId.isBlank()
                ? r -> true : r -> r.getProjectId().equals(projectId);
        List<ScanRecord> records = new ArrayList<>();
        for (java.nio.file.Path f : jsonStore.listJsonFiles(jsonStore.paths().scans)) {
            // 只读取扫描记录文件，跳过 <id>-findings.json
            String name = f.getFileName().toString();
            if (name.endsWith("-findings.json")) {
                continue;
            }
            ScanRecord record = jsonStore.read(f, ScanRecord.class);
            if (record != null) {
                records.add(record);
            }
        }
        return records.stream()
                .filter(filter)
                .sorted((a, b) -> (b.getStartedAt() == null ? "" : b.getStartedAt()).compareTo(a.getStartedAt() == null ? "" : a.getStartedAt()))
                .toList();
    }

    public ScanRecord getScan(String scanId) {
        ScanRecord record = jsonStore.read(jsonStore.paths().scans.resolve(scanId + ".json"), ScanRecord.class);
        if (record == null) {
            throw new BusinessException(ErrorCodeEnum.SCAN_NOT_FOUND);
        }
        return record;
    }

    public List<ScanFinding> getFindings(String scanId, String severity, String engine, String category, Integer limit) {
        ScanRecord record = getScan(scanId);
        Path file = jsonStore.paths().scans.resolve(scanId + "-findings.json");
        List<ScanFinding> findings = jsonStore.read(file, new com.fasterxml.jackson.core.type.TypeReference<List<ScanFinding>>() {
        });
        if (findings == null) {
            findings = List.of();
        }
        List<ScanFinding> result = new ArrayList<>(findings.stream()
                .filter(f -> severity == null || severity.isBlank() || severity.equalsIgnoreCase(f.getSeverity()))
                .filter(f -> engine == null || engine.isBlank() || engine.equalsIgnoreCase(f.getEngine()))
                .filter(f -> category == null || category.isBlank() || (f.getCategory() != null && f.getCategory().equalsIgnoreCase(category)))
                .toList());
        result.sort((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())));
        if (limit != null && limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    public ScanFinding getFinding(String scanId, String findingId) {
        return getFindings(scanId, null, null, null, null).stream()
                .filter(f -> f.getId().equals(findingId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCodeEnum.SCAN_NOT_FOUND, "漏洞记录不存在"));
    }

    private int sevRank(String sev) {
        return switch (sev == null ? "" : sev.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    // ============ SSE ============

    public Flux<Map<String, Object>> events(String scanId) {
        Sinks.Many<Map<String, Object>> sink = sinks.get(scanId);
        if (sink == null) {
            // 扫描已结束：从文件回放关键事件
            return Flux.fromIterable(replayFromFile(scanId));
        }
        return sink.asFlux();
    }

    private List<Map<String, Object>> replayFromFile(String scanId) {
        List<Map<String, Object>> events = new ArrayList<>();
        ScanRecord record = getScan(scanId);
        events.add(Map.of("type", "replay", "scanId", scanId, "status", record.getStatus()));
        if (record.getSummary() != null) {
            events.add(Map.of("type", "done", "scanId", scanId, "status", record.getStatus(), "summary", record.getSummary()));
        }
        return events;
    }

    private void emit(String scanId, String type, Object data) {
        Sinks.Many<Map<String, Object>> sink = sinks.get(scanId);
        if (sink != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("data", data);
            event.put("ts", System.currentTimeMillis());
            sink.tryEmitNext(event);
        }
    }

    // ============ 持久化 ============

    private void saveRecord(ScanRecord record) {
        jsonStore.write(jsonStore.paths().scans.resolve(record.getId() + ".json"), record);
    }

    private void saveFindings(String scanId, List<ScanFinding> findings) {
        jsonStore.write(jsonStore.paths().scans.resolve(scanId + "-findings.json"), findings);
    }

    private ScanRecord.StageProgress stage(String status) {
        return ScanRecord.StageProgress.builder().status(status).current(0).total(0).build();
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    // ============ 环境探测 ============

    private Map<String, Object> detectProject(Path root) {
        List<Path> files = fileScanner.listFiles(root);
        java.util.Set<String> langs = new java.util.HashSet<>();
        int manifests = 0;
        for (Path f : files) {
            String name = f.getFileName().toString();
            if (List.of("package.json", "pom.xml", "requirements.txt", "go.mod", "Gemfile", "composer.json").contains(name)) {
                manifests++;
            }
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String ext = name.substring(dot + 1).toLowerCase();
                String lang = switch (ext) {
                    case "java" -> "Java";
                    case "py" -> "Python";
                    case "js", "jsx", "ts", "tsx" -> "JavaScript/TypeScript";
                    case "go" -> "Go";
                    case "php" -> "PHP";
                    case "rb" -> "Ruby";
                    case "cs" -> "C#";
                    default -> null;
                };
                if (lang != null) {
                    langs.add(lang);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files.size());
        result.put("languages", String.join(", ", langs));
        result.put("manifests", manifests);
        return result;
    }

    private Map<String, Object> buildSummary(ScanRecord record, List<ScanFinding> findings) {
        Map<String, Object> summary = new LinkedHashMap<>();
        int critical = 0, high = 0, medium = 0, low = 0, info = 0;
        Map<String, Integer> byEngine = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (ScanFinding f : findings) {
            switch (f.getSeverity() == null ? "" : f.getSeverity().toUpperCase()) {
                case "CRITICAL" -> critical++;
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                case "LOW" -> low++;
                default -> info++;
            }
            byEngine.merge(f.getEngine(), 1, Integer::sum);
            byCategory.merge(f.getCategory(), 1, Integer::sum);
        }
        summary.put("total", findings.size());
        summary.put("critical", critical);
        summary.put("high", high);
        summary.put("medium", medium);
        summary.put("low", low);
        summary.put("info", info);
        summary.put("byEngine", byEngine);
        summary.put("byCategory", byCategory);
        summary.put("agentEnabled", reviewAgentService.isConfigured());
        return summary;
    }
}
