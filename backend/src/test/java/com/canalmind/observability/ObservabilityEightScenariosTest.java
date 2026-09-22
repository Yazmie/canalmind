package com.canalmind.observability;

import com.canalmind.chat.session.InMemorySessionStore;
import com.canalmind.chat.session.SessionState;
import com.canalmind.chat.session.SessionTurn;
import com.canalmind.eval.pipeline.AnswerComposer;
import com.canalmind.eval.pipeline.QuestionPipeline;
import com.canalmind.intent.IntentRouter;
import com.canalmind.intent.RouteDecision;
import com.canalmind.rag.retrieve.NoopReranker;
import com.canalmind.rag.retrieve.RetrievalConfig;
import com.canalmind.rag.retrieve.RetrievalPipeline;
import com.canalmind.rag.retrieve.RrfFusion;
import com.canalmind.rag.retrieve.VectorRetrievalChannel;
import com.canalmind.rag.vector.VectorHit;
import com.canalmind.rag.vector.VectorSearcher;
import com.canalmind.structured.FeeCalculationEngine;
import com.canalmind.structured.JsonChatInvoker;
import com.canalmind.structured.SchemaGate;
import com.canalmind.structured.StructuredDataLookup;
import com.canalmind.structured.StructuredQueryRepository;
import com.canalmind.structured.StructuredQueryService;
import com.canalmind.structured.TariffRuleLookup;
import com.canalmind.structured.TariffRuleRow;
import com.canalmind.structured.ToolArgumentExtractor;
import com.canalmind.structured.ToolCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库《AI 可观测性与 Trace：如何还原 Agent 的完整执行过程》第 465–472 行的
 * 「<b>8 个集成测试场景</b>」在本项目里的落地（规格书第 661 行那半句）。
 *
 * <p>本类覆盖其中 <b>①②③④⑦⑧</b> 六个（离线、零付费、不连库）；
 * <b>⑤ 流式模型响应</b>与 <b>⑥ HTTP 下游</b>需要真实发出 HTTP 请求，
 * 由同包的 {@code ObservabilityStreamingAndDownstreamTest} 用本地桩服务器覆盖。
 *
 * <h2>逐条映射（⚠️ 有偏差的地方如实写在这里，不迁就）</h2>
 * <ol>
 *   <li>① 正常 RAG 问答 ⇒ {@code QuestionPipeline.run} 走 R2「规则话题」⇒ 检索通道。
 *       断言的是<b>父子关系</b>，不是「平台里有 Trace」。
 *       ⚠️ <b>实测</b>：检索段挂根下、它的 3 个子步骤挂检索段下，而<b>生成段与检索段是兄弟</b>
 *       （都挂在根下）⇒ Trace 上看不出「这一次生成用的是哪一次检索的结果」，
 *       只能看出时间先后。这条差异如实记入证据文档，⛔ 不为了让断言好看而改动它。</li>
 *   <li>② 模型超时后重试 ⇒ 抽取层 {@code ToolArgumentExtractor} 的带错误反馈重试。
 *       ⚠️ 本项目<b>只有这一处</b>重试会逐次新建 Span 并带 {@code attempt}；
 *       {@code LlmCallGuard}（C-58）的退避重试<b>不产生独立 Attempt Span</b>（如实记）。</li>
 *   <li>③ 工具返回业务失败 ⇒ {@code ToolResult} 的 {@code DEGRADED}/{@code NO_DATA} 等状态。
 *       ⚠️ <b>实测两种业务失败的可观测结果完全不同</b>：{@code TOOL_NONE} 会回退检索并写下
 *       {@code ai.rag.fallback=true}；抽取重试用尽那种（{@code degradeKind≠TOOL_NONE}）
 *       <b>既不回退、也不留任何键</b>。§7.3 白名单里没有任何键能承载 {@code ToolResult}
 *       的业务状态 ⇒ 本类断言「业务失败不被记成技术错误」+ 两种失败各自的实际可观测结果。</li>
 *   <li>④ 线程池 / 虚拟线程并行 ⇒ Observation 父指针的跨线程传播。
 *       ⚠️ <b>类路径上没有 tracing 桥</b>（实测：无 {@code micrometer-tracing}、无
 *       {@code opentelemetry}）⇒ 本项目<b>根本没有 traceId</b>，
 *       故知识库第 480 行「跨线程 / 跨服务 {@code traceId} 是否一致」在本项目<b>无法断言</b>，
 *       只能用<b>父指针</b>代替；且 {@code commonPool} 下父指针本就丢失（框架行为）。</li>
 *   <li>⑦ Agent 暂停后恢复 ⇒ ⚠️ <b>本项目没有 Agent</b>，用「多轮会话续接」做<b>映射</b>：
 *       断言「续接只是新起一棵树、⛔ 没有任何暂停/恢复节点」，
 *       ⛔ 不造假节点（同 {@code ai.output.validate} 按 D23 挂起的处置）。</li>
 *   <li>⑧ 敏感信息 ⇒ 导出产物里的属性键与取值：断言「只出现 §7.3 白名单里的键」
 *       +「问句原文/密钥串一个字节都不出现」。</li>
 * </ol>
 *
 * <h2>为什么断言父指针而不是「Span 出现过」</h2>
 * <p>本项目实测过一次「名字全对、Span 一条不少、整棵树却全是平的」
 * （{@code docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md}）。
 * 所以「某 Span 出现过」是<b>无法证伪</b>的话，必须断言<b>谁挂在谁下面</b>。
 *
 * <p>⛔ <b>本类不许放宽断言换绿</b>：若某条断言变红，说明「我们以为的结构」与「真实结构」不一致，
 * 那正是要报出来的事。
 */
