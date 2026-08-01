package com.geek.codeguard.scan.service;

import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Excel (.xlsx) 报告生成：直接构造 SpreadsheetML（zip + XML），无第三方依赖。
 */
@Component
public class XlsxReportBuilder {

    private static final Map<String, String> SEV_LABEL = Map.of(
            "CRITICAL", "严重", "HIGH", "高危", "MEDIUM", "中危", "LOW", "低危", "INFO", "提示");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public byte[] build(ScanRecord scan, List<ScanFinding> findings) {
        try {
            List<String> shared = new ArrayList<>();
            shared.add("等级");
            shared.add("漏洞标题");
            shared.add("引擎");
            shared.add("类别");
            shared.add("编号");
            shared.add("依赖");
            shared.add("当前版本");
            shared.add("修复版本");
            shared.add("文件");
            shared.add("行号");
            shared.add("描述");
            shared.add("解决方案");
            shared.add("参考");

            // 汇总行
            Map<String, Object> s = scan.getSummary() == null ? Map.of() : scan.getSummary();
            shared.add("漏洞汇总");
            shared.add("严重");
            shared.add("高危");
            shared.add("中危");
            shared.add("低危");
            shared.add("提示");
            shared.add("合计");

            List<ScanFinding> sorted = findings.stream()
                    .sorted((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())))
                    .toList();
            for (ScanFinding f : sorted) {
                add(shared, sevLabel(f.getSeverity()));
                add(shared, f.getTitle());
                add(shared, engineLabel(f.getEngine()));
                add(shared, f.getCategory());
                add(shared, f.getVulnId());
                add(shared, f.getDependencyName());
                add(shared, f.getDependencyVersion());
                add(shared, f.getFixedVersion());
                add(shared, f.getFile());
                add(shared, f.getLine() == null ? "" : String.valueOf(f.getLine()));
                add(shared, f.getDescription());
                add(shared, f.getSolution());
                add(shared, f.getReferences() == null ? "" : String.join("; ", f.getReferences().stream().limit(3).toList()));
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bos)) {
                put(zip, "[Content_Types].xml", contentTypes());
                put(zip, "_rels/.rels", rels());
                put(zip, "xl/workbook.xml", workbook(scan));
                put(zip, "xl/_rels/workbook.xml.rels", workbookRels());
                put(zip, "xl/styles.xml", styles());
                put(zip, "xl/sharedStrings.xml", sharedStrings(shared));
                put(zip, "xl/worksheets/sheet1.xml", sheet(scan, sorted, shared));
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Excel 报告失败", e);
        }
    }

    private String sheet(ScanRecord scan, List<ScanFinding> findings, List<String> shared) {
        Map<String, Object> s = scan.getSummary() == null ? Map.of() : scan.getSummary();
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        b.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>");
        b.append("<cols>");
        for (int i = 1; i <= 13; i++) {
            int width = switch (i) {
                case 5 -> 18;
                case 6, 11, 12, 13 -> 40;
                case 9 -> 34;
                default -> 14;
            };
            b.append("<col min=\"").append(i).append("\" max=\"").append(i).append("\" width=\"").append(width).append("\"/>");
        }
        b.append("</cols>");
        b.append("<sheetData>");

        // 标题行（合并 A1:M1）
        String[] titleRow = {resolve(shared, "CodeGuard 代码安全扫描报告 - " + scan.getProjectName())};
        b.append(row(0, titleRow, 2));
        String[] infoRow = {resolve(shared, "项目：" + scan.getProjectName() + " | 状态：" + scan.getStatus()
                + " | 触发方式：" + ("SCHEDULED".equals(scan.getTrigger()) ? "定时扫描" : "手动扫描")
                + " | 时间：" + fmt(scan.getStartedAt())
                + " | 耗时：" + (scan.getDurationMs() == null ? "-" : (scan.getDurationMs() / 1000.0) + "s"))};
        b.append(row(1, infoRow, 1));
        String[] summaryRow = {resolve(shared, "漏洞汇总：严重 " + str(s.get("critical")) + " / 高危 " + str(s.get("high"))
                + " / 中危 " + str(s.get("medium")) + " / 低危 " + str(s.get("low")) + " / 提示 " + str(s.get("info"))
                + " / 合计 " + str(s.get("total")))};
        b.append(row(2, summaryRow, 1));

        // 空行
        b.append(row(3, new String[]{resolve(shared, " "), ""}, 1));

        // 表头
        int r = 4;
        String[] header = {
                resolve(shared, "等级"), resolve(shared, "漏洞标题"), resolve(shared, "引擎"), resolve(shared, "类别"),
                resolve(shared, "编号"), resolve(shared, "依赖"), resolve(shared, "当前版本"), resolve(shared, "修复版本"),
                resolve(shared, "文件"), resolve(shared, "行号"), resolve(shared, "描述"), resolve(shared, "解决方案"),
                resolve(shared, "参考")
        };
        b.append(row(r++, header, 2));

        for (ScanFinding f : findings) {
            String[] cells = {
                    resolve(shared, sevLabel(f.getSeverity())),
                    resolve(shared, nullSafe(f.getTitle())),
                    resolve(shared, engineLabel(f.getEngine())),
                    resolve(shared, nullSafe(f.getCategory())),
                    resolve(shared, nullSafe(f.getVulnId())),
                    resolve(shared, nullSafe(f.getDependencyName())),
                    resolve(shared, nullSafe(f.getDependencyVersion())),
                    resolve(shared, nullSafe(f.getFixedVersion())),
                    resolve(shared, nullSafe(f.getFile())),
                    resolve(shared, f.getLine() == null ? "" : String.valueOf(f.getLine())),
                    resolve(shared, nullSafe(f.getDescription())),
                    resolve(shared, nullSafe(f.getSolution())),
                    resolve(shared, f.getReferences() == null ? "" : String.join("; ", f.getReferences().stream().limit(3).toList()))
            };
            b.append(row(r++, cells, 1));
        }
        b.append("</sheetData>");
        b.append("<mergeCells count=\"3\">");
        b.append("<mergeCell ref=\"A1:M1\"/><mergeCell ref=\"A2:M2\"/><mergeCell ref=\"A3:M3\"/>");
        b.append("</mergeCells>");
        b.append("</worksheet>");
        return b.toString();
    }

    private String row(int r, String[] cells, int style) {
        StringBuilder b = new StringBuilder();
        b.append("<row r=\"").append(r + 1).append("\">");
        int col = 1;
        for (String c : cells) {
            b.append("<c r=\"").append(colName(col)).append(r + 1).append("\" t=\"s\" s=\"").append(style).append("\">")
                    .append("<v>").append(c.isEmpty() ? 0 : Integer.parseInt(c)).append("</v></c>");
            col++;
        }
        b.append("</row>");
        return b.toString();
    }

    private String nullSafe(String v) {
        return v == null ? "" : v;
    }

    /** 取 sharedStrings 索引，不存在则动态追加 */
    private String resolve(List<String> shared, String v) {
        String val = v == null ? "" : v;
        int idx = shared.indexOf(val);
        if (idx < 0) {
            shared.add(val);
            idx = shared.size() - 1;
        }
        return String.valueOf(idx);
    }

    private String colName(int i) {
        StringBuilder sb = new StringBuilder();
        while (i > 0) {
            i--;
            sb.insert(0, (char) ('A' + i % 26));
            i /= 26;
        }
        return sb.toString();
    }

    private void add(List<String> shared, String v) {
        if (v != null && !v.isBlank() && !shared.contains(v)) {
            shared.add(v);
        }
    }

    private String sharedStrings(List<String> shared) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        b.append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
                .append(shared.size()).append("\" uniqueCount=\"").append(shared.size()).append("\">");
        for (String s : shared) {
            b.append("<si><t xml:space=\"preserve\">").append(esc(s == null ? "" : s)).append("</t></si>");
        }
        b.append("</sst>");
        return b.toString();
    }

    private String workbook(ScanRecord scan) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="漏洞明细" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""";
    }

    private String workbookRels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
                </Relationships>""";
    }

    private String contentTypes() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>""";
    }

    private String rels() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""";
    }

    private String styles() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="2">
                    <font><sz val="11"/><name val="Microsoft YaHei"/></font>
                    <font><b/><sz val="11"/><name val="Microsoft YaHei"/></font>
                  </fonts>
                  <fills count="2">
                    <fill><patternFill patternType="none"/></fill>
                    <fill><patternFill patternType="gray125"/></fill>
                  </fills>
                  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                  <cellXfs count="2">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                  </cellXfs>
                </styleSheet>""";
    }

    private void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String esc(String s) {
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
