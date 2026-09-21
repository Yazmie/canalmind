package com.canalmind.rag.retrieve;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索链路编排（第 2 周第 5 项）：<b>各通道召回 → 融合 → 重排 → 取上下文</b>。
 *
 * <h2>流程与三档 Top-K 的对应</h2>
 *
 * <pre>
 *   for 每个通道:  channel.search(query, recallTopK)      → 每路 recallTopK 条
 *   fusion.fuse(各路, fusionTopK)                          → 融合后 fusionTopK 条（fused 全量）
 *   rerankEnabled ? reranker.rerank(query, fused, rerankTopN)
 *                 : 不做重排这一步的截断（下游直接取融合结果，见下 "B1"）
 *                                                          → 启用时重排后 rerankTopN 条
 *   取前 contextTopN                                        → 最终上下文
 * </pre>
 *
 * <h2>⚠️⚠️ 条数等式：每题 {@code hits} 条数恒等于产物顶层 {@code topK}（B1，2026-09-21 修）</h2>
 *
 * <p>只要上游候选够（{@code fused.size() >= contextTopN}），最终上下文的条数<b>必须恰好</b>
 * 等于 {@link RetrievalConfig#contextTopN()}，也就是说
 * {@code RetrievalResultJson} 里每题的 {@code hits} 长度恒等于顶层 {@code topK}。
 * 这不是"差不多就行"，而是下游 {@code eval_retrieval.py} 的<b>硬等式校验</b>
 * （{@code min(hits) == max(hits) == topK}）：声明 5 条而实际 3 条会被<b>明确拒绝</b>。
 *
 * <p>为什么这条等式值得单列一节：它出问题的方式<b>全程不报错</b>。
 * 曾把"关掉重排"那一档也按 {@code rerankTopN} 截断，于是
 * {@code --no-rerank --top-k 30} 只产出 10 条——产物声明 {@code topK=30}、实际 10 条，
 * 指标在 10 条上算却标成 @30；而它与 {@link RetrievalConfig} 自述的契约
 * （{@code contextTopN ≤ (rerankEnabled ? rerankTopN : fusionTopK)}）本来就矛盾。
 * 表现为"<b>关掉重排反而让上下文变少</b>"，静默且不报错。
 * 故本档改为<b>绕过</b> NoopReranker 的那次截断，并加了两条回归测试钉住这条等式。
 *
 * <p><b>重排的输入是整份融合结果</b>（不是先截到 {@code rerankTopN} 再重排）。
 * 这是重排存在的意义：融合只按名次把两路拼起来，第 12 位的候选完全可能是正确答案，
 * 先截到 10 再重排就<b>永远看不到它</b>。代价是提示词长度随 {@code fusionTopK} 增长
 * （C-27 已记录该成本），故两者的关系由 {@link RetrievalConfig} 校验规则约束。
 *
 * <h2>空查询短路</h2>
 *
 * <p>查询为空（null / 空白）时<b>不调通道、不调模型</b>，直接返回空结果。
 * 口径与既有两个 searcher 一致（{@code VectorSearcher} 与 {@code Bm25Searcher} 对空输入
 * 都返回空列表而不查库）：两通道对"空输入"的处理若不同，消融对比会在这一格上出现假差异。
 *
 * <p>短路结果的 {@code rerankOutcome.model} 写 {@link RerankOutcome#NO_MODEL}
 * （本次<b>没有</b>调用任何模型），而不是冒用重排器的名字——
 * 冒用会让产物读者以为模型真的跑过一次。
 *
 * <h2>通道异常<b>不</b>降级成单通道</h2>
 *
 * <p>任一通道抛 {@link RetrievalChannelException} 就整次失败（由调用方打印异常与底层原因）。
 * 刻意不做"某路挂了就只用另一路"的容错：那样跑出来的产物与"混合检索没有增益"
 * 在数据上完全一样，而实际是<b>混合检索没跑成</b>。
 * 这与 C-27 要求"缺 key 必须报错、不许静默降级"是同一条纪律，只是换到了召回侧。
 *
 * <h2>名字一致性校验</h2>
 *
 * <p>构造时校验 {@code config.fusionName()} == {@code fusion.name()}、
 * {@code config.rerankerName()} == {@code reranker.name()}（后者仅在重排启用时）。
 * 理由：这两个名字会写进评测产物的 {@code channel} 字段，
 * 名字与实际用的实现不一致时，产物会<b>声称</b>用了某个策略而实际不是——
 * 这类错在事后看报告时完全无法发现。
 */
public final class RetrievalPipeline {

    private final List<RetrievalChannel> channels;
    private final FusionStrategy fusion;
    private final Reranker reranker;
    private final RetrievalConfig config;

    public RetrievalPipeline(List<RetrievalChannel> channels, FusionStrategy fusion,
                             Reranker reranker, RetrievalConfig config) {
        this.channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        if (this.channels.isEmpty()) {
            throw new IllegalArgumentException(
                    "至少需要一个检索通道：没有任何通道时融合结果恒为空，"
                            + "而链路上不会有任何报错（表现为「什么都召不回」）");
        }
        for (RetrievalChannel c : this.channels) {
            Objects.requireNonNull(c, "channels 元素");
        }
        this.fusion = Objects.requireNonNull(fusion, "fusion");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.config = Objects.requireNonNull(config, "config");
        if (!config.fusionName().equals(fusion.name())) {
            throw new IllegalArgumentException(
                    "配置的 fusionName「" + config.fusionName() + "」与实际传入的融合策略「"
                            + fusion.name() + "」不一致：这个名字会写进评测产物的 channel 字段，"
                            + "不一致会让产物**声称**用了某个策略而实际不是，事后看报告无法发现。");
        }
        if (config.rerankEnabled() && !config.rerankerName().equals(reranker.name())) {
            throw new IllegalArgumentException(
                    "配置的 rerankerName「" + config.rerankerName() + "」与实际传入的重排器「"
                            + reranker.name() + "」不一致：这个名字会写进评测产物的 channel 字段，"
                            + "不一致会让产物声称用过某个重排器而实际不是。");
        }
    }

    /** 本次配置（供 CLI 打印与产物渲染）。 */
    public RetrievalConfig config() {
        return config;
    }

    /** 通道名列表（按构造顺序，供观测）。 */
    public List<String> channelNames() {
        return channels.stream().map(RetrievalChannel::name).toList();
    }

    /**
     * 执行一次检索。
     *
     * @param query 查询文本；为空时短路（见类注释）
     */
    public RetrievalResult retrieve(String query) {
        Map<String, List<String>> trace = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            // 键集与正常路径**保持一致**（只是全为空）：产物 schema 稳定，
            // 否则"空查询那次运行少几个键"会让下游解析器与 diff 都多一个特例。
            for (RetrievalChannel channel : channels) {
                trace.put("recall:" + channel.name(), List.of());
            }
            trace.put(STAGE_RECALL, List.of());
            trace.put(RetrievalResult.STAGE_FUSION, List.of());
            trace.put(RetrievalResult.STAGE_RERANK, List.of());
            trace.put(RetrievalResult.STAGE_CONTEXT, List.of());
            return new RetrievalResult(query, List.of(), List.of(),
                    RerankOutcome.ok(List.of(), 0L, RerankOutcome.NO_MODEL), trace);
        }

        // ── 1. 各通道召回 ────────────────────────────────────────────────
        List<List<RetrievalCandidate>> perChannel = new ArrayList<>(channels.size());
        List<String> recallUnion = new ArrayList<>();
        Set<String> unionSeen = new HashSet<>();
        for (RetrievalChannel channel : channels) {
            List<RetrievalCandidate> hits = channel.search(query, config.recallTopK());
            perChannel.add(hits);
            trace.put("recall:" + channel.name(), ids(hits));
            for (RetrievalCandidate c : hits) {
                if (unionSeen.add(c.chunkId())) {
                    recallUnion.add(c.chunkId());
                }
            }
        }
        trace.put(STAGE_RECALL, List.copyOf(recallUnion));

        // ── 2. 融合 ──────────────────────────────────────────────────────
        List<RetrievalCandidate> fused = fusion.fuse(perChannel, config.fusionTopK());
        trace.put(RetrievalResult.STAGE_FUSION, ids(fused));

        // ── 3. 重排（关掉时**根本不做这一步的截断**）────────────────────────────
        // ⚠️⚠️ B1（2026-09-21 真实运行发现，本次修的就是它）：
        //     关闭重排时曾走 NoopReranker 并把 rerankTopN 当上限传给它，
        //     于是 `--no-rerank --top-k 30`（合法组合：校验只要求 contextTopN ≤ fusionTopK）
        //     实际只产出 10 条——产物声明 topK=30 而每题只有 10 条，
        //     指标在 10 条上算却标成 @30，**静默且不报错**。
        //     根因是它和 RetrievalConfig 自己声明的契约互相矛盾：
        //         contextTopN ≤ (rerankEnabled ? rerankTopN : fusionTopK)
        //     —— 关掉重排时上游是**融合结果**，rerankTopN 在本档根本不该约束上下文。
        //
        //     修法：本档**绕过** NoopReranker 这一次调用（它的全部作用就是那次截断），
        //     下游直接从融合结果取 contextTopN。⛔ 不是"把上限改成 fusionTopK 再截一次"——
        //     那样虽然当下等价（融合结果本身 ≤ fusionTopK），却把"上下文能取多少"
        //     继续挂在重排档的参数上，下次有人改 NoopReranker 就会重新引入同一个 bug。
        //
        // ⚠️ 这里**没有**新增一条"旁路"语义：本档的 rerank 阶段产物就是融合结果本身
        //     （STAGE_RERANK = 融合序），与 NoopReranker 原样返回完全一致，
        //     只是不再经过一个"只为了截断而存在"的对象。
        RerankOutcome outcome;
        if (config.rerankEnabled()) {
            outcome = reranker.rerank(query, fused, config.rerankTopN());
        } else {
            // attempts=0：本档一次模型都没调（NoopReranker 也不调）。
            // 模型名写 RerankOutcome.NO_MODEL，⛔ 不许冒用真实重排器的名字。
            outcome = RerankOutcome.ok(fused, 0L, RerankOutcome.NO_MODEL);
        }
        trace.put(RetrievalResult.STAGE_RERANK, ids(outcome.candidates()));

        // ── 4. 取最终上下文 ──────────────────────────────────────────────
        List<RetrievalCandidate> ranked = outcome.candidates();
        List<RetrievalCandidate> context = ranked.size() <= config.contextTopN()
                ? ranked : List.copyOf(ranked.subList(0, config.contextTopN()));
        trace.put(RetrievalResult.STAGE_CONTEXT, ids(context));

        return new RetrievalResult(query, context, fused, outcome, trace);
    }

    /** 阶段名：各通道召回的并集（保持首次出现顺序）。各通道单独一档写作 {@code recall:<通道名>}。 */
    public static final String STAGE_RECALL = "recall";

    private static List<String> ids(List<RetrievalCandidate> list) {
        List<String> out = new ArrayList<>(list.size());
        for (RetrievalCandidate c : list) {
            out.add(c.chunkId());
        }
        return List.copyOf(out);
    }
}
