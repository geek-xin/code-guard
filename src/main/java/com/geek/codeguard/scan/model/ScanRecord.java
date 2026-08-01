package com.geek.codeguard.scan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 一次扫描记录：状态 + 进度 + 汇总。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanRecord {
    private String id;
    private String projectId;
    private String projectName;
    /** MANUAL / SCHEDULED */
    private String trigger;
    /** ALL / SCA / SAST / AGENT */
    private String scope;
    /** RUNNING / COMPLETED / FAILED / STOPPED */
    private String status;
    private String message;
    private String startedAt;
    private String finishedAt;
    private Integer durationMs;
    /** 各阶段进度：{stage: {status, current, total, message}} */
    private Map<String, StageProgress> stages;
    private Map<String, Object> summary;
    private List<String> findingsSummary;
    private String agentReview;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StageProgress {
        private String status; // PENDING / RUNNING / COMPLETED / FAILED
        private Integer current;
        private Integer total;
        private String message;
    }
}
