package com.geek.codeguard.project.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {
    private String id;
    private String name;
    /** 显示别名（可选，手动填写） */
    private String alias;
    /** 项目标签（多个） */
    private List<String> tags;
    /** 项目分组（如：前端/后端/基础设施） */
    private String group;
    private String description;
    /** GITHUB / GITLAB / LOCAL */
    private String source;
    /** 仓库地址（GITHUB/GITLAB 时必填） */
    private String repoUrl;
    /** 分支，默认 main */
    private String branch;
    /** 本地目录（LOCAL 时必填） */
    private String localPath;
    /** 访问令牌（Git 拉取用），可写入但不出现在响应中 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String token;
    private boolean tokenConfigured;
    /** 定时扫描 cron，如 0 0 2 * * ? */
    private String scheduleCron;
    private boolean scheduleEnabled;
    /** 扫描完成后是否邮件推送报告 */
    private boolean emailNotify;
    /** 报告接收邮箱（多个） */
    private List<String> emails;
    /** 是否开启定时同步代码（默认 60 分钟） */
    private boolean autoSyncEnabled;
    /** 定时同步间隔（分钟），默认 60 */
    private Integer syncIntervalMinutes;
    /** 上次同步时间 */
    private Instant lastSyncAt;
    /** 是否开启漏洞自动扫描（默认每 3 小时一次） */
    private boolean autoScanEnabled;
    /** 漏洞自动扫描间隔（分钟），默认 180（3 小时） */
    private Integer scanIntervalMinutes;
    private boolean enabled;
    /** 代码就绪状态：READY / SYNCING / ERROR */
    private String syncStatus;
    private String syncMessage;
    private String lastScanId;
    private Instant lastScanAt;
    private String lastScanStatus;
    /** 最近一次扫描统计：{critical,high,medium,low,info,total,sca,sast,review} */
    private Map<String, Object> lastScanStats;
    private Instant createdAt;
    private Instant updatedAt;

    public static final String SOURCE_GITHUB = "GITHUB";
    public static final String SOURCE_GITLAB = "GITLAB";
    public static final String SOURCE_LOCAL = "LOCAL";
}
