package com.canalmind.observability;

import com.canalmind.config.PromptVersions;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规格书 §7.2 的<b>业务 Span 树</b>契约测试：把真实的编排跑一遍，逐条核对<b>父指针</b>。
 *
 * <h2>为什么要专门写这一组（而不满足于"名字对得上"）</h2>
 *
 * <p>{@code AiObservabilityTest} 只保证"名字集合 = 规格书 §7.2 的名字集合"。
 * 但本项目的实测教训是：**名字全对、Span 一条不少、整棵树却全是平的**——
 * 手工 {@code start()}/{@code stop()} 时子 Span 启动那一刻"当前观测"不存在，
 * 于是父指针清一色 {@code null}，而**不会报任何错**
 * （见 {@code docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md}）。
 * ⇒ 只断言"Span 出现过"是一句**无法证伪**的话；必须断言 <b>谁挂在谁下面</b>。
 *
 * <h2>本类的四条纪律</h2>
 * <ol>
 *   <li>⛔ <b>不连库、不调模型</b>：JdbcTemplate / ChatModel / VectorSearcher 全是桩，
 *       费率表与货类表是内存假实现（同 {@code StructuredQueryServiceTest} /
 *       {@code RetrievalPipelineTest} 的做法），故本类可离线、零付费运行；</li>
 *   <li>⛔ <b>不手工 start/stop</b>：业务侧一律走 {@code observe(...)}，
 *       本类只在桩装配上建注册表；</li>
 *   <li>⛔ <b>不宽纵断言</b>：每条断言的失败消息都写明"这说明什么坏了"；</li>
 *   <li>⚠️ <b>与真实结构不符的地方如实记</b>（如 {@code ai.fee.calculate} 实际嵌在
 *       {@code ai.structured.query} 之下，而不是规格书画的那样与它并列）——
 *       宁可记一笔差异，也不为了让断言好看而改写规格。</li>
 * </ol>
 */

class AiSpanTreeTest {

    private static final ToolCatalog CATALOG = ToolCatalog.loadDefault();

    /** 与既有装配同源的业务日（{@code StructuredQueryServiceTest} 用的是同一天）。 */
    private static final String TODAY = "2027-09-15";

    /**
     * 真实 Golden Set 里的 L5 复合题（R3：数值诉求 + 规则诉求 + 多问句）。
     *
     * <p>⚠️ 用真实题面而不是自造例句：路由是<b>规则</b>判的，
     * 自造例句很容易"看着像复合、实际命中别的规则"，于是测试从此不再覆盖它想覆盖的分支。
     * 这条题面由 {@code L5RunTest} 的既有自检保证 route=COMPOSITE。
     */
    private static final String COMPOSITE_Q =
            "1200 吨成品油 2026 年 10 月 15 号过闸，过闸费要交多少？"
                    + "另外这货又算危险品又算保供物资，两个都往上加吗？";

    /**
     * R4 的题面：只提到业务对象、没提数值 ⇒ 先进结构化，再由抽取层的 TOOL_NONE 决定转交检索。
     *
     * <p>⚠️ 该题面取自 {@code IntentRouterTest.objectOnlyGoesToStructured}（已被测试钉住为 R4）。
     */
    private static final String OBJECT_ONLY_Q = "有哪些化学品船？";

    /** 合规的 quote_fee 抽取结果（形状取自 {@code ToolArgumentExtractorTest}）。 */
    private static final String QUOTE_FEE_JSON = """
            {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_name":"成品油",
             "tonnage":1200,"biz_date":"2026-10-15"},"missing":[],"reason":"测算过闸费"}""";

    /** 抽取层的 TOOL_NONE：按契约"本题不属于结构化工具集"（形状取自 ToolArgumentExtractorTest）。 */
    private static final String TOOL_NONE_JSON =
            "{\"tool\":\"none\",\"arguments\":{},\"missing\":[],\"reason\":\"政策咨询\"}";

    /** 不合法的应答：不是 JSON ⇒ 抽取层应当重试（用于"每次重试各一个 Span"的断言）。 */
    private static final String NOT_JSON = "这不是 JSON，模型又忘了格式";

    // ==================== ① 整棵树：根 / 兄弟 / 三层链 ====================

