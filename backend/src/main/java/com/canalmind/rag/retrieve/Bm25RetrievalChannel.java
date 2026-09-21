package com.canalmind.rag.retrieve;

import com.canalmind.rag.bm25.Bm25Hit;
import com.canalmind.rag.bm25.Bm25QueryOptions;
import com.canalmind.rag.bm25.Bm25Searcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * BM25 通道（第 2 周第 5 项）：把 {@link Bm25Searcher} 的命中适配成
 * {@link RetrievalCandidate}，供 C-26 的融合层使用。
 *
 * <h2>本类不做检索，也不做分词</h2>
 *
 * <p>分词与打分分别在 {@code RagBm25Search}（SQL 侧）与 {@code ChineseTokenizer}（Java 侧）里，
 * 本类只做形状转换并写名次。查询文本<b>原样</b>传给 searcher——不 trim、不改写：
 * 查询侧与索引侧的分词口径由 {@code Bm25Searcher.ensureProfileMatches()} 守卫，
 * 在这里"顺手清洗一下"会让守卫比对的口径与实际送进 SQL 的口径变成两个东西。
 *
 * <p>⚠️ 分数<b>只在同一查询内可比</b>（{@link Bm25Hit#score()} 的注释）：
 * 它无上界，跨查询不可比。融合层只用名次（见 {@link RrfFusion}），
 * 故这条限制不会污染融合结果；但 {@code channelScores} 里保留原值，
 * 供事后核对"这一路的原始分是多少"。
 */
public final class Bm25RetrievalChannel implements RetrievalChannel {

    /** 通道名（写进候选的 {@code channelScores}/{@code channelRanks} 键）。 */
    public static final String NAME = "bm25";

    private final Bm25Searcher searcher;

    public Bm25RetrievalChannel(Bm25Searcher searcher) {
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
        List<Bm25Hit> hits;
        try {
            hits = searcher.search(query, Bm25QueryOptions.topK(topK));
        } catch (Exception e) {
            // 受检异常包成非受检：签名理由见 RetrievalChannelException
            throw new RetrievalChannelException(
                    "BM25 通道检索失败（查询长度 " + (query == null ? 0 : query.length()) + " 字符）："
                            + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        List<RetrievalCandidate> out = new ArrayList<>(hits.size());
        int rank = 1;   // 契约：名次 1 起，且等于返回顺序
        for (Bm25Hit h : hits) {
            out.add(RetrievalCandidate.fromHit(NAME, h.score(), rank++,
                    h.chunkId(), h.docId(), h.docTitle(), h.chunkType(),
                    h.articleNo(), h.articleTitle(), h.version()));
        }
        return List.copyOf(out);
    }
}
