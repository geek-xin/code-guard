package com.geek.codeguard.settings.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全局运行时配置（config/settings.json），保存后热生效，无需重启。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settings {
    private Agent agent;
    private Smtp smtp;
    /** 第三方登录（OAuth）全局配置：保存后热生效，优先于 application.yml 环境变量 */
    private OAuth oauth;
    /** Git 访问令牌全局配置：GitHub / GitLab 私有仓库拉取、分支查询、创建 Issue 时复用 */
    private Git git;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Git {
        /** GitHub Personal Access Token（私有仓库拉取/建 Issue 用） */
        private String githubToken;
        /** GitLab Personal Access Token（私有仓库拉取/分支查询用） */
        private String gitlabToken;
        /** GitLab Base URL，默认 https://gitlab.com（内网/自建 GitLab 填写） */
        private String gitlabUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Smtp {
        private Boolean enabled;
        private String host;
        private Integer port;
        private String username;
        private String password;
        private String from;
        private Boolean ssl;
        /** 默认收件邮箱（项目未单独配置时使用） */
        private java.util.List<String> defaultRecipients;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Agent {
        /** 是否启用 Agent（null 表示沿用环境变量默认） */
        private Boolean enabled;
        /** OpenAI 兼容接口地址 */
        private String baseUrl;
        /** API Key */
        private String apiKey;
        /** 模型名 */
        private String model;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OAuth {
        private String githubClientId;
        private String githubClientSecret;
        private String githubRedirectUri;
        private String gitlabClientId;
        private String gitlabClientSecret;
        private String gitlabRedirectUri;
        private String gitlabBaseUrl;
    }
}