    @Test
    @DisplayName("★ §7.2 的树：ai.agent.run 为根，intent.route 与 structured.query / rag.retrieve 都挂它下面")
    void compositeRunBuildsTheSpecTree() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        QuestionPipeline pipeline = pipeline(registry, stubInvoker(QUOTE_FEE_JSON));
        QuestionPipeline.Answer answer = pipeline.run(COMPOSITE_Q);

        // ── ① 根：父必须是 null（父指针非 null 说明有人把它挂到了别处） ──
        Recorder.Span root = rec.only(AiSpans.AGENT_RUN);
        assertThat(root.parent)
                .as("ai.agent.run 必须是根：父为 null。非 null 说明它被别的 Span 包住了")
                .isNull();

        // ── ② 路由：父 = 根 ──
        assertThat(rec.only(AiSpans.INTENT_ROUTE).parent)
                .as("ai.intent.route 必须挂在 ai.agent.run 之下（路由发生在任何通道调用之前）")
                .isEqualTo(AiSpans.AGENT_RUN);

        // ── ③ 两条通道**同为根的兄弟**（复合题是串行两步，不是互包） ──
        assertThat(rec.only(AiSpans.STRUCTURED_QUERY).parent)
                .as("ai.structured.query 的父应当是 ai.agent.run（通道由编排层调用）")
                .isEqualTo(AiSpans.AGENT_RUN);
        assertThat(rec.only(AiSpans.RAG_RETRIEVE).parent)
                .as("ai.rag.retrieve 的父应当是 ai.agent.run——它与 structured.query 是**兄弟**，"
                        + "⛔ 不是它的子节点（两步是串行关系，不是嵌套关系）")
                .isEqualTo(AiSpans.AGENT_RUN);

        // ── ④ 三层链：agent.run → rag.retrieve → db.vector.search ──
        assertThat(rec.only(AiSpans.DB_VECTOR_SEARCH).parent)
                .as("db.vector.search 必须是 ai.rag.retrieve 的子节点（三层链的第 3 层）；"
                        + "父为 null ⇒ 向量检索的耗时无法归到检索这一步上")
                .isEqualTo(AiSpans.RAG_RETRIEVE);

        // ── ④' 另一条三层链（更深一层）：agent.run → structured.query → extract → validate ──
        assertThat(rec.only(AiSpans.TOOL_PARAM_EXTRACT).parent)
                .as("ai.tool.param.extract 必须是 ai.structured.query 的子节点")
                .isEqualTo(AiSpans.STRUCTURED_QUERY);
        // ⚠️ 校验是**两个** Span 而不是一个：envelope（模型输出的整体形状）与
        //    arguments（所选工具的每个参数）是两次独立校验（SchemaGate 的类注释有说明），
        //    故这里断言"全部、且每一个都挂在本次抽取之下"。
        assertThat(rec.all(AiSpans.TOOL_PARAM_VALIDATE))
                .as("一次尝试要校 envelope 与 arguments 两步 ⇒ 恰好 2 个校验 Span。"
                        + "出现 0 个说明校验没埋上；出现 3 个以上说明抽取被重试了"
                        + "（本跑的桩应答一次就合规）")
                .hasSize(2);
        for (Recorder.Span v : rec.all(AiSpans.TOOL_PARAM_VALIDATE)) {
            assertThat(v.parent)
                    .as("ai.tool.param.validate 必须挂在**这一次尝试**的 ai.tool.param.extract 之下"
                            + "（挂在 structured.query 或根上就说不清校验的是哪一次尝试）")
                    .isEqualTo(AiSpans.TOOL_PARAM_EXTRACT);
            assertThat(v.attr(AiSpans.KEY_RAG_RESULT_COUNT))
                    .as("校验 Span 要记错误条数（0 = 这次抽出来的参数合规）")
                    .isEqualTo("0");
        }
        // ⚠️ 两个 Span **同名**，所以光数"有 2 个"并不能说明它们是两件不同的事：
        //    必须再断言它们的 stage 不同，否则读者看到的是"两个无法区分的同名节点"。
        assertThat(rec.all(AiSpans.TOOL_PARAM_VALIDATE))
                .extracting((Recorder.Span v) -> v.attr(AiSpans.KEY_TOOL_VALIDATE_STAGE))
                .as("envelope 与 arguments 两次校验必须**各自上报自己的 stage**——"
                        + "否则两个同名兄弟节点在 Trace 上分不出哪一条对应哪种补救")
                .containsExactlyInAnyOrder(AiSpans.VALUE_VALIDATE_STAGE_ENVELOPE,
                        AiSpans.VALUE_VALIDATE_STAGE_ARGUMENTS);

