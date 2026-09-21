package com.canalmind.rag.retrieve;

import java.util.List;

/**
 * 融合策略（第 2 周第 5 项，C-26 明确要求<b>可插拔</b>）。
 *
 * <p>为什么必须是一个接口而不是"直接写 RRF"：C-26 的决策理由是
 * ①RRF 只依据名次融合、回避两路分数量纲问题；②规格书第 497 行已把
 * "向量 vs 向量+BM25+RRF"当作消融变量；③<b>知识库《RAG 优化》第 123 行把
 * "归一化加权"列为并列选项</b>，第 4 周若数据显示数值类查询上加权更好，
 * 应当换实现而<b>不动上层</b>。
 *
 * <p>⚠️ 本项<b>只</b>实现 RRF（{@link RrfFusion}）。归一化加权是 C-26 明确留给
 * 第 4 周的可选第二实现，<b>本项不实现</b>——提前写一个"反正先放着"的加权实现，
 * 会因为没人评测而变成一份没有基线的代码，反而增加后续误用的可能。
 * 接口的存在已经足够让它在需要时插进来。
 */
public interface FusionStrategy {

    /**
     * 策略名。
     *
     * <p>会写进评测产物的 {@code channel} 字段（如 {@code rrf+llm-rerank}），
     * 故必须稳定且能唯一定位到一种算法——名字含糊会让两次消融的产物无法区分。
     */
    String name();

    /**
     * 融合多路有序候选。
     *
     * @param rankedLists 各路候选，<b>每路内部顺序即名次</b>（名次 1 起）。
     *                    允许出现 {@code null} 元素（视为空路）与重复 chunkId
     * @param topK        融合后保留条数（= {@link RetrievalConfig#fusionTopK()}），必须 ≥ 1
     * @return 融合后的候选，长度 ≤ {@code topK}；顺序即最终名次。
     *         返回的候选应当带上 {@code fusedScore} 与合并后的 {@code channelScores}/{@code channelRanks}
     */
    List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> rankedLists, int topK);
}
