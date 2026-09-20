package com.canalmind.rag.split;

import com.canalmind.rag.split.ChunkManifest.DocumentSummary;
import com.canalmind.rag.split.ChunkManifest.Statistics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 由切分结果构建清单与统计报告。
 *
 * <p>统计字段对应知识库《RAG 文档处理与切分策略》<b>第 181–183 行</b>的
 * {@code ChunkingQualityReport}（块大小均值/最小/最大/标准差 + 分布告警），
 * 以及<b>第 253 行</b>要求记录的"Chunk 大小分布、来源、版本、权限与章节路径"。
 */
public final class ManifestReporter {

    /** 历史区间：[0,50) [50,100) [100,200) [200,400) [400,800) [800,+∞)。 */
    private static final int[] HIST_EDGES = {50, 100, 200, 400, 800};

    private static final Pattern ARTICLE_HEAD =
            Pattern.compile("^###\\s+第[零一二三四五六七八九十百千]+条", Pattern.MULTILINE);

    private ManifestReporter() {
    }

    /**
     * 构建清单。
     *
     * @param strategy      策略标识
     * @param strategyVer   切分器版本
     * @param props         本次参数
     * @param perDocChunks  逐文档的 chunk（与 docs 顺序一致）
     * @param docs          文档列表
     * @param baselineStats 基线统计；可为 {@code null}
     */
    public static ChunkManifest build(String strategy, String strategyVer, ChunkingProperties props,
                                      List<List<Chunk>> perDocChunks, List<CorpusDocument> docs,
                                      Statistics baselineStats) {
        List<Chunk> all = new ArrayList<>();
        List<DocumentSummary> summaries = new ArrayList<>();
        int noChunk = 0;

        for (int i = 0; i < docs.size(); i++) {
            CorpusDocument d = docs.get(i);
            List<Chunk> cs = perDocChunks.get(i);
            all.addAll(cs);
            int body = 0;
            int table = 0;
            for (Chunk c : cs) {
                if (c.getChunkType() == ChunkType.TABLE) {
                    table++;
                } else {
                    body++;
                }
            }
            if (cs.isEmpty()) {
                noChunk++;
            }
            summaries.add(new DocumentSummary(
                    d.docId(), d.docNo(), d.title(), d.version(),
                    d.effectiveFrom(), d.effectiveTo(), d.isSuperseded(),
                    countArticles(d.rawText()), body, table));
        }

        Map<String, Object> propsMap = new LinkedHashMap<>();
        propsMap.put("maxArticleChars", props.maxArticleChars());
        propsMap.put("baselineChunkChars", props.baselineChunkChars());
        propsMap.put("baselineOverlapChars", props.baselineOverlapChars());
        propsMap.put("lengthUnit", "字符（不含空白）");
        propsMap.put("lengthUnitNote",
                "知识库以 Token 给建议（400-512），本项目用字符实现——切分器不持有 bge-m3 tokenizer，"
                        + "用估算 Token 会引入无法验证的换算系数。中文约 1 Token ≈ 1-2 汉字。");

        return new ChunkManifest(
                ChunkManifest.CURRENT_MANIFEST_VERSION,
                strategy, strategyVer, propsMap,
                statistics(all, noChunk), summaries, all, baselineStats);
    }

