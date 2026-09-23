package com.canalmind.chat.frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.canalmind.chat.ChatFrameSource;
import com.canalmind.intent.IntentRouter;
import com.canalmind.intent.RouteDecision;
import com.canalmind.rag.embed.EmbeddingClientFactory;
import com.canalmind.rag.ingest.RagChunkRepository;
import com.canalmind.rag.retrieve.NoopReranker;
import com.canalmind.rag.retrieve.RetrievalCandidate;
import com.canalmind.rag.retrieve.RetrievalConfig;
import com.canalmind.rag.retrieve.RetrievalPipeline;
import com.canalmind.rag.retrieve.RetrievalResult;
import com.canalmind.rag.retrieve.RrfFusion;
import com.canalmind.rag.retrieve.VectorRetrievalChannel;
import com.canalmind.rag.vector.VectorSearcher;
import com.canalmind.structured.DeepSeekJsonInvoker;
import com.canalmind.structured.FeeCalculationEngine;
import com.canalmind.structured.SchemaGate;
import com.canalmind.structured.StructuredQueryRepository;
import com.canalmind.structured.StructuredQueryService;
import com.canalmind.structured.TariffRuleRepository;
import com.canalmind.structured.ToolArgumentExtractor;
import com.canalmind.structured.ToolCatalog;
import com.canalmind.structured.ToolResult;

/**
 * 结构化帧的装配器：**路由 → 通道 → 引用 → 单行 JSON**。
 *
 * <h2>它做什么、⛔ 不做什么（这一段最重要，别读错）</h2>
 * <p>它**只**产出随 SSE 一起下发的 {@code event: structured} 帧：
 * 路由结论、结构化通道的五态结果、以及检索通道命中的引用来源。
 * <p>它<b>不生成答案正文</b>。正文仍由 {@code ChatStreamController} 里那次
 * 既有的 {@code chatClient} 流式调用产出，<b>一个字都没改</b>。
 * <p>⚠️⚠️ 这带来一个必须说清的边界：**帧里的确定性事实目前不会进入正文的提示词**。
 * 也就是说，模型回答正文时看不到这里的金额与依据。
 * ⛔ 这不是遗漏，而是**刻意的**：答案路径（系统提示词 + 生成调用）已被决策 C-51
 * **冻结**——L3/L5/L6 的既有出分产物正是那条路径的产物，动它会让分层数字与基线不可比
 * （本项目纪律："每轮只改少量变量"）。把事实喂进生成提示词属于一次**新的答案路径版本**，
 * 该由用户按 A 类流程另行决定。
 * ⇒ 因此本帧的定位是**旁路的确定性事实与引用**，前端应把它当作"系统这一路查到了什么"的
 * 如实展示，而不是"正文凭什么这么说"的证明。
 *
 * <h2>为什么默认关（{@code canalmind.chat.structured-frame=on} 才生效）</h2>
 * 三条理由，任一条都足以让它默认关：
 * <ol>
 *   <li><b>既有行为零变化</b>：不开启时容器里根本没有这个 Bean，
 *       SSE 出站事件集合与引入本功能之前**逐字节一致**，
 *       既有协议测试（{@code ChatStreamControllerTest} 的四条断言）无需为它改动一个字；</li>
 *   <li><b>不偷偷花钱</b>：路由的规则未命中时会走分类器、结构化通道会做参数抽取，
 *       两者都是**付费模型调用**。默认关 ⇒ 不请求就不产生费用；</li>
 *   <li><b>不引进启动依赖</b>：装配要连库与 Ollama。默认关 ⇒
 *       没起这两样时应用照常启动、问答照常可用（只是没有帧）。</li>
 * </ol>
 *
 * <h2>失败软着陆（为什么不抛）</h2>
 * 帧是**增强信息**，不是问答的必要条件。任何一步失败（库没起、Ollama 没起、
 * 模型不可用、没有向量档案）都只让**这一帧消失**，绝不能让整次问答变成 error 事件——
 * 否则"锦上添花"的东西会把主链路一起拖垮。故全部收敛为返回 {@code Optional.empty()}，
 * 并且**只在第一次失败时打一条 WARN**（避免每个请求刷一屏同样的堆栈）。
 */
