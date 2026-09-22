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
 *   <tr><td rowspan="4">低基数（{@link #LOW_CARDINALITY_KEYS}）</td>
 *       <td>{@code ai.rag.strategy}、{@code ai.rag.result}</td>
 *       <td>Metrics + Trace</td></tr>
 *   <tr><td>{@code ai.intent.type}</td><td>Metrics + Trace</td></tr>
 *   <tr><td>{@code ai.rag.rerank.changed}、{@code ai.rag.fallback}（布尔式封闭枚举）</td>
 *       <td>Metrics + Trace</td></tr>
 *   <tr><td colspan="2">取值必须是<b>封闭枚举</b>（如 {@code hit}/{@code empty}）</td></tr>
 *   <tr><td rowspan="4">高基数（{@link #HIGH_CARDINALITY_KEYS}）</td>
 *       <td>{@code ai.rag.result_count}、文档 ID、{@code knowledge_base_id}</td>
 *       <td>只进 Trace</td></tr>
 *   <tr><td>{@code attempt}（重试次数）</td><td>只进 Trace</td></tr>
 *   <tr><td>{@code db.rows}（SQL 行数）</td><td>只进 Trace</td></tr>
 *   <tr><td>{@code ai.config.version}（配置/提示词/模型版本）</td><td>只进 Trace</td></tr>
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
    /**
     * 输出校验（知识库第 443 行列为"必须自己补"的第 ④ 项：输出结构化校验与安全检查）。
     *
     * <p>⚠️⚠️ <b>本项目暂未埋点：运行时无该环节，用户 2026-09-22 裁示挂起</b>。
     * 名字保留在 {@link #ALL} 里（{@code AiObservabilityTest} 要与规格书 §7.2 逐字比对），
     * 但<b>没有任何宿主类会产出它</b>——本项目里唯一那个"服务端 Schema 校验"
     * （{@code SchemaGate}）校的是<b>工具参数</b>，对应 {@link #TOOL_PARAM_VALIDATE}，
     * ⛔ 不是"最终输出校验"。
     * <p>⛔ <b>不要为了凑齐 §7.2 的节点数而造一个空壳 Span</b>：一个恒不触发、
     * 或只在某个无实质动作的地方触发的 Span，在 Trace 里看起来"这一步做了"，
     * 是<b>假证据</b>——比不埋更坏（同 {@code NoopReranker} 不埋点的理由）。
     */
    public static final String OUTPUT_VALIDATE = "ai.output.validate";

    // ==================== 低基数属性键（进 Metrics + Trace） ====================

    /** 检索策略（封闭枚举，规格书 §7.3 第 570 行）。 */
    public static final String KEY_RAG_STRATEGY = "ai.rag.strategy";
    /** 检索结果（封闭枚举：{@link #VALUE_RESULT_HIT} / {@link #VALUE_RESULT_EMPTY}）。 */
    public static final String KEY_RAG_RESULT = "ai.rag.result";
    /** 意图类型（封闭枚举，对应 {@code RouteDecision.CHANNEL_*}）。 */
    public static final String KEY_INTENT_TYPE = "ai.intent.type";

    /**
     * 重排是否**改变了候选顺序**（封闭枚举：{@code true} / {@code false}）。
     *
     * <p><b>为什么它是低基数（进 Metrics）</b>：规格书 §7.2 第 559 行要求
     * {@code ai.rag.rerank} "记录<b>重排前后顺序对比</b>"，而这句要求可以被归约成一个
     * <b>只有两种取值</b>的问题——"这次重排到底改没改顺序"。于是它可以参与聚合：
     * "重排改变顺序的比例"是一句**能算出来的**话，而"把前后两份列表都写进属性"既放不下
     * （列表是正文级体积）也无法聚合。
     *
     * <p>取值封闭性由构造保证：调用点只写 {@code "true"} / {@code "false"} 两个字面量
     * （见 {@code LlmReranker}），⛔ 不写"改了 3 条"这种开放取值。
     *
     * <p>⚠️ 语义边界（如实记一笔）：它只说"**顺序变了没有**"，<b>不说"变好还是变坏"</b>——
     * 后者需要 gold 标注，属评测层（{@code Recall@k}/{@code MRR}）的结论，
     * ⛔ 不从检索链路内部自报。
     */
    public static final String KEY_RAG_RERANK_CHANGED = "ai.rag.rerank.changed";

    /**
     * 是否发生了 {@code TOOL_NONE} 回退（封闭枚举：{@code true}）。
     *
     * <p><b>为什么需要它</b>：结构化通道返回 {@code TOOL_NONE} 时，编排层会把问题
     * <b>转交检索通道</b>（见 {@code QuestionPipeline.run} 的注释：这条契约在 5 处声明过，
     * 但很久没有地方真的执行它）。这次转交是**一次内部路由修正**——
     * {@code Answer.route} 说 STRUCTURED、而答案实际由检索段支撑
     * （所以 {@code Answer.effectiveChannel()} 专门为此存在）。
     *
     * <p>若不记录它，"路由判错"与"回退生效"在 Trace 上长得一模一样，
     * 而这正是本项目《评测报告》归因时最需要分开的两件事。
     *
     * <p>⚠️ 只在<b>真的发生</b>时写 {@code "true"}（⛔ 不写 {@code "false"}）：
     * 该键的用途是"数一数有多少题发生了回退"，缺失即表示没发生；
     * 写 false 只会让时间序列多一份恒 0 的冗余数据。
     */
    public static final String KEY_RAG_FALLBACK = "ai.rag.fallback";

    /**
     * {@code ai.tool.param.validate} 这**一次**校的是哪一步（封闭枚举：{@code envelope} / {@code arguments}）。
     *
     * <p><b>为什么需要它</b>：该 Span 在**一次抽取尝试里会出现两次**——先校模型回包的
     * <b>JSON 外壳</b>（是不是合法 JSON、字段齐不齐），再校<b>所选工具的实参</b>
     * （必填项、类型、取值域）。两处都按规格书 §7.2 的**同一个名字**上报，
     * 于是在 Trace 上就是**两个同名兄弟节点**：Span 一条不少、名字也全对，
     * 但读者分不清哪一条对应哪一种失败。
     *
     * <p>而这两种失败的<b>补救方式不同</b>：外壳不合法要多半改提示词（模型没按格式答），
     * 实参不合法要带着错误信息重试抽取（模型看错了某个参数的契约）。
     * ⇒ 本键只做一件事：**在不偏离规格书节点名的前提下，让两个节点可区分**。
     *
     * <p>⚠️ 取值只有两个字面量（见 {@code SchemaGate} 的两处调用），⛔ 不写工具名等开放取值。
     */
    public static final String KEY_TOOL_VALIDATE_STAGE = "ai.tool.param.validate.stage";

    /** {@link #KEY_TOOL_VALIDATE_STAGE} 的取值：校验模型回包的 JSON 外壳。 */
    public static final String VALUE_VALIDATE_STAGE_ENVELOPE = "envelope";
    /** {@link #KEY_TOOL_VALIDATE_STAGE} 的取值：校验所选工具的实参。 */
    public static final String VALUE_VALIDATE_STAGE_ARGUMENTS = "arguments";

    /** {@link #KEY_RAG_RESULT} 的取值：命中。 */
    public static final String VALUE_RESULT_HIT = "hit";
    /** {@link #KEY_RAG_RESULT} 的取值：空召回。 */
    public static final String VALUE_RESULT_EMPTY = "empty";

    /** 低基数键全集——供测试断言"没有高基数的东西混进来"。 */
    public static final java.util.Set<String> LOW_CARDINALITY_KEYS =
            java.util.Set.of(KEY_RAG_STRATEGY, KEY_RAG_RESULT, KEY_INTENT_TYPE,
                    KEY_RAG_RERANK_CHANGED, KEY_RAG_FALLBACK, KEY_TOOL_VALIDATE_STAGE);

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

    /**
     * 配置 / 提示词 / 模型的**版本标识**（只进 Trace）。
     *
     * <p><b>为什么它必须是高基数</b>：它的取值集合随"我们改过多少版提示词/模型"增长，
     * 而版本一旦成为 Metrics 标签，"按版本对比指标"就会让时间序列按版本数翻倍——
     * 版本对比是<b>离线评测</b>的活（本项目有 {@code eval_record} + run_id），
     * 不该由指标系统承担。故它只进 Trace：排查时能回答"这条 Trace 是哪一版跑出来的"。
     *
     * <p><b>取值允许中文</b>（高基数一侧不限制字符集，只禁换行与超长）：
     * 本项目的规则 id 就是中文（如 {@code R1-数值诉求-结构化}）——
     * 它是人写给人看的，强行转成英文枚举值只会丢掉"凭什么这么判"的信息。
     *
     * <p>⛔ <b>不得用它承载正文</b>：版本串、提示词版本号、模型名都远小于 512 字符，
     * 一旦有人把提示词原文挂上来，闸门会因超长/换行而拒（这正是它存在的意义）。
     */
    public static final String KEY_CONFIG_VERSION = "ai.config.version";

    /** 高基数键全集。 */
    public static final java.util.Set<String> HIGH_CARDINALITY_KEYS =
            java.util.Set.of(KEY_RAG_RESULT_COUNT, KEY_KNOWLEDGE_BASE_ID, KEY_DOCUMENT_IDS,
                    KEY_ATTEMPT, KEY_DB_ROWS, KEY_DEGRADE_REASON, KEY_CONFIG_VERSION);

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
