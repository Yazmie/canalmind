package com.canalmind.rag.retrieve;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 混合检索的统一候选（第 2 周第 5 项，决策 C-26 / C-27）。
 *
 * <h2>为什么要有这个类型，而不是直接用 VectorHit / Bm25Hit</h2>
 *
 * <p>C-26 要求融合层对<b>两个通道的命中一视同仁</b>。{@code VectorHit} 与 {@code Bm25Hit}
 * 的字段虽然已逐字段对齐（见它们的类注释），但两者都只有<b>一个</b>分数，
 * 而融合后的候选天然要记住「它在每一路各是多少分、各排第几」——
 * 少了这份记录，第 4 周排查"某条为什么被融合排到第 7"时只能重跑一遍检索，
 * 而重跑结果未必相同（LLM Rerank 依赖外部服务，见 {@link LlmReranker}）。
 *
 * <h2>不可变与确定性</h2>
 *
 * <p>record 本身不可变，但 {@code Map} 分量是可变的引用，故紧凑构造器里
 * <b>复制成 {@code LinkedHashMap} 再包一层 unmodifiable</b>：
 * <ul>
 *   <li>不用 {@code Map.copyOf}——它返回的是**无序**不可变映射，
 *       会让 {@code channelScores} 的键序在两次运行间漂移，
 *       而这份键序会写进评测产物 JSON（本项目要求产物字节级可复现，AGENTS.md §7.2）；</li>
 *   <li>不直接持有调用方传进来的 map——否则调用方后续 put 一笔，会静默改掉
 *       "当时的证据"，这类问题不报错、只在事后复核时让人对不上账。</li>
 * </ul>
 *
 * <p>所有 {@code with*} 方法都返回<b>新实例</b>而不是就地改：候选会被
 * {@link RetrievalPipeline} 的多个阶段（召回 / 融合 / 重排）共用，
 * 就地改会让"某一阶段的输入"在事后变成另一个样子，
 * 而 {@link RetrievalResult#stageTrace()} 记下的 chunkId 顺序却不会跟着变。
 *
 * @param chunkId      chunk 标识（与 {@code rag_chunk.chunk_id} 一致）。<b>不得为空</b>：
 *                     融合按它做并集合并（C-26），空 id 会让所有"无 id 的候选"被并成一条
 * @param docId        来源文档
 * @param docTitle     文档标题
 * @param chunkType    BODY / TABLE
 * @param articleNo    条号（如「第三条」），可能是 null（文档级 chunk 没有条号）
 * @param articleTitle 条标题，可能是 null
 * @param version      文档版本（如 2026 / V1.0）
 * @param channelScores 通道名 → 该通道给出的<b>原始分</b>（未做任何归一化，C-26 明确不归一化）。
 *                      两路量纲差异极大（BM25 实测 3.0~31.4 且无上界，余弦相似度 0~1 有界），
 *                      故这些分数<b>只在同一通道内可比</b>，跨通道比较只能看名次
 * @param channelRanks  通道名 → 名次（<b>1 起</b>）。融合公式只用这个（RRF 只依据名次，
 *                      不必把两种分数量纲换算到同一尺度）
 * @param fusedScore    融合分（当前实现为 RRF：{@code Σ 1/(k+rank)}）。
 *                      它是<b>求和</b>的结果，故取值随通道数变化，只在同一配置内可比
 * @param rerankRank    重排后的名次（1 起）；未启用重排或重排降级时为 {@code null}
 * @param rerankScore   重排模型给出的分数；未重排、降级，或模型漏掉该候选时为 {@code null}
 */
public record RetrievalCandidate(
        String chunkId,
        String docId,
        String docTitle,
        String chunkType,
        String articleNo,
        String articleTitle,
        String version,
        Map<String, Double> channelScores,
        Map<String, Integer> channelRanks,
        double fusedScore,
        Integer rerankRank,
        Double rerankScore) {

    public RetrievalCandidate {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException(
                    "chunkId 不得为空：融合按 chunk_id 做并集合并（C-26），"
                            + "空 id 会把所有「没有 id 的候选」并成同一条，且不会报错。");
        }
        channelScores = immutableOrdered(channelScores);
        channelRanks = immutableOrdered(channelRanks);
        if (rerankRank != null && rerankRank < 1) {
            // 名次从 1 起是契约（VectorResultJson 的 rank 同理）：从 0 起会让整列偏一位
            throw new IllegalArgumentException("rerankRank 从 1 起，实际：" + rerankRank);
        }
    }

    /**
     * 两个通道<b>共用</b>的「一条命中 → 一个候选」映射。
     *
     * <p>刻意做成这里一处：把 7 个溯源字段的顺序<b>只写一次</b>。两个通道各写一份映射的话，
     * 早晚有一份把 {@code articleNo} 与 {@code articleTitle} 写反，
     * 而字段错位<b>不会报错</b>——只会让某一路的"按条款定位"悄悄失效。
     *
     * @param channel 通道名（写进 {@code channelScores}/{@code channelRanks} 的键）
     * @param score   该通道的<b>原始分</b>（不归一化）
     * @param rank    该通道内的名次，<b>1 起</b>（= 命中列表里的返回顺序）
     */
    public static RetrievalCandidate fromHit(String channel, double score, int rank,
                                             String chunkId, String docId, String docTitle,
                                             String chunkType, String articleNo, String articleTitle,
                                             String version) {
        return new RetrievalCandidate(chunkId, docId, docTitle, chunkType, articleNo, articleTitle,
                version, Map.of(channel, score), Map.of(channel, rank), 0.0, null, null);
    }

    /**
     * 只带元数据的候选（无通道数据）——供测试夹具与"手搓候选"使用。
     * 生产路径一律由 {@link RetrievalChannel} 产出。
     */
    public static RetrievalCandidate of(String chunkId) {
        return new RetrievalCandidate(chunkId, null, null, null, null, null, null,
                Map.of(), Map.of(), 0.0, null, null);
    }

    /** 记下一个通道给出的原始分与名次（名次从 1 起），返回新实例。 */
    public RetrievalCandidate withChannel(String channel, double score, int rank) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("通道名不得为空");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("通道名次从 1 起，实际：" + rank);
        }
        Map<String, Double> scores = new LinkedHashMap<>(channelScores);
        Map<String, Integer> ranks = new LinkedHashMap<>(channelRanks);
        scores.put(channel, score);
        ranks.put(channel, rank);
        return new RetrievalCandidate(chunkId, docId, docTitle, chunkType, articleNo, articleTitle,
                version, scores, ranks, fusedScore, rerankRank, rerankScore);
    }

    /** 写入融合分，返回新实例。 */
    public RetrievalCandidate withFusedScore(double newFusedScore) {
        return new RetrievalCandidate(chunkId, docId, docTitle, chunkType, articleNo, articleTitle,
                version, channelScores, channelRanks, newFusedScore, rerankRank, rerankScore);
    }

    /**
     * 写入重排名次与分数，返回新实例。
     *
     * @param rank  重排名次（1 起）
     * @param score 重排分数；模型漏掉该候选时传 {@code null}
     *              （⛔ 不要用 0 或负数代替 null：那会让"模型没给分"看起来像"模型给了低分"）
     */
    public RetrievalCandidate withRerank(int rank, Double score) {
        return new RetrievalCandidate(chunkId, docId, docTitle, chunkType, articleNo, articleTitle,
                version, channelScores, channelRanks, fusedScore, rank, score);
    }

    /**
     * 合并同一 chunk 在<b>另一路</b>出现时带来的通道数据（并集），返回新实例。
     *
     * <p>元数据一律保留 {@code this} 的（即先出现的那一路）：两条命中的元数据来自同一张
     * {@code rag_chunk} 行，正常情况逐字段相同；若不同，说明两通道读到的库状态不一致，
     * 那是更严重的问题，不该在这里"挑一个看起来更好的"掩盖过去。
     * 键冲突时同样保留 {@code this} 的取值（先出现者优先），保证合并结果与处理顺序绑定、可复现。
     */
    public RetrievalCandidate mergeChannels(RetrievalCandidate other) {
        Objects.requireNonNull(other, "other");
        Map<String, Double> scores = new LinkedHashMap<>(channelScores);
        Map<String, Integer> ranks = new LinkedHashMap<>(channelRanks);
        other.channelScores.forEach(scores::putIfAbsent);
        other.channelRanks.forEach(ranks::putIfAbsent);
        return new RetrievalCandidate(chunkId, docId, docTitle, chunkType, articleNo, articleTitle,
                version, scores, ranks, fusedScore, rerankRank, rerankScore);
    }

    /** 某个通道给的名次；该通道没召回它时为 {@code null}。 */
    public Integer channelRank(String channel) {
        return channelRanks.get(channel);
    }

    /**
     * 最终用于排序展示的分数：有重排分就用重排分，否则用融合分。
     *
     * <p>评测产物 {@code hits[].score} 用的就是它，理由见 {@link RetrievalResultJson}：
     * 下游脚本（{@code backend/scripts/eval_retrieval.py}）只认一个 {@code score}，
     * 而"最终阶段"才是它要评的那个阶段的分。
     */
    public double finalScore() {
        return rerankScore != null ? rerankScore : fusedScore;
    }

    /** 供 CLI/日志的一行摘要。小数位固定 {@link Locale#ROOT}，避免 locale 的小数点差异。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%.6f", finalScore()));
        if (rerankScore != null) {
            sb.append(String.format(Locale.ROOT, "（重排 %.6f 名次 %d，融合 %.6f）",
                    rerankScore, rerankRank == null ? -1 : rerankRank, fusedScore));
        } else {
            sb.append(String.format(Locale.ROOT, "（融合 %.6f，未重排）", fusedScore));
        }
        sb.append("  ").append(chunkType == null ? "" : chunkType);
        sb.append(' ').append(docId);
        sb.append(' ').append(articleNo == null ? "" : articleNo);
        sb.append("【").append(articleTitle == null ? "" : articleTitle).append("】");
        sb.append("  ").append(chunkId);
        sb.append("  通道名次 ").append(channelRanks);
        return sb.toString();
    }

    private static <V> Map<String, V> immutableOrdered(Map<String, V> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }
}
