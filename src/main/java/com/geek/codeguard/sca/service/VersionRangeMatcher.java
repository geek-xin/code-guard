package com.geek.codeguard.sca.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量版本范围匹配：支持 ">=1.2, <1.3"、">= 1.0.0"、"< 2.0"、"= 1.2.3"、"*"、"1.2.x"、
 * OSV 风格 "||" 分隔的多组范围。
 */
@Component
public class VersionRangeMatcher {

    private static final Pattern NUMERIC = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    private static final Pattern RANGE = Pattern.compile("(>=|<=|>|<|=|~>|\\^)\\s*([\\w.\\-*+]+)");

    public boolean matches(String version, List<String> ranges) {
        if (version == null || version.isBlank() || version.equals("*")) {
            return false;
        }
        if (ranges == null || ranges.isEmpty()) {
            return false;
        }
        for (String group : ranges) {
            if (group == null || group.isBlank()) {
                continue;
            }
            // OSV 风格：多组用 || 分隔
            for (String range : group.split("\\|\\|")) {
                if (matchSingleRange(version, range.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchSingleRange(String version, String range) {
        if (range.isEmpty() || range.equals("*")) {
            return true;
        }
        // 形如 1.2.x 或 1.2.*
        if (range.matches("^[\\w.\\-*]+$")) {
            String r = range.replace("*", "x");
            if (r.endsWith(".x")) {
                return version.startsWith(r.substring(0, r.length() - 1));
            }
            // 精确版本（容忍 "= " 前缀）
            String exact = r.startsWith("=") ? r.substring(1).trim() : r;
            return compare(version, exact) == 0;
        }
        // 逗号分隔的约束组合
        String[] parts = range.split(",");
        for (String part : parts) {
            Matcher m = RANGE.matcher(part.trim());
            if (!m.find()) {
                continue;
            }
            String op = m.group(1);
            String target = m.group(2);
            if (target.contains("x") || target.contains("*")) {
                target = target.replace("*", "x").replace(".x", "");
            }
            int cmp = compare(version, target);
            boolean ok = switch (op) {
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case "=", "~>", "^" -> cmp == 0;
                default -> false;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 简单数值比较，返回 -1/0/1 */
    public int compare(String a, String b) {
        List<Integer> av = numbers(a);
        List<Integer> bv = numbers(b);
        int len = Math.max(av.size(), bv.size());
        for (int i = 0; i < len; i++) {
            int x = i < av.size() ? av.get(i) : 0;
            int y = i < bv.size() ? bv.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        // 主版本号相同则比较预发布后缀：带后缀的更低
        boolean aPre = a.matches(".*[-+].*") && !a.contains("+") || a.matches(".*-.*");
        boolean bPre = b.matches(".*-.*");
        if (aPre != bPre) {
            return aPre ? -1 : 1;
        }
        return 0;
    }

    private List<Integer> numbers(String v) {
        List<Integer> result = new ArrayList<>();
        Matcher m = NUMERIC.matcher(v);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) {
                    result.add(Integer.parseInt(m.group(i)));
                }
            }
        }
        return result;
    }
}
