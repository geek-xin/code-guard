package com.geek.codeguard.scan.service;

import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描报告生成：支持 Markdown / HTML（自包含样式，可直接打印为 PDF）。
 */
@Service
public class ReportService {

    private static final Map<String, String> SEV_LABEL = Map.of(
            "CRITICAL", "严重", "HIGH", "高危", "MEDIUM", "中危", "LOW", "低危", "INFO", "提示");
    private static final Map<String, String> SEV_COLOR = Map.of(
            "CRITICAL", "#E23B2E", "HIGH", "#F45113", "MEDIUM", "#F6C445", "LOW", "#7BC4E8", "INFO", "#B9B2A9");

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public String buildMarkdown(ScanRecord scan, List<ScanFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 代码安全扫描报告\n\n");
        sb.append("| 项目 | 状态 | 触发方式 | 扫描时间 | 耗时 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        sb.append("| ").append(escMd(scan.getProjectName())).append(" | ")
                .append(scan.getStatus()).append(" | ")
                .append("SCHEDULED".equals(scan.getTrigger()) ? "定时扫描" : "手动扫描").append(" | ")
                .append(fmt(scan.getStartedAt())).append(" | ")
                .append(scan.getDurationMs() == null ? "-" : (scan.getDurationMs() / 1000.0) + "s").append(" |\n\n");
        if (scan.getMessage() != null) {
            sb.append("> ").append(escMd(scan.getMessage())).append("\n\n");
        }

        Map<String, Object> s = scan.getSummary() == null ? Map.of() : scan.getSummary();
        sb.append("## 漏洞汇总\n\n");
        sb.append("| 严重 | 高危 | 中危 | 低危 | 提示 | 合计 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        sb.append("| ").append(num(s.get("critical"))).append(" | ")
                .append(num(s.get("high"))).append(" | ")
                .append(num(s.get("medium"))).append(" | ")
                .append(num(s.get("low"))).append(" | ")
                .append(num(s.get("info"))).append(" | ")
                .append(num(s.get("total"))).append(" |\n\n");

        sb.append("### 按引擎分布\n\n");
        sb.append("| 引擎 | 数量 |\n|---|---|\n");
        Object eng = s.get("byEngine");
        if (eng instanceof Map<?, ?> m) {
            m.forEach((k, v) -> sb.append("| ").append(engineLabel(String.valueOf(k))).append(" | ").append(v).append(" |\n"));
        }
        sb.append("\n");

        sb.append("## 漏洞明细\n\n");
        if (findings.isEmpty()) {
            sb.append("未发现漏洞。\n");
        }
        List<ScanFinding> sorted = findings.stream()
                .sorted((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            ScanFinding f = sorted.get(i);
            sb.append("### ").append(i + 1).append(". [").append(sevLabel(f.getSeverity())).append("] ")
                    .append(escMd(f.getTitle())).append("\n\n");
            sb.append("- **引擎**：").append(engineLabel(f.getEngine())).append("\n");
            sb.append("- **类别**：").append(f.getCategory()).append("\n");
            if (f.getVulnId() != null) sb.append("- **编号**：").append(f.getVulnId()).append("\n");
            if (f.getCwe() != null && !"SCA".equals(f.getCwe())) sb.append("- **CWE**：").append(f.getCwe()).append("\n");
            if (f.getFile() != null) {
                sb.append("- **位置**：").append(f.getFile());
                if (f.getLine() != null) sb.append(": ").append(f.getLine());
                sb.append("\n");
            }
            if (f.getDependencyName() != null) {
                sb.append("- **依赖**：`").append(f.getDependencyName()).append("@").append(f.getDependencyVersion()).append("`");
                if (f.getFixedVersion() != null) sb.append(" → 修复版本 **").append(f.getFixedVersion()).append("**");
                sb.append("\n");
            }
            if (f.getDescription() != null && !f.getDescription().isBlank()) {
                sb.append("- **描述**：").append(escMd(oneLine(f.getDescription()))).append("\n");
            }
            if (f.getSolution() != null && !f.getSolution().isBlank()) {
                sb.append("- **解决方案**：").append(escMd(oneLine(f.getSolution()))).append("\n");
            }
            if (f.getReferences() != null && !f.getReferences().isEmpty()) {
                sb.append("- **参考**：").append(String.join(" ; ", f.getReferences().stream().limit(3).toList())).append("\n");
            }
            sb.append("\n");
        }

        if (scan.getAgentReview() != null && !scan.getAgentReview().isBlank()) {
            sb.append("## AI 审查意见\n\n").append(scan.getAgentReview()).append("\n");
        }
        sb.append("\n---\n*由 CodeGuard 代码安全分析平台生成于 ").append(fmt(Instant.now().toString())).append("*\n");
        return sb.toString();
    }

    public String buildHtml(ScanRecord scan, List<ScanFinding> findings) {
        Map<String, Object> s = scan.getSummary() == null ? Map.of() : scan.getSummary();
        List<ScanFinding> sorted = findings.stream()
                .sorted((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())))
                .toList();

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            ScanFinding f = sorted.get(i);
            String color = SEV_COLOR.getOrDefault(f.getSeverity(), "#B9B2A9");
            rows.append("<tr>")
                    .append("<td><span class=\"sev\" style=\"background:").append(color).append("\">")
                    .append(sevLabel(f.getSeverity())).append("</span></td>")
                    .append("<td class=\"title\">").append(escHtml(f.getTitle()))
                    .append(f.getDependencyName() != null
                            ? "<div class=\"dep\">" + escHtml(f.getDependencyName() + "@" + f.getDependencyVersion())
                            + (f.getFixedVersion() != null ? " → 修复版本 " + escHtml(f.getFixedVersion()) : "") + "</div>" : "")
                    .append("</td>")
                    .append("<td>").append(engineLabel(f.getEngine())).append("</td>")
                    .append("<td class=\"mono\">").append(escHtml(f.getFile() == null ? "-" : f.getFile()))
                    .append(f.getLine() != null ? ":" + f.getLine() : "").append("</td>")
                    .append("<td class=\"mono\">").append(escHtml(f.getVulnId() == null ? "-" : f.getVulnId())).append("</td>")
                    .append("</tr>");
            rows.append("<tr class=\"detail-row\"><td colspan=\"5\"><details>")
                    .append("<summary>描述 / 解决方案</summary>")
                    .append("<p><b>描述：</b>").append(escHtml(f.getDescription())).append("</p>")
                    .append("<p class=\"fix\"><b>解决方案：</b>").append(escHtml(f.getSolution())).append("</p>")
                    .append(f.getReferences() != null && !f.getReferences().isEmpty()
                            ? "<p><b>参考：</b>" + f.getReferences().stream().limit(4)
                            .map(r -> "<a href=\"" + escHtml(r) + "\">" + escHtml(r.replace("https://", "")) + "</a>")
                            .reduce((a, b) -> a + " · " + b).orElse("") + "</p>" : "")
                    .append("</details></td></tr>");
        }

        String agentBlock = scan.getAgentReview() != null && !scan.getAgentReview().isBlank()
                ? "<h2>AI 审查意见</h2><div class=\"agent\">" + escHtml(scan.getAgentReview()).replace("\n", "<br>") + "</div>" : "";

        return """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>CodeGuard 安全扫描报告 - %s</title>
<style>
  :root{--ink:#161616;--muted:#6F6A64;--bg:#F8F6F3;--primary:#F45113}
  *{box-sizing:border-box}
  body{font-family:'PingFang SC','Microsoft YaHei',system-ui,sans-serif;background:linear-gradient(90deg,rgba(17,17,17,.04) 1px,transparent 1px),linear-gradient(0deg,rgba(17,17,17,.04) 1px,transparent 1px),var(--bg);background-size:36px 36px;color:var(--ink);margin:0;padding:32px}
  .page{max-width:960px;margin:0 auto}
  .header{border:3px solid var(--ink);border-radius:12px;background:#fff;box-shadow:6px 6px 0 #111;padding:24px 28px;margin-bottom:24px}
  .header h1{margin:0 0 6px;font-size:26px;font-weight:900}
  .header .sub{color:var(--muted);font-size:13px;font-weight:600}
  .header .logo{display:inline-block;background:var(--primary);color:#fff;font-weight:900;padding:4px 10px;border:2px solid var(--ink);border-radius:6px;margin-right:8px}
  h2{font-size:18px;font-weight:900;margin:28px 0 12px;border-bottom:3px solid var(--ink);padding-bottom:6px}
  table{width:100%%;border-collapse:collapse;background:#fff;border:3px solid var(--ink);border-radius:8px;overflow:hidden;font-size:13px}
  th{background:var(--ink);color:#fff;text-align:left;padding:10px 12px;font-weight:700}
  td{padding:10px 12px;border-top:1px solid rgba(17,17,17,.12);vertical-align:top}
  tr:hover td{background:#FFF7D6}
  .sev{display:inline-block;color:#fff;font-weight:800;font-size:12px;padding:2px 8px;border-radius:4px;border:1.5px solid var(--ink)}
  .title{font-weight:700}
  .dep{font-size:12px;color:var(--muted);margin-top:2px}
  .mono{font-family:'SFMono-Regular',Menlo,Consolas,monospace;font-size:12px}
  .detail-row td{background:#F8F6F3;padding:6px 12px}
  .detail-row p{margin:6px 0;font-size:13px;line-height:1.6}
  .fix{background:#E8F8EF;border:1.5px solid #18A96B;border-radius:6px;padding:8px 10px}
  .agent{background:#FFF7D6;border:2px solid var(--ink);border-radius:8px;padding:14px 16px;line-height:1.7;font-size:13px}
  .cards{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-bottom:8px}
  .card{border:2px solid var(--ink);border-radius:8px;background:#fff;padding:12px;text-align:center;box-shadow:3px 3px 0 #111}
  .card .n{font-size:28px;font-weight:900}
  .card .l{font-size:12px;font-weight:700;color:var(--muted)}
  .footer{margin-top:32px;text-align:center;color:var(--muted);font-size:12px;font-weight:600}
  @media print{body{background:#fff;padding:0}.page{max-width:100%%}.detail-row{break-inside:avoid}}
</style>
</head>
<body><div class="page">
<div class="header">
  <h1><span class="logo">CodeGuard</span>代码安全扫描报告</h1>
  <div class="sub">项目：<b>%s</b> &nbsp;·&nbsp; 状态：%s &nbsp;·&nbsp; 触发方式：%s &nbsp;·&nbsp; 时间：%s &nbsp;·&nbsp; 耗时：%s</div>
</div>
<h2>漏洞汇总</h2>
<div class="cards">
  <div class="card"><div class="n" style="color:#E23B2E">%s</div><div class="l">严重</div></div>
  <div class="card"><div class="n" style="color:#F45113">%s</div><div class="l">高危</div></div>
  <div class="card"><div class="n" style="color:#B8860B">%s</div><div class="l">中危</div></div>
  <div class="card"><div class="n" style="color:#2E86AB">%s</div><div class="l">低危</div></div>
  <div class="card"><div class="n">%s</div><div class="l">合计</div></div>
</div>
<h2>漏洞明细（%d）</h2>
<table>
<thead><tr><th style="width:70px">等级</th><th>漏洞</th><th style="width:120px">引擎</th><th style="width:220px">位置</th><th style="width:150px">编号</th></tr></thead>
<tbody>
%s
</tbody>
</table>
%s
<div class="footer">本报告由 CodeGuard 代码安全分析平台自动生成 · %s</div>
</div></body></html>
""".formatted(
                escHtml(scan.getProjectName()),
                escHtml(scan.getProjectName()),
                escHtml(scan.getStatus()),
                "SCHEDULED".equals(scan.getTrigger()) ? "定时扫描" : "手动扫描",
                fmt(scan.getStartedAt()),
                scan.getDurationMs() == null ? "-" : (scan.getDurationMs() / 1000.0) + "s",
                num(s.get("critical")), num(s.get("high")), num(s.get("medium")), num(s.get("low")), num(s.get("total")),
                sorted.size(), rows, agentBlock,
                fmt(Instant.now().toString()));
    }

    public byte[] buildJson(ScanRecord scan, List<ScanFinding> findings) {
        try {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("scan", scan);
            payload.put("findings", findings);
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalStateException("生成 JSON 报告失败", e);
        }
    }

    private String engineLabel(String engine) {
        return switch (engine == null ? "" : engine) {
            case "SCA" -> "依赖扫描 SCA";
            case "SAST" -> "静态分析 SAST";
            case "AGENT" -> "AI 审查 Agent";
            default -> engine;
        };
    }

    private String sevLabel(String sev) {
        return SEV_LABEL.getOrDefault(sev == null ? "" : sev.toUpperCase(), sev == null ? "-" : sev);
    }

    private int sevRank(String sev) {
        return switch (sev == null ? "" : sev.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private String fmt(String iso) {
        try {
            return FMT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso == null ? "-" : iso;
        }
    }

    private String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private String escMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").trim();
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
