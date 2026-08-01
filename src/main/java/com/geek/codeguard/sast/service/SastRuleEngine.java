package com.geek.codeguard.sast.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.geek.codeguard.config.CodeGuardProperties;
import com.geek.codeguard.config.JsonStore;
import com.geek.codeguard.sast.model.SastRule;
import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.service.ProjectFileScanner;
import com.geek.codeguard.scan.service.ScanProgressListener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SAST 静态规则引擎：按语言加载规则，正则扫描源码，产出带行号与解决方案的发现。
 */
@Service
@Slf4j
public class SastRuleEngine {

    private static final Map<String, List<String>> EXT_LANGUAGES = Map.of(
            "java", List.of("java"),
            "python", List.of("py"),
            "javascript", List.of("js", "jsx", "mjs", "cjs"),
            "typescript", List.of("ts", "tsx"),
            "go", List.of("go"),
            "php", List.of("php"),
            "ruby", List.of("rb"),
            "csharp", List.of("cs")
    );

    private final JsonStore jsonStore;
    private final CodeGuardProperties props;
    private final ProjectFileScanner fileScanner;
    private final Map<String, List<CompiledRule>> rulesByLanguage = new HashMap<>();

    public SastRuleEngine(JsonStore jsonStore, CodeGuardProperties props, ProjectFileScanner fileScanner) {
        this.jsonStore = jsonStore;
        this.props = props;
        this.fileScanner = fileScanner;
    }

    @PostConstruct
    public synchronized void load() {
        rulesByLanguage.clear();
        List<SastRule> rules = new ArrayList<>();
        // 内置规则
        try (var in = getClass().getClassLoader().getResourceAsStream("sast-rules.json")) {
            if (in != null) {
                rules.addAll(jsonStore.mapper().readValue(in.readAllBytes(), new TypeReference<List<SastRule>>() {
                }));
            }
        } catch (IOException e) {
            log.error("加载内置 SAST 规则失败", e);
        }
        // 用户扩展规则
        Path ext = jsonStore.paths().rules.resolve("sast-rules.json");
        if (Files.exists(ext)) {
            try {
                rules.addAll(jsonStore.mapper().readValue(Files.readString(ext), new TypeReference<List<SastRule>>() {
                }));
            } catch (IOException e) {
                log.warn("加载扩展规则失败: {}", e.getMessage());
            }
        }
        for (SastRule rule : rules) {
            if (rule.getPatterns() == null) {
                continue;
            }
            CompiledRule compiled = new CompiledRule(rule);
            for (String lang : rule.getLanguages() == null ? java.util.List.<String>of() : rule.getLanguages()) {
                rulesByLanguage.computeIfAbsent(lang.toLowerCase(), k -> new ArrayList<>()).add(compiled);
            }
        }
        log.info("SAST 规则引擎就绪：{} 条规则", rules.size());
    }

    public List<ScanFinding> scan(Path root, String projectId, String scanId, ScanProgressListener listener) {
        List<Path> files = fileScanner.listFiles(root);
        listener.onStage("SAST", "RUNNING", "待分析文件 " + files.size() + " 个");
        List<ScanFinding> findings = new ArrayList<>();
        int total = files.size();
        int idx = 0;
        int maxKb = props.getMaxFileKb();

        for (Path file : files) {
            idx++;
            String lang = languageOf(file);
            if (lang == null) {
                listener.onProgress("SAST", idx, total, fileScanner.relative(root, file));
                continue;
            }
            listener.onProgress("SAST", idx, total, fileScanner.relative(root, file));
            List<CompiledRule> rules = rulesByLanguage.getOrDefault(lang, List.of());
            if (rules.isEmpty()) {
                continue;
            }
            String content;
            try {
                if (Files.size(file) > maxKb * 1024L) {
                    continue;
                }
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            int[] lineOffsets = lineOffsets(content);
            String rel = fileScanner.relative(root, file);
            for (CompiledRule rule : rules) {
                SastRule r = rule.rule();
                for (CompiledPattern cp : rule.patterns()) {
                    Matcher m = cp.pattern().matcher(content);
                    int count = 0;
                    int max = cp.maxMatches();
                    while (m.find()) {
                        if (count >= max) {
                            break;
                        }
                        count++;
                        int line = lineAt(lineOffsets, m.start());
                        String snippet = snippetAt(content, lineOffsets, line);
                        ScanFinding f = ScanFinding.builder()
                                .id(UUID.randomUUID().toString())
                                .scanId(scanId)
                                .projectId(projectId)
                                .engine("SAST")
                                .category(r.getCategory())
                                .severity(r.getSeverity())
                                .title(r.getName())
                                .description(r.getMessage() + " " + (cp.description() == null ? "" : "（" + cp.description() + "）"))
                                .file(rel)
                                .line(line)
                                .codeSnippet(snippet)
                                .vulnId(r.getId())
                                .solution(r.getRemediation())
                                .references(r.getReferences() == null ? List.of() : r.getReferences())
                                .confidence(cp.confidence())
                                .cwe(r.getCwe())
                                .createdAt(System.currentTimeMillis())
                                .build();
                        findings.add(f);
                        listener.onFinding(f);
                    }
                }
            }
        }
        listener.onStage("SAST", "COMPLETED", "SAST 完成，发现 " + findings.size() + " 个问题");
        return findings;
    }

    private String languageOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase();
        for (Map.Entry<String, List<String>> e : EXT_LANGUAGES.entrySet()) {
            if (e.getValue().contains(ext)) {
                return e.getKey();
            }
        }
        return null;
    }

    private int[] lineOffsets(String content) {
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        int[] offsets = new int[lines + 1];
        int line = 1;
        offsets[1] = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
                offsets[line] = i + 1;
            }
        }
        offsets[lines] = content.length();
        return offsets;
    }

    private int lineAt(int[] offsets, int pos) {
        // 二分查找
        int lo = 1, hi = offsets.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (offsets[mid] <= pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return Math.max(1, lo - 1);
    }

    private String snippetAt(String content, int[] offsets, int line) {
        int start = line >= 1 && line <= offsets.length - 1 ? offsets[line] : 0;
        int end = line + 1 <= offsets.length - 1 ? offsets[line + 1] : content.length();
        if (start > end || start > content.length()) {
            return null;
        }
        String s = content.substring(start, Math.min(end, content.length())).strip();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    private record CompiledRule(SastRule rule, List<CompiledPattern> patterns) {
        private CompiledRule(SastRule rule) {
            this(rule, compilePatterns(rule));
        }

        private static List<CompiledPattern> compilePatterns(SastRule rule) {
            List<CompiledPattern> list = new ArrayList<>();
            for (SastRule.RulePattern p : rule.getPatterns()) {
                try {
                    Pattern pattern = Pattern.compile(p.getRegex(), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
                    list.add(new CompiledPattern(pattern, p.getConfidence() == null ? 70 : p.getConfidence(),
                            p.getMaxMatches() == null ? 20 : p.getMaxMatches(), p.getDescription()));
                } catch (Exception e) {
                    log.warn("规则 {} 正则编译失败: {}", rule.getId(), p.getRegex());
                }
            }
            return list;
        }
    }

    private record CompiledPattern(Pattern pattern, int confidence, int maxMatches, String description) {
    }
}
