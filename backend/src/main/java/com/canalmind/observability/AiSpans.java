package com.canalmind.observability;

/**
 * 业务 Span 与属性键的<b>唯一定义处</b>（规格书 §7.2，第 543–564 行）。
 *
 * <p><b>为什么这些字符串必须集中在一个类里，而不是散在各埋点处</b>：知识库
 * 《AI 可观测性与 Trace…》第 451 行要求「统一错误类型和标签命名」、第 459 行要求
 * 「先统一项目的埋点入口」。名字一旦散写，同一个环节就会出现
 * {@code ai.rag.retrieve} 与 {@code rag.retrieve} 两种拼法——**两边都合法、都不报错**，
 * 而聚合时它们就是两个不同的操作，于是"检索慢"这个结论永远拼不出全量。
 *
 * <p>✅ <b>可被证伪</b>：{@code AiSpansTest} 断言本类的名字集合与规格书 §7.2 的清单
 * <b>逐字一致</b>（含顺序），所以"规格书说要埋 16 个、代码里只定义了 N 个"这类偏差
 * 会当场红，而不是靠人读文档发现。
 *
 * <h2>命名纪律（知识库第 91、105、113 行）</h2>
 * <ul>
 *   <li><b>稳定、低基数</b>：⛔ 名字里不得出现用户问题、单号、完整 URL——
 *       否则 Span 名称的数量会随用户输入无界增长（第 105 行）。</li>
 *   <li><b>通用约定优先，再补自有命名空间</b>（第 113 行）：OTel 已定义的
 *       {@code gen_ai.*} / {@code invoke_agent} / {@code execute_tool} 优先复用；
 *       本项目自己的业务阶段用 {@code ai.*} 命名空间。</li>
 *   <li><b>约定版本要钉在埋点库里</b>（第 113 行）——见 {@link #SEMANTIC_CONVENTION_VERSION}。</li>
 * </ul>
 *
 * <h2>⚠️ 基数（cardinality）纪律（规格书 §7.3，第 566–573 行；知识库第 202、451 行）</h2>
 * <p>知识库第 202 行：<i>"低基数键会进入 Metrics 和 Trace，高基数键只进入 Trace"</i>，
 * 并要求自补埋点<b>保持同一边界</b>。故本类把两类键<b>刻意分成两组常量</b>，
 * 让"这个键该进哪一层"在调用点一眼可见：
 * <table border="1">
 *   <caption>两类键的边界</caption>
 *   <tr><th>类别</th><th>键</th><th>去向</th></tr>
 *   <tr><td rowspan="3">低基数（{@link #LOW_CARDINALITY_KEYS}）</td>
 *       <td>{@code ai.rag.strategy}、{@code ai.rag.result}</td>
 *       <td>Metrics + Trace</td></tr>
 *   <tr><td>{@code ai.intent.type}</td><td>Metrics + Trace</td></tr>
 *   <tr><td colspan="2">取值必须是<b>封闭枚举</b>（如 {@code hit}/{@code empty}）</td></tr>
 *   <tr><td rowspan="3">高基数（{@link #HIGH_CARDINALITY_KEYS}）</td>
 *       <td>{@code ai.rag.result_count}、文档 ID、{@code knowledge_base_id}</td>
 *       <td>只进 Trace</td></tr>
 *   <tr><td>{@code attempt}（重试次数）</td><td>只进 Trace</td></tr>
 *   <tr><td>{@code db.rows}（SQL 行数）</td><td>只进 Trace</td></tr>
 * </table>
 *
 * <p>⛔⛔ <b>绝对不允许进属性的一类东西</b>（知识库第 200、451、531–533 行）：
 * <b>原始问题文本、文档正文、Prompt / Completion 原文、工具参数原文</b>。
 * 它们不只是高基数，还<b>可能很长并含敏感信息</b>（第 200 行）；
 * 第 531–533 行把这个做法列为"常见错误：把用户输入放进指标标签"。
 * 需要留痕时改为<b>哈希或受控枚举</b>（第 105、200 行）。
 *
 * <p>⚠️ {@code knowledge_base_id} 在本项目里是否真属高基数**如实记一笔**：本机只有一个
 * 知识库（语料 28 篇 → 541 个 chunk），所以它当下<b>基数并不高</b>；仍按规格书 §7.3
 * 归入高基数一侧，是因为它<b>随知识库数量增长</b>，且本项目的对外形态是多知识库。
 */
