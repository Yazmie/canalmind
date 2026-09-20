package com.canalmind.rag.split;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语料文档读取与元数据解析。
 *
 * <p>解析对象是决策 <b>C-20</b> 规定的固定头部结构：
 * 1 行仿真声明 + 7 字段元数据表（文号、标题、发布机构、版本、生效期、替代关系、说明）。
 * 该结构由 {@code validate_corpus.py} 持续守护，故这里是<strong>严格解析</strong>而不是尽力容错——
 * 一旦头部格式变化，应该让切分器显式报错，而不是悄悄切出错误的元数据。
 */
public final class DocumentParser {

    /** 换行常量：用 (char) 10 计算，**不用字符串转义**——写入层折叠反斜杠会让它静默失效（见 docs/工程复盘/工程方法/转义与写入层的静默损坏.md）。 */
    static final String NEWLINE = String.valueOf((char) 10);


    /** 元数据行：{@code | 文号 | PLYH-GZ-2026-001 |} */
    private static final Pattern META_ROW = Pattern.compile("^\\|\\s*(文号|标题|发布机构|版本|生效期|替代关系|说明)\\s*\\|\\s*(.*?)\\s*\\|$");

    /** 生效期：{@code 2026-09-16 至 长期有效} / {@code 2026-09-16 至 2026-12-31} */
    private static final Pattern EFFECTIVE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s*至\\s*(长期有效|\\d{4}-\\d{2}-\\d{2})$");

    private static final Pattern H1 = Pattern.compile("^#\\s+(.+?)\\s*$");
    private static final Pattern H2 = Pattern.compile("^##\\s+(.+?)\\s*$");

    public static final String LONG_TERM = "长期有效";

    private DocumentParser() {
    }

