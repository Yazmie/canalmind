package com.canalmind.rag.tokenize;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于 jieba-analysis + 自定义航运词典的中文分词器（第 2 周第 4 项）。
 *
 * <h2>为什么不能"把整段文本直接丢给 jieba"</h2>
 *
 * <p>jieba-analysis 的 {@code JiebaSegmenter.process} 会把文本按
 * {@code CharacterUtil.ccFind} 切成"连续可识别段"再交给分词，而该判断对
 * **汉字、ASCII 字母、数字、连接符（{@code + # & . _ -}）一律返回 true**。
 * 于是 "300米""PLYH-GZ-2026-001""4.20" 这类内容会**混在同一段**里进入分词器，
 * 结果不可控（中文与数字的切分点靠概率决定，不同版本可能不同）。
 *
 * <p>本类因此**自己做段切分**，只在汉字段上调 jieba，规则明确可测：
 *
 * <table border="1">
 *   <caption>词元化规则（v1）</caption>
 *   <tr><th>输入段</th><th>处理</th><th>输出词元</th></tr>
 *   <tr><td>汉字段（如"过闸费的收费标准"）</td>
 *       <td>jieba（{@link JiebaSegmenter.SegMode}）+ 去停用词</td>
 *       <td>过闸费 / 收费 / 标准 …（词典词整词成词）</td></tr>
 *   <tr><td>代码段（ASCII 字母/数字/连接符，如 "PLYH-GZ-2026-001"）</td>
 *       <td>小写 + 去首尾连接符；**整串**与**部件**都产出</td>
 *       <td>plyh-gz-2026-001 ｜ plyh / gz / 2026 / 001</td></tr>
 *   <tr><td>纯数字（如 "300"、"4.20"）</td>
 *       <td>整串一个词元（{@code .} 在数字之间不拆）</td>
 *       <td>300 ｜ 4.20</td></tr>
 *   <tr><td>其它（标点、全角符号、表格管道符）</td><td>丢弃</td><td>—</td></tr>
 * </table>
 *
 * <h2>剥离的两类"结构标记"（都不是内容）</h2>
 *
 * <p>语料是 Markdown 写的规则文本，里面有两类**纯结构**标记，它们如果进了倒排表，
 * 会让"任意两个文档因为共享结构片段而相似"，且<b>不报错</b>：
 * <ol>
 *   <li>{@code [TABLE:<chunk_id> rows=N cols=M]} —— 表格被单独抽成 TABLE chunk 后，
 *       正文原位留的占位标记（实测 87 处 + 7 个只剩标记的退化 chunk）；</li>
 *   <li>行首的 Markdown 有序列表编号 {@code 1. } {@code 2. } ——
 *       <b>实测 1170 处、覆盖 454 个正文 chunk 中的 301 个（66%）</b>。
 *       不剥离时，词元 {@code 1}/{@code 2}/{@code 3}/{@code 4} 会挤进 df 榜首
 *       （df 分别 317/318/310/217，即出现在 60% 的 chunk 里），成为纯粹的排名噪声。</li>
 * </ol>
 *
 * <p>⚠️ 规则只匹配语料里**实际存在**的那种写法（行首 + 1~3 位数字 + {@code .} + 空白），
 * 不做"看起来像编号的都剥"的猜测——实测语料里不存在 {@code 1)}、{@code 1、}、
 * {@code （1）} 等写法（均为 0 处，见 {@code docs/进度看板.md} §3.4 的量化记录）。
 * 这样 {@code 4.20 元/吨} 这类真实数值不会被误伤（数字后紧跟数字，不匹配）。
 *
 * <h2>为什么代码段要"整串 + 部件"都产出</h2>
 *
 * <p>本项目的语料大量出现文号（{@code PLYH-GZ-2026-001}）、年份版本（2026/2027）、
 * 精确数值，它们正是 BM25 相对向量检索的**区分度来源**
 * （知识库《RAG 优化》第 115 行：错误码、型号和版本号这类精确词容易被近义内容挤掉）。
 * <ul>
 *   <li>只产出整串 → 查询写 {@code PLYH-GZ-2026}（少一段）就召不回；</li>
 *   <li>只产出部件 → 查询写完整文号时反而变成多个词的 OR，精度下降；</li>
 *   <li>两者都产出 → 完整文号精确命中整串（高 tf 高权重），部分文号靠部件召回。</li>
 * </ul>
 * 代价是词元总数增加（实测见《评测报告》检索段），
 * "只产整串"作为第 4 周消融的候选变量保留。
 *
 * <h2>并发与确定性</h2>
 *
 * <p>jieba-analysis 1.0.2 的 {@code JiebaSegmenter} **没有缓存字段**（读源码确认），
 * 每次 {@code process} 只用局部变量；词典在 {@link ShippingDictionary} 的类初始化时
 * 灌一次、之后不再写入。故本类实例可被多线程共享使用（
 * {@code process} 内部只读 {@code WordDictionary}）。
 * 这一点对第 3 周把检索接进 Web 链路是前提，故在此写明。
 *
 * <p>⚠️ 若升级 jieba-analysis，**必须重新确认**上面两条（无缓存、词表只读），
 * 并在 {@code JiebaShippingTokenizerTest} 里保持并发用例。
 */
