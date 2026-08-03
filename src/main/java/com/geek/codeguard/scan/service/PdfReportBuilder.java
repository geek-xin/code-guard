package com.geek.codeguard.scan.service;

import com.geek.codeguard.scan.model.ScanFinding;
import com.geek.codeguard.scan.model.ScanRecord;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * PDF 报告生成（OpenPDF）：自动探测系统中文字体并嵌入，保证中文正常显示。
 */
@Component
@Slf4j
public class PdfReportBuilder {

    private static final Map<String, String> SEV_LABEL = Map.of(
            "CRITICAL", "严重", "HIGH", "高危", "MEDIUM", "中危", "LOW", "低危", "INFO", "提示");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final String[][] FONT_CANDIDATES = {
            // macOS：PingFang 在较新系统路径已变更，优先 Hiragino（OpenPDF 编码正常）
            {"/System/Library/Fonts/Hiragino Sans GB.ttc", "Hiragino Sans GB"},
            {"~/Library/Fonts/PingFang.ttc", "PingFang SC"},
            {"~/Library/Fonts/苹方黑体-准-简.ttf", "PingFang SC"},
            {"/System/Library/Fonts/PingFang.ttc", "PingFang SC"},
            // Linux
            {"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", "Noto Sans CJK SC"},
            {"/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", "WenQuanYi Zen Hei"},
            {"/usr/share/fonts/truetype/wqy/wqy-microhei.ttc", "WenQuanYi Micro Hei"},
            {"/usr/share/fonts/truetype/arphic/uming.ttc", "AR PL UMing CN"},
            // Windows
            {"C:\\Windows\\Fonts\\msyh.ttc", "Microsoft YaHei"},
            {"C:\\Windows\\Fonts\\simhei.ttf", "SimHei"},
            {"C:\\Windows\\Fonts\\simsun.ttc", "SimSun"},
            // macOS 兜底（部分系统可正常嵌入）
            {"/System/Library/Fonts/STHeiti Light.ttc", "Heiti SC"},
    };

    private BaseFont cjkFont;

