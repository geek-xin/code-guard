package com.geek.codeguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "codeguard")
@Data
public class CodeGuardProperties {

    private String dataDir = "./config";
    private String workspaceDir = "./config/workspace";
    private String tokenSecret = "codeguard-dev-secret";
    private long tokenTtlHours = 72;
    private long tokenRememberHours = 720;
    private int scanConcurrency = 4;
    private int sastThreads = 4;
    private int scaOsvConcurrency = 6;
    private int maxFileKb = 2048;
    private List<String> ignorePatterns = new ArrayList<>();
    /** 平台自身仓库（用于问题反馈） */
    private String githubRepoUrl = "https://github.com/geek-xin/code-guard";
    private Auth auth = new Auth();
    private Sca sca = new Sca();
    private Agent agent = new Agent();
    private Scheduler scheduler = new Scheduler();

    @Data
    public static class Auth {
        private OAuth github = new OAuth();
        private OAuth gitlab = new OAuth();

        @Data
        public static class OAuth {
            private String clientId = "";
            private String clientSecret = "";
            private String redirectUri = "";
            private String baseUrl = "https://github.com";
        }
    }

    @Data
    public static class Sca {
        private boolean osvEnabled = true;
        private int osvTimeoutMs = 8000;
        private List<String> dbFiles = new ArrayList<>();
    }

    @Data
    public static class Agent {
        private boolean enabled = true;
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private int timeoutMs = 60000;
    }

    @Data
    public static class Scheduler {
        private long pollIntervalMs = 30000;
    }

    public Paths resolvePaths() {
        return new Paths();
    }

    /** 常用目录的便捷访问 */
    public class Paths {
        public final java.nio.file.Path data = java.nio.file.Path.of(dataDir).toAbsolutePath().normalize();
        public final java.nio.file.Path workspace = java.nio.file.Path.of(workspaceDir).toAbsolutePath().normalize();
        public final java.nio.file.Path repositories = data.resolve("repositories");
        public final java.nio.file.Path scans = data.resolve("scans");
        public final java.nio.file.Path vulndb = data.resolve("vulndb");
        public final java.nio.file.Path rules = data.resolve("rules");
        public final java.nio.file.Path users = data.resolve("users");
        public final java.nio.file.Path osvCache = data.resolve("sca-cache");
    }
}
