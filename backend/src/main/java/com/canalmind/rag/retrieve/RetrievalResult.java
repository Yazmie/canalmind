package com.canalmind.rag.retrieve;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次检索的完整结果（第 2 周第 5 项）。
 *
 * <h2>为什么要带 {@code fused} 全量与 {@code stageTrace}，而不是只给最终 context</h2>
 *
 * <p>最终 context 只有 5 条（{@link RetrievalConfig#contextTopN()}），
 * 但"为什么是这 5 条"需要看融合后那一整份排序：某条正确证据若在第 12 位被截掉，
 * 结论是"上下文太窄"；若它压根没进融合结果，结论是"召回不够"
 * （知识库《RAG 优化》第 178 行"先检查 Context Recall"）。
 * 只看最终 5 条时这两种情况<b>长得一模一样</b>，于是调参会调错旋钮。
 *
 * <p>{@code stageTrace} 把每个阶段的 chunkId 顺序都留下来（<b>只留 id</b>，不留正文：
 * 正文体积大且会被反复打印，而排查时真正要看的是"顺序怎么变的"）。
 *
 * @param query        原始查询（空查询短路时保持原值，可能是 null）
 * @param context      最终进上下文的候选，长度 ≤ {@code contextTopN}，<b>顺序即最终名次</b>
 * @param fused        融合后的全量候选（截断到 {@code fusionTopK}），便于观测与排错
 * @param rerankOutcome 重排结果（含降级标记与耗时）。空查询短路时为
 *                      {@link RerankOutcome#NO_MODEL} 的空结果——<b>绝不冒用真实模型名</b>
 * @param stageTrace   阶段名 → chunkId 列表。键序固定（通道召回 → 融合 → 重排 → 上下文），
 *                     值是不可变列表。用于"同一份数据两次运行的产物必须字节相同"的自检
 */
public record RetrievalResult(
        String query,
        List<RetrievalCandidate> context,
        List<RetrievalCandidate> fused,
        RerankOutcome rerankOutcome,
        Map<String, List<String>> stageTrace) {

    /** 阶段名：融合。 */
    public static final String STAGE_FUSION = "fusion";

    /** 阶段名：重排。 */
    public static final String STAGE_RERANK = "rerank";

    /** 阶段名：最终上下文。 */
    public static final String STAGE_CONTEXT = "context";

    public RetrievalResult {
        context = List.copyOf(context == null ? List.of() : context);
        fused = List.copyOf(fused == null ? List.of() : fused);
        Objects.requireNonNull(rerankOutcome, "rerankOutcome");
        Map<String, List<String>> trace = new LinkedHashMap<>();
        if (stageTrace != null) {
            stageTrace.forEach((k, v) -> trace.put(k, List.copyOf(v == null ? List.of() : v)));
        }
        stageTrace = Collections.unmodifiableMap(trace);
    }

    /** 某个阶段的 chunkId 顺序；阶段不存在时返回空列表。 */
    public List<String> trace(String stage) {
        return stageTrace.getOrDefault(stage, List.of());
    }

    /** 本次是否发生了重排降级（供 CLI 醒目标注）。 */
    public boolean rerankDegraded() {
        return rerankOutcome.degraded();
    }
}
