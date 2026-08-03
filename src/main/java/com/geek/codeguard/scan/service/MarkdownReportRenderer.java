package com.geek.codeguard.scan.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown 渲染器：把 AI 审查意见（Markdown）解析为结构化块，
 * 供 PDF / Word 报告统一排版，避免「#」「**」「|」等原始语法符号直接暴露。
 */
public final class MarkdownReportRenderer {

    public enum Type {
        H1, H2, H3, H4, PARAGRAPH, LIST, TABLE, CODE, QUOTE, BLANK
    }

    public static final class Block {
        public final Type type;
        /** 文本内容（标题/段落/列表项/引用/代码行） */
        public final String text;
        /** 表格数据：首行为表头 */
        public final List<List<String>> table;

        public Block(Type type, String text, List<List<String>> table) {
            this.type = type;
            this.text = text;
            this.table = table;
        }

        public static Block of(Type type, String text) {
            return new Block(type, text, null);
        }

        public static Block table(List<List<String>> rows) {
            return new Block(Type.TABLE, null, rows);
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]|\\d+\\.)\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s*(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    private MarkdownReportRenderer() {
    }

    public static List<Block> parse(String md) {
        List<Block> blocks = new ArrayList<>();
        if (md == null || md.isBlank()) {
            return blocks;
        }
        List<String> lines = md.replace("\r\n", "\n").replace('\r', '\n')
                .split("\n", -1) == null ? List.of() : java.util.Arrays.asList(md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));

        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                blocks.add(Block.of(Type.BLANK, ""));
                i++;
                continue;
            }

            // 分隔线
            if (trimmed.matches("^([-*_])\\1{2,}\\s*$")) {
                blocks.add(Block.of(Type.BLANK, ""));
                i++;
                continue;
            }

            // 代码块
            if (trimmed.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < n && !lines.get(i).trim().startsWith("```")) {
                    if (code.length() > 0) {
                        code.append('\n');
                    }
                    code.append(lines.get(i));
                    i++;
                }
                i++; // 跳过闭合 ``` 或到达末尾
                if (code.length() > 0) {
                    blocks.add(Block.of(Type.CODE, code.toString()));
                }
                continue;
            }

            // 标题
            Matcher hm = HEADING.matcher(trimmed);
            if (hm.matches()) {
                int level = hm.group(1).length();
                Type t = switch (level) {
                    case 1 -> Type.H1;
                    case 2 -> Type.H2;
                    case 3 -> Type.H3;
                    default -> Type.H4;
                };
                blocks.add(Block.of(t, inlineClean(hm.group(2).trim())));
                i++;
                continue;
            }

            // 表格：收集连续 | 行
            if (TABLE_ROW.matcher(trimmed).matches()) {
                List<List<String>> rows = new ArrayList<>();
                boolean header = true;
                while (i < n && TABLE_ROW.matcher(lines.get(i).trim()).matches()) {
                    String t = lines.get(i).trim();
                    // 跳过分隔行 |---|:---|
                    if (t.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")) {
                        i++;
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (String c : t.split("\\|")) {
                        cells.add(inlineClean(c.trim()));
                    }
                    // 去掉首尾空单元格（| a | b | 首尾 split 出的空串）
                    if (!cells.isEmpty() && cells.get(0).isEmpty()) {
                        cells.remove(0);
                    }
                    if (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                        cells.remove(cells.size() - 1);
                    }
                    rows.add(cells);
                    i++;
                }
                if (!rows.isEmpty()) {
                    blocks.add(Block.table(rows));
                }
                continue;
            }

            // 列表项：收集连续列表
            Matcher lm = LIST_ITEM.matcher(trimmed);
            if (lm.matches()) {
                List<String> items = new ArrayList<>();
                items.add(inlineClean(lm.group(2).trim()));
                i++;
                while (i < n) {
                    String nt = lines.get(i).trim();
                    Matcher nm = LIST_ITEM.matcher(nt);
                    if (nm.matches()) {
                        items.add(inlineClean(nm.group(2).trim()));
                        i++;
                    } else if (!nt.isEmpty() && !nt.startsWith("#") && !TABLE_ROW.matcher(nt).matches()
                            && !nt.startsWith("```") && !LIST_ITEM.matcher(nt).matches()) {
                        // 续行（缩进的换行内容）
                        items.add(inlineClean(nt));
                        i++;
                    } else {
                        break;
                    }
                }
                for (String item : items) {
                    blocks.add(Block.of(Type.LIST, item));
                }
                continue;
            }

            // 引用
            Matcher qm = QUOTE.matcher(trimmed);
            if (qm.matches()) {
                blocks.add(Block.of(Type.QUOTE, inlineClean(qm.group(1).trim())));
                i++;
                continue;
            }

            // 普通段落：合并连续普通行
            StringBuilder para = new StringBuilder(inlineClean(trimmed));
            i++;
            while (i < n) {
                String nt = lines.get(i).trim();
                if (nt.isEmpty() || HEADING.matcher(nt).matches() || TABLE_ROW.matcher(nt).matches()
                        || LIST_ITEM.matcher(nt).matches() || nt.startsWith("```")
                        || QUOTE.matcher(nt).matches() || nt.matches("^([-*_])\\1{2,}\\s*$")) {
                    break;
                }
                para.append(' ').append(inlineClean(nt));
                i++;
            }
            blocks.add(Block.of(Type.PARAGRAPH, para.toString()));
        }
        return blocks;
    }

    /** 行内 Markdown 清理：加粗/斜体/行内代码/链接等符号去除 */
    public static String inlineClean(String s) {
        if (s == null) {
            return "";
        }
        String r = s;
        // [text](url) -> text
        r = r.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // **text** 或 __text__
        r = r.replaceAll("(\\*\\*|__)(.+?)(\\*\\*|__)", "$2");
        // *text* 或 _text_
        r = r.replaceAll("(^|[^*])\\*([^*]+)\\*", "$1$2");
        r = r.replaceAll("(^|[^_])_([^_]+)_", "$1$2");
        // `code`
        r = r.replace("`", "");
        // ~~text~~
        r = r.replaceAll("~~(.+?)~~", "$1");
        // <br> 等
        r = r.replace("<br>", " ").replace("<br/>", " ").replace("<br />", " ");
        return r.trim();
    }
}
