package com.geek.codeguard.scan.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一漏洞发现模型：SCA / SAST / Review Agent 共用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanFinding {
    private String id;
    private String scanId;
    private String projectId;
    /** SCA / SAST / AGENT */
    private String engine;
    /** 漏洞类别，如 sql-injection / dependency / xss ... */
    private String category;
    /** CRITICAL / HIGH / MEDIUM / LOW / INFO */
    private String severity;
    private String title;
    private String description;
    /** 相对项目根目录的文件路径 */
    private String file;
    private Integer line;
    private String codeSnippet;
    /** SCA 依赖信息 */
    private String dependencyName;
    private String dependencyVersion;
    private String fixedVersion;
    private String ecosystem;
    /** CVE / 规则编号 */
    private String vulnId;
    /** 解决方案 */
    private String solution;
    private List<String> references;
    /** 置信度 0-100 */
    private Integer confidence;
    private String cwe;
    private Long createdAt;
}
