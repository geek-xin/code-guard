package com.geek.codeguard.auth.service;

import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.settings.model.Settings;
import com.geek.codeguard.settings.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OAuthService {

    private final SettingsService settingsService;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OAuthService(SettingsService settingsService) {
        this.settingsService = settingsService;
        this.mapper = new ObjectMapper();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String githubAuthorizeUrl(boolean remember) {
        Settings.OAuth cfg = settingsService.effectiveOAuth();
        if (!notBlank(cfg.getGithubClientId()) || !notBlank(cfg.getGithubClientSecret())) {
            throw new BusinessException(ErrorCodeEnum.OAUTH_FAILED,
                    "GitHub OAuth 未配置，请在「设置 → 第三方登录」中填写 Client ID 与 Client Secret");
        }
        return "https://github.com/login/oauth/authorize?" + query(Map.of(
                "client_id", cfg.getGithubClientId(),
                "redirect_uri", cfg.getGithubRedirectUri(),
                "scope", "read:user repo",
                "state", buildState(remember)
        ));
    }

    public String gitlabAuthorizeUrl(boolean remember) {
        Settings.OAuth cfg = settingsService.effectiveOAuth();
        if (!notBlank(cfg.getGitlabClientId()) || !notBlank(cfg.getGitlabClientSecret())) {
            throw new BusinessException(ErrorCodeEnum.OAUTH_FAILED,
                    "GitLab OAuth 未配置，请在「设置 → 第三方登录」中填写 Client ID 与 Client Secret");
        }
        return cfg.getGitlabBaseUrl() + "/oauth/authorize?" + query(Map.of(
                "client_id", cfg.getGitlabClientId(),
                "redirect_uri", cfg.getGitlabRedirectUri(),
                "response_type", "code",
                "scope", "read_api api",
                "state", buildState(remember)
        ));
    }

    /** state = base64url({"r":0/1,"n":nonce})，回调时还原 remember 偏好 */
    private String buildState(boolean remember) {
        try {
            String payload = "{\"r\":" + (remember ? 1 : 0) + ",\"n\":\"" + UUID.randomUUID() + "\"}";
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    public boolean parseRemember(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(state);
            JsonNode node = mapper.readTree(raw);
            return node.path("r").asInt(0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用授权码换取用户信息并返回 OAuth 用户实体 */
    public User exchangeGithub(String code) {
        Settings.OAuth cfg = settingsService.effectiveOAuth();
        String token = postForm("https://github.com/login/oauth/access_token", Map.of(
                "client_id", cfg.getGithubClientId(),
                "client_secret", cfg.getGithubClientSecret(),
                "code", code,
                "redirect_uri", cfg.getGithubRedirectUri()
        ));
        JsonNode user = getJson("https://api.github.com/user", token);
        return User.builder()
                .username(user.path("login").asText("github_" + UUID.randomUUID().toString().substring(0, 8)))
                .provider("GITHUB")
                .providerUserId(user.path("id").asText())
                .displayName(user.path("name").asText(null) == null ? user.path("login").asText() : user.path("name").asText())
                .avatarUrl(user.path("avatar_url").asText(null))
                .email(user.path("email").asText(null))
                .accessToken(token)
                .build();
    }

    public User exchangeGitlab(String code) {
        Settings.OAuth cfg = settingsService.effectiveOAuth();
        String token = postForm(cfg.getGitlabBaseUrl() + "/oauth/token", Map.of(
                "client_id", cfg.getGitlabClientId(),
                "client_secret", cfg.getGitlabClientSecret(),
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", cfg.getGitlabRedirectUri()
        ));
        JsonNode user = getJson(cfg.getGitlabBaseUrl() + "/api/v4/user", token);
        return User.builder()
                .username(user.path("username").asText("gitlab_" + UUID.randomUUID().toString().substring(0, 8)))
                .provider("GITLAB")
                .providerUserId(user.path("id").asText())
                .displayName(user.path("name").asText("GitLab 用户"))
                .avatarUrl(user.path("avatar_url").asText(null))
                .email(user.path("email").asText(null))
                .accessToken(token)
                .build();
    }

    private String postForm(String url, Map<String, String> form) {
        try {
            String body = form.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(resp.body());
            String accessToken = node.path("access_token").asText(null);
            if (accessToken == null) {
                log.error("OAuth token 交换失败: {}", resp.body());
                throw new BusinessException(ErrorCodeEnum.OAUTH_FAILED, "OAuth 授权码交换失败: " + node.path("error_description").asText(node.path("error").asText("unknown")));
            }
            return accessToken;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodeEnum.OAUTH_FAILED, "OAuth 请求失败: " + e.getMessage());
        }
    }

    private JsonNode getJson(String url, String accessToken) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new BusinessException(ErrorCodeEnum.OAUTH_FAILED, "OAuth 获取用户信息失败: HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new BusinessException(ErrorCodeEnum.OAUTH_FAILED, "OAuth 请求失败: " + e.getMessage());
        }
    }

    private String query(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
