package com.canalmind.rag.split;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 管道表解析器。
 *
 * <p><b>只做最小必要的事</b>：把语料里的标准管道表解析为「表头 + 逐行数据」。
 * 语料写作格式已由 {@code docs/规则语料/README.md} §3.2 硬性约束为
 * "必须是标准 Markdown 管道表，不得用图片或缩进文本模拟"，
 * 且该约束由 {@code validate_corpus.py} 持续守护——
 * 因此这里<strong>不需要</strong>处理 PDF 抽取那类脏数据
 * （知识库第 211 行提到"传统 PDF 解析会把表格转成混乱的文本"，那是另一类问题）。
 *
 * <p>知识库依据：《RAG 文档处理与切分策略》<b>第 213 行</b>给出最基础做法
 * "表格解析 + Markdown 化…Markdown 表格至少保留了行列关系"；
 * <b>第 150 行</b>要求表格"单独结构化抽取，不与正文混切"。
 */
public final class MarkdownTableParser {

    private MarkdownTableParser() {
    }

    /** 解析结果：表头 + 数据行 + 该表在原文中的行范围（1 起，闭区间）。 */
    public record ParsedTable(List<String> headers, List<List<String>> rows, int startLine, int endLine) {

        public int rowCount() {
            return rows.size();
        }

        /** 行数与表头列数一致的表视为结构完好。 */
        public boolean isWellFormed() {
            return !headers.isEmpty() && rows.stream().allMatch(r -> r.size() == headers.size());
        }
    }

    /** 该行是否可能是管道表的数据行（以 | 开头且以 | 结尾）。 */
    static boolean looksLikeTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.endsWith("|") && t.length() >= 2;
    }

    /** 该行是否是分隔行（如 {@code |---|---|}）。允许多余空格与 {@code :} 对齐标记。 */
    static boolean isSeparatorRow(String line) {
        String t = line.trim();
        if (!looksLikeTableRow(t)) {
            return false;
        }
        List<String> cells = splitCells(t);
        if (cells.isEmpty()) {
            return false;
        }
        for (String c : cells) {
            // 每格必须是至少一个 - 组成，可带左右对齐的冒号
            if (!c.trim().matches(":?-{1,}:?")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按未转义的 {@code |} 拆格，并去掉首尾管道符。
     *
     * <p>语料里存在转义管道（如单元格内写 {@code \\|}），因此不能简单 {@code split("|")}。
     */
    static List<String> splitCells(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                // 转义管道：保留字面量 | ，不作为分隔符
                cur.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /**
     * 从 {@code lines} 的 {@code startIndex} 行起解析一个表格。
     *
     * @return 解析结果；若该处不是一个合法表格则返回 {@code null}
     */
    public static ParsedTable parseAt(List<String> lines, int startIndex, int lineOffset) {
        if (startIndex + 1 >= lines.size()) {
            return null;
        }
        if (!looksLikeTableRow(lines.get(startIndex)) || !isSeparatorRow(lines.get(startIndex + 1))) {
            return null;
        }
        List<String> headers = splitCells(lines.get(startIndex));
        List<List<String>> rows = new ArrayList<>();
        int i = startIndex + 2;
        while (i < lines.size() && looksLikeTableRow(lines.get(i)) && !isSeparatorRow(lines.get(i))) {
            rows.add(splitCells(lines.get(i)));
            i++;
        }
        int startLine = lineOffset + startIndex + 1;
        int endLine = lineOffset + i;
        return new ParsedTable(headers, rows, startLine, endLine);
    }

    /** 把解析结果还原为规范 Markdown 文本（用于表格 chunk 的 content 字段）。 */
    public static String toMarkdown(ParsedTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderRow(table.headers())).append('\n');
        List<String> seps = new ArrayList<>();
        for (int i = 0; i < table.headers().size(); i++) {
            seps.add("---");
        }
        sb.append(renderRow(seps)).append('\n');
        for (List<String> row : table.rows()) {
            sb.append(renderRow(row)).append('\n');
        }
        return sb.toString();
    }

    /** 渲染一行：{@code | a | b |}（把字面量 | 转义，避免破坏表格结构）。 */
    private static String renderRow(List<String> cells) {
        StringBuilder sb = new StringBuilder("|");
        for (String c : cells) {
            sb.append(' ').append(c.replace("|", "\\|")).append(" |");
        }
        return sb.toString();
    }
}
