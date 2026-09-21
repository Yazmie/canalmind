package com.canalmind.rag.retrieve;

import java.util.List;
import java.util.Objects;

/**
 * 重排结果（第 2 周第 5 项，C-27）。
 *
 * <p><b>为什么要单独一个类型而不是直接返回 List</b>：C-27 的降级要求是
 * 「解析失败即降级 = 保留融合原序」——若不把 {@code degraded} 与
 * {@code degradeReason} 带出来，这个降级在数据上<b>与"重排后恰好等价于原序"完全一样</b>，
 * 于是"没跑成"会被读成"rerank 没效果"。这是本项目一贯要挡的一类问题
 * （对比 C-27 里"缺 key 不许静默降级"的同一条理由）。
 *
 * <p>{@code latencyMillis} <b>不进产物 JSON</b>：它每次运行都不同，
 * 会让产物 diff 永远不为空，于是"某次差异来自模型改进还是来自环境"就无法判断
 * （AGENTS.md §7.2）。它只打 stdout，供人工与第 4 周的延迟分析用。
 *
 * <h2>⚠️ 重试也要进产物（2026-09-21 补）</h2>
 *
 * <p>这条链路会<b>偶发降级</b>（实测同一查询 4 次真实调用里 1 次解析失败），
 * 故 {@link LlmReranker} 加了有界重试。重试一旦存在，"<b>一次就成功</b>"与
 * "<b>第 3 次才成功</b>"就变成两种不同的事实——前者说明链路稳定，
 * 后者说明链路本身不稳、只是被重试兜住了。若两者都只写 {@code degraded=false}，
 * 产物读者<b>无法分辨</b>，而这正是"重试把真故障掩盖掉"的入口。
 * 故 {@link #attempts()} 与 {@link #previousDegradeReasons()} <b>必须</b>随产物带出去：
 * {@code attempts=1} 才是一次成功；{@code attempts=3} 且 {@code degraded=false}
 * 读作"重试两次后成功（前两次的原因在 previousDegradeReasons 里）"。
 *
 * <h2>⚠️ 还要说得清「为什么没重试」（2026-09-21 补，{@link FailureKind}）</h2>
 *
 * <p>真实试点暴露了一个<b>自相矛盾的输出</b>：stdout 上一行写
 * 「重排尝试 ：1 次（一次成功）」，下一行却是降级警告。原因是"次数"与"是否成功"
 * 被当成同一件事——{@code attempts=1} <b>只说明没重试</b>，可能是"一次就成了"，
 * 也可能是"这个原因根本不重试"（空正文 / 超时 / 鉴权失败 / 候选超上限…），
 * 还可能是"重试被 {@code --rerank-retry 0} 关掉了"。三者必须分得开。
 *
 * <p>故本次把"失败分类"做成<b>数据</b>（而不是让读者去猜 {@code degradeReason} 的措辞）：
 * {@link #failureKind()} 是一个枚举，它同时给出
 * 「这一档要不要重试」（{@link FailureKind#retryable()}）与
 * 「为什么没重试」（{@link FailureKind#retryNote()}）。
 * ⛔ 刻意<b>不</b>用 {@code degradeReason} 的字符串匹配来做这个判断：
 * 那种写法改一个字就静默失效（与 {@link LlmReranker} 里"逐条写死在分支里"的口径一致）。
 *
 * @param candidates    重排后的候选，长度 = {@code min(topN, 输入条数)}。
 *                      <b>降级时是"输入原序的前 topN 条"</b>，且<b>不带</b>
 *                      {@code rerankRank}/{@code rerankScore}
 *                      （⛔ 给它们填上"等价于原序的名次"会让降级看起来像成功）
 * @param degraded      本次是否降级（模型不可用 / 输出不可解析 / 候选超上限等）。
 *                      ⚠️ 语义是「<b>重试后仍降级</b>」：重试过一次就成功的这里仍是 false，
 *                      那种情况由 {@link #attempts()} / {@link #previousDegradeReasons()} /
 *                      {@link #recoveredByRetry()} 表达
 * @param degradeReason 降级原因，<b>未降级时为 null</b>；已降级时不得为空。
 *                      重试后仍降级时，这里放的是<b>最后一次</b>的原因，前几次在
 *                      {@link #previousDegradeReasons()}
 * @param latencyMillis 本次重排耗时（毫秒，<b>含重试</b>）。降级且<b>未调用模型</b>时为 0
 * @param model         实际使用的模型标识；空查询短路等未调用模型的场景由调用方写
 *                      {@link #NO_MODEL}，<b>不要</b>冒用真实模型名
 * @param attempts      本次向重排模型<b>发起了几次调用</b>（首发 + 重试）。
 *                      <b>0 = 一次都没调</b>（空输入 / 候选超上限被拒 / 关掉重排的 NoopReranker），
 *                      此时 {@link #degradeReason()} 会写明"未调用模型"，两者不矛盾
 * @param previousDegradeReasons 前几次失败的原文（按发生顺序）；
 *                      最后一次失败在 {@link #degradeReason()}（若最终成功则该键为 null）。
 *                      <b>未重试时为空表</b>；长度 = {@code max(0, attempts - 1)}
 * @param failureKind   <b>最后一次失败</b>的分类（见 {@link FailureKind}）。
 *                      未降级时一律为 {@link FailureKind#NONE}；
 *                      由调用方自建的 outcome（测试夹具、自定义兜底实现）可用
 *                      {@link FailureKind#UNSPECIFIED} 表示"没标注"
 */