public final class AiSpans {

    private AiSpans() {
    }

    /**
     * 语义约定版本（知识库第 113 行要求"把约定版本写进埋点库，升级时集中适配"）。
     *
     * <p>⚠️ 知识库第 111 行明确：OTel 的 GenAI 与 Agent Span 语义约定<b>仍标记为
     * {@code Development}</b>，"属性和命名还有调整的可能"。所以这里记的不是"我们遵照了
     * 某个稳定标准"，而是<b>我们在哪一天、按哪一版约定埋的点</b>——将来约定变更时，
     * 靠它能判断"哪些 Span 需要跟着改"。
     */
    public static final String SEMANTIC_CONVENTION_VERSION =
            "canalmind-spans/v1@2026-09-22（对齐规格书 §7.2；OTel GenAI/Agent 约定仍为 Development）";

    // ==================== Span 名（规格书 §7.2，第 548–563 行，顺序照抄） ====================

    /** 根 Span：一次完整的 Agent 执行（知识库第 438 行列为"必须自己补"的第 ① 项）。 */
    public static final String AGENT_RUN = "ai.agent.run";
    /** 意图路由；属性里要能看出"凭什么这么判"（规格书第 549 行：记录 {@code route_reason}）。 */
    public static final String INTENT_ROUTE = "ai.intent.route";
    /** 结构化通道本体。 */
    public static final String STRUCTURED_QUERY = "ai.structured.query";
    /**
     * 参数抽取。
     *
     * <p>⚠️ 知识库第 55、269 行：<b>每次重试要新建 Span 并带 {@code attempt}</b>，
     * 否则"第一次超时和第二次成功会混在同一条记录里"。本项目的抽取器有
     * {@code DEFAULT_MAX_RETRIES} 次带错误反馈的重试，故本 Span 是<b>逐次尝试</b>的。
     */
    public static final String TOOL_PARAM_EXTRACT = "ai.tool.param.extract";
    /** 参数校验（服务端 JSON Schema 闸门，决策 C-32）。 */
    public static final String TOOL_PARAM_VALIDATE = "ai.tool.param.validate";
    /** SQL 执行（规格书第 553 行：记录耗时、行数）。 */
    public static final String DB_SQL_EXECUTE = "db.sql.execute";
    /** 文档通道本体（知识库第 443 行列为"必须自己补"的第 ② 项之一）。 */
    public static final String RAG_RETRIEVE = "ai.rag.retrieve";
    /** Query Rewrite。 */
    public static final String RAG_REWRITE = "ai.rag.rewrite";
    /** BM25 + 向量 + RRF 融合。 */
    public static final String RAG_HYBRID = "ai.rag.hybrid";
    /** 向量检索（通用命名空间，知识库第 64 行示例为 {@code db.vector.client.operation}）。 */
    public static final String DB_VECTOR_SEARCH = "db.vector.search";
    /** 关键词检索。 */
    public static final String DB_BM25_SEARCH = "db.bm25.search";
    /** 重排（规格书第 559 行：记录重排前后顺序对比）。 */
    public static final String RAG_RERANK = "ai.rag.rerank";
    /** 上下文裁剪。 */
    public static final String RAG_CONTEXT_BUILD = "ai.rag.context.build";
    /** 确定性费用计算（决策 C-33：金额由纯代码算，不给模型）。 */
    public static final String FEE_CALCULATE = "ai.fee.calculate";
    /** 模型调用（通用约定优先，知识库第 109 行：{@code gen_ai.client.operation}）。 */
    public static final String LLM_GENERATE = "ai.llm.generate";
    /** 输出校验（知识库第 443 行列为"必须自己补"的第 ④ 项：输出结构化校验与安全检查）。 */
    public static final String OUTPUT_VALIDATE = "ai.output.validate";

