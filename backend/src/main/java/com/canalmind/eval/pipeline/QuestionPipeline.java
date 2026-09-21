package com.canalmind.eval.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import com.canalmind.intent.IntentRouter;
import com.canalmind.intent.RouteDecision;
import com.canalmind.rag.embed.EmbeddingClientFactory;
import com.canalmind.rag.embed.EmbeddingModelGuard;
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
 * 端到端问答编排层：**路由 → 通道 → 作答**（第 4 周 L3/L5/L6 出分的前置）。
 *
 * <h2>为什么必须补这一层</h2>
 * 第 3 周已有的两条链路都是**分段**的：`StructuredQueryCli` 只会结构化通道，
 * `RetrievalQueryCli` 只会检索通道。而 L3/L5/L6 是**行为口径**——
 * 它们问的是"**这个助手**遇到这个问题会怎么答"，所以必须先有一个"按问题选通道"的入口。
 * 缺这一层时 L3–L6 拿不到分数（《评测报告》§5 记录的就是这个缺口）。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>路由在调用之前定好，并随答案一起记下</b>（C-37⑤）：`routeReason` 要写进
 *       `EvalRecord.route_reason`，否则"拆错步"与"答错"无法分开归因。</li>
 *   <li><b>复合题串行、⛔ 不并行</b>（C-37）：两步之间可能有依赖，并行只会多引入失败模式。</li>
 *   <li><b>金额段与依据段分开</b>（C-37⑤）：金额来自结构化通道的**数据库 oracle**，
 *       依据来自检索段；这样金额对错是**客观**的，不受 Judge 主观性影响。</li>
 * </ol>
 *
 * <h2>⚠️ 本类不做判分</h2>
 * 只产出「答案 + 路由 + 工具结果 + 检索片段 id」。判分由各层自己的判分器做
 * （L1 oracle / L2 规则 / L3·L5·L6 Judge / L4 规则）。
 * 好处：**同一份作答可以换判分器复算，不必重新花钱调用模型**。
 */
public final class QuestionPipeline {

    /** 检索段给作答用的上下文条数（与 `RetrievalConfig.defaults()` 的 contextTopN 一致）。 */
    private static final int CONTEXT_TOP_N = 5;

    private final JdbcTemplate jdbc;
    private final IntentRouter router;
    private final StructuredQueryService structured;
    private final RetrievalPipeline retrieval;
    private final AnswerComposer composer;

