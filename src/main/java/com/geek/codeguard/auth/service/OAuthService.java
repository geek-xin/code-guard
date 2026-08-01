package com.geek.codeguard.auth.service;

import com.geek.codeguard.auth.model.User;
import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OAuthService {

    private final CodeGuardProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OAuthService(CodeGuardProperties props) {
        this.props = props;
        this.mapper = new ObjectMapper();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String githubAuthorizeUrl() {
        CodeGuardProperties.Auth.OAuth cfg = props.getAuth().getGithub();
        return "https://github.com/login/oauth/authorize?" + query(Map.of(
                "client_id", cfg.getClientId(),
                "redirect_uri", cfg.getRedirectUri(),
                "scope", "read:user repo",
                "state", UUID.randomUUID().toString()
        ));
    }

    public String gitlabAuthorizeUrl() {
        CodeGuardProperties.Auth.OAuth cfg = props.getAuth().getGitlab();
        return cfg.getBaseUrl() + "/oauth/authorize?" + query(Map.of(
                "client_id", cfg.getClientId(),
                "redirect_uri", cfg.getRedirectUri(),
                "response_type", "code",
                "scope", "read_api api",
                "state", UUID.randomUUID().toString()
        ));
    }

    /** 用授权码换取用户信息并返回 OAuth 用户实体 */
    public User exchangeGithub(String code) {
        CodeGuardProperties.Auth.OAuth cfg = props.getAuth().getGithub();
        String token = postForm("https://github.com/login/oauth/access_token", Map.of(
                "client_id", cfg.getClientId(),
                "client_secret", cfg.getClientSecret(),
                "code", code,
                "redirect_uri", cfg.getRedirectUri()
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
        CodeGuardProperties.Auth.OAuth cfg = props.getAuth().getGitlab();
        String token = postForm(cfg.getBaseUrl() + "/oauth/token", Map.of(
                "client_id", cfg.getClientId(),
                "client_secret", cfg.getClientSecret(),
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", cfg.getRedirectUri()
        ));
        JsonNode user = getJson(cfg.getBaseUrl() + "/api/v4/user", token);
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
}
