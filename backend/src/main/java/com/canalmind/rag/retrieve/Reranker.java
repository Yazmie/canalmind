package com.canalmind.rag.retrieve;

import java.util.List;

/**
 * 重排器（第 2 周第 5 项，C-27 明确要求<b>可插拔</b>）。
 *
 * <p>为什么是接口：C-27 选的是 <b>LLM Rerank（DeepSeek）</b>作为主实现，但同一决策里
 * 也写明了可回退路径——第 4 周若数据显示 rerank 增益显著而延迟/成本不可接受，
 * 换本地 cross-encoder 做对照时<b>上层不动</b>。另外"有/无 rerank"这一消融档
 * （规格书第 497 行）需要 {@link NoopReranker} 作为对照，而它同样实现本接口。
 *
 * <h2>实现的两条硬约束（由 C-27 的"降级 = 保留融合原序"推出）</h2>
 *
 * <ol>
 *   <li><b>不许丢候选、不许编造 chunk_id</b>：返回的候选必须来自传入的那批，
 *       且数量 = {@code min(topN, 输入条数)}。上游的评测要靠"同一条候选在
 *       有无 rerank 两档里都能被看到"来归因，凭空多一条或少了三条都会让对比失真；</li>
 *   <li><b>失败必须显式降级</b>：不允许"失败了就静默返回原序"——
 *       那样产出的报告会被读成"rerank 没效果"，而实际上是<b>没跑成</b>。
 *       故失败要写进 {@link RerankOutcome#degraded()} 与
 *       {@link RerankOutcome#degradeReason()}，并由产物 JSON 与 CLI 打印出来。</li>
 * </ol>
 */
public interface Reranker {

    /** 重排器名（写进评测产物的 {@code channel} 字段，如 {@code llm-rerank}）。 */
    String name();

    /**
     * 重排。
     *
     * @param query      原始查询（重排要拿它判断"哪条更相关"）
     * @param candidates 待重排候选，通常来自 {@link FusionStrategy} 的输出（已按融合序）
     * @param topN       重排后保留条数（= {@link RetrievalConfig#rerankTopN()}），必须 ≥ 1
     * @return 重排结果，见 {@link RerankOutcome}。<b>不得返回 null</b>
     */
    RerankOutcome rerank(String query, List<RetrievalCandidate> candidates, int topN);
}