    public byte[] build(ScanRecord scan, List<ScanFinding> findings) {
        try {
            ensureFont();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(doc, bos);
            doc.open();

            Font titleFont = font(18, Font.BOLD);
            Font headFont = font(11, Font.BOLD);
            Font bodyFont = font(9, Font.NORMAL);
            Font smallFont = font(8, Font.NORMAL);
            Font tinyFont = font(7, Font.NORMAL);

            // 标题
            Paragraph title = new Paragraph("CodeGuard 代码安全扫描报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(title);
            Paragraph sub = new Paragraph("项目：" + safe(scan.getProjectName()) + "    状态：" + safe(scan.getStatus())
                    + "    触发方式：" + ("SCHEDULED".equals(scan.getTrigger()) ? "定时扫描" : "手动扫描")
                    + "    时间：" + fmt(scan.getStartedAt())
                    + "    耗时：" + (scan.getDurationMs() == null ? "-" : (scan.getDurationMs() / 1000.0) + "s"), smallFont);
            sub.setAlignment(Element.ALIGN_CENTER);
            doc.add(sub);
            doc.add(new Paragraph(" ", bodyFont));

            // 汇总
            Map<String, Object> s = scan.getSummary() == null ? Map.of() : scan.getSummary();
            doc.add(new Paragraph("一、漏洞汇总", headFont));
            PdfPTable summary = new PdfPTable(6);
            summary.setWidthPercentage(100);
            summary.setKeepTogether(true);
            String[] cols = {"严重", "高危", "中危", "低危", "提示", "合计"};
            int[] sevVals = {num(s.get("critical")), num(s.get("high")), num(s.get("medium")),
                    num(s.get("low")), num(s.get("info")), num(s.get("total"))};
            for (String c : cols) summary.addCell(cell(c, headFont, true));
            for (int v : sevVals) summary.addCell(cell(String.valueOf(v), bodyFont, false));
            doc.add(summary);
            doc.add(new Paragraph(" ", bodyFont));

            // 引擎分布
            Object eng = s.get("byEngine");
            if (eng instanceof Map<?, ?> m && !m.isEmpty()) {
                doc.add(new Paragraph("二、按引擎分布", headFont));
                PdfPTable engineTable = new PdfPTable(2);
                engineTable.setWidthPercentage(100);
                engineTable.setKeepTogether(true);
                engineTable.addCell(cell("引擎", headFont, true));
                engineTable.addCell(cell("数量", headFont, true));
                for (var e : m.entrySet()) {
                    engineTable.addCell(cell(engineLabel(String.valueOf(e.getKey())), bodyFont, false));
                    engineTable.addCell(cell(String.valueOf(e.getValue()), bodyFont, false));
                }
                doc.add(engineTable);
                doc.add(new Paragraph(" ", bodyFont));
            }

            // 漏洞明细
            doc.add(new Paragraph("三、漏洞明细（共 " + findings.size() + " 条）", headFont));
            List<ScanFinding> sorted = findings.stream()
                    .sorted((a, b) -> Integer.compare(sevRank(b.getSeverity()), sevRank(a.getSeverity())))
                    .toList();
            PdfPTable table = new PdfPTable(new float[]{0.9f, 3.3f, 1.1f, 2.1f, 1.6f});
            table.setWidthPercentage(100);
            // 允许超长行跨页拆分，避免整行放不下时产生大量空白页
            table.setSplitLate(false);
            String[] headers = {"等级", "漏洞", "引擎", "位置", "编号"};
            for (String h : headers) table.addCell(cell(h, headFont, true));
            for (ScanFinding f : sorted) {
                table.addCell(cell(sevLabel(f.getSeverity()), bodyFont, false));
                table.addCell(cell(trimText(MarkdownReportRenderer.inlineClean(f.getTitle()), 200), bodyFont, false));
                table.addCell(cell(engineLabel(f.getEngine()), bodyFont, false));
                table.addCell(cell(trimText(ReportPaths.shortPath(f.getFile()), 60)
                        + (f.getLine() != null ? " : " + f.getLine() : ""), bodyFont, false));
                PdfPCell idCell = cell(trimText(safe(f.getVulnId()), 60), tinyFont, false);
                idCell.setNoWrap(true);
                table.addCell(idCell);
            }
            doc.add(table);
            doc.add(new Paragraph(" ", bodyFont));

            // AI 审查意见
            if (scan.getAgentReview() != null && !scan.getAgentReview().isBlank()) {
                doc.add(new Paragraph("四、AI 审查意见", headFont));
                renderMarkdown(doc, scan.getAgentReview(), bodyFont, headFont, smallFont);
            }

            doc.add(new Paragraph("本报告由 CodeGuard 代码安全分析平台自动生成 · " + fmt(Instant.now().toString()), smallFont));
            doc.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 PDF 报告失败: " + e.getMessage(), e);
        }
    }

    private synchronized void ensureFont() {
        if (cjkFont != null) {
            return;
        }
        for (String[] cand : FONT_CANDIDATES) {
            try {
                String candidate = cand[0].startsWith("~/")
                        ? System.getProperty("user.home") + cand[0].substring(1)
                        : cand[0];
                File f = new File(candidate);
                if (!f.exists()) {
                    continue;
                }
                String path = candidate;
                if (path.toLowerCase().endsWith(".ttc")) {
                    path = path + ",0";
                }
                cjkFont = BaseFont.createFont(path, "Identity-H", BaseFont.EMBEDDED);
                log.info("PDF 使用中文字体: {}", candidate);
                return;
            } catch (Exception e) {
                log.debug("字体不可用 {}: {}", cand[0], e.getMessage());
            }
        }
        try {
            cjkFont = BaseFont.createFont();
            log.warn("未找到中文字体，PDF 中文可能无法显示，请安装 Noto Sans CJK / 文泉驿 / 微软雅黑");
        } catch (Exception e) {
            throw new IllegalStateException("无可用字体", e);
        }
    }

    /** 渲染 AI 审查的 Markdown 块到 PDF 文档 */
    private void renderMarkdown(Document doc, String markdown,
                                Font body, Font head, Font small) {
        List<MarkdownReportRenderer.Block> blocks = MarkdownReportRenderer.parse(markdown);
        for (MarkdownReportRenderer.Block b : blocks) {
            switch (b.type) {
                case H1, H2, H3, H4 -> {
                    float size = switch (b.type) {
                        case H1 -> 15f;
                        case H2 -> 13f;
                        case H3 -> 11f;
                        default -> 10f;
                    };
                    Paragraph p = new Paragraph(safe(b.text), font(size, Font.BOLD));
                    p.setSpacingBefore(8);
                    p.setSpacingAfter(4);
                    doc.add(p);
                }
                case PARAGRAPH, QUOTE -> {
                    Paragraph p = new Paragraph(safe(b.text), body);
                    p.setSpacingAfter(5);
                    if (b.type == MarkdownReportRenderer.Type.QUOTE) {
                        p.setIndentationLeft(12);
                    }
                    doc.add(p);
                }
                case LIST -> {
                    Paragraph p = new Paragraph("•  " + safe(b.text), body);
                    p.setIndentationLeft(10);
                    p.setSpacingAfter(3);
                    doc.add(p);
                }
                case CODE -> {
                    Paragraph p = new Paragraph(safe(b.text), font(7.5f, Font.NORMAL));
                    p.setLeading(10);
                    p.setSpacingBefore(4);
                    p.setSpacingAfter(6);
                    doc.add(p);
                }
                case TABLE -> {
                    if (b.table != null && !b.table.isEmpty()) {
                        doc.add(renderTable(b.table, body, head));
                        doc.add(new Paragraph(" ", body));
                    }
                }
                default -> {
                    // BLANK 忽略
                }
            }
        }
    }

    private PdfPTable renderTable(List<List<String>> rows, Font body, Font head) {
        int cols = 1;
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        PdfPTable t = new PdfPTable(cols);
        t.setWidthPercentage(100);
        t.setSplitLate(false);
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            boolean isHeader = i == 0;
            for (int c = 0; c < cols; c++) {
                String txt = c < row.size() ? row.get(c) : "";
                t.addCell(cell(trimText(txt, 400), isHeader ? head : body, isHeader));
            }
        }
        return t;
    }

    private Font font(float size, int style) {
        return new Font(cjkFont, size, style);
    }

    private PdfPCell cell(String text, Font font, boolean header) {
        PdfPCell c = new PdfPCell(new Phrase(safe(text), font));
        c.setPadding(4);
        if (header) {
            c.setBackgroundColor(new java.awt.Color(22, 22, 22));
            c.setPhrase(new Phrase(safe(text), new Font(cjkFont, 10, Font.BOLD, java.awt.Color.WHITE)));
        }
        return c;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    /** 截断超长文本，避免单元格/段落行高异常撑爆页面 */
    private String trimText(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }

    private String fmt(String iso) {
        try {
            return FMT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso == null ? "-" : iso;
        }
    }

    private int num(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
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
