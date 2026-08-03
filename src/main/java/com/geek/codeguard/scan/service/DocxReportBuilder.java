package com.geek.codeguard.scan.service;

import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Word (.docx) 报告生成：直接构造 OOXML（zip + XML），无第三方依赖。
 */
@Component
public class DocxReportBuilder {

    private static final Map<String, String> SEV_LABEL = Map.of(
            "CRITICAL", "严重", "HIGH", "高危", "MEDIUM", "中危", "LOW", "低危", "INFO", "提示");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public byte[] build(ScanRecord scan, List<ScanFinding> findings) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                put(zip, "[Content_Types].xml", contentTypes());
                put(zip, "_rels/.rels", rels());
                put(zip, "word/_rels/document.xml.rels", docRels());
                put(zip, "word/document.xml", documentXml(scan, findings));
                put(zip, "word/styles.xml", styles());
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Word 报告失败", e);
        }
    }

    private String documentXml(ScanRecord scan, List<ScanFinding> findings) {
        Map<String, Object> s = scan.getSummary() == null ? Map.of() : scan.getSummary();
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        xml.append("<w:body>");

        // 标题
        xml.append(p("CodeGuard 代码安全扫描报告", 24, true, "2D2D2D"));
        xml.append(p("项目：" + esc(scan.getProjectName()) + "    状态：" + esc(scan.getStatus())
                + "    触发方式：" + ("SCHEDULED".equals(scan.getTrigger()) ? "定时扫描" : "手动扫描")
                + "    时间：" + fmt(scan.getStartedAt())
                + "    耗时：" + (scan.getDurationMs() == null ? "-" : (scan.getDurationMs() / 1000.0) + "s"), 11, false, null));
        xml.append(emptyP());

        // 汇总表
        xml.append(p("一、漏洞汇总", 16, true, null));
        String[][] summaryRows = {
                {"严重", str(s.get("critical"))}, {"高危", str(s.get("high"))},
                {"中危", str(s.get("medium"))}, {"低危", str(s.get("low"))},
                {"提示", str(s.get("info"))}, {"合计", str(s.get("total"))}
        };
        xml.append(table(new String[]{"等级", "数量"}, summaryRows, new int[]{2400, 2400}));
        xml.append(emptyP());

        // 引擎分布
        Object eng = s.get("byEngine");
        if (eng instanceof Map<?, ?> m) {
            xml.append(p("二、按引擎分布", 16, true, null));
            String[][] engineRows = new String[m.size()][2];
            int i = 0;
            for (var e : m.entrySet()) {
                engineRows[i][0] = engineLabel(String.valueOf(e.getKey()));
                engineRows[i][1] = String.valueOf(e.getValue());
                i++;
            }
            xml.append(table(new String[]{"引擎", "数量"}, engineRows, new int[]{2400, 2400}));
            xml.append(emptyP());
        }

        // 漏洞明细（紧凑主表：等级/漏洞/引擎/位置/编号）
        xml.append(p("三、漏洞明细（共 " + findings.size() + " 条）", 16, true, null));
        List<ScanFinding> sorted = findings.stream()
                .sorted((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())))
                .toList();
        String[][] detailRows = new String[sorted.size()][5];
        for (int idx = 0; idx < sorted.size(); idx++) {
            ScanFinding f = sorted.get(idx);
            detailRows[idx][0] = sevLabel(f.getSeverity());
            detailRows[idx][1] = trimText(MarkdownReportRenderer.inlineClean(f.getTitle()), 160);
            detailRows[idx][2] = engineLabel(f.getEngine());
            detailRows[idx][3] = trimText(ReportPaths.shortPath(f.getFile()), 60)
                    + (f.getLine() != null ? " : " + f.getLine() : "");
            detailRows[idx][4] = trimText(f.getVulnId(), 40);
        }
        xml.append(table(new String[]{"等级", "漏洞", "引擎", "位置", "编号"},
                detailRows, new int[]{800, 3200, 1100, 1900, 1800}));
        xml.append(emptyP());
        // 关键字段详情（依赖/修复版本/解决方案）以紧凑小字列于表后
        for (int idx = 0; idx < sorted.size(); idx++) {
            ScanFinding f = sorted.get(idx);
            String dep = f.getDependencyName() == null ? null
                    : "依赖 " + f.getDependencyName() + "@" + f.getDependencyVersion()
                    + (f.getFixedVersion() != null ? "（修复版本 " + f.getFixedVersion() + "）" : "");
            String desc = f.getDescription() == null ? null : trimText(oneLine(f.getDescription()), 200);
            String sol = f.getSolution() == null ? null : trimText(oneLine(f.getSolution()), 200);
            if (dep != null || desc != null || sol != null) {
                xml.append(p(esc((idx + 1) + ". " + trimText(oneLine(MarkdownReportRenderer.inlineClean(f.getTitle())), 80)
                        + "（" + sevLabel(f.getSeverity()) + "）"), 11, true, null));
                if (dep != null) xml.append(p(esc(dep), 9.5f, false, null));
                if (desc != null) xml.append(p(esc("描述：" + desc), 9.5f, false, null));
                if (sol != null) xml.append(p(esc("解决方案：" + sol), 9.5f, false, "1A7F5A"));
                xml.append(emptyP());
            }
        }

        if (scan.getAgentReview() != null && !scan.getAgentReview().isBlank()) {
            xml.append(p("四、AI 审查意见", 16, true, null));
            renderMarkdown(xml, scan.getAgentReview());
        }

        xml.append(emptyP());
        xml.append(p("本报告由 CodeGuard 代码安全分析平台自动生成 · " + fmt(Instant.now().toString()), 9, false, "8A8A8A"));
        xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>");
        xml.append("</w:body></w:document>");
        return xml.toString();
    }

    private String p(String text, float size, boolean bold, String color) {
        // 单元格/段落文本内不能有裸换行符（会破坏 OOXML 文本节点渲染），统一替换为空格
        String safeText = text == null ? "" : text.replace("\r", " ").replace("\n", " ");
        StringBuilder b = new StringBuilder();
        b.append("<w:p><w:pPr><w:spacing w:before=\"80\" w:after=\"80\"/>");
        b.append("<w:rPr><w:sz w:val=\"").append(Math.round(size * 2)).append("\"/><w:szCs w:val=\"").append(Math.round(size * 2)).append("\"/>");
        if (bold) b.append("<w:b/>");
        if (color != null) b.append("<w:color w:val=\"").append(color).append("\"/>");
        b.append("</w:rPr></w:pPr>");
        b.append("<w:r><w:rPr><w:sz w:val=\"").append(Math.round(size * 2)).append("\"/><w:szCs w:val=\"").append(Math.round(size * 2)).append("\"/>");
        if (bold) b.append("<w:b/>");
        if (color != null) b.append("<w:color w:val=\"").append(color).append("\"/>");
        b.append("</w:rPr><w:t xml:space=\"preserve\">").append(safeText).append("</w:t></w:r></w:p>");
        return b.toString();
    }

    private String emptyP() {
        return "<w:p/>";
    }

    /** 渲染 AI 审查的 Markdown 块为 Word 段落/表格 */
    private void renderMarkdown(StringBuilder xml, String markdown) {
        for (MarkdownReportRenderer.Block b : MarkdownReportRenderer.parse(markdown)) {
            switch (b.type) {
                case H1, H2, H3, H4 -> {
                    int size = switch (b.type) {
                        case H1 -> 16;
                        case H2 -> 14;
                        case H3 -> 13;
                        default -> 12;
                    };
                    xml.append(p(esc(b.text), size, true, null));
                }
                case PARAGRAPH, QUOTE -> xml.append(p(esc(b.text), 10, false, null));
                case LIST -> xml.append(p(esc("• " + b.text), 10, false, null));
                case CODE -> xml.append(p(esc(trimText(b.text, 500)), 8.5f, false, "444444"));
                case TABLE -> {
                    if (b.table != null && !b.table.isEmpty()) {
                        int cols = 1;
                        for (List<String> r : b.table) {
                            cols = Math.max(cols, r.size());
                        }
                        int[] widths = new int[cols];
                        for (int c = 0; c < cols; c++) {
                            widths[c] = 5400 / cols;
                        }
                        String[][] rows = new String[b.table.size()][cols];
                        for (int i = 0; i < b.table.size(); i++) {
                            for (int c = 0; c < cols; c++) {
                                rows[i][c] = c < b.table.get(i).size() ? b.table.get(i).get(c) : "";
                            }
                        }
                        xml.append(table(headers(b.table.get(0)), rows, widths));
                        xml.append(emptyP());
                    }
                }
                default -> {
                    // BLANK 忽略
                }
            }
        }
    }

    private String[] headers(List<String> first) {
        return first.toArray(new String[0]);
    }

    private String table(String[] headers, String[][] rows, int[] widths) {
        StringBuilder b = new StringBuilder();
        b.append("<w:tbl><w:tblPr><w:tblBorders>");
        for (String border : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
            b.append("<w:").append(border).append(" w:val=\"single\" w:sz=\"4\" w:color=\"444444\"/>");
        }
        b.append("</w:tblBorders>");
        // 固定布局：让 gridCol / tcW 生效，避免 Word 按内容自动重排导致列宽失控
        b.append("<w:tblLayout w:type=\"fixed\"/>");
        // 列宽总和不超过页面内容宽（A4 页宽 11906 - 左右边距 2880 ≈ 9000 twips），
        // 避免 Word 过度压缩导致每列过窄、文字破碎折行
        int total = 0;
        for (int w : widths) {
            total += w;
        }
        if (total > 9000 && widths.length > 0) {
            double ratio = 9000.0 / total;
            for (int i = 0; i < widths.length; i++) {
                widths[i] = (int) Math.round(widths[i] * ratio);
            }
        }
        b.append("<w:tblW w:w=\"").append(total > 9000 ? 9000 : total).append("\" w:type=\"dxa\"/></w:tblPr>");
        b.append("<w:tblGrid>");
        for (int w : widths) b.append("<w:gridCol w:w=\"").append(w).append("\"/>");
        b.append("</w:tblGrid>");
        // header
        b.append("<w:tr>");
        for (int c = 0; c < headers.length; c++) {
            b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[c]).append("\" w:type=\"dxa\"/>")
                    .append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8E8E8\"/></w:tcPr>");
            b.append(p(esc(headers[c]), 11, true, null));
            b.append("</w:tc>");
        }
        b.append("</w:tr>");
        for (String[] row : rows) {
            b.append("<w:tr>");
            for (int c = 0; c < row.length; c++) {
                b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(widths[c % widths.length]).append("\" w:type=\"dxa\"/></w:tcPr>");
                b.append(p(esc(row[c]), 9.5f, false, null));
                b.append("</w:tc>");
            }
            b.append("</w:tr>");
        }
        b.append("</w:tbl>");
        return b.toString();
    }

    private String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                </Types>""";
    }

    private String rels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>""";
    }

    private String docRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>""";
    }

    private String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Microsoft YaHei" w:eastAsia="微软雅黑" w:hAnsi="Microsoft YaHei"/><w:sz w:val="20"/></w:rPr></w:rPrDefault></w:docDefaults>
                  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
                </w:styles>""";
    }

    private void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String str(Object o) {
        return o == null ? "0" : String.valueOf(o);
    }

    private String trimText(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = oneLine(s);
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String fmt(String iso) {
        try {
            return FMT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso == null ? "-" : iso;
        }
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

    private String engineLabel(String engine) {
        return switch (engine == null ? "" : engine) {
            case "SCA" -> "依赖扫描 SCA";
            case "SAST" -> "静态分析 SAST";
            case "AGENT" -> "AI 审查 Agent";
            default -> engine;
        };
    }
}
