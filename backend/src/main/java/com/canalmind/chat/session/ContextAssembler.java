package com.canalmind.chat.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 多轮上下文的装配入口（第 4 周第 2 项，决策 <b>C-38</b>）：滑动窗口 + Compaction 摘要。
 *
 * <h2>消息顺序（这个顺序就是设计，⛔ 不要改）</h2>
 * <ol>
 *   <li>{@code system} 基础提示（构造时注入）；</li>
 *   <li>{@code system} 会话摘要（**仅当已有摘要**）：只代表更早的轮次；</li>
 *   <li>{@code system} {@link #RAW_PARAMS_HEADER 最近原始参数}（**仅当近期轮次确有结构化参数**）：
 *       逐字给出最近若干轮的 {@code structuredParamsJson}，⛔ 永不入摘要；</li>
 *   <li>仍为原文的每一轮：{@code user} 问题 → {@code assistant} 应答（应答为空则不发该条）；</li>
 *   <li>{@code user} 本轮新问题。</li>
 * </ol>
 * 摘要块排在原始参数块**之前**：先说"更早聊过什么"，紧接着说"近期可执行的参数是这些"，
 * 最后才是逐轮原文与当前问题。反过来（参数在前、摘要在后）会让摘要成为模型最后读到的
 * "最新印象"，而摘要恰恰是**最不可信**的那一块（它是二次复述，不是原始返回值）——
 * 决策 C-38③ 要防的就是"模型拿摘要里的复述当参数用"。
 *
 * <h2>硬约束：压缩不得吃掉结构化参数（C-38③）</h2>
 * 最近 {@code keepRecentTurns} 轮的 {@code structuredParamsJson} **必须逐字**留在
 * {@link #RAW_PARAMS_HEADER} 块里，即使压缩已经发生。理由是数据集自身的要求：
 * {@code docs/评测报告/golden-set-v3.json} 的 {@code L4-008}（第 1762 行 {@code min_gross_tonnage=1520}）、
 * {@code L4-019}（第 2157 行 {@code date_from=2026-11-26}）、
 * {@code L4-023}（第 2297 行 {@code tonnage=2774}）三条第二轮的正确答案**都要从第一轮的真实返回值**里取，
 * 而第一轮在任何时刻都是"最近一轮"⇒ 只要 {@code keepRecentTurns ≥ 1}，它就一定还在原文侧。
 *
 * <h2>⚠️ 诚实说明：本项目数据集上压缩路径从不触发</h2>
 * 本项目的数据集最长对话就是 **2 轮**（实测：{@code golden-set-v3.json} 120 条里
 * 对话长度为 2 的有 25 条、其余 95 条无对话；{@code backend/scripts/golden-extra-items.json}
 * 80 条里同样 25 条），而 {@link ContextBudget#DEFAULT_KEEP_RECENT_TURNS} 是 2。
 * 于是在任何**现实的**预算下，本类的压缩分支都不会被走到：
 * 它的正确性**只由 {@code ContextAssemblerTest} 里"强制极小预算"的用例证明**，
 * ⛔ **没有任何一次金标准集运行能证明它**。读取本类产出的 {@code note} 时请照此理解，
 * 不要把它写成"压缩已在评测中生效"（`AGENTS.md` §6 第 1、3 条）。
 */
public final class ContextAssembler {

    /** 换行用计算字符，⛔ 不用转义字面量（`AGENTS.md` §7.2 硬规定；本项目已因此静默失败三次）。 */
    private static final String LF = String.valueOf((char) 10);

    /** 最近原始参数块的块头；⛔ 改字就要同步改测试（测试按它定位这一块）。 */
    public static final String RAW_PARAMS_HEADER = "【最近原始参数（⛔ 永不入摘要；改写本轮参数必须以此为唯一出处）】";

    /** 会话摘要块的块头；⛔ 改字就要同步改测试。 */
    public static final String SUMMARY_HEADER = "【会话摘要（只代表更早的轮次，其中不含下列原始参数）】";

    /** note 里引用的异常信息上限，避免一个超长的模型报错把 note 本身撑爆。 */
    private static final int MAX_NOTE_DETAIL = 120;

    private final SessionStore store;
    private final TokenEstimator estimator;
    private final ContextBudget budget;
    private final String baseSystemPrompt;
    private final ContextCompactor compactor;

    /**
     * @param store            会话存储（本阶段为 {@link InMemorySessionStore}，接口是给未来的 Redis 留的）
     * @param summarizer       摘要器（压缩时才会被调用；见 {@code ContextSummarizer} 的失败约定）
     * @param estimator        token 估算器（阈值比较用，⛔ 其返回值不是真实 token 数）
     * @param budget           预算（阈值**由外部注入**，不是写死的常量——决策 C-38②）
     * @param baseSystemPrompt 基础系统提示（⛔ 不得为空：本层的第一块就是它，
     *                         空的话整段上下文会以"没有角色设定"开始，而模型不会报错）
     */
    public ContextAssembler(SessionStore store, ContextSummarizer summarizer, TokenEstimator estimator,
                           ContextBudget budget, String baseSystemPrompt) {
        this.store = Objects.requireNonNull(store, "store 不得为 null");
        Objects.requireNonNull(summarizer, "summarizer 不得为 null");
        this.estimator = Objects.requireNonNull(estimator, "estimator 不得为 null");
        this.budget = Objects.requireNonNull(budget, "budget 不得为 null");
        if (baseSystemPrompt == null || baseSystemPrompt.isBlank()) {
            throw new IllegalArgumentException("baseSystemPrompt 不得为空");
        }
        this.baseSystemPrompt = baseSystemPrompt;
        this.compactor = new ContextCompactor(summarizer);
    }

    /**
     * 装配一个新问题的上下文。
     *
     * <p>⚠️ **本方法不改动存储**：既不会把 {@code newQuestion} 存成"已完成的一轮"，
     * 也不会持久化压缩结果。理由是"这一轮还没答"——若在这里先落库，
     * 一旦模型调用失败，会话里就多出一条"有问题没答案"的假轮次，
     * 而后续装配会把它当成历史发回去（本项目对"半成品被当成成品"有实录：
     * `docs/工程复盘/诚实记录/中间产物值被当成最终值沿用.md`）。
     * 完成一轮请调 {@link #appendTurn(String, SessionTurn)}。
     *
     * @param sessionId   会话 id
     * @param newQuestion 本轮问题原话
     * @return 装配结果（消息、估算值、是否压缩、用了哪版摘要、原文轮次、说明）
     * @throws IllegalArgumentException newQuestion 为空
     * @throws Exception                仅当摘要器失败**之外**的异常（摘要器失败已被吸收进 {@code note}）
     */
    public AssembledContext assemble(String sessionId, String newQuestion) throws Exception {
        if (newQuestion == null || newQuestion.isBlank()) {
            throw new IllegalArgumentException("newQuestion 不得为空（sessionId=" + sessionId + "）");
        }

        SessionState state = store.load(sessionId);
        List<ContextMessage> messages = build(state, newQuestion);
        int estimated = estimateOf(messages);
        int limit = budget.maxEstimatedTokens();

        if (estimated <= limit) {
            return new AssembledContext(messages, estimated, false, state.summary(), state.rawTurns(),
                    "未触发压缩：估计 " + estimated + " ≤ 预算 " + limit
                            + "（估算器为启发式，⛔ 这个数不是真实 token 数）");
        }

        SessionState before = state;
        try {
            SessionState folded = compactor.compact(before, budget.keepRecentTurns());
            if (folded.summarizedUpTo() <= before.summarizedUpTo()) {
                return new AssembledContext(messages, estimated, false, before.summary(), before.rawTurns(),
                        "预算超限（估计 " + estimated + " > " + limit + "）但**无可折叠的更早轮次**："
                                + "原文轮次 " + before.rawTurns().size() + " ≤ 保留轮数 "
                                + budget.keepRecentTurns() + "，⛔ 未丢弃任何轮次");
            }
            List<ContextMessage> compactedMessages = build(folded, newQuestion);
            int compactedEstimated = estimateOf(compactedMessages);
            return new AssembledContext(compactedMessages, compactedEstimated, true, folded.summary(),
                    folded.rawTurns(),
                    "已压缩：index < " + folded.summarizedUpTo() + " 的轮次已折进摘要；估计 "
                            + estimated + " → " + compactedEstimated + "（启发式估算）");
        } catch (Exception summarizerFailure) {
            // 摘要失败 ⇒ 保留全部原文，并把失败如实写进 note。
            // ⛔ 不在这里"当作没有更早轮次"：那会让一次模型超时变成静默丢历史，
            // 而丢的正是 L4-008 / L4-019 / L4-023 赖以取数的那几轮。
            return new AssembledContext(messages, estimated, false, before.summary(), before.rawTurns(),
                    "预算超限（估计 " + estimated + " > " + limit + "）但**摘要失败**（"
                            + summarizerFailure.getClass().getSimpleName() + "："
                            + firstLine(summarizerFailure.getMessage())
                            + "）：⛔ 未丢弃任何轮次，原文保留");
        }
    }

    /**
     * 记录一轮**已经答完**的问答（由调用方在拿到应答之后调用）。
     *
     * <p>这一轮会立刻参与后续回合的装配：它要么落在原文侧（{@code index >= summarizedUpTo}），
     * 要么落进摘要覆盖范围。
     *
     * @throws IllegalArgumentException 该轮 index 落在摘要覆盖范围内
     *                                  （{@code index < summarizedUpTo}）——
     *                                  这种轮次**不会出现在上下文里**（已被摘要代表），
     *                                  接受它就等于静默丢一轮，故直接拒绝而不是默默存下
     */
    public void appendTurn(String sessionId, SessionTurn turn) {
        Objects.requireNonNull(turn, "turn 不得为 null");
        SessionState state = store.load(sessionId);
        if (turn.index() < state.summarizedUpTo()) {
            throw new IllegalArgumentException("轮次 index=" + turn.index()
                    + " 落在摘要覆盖范围内（summarizedUpTo=" + state.summarizedUpTo()
                    + "）：这样的轮次不会出现在上下文里（已由摘要代表），存下它等于静默丢一轮");
        }
        store.save(state.withTurn(turn));
    }

    /** 当前预算（供自证与排查读取）。 */
    public ContextBudget budget() {
        return budget;
    }

    // ------------------------------------------------------------ 装配细节

    private List<ContextMessage> build(SessionState state, String newQuestion) {
        ArrayList<ContextMessage> messages = new ArrayList<>();
        messages.add(ContextMessage.system(baseSystemPrompt));
        if (state.hasSummary()) {
            messages.add(ContextMessage.system(SUMMARY_HEADER + LF + state.summary()));
        }
        List<SessionTurn> raw = state.rawTurns();
        String paramsBlock = renderRawParams(raw);
        if (!paramsBlock.isEmpty()) {
            messages.add(ContextMessage.system(paramsBlock));
        }
        for (SessionTurn turn : raw) {
            messages.add(ContextMessage.user(turn.question()));
            if (!turn.answer().isBlank()) {
                messages.add(ContextMessage.assistant(turn.answer()));
            }
        }
        messages.add(ContextMessage.user(newQuestion));
        return List.copyOf(messages);
    }

    /**
     * 渲染"最近原始参数"块：每轮一行，参数文本**逐字照抄**（⛔ 不做任何格式化/转义）。
     *
     * <p>近期轮次都没有结构化参数时**整块不发**（而不是发一个空块）：
     * 空块会把「本轮确实没有可执行参数」与「参数块生成失败」混成一件事——
     * 与 {@link ContextMessage} 拒绝空正文是同一条理由。
     */
    private static String renderRawParams(List<SessionTurn> rawTurns) {
        StringBuilder sb = new StringBuilder();
        for (SessionTurn turn : rawTurns) {
            if (!turn.hasStructuredParams()) {
                continue;
            }
            if (sb.isEmpty()) {
                sb.append(RAW_PARAMS_HEADER).append(LF);
            }
            sb.append("第 ").append(turn.index()).append(" 轮：")
                    .append(turn.structuredParamsJson()).append(LF);
        }
        return sb.isEmpty() ? "" : sb.toString().stripTrailing();
    }

    private int estimateOf(List<ContextMessage> messages) {
        int total = 0;
        for (ContextMessage message : messages) {
            total += estimator.estimate(message.content());
        }
        return total;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int cut = text.indexOf(LF);
        String line = cut < 0 ? text : text.substring(0, cut);
        return line.length() <= MAX_NOTE_DETAIL ? line : line.substring(0, MAX_NOTE_DETAIL) + "…";
    }

    // ------------------------------------------------------------------ 值对象

    /**
     * 上下文预算（**全部由外部注入**，决策 C-38②：阈值可注入、可强制触发，⛔ 不写死）。
     *
     * @param maxEstimatedTokens 触发压缩的估算上限，**必须为正**
     * @param keepRecentTurns    必须保留原文的最近轮数，**必须为正**
     *                           （0 的意思"最近一轮的参数也可以被摘要吃掉"，与 C-38③ 的硬约束直接冲突）
     */
    public record ContextBudget(int maxEstimatedTokens, int keepRecentTurns) {

        /**
         * 默认估算上限 {@value}。
         *
         * <p><b>这是策略选择，不是实测结论</b>（`AGENTS.md` §6 第 1 条）。取偏小的数是因为
         * 本层只装配"对话切片"——基础提示 + 摘要 + 近期原始参数 + 若干轮问答 + 新问题，
         * ⛔ **不含检索回来的规则片段**，那些由检索侧另行占用模型的输入额度。
         * 故这里给检索侧与输出额度留出余量，而不是把整个窗口额度都算给对话。
         * 至于"真实窗口到底多大"，本仓库**无法自证**：{@code .env} 里
         * {@code DEEPSEEK_BASE_URL=https://fastmodel.top/v1} 是第三方中转端点、
         * 模型名是 {@code deepseek-v4-flash}，其窗口大小在仓库内没有任何出处。
         * 所以它只是"没显式注入时不会太离谱"的兜底，真正生效的取值应由调用方注入。
         */
        public static final int DEFAULT_MAX_ESTIMATED_TOKENS = 4096;

        /**
         * 默认保留轮数 {@value}。
         *
         * <p><b>依据是实测的本项目数据形态</b>：数据集最长对话就是 2 轮——
         * {@code docs/评测报告/golden-set-v3.json} 120 条里对话长度为 2 的有 25 条、
         * 其余 95 条无对话；{@code backend/scripts/golden-extra-items.json} 80 条里同样 25 条。
         * 取 2 意味着默认配置在**现有任何样本上**都不会把"上一轮的返回值"折走，
         * 从而不会自己制造 C-38③ 要防的那种失败。
         */
        public static final int DEFAULT_KEEP_RECENT_TURNS = 2;

        /** 默认预算（{@link #DEFAULT_MAX_ESTIMATED_TOKENS} + {@link #DEFAULT_KEEP_RECENT_TURNS}）。 */
        public static final ContextBudget DEFAULT =
                new ContextBudget(DEFAULT_MAX_ESTIMATED_TOKENS, DEFAULT_KEEP_RECENT_TURNS);

        public ContextBudget {
            if (maxEstimatedTokens < 1) {
                throw new IllegalArgumentException("maxEstimatedTokens 必须为正，收到：" + maxEstimatedTokens
                        + "（0 或负数会让每一次装配都判定为超预算）");
            }
            if (keepRecentTurns < 1) {
                throw new IllegalArgumentException("keepRecentTurns 必须为正，收到：" + keepRecentTurns
                        + "（决策 C-38③：最近轮次的原始参数永不被摘要替代）");
            }
        }
    }

    /**
     * 一次装配的结果。
     *
     * @param messages        按 {@link ContextAssembler} 类注释的顺序排好的消息（不可变）
     * @param estimatedTokens **启发式估算**的总量（⛔ 不是真实 token 数）；压缩发生时它是压缩后的值
     * @param compacted       本次是否**真的**折叠了轮次。⚠️ 为 {@code false} 有两种完全不同含义，
     *                        必须读 {@code note} 才能区分：①本来没超预算；②超了预算但无可折叠
     *                        （或摘要失败）。⛔ 别只看这个布尔值
     * @param summaryUsed     实际放进上下文的那版摘要（没有摘要块时为空串）；
     *                        它与 {@code rawTurns} 的 {@code structuredParamsJson} **职责不重叠**：
     *                        摘要只代表更早的叙述，参数一律以原文为准
     * @param rawTurns        仍是原文的轮次（{@code index >= summarizedUpTo}），
     *                        压缩后恰为最后 {@code keepRecentTurns} 轮
     * @param note            **给排查的人看的说明**（中文、单段）：压缩到底有没有触发、
     *                        为什么没触发、估算值怎么变。⚠️ 它同时是"诚实说明"的载体：
     *                        本项目数据集上压缩分支从不触发，读它才不会误以为压缩已生效
     */
    public record AssembledContext(List<ContextMessage> messages, int estimatedTokens, boolean compacted,
                                  String summaryUsed, List<SessionTurn> rawTurns, String note) {

        public AssembledContext {
            messages = messages == null ? List.of() : List.copyOf(messages);
            summaryUsed = summaryUsed == null ? "" : summaryUsed;
            rawTurns = rawTurns == null ? List.of() : List.copyOf(rawTurns);
            note = note == null ? "" : note;
        }
    }
}