public final class JiebaShippingTokenizer implements ChineseTokenizer {

    /** 分词器名：与 pom 里的 groupId/artifactId 对应，进档案名。 */
    public static final String NAME = "jieba-analysis";

    /**
     * 分词器版本。
     *
     * <p>⚠️ 必须与 {@code backend/pom.xml} 里 {@code com.huaban:jieba-analysis} 的版本一致。
     * 靠人同步不可靠，故 {@code JiebaShippingTokenizerTest} 会**从类路径上该 jar 的文件名**
     * 反查真实版本并断言与本常量相等——版本漂移会在测试里红掉，而不是悄悄改变检索基线。
     */
    public static final String VERSION = "1.0.2";

    /** jieba 的 INDEX 模式：整词之外，还会输出"本身也在词典里"的 2~3 字子词。 */
    public static final String MODE_INDEX = "INDEX";

    /** jieba 的 SEARCH 模式：只输出整词。 */
    public static final String MODE_SEARCH = "SEARCH";

    /**
     * <b>本类自己的分词规则版本</b>（与 jieba 库版本正交）。
     *
     * <p>为什么要单独版本化：{@link #VERSION} 只描述"用了哪个 jieba 版本"，
     * 而**真正决定词元集合的**还有本类的规则——段切分、表格标记剥离、列表编号剥离、
     * 连接符与部件长度、停用词过滤。规则改了但库没换，若档案指纹不变，
     * 就地重灌就会把**两种口径的词元混进同一个档案**，而查询侧无法分辨
     * （分数照样算得出来，只是不再可比）。
     *
     * <p>实测触发过一次：首轮入库未剥离 markdown 列表编号（1170 处、覆盖 66% 的正文
     * chunk），修正后词元集合变化，于是把它记为 rules-v2 并新建档案，
     * rules-v1 保留在库里作为对照。
     *
     * <p>⚠️ <b>已知薄弱点</b>：这个常量靠人记得改——改了本类的规则就必须 bump 它，
     * 否则数据库守卫 {@code rag_assert_token_profile} 察觉不到（它比的是值，不是行为）。
     * 之所以仍用常量而不是哈希：规则是代码，没法像词典那样算文件哈希。
     * 缓解手段是单元测试把档案名与版本串写死（改规则时测试会红，逼人做决定）。
     */
    public static final String RULES_VERSION = "v2";

    /** 默认模式。选 INDEX 的理由：语料里"过闸费费率表"这类长词被问到子串时的召回更宽。 */
    public static final String DEFAULT_SEGMENT_MODE = MODE_INDEX;

