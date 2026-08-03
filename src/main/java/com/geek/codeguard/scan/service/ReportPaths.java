package com.geek.codeguard.scan.service;

/**
 * 报告路径工具：把扫描发现中的绝对路径（含 workspace 前缀）转为项目内相对路径，
 * 避免报告中出现超长的机器路径导致表格破碎换行。
 */
public final class ReportPaths {

    private ReportPaths() {
    }

    /**
     * 把绝对路径转为项目内相对路径：
     * /xxx/workspace/{projectId}/frontend/package.json -> frontend/package.json
     * /xxx/config/workspace/{projectId}/pom.xml         -> pom.xml
     * 已是相对路径的（SAST 结果）原样返回。
     */
    public static String shortPath(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        String f = file.replace('\\', '/');
        int idx = f.indexOf("/workspace/");
        if (idx >= 0) {
            String after = f.substring(idx + "/workspace/".length());
            int slash = after.indexOf('/');
            if (slash >= 0) {
                return after.substring(slash + 1);
            }
            return after;
        }
        // 兼容 /xxx/codeguard/data/workspace/ 之类
        idx = f.indexOf("workspace/");
        if (idx >= 0 && f.contains("/workspace/")) {
            String after = f.substring(idx + "workspace/".length());
            int slash = after.indexOf('/');
            if (slash >= 0) {
                return after.substring(slash + 1);
            }
            return after;
        }
        return f;
    }
}
