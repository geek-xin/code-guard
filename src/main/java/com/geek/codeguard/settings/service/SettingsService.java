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

        Map<String, Object> oauth = new LinkedHashMap<>();
        oauth.put("githubConfigured", notBlank(props.getAuth().getGithub().getClientId()));
        oauth.put("gitlabConfigured", notBlank(props.getAuth().getGitlab().getClientId()));
        oauth.put("gitlabBaseUrl", props.getAuth().getGitlab().getBaseUrl());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
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
