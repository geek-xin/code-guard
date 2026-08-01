package com.geek.codeguard.sca.service;

import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.sca.model.Dependency;
import com.geek.codeguard.sca.model.Vulnerability;
import com.geek.codeguard.sca.parser.DependencyParser;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.service.ProjectFileScanner;
import com.geek.codeguard.scan.service.ScanProgressListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SCA（软件成分分析）：解析依赖清单 -> 离线漏洞库 + OSV 在线查询 -> 生成漏洞发现。
 */
@Service
@Slf4j
public class ScaService {

    private final DependencyParser parser;
    private final VulnerabilityDbService dbService;
    private final OsvClient osvClient;
    private final ProjectFileScanner fileScanner;
    private final CodeGuardProperties props;

    public ScaService(DependencyParser parser, VulnerabilityDbService dbService,
                      OsvClient osvClient, ProjectFileScanner fileScanner,
                      CodeGuardProperties props) {
        this.parser = parser;
        this.dbService = dbService;
        this.osvClient = osvClient;
        this.fileScanner = fileScanner;
        this.props = props;
    }

    public List<ScanFinding> scan(Path root, String projectId, String scanId, ScanProgressListener listener) {
        List<Path> files = fileScanner.listFiles(root);
        List<Path> manifests = files.stream().filter(parser::isManifest).toList();
        listener.onStage("SCA", "RUNNING", "共发现 " + manifests.size() + " 个依赖清单");

        List<ScanFinding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = manifests.size();
        int idx = 0;

        for (Path manifest : manifests) {
            idx++;
            listener.onProgress("SCA", idx, total, manifest.getFileName().toString());
            List<Dependency> deps = parser.parse(manifest);
            if (deps.isEmpty()) {
                continue;
            }
            for (Dependency dep : deps) {
                List<Vulnerability> localHits = dbService.lookup(dep.getEcosystem(), dep.getName(), dep.getVersion());
                for (Vulnerability v : localHits) {
                    ScanFinding f = toFinding(v, dep, root, projectId, scanId);
                    if (seen.add(f.getVulnId() + "|" + dep.getName())) {
                        findings.add(f);
                        listener.onFinding(f);
                    }
                }
                if (props.getSca().isOsvEnabled()) {
                    try {
                        List<Vulnerability> osvHits = osvClient.query(dep.getEcosystem(), dep.getName(), dep.getVersion());
                        for (Vulnerability v : osvHits) {
                            ScanFinding f = toFinding(v, dep, root, projectId, scanId);
                            if (seen.add(f.getVulnId() + "|" + dep.getName())) {
                                findings.add(f);
                                listener.onFinding(f);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("OSV 查询失败 {}: {}", dep.getName(), e.getMessage());
                    }
                }
            }
        }
        listener.onStage("SCA", "COMPLETED", "SCA 完成，发现 " + findings.size() + " 个依赖漏洞");
        return findings;
    }

    private ScanFinding toFinding(Vulnerability v, Dependency dep, Path root, String projectId, String scanId) {
        String fixed = extractFixedVersion(v, dep.getEcosystem(), dep.getName());
        String severity = normalize(v.getSeverity());
        String solution = fixed == null
                ? "1. 升级依赖 " + dep.getName() + " 到已修复版本；2. 如无法升级，参考官方通告采取缓解措施；3. 复查是否存在受影响调用路径。"
                : "升级依赖 " + dep.getName() + " 从 " + dep.getVersion() + " 到 " + fixed + " 或更高版本；"
                + "同时检查锁文件（package-lock.json / pom.xml / go.sum 等）确保升级生效。";

        return ScanFinding.builder()
                .id(UUID.randomUUID().toString())
                .scanId(scanId)
                .projectId(projectId)
                .engine("SCA")
                .category("dependency")
                .severity(severity)
                .title(v.getSummary() == null || v.getSummary().isBlank() ? v.getId() + " 影响 " + dep.getName() : v.getSummary())
                .description(buildDescription(v, dep))
                .file(dep.getManifest())
                .dependencyName(dep.getName())
                .dependencyVersion(dep.getVersion())
                .fixedVersion(fixed)
                .ecosystem(dep.getEcosystem())
                .vulnId(v.getId())
                .solution(solution)
                .references(v.getReferences() == null ? List.of() : v.getReferences())
                .confidence(90)
                .cwe("SCA")
                .createdAt(System.currentTimeMillis())
                .build();
    }

    private String buildDescription(Vulnerability v, Dependency dep) {
        StringBuilder sb = new StringBuilder();
        sb.append("依赖 ").append(dep.getName()).append(" 当前版本 ").append(dep.getVersion())
                .append(" 存在已知漏洞 ").append(v.getId()).append("。");
        if (v.getDetails() != null && !v.getDetails().isBlank()) {
            String details = v.getDetails().length() > 1200 ? v.getDetails().substring(0, 1200) + "..." : v.getDetails();
            sb.append('\n').append(details);
        }
        return sb.toString();
    }

    private String extractFixedVersion(Vulnerability v, String ecosystem, String pkg) {
        String best = null;
        if (v.getAffected() != null) {
            for (Vulnerability.Affected a : v.getAffected()) {
                if (a.getPackageName() == null || !a.getPackageName().equalsIgnoreCase(pkg)) {
                    continue;
                }
                if (a.getRanges() != null) {
                    for (String range : a.getRanges()) {
                        for (String part : range.split(",")) {
                            String p = part.trim();
                            if (p.startsWith("< ") || p.startsWith("<=")) {
                                String candidate = p.replaceAll("[<>=\\s]", "");
                                if (best == null || compareVersions(candidate, best) > 0) {
                                    best = candidate;
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private int compareVersions(String a, String b) {
        return new VersionRangeMatcher().compare(a, b);
    }

    private String normalize(String sev) {
        if (sev == null) {
            return "MEDIUM";
        }
        return switch (sev.toUpperCase()) {
            case "CRITICAL" -> "CRITICAL";
            case "HIGH" -> "HIGH";
            case "MODERATE", "MEDIUM" -> "MEDIUM";
            case "LOW" -> "LOW";
            default -> "MEDIUM";
        };
    }
}
