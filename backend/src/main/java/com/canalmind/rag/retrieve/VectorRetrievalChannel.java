package com.canalmind.rag.retrieve;

import com.canalmind.rag.vector.VectorHit;
import com.canalmind.rag.vector.VectorQueryOptions;
import com.canalmind.rag.vector.VectorSearcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    public VectorRetrievalChannel(VectorSearcher searcher) {
        this.searcher = Objects.requireNonNull(searcher, "searcher");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievalCandidate> search(String query, int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
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
        return List.copyOf(out);
    }
}