    QuestionPipeline(JdbcTemplate jdbc, IntentRouter router, StructuredQueryService structured,
                     RetrievalPipeline retrieval, AnswerComposer composer) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.router = Objects.requireNonNull(router, "router");
        this.structured = Objects.requireNonNull(structured, "structured");
        this.retrieval = Objects.requireNonNull(retrieval, "retrieval");
        this.composer = Objects.requireNonNull(composer, "composer");
    }

    /**
     * 按环境装配真实的编排层（向量基线档：纯向量 + Top-30 + **无重排**）。
     *
     * <p>⚠️ 取"无重排"档，是为了让 L3/L5/L6 的分层数字与 §2 里已验证的检索基线**同一档**；
     * 带重排的档留作第 5 项消融实验的对照臂，⛔ **不要把两档混在一张表里比**。
     *
     * @throws EmbeddingModelGuard.EmbeddingModelMismatchException 查询侧嵌入模型与索引档案不一致。
     *         ⚠️ **刻意向上抛而不是就地降级**：档案不一致时算出来的相似度**没有任何意义**，
     *         静默降级会把"索引格式不对"记成"检索效果差"（本项目 检索工程 类已记录过同族教训）。
     */
    public static QuestionPipeline create(JdbcTemplate jdbc, Map<String, String> env,
                                          ChatModel chatModel, String businessToday)
            throws EmbeddingModelGuard.EmbeddingModelMismatchException {
        // ── 检索通道：档案名从库里探，探不到就报错（⛔ 不猜、不降级）──
        String vectorProfile = new RagChunkRepository(jdbc).selectProfileName();
        if (vectorProfile == null) {
            throw new IllegalStateException(
                    "库里没有任何向量索引档案。请先运行 PgVectorIngestCli 完成切分+embedding+入库。");
        }
        VectorSearcher searcher = new VectorSearcher(jdbc, EmbeddingClientFactory.fromEnvironment(env),
                vectorProfile);
        searcher.ensureProfileMatches();
        RetrievalConfig config = RetrievalConfig.defaults().withoutRerank();
        RetrievalPipeline pipeline = new RetrievalPipeline(
                List.of(new VectorRetrievalChannel(searcher)),
                new RrfFusion(config.rrfK()), new NoopReranker(), config);

        // ── 结构化通道：与 StructuredQueryCli 同一套装配 ──
        // 抽取器与路由分类器**共用一个 invoker**：它们调的是同一个模型，
        // 各自再造一个只会让用法与重试预算分叉。
        ToolCatalog catalog = ToolCatalog.loadDefault();
        DeepSeekJsonInvoker invoker = new DeepSeekJsonInvoker(chatModel, 2048);
        ToolArgumentExtractor extractor = new ToolArgumentExtractor(catalog, SchemaGate.of(catalog),
                invoker, ToolArgumentExtractor.DEFAULT_MAX_RETRIES, businessToday);
        StructuredQueryService service = new StructuredQueryService(catalog, extractor,
                new TariffRuleRepository(jdbc), new StructuredQueryRepository(jdbc),
                new FeeCalculationEngine(), businessToday);

        return new QuestionPipeline(jdbc, new IntentRouter(invoker), service, pipeline,
                new AnswerComposer(chatModel));
    }

    /** 一次作答的完整现场。 */
    public record Answer(String question, RouteDecision route, String text, ToolResult tool,
                         List<String> contextChunkIds, String contextExcerpt, int modelCalls) {
        public Answer {
            contextChunkIds = contextChunkIds == null ? List.of() : List.copyOf(contextChunkIds);
        }

        /** 主通道，供分层统计用。 */
        public String channel() {
            return route.channel();
        }

        /** 结构化通道的状态（非结构化通道为 {@code null}），供 L6 判分用。 */
        public String toolStatus() {
            return tool == null ? null : tool.status();
        }
    }

    public Answer run(String question) {
        RouteDecision route = router.route(question);
        List<String> chunkIds = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        ToolResult tool = null;

        if (RouteDecision.CHANNEL_STRUCTURED.equals(route.channel())) {
            tool = structured.ask(question);
        } else if (RouteDecision.CHANNEL_RETRIEVAL.equals(route.channel())) {
            collect(retrieval.retrieve(question), chunkIds, context);
        } else {
            // ── 复合：**串行**跑两步（C-37），⛔ 不并行 ──
            tool = structured.ask(question);
            collect(retrieval.retrieve(question), chunkIds, context);
        }

        // 结构化通道**已含**数据库算出的答案时，仍让模型组织语言：
        // L3/L6 的 rubric 判的是「措辞是否如实」（如"必须指出「米」是长度单位"），
        // ⛔ 不是判"有没有调对工具"——那由 L1/L4 的规则判分负责。
        String text = composer.compose(question, route, tool, context.toString());
        // ⚠️ 模型调用数**如实计数**（1 = 本次组织语言的调用）；抽取/分类器的内部调用
        //    由它们各自的重试预算决定，不计在此处，⛔ 别把两者混成一个数。
        return new Answer(question, route, text, tool, chunkIds, context.toString(), 1);
    }

    /**
     * 取上下文片段。
     *
     * <p>⚠️ `RetrievalCandidate` **只有 id 与分数、没有正文**，正文必须回库取
     * （`rag_chunk.content`）。这不是绕路：检索结果与正文分属两处是既有设计，
     * 在这里补一次查询比让检索层多带一份正文更不容易把两处口径改岔。
     */
    private void collect(RetrievalResult r, List<String> chunkIds, StringBuilder context) {
        if (r == null || r.context() == null) {
            return;
        }
        int n = 0;
        for (RetrievalCandidate c : r.context()) {
            if (n >= CONTEXT_TOP_N) {
                break;
            }
            String id = c.chunkId();
            if (id == null || chunkIds.contains(id)) {
                continue;
            }
            chunkIds.add(id);
            context.append("[").append(n + 1).append("] ").append(contentOf(id)).append((char) 10);
            n++;
        }
    }

    /** 取 chunk 正文；查不到返回空串并**保留占位**，⛔ 不静默丢弃（否则上下文会静默变短）。 */
    String contentOf(String chunkId) {
        List<String> got = jdbc.queryForList("SELECT content FROM rag_chunk WHERE chunk_id = ?",
                String.class, chunkId);
        return got.isEmpty() || got.get(0) == null ? "" : got.get(0);
    }
}