        // ── ④'' 检索内部三个兄弟节点：通道召回 / 融合 / 裁剪都挂在同一层 ──
        for (String child : List.of(AiSpans.DB_VECTOR_SEARCH, AiSpans.RAG_HYBRID,
                AiSpans.RAG_CONTEXT_BUILD)) {
            assertThat(rec.only(child).parent)
                    .as("「" + child + "」应当是 ai.rag.retrieve 的子节点（与其它两步**并列**）")
                    .isEqualTo(AiSpans.RAG_RETRIEVE);
        }

        // ── ⑤ 通用不变量：除根以外**每个 Span 都必须有父**，且父必须在本次记录里 ──
        assertNoOrphanSpans(rec);

        // ── ⑥ 规格书 §7.2 里这段链路应当出现的节点，一个都不许少 ──
        assertThat(rec.names()).contains(AiSpans.AGENT_RUN, AiSpans.INTENT_ROUTE,
                AiSpans.STRUCTURED_QUERY, AiSpans.TOOL_PARAM_EXTRACT, AiSpans.TOOL_PARAM_VALIDATE,
                AiSpans.FEE_CALCULATE, AiSpans.RAG_RETRIEVE, AiSpans.DB_VECTOR_SEARCH,
                AiSpans.RAG_HYBRID, AiSpans.RAG_CONTEXT_BUILD, AiSpans.LLM_GENERATE);

        // ── ⑦ 根上的两个 §7.3 属性：通道 + 是否回退 ──
        assertThat(root.attr(AiSpans.KEY_INTENT_TYPE))
                .as("根 Span 必须记 ai.intent.type（否则「各通道各多少题」没有可聚合的数据）")
                .isEqualTo(RouteDecision.CHANNEL_COMPOSITE);
        assertThat(root.attrs)
                .as("本跑没有发生 TOOL_NONE 回退 ⇒ **不得**写 ai.rag.fallback"
                        + "（只在真的回退时写：写了 false 会让这个键变成三值）")
                .doesNotContainKey(AiSpans.KEY_RAG_FALLBACK);

        // ── ⑧ 各层自己的属性抽查（每层一条，防止"埋了但什么都没记"） ──
        assertThat(rec.only(AiSpans.INTENT_ROUTE).attr(AiSpans.KEY_CONFIG_VERSION))
                .as("路由 Span 要记命中规则（高基数 ai.config.version）")
                .isEqualTo(IntentRouter.RULE_R3_COMPOSITE);
        assertThat(rec.only(AiSpans.INTENT_ROUTE).attr(AiSpans.KEY_INTENT_TYPE))
                .isEqualTo(RouteDecision.CHANNEL_COMPOSITE);

        Recorder.Span retrieve = rec.only(AiSpans.RAG_RETRIEVE);
        assertThat(retrieve.attr(AiSpans.KEY_RAG_STRATEGY))
                .as("单通道 ⇒ 策略名取通道名（能进 Metrics 的封闭取值）")
                .isEqualTo(VectorRetrievalChannel.NAME);
        assertThat(retrieve.attr(AiSpans.KEY_RAG_RESULT)).isEqualTo(AiSpans.VALUE_RESULT_HIT);
        assertThat(retrieve.attr(AiSpans.KEY_RAG_RESULT_COUNT)).isEqualTo("2");
        assertThat(retrieve.attr(AiSpans.KEY_KNOWLEDGE_BASE_ID))
                .as("规格书 §7.3 点名的 knowledge_base_id（高基数、只进 Trace）。"
                        + "取到 null 就说明这个键挂在白名单里却没人写——那正是 C-59 要修的死键")
                .isEqualTo(RetrievalPipeline.KNOWLEDGE_BASE_ID);

