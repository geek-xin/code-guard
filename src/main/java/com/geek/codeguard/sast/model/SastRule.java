package com.geek.codeguard.sast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SastRule {
    private String id;
    private String name;
    private String category;
    private String severity;
    private String cwe;
    private List<String> languages;
    private String message;
    private String remediation;
    private List<String> references;
    private List<RulePattern> patterns;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RulePattern {
        private String regex;
        private String description;
        private Integer confidence;
        /** 匹配数量上限（防止刷屏），默认 20 */
        private Integer maxMatches;
    }
}