@Component
@ConditionalOnProperty(name = ChatFrameAssembler.ENABLED_PROPERTY, havingValue = "on")
public class ChatFrameAssembler implements ChatFrameSource {

    /** 开启开关。默认关，理由见类注释。 */
    public static final String ENABLED_PROPERTY = "canalmind.chat.structured-frame";

    private static final Logger log = LoggerFactory.getLogger(ChatFrameAssembler.class);

    /** 引用最多带几条：与检索段的 `contextTopN` 同档，⛔ 不要把整个候选集都塞给前端。 */
    private static final int CITATION_LIMIT = 5;

    /**
     * 业务当天（供参数抽取理解"本月""上季度"这类相对时间）。
     *
     * <p>⚠️ 默认值 {@code 2027-09-15} 是**业务数据窗口的末日**
     * （{@code StructuredQueryRepository.BUSINESS_START ~ BUSINESS_END} = 2026-09-16 ~ 2027-09-15，
     * 即通航后一年）。⛔ 这个日期**不是随手挑的**：抽取器拿它当"今天"，
     * 换一个值就会让同一句"本月的过闸费"抽出不同的日期区间。
     * ⚠️ 如实记一笔：同一常量在 {@code StructuredQueryCli} / {@code L4RunCli} /
     * {@code LayerRunCli} 里各写了一遍（三处目前值相同）。
     * 本类不再抄第四份字面量，改为可配置 + 同一个默认值；
     * 把三处合并成一处属独立清理，⛔ 不在本次改动范围内悄悄做。
     */
    static final String DEFAULT_BUSINESS_TODAY = "2027-09-15";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<ChatModel> chatModels;
    private final Environment environment;
    private final ObservationRegistry observationRegistry;
    private final String businessToday;

    /** 懒装配的三件套；装配成功后才缓存（失败不缓存，下次请求会重试）。 */
    private volatile Components components;

