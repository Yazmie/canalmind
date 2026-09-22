package com.canalmind.rag.retrieve;

import com.canalmind.rag.vector.VectorHit;
import com.canalmind.rag.vector.VectorQueryOptions;
import com.canalmind.rag.vector.VectorSearcher;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 向量通道（第 2 周第 5 项）：把 {@link VectorSearcher} 的命中适配成
 * {@link RetrievalCandidate}，供 C-26 的融合层使用。
 *
 * <h2>本类不做检索，也不做过滤</h2>
 *
 * <p>检索、打分、过滤全在 {@link VectorSearcher} 里（它把距离计算留在 pgvector 侧，
 * 见其类注释）。本类只做一次<b>形状转换</b>，并把名次写进候选。
 * 所以这里<b>不</b>新增任何默认值：{@code options} 只给 Top-K，其余过滤全关，
 * 与 {@code VectorQueryCli} 的"评测基线"口径一致——若在这里偷偷加上某个过滤条件，
 * 融合结果会与单通道基线不可比，而<b>不会有任何报错</b>。
 *
 * <h2>已归一化说明</h2>
 *
 * <p>{@code channelScores} 里放的是<b>原始分</b>（余弦相似度，越大越相关，约 [-1,1]），
 * <b>不做归一化</b>：C-26 选 RRF 正是因为"不必把 BM25 分数与余弦分数换算到同一尺度"，
 * 而任何形式的归一化都会引入新的口径变量（分母取谁、截断到哪、是否按查询动态归一），
 * 把第 4 周的归因搅浑。
 */
public final class VectorRetrievalChannel implements RetrievalChannel {

    /**
     * 通道名。
     *
     * <p>与 {@code VectorResultJson.CHANNEL}（= {@code vector}）取同一个字面量：
     * 它是候选里 {@code channelScores}/{@code channelRanks} 的键，也是评测产物里能追溯到
     * "这条分是谁给的"的唯一线索。两处若不一致，产物的可核对性就断了。
     */
    public static final String NAME = "vector";

    private final VectorSearcher searcher;
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code db.vector.search}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    public VectorRetrievalChannel(VectorSearcher searcher) {
        this(searcher, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 第 557 行的 {@code db.vector.search}）。
     *
     * <p>⚠️ 既有单参构造器保持不变、委托给 {@link ObservationRegistry#NOOP}
     * （使用方：{@code RetrievalQueryCli} 与 {@code VectorRetrievalChannelTest}），
     * **旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public VectorRetrievalChannel(VectorSearcher searcher, ObservationRegistry observationRegistry) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * 检索并适配成候选。
     *
     * <p>规格书 §7.2 第 557 行给这一步一个 Span（{@code db.vector.search}），
     * 记高基数 {@code ai.rag.result_count} = 命中条数。
     * <p>⛔ 不记 {@code ai.rag.top_k}：那需要**新增一个属性键**，
     * 而 topK 是实验条件、已在 {@code RetrievalConfig} 里（产物会带），
     * 为它新开一个只进 Trace 的键不值当（登记键的纪律见 {@link AiSpans} 的类注释）。
     *
     * <p>⚠️ <b>如实记一笔这个 Span 的耗时口径</b>：本方法内部调的是
     * {@link VectorSearcher#search}，而它<b>先把查询文本送 embedding 服务取向量、再查 pgvector</b>
     * （见 {@code VectorSearcher} 的 {@code client.embedOne(query)}）——
     * 所以这个 Span 的耗时是"**取向量 + SQL**"，比纯 SQL 时间要长。
     * 真正"只含 SQL"的那一层（{@code VectorSearcher} 内部）当前没有单独埋点，
     * 故 ⛔ 不要把本 Span 的耗时直接当成"向量库慢"的证据。
     */
    @Override
    public List<RetrievalCandidate> search(String query, int topK) {
        // ⚠️ 参数校验放在 Span 之外：topK 非法时**根本没有发生检索**，
        //    为它产出一个 db.vector.search 节点等于给 Trace 里塞一条"查了库"的假记录。
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
        Observation observation = AiObservation.start(AiSpans.DB_VECTOR_SEARCH, observationRegistry);
        return observation.observe(() -> {
            List<VectorHit> hits;
            try {
                hits = searcher.search(query, VectorQueryOptions.topK(topK));
            } catch (Exception e) {
                // 受检异常包成非受检：签名理由见 RetrievalChannelException。
                // 消息里带上通道名与查询长度（不打印查询正文：探针集里可能含不该进日志的业务文本）
                throw new RetrievalChannelException(
                        "向量通道检索失败（查询长度 " + (query == null ? 0 : query.length()) + " 字符）："
                                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
            List<RetrievalCandidate> out = new ArrayList<>(hits.size());
            int rank = 1;   // 契约：名次 1 起，且等于返回顺序（见 RetrievalChannel 类注释）
            for (VectorHit h : hits) {
                out.add(RetrievalCandidate.fromHit(NAME, h.score(), rank++,
                        h.chunkId(), h.docId(), h.docTitle(), h.chunkType(),
                        h.articleNo(), h.articleTitle(), h.version()));
            }
            AiObservation.highCardinality(observation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(out.size()));
            return List.copyOf(out);
        });
    }
}