class ObservabilityEightScenariosTest {

    private static final ToolCatalog CATALOG = ToolCatalog.loadDefault();

    /** 与既有装配同源的业务日（{@code StructuredQueryServiceTest} 用的是同一天）。 */
    private static final String TODAY = "2027-09-15";

    /** 场景 ① 的题面：{@code IntentRouterTest} 已钉住它走 R2 ⇒ 检索通道。 */
    private static final String RAG_Q = "停泊费是按天计算的，如果船停了不满一天，要算几天？";

    /** 走结构化通道的题面（{@code IntentRouterTest.numericDemandGoesToStructured} 钉住 R1）。 */
    private static final String NUMERIC_Q = "过闸费一吨多少钱？";

    /** 合规的 quote_fee 抽取结果（形状取自 {@code ToolArgumentExtractorTest}）。 */
    private static final String QUOTE_FEE_JSON = """
            {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_name":"成品油",
             "tonnage":1200,"biz_date":"2026-10-15"},"missing":[],"reason":"测算过闸费"}""";

    /** 不合法的应答：不是 JSON ⇒ 抽取层应当带错误反馈重试。 */
    private static final String NOT_JSON = "这不是 JSON，模型又忘了格式";

    /** 抽取层的 TOOL_NONE：按契约「本题不属于结构化工具集」（形状取自 ToolArgumentExtractorTest）。 */
    private static final String TOOL_NONE_JSON =
            "{\"tool\":\"none\",\"arguments\":{},\"missing\":[],\"reason\":\"政策咨询\"}";

    /**
     * R4 的题面：只提到业务对象、没提数值 ⇒ 先进结构化，再由抽取层的 TOOL_NONE 决定转交检索。
     *
     * <p>⚠️ 该题面取自 {@code IntentRouterTest.objectOnlyGoesToStructured}（已被测试钉住为 R4）。
     */
    private static final String OBJECT_ONLY_Q = "有哪些化学品船？";

    // ==================== 场景 ①：正常的 RAG 问答 ====================

    @Test
    @DisplayName("场景①：正常 RAG 问答——检索段挂根下、3 个子步骤挂检索段下；⚠️ 生成段实测与检索段是兄弟")
    void scenario1NormalRagQuestionKeepsRetrievalAndGenerationInOneChain() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        QuestionPipeline.Answer answer = pipeline(registry, stubInvoker(NOT_JSON)).run(RAG_Q);