    /**
     * 读取一篇语料文档。
     *
     * @param file 语料 .md 文件
     * @throws IllegalArgumentException 头部元数据缺失或不符合 C-20 规格
     */
    public static CorpusDocument load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取语料失败: " + file, e);
        }
        return parse(file, text);
    }

    /** 从文本解析（与文件读取分离，便于单测直接喂字符串）。 */
    public static CorpusDocument parse(Path file, String text) {
        // 归一化：
        // 1) 去除 UTF-8 BOM —— 本项目真实踩过（见 docs/工程复盘/环境坑/脚本文件BOM.md），
        //    BOM 会让第一行变成 "\uFEFF> **【仿真语料声明】**…"，
        //    使 H1/元数据匹配整体失配，且**不报错**，只是静默少切。
        // 2) 统一换行 —— 语料约定 LF，但工具链可能引入 CRLF；CR 会让
        //    `line.trim()` 之外的比较（如按行 split 后再拼接）出现隐蔽差异。
        text = normalizeText(text);
        String fileName = file.getFileName().toString();
        String docId = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        String categoryNo = docId.length() >= 2 && Character.isDigit(docId.charAt(0)) && Character.isDigit(docId.charAt(1))
                ? docId.substring(0, 2)
                : "";

        String docNo = null;
        String title = null;
        String issuer = null;
        String version = null;
        String effectiveRaw = null;
        String supersedes = null;
        String note = null;

        for (String line : text.split(NEWLINE, -1)) {
            Matcher m = META_ROW.matcher(line.trim());
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1);
            String value = m.group(2).trim();
            switch (key) {
                case "文号" -> docNo = value;
                case "标题" -> title = value;
                case "发布机构" -> issuer = value;
                case "版本" -> version = value;
                case "生效期" -> effectiveRaw = value;
                case "替代关系" -> supersedes = value;
                case "说明" -> note = value;
                default -> { }
            }
        }

        // 元数据块是 C-21 的前置条件；缺失必须显式失败，不能降级
        requireNonBlank(docNo, "文号", docId);
        requireNonBlank(title, "标题", docId);
        requireNonBlank(version, "版本", docId);
        requireNonBlank(effectiveRaw, "生效期", docId);

        String effectiveFrom;
        String effectiveTo;
        Matcher em = EFFECTIVE.matcher(effectiveRaw);
        if (!em.matches()) {
            throw new IllegalArgumentException(
                    "文档 " + docId + " 的「生效期」不符合 C-20 格式「YYYY-MM-DD 至 YYYY-MM-DD|长期有效」: " + effectiveRaw);
        }
        effectiveFrom = em.group(1);
        effectiveTo = LONG_TERM.equals(em.group(2)) ? null : em.group(2);

        // 标题以元数据字段为准；H1 若存在且不一致，说明文档内部不自洽——同样显式报错
        for (String line : text.split(NEWLINE, -1)) {
            Matcher m = H1.matcher(line.trim());
            if (m.matches()) {
                String h1 = m.group(1).trim();
                if (!h1.equals(title)) {
                    throw new IllegalArgumentException(
                            "文档 " + docId + " 的 H1「" + h1 + "」与元数据「标题」字段「" + title + "」不一致");
                }
                break;
            }
        }

        return new CorpusDocument(docId, categoryNo, docNo, title, issuer, version,
                effectiveFrom, effectiveTo, supersedes, note, text, file);
    }

    /**
     * 文本归一化：去 BOM + 统一换行为 LF。
     *
     * <p><b>为什么必须在切分前做</b>：BOM（U+FEFF）与 CR 都属于"看不见但会让字符串比较失配"的字符。
     * 本项目在语料校验器上真实踩过一次 BOM（{@code docs/工程复盘/环境坑/脚本文件BOM.md}），
     * 当时也是"校验通过但内容错"。放在这里做一次，比在每个正则里各自容错更可靠。
     *
     * <p><b>⚠️ 这里的两个 replace 都是<em>字面量</em>替换。</b>
     * 第一个用双反斜杠形式（\\r\\n 与 \\n）
     * 表示"反斜杠+r"与"反斜杠+n"这两种**两字符**文本——不是真实回车换行。
     * 若误写成单反斜杠，replace 会在文本里寻找"真实 CR 紧跟真实 LF"，
     * 对已经归一化的文本是空操作，却会把文中本来就有的字面 CR/LF 破坏掉。
     * 第二个 replace 的参数是**真实字符**（'\r' 与 '\n'），两者不要混。
     */
    static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        if (!text.isEmpty() && text.charAt(0) == (char) 0xFEFF) {
            text = text.substring(1);
        }
        // ⚠️ 刻意不使用字符串转义常量，改用**计算出的真实字符**。
        // 原因（实测，见 docs/工程复盘/工程方法/转义与写入层的静默损坏.md）：
        // 同一个换行，在 Java 源码里写成"一个反斜杠"与"两个反斜杠"，
        // 运行时的长度分别是 2 与 4，而只有 2 字符那种才能匹配到真实 CRLF。
        // 写入层会把反斜杠数量折叠，产生"看起来对、实际匹配不到、且不报错"的静默错误
        // （本项目因此出现过 CRLF 未被归一化、表格静默少切）。
        // 用 (char) 13 / (char) 10 构造，任何工具都无法折坏，读者也不必数反斜杠。
        String cr = String.valueOf((char) 13);
        String lf = String.valueOf((char) 10);
        // ⚠️ 顺序**不可交换**：必须先把 CRLF 收成一个 LF，再处理落单的 CR。
        // 若先做 cr->lf，则 CRLF 会变成两个 LF（实测：长度不变、LF 数翻倍），
        // 于是每一处换行都多出一行——不报错，但整个文档的行结构错位。
        return text.replace(cr + lf, lf).replace(cr, lf);
    }

    private static void requireNonBlank(String value, String field, String docId) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("文档 " + docId + " 缺少元数据字段「" + field + "」");
        }
    }

    /** 列出语料目录下的全部 .md（排除 README.md），按文件名排序。 */
    public static List<Path> listCorpusFiles(Path corpusDir) {
        try (var stream = Files.list(corpusDir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("README.md"))
                    .sorted()
                    .forEach(files::add);
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("列出语料目录失败: " + corpusDir, e);
        }
    }

    /**
     * 返回正文起始行号（1 起）：跳过仿真声明、元数据表、空行与 H1。
     *
     * <p>不是为了"美化"，而是为了让正文 chunk 的 {@code sourceLines} 溯源准确——
     * 若把元数据块也算进正文，后续用行号回原文核对时会整体偏移。
     */
    public static int findBodyStartLine(String text) {
        String[] lines = text.split(NEWLINE, -1);
        boolean seenH1 = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (H1.matcher(t).matches()) {
                seenH1 = true;
                continue;
            }
            if (seenH1 && !t.isEmpty()) {
                return i + 1;
            }
        }
        return lines.length;
    }

    /** 从任意级别的标题行中提取标题文字，非标题返回 {@code null}。 */
    static String headingText(String line) {
        String t = line.trim();
        if (!t.startsWith("#")) {
            return null;
        }
        int i = 0;
        while (i < t.length() && t.charAt(i) == '#') {
            i++;
        }
        if (i == 0 || i >= t.length() || t.charAt(i) != ' ') {
            return null;
        }
        return t.substring(i + 1).trim();
    }

    static boolean isH2(String line) {
        return line.trim().startsWith("## ") && !line.trim().startsWith("### ");
    }
}
