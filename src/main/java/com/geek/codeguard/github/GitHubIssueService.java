package com.geek.codeguard.github;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.project.model.Project;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import com.geek.codeguard.scan.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将扫描发现提交为 GitHub Issue（汇总报告 + Top 漏洞明细）。
 */
@Service
@Slf4j
public class GitHubIssueService {

    private static final String API = "https://api.github.com";
    private static final int MAX_BODY = 60000;

    private final ReportService reportService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GitHubIssueService(ReportService reportService) {
        this.reportService = reportService;
    }

    public Map<String, Object> createIssue(Project project, ScanRecord scan, List<ScanFinding> findings, String token) {
        if (!"GITHUB".equals(project.getSource())) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "仅 GitHub 来源的项目支持提交 Issue");
        }
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.AGENT_FAILED,
                    "缺少 GitHub 访问令牌：请在项目配置中填写 Token，或使用 GitHub 账号登录");
        }
        String owner = extractOwner(project.getRepoUrl());
        String repo = extractRepo(project.getRepoUrl());
        if (owner == null || repo == null) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "无法从仓库地址解析 owner/repo: " + project.getRepoUrl());
        }
        try {
            Map<String, Object> summary = scan.getSummary() == null ? Map.of() : scan.getSummary();
            String title = "[code-guard] 安全扫描报告：" + scan.getProjectName()
                    + "（共 " + summary.get("total") + " 个问题）";
            String body = reportService.buildMarkdown(scan, findings.size() > 60 ? findings.subList(0, 60) : findings);
            if (body.length() > MAX_BODY) {
                body = body.substring(0, MAX_BODY) + "\n\n...（内容已截断，请查看平台完整报告）";
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("body", body);
            payload.put("labels", List.of("security", "code-guard"));

            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/repos/" + owner + "/" + repo + "/issues"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 201) {
                String detail = "";
                try {
                    detail = mapper.readTree(resp.body()).path("message").asText("");
                } catch (Exception ignored) {
                }
                throw new BusinessException(ErrorCodeEnum.AGENT_FAILED, "GitHub 创建 Issue 失败 (HTTP " + resp.statusCode() + "): " + detail);
            }
            JsonNode node = mapper.readTree(resp.body());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("number", node.path("number").asInt());
            result.put("htmlUrl", node.path("html_url").asText());
            result.put("title", node.path("title").asText());
            result.put("state", node.path("state").asText());
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.AGENT_FAILED, "提交 GitHub Issue 失败: " + e.getMessage());
        }
    }

    private String extractOwner(String url) {
        if (url == null) return null;
        String u = url.replace("https://github.com/", "").replace("git@github.com:", "").replace(".git", "");
        String[] parts = u.split("/");
        return parts.length >= 2 ? parts[0] : null;
    }

    private String extractRepo(String url) {
        if (url == null) return null;
        String u = url.replace("https://github.com/", "").replace("git@github.com:", "").replace(".git", "");
        String[] parts = u.split("/");
        return parts.length >= 2 ? parts[1] : null;
    }
}
