package com.geek.codeguard.project.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.project.model.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远程仓库分支查询：通过 GitHub / GitLab REST API 拉取分支列表，
 * 供添加项目时选择「拉取哪个分支」。
 * - GitHub：公开仓库无需认证，私有仓库使用 Personal Access Token（Authorization: Bearer）。
 * - GitLab：API 需要认证，必须携带 Token（PRIVATE-TOKEN），否则无法获取分支。
 */
@Service
@Slf4j
public class GitRemoteService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 10;

    private static final Pattern HTTPS_URL = Pattern.compile("^https?://([^/]+)/(.+?)(?:\\.git)?/?$");
    private static final Pattern SSH_URL = Pattern.compile("^git@([^:]+):(.+?)(?:\\.git)?/?$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 分支列表结果：defaultBranch 为远端默认分支（尽量探测），branches 去重保序 */
    public record BranchList(String defaultBranch, List<String> branches) {
    }

    /**
     * 查询远端分支列表。
     *
     * @param source  GITHUB / GITLAB
     * @param repoUrl 仓库 HTTPS 或 SSH 地址
     * @param token   访问令牌（GitHub 私有仓库可选；GitLab 必填）
     */
    public BranchList listBranches(String source, String repoUrl, String token) {
        if (source == null || source.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "缺少源码来源");
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "缺少仓库地址");
        }
        return switch (source.trim().toUpperCase()) {
            case Project.SOURCE_GITHUB -> listGithubBranches(repoUrl, blankToNull(token));
            case Project.SOURCE_GITLAB -> listGitlabBranches(repoUrl, blankToNull(token));
            default -> throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "不支持的分支查询来源: " + source);
        };
    }

    // ---------------- GitHub ----------------

    private BranchList listGithubBranches(String repoUrl, String token) {
        String[] parts = parseGithub(repoUrl);
        String owner = parts[0];
        String repo = parts[1];
        String apiBase = "https://api.github.com";
        if (!"github.com".equalsIgnoreCase(parts[2])) {
            // GitHub Enterprise：{scheme}://{host}/api/v3（内网常用 http）
            apiBase = scheme(repoUrl) + "://" + parts[2] + "/api/v3";
        }

        String defaultBranch = null;
        try {
            JsonNode repoInfo = getJson(apiBase + "/repos/" + owner + "/" + repo,
                    token, "github");
            defaultBranch = repoInfo.path("default_branch").asText(null);
        } catch (BusinessException e) {
            if (e.getCode().equals(ErrorCodeEnum.REMOTE_FETCH_FAILED.getCode())) {
                // 仓库信息接口失败（404/403 等），继续尝试分支接口以获得更准确的错误
                log.warn("获取 GitHub 仓库信息失败: {}", e.getMessage());
            } else {
                throw e;
            }
        }

        Set<String> branches = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<String> pageBranches = getGithubBranchPage(apiBase, owner, repo, token, page);
            branches.addAll(pageBranches);
            if (pageBranches.size() < PER_PAGE) {
                break;
            }
        }
        if (branches.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.REMOTE_FETCH_FAILED,
                    "未获取到 GitHub 分支，请确认仓库地址与 Token 是否正确");
        }
        return buildResult(defaultBranch, branches);
    }

    private List<String> getGithubBranchPage(String apiBase, String owner, String repo, String token, int page) {
        JsonNode node = getJson(apiBase + "/repos/" + owner + "/" + repo
                + "/branches?per_page=" + PER_PAGE + "&page=" + page, token, "github");
        List<String> names = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode b : node) {
                String name = b.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 解析 GitHub 仓库地址。
     * 支持：https://github.com/owner/repo(.git)、git@github.com:owner/repo(.git)
     *
     * @return [owner, repo, host]
     */
    static String[] parseGithub(String url) {
        Matcher m = HTTPS_URL.matcher(url.trim());
        String host;
        String path;
        if (m.matches()) {
            host = m.group(1);
            path = m.group(2);
        } else {
            m = SSH_URL.matcher(url.trim());
            if (!m.matches()) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST,
                        "无法识别的 GitHub 仓库地址: " + url);
            }
            host = m.group(1);
            path = m.group(2);
        }
        String[] seg = path.split("/");
        if (seg.length < 2) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST,
                    "GitHub 仓库地址缺少 owner/repo: " + url);
        }
        return new String[]{seg[seg.length - 2], seg[seg.length - 1], host};
    }

    // ---------------- GitLab ----------------

    private BranchList listGitlabBranches(String repoUrl, String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.GIT_AUTH_REQUIRED,
                    "GitLab 需要提供访问令牌（Token）才能获取分支列表，请在「访问令牌」中填写 GitLab Personal Access Token");
        }

        String[] parts = parseGitlab(repoUrl);
        // 保留仓库地址的协议（内网 GitLab 常用 http://）
        String baseUrl = scheme(repoUrl) + "://" + parts[0];
        String projectPath = parts[1];
        String encoded = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);

        String defaultBranch = null;
        try {
            JsonNode projectInfo = getJson(baseUrl + "/api/v4/projects/" + encoded, token, "gitlab");
            defaultBranch = projectInfo.path("default_branch").asText(null);
        } catch (BusinessException e) {
            log.warn("获取 GitLab 项目信息失败: {}", e.getMessage());
        }

        Set<String> branches = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<String> pageBranches = getGitlabBranchPage(baseUrl, encoded, token, page);
            branches.addAll(pageBranches);
            if (pageBranches.size() < PER_PAGE) {
                break;
            }
        }
        if (branches.isEmpty()) {
            throw new BusinessException(ErrorCodeEnum.REMOTE_FETCH_FAILED,
                    "未获取到 GitLab 分支，请确认仓库地址与 Token 是否正确");
        }
        return buildResult(defaultBranch, branches);
    }

    private List<String> getGitlabBranchPage(String baseUrl, String encodedProject, String token, int page) {
        JsonNode node = getJson(baseUrl + "/api/v4/projects/" + encodedProject
                + "/repository/branches?per_page=" + PER_PAGE + "&page=" + page, token, "gitlab");
        List<String> names = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode b : node) {
                String name = b.path("name").asText(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 解析 GitLab 仓库地址。
     * 支持：https://host/group/sub/repo(.git)、git@host:group/sub/repo(.git)
     *
     * @return [host, group/sub/repo]
     */
    static String[] parseGitlab(String url) {
        Matcher m = HTTPS_URL.matcher(url.trim());
        String host;
        String path;
        if (m.matches()) {
            host = m.group(1);
            path = m.group(2);
        } else {
            m = SSH_URL.matcher(url.trim());
            if (!m.matches()) {
                throw new BusinessException(ErrorCodeEnum.BAD_REQUEST,
                        "无法识别的 GitLab 仓库地址: " + url);
            }
            host = m.group(1);
            path = m.group(2);
        }
        if (path.isBlank()) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST,
                    "GitLab 仓库地址缺少项目路径: " + url);
        }
        return new String[]{host, path};
    }

    // ---------------- common ----------------

    private BranchList buildResult(String defaultBranch, Set<String> branches) {
        List<String> list = new ArrayList<>(branches);
        if (defaultBranch != null && !defaultBranch.isBlank()) {
            String def = defaultBranch;
            // 默认分支排最前
            list.sort(Comparator
                    .comparing((String b) -> b.equals(def) ? 0 : 1)
                    .thenComparing(String::compareToIgnoreCase));
        } else if (!list.isEmpty()) {
            defaultBranch = list.get(0);
        }
        return new BranchList(defaultBranch, list);
    }

    private JsonNode getJson(String url, String token, String kind) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET();
        if (token != null && !token.isBlank()) {
            if ("gitlab".equals(kind)) {
                builder.header("PRIVATE-TOKEN", token);
            } else {
                builder.header("Authorization", "Bearer " + token);
                builder.header("X-GitHub-Api-Version", "2022-11-28");
            }
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 401 || status == 403) {
                throw new BusinessException(ErrorCodeEnum.GIT_AUTH_REQUIRED,
                        ("gitlab".equals(kind) ? "GitLab" : "GitHub")
                                + " 认证失败，请检查访问令牌（Token）是否正确或是否已过期");
            }
            if (status == 404) {
                throw new BusinessException(ErrorCodeEnum.REMOTE_FETCH_FAILED,
                        ("gitlab".equals(kind) ? "GitLab" : "GitHub")
                                + " 项目不存在或无权访问，请检查仓库地址与 Token");
            }
            if (status >= 400) {
                throw new BusinessException(ErrorCodeEnum.REMOTE_FETCH_FAILED,
                        ("gitlab".equals(kind) ? "GitLab" : "GitHub")
                                + " 请求失败: HTTP " + status);
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.REMOTE_FETCH_FAILED,
                    "请求远程仓库 API 失败: " + rootMessage(e));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 仓库地址协议：http:// 保留 http，其余（https/ssh）按 https 处理 */
    private static String scheme(String url) {
        return url != null && url.trim().startsWith("http://") ? "http" : "https";
    }

    private static String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg.split("\n")[0];
    }
}