    /** 计算统计量。 */
    public static Statistics statistics(List<Chunk> chunks, int docsWithNoChunk) {
        int n = chunks.size();
        int body = 0;
        int table = 0;
        long total = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (Chunk c : chunks) {
            int len = c.getCharCount();
            total += len;
            min = Math.min(min, len);
            max = Math.max(max, len);
            if (c.getChunkType() == ChunkType.TABLE) {
                table++;
            } else {
                body++;
            }
        }
        if (n == 0) {
            min = 0;
        }
        double mean = n == 0 ? 0 : (double) total / n;
        double var = 0;
        for (Chunk c : chunks) {
            double d = c.getCharCount() - mean;
            var += d * d;
        }
        double std = n == 0 ? 0 : Math.sqrt(var / n);

        Map<Integer, Integer> hist = new TreeMap<>();
        for (int edge : HIST_EDGES) {
            hist.put(edge, 0);
        }
        hist.put(Integer.MAX_VALUE, 0);
        for (Chunk c : chunks) {
            int len = c.getCharCount();
            int key = Integer.MAX_VALUE;
            for (int edge : HIST_EDGES) {
                if (len < edge) {
                    key = edge;
                    break;
                }
            }
            hist.merge(key, 1, Integer::sum);
        }

        return new Statistics(n, total, round(mean), n == 0 ? 0 : min, max, round(std),
                table, body, docsWithNoChunk, hist);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static int countArticles(String text) {
        Matcher m = ARTICLE_HEAD.matcher(text);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    /** 人类可读报告（控制台输出用）。 */
    public static String render(ChunkManifest m) {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(72)).append('\n');
        sb.append("切分报告  strategy=").append(m.strategy())
                .append("  version=").append(m.splitterVersion()).append('\n');
        sb.append("=".repeat(72)).append('\n');

        Statistics s = m.statistics();
        sb.append(String.format("文档数        : %d%n", m.documents().size()));
        sb.append(String.format("chunk 总数    : %d  （正文 %d + 表格 %d）%n",
                s.chunkCount(), s.bodyChunkCount(), s.tableChunkCount()));
        sb.append(String.format("字符数        : 合计 %d，均值 %.2f，最小 %d，最大 %d，标准差 %.2f%n",
                s.totalChars(), s.meanChars(), s.minChars(), s.maxChars(), s.stdDevChars()));
        sb.append(String.format("分布告警      : %s（标准差 %s 均值×0.5）%n",
                s.isSizeDistributionUneven() ? "⚠️ 分布不均" : "OK", s.isSizeDistributionUneven() ? ">" : "<="));
        sb.append(String.format("无 chunk 文档 : %d%n", s.docsWithNoChunk()));
        sb.append("块大小分布    :\\n");
        for (Map.Entry<Integer, Integer> e : s.sizeHistogram().entrySet()) {
            String label = e.getKey() == Integer.MAX_VALUE
                    ? String.format("[%d, +∞)", HIST_EDGES[HIST_EDGES.length - 1])
                    : String.format("[%d, %d)", prevEdge(e.getKey()), e.getKey());
            sb.append(String.format("    %-14s %4d%n", label, e.getValue()));
        }

        sb.append("-".repeat(72)).append('\n');
        sb.append("逐文档：\\n");
        sb.append(String.format("  %-4s %-32s %-6s %-5s %-5s %-5s %s%n",
                "类", "文档", "版本", "条", "正文", "表格", "状态"));
        for (DocumentSummary d : m.documents()) {
            sb.append(String.format("  %-4s %-32s %-6s %5d %5d %5d %s%n",
                    d.docId().substring(0, Math.min(2, d.docId().length())),
                    truncate(d.title(), 30),
                    d.version(),
                    d.articleCount(), d.bodyChunkCount(), d.tableChunkCount(),
                    d.superseded() ? "已废止(至 " + d.effectiveTo() + ")" : "现行"));
        }
        sb.append("-".repeat(72)).append('\n');

        if (m.baseline() != null) {
            Statistics b = m.baseline();
            sb.append("对照：固定长度基线（C-21 要求的消融对照物）\\n");
            sb.append(String.format("  %-14s %-10s %-10s %s%n", "指标", "结构化", "基线", "比值"));
            sb.append(String.format("  %-14s %-10d %-10d %.2f%n", "chunk 数", s.chunkCount(), b.chunkCount(),
                    b.chunkCount() == 0 ? 0.0 : (double) s.chunkCount() / b.chunkCount()));
            sb.append(String.format("  %-14s %-10.2f %-10.2f %.2f%n", "均值字符", s.meanChars(), b.meanChars(),
                    b.meanChars() == 0 ? 0.0 : s.meanChars() / b.meanChars()));
            sb.append(String.format("  %-14s %-10.2f %-10.2f %.2f%n", "标准差", s.stdDevChars(), b.stdDevChars(),
                    b.stdDevChars() == 0 ? 0.0 : s.stdDevChars() / b.stdDevChars()));
            sb.append(String.format("  %-14s %-10d %-10d%n", "最小字符", s.minChars(), b.minChars()));
            sb.append(String.format("  %-14s %-10d %-10d%n", "最大字符", s.maxChars(), b.maxChars()));
            sb.append("  注：基线按批大小 320/重叠 60 字符（见 ChunkingProperties 取值依据）。\\n");
        }
        return sb.toString();
    }

    private static int prevEdge(int edge) {
        int prev = 0;
        for (int e : HIST_EDGES) {
            if (e == edge) {
                return prev;
            }
            prev = e;
        }
        return prev;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
