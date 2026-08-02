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
}
