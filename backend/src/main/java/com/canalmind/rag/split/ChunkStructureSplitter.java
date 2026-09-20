package com.canalmind.rag.split;

import com.canalmind.rag.split.MarkdownTableParser.ParsedTable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知切分器：以「条」为切分单元（决策 <b>C-21</b>）。
 *
 * <h2>切分规则</h2>
 * <ol>
 *   <li><b>条（{@code ### 第X条 【标题】}）是唯一主切分单元</b>——454 条即为 454 个正文 chunk。</li>
 *   <li><b>章（{@code ## 第X章}）不独立成块</b>，只作为 {@code chapterPath} 元数据
 *       （语料写作约定 §3.2：章"不作为独立 chunk"）。</li>
 *   <li><b>款（{@code #### 一、}）与项（{@code 1.}）仅在条超过阈值时</b>才升格为子 chunk。
 *       阈值实测不触发（见 {@link ChunkingProperties}），该路径为防御性并有单测覆盖。</li>
 *   <li><b>表格单独抽取</b>为 {@link ChunkType#TABLE} chunk，正文中该位置替换为占位标记。
 *       依据 {@code docs/规则语料/README.md} §3.2「表格单独结构化抽取，不与正文混切」
 *       与知识库《RAG 文档处理与切分策略》第 150 行。</li>
 * </ol>
 *
 * <h2>为什么正文里保留一个"占位标记"而不是把表格整个删掉</h2>
 * 知识库第 150 行要求表格<strong>单独抽取</strong>，但若正文 chunk 里连"这里有一张表"都不剩，
 * 就会产生知识库第 93 行所说的<strong>上下文蒸发</strong>：
 * 模型读到"建材类货物过闸费费率如下："却看不到任何表格存在过的痕迹，
 * 也无从知道该去引用表格 chunk。故正文保留一行引用标记，指向表格 chunk 的标识。
 */
public class ChunkStructureSplitter implements Splitter {

    /** 换行常量：用 (char) 10 计算，**不用字符串转义**——写入层折叠反斜杠会让它静默失效（见 docs/工程复盘/工程方法/转义与写入层的静默损坏.md）。 */
    static final String NEWLINE = String.valueOf((char) 10);


    /** 策略标识（写入 chunk，用于第 4 周消融对比时区分来源）。 */
    public static final String STRATEGY = "structure-article-v1";

    /**
     * 切分器版本。
     *
     * <p>⚠️ <b>改变切分逻辑必须提升此版本号</b>。依据《RAG 优化》第 366 行
     * "每一轮只改变少量变量，同时记录解析器、Chunk、Embedding、索引…的版本"——
     * 否则第 4 周拿到两组分数却无法说明它们来自哪一版切分。
     */
    public static final String VERSION = "1.0.0";

    /** 条标题行：{@code ### 第三条 职责分工} 或 {@code ### 第三条 【职责分工】}。 */
    private static final Pattern ARTICLE_HEAD =
            Pattern.compile("^###\\s+(第[零一二三四五六七八九十百千]+条)\\s*(?:【(.+?)】)?\\s*(.*?)\\s*$");

    /** 款标题行：{@code #### 一、申请方式}。 */
    private static final Pattern CLAUSE_HEAD = Pattern.compile("^####\\s+(.+?)\\s*$");

    /** 项列表行：{@code 1. 船舶已办理过闸申报；}。 */
    private static final Pattern ITEM_LINE = Pattern.compile("^\\d+\\.\\s+.+$");

    /** 占位标记：不动点，便于评测期机械识别正文中的表格引用。 */
    public static final String TABLE_MARKER_PREFIX = "[TABLE:";

    private final ChunkingProperties props;

    public ChunkStructureSplitter() {
        this(ChunkingProperties.defaults());
    }

    public ChunkStructureSplitter(ChunkingProperties props) {
        this.props = props;
    }

    @Override
    public String strategyName() {
        return STRATEGY;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<Chunk> split(CorpusDocument doc) {
        // 在**切分器边界**再做一次换行归一化，而不是只依赖 DocumentParser.parse：
        // ① 表格解析（MarkdownTableParser）拿到的是**未 trim 的原始行**，
        //    CRLF 会让 `^\\|` 之类的行首行尾判定失配，表格就静默不被识别；
        // ② split(doc) 是公开 API，调用方可能自行拼装 rawText（测试、CLI、未来的导入器），
        //    不能假设上游一定归一化过。
        String text = DocumentParser.normalizeText(doc.rawText());
        List<String> lines = List.of(text.split(NEWLINE, -1));
        int bodyStart = DocumentParser.findBodyStartLine(text);

        List<Chunk> chunks = new ArrayList<>();
        // 条的范围（0 基，起闭止开），以及它所属的章
        int articleStart = -1;
        String articleNo = null;
        String articleTitle = null;
        String chapter = null;

        for (int i = bodyStart - 1; i <= lines.size(); i++) {
            boolean atEnd = i == lines.size();
            String line = atEnd ? "" : lines.get(i);
            String trimmed = line.trim();

            if (!atEnd && DocumentParser.isH2(trimmed)) {
                // 章不独立成块，但先结算上一条
                if (articleStart >= 0) {
                    chunks.addAll(emitArticle(doc, lines, articleStart, i, articleNo, articleTitle, chapter));
                    articleStart = -1;
                }
                chapter = DocumentParser.headingText(trimmed);
                continue;
            }

            Matcher am = ARTICLE_HEAD.matcher(trimmed);
            if (am.matches()) {
                if (articleStart >= 0) {
                    chunks.addAll(emitArticle(doc, lines, articleStart, i, articleNo, articleTitle, chapter));
                }
                articleStart = i;
                articleNo = am.group(1);
                // 标题有两种写入形式：`### 第X条 【标题】`（README 推荐）与 `### 第X条 标题`。
                // 后者以前**完全不被识别**（正则结尾 $\s* 不允许正文），于是一整条被静默丢弃——
                // 这是"正则写得太严 → 静默漏数据"，比报错危险得多。
                String bracketed = am.group(2);
                String plain = am.group(3);
                articleTitle = bracketed != null ? bracketed : (plain == null ? "" : plain);
                continue;
            }

            if (atEnd && articleStart >= 0) {
                chunks.addAll(emitArticle(doc, lines, articleStart, i, articleNo, articleTitle, chapter));
                articleStart = -1;
            }
        }
        return chunks;
    }

    /**
     * 产出某一条的全部 chunk：1 个正文 chunk（或拆分为多个）+ N 个表格 chunk。
     *
     * @param startIdx 条标题行的 0 基下标
     * @param endIdx   条结束的 0 基下标（不含）
     */
    private List<Chunk> emitArticle(CorpusDocument doc, List<String> lines, int startIdx, int endIdx,
                                    String articleNo, String articleTitle, String chapter) {
        List<Chunk> result = new ArrayList<>();
        int bodyFrom = startIdx + 1;
        if (bodyFrom > endIdx) {
            bodyFrom = endIdx;
        }

        // 1) 先抽出该条内的所有表格，并把它们在正文中的行段记下来
        List<TableSpan> tables = new ArrayList<>();
        int i = bodyFrom;
        while (i < endIdx) {
            ParsedTable t = MarkdownTableParser.parseAt(lines, i, 0);
            if (t != null) {
                tables.add(new TableSpan(t, i, i + (t.endLine() - t.startLine() + 1)));
                i += t.endLine() - t.startLine() + 1;
            } else {
                i++;
            }
        }

        // 2) 正文段落：按表格切段，表格位置替换为占位标记
        List<Segment> segments = new ArrayList<>();
        int cur = bodyFrom;
        for (TableSpan span : tables) {
            if (span.from() > cur) {
                segments.add(new Segment(cur, span.from(), null));
            }
            segments.add(new Segment(span.from(), span.to(), span));
            cur = span.to();
        }
        if (cur < endIdx) {
            segments.add(new Segment(cur, endIdx, null));
        }

        // 3) 组装正文文本（占位标记先留空，待表格 chunk 分配好 id 后回填）
        List<String> bodyLines = new ArrayList<>();
        List<Integer> markerLineIdx = new ArrayList<>();
        for (Segment seg : segments) {
            if (seg.table() == null) {
                for (int k = seg.from(); k < seg.to(); k++) {
                    bodyLines.add(lines.get(k));
                }
            } else {
                markerLineIdx.add(bodyLines.size());
                bodyLines.add(""); // 占位，稍后回填
            }
        }

        String bodyText = normalizeBody(bodyLines);

        // 4) 表格 chunk（先建，以便正文标记能引用其 id）
        int tableSeq = 0;
        List<String> pendingMarkers = new ArrayList<>();
        for (TableSpan span : tables) {
            tableSeq++;
            String tableContent = MarkdownTableParser.toMarkdown(span.table());
            String tableChunkId = stableId(doc.docId(), articleNo, tableSeq, ChunkType.TABLE.name(), 0);
            pendingMarkers.add(TABLE_MARKER_PREFIX + tableChunkId + " rows=" + span.table().rowCount()
                    + " cols=" + span.table().headers().size() + "]");

            Chunk tc = baseChunk(doc, chapter, articleNo, articleTitle);
            tc.setChunkType(ChunkType.TABLE);
            tc.setContent(tableContent.trim());
            tc.setCharCount(countNonWhitespace(tableContent));
            tc.setTableHeaders(span.table().headers());
            tc.setTableRows(span.table().rows());
            tc.setSourceLines((span.table().startLine()) + "-" + (span.table().endLine()));
            tc.setChunkId(tableChunkId);
            result.add(tc);
        }

        // 回填正文中的占位标记
        for (int k = 0; k < markerLineIdx.size() && k < pendingMarkers.size(); k++) {
            bodyLines.set(markerLineIdx.get(k), pendingMarkers.get(k));
        }
        bodyText = normalizeBody(bodyLines);

        // 5) 正文 chunk：条在阈值内则整条一块；超阈值才按款/项再拆
        if (countNonWhitespace(bodyText) <= props.maxArticleChars()) {
            Chunk bc = baseChunk(doc, chapter, articleNo, articleTitle);
            bc.setChunkType(ChunkType.BODY);
            bc.setContent(bodyText);
            bc.setCharCount(countNonWhitespace(bodyText));
            bc.setSourceLines((startIdx + 1) + "-" + endIdx);
            bc.setChunkId(stableId(doc.docId(), articleNo, 0, ChunkType.BODY.name(), 0));
            result.add(bc);
        } else {
            result.addAll(splitOversizedArticle(doc, chapter, articleNo, articleTitle, bodyText, startIdx + 1, endIdx));
        }
        return result;
    }

    // ── 条过长时的再拆分（语料实测不触发，防御性路径）────────────────────

    private List<Chunk> splitOversizedArticle(CorpusDocument doc, String chapter, String articleNo,
                                              String articleTitle, String bodyText, int fromLine, int toLine) {
        List<String> lines = new ArrayList<>(List.of(bodyText.split(NEWLINE, -1)));
        List<List<String>> parts = null;
        String mode = null;

        // ① 按款（#### 一、）拆
        List<Integer> bounds = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (CLAUSE_HEAD.matcher(lines.get(i).trim()).matches()) {
                bounds.add(i);
            }
        }
        if (bounds.size() >= 2) {
            parts = splitAtBounds(lines, bounds);
            mode = "clause";
        }

        // ② 按项（1. 2. …）拆
        if (parts == null) {
            List<Integer> itemBounds = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (ITEM_LINE.matcher(lines.get(i).trim()).matches()) {
                    itemBounds.add(i);
                }
            }
            if (itemBounds.size() >= 2) {
                parts = splitAtBounds(lines, itemBounds);
                mode = "item";
            }
        }

        // ③ 按句末标点硬切（最后手段）
        if (parts == null) {
            parts = splitBySentences(bodyText);
            mode = "sentence";
        }

        List<Chunk> out = new ArrayList<>();
        int idx = 0;
        for (List<String> part : parts) {
            String text = String.join(NEWLINE, part).trim();
            if (text.isEmpty()) {
                continue;
            }
            idx++;
            Chunk c = baseChunk(doc, chapter, articleNo, articleTitle);
            c.setChunkType(ChunkType.BODY);
            c.setContent(text);
            c.setCharCount(countNonWhitespace(text));
            c.setPartIndex(idx);
            c.setPartCount(parts.size());
            c.setSourceLines(fromLine + "-" + toLine);
            c.getExtra().put("splitMode", mode);
            c.setChunkId(stableId(doc.docId(), articleNo, 0, ChunkType.BODY.name(), idx));
            out.add(c);
        }
        return out;
    }

    private static List<List<String>> splitAtBounds(List<String> lines, List<Integer> bounds) {
        List<List<String>> parts = new ArrayList<>();
        for (int b = 0; b < bounds.size(); b++) {
            int start = bounds.get(b);
            int end = (b + 1 < bounds.size()) ? bounds.get(b + 1) : lines.size();
            // 条第一条款之前的前言并入第一段，避免丢失开头
            if (b == 0 && start > 0) {
                start = 0;
            }
            parts.add(new ArrayList<>(lines.subList(start, end)));
        }
        return parts;
    }

    /** 按中文句末标点切分，并合并到不超过阈值。 */
    private List<List<String>> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (c == '。' || c == '；' || c == '\n') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            sentences.add(cur.toString().trim());
        }

        List<List<String>> parts = new ArrayList<>();
        List<String> bucket = new ArrayList<>();
        int size = 0;
        for (String s : sentences) {
            if (size > 0 && size + s.length() > props.maxArticleChars()) {
                parts.add(bucket);
                bucket = new ArrayList<>();
                size = 0;
            }
            bucket.add(s);
            size += s.length();
        }
        if (!bucket.isEmpty()) {
            parts.add(bucket);
        }
        return parts;
    }

    // ── 工具 ────────────────────────────────────────────────────────────

    private Chunk baseChunk(CorpusDocument doc, String chapter, String articleNo, String articleTitle) {
        Chunk c = new Chunk();
        c.setDocId(doc.docId());
        c.setDocTitle(doc.title());
        c.setDocNo(doc.docNo());
        c.setVersion(doc.version());
        c.setEffectiveFrom(doc.effectiveFrom());
        c.setEffectiveTo(doc.effectiveTo());
        c.setSupersedes(doc.supersedes());
        c.setDocCategoryNo(doc.categoryNo());
        List<String> path = new ArrayList<>();
        path.add(chapter == null ? doc.title() : chapter);
        c.setChapterPath(path);
        c.setArticleNo(articleNo);
        c.setArticleTitle(articleTitle);
        c.setStrategy(strategyName());
        c.setSplitterVersion(version());
        return c;
    }

    /**
     * 稳定 chunk 标识：由（文档 + 条号 + 序号 + 类型 + 部件号）派生。
     *
     * <p>刻意<strong>不含内容哈希</strong>：这样"改了一个字的正文"不会让整篇所有 chunk 都换 id，
     * 而是只有受影响的那一个变化。这对增量更新友好——依据《RAG 知识库文档如何更新》
     * 关于版本控制与去重的思路，标识应描述<strong>位置</strong>，内容变化另行检测。
     *
     * <p>不使用 {@code String.hashCode()} 或随机数：前者跨 JVM 虽稳定但仅 32 位、碰撞面偏大，
     * 后者会破坏"同输入同输出"，使评测不可复现。
     */
    private static String stableId(String docId, String articleNo, int seq, String type, int partIndex) {
        String seed = docId + "|" + (articleNo == null ? "" : articleNo) + "|" + seq + "|" + type + "|" + partIndex;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 正文行规范化：去首尾空行、连续空行压成一个、去掉行尾空白。 */
    private static String normalizeBody(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean prevBlank = true;
        for (String raw : lines) {
            String l = raw.replaceAll("\\s+$", "");
            boolean blank = l.isEmpty();
            if (blank && prevBlank) {
                continue;
            }
            out.add(l);
            prevBlank = blank;
        }
        while (!out.isEmpty() && out.get(0).isEmpty()) {
            out.remove(0);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return String.join(NEWLINE, out);
    }

    /** 字符数（不含空白）——块大小分布审计用，与 {@code measure_chunks.py} 口径一致。 */
    static int countNonWhitespace(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    private record TableSpan(ParsedTable table, int from, int to) {
    }

    private record Segment(int from, int to, TableSpan table) {
    }

    /** 暴露给测试：本切分器当前使用的参数。 */
    public ChunkingProperties properties() {
        return props;
    }

    /** 便于测试断言"条号集合"这类结构性事实。 */
    static Set<String> articleNumbers(List<Chunk> chunks) {
        Set<String> s = new LinkedHashSet<>();
        for (Chunk c : chunks) {
            if (c.getArticleNo() != null) {
                s.add(c.getArticleNo());
            }
        }
        return s;
    }
}