public record RerankOutcome(
        List<RetrievalCandidate> candidates,
        boolean degraded,
        String degradeReason,
        long latencyMillis,
        String model,
        int attempts,
        List<String> previousDegradeReasons,
        FailureKind failureKind) {

    /**
     * 「本次没有调用任何模型」的占位模型名。
     *
     * <p>用在空查询短路这类场景。刻意不用 {@code null}：{@code null} 与"忘了填"
     * 无法区分，而这个字段的用途正是让人一眼看出"模型到底跑没跑"。
     */
    public static final String NO_MODEL = "none";

    /**
     * 重排失败的分类（2026-09-21 补）。
     *
     * <p>存在的理由：<b>"试了几次"和"为什么只试了一次"是两件事</b>。
     * 只报 {@code attempts} 时，{@code 1 次} 既可能是"一次就成了"，
     * 也可能是"这个原因压根不重试"——实测就出现过 stdout 先写
     * 「1 次（一次成功）」、下一行紧跟降级警告的自相矛盾。
     *
     * <p>每个取值都带两条判断依据，都是<b>数据</b>而不是文案：
     * <ul>
     *   <li>{@link #retryable()}——这一档要不要重试（口径逐条对齐
     *       {@link LlmReranker} 类注释里的重试表，⛔ 全场只有
     *       {@link #UNPARSABLE} 一个可重试档，不擅自扩大）；</li>
     *   <li>{@link #retryNote()}——<b>为什么（不）重试</b>，给 stdout 与排错用；</li>
     *   <li>{@link #beforeModelCall()} / {@link #afterModelCall()}——这一档发生在调模型
     *       <b>之前</b>还是<b>之后</b>，用来与 {@link #attempts()} 交叉校验：
     *       "调模型之前就失败"却记着调过 1 次，说明尝试次数与失败分类来自两次不同的统计，
     *       产物会自相矛盾（本类型的构造器直接拒绝这种组合）。</li>
     * </ul>
     */
    public enum FailureKind {

        /** 没有失败（成功，或"空输入"这类不算失败的短路）。 */
        NONE("无失败", false, "没有失败"),

        /**
         * 调用方自己造 outcome 时没给分类（测试夹具、自定义 {@link Reranker} 实现）。
         *
         * <p>刻意保留这一档而不是硬塞一个具体分类：塞错分类比"未标注"更坏——
         * 产物会因此给出一个<b>看起来可信</b>的错误结论。
         */
        UNSPECIFIED("未标注", false, "调用方未标注分类，故一律不重试"),

        /** 候选数超过上限（在调模型之前就被拒）。 */
        TOO_MANY_CANDIDATES("候选超上限", false, "候选数在调模型之前就已超上限，重试结果相同"),

        /** 候选正文片段全部为空（在调模型之前就被拒）。 */
        EMPTY_SNIPPETS("正文片段全空", false, "正文在调模型之前就已确定全空，重试结果相同"),

        /** 取候选正文片段时抛异常（在调模型之前）。 */
        SNIPPET_FAILED("取正文片段失败", false, "读正文失败发生在调模型之前，重试结果相同"),

        /** 调用超时（放弃等待）。 */
        TIMEOUT("调用超时", false, "重试要再等一整个等待上限，且超时多半是模型/网关真的慢"),

        /**
         * 模型调通了、正文却是空的。
         *
         * <p>最常见的原因是推理 token 把 {@code maxTokens} 预算吃光
         * （{@code finish_reason=length} 且 {@code completion == maxTokens}）。
         */
        BLANK_COMPLETION("空正文", false, "空正文是确定性失败：同一提示词再来一次只会再花一次钱"),

        /** 调用失败（网络不可达 / 鉴权 401 / 服务端 5xx）。 */
        CALL_FAILED("调用失败（网络/鉴权/服务端）", false,
                "重试会把「key 过期 / base-url 写错 / 网络不通」掩盖成「试几次就好了」"),

        /** 调用被中断（InterruptedException）。 */
        INTERRUPTED("被中断", false, "调用方要求停，重试等于把中断吃掉"),

        /** 输出已解析成功，但引用了候选集之外的 chunk_id（编造 id）。 */
        UNKNOWN_CHUNK_ID("编造 chunk_id", false,
                "输出已解析成功，属于模型服从性问题，不在「无法解析 / 被截断」这条重试口径内"),

        /**
         * 输出无法解析为约定 JSON（<b>含被 token 上限截断</b>）——<b>全场唯一可重试的一档</b>。
         *
         * <p>理由：模型这一轮"没吐好"，换个样本很可能就好（实测降级率 ~25% 就属这一类）。
         */
        UNPARSABLE("输出无法解析或被截断", true, "模型这一轮「没吐好」，换个样本很可能就好");

        private final String label;
        private final boolean retryable;
        private final String retryNote;

        FailureKind(String label, boolean retryable, String retryNote) {
            this.label = label;
            this.retryable = retryable;
            this.retryNote = retryNote;
        }

        /** 短标签（给 stdout 用，如「空正文」）。 */
        public String label() {
            return label;
        }

        /** 这一档是否重试。⛔ 只有 {@link #UNPARSABLE} 为 true。 */
        public boolean retryable() {
            return retryable;
        }

        /** 为什么（不）重试——一句话，给 stdout 与排错用。 */
        public String retryNote() {
            return retryNote;
        }

        /** 这一档是否发生在<b>调模型之前</b>（此时 {@code attempts} 必须为 0）。 */
        public boolean beforeModelCall() {
            return this == TOO_MANY_CANDIDATES || this == EMPTY_SNIPPETS || this == SNIPPET_FAILED;
        }

        /** 这一档是否发生在<b>调模型之后</b>（此时 {@code attempts} 必须 >= 1）。 */
        public boolean afterModelCall() {
            return this == TIMEOUT || this == BLANK_COMPLETION || this == CALL_FAILED
                    || this == INTERRUPTED || this == UNPARSABLE || this == UNKNOWN_CHUNK_ID;
        }
    }

    public RerankOutcome {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        Objects.requireNonNull(failureKind, "failureKind");
        if (degraded && (degradeReason == null || degradeReason.isBlank())) {
            // 降级而不写原因 = 把"为什么没跑成"丢掉，等于回到 C-27 要挡的那种状态
            throw new IllegalArgumentException(
                    "degraded=true 时必须写明 degradeReason（否则「没跑成」与「跑成了但没效果」无法区分）");
        }
        if (!degraded) {
            // 未降级却留着原因/分类：属于自相矛盾的状态，统一归一成 null/NONE，
            // 免得产物里出现 degraded=false 却带一句"失败原因"或一个失败分类的怪组合
            //
            // ⚠️ 但 previousDegradeReasons **不清空**：重试后成功（degraded=false）
            //    恰恰要靠它表达"这次不是一次成功的"。清掉它就等于把重试藏起来了。
            degradeReason = null;
            failureKind = FailureKind.NONE;
        } else if (failureKind == FailureKind.NONE) {
            // 降级却报"没有失败"：读者据此推不出任何处置方向，等于把分类白做了
            throw new IllegalArgumentException(
                    "degraded=true 时 failureKind 不得为 NONE（NONE 只用于未降级）："
                            + "否则「为什么没重试、该改哪个旋钮」在产物里就丢了");
        }
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis 不得为负，实际：" + latencyMillis);
        }
        if (attempts < 0) {
            throw new IllegalArgumentException(
                    "attempts 不得为负（0 = 一次都没调模型），实际：" + attempts);
        }
        if (failureKind.beforeModelCall() && attempts != 0) {
            // 这类失败在构造提示词之前就决定了，不可能"调过模型"
            throw new IllegalArgumentException(
                    "failureKind=" + failureKind + " 发生在调模型之前，attempts 必须为 0，实际："
                            + attempts + "：两者对不上说明尝试次数与失败分类来自两次不同的统计，"
                            + "产物会自相矛盾");
        }
        if (failureKind.afterModelCall() && attempts < 1) {
            throw new IllegalArgumentException(
                    "failureKind=" + failureKind + " 只可能发生在调模型之后，attempts 必须 >= 1，实际："
                            + attempts + "：两者对不上说明「失败分类」与「试了几次」来自两次不同的统计");
        }
        previousDegradeReasons = List.copyOf(
                previousDegradeReasons == null ? List.of() : previousDegradeReasons);
        if (previousDegradeReasons.size() > attempts) {
            // 前几次失败最多有 attempts-1 条（最后一次归 degradeReason）；
            // 超了说明"尝试次数"与"失败记录"来自两次不同的统计，产物会自相矛盾
            throw new IllegalArgumentException(
                    "previousDegradeReasons 有 " + previousDegradeReasons.size()
                            + " 条，但 attempts 只有 " + attempts
                            + "：最多只能是 attempts 次尝试留下的失败记录，"
                            + "多出来说明尝试次数与失败记录对不上，产物会自相矛盾");
        }
        Objects.requireNonNull(model, "model");
    }

    /** 未降级结果（未重试：{@code attempts=0}，表示调用方自行决定是否统计调用次数）。 */
    public static RerankOutcome ok(List<RetrievalCandidate> candidates, long latencyMillis, String model) {
        return new RerankOutcome(candidates, false, null, latencyMillis, model, 0, List.of(),
                FailureKind.NONE);
    }

    /**
     * 未降级结果，且带出<b>尝试次数与前几次失败</b>（重试后成功时用这个）。
     *
     * @param attempts               本次共尝试几次（含成功那一次）
     * @param previousDegradeReasons 前几次失败的原文（按顺序；长度 = attempts - 1）
     */
    public static RerankOutcome ok(List<RetrievalCandidate> candidates, long latencyMillis,
                                   String model, int attempts,
                                   List<String> previousDegradeReasons) {
        return new RerankOutcome(candidates, false, null, latencyMillis, model, attempts,
                previousDegradeReasons, FailureKind.NONE);
    }

    /**
     * 降级结果：保留 {@code fallback}（= 输入原序截断），<b>未标注</b>失败分类。
     *
     * <p>⚠️ 拿得到分类时请用带 {@link FailureKind} 的那个重载：分类是
     * "为什么没重试、该改哪个旋钮"的唯一机器可读依据，缺了它 stdout 只能含糊其辞。
     */
    public static RerankOutcome degraded(List<RetrievalCandidate> fallback, String reason,
                                         long latencyMillis, String model) {
        return new RerankOutcome(fallback, true, reason, latencyMillis, model, 0, List.of(),
                FailureKind.UNSPECIFIED);
    }

    /** 降级结果（带失败分类；{@code attempts} 由分类推导——调模型之前失败的那些档一律 0 次）。 */
    public static RerankOutcome degraded(List<RetrievalCandidate> fallback, String reason,
                                         long latencyMillis, String model, FailureKind kind) {
        return new RerankOutcome(fallback, true, reason, latencyMillis, model, 0, List.of(), kind);
    }

    /**
     * 降级结果（重试后<b>仍</b>降级时用这个），<b>未标注</b>失败分类。
     *
     * @param reason                 最后一次失败的原因
     * @param attempts               本次共尝试几次
     * @param previousDegradeReasons 前几次失败的原文（长度 = attempts - 1）
     */
    public static RerankOutcome degraded(List<RetrievalCandidate> fallback, String reason,
                                         long latencyMillis, String model, int attempts,
                                         List<String> previousDegradeReasons) {
        return new RerankOutcome(fallback, true, reason, latencyMillis, model, attempts,
                previousDegradeReasons, FailureKind.UNSPECIFIED);
    }

    /**
     * 同上，但把<b>最后一次失败的分类</b>也说清楚（重排链路内部一律用这个）。
     *
     * @param kind 最后一次失败属于哪一档（前几次必然是 {@link FailureKind#UNPARSABLE}，
     *             因为那是唯一可重试的一档，故不必逐条记录）
     */
    public static RerankOutcome degraded(List<RetrievalCandidate> fallback, String reason,
                                         long latencyMillis, String model, int attempts,
                                         List<String> previousDegradeReasons, FailureKind kind) {
        return new RerankOutcome(fallback, true, reason, latencyMillis, model, attempts,
                previousDegradeReasons, kind);
    }

    /** 本次是否"重试过"（不止一次尝试）——产物读者据此区分"一次成功"与"被重试兜住"。 */
    public boolean retried() {
        return attempts > 1;
    }

    /**
     * 本次是否"<b>失败过、但重试后成功了</b>"。
     *
     * <p>它与 {@link #degraded()} 是两件不同的事，且<b>必须能一眼分清</b>：
     * <ul>
     *   <li>{@code degraded=false} 且本方法 {@code false} ⇒ 真的一次就成（或压根没调模型）；</li>
     *   <li>{@code degraded=false} 且本方法 {@code true} ⇒ <b>重试才成的</b>，
     *       说明链路这一刻不稳，只是被重试兜住了——⛔ 不许读成"链路稳定"；</li>
     *   <li>{@code degraded=true} ⇒ 重试后仍降级（本方法必为 false）。</li>
     * </ul>
     * 判定写成方法而不是让每个读者自己拼 {@code !degraded && attempts > 1}：
     * 拼错不会有任何报错，只会得到一句自信的错误结论（同一个事实有两个写法就必然分叉）。
     */
    public boolean recoveredByRetry() {
        return !degraded && attempts > 1;
    }
}