        assertThat(answer.route().channel())
                .as("场景 ① 的前提：该题面必须被规则判为检索通道，否则跑的根本不是 RAG 链路")
                .isEqualTo(RouteDecision.CHANNEL_RETRIEVAL);

        Recorder.Span root = rec.only(AiSpans.AGENT_RUN);
        assertThat(root.parent).as("ai.agent.run 必须是根").isNull();

        Recorder.Span intent = rec.only(AiSpans.INTENT_ROUTE);
        assertThat(intent.parent)
                .as("路由必须挂在根下；否则说明路由发生在根 Span 作用域之外")
                .isEqualTo(AiSpans.AGENT_RUN);

        Recorder.Span retrieve = rec.only(AiSpans.RAG_RETRIEVE);
        assertThat(retrieve.parent)
                .as("检索段必须挂在根下")
                .isEqualTo(AiSpans.AGENT_RUN);

        // 检索段内部的三个子步骤必须**都**挂在检索段下（这是「检索这一段能整体还原」的证据）
        for (String step : List.of(AiSpans.DB_VECTOR_SEARCH, AiSpans.RAG_HYBRID,
                AiSpans.RAG_CONTEXT_BUILD)) {
            assertThat(rec.only(step).parent)
                    .as("检索子步骤「" + step + "」必须挂在 " + AiSpans.RAG_RETRIEVE + " 之下；"
                            + "挂在根下说明它脱离了检索段，Trace 上看不出它属于哪次检索。本次全部："
                            + rec.spans)
                    .isEqualTo(AiSpans.RAG_RETRIEVE);
        }

        Recorder.Span generate = rec.only(AiSpans.LLM_GENERATE);
        // ⚠️ 实测（本次运行）：生成段挂在**根**下，不是挂在检索段下。这**不是我原先的预期**——
        //    本条断言如实记录真实结构，并把这条差异写进证据文档（⛔ 不放宽、也不改生产代码）。
        assertThat(generate.parent)
                .as("⚠️ 实测：ai.llm.generate 的父是 " + AiSpans.AGENT_RUN + "，不是 "
                        + AiSpans.RAG_RETRIEVE + "。含义是——Trace 上能看出「先检索、后生成」的"
                        + "时间先后，却看不出「这一次生成用的是哪一次检索的结果」（两者是兄弟而非父子）。"
                        + "知识库第 476 行要求核对「父子关系是否正确」：这条差异已记入证据文档，"
                        + "本任务只许改 test/** ⇒ 如实断言现状。"
                        + "若将来把生成嵌进检索段，本条会变红，届时应改成断言 "
                        + AiSpans.RAG_RETRIEVE + "。本次全部 Span：" + rec.spans)
                .isEqualTo(AiSpans.AGENT_RUN);