    /** 表格占位标记前缀，实测形如 {@code [TABLE:7f83bb32ecaf43ac rows=6 cols=3]}（语料中 87 处）。 */
    static final String TABLE_MARKER_PREFIX = "[TABLE:";

    /** 真实换行/回车/制表符一律用计算字符，不用转义字面量（AGENTS.md §7.2 第 1 条）。 */
    private static final char LF = (char) 10;

    private static final char TAB = (char) 9;

    /** 列表编号最多几位数字：语料实测为 1~2 位；限 3 位以免吞掉"2026 年"这类内容。 */
    private static final int MAX_LIST_MARKER_DIGITS = 3;

    private static final char CJK_START = (char) 0x4E00;
    private static final char CJK_END = (char) 0x9FFF;
    private static final char CJK_EXT_A_START = (char) 0x3400;
    private static final char CJK_EXT_A_END = (char) 0x4DBF;

    /** 段内连接符：让 PLYH-GZ-2026-001、元/吨、28.57% 这类内容成为一个代码段（再按需拆部件）。 */
    private static final String CONNECTORS = "+#&._-/:";

    /** 部件的最小长度：单字符部件（如 "a-b" 里的 a、b）几乎全是噪声。 */
    private static final int MIN_PART_LENGTH = 2;

    private final JiebaSegmenter segmenter;
    private final JiebaSegmenter.SegMode segMode;
    private final String segmentModeName;

    /** 默认构造：{@link #DEFAULT_SEGMENT_MODE}。 */
    public JiebaShippingTokenizer() {
        this(DEFAULT_SEGMENT_MODE);
    }

    /**
     * @param segmentMode {@link #MODE_INDEX} 或 {@link #MODE_SEARCH}
     */
    public JiebaShippingTokenizer(String segmentMode) {
        if (MODE_INDEX.equals(segmentMode)) {
            this.segMode = JiebaSegmenter.SegMode.INDEX;
        } else if (MODE_SEARCH.equals(segmentMode)) {
            this.segMode = JiebaSegmenter.SegMode.SEARCH;
        } else {
            throw new IllegalArgumentException(
                    "segmentMode 只能是 " + MODE_INDEX + " 或 " + MODE_SEARCH + "，实际：" + segmentMode);
        }
        this.segmentModeName = segmentMode;

        // 固定初始化顺序：先让 ShippingDictionary / StopwordList 的类初始化跑完
        // （读资源 + 严格校验 + 灌词典），再建 JiebaSegmenter。
        // 否则"词典文件格式错误"会在分词器已经建好之后才暴露，错误现场更远。
        ShippingDictionary.words();
        StopwordList.words();
        this.segmenter = new JiebaSegmenter();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String segmentMode() {
        return segmentModeName;
    }

    @Override
    public List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = normalized.length();
        while (i < n) {
            char ch = normalized.charAt(i);
            if (isCjk(ch)) {
                int j = i;
                while (j < n && isCjk(normalized.charAt(j))) {
                    j++;
                }
                addCjkTerms(normalized.substring(i, j), out);
                i = j;
            } else if (isCodeChar(ch)) {
                int j = i;
                while (j < n && isCodeChar(normalized.charAt(j))) {
                    j++;
                }
                addCodeTerms(normalized.substring(i, j), out);
                i = j;
            } else {
                i++;   // 分隔符（标点、空白、全角符号、表格管道符）：跳过
            }
        }
        return out;
    }