    /** 失败只报一次，避免日志刷屏（同时提醒使用者"帧一直是空的"）。 */
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public ChatFrameAssembler(JdbcTemplate jdbc,
                              ObjectProvider<ChatModel> chatModels,
                              Environment environment,
                              ObservationRegistry observationRegistry,
                              @Value("${canalmind.business-today:" + DEFAULT_BUSINESS_TODAY + "}")
                              String businessToday) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.chatModels = Objects.requireNonNull(chatModels, "chatModels");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
        this.businessToday = businessToday;
        // ⚠️ 构造器里刻意**不碰**数据库、不碰模型：Spring 上下文在测试里要能
        //    在没有 PG / 没有 Ollama 的机器上装配起来（既有 @SpringBootTest 就是这种环境）。
        //    所有 I/O 都推迟到第一次提问。
    }

    @Override
    public Optional<String> frameFor(String question, String sessionId) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(build(question, sessionId));
        } catch (Exception ex) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("结构化帧装配失败，本次及后续请求将不带 structured 事件"
                        + "（这是增强信息，不影响问答本身）。如需排查，请确认 PG / Ollama 可用，"
                        + "并检查 canalmind.chat.structured-frame 与向量索引档案。原因：{}",
                        ex.toString());
                log.debug("结构化帧失败明细", ex);
            }
            return Optional.empty();
        }
    }

    private String build(String question, String sessionId) {
        Components c = components();
        RouteDecision route = c.router().route(question);

        ToolResult tool = null;
        List<String> chunkIds = List.of();

        if (RouteDecision.CHANNEL_STRUCTURED.equals(route.channel())) {
            tool = c.structured().ask(question);
        } else if (RouteDecision.CHANNEL_RETRIEVAL.equals(route.channel())) {
            chunkIds = retrieve(c, question);
        } else {
            // ── 复合：与 QuestionPipeline 同一约定——**串行**两步，⛔ 不并行（C-37）──
            tool = c.structured().ask(question);
            chunkIds = retrieve(c, question);
        }

        List<FrameCitation> citations = citations(chunkIds);
        if (tool == null && citations.isEmpty()) {
            // 两条通道都没给出任何可展示的东西 ⇒ 不发帧。
            // 前端据此"没有帧就是没有帧"，而不是收到一个空壳再自己判空。
            return null;
        }
        return ChatFrameCodec.encode(sessionId, route, tool, citations);
    }

    /** 检索通道：**只要 chunkId**，正文与元数据稍后按 id 回库取（与 QuestionPipeline 同做法）。 */
    private List<String> retrieve(Components c, String question) {
        RetrievalResult result = c.retrieval().retrieve(question);
        List<String> ids = new ArrayList<>();
        for (RetrievalCandidate candidate : result.context()) {
            String id = candidate.chunkId();
            if (id != null && !ids.contains(id) && ids.size() < CITATION_LIMIT) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * 按 id 回库取引用元数据，并**保持检索名次**。
     *
     * <p>⚠️ 为什么必须重排：{@code IN (...)} 的返回顺序由数据库决定、**不保证**与列表一致。
     * 直接照返回顺序渲染，引用编号就会和检索名次对不上——
     * 而"第 1 条引用是不是最相关的那条"正是引用区唯一有用的信息。
     * 拿不到元数据的 id **静默跳过**（它在库里可能已被重建），
     * ⛔ 不生成占位引用——引用的价值就在于可核对，占位项是假证据。
     */
    private List<FrameCitation> citations(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        // ⚠️ 只拼占位符个数，**值一律走参数绑定**；⛔ 永不把 id 字面量拼进 SQL。
        String sql = "SELECT chunk_id, doc_title, doc_no, article_no, article_title,"
                + " chapter_path, part_index, part_count, content"
                + " FROM rag_chunk WHERE chunk_id IN (" + placeholders + ")";

        Map<String, FrameCitation> byId = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String[] path = new String[0];
            java.sql.Array arr = rs.getArray("chapter_path");
            if (arr != null) {
                Object raw = arr.getArray();
                if (raw instanceof Object[] objs) {
                    path = new String[objs.length];
                    for (int i = 0; i < objs.length; i++) {
                        path[i] = objs[i] == null ? "" : objs[i].toString();
                    }
                }
                // ⚠️ 用完即释放：PG 的数组句柄与结果集同生命周期，
                //    不释放时连接池在高并发下会先耗尽服务端游标。
                arr.free();
            }
            FrameCitation citation = new FrameCitation(
                    rs.getString("chunk_id"),
                    rs.getString("doc_title"),
                    rs.getString("doc_no"),
                    rs.getString("article_no"),
                    rs.getString("article_title"),
                    List.of(path),
                    rs.getInt("part_index"),
                    rs.getInt("part_count"),
                    ChatFrameCodec.excerpt(rs.getString("content")));
            byId.put(citation.chunkId(), citation);
        }, chunkIds.toArray());

        List<FrameCitation> ordered = new ArrayList<>();
        for (String id : chunkIds) {
            FrameCitation found = byId.get(id);
            if (found != null) {
                ordered.add(found);
            }
        }
        return ordered;
    }

    /** 双检锁的懒装配；装配失败会抛出（由 {@link #frameFor} 收敛成"没有帧"）。 */
    private Components components() {
        Components cached = components;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (components == null) {
                components = buildComponents();
            }
            return components;
        }
    }

    /**
     * 装配三件套。**装配顺序与 {@code QuestionPipeline.create} 保持一致**，
     * 用的是同一批类、同一批参数（向量档案从库里探、⛔ 不猜），
     * 目的是让"网页上看到的"与"评测跑出来的"走同一套口径。
     */
    private Components buildComponents() {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException("上下文里没有 ChatModel，结构化帧无法装配");
        }

        ToolCatalog catalog = ToolCatalog.loadDefault();
        DeepSeekJsonInvoker invoker = new DeepSeekJsonInvoker(chatModel, 2048, observationRegistry);
        ToolArgumentExtractor extractor = new ToolArgumentExtractor(
                catalog, SchemaGate.of(catalog, observationRegistry), invoker,
                ToolArgumentExtractor.DEFAULT_MAX_RETRIES, businessToday, observationRegistry);
        StructuredQueryService structured = new StructuredQueryService(
                catalog, extractor,
                new TariffRuleRepository(jdbc, observationRegistry),
                new StructuredQueryRepository(jdbc, observationRegistry),
                new FeeCalculationEngine(observationRegistry), businessToday, observationRegistry);

        String vectorProfile = new RagChunkRepository(jdbc).selectProfileName();
        if (vectorProfile == null) {
            throw new IllegalStateException(
                    "库里没有任何向量索引档案 ⇒ 检索通道无法装配（引用来源取不到）。"
                            + "请先运行 PgVectorIngestCli 完成切分 + embedding + 入库。");
        }
        VectorSearcher searcher = new VectorSearcher(jdbc,
                EmbeddingClientFactory.fromEnvironment(envMap()), vectorProfile);
        // ⚠️ 与 QuestionPipeline 同一取向：档案不一致时**不就地降级**——
        //    静默降级会把"索引格式不对"记成"检索效果差"。
        //    ⚠️ 差异只在这里：QuestionPipeline 把该受检异常继续上抛给 CLI
        //    （调用方必须处理），而本类是 Spring Bean、出口是"有没有帧"，
        //    故就地包成 IllegalStateException ⇒ 由 frameFor 统一收敛为"这次没有帧"。
        //    ⛔ 仍然**不吞掉原因**：cause 原样带上，日志里查得到是哪个模型对不上。
        try {
            searcher.ensureProfileMatches();
        } catch (com.canalmind.rag.embed.EmbeddingModelGuard.EmbeddingModelMismatchException e) {
            throw new IllegalStateException(
                    "查询侧嵌入模型与库内索引档案不一致 ⇒ 检索通道不可用（引用来源取不到）。" + e.getMessage(), e);
        }
        RetrievalConfig config = RetrievalConfig.defaults().withoutRerank();
        RetrievalPipeline retrieval = new RetrievalPipeline(
                List.of(new VectorRetrievalChannel(searcher, observationRegistry)),
                new RrfFusion(config.rrfK(), observationRegistry), new NoopReranker(), config,
                observationRegistry);

        return new Components(new IntentRouter(invoker, observationRegistry), structured, retrieval);
    }

    /**
     * 把 Spring 环境映射成 {@code EmbeddingClientFactory} 读的那三个键。
     *
     * <p>⚠️ 两个来源都要看：环境变量（{@code .env} 经 {@code spring.config.import} 导入后
     * 也在 Environment 里）与 {@code spring.ai.ollama.*} 配置项。两处口径一致时以环境变量为准
     * （{@code value(...)} 的顺序即优先级），⛔ 缺省值不在这里补——
     * 补缺省值的事归 {@code EmbeddingClientFactory}（它才有 C-22 的默认值，避免两处各写一份）。
     */
    private Map<String, String> envMap() {
        Map<String, String> env = new HashMap<>();
        putFirst(env, "OLLAMA_BASE_URL", "OLLAMA_BASE_URL", "spring.ai.ollama.base-url");
        putFirst(env, "OLLAMA_EMBEDDING_MODEL", "OLLAMA_EMBEDDING_MODEL",
                "spring.ai.ollama.embedding.model");
        return env;
    }

    private void putFirst(Map<String, String> target, String key, String... propertyNames) {
        for (String name : propertyNames) {
            String v = environment.getProperty(name);
            if (v != null && !v.isBlank()) {
                target.put(key, v.trim());
                return;
            }
        }
    }

    /** 一次装配出的三件套（装配成功才存在，见 {@link #components()}）。 */
    private record Components(IntentRouter router, StructuredQueryService structured,
                              RetrievalPipeline retrieval) {
    }
}
