package com.canalmind.rag.retrieve;

import java.util.List;

/**
 * 检索通道（第 2 周第 5 项，C-26 的输入侧）。
 *
 * <p>一个通道 = 一种召回信号。当前有两个实现：{@link VectorRetrievalChannel}（语义相似）
 * 与 {@link Bm25RetrievalChannel}（关键词精确匹配）。
 *
 * <h2>为什么名次（rank）由通道自己填，而不是让融合层自己数</h2>
 *
 * <p>{@link RetrievalCandidate#channelRanks()} 的契约是「<b>返回顺序</b>，1 起」，
 * 由通道在映射命中时写入。融合层虽然也能从 {@code List} 的下标推出一份名次，
 * 但两者一旦不同（例如通道内部做了去重、或返回顺序与真实打分顺序不一致），
 * 融合结果会与"通道自己声称的名次"矛盾——而<b>这种矛盾不会报错</b>，
 * 只会在第 4 周复盘"为什么这条被融合排到第 7"时对不上账。
 *
 * <h2>空查询不在这里短路</h2>
 *
 * <p>「查询为空 → 不调通道、不调模型」这条口径<b>只写在 {@link RetrievalPipeline} 一处</b>。
 * 刻意不在每个实现里各容错一次（AGENTS.md §7.2 第 4 条：归一化/边界处理要钉在数据边界上）：
 * 分散容错的结果总是"某个调用路径拿到了未处理的输入"，而且没人能说清共有几处。
 * 底层两个 searcher 自身对空输入也已返回空列表，故这里不重复判断不会引发额外查询。
 *
 * <h2>异常口径</h2>
 *
 * <p>两个底层 searcher 各自抛出<b>受检</b>异常（embedding 失败、索引档案不匹配、
 * 分词档案不匹配）。本接口刻意<b>不</b>声明 {@code throws}（保持签名干净、不把
 * 某个通道的异常类型泄漏成公共契约），实现里统一包成
 * {@link RetrievalChannelException}（<b>非受检</b>）。
 *
 * <p>⚠️ 包成非受检<b>不等于可以吞掉</b>：{@link RetrievalPipeline} 不会捕获它，
 * 调用方（CLI）会打印异常类型、消息与<b>底层原因</b>。原因见 C-27 的同类纪律——
 * 一次"只跑了单通道"的评测如果静默通过，会被读成"混合检索没效果"。
 */
public interface RetrievalChannel {

    /** 通道名。同时是 {@link RetrievalCandidate#channelScores()} 等映射的键，故必须稳定。 */
    String name();

    /**
     * 召回。
     *
     * @param query 查询文本
     * @param topK  本通道返回条数上限，必须 ≥ 1（由 {@link RetrievalConfig#recallTopK()} 给出）
     * @return 该通道的命中，<b>顺序即名次</b>（名次 1 起，已在候选里写进 {@code channelRanks}）
     * @throws RetrievalChannelException 底层检索失败（连库/embedding/档案不一致等）
     */
    List<RetrievalCandidate> search(String query, int topK);
}
