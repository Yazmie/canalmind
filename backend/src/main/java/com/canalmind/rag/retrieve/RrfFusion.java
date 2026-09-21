package com.canalmind.rag.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RRF（Reciprocal Rank Fusion）融合（第 2 周第 5 项，决策 C-26 的主实现）。
 *
 * <h2>公式</h2>
 *
 * <pre>
 *   score(d) = Σ_c  1 / (k + rank_c(d))
 * </pre>
 *
 * <p>{@code k} 取经典默认 <b>60</b>（C-26 原文），并由构造入参可调——
 * 放进配置是为了第 4 周能在同一评估集上一起调三档 Top-K 与 k
 * （知识库《RAG 优化》第 151 行："三档 Top-K 应在同一评估集上一起调整"）。
 *
 * <h2>为什么只用名次，不用分数（C-26 的核心）</h2>
 *
 * <p>两路分数量纲差异极大：BM25 实测区间 3.0~31.4 且<b>无上界</b>，
 * 余弦相似度 0~1 <b>有界</b>。直接用分数加权必须先把两者换算到同一尺度，
 * 而换算会立刻引入新的口径变量（分母取谁、截断到哪、是否按查询动态归一）——
 * 于是"融合变好了"与"归一化方式变了"就无法区分。名次则天然同构。
 *
 * <h2>确定性（可字节级复现）</h2>
 *
 * <ul>
 *   <li><b>并集</b>：同一 chunk 出现在多路时合并为一条，{@code channelScores}/
 *       {@code channelRanks} 取各路<b>并集</b>（见 {@link RetrievalCandidate#mergeChannels}），
 *       元数据保留<b>先出现</b>的那一路；</li>
 *   <li><b>同一路内重复</b>：只计<b>第一个</b>出现位置的名次，重复项不再加分——
 *       否则"某一路返回重复行"会把该条分数抬高，而重复行是数据问题、不是相关性证据；</li>
 *   <li><b>排序</b>：先按 {@code fusedScore} 降序，<b>同分时按 chunkId 升序</b>。
 *       同分的场景在真实数据上并不罕见（一路召回、另一路没召回时，分数只由该路名次决定，
 *       名次相同就必然同分），若无第二判据，两次运行的并列顺序可能互换，
 *       评测产物就无法 diff（AGENTS.md §7.2 的同一理由）；</li>
 *   <li><b>浮点求和顺序</b>：按传入的路径顺序累加。这是确定的，但意味着
 *       <b>换一个通道顺序可能改变末位小数的累加结果</b>——故通道顺序属于实验条件，
 *       第 4 周做消融时应固定 {@link RetrievalPipeline} 构造时的通道顺序。</li>
 * </ul>
 *
 * <h2>已知边界</h2>
 *
 * <p>RRF 对"只有一路召回"的候选给分天然偏低（{@code 1/(60+rank)} 一次），
 * 故它<b>不会</b>把单路强信号顶到第一位——这是刻意的取舍（两路都认可者优先），
 * 但它也意味着"某路独有的正确答案"容易被挤到后面。第 4 周看 badcase 时应留意这一类。
 */
public final class RrfFusion implements FusionStrategy {

    /** 策略名（写进评测产物的 {@code channel} 字段）。 */
    public static final String NAME = "rrf";

    /**
     * 经典默认 k（C-26 原文）。
     *
     * <p>k 越大，名次差异被压得越平（1/(60+1) 与 1/(60+30) 只差约 1 倍），
     * 于是融合更"保守"、更依赖"两路都命中"这件事本身；k 越小越偏向单路头部。
     * 默认 60 是业界常用的稳健取值。
     */
    public static final int DEFAULT_K = 60;

    private final int k;

    /** 用 C-26 的默认 k=60。 */
    public RrfFusion() {
        this(DEFAULT_K);
    }

    public RrfFusion(int k) {
        if (k < 1) {
            throw new IllegalArgumentException(
                    "rrfK 必须 >= 1，实际：" + k + "（k 是分母里的常数，"
                            + "取 0 会让 rank=1 的候选得 1/1=1 分、并让 1/(0+0) 这类无意义表达式成为可能）");
        }
        this.k = k;
    }

    /** 本次使用的 k。 */
    public int k() {
        return k;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> rankedLists, int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
        if (rankedLists == null || rankedLists.isEmpty()) {
            return List.of();
        }

        Map<String, RetrievalCandidate> union = new LinkedHashMap<>();
        Map<String, Double> fused = new LinkedHashMap<>();
        for (List<RetrievalCandidate> list : rankedLists) {
            if (list == null || list.isEmpty()) {
                continue;
            }
            Set<String> seenInThisList = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                RetrievalCandidate c = list.get(i);
                if (c == null) {
                    continue;
                }
                if (!seenInThisList.add(c.chunkId())) {
                    continue;   // 同一路内的重复只计首个（见类注释）
                }
                int rank = i + 1;   // 名次 = 该路内的返回顺序，1 起
                // 先合并通道数据，再累加分数：两步分开是因为"这一条来自几路"与
                // "这一条得几分"是两件事，混在一个表达式里将来很难改。
                union.merge(c.chunkId(), c, (existing, incoming) -> existing.mergeChannels(incoming));
                fused.merge(c.chunkId(), 1.0 / (k + rank), Double::sum);
            }
        }

        List<RetrievalCandidate> out = new ArrayList<>(union.size());
        for (Map.Entry<String, RetrievalCandidate> e : union.entrySet()) {
            out.add(e.getValue().withFusedScore(fused.get(e.getKey())));
        }
        out.sort(Comparator.comparingDouble(RetrievalCandidate::fusedScore).reversed()
                .thenComparing(RetrievalCandidate::chunkId));
        if (out.size() > topK) {
            out = new ArrayList<>(out.subList(0, topK));
        }
        return List.copyOf(out);
    }
}
