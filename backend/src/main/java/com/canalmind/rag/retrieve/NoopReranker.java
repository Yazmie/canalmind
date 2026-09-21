package com.canalmind.rag.retrieve;

import java.util.List;

/**
 * 消融对照用的空重排器（第 2 周第 5 项）：原样返回输入（截断到 {@code topN}）。
 *
 * <h2>它的唯一用途是当"无 rerank"这一档</h2>
 *
 * <p>规格书第 497 行把消融变量定为"向量 vs 向量+BM25+RRF（+ rerank）"，
 * 而 C-27 要求提供"有/无 rerank"的可关闭开关。
 *
 * <p>⚠️⚠️ <b>2026-09-21 更正（B1）：{@link RetrievalPipeline} 在
 * {@code rerankEnabled=false} 时<b>不再调用</b>本类</b>。
 * 原因：那次调用唯一的作用就是按 {@code topN} 截断，而当时传进去的是
 * {@code rerankTopN}（"送进重排模型的条数"）——本档根本没有模型，它不该约束上下文。
 * 于是 {@code --no-rerank --top-k 30} 只产出 10 条，产物声明 {@code topK=30} 而实际 10 条，
 * <b>全程不报错</b>。改为"本档直接取融合结果"后，这一类错在结构上不可能再发生。
 *
 * <p>故本类现在的角色是<b>占位与显式语义</b>：{@code RetrievalPipeline} 的构造器要求
 * 一个非空 {@link Reranker}，而 CLI 在 {@code --no-rerank} 时用它把"本档没有重排器"
 * 这件事写成一份可读的对象（它的 {@code name()} 是 {@link #NAME}，与
 * {@link RetrievalConfig#defaults()} 的 {@code rerankerName} 无关，故不会触发名字校验）。
 *
 * <p><b>它与"LLM 重排降级"不是一回事</b>：本类 {@code degraded=false}（本来就不需要模型），
 * 而降级是"本该重排但没跑成"。产物里两者都可辨（前者 {@code rerankScore} 全为 null
 * 且无降级标记，后者带 {@code degraded=true} 与原因）。
 *
 * <p>无状态、线程安全，实现里没有可变的字段。
 */
public final class NoopReranker implements Reranker {

    /** 重排器名（写进评测产物 {@code channel} 字段时的取值）。 */
    public static final String NAME = "noop";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RerankOutcome rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (topN < 1) {
            throw new IllegalArgumentException("topN 必须 >= 1，实际：" + topN);
        }
        List<RetrievalCandidate> in = candidates == null ? List.of() : candidates;
        List<RetrievalCandidate> out = in.size() <= topN ? in : in.subList(0, topN);
        // 不写 rerankRank/rerankScore：这一步没有"重排名次"这件事，
        // 填一个等于位置的名次会让产物看起来像重排跑过了（见 RerankOutcome 的注释）。
        // 上报的模型名用 RerankOutcome.NO_MODEL——本档根本没有模型，冒用真实模型名会误导产物读者。
        return RerankOutcome.ok(List.copyOf(out), 0L, RerankOutcome.NO_MODEL);
    }
}