        Recorder.Span contextBuild = rec.only(AiSpans.RAG_CONTEXT_BUILD);
        assertThat(contextBuild.attr(AiSpans.KEY_RAG_RESULT_COUNT)).isEqualTo("2");
        assertThat(contextBuild.attr(AiSpans.KEY_DOCUMENT_IDS)).isEqualTo("c1,c2");
        assertThat(contextBuild.parent).isEqualTo(AiSpans.RAG_RETRIEVE);

        Recorder.Span extract = rec.only(AiSpans.TOOL_PARAM_EXTRACT);
        assertThat(extract.attr(AiSpans.KEY_ATTEMPT))
                .as("抽取 Span 必须带 attempt（重试时它才是分辨第几次的那把钥匙）")
                .isEqualTo("1");
        Recorder.Span fee = rec.only(AiSpans.FEE_CALCULATE);
        assertThat(fee.attrs)
                .as("费用 Span **刻意不挂** ai.rag.result（C-59，2026-09-22 裁示删除）："
                        + "引擎失败是抛异常、不是返回 null ⇒ 该键恒为 hit，是噪声。"
                        + "⚠️ 断言「不含某键」之前必须先用 only(...) 证明这个 Span 真的跑到了，"
                        + "否则「键不存在」可能只是环节没执行，那样这条断言是假通过")
                .doesNotContainKey(AiSpans.KEY_RAG_RESULT);

        Recorder.Span generate = rec.only(AiSpans.LLM_GENERATE);
        assertThat(generate.attr(AiSpans.KEY_CONFIG_VERSION))
                .as("作答那次模型调用要记提示词版本（v1/v2 是 C-51 的自变量）")
                .isEqualTo(PromptVersions.V1);
        assertThat(generate.attr(AiSpans.KEY_ATTEMPT))
                .as("attempt=1 ⇒ 本次作答没有触发空正文重试")
                .isEqualTo("1");
        assertThat(generate.parent)
                .as("作答的模型调用应当挂在根之下（作答在两步通道都跑完之后）")
                .isEqualTo(AiSpans.AGENT_RUN);