        assertNoOrphanSpans(rec);
        assertAllSpanNamesAreRegistered(rec);
    }

    // ==================== 场景 ②：模型超时后重试 ====================

    @Test
    @DisplayName("场景②：重试——每次尝试各一个 Span、attempt 逐次递增、互为兄弟（不是一条合并记录）")
    void scenario2EachRetryGetsItsOwnAttemptSpan() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        // 第一次回非 JSON（等价于「这次应答不可用」），第二次回合规 JSON
        QuestionPipeline pipeline = pipeline(registry, sequentialInvoker(NOT_JSON, QUOTE_FEE_JSON));
        pipeline.run(NUMERIC_Q);

        List<Recorder.Span> attempts = rec.all(AiSpans.TOOL_PARAM_EXTRACT);
        assertThat(attempts)
                .as("知识库第 55 行要求「每次重试都新建 Span 并用 attempt 标明次数」；"
                        + "若这里只有 1 条，说明重试被合并进同一条记录——"
                        + "于是「第一次失败、第二次成功」在 Trace 上再也分不开。本次全部 Span："
                        + rec.spans)
                .hasSize(2);

        assertThat(attempts.stream().map(s -> s.attr(AiSpans.KEY_ATTEMPT)).toList())
                .as("attempt 必须从 1 起且逐次递增（⛔ 不从 0 起：0 会与「未开始」混淆）")
                .containsExactly("1", "2");

        assertThat(new HashSet<>(attempts.stream().map(s -> s.parent).toList()))
                .as("两次尝试必须是兄弟（同一父）。父不同说明重试换了调用栈，那不是重试而是两次独立调用")
                .hasSize(1);

        assertThat(rec.all(AiSpans.TOOL_PARAM_VALIDATE))
                .as("每次尝试都要走一遍服务端 Schema 校验（§7.2 的 ai.tool.param.validate）")
                .hasSizeGreaterThanOrEqualTo(2);

        assertThat(rec.names())
                .as("⚠️ 如实记现状：本项目目前只有抽取层会为每次尝试建 Span；"
                        + "C-58 的 LlmCallGuard 退避重试不产生独立 Attempt Span。"
                        + "⛔ 这条断言记录的是现状而非主张它正确")
                .contains(AiSpans.TOOL_PARAM_EXTRACT);
    }

    // ==================== 场景 ③：工具返回业务失败 ====================

    @Test
    @DisplayName("场景③：工具业务失败——不记成技术错误，但回退键必须亮起（业务结果与错误状态可区分）")
    void scenario3ToolBusinessFailureIsNotASpanError() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        // 抽取永远回非 JSON ⇒ 重试用尽 ⇒ StructuredQueryService 应返回业务失败
        QuestionPipeline.Answer answer = pipeline(registry, stubInvoker(NOT_JSON)).run(NUMERIC_Q);

        assertThat(answer.tool()).as("结构化通道必须给出一个 ToolResult（哪怕是失败）").isNotNull();
        assertThat(answer.tool().ok())
                .as("本场景的前提：工具返回的是业务失败（⛔ 不是抛异常）。"
                        + "若这里为 true，说明抽取居然成功了，本场景没被覆盖到")
                .isFalse();

        assertThat(rec.errors())
                .as("⚠️ 业务失败不得被记成 Span 错误：错误状态应当只留给「链路本身坏了」。"
                        + "若这里非空，说明有人在业务失败时抛异常，于是 Trace 上"
                        + "「工具说没有这条数据」和「数据库连不上」长得一模一样。本次全部 Span："
                        + rec.spans)
                .isEmpty();

        Recorder.Span root = rec.only(AiSpans.AGENT_RUN);
        assertThat(root.attr(AiSpans.KEY_RAG_FALLBACK))
                .as("⚠️ 实测：抽取重试用尽（degradeKind 不是 TOOL_NONE）后**不回退检索**，"
                        + "于是根 Span 上根本没有 ai.rag.fallback —— 这次业务失败在 Trace 上"
                        + "一个键都没留下。知识库第 478 行要求核对「错误状态、业务结果…是否准确」，"
                        + "在这条路径上**未达成**（已记入证据文档的「我没做到的」一节）。"
                        + "若将来补上承载键、或改成也回退，本条会变红——那是对的，应改成断言新行为")
                .isNull();
        assertThat(answer.retrievalFallback())
                .as("同上：这条业务失败没有触发回退（回退只对 TOOL_NONE 生效，见 QuestionPipeline 第 329 行）")
                .isFalse();
        assertThat(answer.route().channel())
                .as("业务失败后仍留在结构化通道（未转交检索）")
                .isEqualTo(RouteDecision.CHANNEL_STRUCTURED);

        assertThat(AiSpans.LOW_CARDINALITY_KEYS)
                .as("本断言记录的是一条已知缺口：§7.3 白名单里没有任何键能承载 ToolResult 的业务状态"
                        + "（NO_DATA / REJECTED / DEGRADED…），所以「业务结果准确可查」这条验证点"
                        + "在本项目只完成了一半")
                .doesNotContain("ai.tool.status", "ai.tool.result", "tool.status");
    }

    @Test
    @DisplayName("场景③（对照）：TOOL_NONE 型业务失败会回退检索，且 ai.rag.fallback=true（与上一条形成对照）")
    void scenario3bToolNoneBusinessFailureDoesFallBackAndLeavesAKey() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        QuestionPipeline.Answer answer = pipeline(registry, stubInvoker(TOOL_NONE_JSON)).run(OBJECT_ONLY_Q);

        assertThat(answer.tool()).as("结构化通道给出了业务失败（TOOL_NONE 也是一种业务失败）").isNotNull();
        assertThat(answer.tool().ok()).as("本场景的前提：工具结果不是 OK").isFalse();
        assertThat(answer.retrievalFallback())
                .as("TOOL_NONE 按契约要**转交检索**（这是『内部路由修正』，不是『答案取不到』）")
                .isTrue();
        assertThat(rec.only(AiSpans.AGENT_RUN).attr(AiSpans.KEY_RAG_FALLBACK))
                .as("回退发生时，§7.3 的 ai.rag.fallback 必须为 true —— 这是两种业务失败"
                        + "在 Trace 上唯一的可区分标志")
                .isEqualTo("true");
        assertThat(rec.errors())
                .as("回退不是错误：任何 Span 都不该被记成错误状态")
                .isEmpty();
    }

    // ==================== 场景 ④：线程池 / 虚拟线程并行 ====================

    @Test
    @DisplayName("场景④：并行——commonPool 下子 Span 照常产生，但父指针丢失（框架行为，如实断言现状）")
    void scenario4CommonPoolLosesTheParentButKeepsTheSpan() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
        root.observe(() -> {
            try {
                ForkJoinPool.commonPool()
                        .submit(() -> AiObservation.start(AiSpans.INTENT_ROUTE, registry)
                                .observe(() -> "并行路由"))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return "ok";
        });

        Recorder.Span child = rec.only(AiSpans.INTENT_ROUTE);
        assertThat(child.parent)
                .as("⚠️ 本条断言的是当前事实（Observation 作用域存在 ThreadLocal 里，"
                        + "commonPool 的工作线程没有它）⇒ 父指针为 null。"
                        + "注意跨线程丢的是父指针、不是 Span 本身（上面的 only() 已证明 Span 真的产生了）。"
                        + "若本条变红，说明 Micrometer / context-propagation 改了默认语义，"
                        + "应当复核 README 的已知限制，并把本条改成断言「父指针已跨池传播」")
                .isNull();

        assertThat(classpathHasTracingBridge())
                .as("⛔ 知识库第 480 行的「跨线程和跨服务以后 traceId 是否保持一致」在本项目"
                        + "**无法断言**——因为类路径上根本没有 tracing 桥（本断言在运行时真的去查类路径，"
                        + "不是一句注释）：实测只有 micrometer-observation，没有 micrometer-tracing、"
                        + "没有 opentelemetry ⇒ 本项目不存在 traceId 这个量。"
                        + "若本条变红，说明 tracing 桥已接入：请改为断言两端 traceId 一致，"
                        + "并同步 README 的已知限制与规格书第 661 行那半句的状态")
                .isFalse();
    }

    /** 运行时真查类路径：是否存在 tracing 桥（决定本项目有没有 traceId 可言）。 */
    private static boolean classpathHasTracingBridge() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            String lower = entry.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("micrometer-tracing") || lower.contains("opentelemetry")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 场景 ⑦：Agent 暂停后恢复（⚠️ 映射，不是 Agent） ====================

    @Test
    @DisplayName("场景⑦：会话续接=新起一棵树——⛔ 没有任何暂停/恢复节点，也不该有（映射，不是 Agent 挂起）")
    void scenario7MultiTurnContinuationIsANewTreeNotAResume() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        InMemorySessionStore store = new InMemorySessionStore();
        QuestionPipeline pipeline = pipeline(registry, stubInvoker(NOT_JSON));

        QuestionPipeline.Answer first = pipeline.run(RAG_Q);
        store.save(SessionState.empty("s-1")
                .withTurn(SessionTurn.of(1, RAG_Q, first.text(), first.channel())));

        SessionState loaded = store.load("s-1");
        assertThat(loaded.rawTurns())
                .as("本场景的映射依据：上一轮的问答确实被存下来了（会话续接的事实基础）")
                .hasSize(1);
        assertThat(loaded.rawTurns().get(0).question())
                .as("续接的实质：上一轮的问题文本必须还在会话状态里")
                .isEqualTo(RAG_Q);

        // 「恢复」= 带着会话历史的第二次执行
        rec.spans.clear();
        pipeline.run("那要是我停了 3 天呢？");

        assertThat(rec.all(AiSpans.AGENT_RUN))
                .as("每一轮各起一棵树：根恰好 1 个。为 0 说明根没建，为 2 说明上一轮的根泄漏到了本轮")
                .hasSize(1);

        assertAllSpanNamesAreRegistered(rec);
        assertThat(rec.names())
                .as("⚠️ 本项目没有 Agent，也就没有「暂停/恢复」这个环节：知识库第 471 行的场景⑦"
                        + "在本项目属映射（多轮会话续接）。⛔ 断言「没有 resume/pause 节点」是为了"
                        + "防止有人为了凑场景而造一个恒不触发的假节点（同 ai.output.validate 按 D23 挂起）")
                .noneMatch(n -> n.contains("resume") || n.contains("pause") || n.contains("session"));
    }

    // ==================== 场景 ⑧：Prompt / 工具结果含敏感信息 ====================

    @Test
    @DisplayName("场景⑧：敏感信息——导出产物只含 §7.3 白名单键，问句原文与密钥串一个字节都不出现")
    void scenario8SensitiveInputNeverReachesExportedAttributes() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);

        // 猜疑链：把密钥串塞进问句——这是最容易被顺手写进属性的路径
        String secret = "sk-live-SECRET-1234567890abcdef";
        String questionWithSecret = "过闸费怎么算？顺便记一下我的密钥是 " + secret;

        QuestionPipeline pipeline = pipeline(registry, stubInvoker(NOT_JSON));
        pipeline.run(questionWithSecret);

        String json = exporter.toJson();
        assertThat(exporter.spanCount()).as("场景 ⑧ 的前提：本次执行确实产生了 Span").isGreaterThan(0);

        assertThat(json)
                .as("⛔ 密钥串绝不允许出现在导出产物里（知识库第 200、451、531–533 行："
                        + "原始问题 / 正文 / Prompt 原文不得作为属性）。变红即说明有人把问句原文写进了属性")
                .doesNotContain(secret);
        assertThat(json)
                .as("⛔ 问句原文同样不许出现——密钥只是最容易看出来的那一种敏感信息")
                .doesNotContain("顺便记一下我的密钥是");
        assertThat(json)
                .as("⛔ 问句的任何一段都不该进产物：抽查问句里的普通片段也应当查不到")
                .doesNotContain("过闸费怎么算");

        Set<String> exportedKeys = attributeKeys(json);
        Set<String> allowed = new HashSet<>(AiSpans.LOW_CARDINALITY_KEYS);
        allowed.addAll(AiSpans.HIGH_CARDINALITY_KEYS);
        allowed.add("error.type");

        assertThat(exportedKeys)
                .as("导出产物里的属性键必须全部落在 §7.3 白名单内（外加 Micrometer 自带的 error.type）。"
                        + "出现白名单之外的键 = 有人在别处绕过了 AiObservation 的三道闸门")
                .isSubsetOf(allowed);
        assertThat(exportedKeys)
                .as("反过来：本该出现的键必须真的出现了——否则「只含白名单键」会因为产物里根本没有键而假绿")
                .isNotEmpty();
    }

    // ==================== 共用装配（与 AiSpanTreeTest 同源） ====================

    private static QuestionPipeline pipeline(ObservationRegistry registry, JsonChatInvoker invoker)
            throws Exception {
        VectorSearcher searcher = Mockito.mock(VectorSearcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(
                        new VectorHit("c1", "06-过闸费费率表（2026版）", "过闸费费率表（2026版）",
                                "BODY", "第一条", "目的与依据", "2026", 0.81),
                        new VectorHit("c2", "05-计费总则", "计费总则", "TABLE", null, null, "2026", 0.5)));

        ToolArgumentExtractor extractor = new ToolArgumentExtractor(CATALOG,
                SchemaGate.of(CATALOG, registry), invoker, ToolArgumentExtractor.DEFAULT_MAX_RETRIES,
                TODAY, registry);
        StructuredQueryService structured = new StructuredQueryService(CATALOG, extractor,
                new FakeTariff(), new FakeData(), new FeeCalculationEngine(registry), TODAY, registry);

        RetrievalPipeline retrieval = new RetrievalPipeline(
                List.of(new VectorRetrievalChannel(searcher, registry)),
                new RrfFusion(60, registry), new NoopReranker(),
                new RetrievalConfig(30, 20, 10, 5, 60, false, RrfFusion.NAME, NoopReranker.NAME),
                registry);

        AnswerComposer composer = new AnswerComposer(stubChatModel(), null, Map.of(), registry);
        return new QuestionPipeline(new StubJdbc(), new IntentRouter(invoker, registry), structured,
                retrieval, composer, registry);
    }

    private static JsonChatInvoker stubInvoker(String reply) {
        return (sys, user) -> reply;
    }

    private static JsonChatInvoker sequentialInvoker(String... replies) {
        List<String> queue = new ArrayList<>(List.of(replies));
        return (sys, user) -> queue.isEmpty() ? replies[replies.length - 1] : queue.remove(0);
    }

    private static ChatModel stubChatModel() {
        ChatModel model = Mockito.mock(ChatModel.class);
        ChatResponse response = Mockito.mock(ChatResponse.class);
        Generation generation = Mockito.mock(Generation.class);
        Mockito.when(response.getResult()).thenReturn(generation);
        Mockito.when(generation.getOutput()).thenReturn(new AssistantMessage("已按依据作答。（桩）"));
        Mockito.when(model.call(Mockito.any(Prompt.class))).thenReturn(response);
        return model;
    }

    private static final class FakeTariff implements TariffRuleLookup {

        private static final List<TariffRuleRow> RULES = List.of(new TariffRuleRow(1L,
                "CT-过闸费-能源-100", "过闸费", "能源", "载货吨位",
                new BigDecimal("100"), new BigDecimal("100000"), new BigDecimal("2.55"), "元/吨",
                LocalDate.of(2026, 9, 16), LocalDate.of(2027, 1, 1)));

        @Override
        public Optional<TariffRuleRow> findRule(String feeType, String cargoCategory, String tonnageBasis,
                                                BigDecimal tonnage, LocalDate bizDate) {
            return RULES.stream()
                    .filter(r -> r.feeType().equals(feeType) && r.cargoCategory().equals(cargoCategory)
                            && r.tonnageBasis().equals(tonnageBasis) && r.covers(tonnage)
                            && !bizDate.isBefore(r.effectiveFrom()) && bizDate.isBefore(r.effectiveTo()))
                    .findFirst();
        }

        @Override
        public List<TariffRuleRow> listByFeeType(String feeType, LocalDate bizDate) {
            return RULES.stream().filter(r -> r.feeType().equals(feeType)).toList();
        }
    }

    private static final class FakeData implements StructuredDataLookup {

        @Override
        public Boolean cargoCategoryDangerous(String cargoCategory) {
            return "能源".equals(cargoCategory);
        }

        @Override
        public List<StructuredQueryRepository.GroupRow> traffic(LocalDate from, LocalDate to,
                                                               String metric, String groupBy,
                                                               String feeType, boolean viaCanalOnly,
                                                               int topN) {
            return List.of();
        }

        @Override
        public List<List<String>> waybills(String waybillNo, LocalDate from, LocalDate to,
                                           String status, String cargoCategory, boolean viaCanalOnly,
                                           int limit) {
            return List.of();
        }

        @Override
        public List<List<String>> vessels(String vesselId, String vesselType, Integer minGrossTonnage,
                                          BigDecimal maxDraft, int limit) {
            return List.of();
        }
    }

    /** 桩 JdbcTemplate：只服务取 chunk 正文这一步（⛔ 不连库）。 */
    private static final class StubJdbc extends JdbcTemplate {

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            return List.of(elementType.cast("闸室水深以现场标尺为准。（桩正文）"));
        }
    }

    // ==================== 断言工具 ====================

    /**
     * 捕获每次 Span <b>停止时</b>的名字、父名、全部属性与错误。
     *
     * <p>⚠️ 属性必须在 {@code onStop} 取：{@code observe(...)} 体内的属性是启动之后才写的。
     */
    private static final class Recorder implements ObservationHandler<Observation.Context> {

        final List<Span> spans = new ArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                attrs.put(kv.getKey(), kv.getValue());
            }
            for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                attrs.put(kv.getKey(), kv.getValue());
            }
            spans.add(new Span(context.getName(),
                    context.getParentObservation() == null
                            ? null
                            : context.getParentObservation().getContextView().getName(),
                    attrs,
                    context.getError() == null ? null : context.getError().getClass().getName()));
        }

        List<String> names() {
            return spans.stream().map(s -> s.name).toList();
        }

        List<Span> all(String name) {
            return spans.stream().filter(s -> s.name.equals(name)).toList();
        }

        /** 被记了错误的 Span（业务失败不得出现在这里）。 */
        List<Span> errors() {
            return spans.stream().filter(s -> s.error != null).toList();
        }

        Span only(String name) {
            List<Span> got = all(name);
            assertThat(got)
                    .as("本次执行里 Span「" + name + "」应当恰好出现 1 次（实际 " + got.size()
                            + " 次）。0 次 = 该环节没埋上或被短路；多次 = 同一环节被重复埋了")
                    .hasSize(1);
            return got.get(0);
        }

        static final class Span {
            final String name;
            final String parent;
            final Map<String, String> attrs;
            final String error;

            Span(String name, String parent, Map<String, String> attrs, String error) {
                this.name = name;
                this.parent = parent;
                this.attrs = attrs;
                this.error = error;
            }

            String attr(String key) {
                return attrs.get(key);
            }

            @Override
            public String toString() {
                return name + "(parent=" + parent + ", attrs=" + attrs + ", error=" + error + ")";
            }
        }
    }

    /** 除根以外每个 Span 都必须有父，且父必须也在本次记录里（父指针静默丢失的正面判据）。 */
    private static void assertNoOrphanSpans(Recorder rec) {
        for (Recorder.Span s : rec.spans) {
            if (AiSpans.AGENT_RUN.equals(s.name)) {
                assertThat(s.parent).as("根 Span 的父必须是 null").isNull();
                continue;
            }
            assertThat(s.parent)
                    .as("Span「" + s.name + "」的父指针丢了（null）——见"
                            + "《观测埋点的父指针会静默丢失》：对子 Span 用了手工 start/stop，"
                            + "或中途换了另一个 ObservationRegistry。本次全部：" + rec.spans)
                    .isNotNull();
            assertThat(rec.names())
                    .as("Span「" + s.name + "」的父「" + s.parent + "」不在本次记录里——"
                            + "说明父子来自不同的注册表（两棵断开的树）")
                    .contains(s.parent);
        }
    }

    /** ⛔ 任何 Span 名都必须来自 §7.2 的 16 个登记名（⛔ 不得新增第 17 个）。 */
    private static void assertAllSpanNamesAreRegistered(Recorder rec) {
        assertThat(rec.names())
                .as("Span 名必须全部来自 §7.2 的登记名（AiSpans.ALL）。出现新名字 = 有人绕过"
                        + "AiObservation/AiSpans 就地拼了字符串，而 AiObservabilityTest 的逐字比对"
                        + "会因为白名单没变而照样绿")
                .isSubsetOf(AiSpans.ALL);
    }

    /** 从导出 JSON 里抽出全部属性键（低基数 + 高基数两组）。 */
    private static Set<String> attributeKeys(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        Set<String> keys = new HashSet<>();
        collectKeys(root, keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (String field : List.of("lowCardinality", "highCardinality")) {
            JsonNode group = node.get(field);
            if (group != null && group.isObject()) {
                group.fieldNames().forEachRemaining(keys::add);
            }
        }
        // ⚠️ 顶层是 roots（数组），节点之间才是 children（实测的 JSON 形状，见 SpanTreeExporter 第 308/344/357 行）
        for (String nested : List.of("roots", "children")) {
            JsonNode children = node.get(nested);
            if (children != null) {
                collectKeys(children, keys);
            }
        }
    }
}
