package com.canalmind.rag.split;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定长度切分基线。
 *
 * <p><b>它存在的唯一理由是作为对照物</b>，不是备选方案。决策 <b>C-21</b> 要求
 * "结构化切分为主链路，固定长度切分保留为评测基线"；依据《RAG 优化》<b>第 364 行</b>：
 * <i>"有了基线后，再依次比较固定长度、结构化、Parent-Child 和语义 Chunk"</i>。
 * 没有基线，第 4 周就无法说明结构化切分的收益是真的、还是随便怎么切都行。
 *
 * <h2>刻意保留"不懂结构"这一缺陷</h2>
 * 知识库《RAG 文档处理与切分策略》<b>第 29 行</b>指出固定长度的硬伤：
 * <i>"它不懂什么是段落、什么是表格、什么是代码块"</i>；
 * <b>第 37 行</b>给了后果示例：列表刚好跨在边界上时，两块各得一半，
 * <i>"单独看哪个都不完整，模型很容易断章取义"</i>。
 *
 * <p>因此本实现<strong>不做任何结构补偿</strong>——不避开表格、不保留标题、
 * 不规避条款边界。若给它加上结构感知，基线就不再是基线，实验结论也就失去意义。
 * <b>这是有意的"差"，不是没写好。</b>
 *
 * <h2>与结构化切分的口径对齐</h2>
 * 为了让两者可比，基线同样：
 * <ul>
 *   <li>剥离元数据块与声明行（否则元数据会在每个文档里被重复计入，双方口径不一致）；</li>
 *   <li>按字符计长、以不含空白的字符数度量；</li>
 *   <li>保留文档级元数据（版本/生效期），使检索侧过滤条件两边都能用。</li>
 * </ul>
 */
public class FixedLengthBaselineSplitter implements Splitter {

    /** 换行常量：用 (char) 10 计算，**不用字符串转义**——写入层折叠反斜杠会让它静默失效（见 docs/工程复盘/工程方法/转义与写入层的静默损坏.md）。 */
    static final String NEWLINE = String.valueOf((char) 10);


    public static final String STRATEGY = "fixed-length-baseline-v1";
    public static final String VERSION = "1.0.0";

    private final ChunkingProperties props;

    public FixedLengthBaselineSplitter() {
        this(ChunkingProperties.defaults());
    }

    public FixedLengthBaselineSplitter(ChunkingProperties props) {
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
        List<String> lines = List.of(doc.rawText().split(NEWLINE, -1));
        int bodyStart = DocumentParser.findBodyStartLine(doc.rawText());

        // 1) 正文 → 连续字符流（丢结构，模拟"不懂段落/表格"的基线行为）
        StringBuilder sb = new StringBuilder();
        for (int i = bodyStart - 1; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.isEmpty()) {
                continue;
            }
            sb.append(l);
        }
        String stream = sb.toString();

        int size = props.baselineChunkChars();
        int overlap = props.baselineOverlapChars();
        int step = size - overlap;

        List<Chunk> chunks = new ArrayList<>();
        int idx = 0;
        for (int start = 0; start < stream.length(); start += step) {
            int end = Math.min(start + size, stream.length());
            String content = stream.substring(start, end);
            if (content.isBlank()) {
                break;
            }
            idx++;

            Chunk c = new Chunk();
            c.setDocId(doc.docId());
            c.setDocTitle(doc.title());
            c.setDocNo(doc.docNo());
            c.setVersion(doc.version());
            c.setEffectiveFrom(doc.effectiveFrom());
            c.setEffectiveTo(doc.effectiveTo());
            c.setSupersedes(doc.supersedes());
            c.setDocCategoryNo(doc.categoryNo());
            // 基线的固有局限：无法给出章节路径与条号——这正是评测要量化的差异之一
            c.setChapterPath(new ArrayList<>());
            c.setArticleNo(null);
            c.setArticleTitle(null);
            c.setStrategy(strategyName());
            c.setSplitterVersion(version());
            c.setChunkType(ChunkType.BODY);
            c.setContent(content);
            c.setCharCount(ChunkStructureSplitter.countNonWhitespace(content));
            c.setSourceLines(bodyStart + "-" + lines.size());
            c.setChunkId(stableId(doc.docId(), idx));
            c.getExtra().put("baselineCharOffset", start);
            chunks.add(c);

            if (end >= stream.length()) {
                break;
            }
        }
        return chunks;
    }

    /** 基线自身也是可复现的：标识只由文档与序号决定。 */
    private static String stableId(String docId, int seq) {
        return "base-" + String.format("%s-%04d", Integer.toHexString(docId.hashCode() & 0x7fffffff), seq);
    }
}