        // 业务结果照旧（埋点不得改变业务）：复合题两步都跑了
        assertThat(answer.tool()).isNotNull();
        assertThat(answer.contextChunkIds()).containsExactly("c1", "c2");
        assertThat(answer.retrievalFallback()).isFalse();
    }

    @Test
    @DisplayName("⚠️ 与规格书图形不符的三处实际结构（如实钉住，⛔ 不假装一致）")
    void documentsTheDeviationsFromTheSpecDrawing() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);
        pipeline(registry, stubInvoker(QUOTE_FEE_JSON)).run(COMPOSITE_Q);

        // 差异 ①：规格书把 ai.fee.calculate 画成根的**直接**子节点，
        //          而真实调用链是 编排层 → 结构化通道 → 服务层 → 引擎。
        assertThat(rec.only(AiSpans.FEE_CALCULATE).parent)
                .as("实际结构与规格书图形不一致：费用计算发生在结构化通道内部，故父是 ai.structured.query。"
                        + "⚠️ 这是**如实记录**，不是「实现对不对」的判据——若要改成规格书那样，"
                        + "就得让编排层自己调引擎（那是改设计，不是改埋点）")
                .isEqualTo(AiSpans.STRUCTURED_QUERY);

        // 差异 ②：规格书把两路检索画在 ai.rag.hybrid **之下**；
        //          而真实编排是"先把各通道都跑完、再融合"⇒ 通道 Span 与融合 Span 是**兄弟**，
        //          都挂在 ai.rag.retrieve 之下。
        assertThat(rec.only(AiSpans.RAG_HYBRID).parent)
                .as("实际结构与规格书图形不一致：融合发生在**所有通道召回之后**，"
                        + "故 ai.rag.hybrid 与通道 Span 同层，⛔ 不是通道的父节点")
                .isEqualTo(AiSpans.RAG_RETRIEVE);
        assertThat(rec.only(AiSpans.DB_VECTOR_SEARCH).parent)
                .as("通道 Span 的父是检索（⛔ 不是融合）")
                .isEqualTo(AiSpans.RAG_RETRIEVE);
        // 融合层确实跑了（不是"埋了个空壳"）：本档单通道 + RRF，策略名取融合名
        assertThat(rec.only(AiSpans.RAG_HYBRID).attr(AiSpans.KEY_RAG_STRATEGY))
                .isEqualTo(RrfFusion.NAME);
        assertThat(rec.only(AiSpans.RAG_HYBRID).attr(AiSpans.KEY_RAG_RESULT_COUNT)).isEqualTo("2");

        // 差异 ③：ai.rag.rerank 在这一档**不存在**——本档 rerankEnabled=false，
        //          编排层刻意绕过 NoopReranker（B1 那个静默错数的修法），
        //          而唯一会埋点的重排器是 LlmReranker（只有真开重排才被调用）。
        assertThat(rec.all(AiSpans.RAG_RERANK))
                .as("无重排档不该出现 ai.rag.rerank：它的宿主是 LlmReranker，"
                        + "⛔ NoopReranker 不埋点（不做事却留一条记录 = 假证据）")
                .isEmpty();

        // 差异 ④：ai.rag.rewrite 不在本链路里（改写只在离线消融 CLI 中存在）⇒ 本次不得出现
        assertThat(rec.all(AiSpans.RAG_REWRITE))
                .as("默认问答链路没有改写环节，⛔ 不得为了凑齐 §7.2 的节点数而造一个空壳 Span")
                .isEmpty();
    }

    // ==================== ② 重试：每次尝试各一个 Span ====================

    @Test
    @DisplayName("★ 抽取重试：每次尝试各一个 ai.tool.param.extract，且 attempt 依次不同")
    void retryProducesOneSpanPerAttempt() {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        // 前两次给非法 JSON（触发"错误反馈后重试"），第三次给合规的 quote_fee
        JsonChatInvoker invoker = sequentialInvoker(NOT_JSON, NOT_JSON, QUOTE_FEE_JSON);
        ToolArgumentExtractor extractor = new ToolArgumentExtractor(CATALOG,
                SchemaGate.of(CATALOG, registry), invoker, ToolArgumentExtractor.DEFAULT_MAX_RETRIES,
                TODAY, registry);

        var outcome = extractor.extract(COMPOSITE_Q);

        List<Recorder.Span> extractSpans = rec.all(AiSpans.TOOL_PARAM_EXTRACT);
        assertThat(extractSpans)
                .as("重试 2 次 ⇒ 一共 3 个抽取 Span。⛔ 只埋一个「总的重试」节点会让"
                        + "「第几次才成功」消失在 Trace 里（知识库第 55/269 行：每次重试新建 Span）")
                .hasSize(3);
        assertThat(extractSpans).extracting(s -> s.attr(AiSpans.KEY_ATTEMPT))
                .as("三次尝试的 attempt 必须依次是 1/2/3——全都写成 1 的话，"
                        + "Trace 上就看不出「这次是重试过的」")
                .containsExactly("1", "2", "3");
        assertThat(outcome.ok()).as("第三次是合规应答 ⇒ 抽取应当成功").isTrue();

        // ⚠️ 三次尝试会各自校一次参数的**形状**（envelope 在前、arguments 在后）
        assertThat(rec.all(AiSpans.TOOL_PARAM_VALIDATE))
                .as("每次尝试都会校验，故校验 Span 不止一个——这是如实记录，⛔ 不是重复埋点")
                .isNotEmpty();
        assertNoOrphanSpansExceptRootIsAbsent(rec);
    }

    // ==================== ③ 回退：只在真的回退时写 ai.rag.fallback ====================

    @Test
    @DisplayName("TOOL_NONE 回退：ai.rag.fallback=true，且 ai.intent.type 记的是**实际**通道")
    void fallbackIsRecorded() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        Recorder rec = new Recorder();
        registry.observationConfig().observationHandler(rec);

        QuestionPipeline.Answer answer = pipeline(registry, stubInvoker(TOOL_NONE_JSON))
                .run(OBJECT_ONLY_Q);

        assertThat(answer.route().channel())
                .as("题面走的是 R4 ⇒ 路由结论是结构化通道")
                .isEqualTo(RouteDecision.CHANNEL_STRUCTURED);
        assertThat(answer.retrievalFallback()).as("TOOL_NONE ⇒ 应当转交检索").isTrue();

        Recorder.Span root = rec.only(AiSpans.AGENT_RUN);
        assertThat(root.attr(AiSpans.KEY_INTENT_TYPE))
                .as("⚠️ 这里必须记**实际支撑答案的通道**（effectiveChannel）而不是路由结论："
                        + "按路由结论聚合会把「回退到检索才答出来的题」算进结构化通道的功劳里")
                .isEqualTo(RouteDecision.CHANNEL_RETRIEVAL);
        assertThat(root.attr(AiSpans.KEY_RAG_FALLBACK)).isEqualTo("true");
        assertThat(rec.only(AiSpans.INTENT_ROUTE).attr(AiSpans.KEY_INTENT_TYPE))
                .as("路由 Span 记的仍然是**路由结论**（结构化）；与根上的 effectiveChannel 不同是**有意的**："
                        + "前者说明规则判定，后者说明答案实际由谁支撑，两者绝不能混成一列")
                .isEqualTo(RouteDecision.CHANNEL_STRUCTURED);
        assertThat(rec.only(AiSpans.RAG_RETRIEVE).parent)
                .as("回退发生在根 Span 的 scope 之内 ⇒ 检索仍然挂在根之下")
                .isEqualTo(AiSpans.AGENT_RUN);
    }

    // ==================== ④ NOOP：不埋点时业务必须一模一样 ====================

    @Test
    @DisplayName("★ 传 ObservationRegistry.NOOP：不抛异常，且业务返回值与埋点时逐字节相同")
    void noopRegistryIsInvisibleToBusiness() throws Exception {
        ObservationRegistry real = ObservationRegistry.create();
        Recorder rec = new Recorder();
        real.observationConfig().observationHandler(rec);

        QuestionPipeline.Answer traced = pipeline(real, stubInvoker(QUOTE_FEE_JSON))
                .run(COMPOSITE_Q);
        QuestionPipeline.Answer untraced = pipeline(ObservationRegistry.NOOP,
                stubInvoker(QUOTE_FEE_JSON)).run(COMPOSITE_Q);

        assertThat(rec.names()).as("真实注册表这一侧确实记到了 Span").isNotEmpty();
        assertThat(untraced.text())
                .as("⛔ 埋点不得改变业务结果：NOOP 档与埋点档的作答必须一字不差")
                .isEqualTo(traced.text());
        assertThat(untraced.tool().facts()).isEqualTo(traced.tool().facts());
        assertThat(untraced.contextChunkIds()).isEqualTo(traced.contextChunkIds());
        assertThat(untraced.channel()).isEqualTo(traced.channel());
        assertThat(untraced.modelCalls()).isEqualTo(traced.modelCalls());
    }

    // ==================== 桩装配（⛔ 不连库、不调模型） ====================

    /**
     * 用桩装出一棵**真实**的编排（与 {@code QuestionPipeline.create} 同一套接线，只是把外部依赖换成桩）。
     *
     * <p>⚠️ 这里必须用真实注册表注入每一层，否则本类测的就成了"桩自己"。
     */
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

        // 与生产装配同档：纯向量 + 无重排（⛔ NoopReranker 不埋点，故它不接注册表）
        RetrievalPipeline retrieval = new RetrievalPipeline(
                List.of(new VectorRetrievalChannel(searcher, registry)),
                new RrfFusion(60, registry), new NoopReranker(),
                new RetrievalConfig(30, 20, 10, 5, 60, false, RrfFusion.NAME, NoopReranker.NAME),
                registry);

        AnswerComposer composer = new AnswerComposer(stubChatModel(), null, Map.of(), registry);
        return new QuestionPipeline(new StubJdbc(), new IntentRouter(invoker, registry), structured,
                retrieval, composer, registry);
    }

    /** 固定应答的桩 invoker（同 {@code StructuredQueryServiceTest.stub}）。 */
    private static JsonChatInvoker stubInvoker(String reply) {
        return (sys, user) -> reply;
    }

    /** 依次吐出的桩 invoker：用于构造"第 N 次才合规"的重试场景。 */
    private static JsonChatInvoker sequentialInvoker(String... replies) {
        List<String> queue = new ArrayList<>(List.of(replies));
        return (sys, user) -> queue.isEmpty() ? replies[replies.length - 1] : queue.remove(0);
    }

    /** 桩 ChatModel：不联网，直接返回一段非空作答（空作答会触发本类的重试分支）。 */
    private static ChatModel stubChatModel() {
        ChatModel model = Mockito.mock(ChatModel.class);
        ChatResponse response = Mockito.mock(ChatResponse.class);
        Generation generation = Mockito.mock(Generation.class);
        Mockito.when(response.getResult()).thenReturn(generation);
        Mockito.when(generation.getOutput()).thenReturn(new AssistantMessage("已按依据作答。（桩）"));
        Mockito.when(model.call(Mockito.any(Prompt.class))).thenReturn(response);
        return model;
    }

    /** 内存费率表：只有"过闸费 / 能源 / 载货吨位"一条，覆盖 1200 吨、2026-10-15 生效。 */
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

    /** 内存数据面：本类只用得到"货类→危险品"这一支，其余一律空（⛔ 不连库）。 */
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

    /** 桩 JdbcTemplate：只服务"取 chunk 正文"这一步（⛔ 不连库）。 */
    private static final class StubJdbc extends JdbcTemplate {

        @Override
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            return List.of(elementType.cast("闸室水深以现场标尺为准。（桩正文）"));
        }
    }

    // ==================== 断言工具 ====================

    /**
     * 捕获每次 Span <b>停止时</b>的名字、父名与全部属性。
     *
     * <p>⚠️ 属性必须在 {@code onStop} 取：{@code observe(...)} 体内的属性是**启动之后**才写的，
     * 在 {@code onStart} 快照只会得到一份空属性表（那样断言会静默失效）。
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
            // ⚠️ 父名要从 getContextView() 取：ObservationView 本身没有 getName()
            spans.add(new Span(context.getName(), context.getParentObservation() == null
                    ? null
                    : context.getParentObservation().getContextView().getName(), attrs));
        }

        List<String> names() {
            return spans.stream().map(s -> s.name).toList();
        }

        List<Span> all(String name) {
            return spans.stream().filter(s -> s.name.equals(name)).toList();
        }

        Span only(String name) {
            List<Span> got = all(name);
            assertThat(got)
                    .as("本次执行里 Span「" + name + "」应当恰好出现 1 次（实际 " + got.size()
                            + " 次）。出现 0 次 = 该环节没埋上或被短路；多次 = 同一环节被重复埋了")
                    .hasSize(1);
            return got.get(0);
        }

        static final class Span {
            final String name;
            final String parent;
            final Map<String, String> attrs;

            Span(String name, String parent, Map<String, String> attrs) {
                this.name = name;
                this.parent = parent;
                this.attrs = attrs;
            }

            String attr(String key) {
                return attrs.get(key);
            }
        }
    }

    /** 除根以外**每个 Span 都必须有父**，且父必须也在本次记录里（父指针静默丢失的正面判据）。 */
    private static void assertNoOrphanSpans(Recorder rec) {
        for (Recorder.Span s : rec.spans) {
            if (AiSpans.AGENT_RUN.equals(s.name)) {
                assertThat(s.parent).as("根 Span 的父必须是 null").isNull();
                continue;
            }
            assertThat(s.parent)
                    .as("Span「" + s.name + "」的父指针丢了（null）。这正是"
                            + "《观测埋点的父指针会静默丢失》记的那个坑："
                            + "对子 Span 用了手工 start/stop，或者中途换了另一个 ObservationRegistry")
                    .isNotNull();
            assertThat(rec.names())
                    .as("Span「" + s.name + "」的父「" + s.parent + "」不在本次记录里——"
                            + "说明父子来自不同的注册表（两棵断开的树）")
                    .contains(s.parent);
        }
    }

    /** 抽取器是**被直接调用**的（外面没有根 Span）⇒ 这里只要求"有父的都有父、没父的都是树根"。 */
    private static void assertNoOrphanSpansExceptRootIsAbsent(Recorder rec) {
        assertThat(rec.all(AiSpans.AGENT_RUN))
                .as("本测试直接调抽取器，没有编排根 Span")
                .isEmpty();
        for (Recorder.Span s : rec.all(AiSpans.TOOL_PARAM_VALIDATE)) {
            assertThat(s.parent).isEqualTo(AiSpans.TOOL_PARAM_EXTRACT);
        }
    }
}
