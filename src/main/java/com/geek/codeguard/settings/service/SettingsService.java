package com.geek.codeguard.settings.service;

import com.geek.codeguard.common.enums.ErrorCodeEnum;
import com.geek.codeguard.common.exception.BusinessException;
import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.settings.model.Settings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局设置存储：读写 config/settings.json，供 Agent / OAuth 等运行时读取。
 */
@Service
@Slf4j
public class SettingsService {

    private final JsonStore jsonStore;
    private final CodeGuardProperties props;

    public SettingsService(JsonStore jsonStore, CodeGuardProperties props) {
        this.jsonStore = jsonStore;
        this.props = props;
    }

    public synchronized Settings get() {
        Settings s = jsonStore.read(file(), Settings.class);
        return s == null ? Settings.builder().agent(Settings.Agent.builder().build()).build() : s;
    }

    public synchronized void update(Settings update) {
        Settings current = get();
        if (update.getSmtp() != null) {
            Settings.Smtp cur = current.getSmtp() == null ? Settings.Smtp.builder().build() : current.getSmtp();
            Settings.Smtp next = Settings.Smtp.builder()
                    .enabled(update.getSmtp().getEnabled() != null ? update.getSmtp().getEnabled() : cur.getEnabled())
                    .host(notBlank(update.getSmtp().getHost()) ? update.getSmtp().getHost().trim() : cur.getHost())
                    .port(update.getSmtp().getPort() != null ? update.getSmtp().getPort() : cur.getPort())
                    .username(notBlank(update.getSmtp().getUsername()) ? update.getSmtp().getUsername().trim() : cur.getUsername())
                    .from(notBlank(update.getSmtp().getFrom()) ? update.getSmtp().getFrom().trim() : cur.getFrom())
                    .ssl(update.getSmtp().getSsl() != null ? update.getSmtp().getSsl() : cur.getSsl())
                    .password(cur.getPassword())
                    .build();
            String pwd = update.getSmtp().getPassword();
            if (notBlank(pwd) && !"******".equals(pwd.trim())) {
                next.setPassword(pwd.trim());
            }
            current.setSmtp(next);
        }
        if (update.getAgent() != null) {
            Settings.Agent cur = current.getAgent() == null ? Settings.Agent.builder().build() : current.getAgent();
            Settings.Agent next = Settings.Agent.builder()
                    .enabled(update.getAgent().getEnabled() != null ? update.getAgent().getEnabled() : cur.getEnabled())
                    .baseUrl(notBlank(update.getAgent().getBaseUrl()) ? update.getAgent().getBaseUrl().trim() : cur.getBaseUrl())
                    .model(notBlank(update.getAgent().getModel()) ? update.getAgent().getModel().trim() : cur.getModel())
                    .apiKey(cur.getApiKey())
                    .build();
            // API Key：传入非空则更新；传入 "******" 或空保留原值
            String key = update.getAgent().getApiKey();
            if (notBlank(key) && !"******".equals(key.trim())) {
                next.setApiKey(key.trim());
            }
            current.setAgent(next);
        }
        jsonStore.write(file(), current);
        log.info("全局配置已更新: {}", Instant.now());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private Path file() {
        return jsonStore.paths().data.resolve("settings.json");
    }

    /** SMTP 是否已完整配置（可用于发送邮件） */
    public boolean smtpReady() {
        Settings.Smtp s = get().getSmtp();
        return s != null && Boolean.TRUE.equals(s.getEnabled())
                && notBlank(s.getHost()) && s.getPort() != null
                && notBlank(s.getUsername()) && notBlank(s.getPassword());
    }

    public Settings.Smtp smtp() {
        return get().getSmtp();
    }

    /** Agent 生效配置（settings 优先，缺省回退 application.yml） */
    public Settings.Agent effectiveAgent() {
        Settings.Agent s = get().getAgent();
        CodeGuardProperties.Agent p = props.getAgent();
        return Settings.Agent.builder()
                .enabled(s != null && s.getEnabled() != null ? s.getEnabled() : p.isEnabled())
                .baseUrl(notBlank(s == null ? null : s.getBaseUrl()) ? s.getBaseUrl() : p.getBaseUrl())
                .apiKey(notBlank(s == null ? null : s.getApiKey()) ? s.getApiKey() : p.getApiKey())
                .model(notBlank(s == null ? null : s.getModel()) ? s.getModel() : p.getModel())
                .build();
    }

    /** 脱敏视图（不含任何密钥明文） */
    public Map<String, Object> view() {
        Settings s = get();
        Settings.Agent a = effectiveAgent();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("enabled", a.getEnabled());
        agent.put("baseUrl", a.getBaseUrl());
        agent.put("model", a.getModel());
        agent.put("apiKeyConfigured", notBlank(a.getApiKey()));
        agent.put("source", notBlank(s.getAgent() != null ? s.getAgent().getApiKey() : null) ? "settings" : "env");

        Settings.Smtp smtp = get().getSmtp();
        Map<String, Object> smtpView = new LinkedHashMap<>();
        smtpView.put("enabled", smtp != null && smtp.getEnabled() != null ? smtp.getEnabled() : false);
        smtpView.put("host", smtp == null ? "" : smtp.getHost());
        smtpView.put("port", smtp == null ? null : smtp.getPort());
        smtpView.put("username", smtp == null ? "" : smtp.getUsername());
        smtpView.put("from", smtp == null ? "" : smtp.getFrom());
        smtpView.put("ssl", smtp != null && smtp.getSsl() != null ? smtp.getSsl() : true);
        smtpView.put("passwordConfigured", smtp != null && notBlank(smtp.getPassword()));
        smtpView.put("ready", smtpReady());

        Map<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("githubConfigured", notBlank(props.getAuth().getGithub().getClientId()));
        oauth.put("gitlabConfigured", notBlank(props.getAuth().getGitlab().getClientId()));
        oauth.put("gitlabBaseUrl", props.getAuth().getGitlab().getBaseUrl());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        result.put("smtp", smtpView);
        result.put("oauth", oauth);
        return result;
    }

    public void validateAgent(Settings.Agent agent) {
        if (agent.getBaseUrl() != null && !agent.getBaseUrl().isBlank()
                && !agent.getBaseUrl().startsWith("http://") && !agent.getBaseUrl().startsWith("https://")) {
            throw new BusinessException(ErrorCodeEnum.BAD_REQUEST, "Base URL 需以 http:// 或 https:// 开头");
        }
    }
}