    @Override
    public Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String term : tokenize(text)) {
            counts.merge(term, 1, Integer::sum);
        }
        return counts;
    }

    @Override
    public TokenizerProfile profile(double bm25K1, double bm25B) {
        return new TokenizerProfile(
                TokenizerProfile.buildProfileName(
                        NAME, ShippingDictionary.VERSION, RULES_VERSION, segmentModeName),
                NAME,
                // 档案里的版本 = 库版本 + 本类规则版本。只有两者都对得上，词元才可比。
                VERSION + "+rules-" + RULES_VERSION,
                segmentModeName,
                ShippingDictionary.VERSION,
                ShippingDictionary.sha256(),
                ShippingDictionary.termCount(),
                StopwordList.sha256(),
                StopwordList.count(),
                bm25K1,
                bm25B);
    }

    // ── 内部：归一化 ────────────────────────────────────────────────────────

    /**
     * 归一化：NFKC（全角 → 半角、兼容字符展开）+ 剥掉表格占位标记 + 剥掉列表编号。
     *
     * <p>NFKC 是必要的：语料里全角括号、全角冒号、全角数字混用，
     * 不归一化会让"同一段文字"在查询与文档两侧切出不同词元（表现为"明明有却召不回"）。
     */
    static String normalize(String text) {
        return stripListMarkers(stripTableMarkers(Normalizer.normalize(text, Normalizer.Form.NFKC)));
    }

    /**
     * 剥掉行首的 Markdown 有序列表编号（{@code 1. }、{@code 2. } …）。
     *
     * <p>实测依据见类注释：1170 处、覆盖 66% 的正文 chunk，不剥会让数字词元挤进 df 榜首。
     *
     * <p>匹配规则（刻意保守）：行首（文本开头或 LF 之后）→ 可选空格/制表符 →
     * **1~3 位 ASCII 数字** → {@code .} → **至少一个空格/制表符**。
     * 四条缺一不可，故：
     * <ul>
     *   <li>{@code 4.20 元/吨} 不匹配（点后面是数字，不是空白）→ 数值保留；</li>
     *   <li>{@code 2026 年} 不匹配（数字 4 位，且后面不是点）→ 年份保留；</li>
     *   <li>{@code 项目 2 小时} 不匹配（不在行首）→ 内容数字保留。</li>
     * </ul>
     *
     * <p>与 {@link #stripTableMarkers} 一样用 {@code indexOf} 思路手工扫描而不写正则：
     * 单个字符的转义在编辑/生成链路上被悄悄改坏过三次
     * （{@code docs/工程复盘/工程方法/转义与写入层的静默损坏.md}）。
     */
    static String stripListMarkers(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (i == 0 || text.charAt(i - 1) == LF) {
                int markerEnd = matchListMarker(text, i);
                if (markerEnd > 0) {
                    i = markerEnd;   // 编号与紧随空白整体丢弃，行内其余内容保留
                    continue;
                }
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * 尝试匹配一个行首列表编号。
     *
     * @return 编号（含其后空白）结束后的下标；不匹配时返回 {@code -1}
     */
    private static int matchListMarker(String text, int lineStart) {
        int n = text.length();
        int j = lineStart;
        while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == TAB)) {
            j++;
        }
        int digitsStart = j;
        while (j < n && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
            j++;
        }
        int digitCount = j - digitsStart;
        if (digitCount < 1 || digitCount > MAX_LIST_MARKER_DIGITS || j >= n || text.charAt(j) != '.') {
            return -1;
        }
        int afterDot = j + 1;
        if (afterDot >= n || (text.charAt(afterDot) != ' ' && text.charAt(afterDot) != TAB)) {
            return -1;   // "4.20" 这类数值：点后面是数字，不剥
        }
        int k = afterDot;
        while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == TAB)) {
            k++;
        }
        return k;
    }

    /**
     * 剥掉表格占位标记 {@code [TABLE:<chunk_id> rows=N cols=M]}。
     *
     * <p>⚠️ 语料实测：541 个 chunk 中 **87 个 TABLE chunk** 是被抽出来的表格本体，
     * 此外还有 **7 个 BODY chunk 的正文就只有这一个标记**（38 字节，见
     * docs/进度看板.md §3.4 的排查记录）——它们是"整节只有一张表"时的切分退化产物，
     * 本身不含任何可检索内容。不剥标记的后果是 16 位十六进制 chunk_id 会变成词元，
     * 让"任意两个 chunk 都因为共享 id 前缀片段而相似"，且不报错。
     *
     * <p>⚠️ 该标记是**方括号**且携带 chunk_id 与行列数，
     * 不是早前文档写的 {@code [[TABLE:n]]}（见 docs/进度看板.md §3.4 的更正）。
     *
     * <p>用 {@code indexOf} 手工扫描而不是正则：正则里的方括号需要转义，
     * 而本项目对"转义在编辑/生成环节被悄悄改坏"已有过三次血的教训
     * （{@code docs/工程复盘/工程方法/转义与写入层的静默损坏.md}），
     * 能不用转义就不用。
     *
     * <p>公开（而非包内可见）是为了让入库侧能做同一件事：判断"某个 chunk 是不是
     * 只剩标记的退化 chunk"。判断规则必须与分词时的清洗规则**同一份实现**，
     * 否则两边会漂移成两种口径。
     */
    public static String stripTableMarkers(String text) {
        int from = 0;
        StringBuilder sb = new StringBuilder(text.length());
        while (true) {
            int start = text.indexOf(TABLE_MARKER_PREFIX, from);
            if (start < 0) {
                sb.append(text, from, text.length());
                return sb.toString();
            }
            int end = text.indexOf(']', start);
            if (end < 0) {
                // 标记不完整（未被闭合）：原样保留，让人能看见异常数据，而不是静默吞掉
                sb.append(text, from, text.length());
                return sb.toString();
            }
            sb.append(text, from, start);
            // 用空格顶替，避免把标记前后的文字粘成一个词
            sb.append(' ');
            from = end + 1;
        }
    }

    // ── 内部：段 → 词元 ────────────────────────────────────────────────────

    private void addCjkTerms(String run, List<String> out) {
        for (SegToken token : segmenter.process(run, segMode)) {
            String term = token.word;
            if (term == null || term.isEmpty()) {
                continue;
            }
            if (!isAllCjk(term)) {
                continue;   // 理论上不会发生（输入段全是汉字），留着防御
            }
            if (StopwordList.contains(term)) {
                continue;
            }
            out.add(term);
        }
    }

    private static void addCodeTerms(String run, List<String> out) {
        String whole = trimConnectors(run.toLowerCase(Locale.ROOT));
        if (whole.isEmpty() || !hasLetterOrDigit(whole)) {
            return;   // 纯连接符段（如表格分隔行 "---"）：丢弃
        }
        out.add(whole);
        for (String part : splitParts(whole)) {
            if (part.length() < MIN_PART_LENGTH || !hasLetterOrDigit(part) || part.equals(whole)) {
                continue;
            }
            out.add(part);
        }
    }

    /** 按连接符拆部件；但数字之间的 {@code .} 不拆（否则 "4.20" 会变成 "4" 与 "20"）。 */
    private static List<String> splitParts(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isSeparator = isConnector(c) && !(c == '.' && isDigitBetween(s, i));
            if (isSeparator) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static boolean isDigitBetween(String s, int i) {
        return i > 0 && i + 1 < s.length()
                && Character.isDigit(s.charAt(i - 1))
                && Character.isDigit(s.charAt(i + 1));
    }

    private static String trimConnectors(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isConnector(s.charAt(start))) {
            start++;
        }
        while (end > start && isConnector(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isCjk(char ch) {
        return (ch >= CJK_START && ch <= CJK_END) || (ch >= CJK_EXT_A_START && ch <= CJK_EXT_A_END);
    }

    private static boolean isAllCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isCjk(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** 代码段字符：ASCII 字母/数字 + 连接符（NFKC 已把全角字母数字转成 ASCII）。 */
    private static boolean isCodeChar(char ch) {
        return (ch < 128 && Character.isLetterOrDigit(ch)) || isConnector(ch);
    }

    private static boolean isConnector(char ch) {
        return CONNECTORS.indexOf(ch) >= 0;
    }

    private static boolean hasLetterOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
