package com.geek.codeguard.settings.controller;

import com.geek.codeguard.common.result.Result;
import com.geek.codeguard.settings.model.Settings;
import com.geek.codeguard.settings.service.SettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Data
    public static class SmtpRequest {
        private Boolean enabled;
        @Size(max = 256)
        private String host;
        private Integer port;
        @Size(max = 256)
        private String username;
        @Size(max = 2048)
        private String password;
        @Size(max = 256)
        private String from;
        private Boolean ssl;
        private java.util.List<String> defaultRecipients;
    }

    @Data
    public static class AgentRequest {
        private Boolean enabled;
        @Size(max = 512)
        private String baseUrl;
        @Size(max = 2048)
        private String apiKey;
        @Size(max = 128)
        private String model;
    }

    /** 测试 Agent 连接（使用请求体中的表单值，未填则用已保存配置；不保存） */
    @PostMapping("/agent/test")
    public Mono<Result<Map<String, Object>>> testAgent(@RequestBody(required = false) AgentRequest req) {
        return Mono.fromCallable(() -> {
            Settings.Agent candidate = null;
            if (req != null) {
                candidate = Settings.Agent.builder()
                        .enabled(req.getEnabled())
                        .baseUrl(req.getBaseUrl())
                        .apiKey(req.getApiKey())
                        .model(req.getModel())
                        .build();
            }
            return settingsService.testAgent(candidate);
        }).map(Result::success);
    }

    @Data
    public static class SettingsRequest {
        private AgentRequest agent;
        private SmtpRequest smtp;
    }

    /** 全局配置（脱敏视图） */
    @GetMapping
    public Mono<Result<Map<String, Object>>> get() {
        return Mono.just(Result.success(settingsService.view()));
    }

    /** 更新全局配置（热生效） */
    @PutMapping
    public Mono<Result<Map<String, Object>>> update(@Valid @RequestBody SettingsRequest req) {
        return Mono.fromCallable(() -> {
            Settings update = new Settings();
            if (req.getSmtp() != null) {
                Settings.Smtp smtp = Settings.Smtp.builder()
                        .enabled(req.getSmtp().getEnabled())
                        .host(req.getSmtp().getHost())
                        .port(req.getSmtp().getPort())
                        .username(req.getSmtp().getUsername())
                        .password(req.getSmtp().getPassword())
                        .from(req.getSmtp().getFrom())
                        .ssl(req.getSmtp().getSsl())
                        .defaultRecipients(req.getSmtp().getDefaultRecipients())
                        .build();
                update.setSmtp(smtp);
            }
            if (req.getAgent() != null) {
                Settings.Agent agent = Settings.Agent.builder()
                        .enabled(req.getAgent().getEnabled())
                        .baseUrl(req.getAgent().getBaseUrl())
                        .apiKey(req.getAgent().getApiKey())
                        .model(req.getAgent().getModel())
                        .build();
                settingsService.validateAgent(agent);
                update.setAgent(agent);
            }
            settingsService.update(update);
            return settingsService.view();
        }).map(Result::success);
    }
}
