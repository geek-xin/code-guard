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
        xml.append(p("CodeGuard 代码安全扫描报告", 36, true, "2D2D2D"));
        xml.append(p("项目：" + esc(scan.getProjectName()) + "    状态：" + esc(scan.getStatus())
                + "    触发方式：" + ("SCHEDULED".equals(scan.getTrigger()) ? "定时扫描" : "手动扫描")
                + "    时间：" + fmt(scan.getStartedAt())
                + "    耗时：" + (scan.getDurationMs() == null ? "-" : (scan.getDurationMs() / 1000.0) + "s"), 20, false, null));
        xml.append(emptyP());

        // 汇总表
        xml.append(p("一、漏洞汇总", 28, true, null));
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
            xml.append(p("二、按引擎分布", 28, true, null));
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

        // 漏洞明细
        xml.append(p("三、漏洞明细（共 " + findings.size() + " 条）", 28, true, null));
        List<ScanFinding> sorted = findings.stream()
                .sorted((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())))
                .toList();
        for (int idx = 0; idx < sorted.size(); idx++) {
            ScanFinding f = sorted.get(idx);
            xml.append(p((idx + 1) + ". [" + sevLabel(f.getSeverity()) + "] " + esc(f.getTitle()), 24, true, null));
            String[][] rows = {
                    {"引擎", engineLabel(f.getEngine())},
                    {"类别", f.getCategory() == null ? "-" : esc(f.getCategory())},
                    {"编号", f.getVulnId() == null ? "-" : esc(f.getVulnId())},
                    {"位置", f.getFile() == null ? "-" : esc(f.getFile()) + (f.getLine() != null ? " : " + f.getLine() : "")},
                    {"依赖", f.getDependencyName() == null ? "-" : esc(f.getDependencyName() + "@" + f.getDependencyVersion())},
                    {"修复版本", f.getFixedVersion() == null ? "-" : esc(f.getFixedVersion())},
                    {"描述", f.getDescription() == null ? "-" : esc(f.getDescription())},
                    {"解决方案", f.getSolution() == null ? "-" : esc(f.getSolution())},
            };
            xml.append(table(new String[]{"项目", "内容"}, rows, new int[]{1800, 3600}));
            xml.append(emptyP());
        }

        if (scan.getAgentReview() != null && !scan.getAgentReview().isBlank()) {
            xml.append(p("四、AI 审查意见", 28, true, null));
            for (String line : scan.getAgentReview().split("\n")) {
                xml.append(p(esc(line), 20, false, null));
            }
        }

        xml.append(emptyP());
        xml.append(p("本报告由 CodeGuard 代码安全分析平台自动生成 · " + fmt(Instant.now().toString()), 18, false, "8A8A8A"));
        xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>");
        xml.append("</w:body></w:document>");
        return xml.toString();
    }

    private String p(String text, int size, boolean bold, String color) {
        // 单元格/段落文本内不能有裸换行符（会破坏 OOXML 文本节点渲染），统一替换为空格
        String safeText = text == null ? "" : text.replace("\r", " ").replace("\n", " ");
        StringBuilder b = new StringBuilder();
        b.append("<w:p><w:pPr><w:spacing w:before=\"80\" w:after=\"80\"/>");
        b.append("<w:rPr><w:sz w:val=\"").append(size * 2).append("\"/><w:szCs w:val=\"").append(size * 2).append("\"/>");
        if (bold) b.append("<w:b/>");
        if (color != null) b.append("<w:color w:val=\"").append(color).append("\"/>");
        b.append("</w:rPr></w:pPr>");
        b.append("<w:r><w:rPr><w:sz w:val=\"").append(size * 2).append("\"/><w:szCs w:val=\"").append(size * 2).append("\"/>");
        if (bold) b.append("<w:b/>");
        if (color != null) b.append("<w:color w:val=\"").append(color).append("\"/>");
        b.append("</w:rPr><w:t xml:space=\"preserve\">").append(safeText).append("</w:t></w:r></w:p>");
        return b.toString();
    }

    private String emptyP() {
        return "<w:p/>";
    }

    private String table(String[] headers, String[][] rows, int[] widths) {
        StringBuilder b = new StringBuilder();
        b.append("<w:tbl><w:tblPr><w:tblBorders>");
        for (String border : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
            b.append("<w:").append(border).append(" w:val=\"single\" w:sz=\"4\" w:color=\"444444\"/>");
        }
        b.append("</w:tblBorders><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>");
        b.append("<w:tblGrid>");
        for (int w : widths) b.append("<w:gridCol w:w=\"").append(w).append("\"/>");
        b.append("</w:tblGrid>");
        // header
        b.append("<w:tr>");
        for (String h : headers) {
            b.append("<w:tc><w:tcPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8E8E8\"/></w:tcPr>");
            b.append(p(h, 20, true, null));
            b.append("</w:tc>");
        }
        b.append("</w:tr>");
        for (String[] row : rows) {
            b.append("<w:tr>");
            for (String cell : row) {
                b.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>");
                b.append(p(cell, 18, false, null));
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
