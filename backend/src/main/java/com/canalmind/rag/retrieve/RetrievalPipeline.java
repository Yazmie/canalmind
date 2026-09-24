package com.canalmind.rag.retrieve;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

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
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.rag.retrieve} / {@code ai.rag.context.build}）。 */
    private final ObservationRegistry observationRegistry;

    /**
     * 知识库标识（规格书 §7.3 点名的 {@code knowledge_base_id}，C-59，2026-09-22 补写）。
     *
     * <p><b>⚠️ 如实记一笔：它今天是常量。</b>本项目只有<b>一个</b>知识库
     * （{@code docs/规则语料/} 的 26 篇 → 库内 541 个 chunk，同属索引档案
     * {@code ollama:bge-m3:1024}），所以这个键当下<b>基数并不高</b>。
     * 仍按规格书 §7.3 归入高基数一侧（只进 Trace），理由是它<b>随知识库数量增长</b>，
     * 而本项目的对外形态是<b>多知识库</b>。
     *
     * <p>为什么写成常量而不是构造器参数：本项目唯一的知识库是既成事实，
     * 加一个参数会让 14 处既有装配点各多传一个恒同的值，收益为零。
     * ⇒ <b>等真有第二个知识库时再改成注入</b>（那时这个常量正好是需要被替换的那一行）。
     */
    public static final String KNOWLEDGE_BASE_ID = "canalmind-rules";

    public RetrievalPipeline(List<RetrievalChannel> channels, FusionStrategy fusion,
                             Reranker reranker, RetrievalConfig config) {
        this(channels, fusion, reranker, config, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 的 {@code ai.rag.retrieve} 与
     * {@code ai.rag.context.build}）。
     *
     * <p>⚠️ 既有 4 参构造器保持不变、委托给 {@link ObservationRegistry#NOOP}：
     * 它被 {@code RetrievalQueryCli}（含 {@code --selftest} 的 4 处桩装配）与
     * {@code RetrievalPipelineTest} 共 14 处使用，**旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public RetrievalPipeline(List<RetrievalChannel> channels, FusionStrategy fusion,
                             Reranker reranker, RetrievalConfig config,
                             ObservationRegistry observationRegistry) {
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
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
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
     * <p>规格书 §7.2 第 554 行给这一步一个 Span（{@code ai.rag.retrieve}），按 §7.3 记三件事：
     * <ul>
     *   <li><b>低基数</b> {@code ai.rag.strategy}：<b>单通道档取通道名、多通道档取融合策略名</b>
     *       （与 {@link RetrievalConfig#labelFor(List)} 的规则同源，⛔ 不另立一套口径）。
     *       理由：四路消融里"到底只跑了哪一路"是这一档唯一的身份信息，而两路时"用了哪种融合"
     *       才是变量——两者都是封闭枚举，故都能进 Metrics。
     *       ⚠️ 不能直接用 {@code labelFor} 的返回值：它在启用重排时会拼出 {@code rrf+llm-rerank}，
     *       那个 {@code +} 不在低基数取值的合法字符集里（闸门会拒），故这里只取它的**基名**。</li>
     *   <li><b>低基数</b> {@code ai.rag.result}：{@code hit} / {@code empty}，判据就是
     *       <b>最终上下文是否为空</b>——与下面那个条数**同一个数**，避免"两个数各说一套";</li>
     *   <li><b>高基数</b> {@code ai.rag.result_count}：最终进上下文的条数。</li>
     * </ul>
     *
     * <p>⚠️ 空查询短路时也会产出这个 Span（{@code result_count=0} / {@code empty}），
     * 但<b>不会</b>产出 {@code ai.rag.context.build}——那一步（裁剪）根本没执行。
     * ⛔ 不为了凑齐 §7.2 的节点数而造一个空壳子 Span（同 {@code AiSpans.OUTPUT_VALIDATE}
     * 挂起的理由：一个只在无实质动作处触发的节点是**假证据**）。
     *
     * <p>⚠️⚠️ 一律 {@code observe(...)}，⛔ 不手工 {@code start()}/{@code stop()}
     * （实测见 {@code docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md}）。
     *
     * @param query 查询文本；为空时短路（见类注释）
     */
    public RetrievalResult retrieve(String query) {
        Observation observation = AiObservation.start(AiSpans.RAG_RETRIEVE, observationRegistry);
        return observation.observe(() -> {
            RetrievalResult result = doRetrieve(query);
            AiObservation.lowCardinality(observation, AiSpans.KEY_RAG_STRATEGY, strategyName());
            AiObservation.ragResult(observation, result.context().isEmpty());
            AiObservation.highCardinality(observation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(result.context().size()));
            AiObservation.highCardinality(observation, AiSpans.KEY_KNOWLEDGE_BASE_ID,
                    KNOWLEDGE_BASE_ID);
            return result;
        });
    }

    /**
     * {@code ai.rag.strategy} 的取值：<b>单通道取通道名、多通道取融合策略名</b>。
     *
     * <p>口径与 {@link RetrievalConfig#labelFor(List)} 一致（那里是写给产物看的，
     * 这里要的是"进 Metrics 的封闭枚举"，故去掉 {@code +重排器名} 的后缀）。
     */
    String strategyName() {
        return channels.size() == 1 ? channels.get(0).name() : fusion.name();
    }

    /** 真正的检索（原 {@code retrieve} 的实现）：与埋点分开，便于核对"埋点没动过编排"。 */
    private RetrievalResult doRetrieve(String query) {
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
        // 规格书 §7.2 第 560 行：ai.rag.context.build ← "上下文裁剪"。
        // ⚠️ 本项目的裁剪**就在这一步**（`ranked` 按 contextTopN 截断即是"裁剪"本身），
        //    故埋在这里；`QuestionPipeline.collect` 里还有一层"取正文 + 去重 + 再截 5 条"，
        //    那是**取正文**（另外查 rag_chunk）而不是"裁剪候选"，且它只发生在端到端链路上。
        //    ⇒ 两处都埋会得到两个同名节点、语义却不同（重复节点，知识库第 459 行明确反对）。
        Observation contextObservation = AiObservation.start(AiSpans.RAG_CONTEXT_BUILD,
                observationRegistry);
        List<RetrievalCandidate> context = contextObservation.observe(() -> {
            List<RetrievalCandidate> trimmed = ranked.size() <= config.contextTopN()
                    ? ranked : List.copyOf(ranked.subList(0, config.contextTopN()));
            AiObservation.highCardinality(contextObservation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(trimmed.size()));
            // ⚠️ document_ids 是高基数，但**不是无界**：闸门上限 512 字符，
            //    所以这里按"整条 id 为单位"截断（⛔ 不切半个 id，那会让下游对不上任何东西）。
            //    ⚠️ 上下文为空时**不写**这个属性：闸门拒绝空取值，而"0 条"已经由
            //    result_count=0 与 ai.rag.result=empty 表达，⛔ 不另造一个 "-"/"none" 哨兵值
            //    （哨兵值会与真实 chunk id 混在同一列里）。
            String documentIds = joinedChunkIds(trimmed);
            if (!documentIds.isEmpty()) {
                AiObservation.highCardinality(contextObservation, AiSpans.KEY_DOCUMENT_IDS,
                        documentIds);
            }
            return trimmed;
        });
        trace.put(RetrievalResult.STAGE_CONTEXT, ids(context));

        return new RetrievalResult(query, context, fused, outcome, trace);
    }

    /**
     * 逗号分隔的 chunk id 串，**按整条截断到闸门上限**（见 {@link AiObservation#MAX_HIGH_CARDINALITY_LENGTH}）。
     *
     * <p>为什么在这里截而不是交给闸门抛：闸门抛异常会让**埋点把业务搞挂**——
     * 那是方向上就错了（埋点不能改变业务结果）。故在写之前自己收敛到合法形状。
     */
    static String joinedChunkIds(List<RetrievalCandidate> context) {
        StringBuilder sb = new StringBuilder();
        for (RetrievalCandidate c : context) {
            String id = c.chunkId() == null ? "" : c.chunkId();
            int needed = (sb.length() == 0 ? 0 : 1) + id.length();
            if (sb.length() + needed > AiObservation.MAX_HIGH_CARDINALITY_LENGTH) {
                break;   // 超上限就停手：宁可少列几个 id，也不写半截 id
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
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
