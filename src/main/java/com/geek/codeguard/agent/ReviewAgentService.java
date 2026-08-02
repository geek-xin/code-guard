package com.geek.codeguard.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.settings.model.Settings;
import com.geek.codeguard.settings.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Code Review Agent：调用 OpenAI 兼容的 chat completions 接口，
 * 基于扫描结果生成修复方案与重点文件的人工智能审查意见。
 * 未配置 API Key 时自动降级跳过（不影响扫描）。
 */
@Service
@Slf4j
public class ReviewAgentService {

    private final CodeGuardProperties props;
    private final SettingsService settingsService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ReviewAgentService(CodeGuardProperties props, SettingsService settingsService) {
        this.props = props;
        this.settingsService = settingsService;
    }

    public boolean isConfigured() {
        Settings.Agent cfg = settingsService.effectiveAgent();
        return cfg.getEnabled() != null && cfg.getEnabled()
                && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    /** 返回 Markdown 审查报告；失败或未配置时返回 null */
    public String review(String projectName, List<ScanFinding> findings) {
        if (!isConfigured()) {
            return null;
        }
        try {
            Settings.Agent cfg = settingsService.effectiveAgent();
            String prompt = buildPrompt(projectName, findings);
            String endpoint = cfg.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.getModel());
            body.put("temperature", 0.2);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt)
            ));
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(props.getAgent().getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Review Agent 调用失败: HTTP {}", resp.statusCode());
                return null;
            }
            var node = mapper.readTree(resp.body());
            String content = node.path("choices").path(0).path("message").path("content").asText(null);
            return content == null || content.isBlank() ? null : content;
        } catch (Exception e) {
            log.warn("Review Agent 异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 流式审查：stream=true 逐 token 回调 onDelta（用于前端实时打印思考过程）。
     * 返回完整 Markdown 内容。
     */
    public String streamReview(String projectName, List<ScanFinding> findings, Consumer<String> onDelta) {
        return streamReview(projectName, findings, onDelta, null);
    }

    /** cancelled 非空时：取消监控线程每 500ms 检查，取消则关闭响应流中断读取 */
    public String streamReview(String projectName, List<ScanFinding> findings, Consumer<String> onDelta,
                               AtomicBoolean cancelled) {
        if (!isConfigured()) {
            return null;
        }
        ScheduledExecutorService monitor = null;
        try {
            Settings.Agent cfg = settingsService.effectiveAgent();
            String prompt = buildPrompt(projectName, findings);
            String endpoint = cfg.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", cfg.getModel());
            body.put("stream", true);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt)
            ));
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(props.getAgent().getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("Agent 流式调用失败: HTTP {} - {}", resp.statusCode(), err);
                return null;
            }
            // 取消监控：取消时关闭响应流，readLine 立即中断
            if (cancelled != null) {
                monitor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "agent-cancel-monitor");
                    t.setDaemon(true);
                    return t;
                });
                InputStream respBody = resp.body();
                monitor.scheduleAtFixedRate(() -> {
                    if (cancelled.get()) {
                        try {
                            respBody.close();
                        } catch (Exception ignored) {
                        }
                    }
                }, 300, 300, TimeUnit.MILLISECONDS);
            }
            StringBuilder full = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    try {
                        var node = mapper.readTree(data);
                        var deltaNode = node.path("choices").path(0).path("delta");
                        // 推理模型的思考过程（deepseek 等）也实时推送
                        String reasoning = deltaNode.path("reasoning_content").asText(null);
                        if (reasoning != null && !reasoning.isEmpty() && onDelta != null) {
                            onDelta.accept(reasoning);
                        }
                        String delta = deltaNode.path("content").asText(null);
                        if (delta != null && !delta.isEmpty()) {
                            full.append(delta);
                            if (onDelta != null) {
                                onDelta.accept(delta);
                            }
                        }
                    } catch (Exception ignored) {
                        // 忽略非 JSON 行
                    }
                }
            }
            return full.toString();
        } catch (Exception e) {
            log.debug("Agent 流式调用结束: {}", e.getMessage());
            return null;
        } finally {
            if (monitor != null) {
                monitor.shutdownNow();
            }
        }
    }

    private static final String SYSTEM_PROMPT = """
            你是一名资深应用安全工程师（AppSec），负责对代码扫描结果进行人工复核并给出可执行的修复方案。
            要求：
            1. 用中文输出，使用 Markdown 格式。
            2. 结构：漏洞总体评估、按严重程度分类的修复建议、优先修复清单（Top 5）、长期加固建议。
            3. 每条建议必须给出：问题、风险、具体修复步骤（含代码示例）。
            4. 如发现误报，明确说明并给出理由。
            5. 数量一致性：输入中给出了各严重级别的精确统计（如 CRITICAL 2 个）。
               你在每个级别标题下展开的具体问题条目数必须与该统计完全一致，逐个列出，
               严禁出现标题声明 N 个但只列出少于 N 条的遗漏，也不得把多条合并为一条。
            """;

    private String buildPrompt(String projectName, List<ScanFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(projectName).append("\n");
        sb.append("本次扫描发现 ").append(findings.size()).append(" 个问题。\n");
        // 精确统计各严重级别数量
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ScanFinding f : findings) {
            String sev = f.getSeverity() == null ? "UNKNOWN" : f.getSeverity().toUpperCase();
            counts.merge(sev, 1, Integer::sum);
        }
        sb.append("严重级别统计（务必在对应标题下逐条列出与数量一致的条目）：");
        sb.append(counts.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue() + " 个")
                .reduce((a, b) -> a + "，" + b).orElse("无"));
        sb.append("\n\n按严重程度排序的完整问题清单如下：\n\n");
        List<ScanFinding> sorted = new ArrayList<>(findings);
        sorted.sort((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())));
        int limit = Math.min(sorted.size(), 30);
        for (int i = 0; i < limit; i++) {
            ScanFinding f = sorted.get(i);
            sb.append(i + 1).append(". [").append(f.getSeverity()).append("] ")
                    .append(f.getTitle()).append("\n");
            sb.append("   - 引擎: ").append(f.getEngine()).append(" / 类别: ").append(f.getCategory()).append("\n");
            if (f.getVulnId() != null) {
                sb.append("   - 编号: ").append(f.getVulnId()).append("\n");
            }
            if (f.getFile() != null) {
                sb.append("   - 位置: ").append(f.getFile());
                if (f.getLine() != null) {
                    sb.append(":").append(f.getLine());
                }
                sb.append("\n");
            }
            if (f.getDependencyName() != null) {
                sb.append("   - 依赖: ").append(f.getDependencyName()).append(" ")
                        .append(f.getDependencyVersion());
                if (f.getFixedVersion() != null) {
                    sb.append(" -> 修复版本 ").append(f.getFixedVersion());
                }
                sb.append("\n");
            }
            if (f.getDescription() != null && f.getDescription().length() <= 500) {
                sb.append("   - 描述: ").append(f.getDescription()).append("\n");
            }
            if (f.getSolution() != null) {
                sb.append("   - 已知解决方案: ").append(f.getSolution()).append("\n");
            }
            if (f.getCodeSnippet() != null) {
                sb.append("   - 代码片段: ```\n").append(f.getCodeSnippet()).append("\n```\n");
            }
            sb.append("\n");
        }
        return sb.toString();
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
}