    // ==================== 低基数属性键（进 Metrics + Trace） ====================

    /** 检索策略（封闭枚举，规格书 §7.3 第 570 行）。 */
    public static final String KEY_RAG_STRATEGY = "ai.rag.strategy";
    /** 检索结果（封闭枚举：{@link #VALUE_RESULT_HIT} / {@link #VALUE_RESULT_EMPTY}）。 */
    public static final String KEY_RAG_RESULT = "ai.rag.result";
    /** 意图类型（封闭枚举，对应 {@code RouteDecision.CHANNEL_*}）。 */
    public static final String KEY_INTENT_TYPE = "ai.intent.type";

    /** {@link #KEY_RAG_RESULT} 的取值：命中。 */
    public static final String VALUE_RESULT_HIT = "hit";
    /** {@link #KEY_RAG_RESULT} 的取值：空召回。 */
    public static final String VALUE_RESULT_EMPTY = "empty";

    /** 低基数键全集——供测试断言"没有高基数的东西混进来"。 */
    public static final java.util.Set<String> LOW_CARDINALITY_KEYS =
            java.util.Set.of(KEY_RAG_STRATEGY, KEY_RAG_RESULT, KEY_INTENT_TYPE);

    // ==================== 高基数属性键（只进 Trace） ====================

    /** 召回条数（数值无界 ⇒ 只进 Trace，规格书 §7.3 第 571 行）。 */
    public static final String KEY_RAG_RESULT_COUNT = "ai.rag.result_count";
    /** 知识库 id（随知识库数量增长 ⇒ 只进 Trace）。 */
    public static final String KEY_KNOWLEDGE_BASE_ID = "knowledge_base_id";
    /** 召回文档 id 列表（只进 Trace）。 */
    public static final String KEY_DOCUMENT_IDS = "ai.rag.document_ids";
    /** 本次尝试序号（从 1 起；知识库第 55、269 行）。 */
    public static final String KEY_ATTEMPT = "attempt";
    /** 受影响行数（只进 Trace，规格书第 553 行要求记"行数"）。 */
    public static final String KEY_DB_ROWS = "db.rows";
    /** 通道降级/回退原因（受控枚举由调用方给，⛔ 不得塞自由文本）。 */
    public static final String KEY_DEGRADE_REASON = "ai.degrade.reason";

    /** 高基数键全集。 */
    public static final java.util.Set<String> HIGH_CARDINALITY_KEYS =
            java.util.Set.of(KEY_RAG_RESULT_COUNT, KEY_KNOWLEDGE_BASE_ID, KEY_DOCUMENT_IDS,
                    KEY_ATTEMPT, KEY_DB_ROWS, KEY_DEGRADE_REASON);

    /**
     * 规格书 §7.2 要求的 Span 名单（顺序照抄规格书第 548–563 行）。
     *
     * <p>存在的唯一理由：让 {@code AiSpansTest} 能<b>断言"规格书要的 16 个名字，
     * 这里一个不少、一个不多、顺序一致"</b>。
     */
    public static final java.util.List<String> ALL =
            java.util.List.of(AGENT_RUN, INTENT_ROUTE, STRUCTURED_QUERY, TOOL_PARAM_EXTRACT,
                    TOOL_PARAM_VALIDATE, DB_SQL_EXECUTE, RAG_RETRIEVE, RAG_REWRITE, RAG_HYBRID,
                    DB_VECTOR_SEARCH, DB_BM25_SEARCH, RAG_RERANK, RAG_CONTEXT_BUILD,
                    FEE_CALCULATE, LLM_GENERATE, OUTPUT_VALIDATE);
}
