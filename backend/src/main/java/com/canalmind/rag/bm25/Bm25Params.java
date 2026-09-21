package com.canalmind.rag.bm25;

/**
 * BM25 超参数（第 2 周第 4 项）。
 *
 * <p>公式（唯一实现在 PostgreSQL 的 {@code rag_bm25_search}，见 V4 迁移）：
 * <pre>
 *   score(q,d) = Σ_t  idf(t) · (tf(t,d) · (k1+1)) / (tf(t,d) + k1 · (1 - b + b · dl(d)/avgdl))
 *   idf(t)     = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
 * </pre>
 *
 * <ul>
 *   <li><b>k1</b> 控制词频饱和：tf 再大，分子分母同阶增长 → 分数收敛到 {@code idf·(k1+1)}。
 *       k1 越大，"多出现几次"的边际收益越高。</li>
 *   <li><b>b</b> 控制文档长度归一化：b=0 完全不归一（长文档天然占优）；
 *       b=1 完全归一（长文档被压得最狠）。本项目 chunk 长度分布不均
 *       （实测 min/max 见《评测报告》检索段），故**不能**取 0。</li>
 * </ul>
 *
 * <p>默认取 Lucene 的 1.2 / 0.75：这是最通行的起点，且本项目语料规模下
 * 不存在"必须调参才有意义"的情形。参数**进档案**（{@code rag_token_profile.bm25_k1/bm25_b}），
 * 改动即等于换一套打分口径 → 数据库守卫会拒绝就地重灌，必须先新建档案
 * （理由：否则上周的评测分数会被无声重解释）。
 */
public record Bm25Params(double k1, double b) {

    /** Lucene {@code BM25Similarity} 的默认 k1。 */
    public static final double LUCENE_K1 = 1.2;

    /** Lucene {@code BM25Similarity} 的默认 b。 */
    public static final double LUCENE_B = 0.75;

    public Bm25Params {
        // 与 V4 迁移的 rag_token_profile_k1_ck / _b_ck 一致，避免"Java 放行、数据库才拒绝"
        if (!(k1 > 0 && k1 <= 100)) {
            throw new IllegalArgumentException("k1 必须在 (0, 100]，实际：" + k1);
        }
        if (!(b >= 0 && b <= 1)) {
            throw new IllegalArgumentException("b 必须在 [0, 1]，实际：" + b);
        }
    }

    /** Lucene 默认参数（本项目的起点，也是默认档案参数）。 */
    public static Bm25Params luceneDefaults() {
        return new Bm25Params(LUCENE_K1, LUCENE_B);
    }
}
