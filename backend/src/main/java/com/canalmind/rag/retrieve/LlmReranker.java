package com.canalmind.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * LLM 重排器（第 2 周第 5 项，决策 C-27 的<b>主实现</b>）：让 DeepSeek {@code deepseek-chat}
 * 对融合后的候选重新排序，输出严格 JSON，解析后写进 {@code rerankRank}/{@code rerankScore}。
 *
 * <h2>⚠️ 本实现依赖外部服务（成本与延迟随候选数增长）</h2>
 *
 * <p>这是本项目里第一条<b>把外部大模型放进检索链路</b>的代码，与 embedding（本地 Ollama）
 * 和 BM25（本地 PG）都不同：每次重排都要往 DeepSeek 发一次请求，
 * <ul>
 *   <li><b>成本</b>：提示词长度 ≈ 候选数 × 每候选截断长度。上界 =
 *       {@link #DEFAULT_MAX_CANDIDATES}（50）× {@link #DEFAULT_SNIPPET_CHARS}（400）
 *       = 约 2 万字符（约 1 万 token 量级，具体取决于分词）；
 *   <li><b>延迟</b>：一次问答 = 一次重排请求，实测量级在秒级（见产物 stdout 的
 *       {@code latencyMillis}，<b>不进 JSON</b>，理由见 {@link RerankOutcome}）；
 *   <li><b>可用性</b>：DeepSeek 不可用时检索链路<b>不会挂</b>，而是降级为融合原序
 *       （见下"降级"一节）。正因为会静默地"看起来正常"，降级必须有明确标记。
 * </ul>
 *
 * <h2>降级策略（C-27 原文：解析失败即降级 = 保留融合原序）</h2>
 *
 * <p>以下每一条都<b>返回 {@code degraded=true} 且保留输入原序</b>，绝不丢候选、绝不编造 chunk_id：
 * <ol>
 *   <li><b>候选数超过上限</b>（{@link #DEFAULT_MAX_CANDIDATES} = 50）：<b>拒绝调用</b>模型
 *       （不是"先截断再调用"——截断会让排序只在一部分候选里进行，而产物看起来与正常重排无异）；</li>
 *   <li><b>模型调用超时</b>（{@link #DEFAULT_TIMEOUT} = 90s，{@link #withTimeout} 可调，
 *       环境变量 {@code CANALMIND_RERANK_TIMEOUT_SECONDS} 可覆盖）；</li>
 *   <li><b>模型调用异常</b>（网络不可达、鉴权失败、服务端 5xx 等）；</li>
 *   <li><b>取候选正文片段失败</b>（如接库读 {@code rag_chunk.content} 出错）；</li>
 *   <li><b>候选正文片段全部为空</b>：此时重排只能看标题与条号，收益不可信，
 *       若照常返回会被读成"rerank 无增益"——故显式降级并要求检查 snippet 来源；</li>
 *   <li><b>输出无法解析为约定 JSON</b>（语法错、缺 {@code ranking} 数组、条目缺 {@code chunk_id}）；</li>
 *   <li><b>输出引用了候选集之外的 chunk_id</b>（编造 id 是模型的一种典型失败模式，
 *       一旦出现就说明这次输出整体不可信，故<b>整次</b>降级而不是"忽略那条"）。</li>
 * </ol>
 *
 * <p>可以容忍、<b>不</b>降级的情况（写在类型注释里，免得被当成漏判）：
 * <ul>
 *   <li>模型<b>漏掉</b>部分候选 → 漏掉的按<b>输入原序追加</b>在末尾
 *       （{@code rerankScore} 为 null，表示"模型没给分"，而不是 0 分）；</li>
 *   <li>模型给出<b>重复</b> chunk_id → 只取首次出现，后者忽略；</li>
 *   <li>模型给出<b>相同分数</b> → 按模型给出的顺序稳定排列（不引第二判据：
 *       候选顺序是模型输出的一部分，重排后仍保持它是"忠实"，而 RRF 那一层
 *       才需要 chunkId 决胜，因为那里的并列来自算法本身而非模型）；</li>
 *   <li>模型输出被 markdown 代码围栏包住 → 见 {@link #parseRankingJson}</li>
 * </ul>
 *
 * <h2>⚠️ 有界重试：只在"可重试原因"上重试（2026-09-21 补，B4 配套）</h2>
 *
 * <p>实测背景：这条链路<b>会偶发降级</b>——同一查询 4 次真实调用里 1 次降级
 * （{@code JSON 语法错 … column: 546}）、3 次成功。降级率约 25% 时跑 40 条题，
 * 约 10 条会落到"融合原序"兜底，于是指标<b>同时混了"重排效果"与"兜底效果"、不可解读</b>。
 * 故加了有限次数的重试（默认 {@link #DEFAULT_MAX_RETRIES} = 2，可由
 * {@code RetrievalQueryCli --rerank-retry} 调）。
 *
 * <p><b>哪些原因才重试</b>（逐条写死在分支里，不做字符串匹配——那种判断改一个字就静默失效）：
 * <table border="1">
 *   <caption>重试与否的逐条口径</caption>
 *   <tr><th>降级原因</th><th>重试</th><th>理由</th></tr>
 *   <tr><td>输出无法解析为约定 JSON（含被 token 上限截断）</td><td><b>是</b></td>
 *       <td>模型这一轮"没吐好"，换个样本很可能就好——这正是实测中 ~25% 的那一类</td></tr>
 *   <tr><td>调用超时</td><td>否</td>
 *       <td>重试会再等一整个 {@link #DEFAULT_TIMEOUT}（当前 90 s，取值依据见其 javadoc），
 *           40 题整批可能被拖到小时级；
 *           且超时多半是模型/网关真的慢，不是随机抖动</td></tr>
 *   <tr><td>调用失败（网络 / 鉴权 401 / 服务端 5xx）</td><td>否</td>
 *       <td>⛔ <b>与本项目"缺 key 不得静默降级"同一条纪律</b>：
 *           重试会把"key 过期""base-url 写错""网络不通"掩盖成"重试了几次就好了"，
 *           而真故障需要人去看、去修。缺 key 的检查另在
 *           {@link LlmRerankerFactory#requireApiKey}（连库之前就报错）</td></tr>
 *   <tr><td>返回空正文</td><td>否</td>
 *       <td>空正文是<b>确定性</b>失败（实测 max_tokens 被推理吃光时 2/2 复现），
 *           同一提示词再来一次只会再花一次钱；它已单独成一档并带 finishReason/token 旁证</td></tr>
 *   <tr><td>候选超上限 / 正文片段全空 / 取片段失败</td><td>否</td>
 *       <td>这些在<b>调模型之前</b>就决定了，重试一万次结果相同</td></tr>
 *   <tr><td>输出引用了候选集之外的 chunk_id</td><td>否</td>
 *       <td>它<b>已经解析成功</b>，属于"模型服从性"而不是"输出坏了"；
 *           本次刻意只放宽到"无法解析/被截断"两类（命令行的 {@code --rerank-retry}
 *           说明里也这么写），不擅自扩大</td></tr>
 *   <tr><td>被中断（InterruptedException）</td><td>否</td>
 *       <td>调用方要求停，重试等于把中断吃掉</td></tr>
 * </table>
 *
 * <p>⚠️ <b>「不重试」与「一次就成」是两件不同的事</b>：{@code attempts=1} 既可能是
 * "一次就成了"，也可能是"这一档压根不重试"（空正文 / 超时 / 鉴权失败 / 候选超上限…），
 * 还可能是"重试被 {@code --rerank-retry 0} 关掉了"。2026-09-21 的真实试点就出过
 * stdout 上一行写「1 次（一次成功）」、下一行紧跟降级警告的自相矛盾。
 * 故上表每一行都对应一个<b>机器可读</b>的分类取值 {@link RerankOutcome.FailureKind}
 * （它同时给出 {@code retryable} 与"为什么没重试"），
 * ⛔ 不许让读者去猜 {@code degradeReason} 的措辞、更不许用字符串匹配来判断。
 *
 * <p>⚠️ <b>重试过就必须看得出来</b>：重试后成功不等于"没重试过"。
 * 全程记录 {@code attempts} 与 {@code previousDegradeReasons}
 * （见 {@link RerankOutcome}），否则重试会把"链路不稳"掩盖成"链路正常"。
 *
 * <h2>提示词里给什么</h2>
 *
 * <p>每条候选只给 {@code chunk_id} + 溯源（文档标题/条号）+ <b>正文片段</b>，
 * 正文片段由 {@link ChunkSnippetProvider} 提供（生产路径接 {@code rag_chunk.content}），
 * 并<b>逐条截断到 {@link #DEFAULT_SNIPPET_CHARS} = 400 字符</b>、
 * 把换行折成空格（换行会让提示词里出现大量空白行，稀释有效信息）。
 * 截断处加省略号，让模型知道正文被截断，而不是以为句子到此为止。
 *
 * <p>⛔ 不给 chunk 的内部编号之外的任何可变信息（时间、运行序号）：
 * 提示词必须是<b>同一输入得到同一串字符</b>，否则"这次结果不一样"就分不清
 * 是模型波动还是提示词变了。
 *
 * <h2>模型接入：与既有 chat 链路同源</h2>
 *
 * <p>模型调用通过 {@link PromptCompleter} 这道缝进来，生产实现见
 * {@link #forChatClient}（用 Spring AI 的 {@link ChatClient}，与
 * {@code ChatStreamController} 走的是同一套 Spring AI + DeepSeek 配置，
 * 不引入任何新依赖），CLI 侧由 {@code LlmRerankerFactory} 从环境变量装配。
 * 缝的存在是为了让"解析失败/超时/网络错/未知 id"这些<b>外部依赖的失败路径</b>
 * 能用桩稳定覆盖——它们恰恰是最容易写错、又最难用真实服务复现的部分。
 */
public final class LlmReranker implements Reranker {

    /** 重排器名（写进评测产物 {@code channel} 字段，如 {@code rrf+llm-rerank}）。 */
    public static final String NAME = "llm-rerank";

    /**
     * 送进提示词的候选数上限。
     *
     * <p>超过就拒绝并降级。50 的依据：提示词长度与候选数成正比，
     * 而本项目 {@code fusionTopK} 默认 30，留一档余量给"临时调大 fusionTopK"的试验；
     * 同时它也是"一次请求的提示词大概几千 token"这条经验边界的粗估。
     */
    public static final int DEFAULT_MAX_CANDIDATES = 50;

    /** 每候选正文片段截断上限（字符，不是字节）。见类注释"提示词里给什么"。 */
    public static final int DEFAULT_SNIPPET_CHARS = 400;

    /**
     * 单次重排调用的等待上限。超时即降级（放弃等待，见 {@link #callWithTimeout}）。
     *
     * <p><b>取值 90 s（2026-09-21 由用户依实测证据拍定，原值 30 s）</b>：30 s 那条线
     * 正好压在实测均值上，会把大量<b>本来能成功</b>的调用判成超时降级，
     * 而产物里的表现与「重排没效果」<b>完全一样</b>（都是降级 + 保留融合原序）——
     * 这正是最该避免的那种「没跑成看起来像没效果」。
     *
     * <p>实测依据（{@code rerankTopN=30} 档：30 条候选、提示词约 8.7～9.6k 字符，
     * 40 题整批的真实调用统计）：单题重排耗时
     * <b>最小 21.6 s / 均值 29.4 s / 最大 43.5 s</b>——
     * 即 30 s 这条线正落在分布中间，而不是"安全余量"。
     * 更早的同档实测同样是秒级偏慢：候选 5 条、提示词 1.2k 字符 → 8.6 s；
     * 候选 30 条、提示词 14k 字符 → 14～16 s（合成题面）。
     *
     * <p>⚠️ 本轮还实测到一次<b>成功</b>的重排耗时 <b>29.875 s</b>——离 30 s 上限只差 0.1 秒。
     * 这类调用成与不成只取决于网关抖动，而判成超时之后，产物与"重排本来就没用"无法区分。
     *
     * <p><b>90 s 的依据</b>：≈ 实测最大值（43.5 s）的 2 倍，给网关抖动留出余量，
     * 同时仍远小于「40 题整批」的量级。它属<b>实验条件</b>之一，
     * 故可由环境变量 {@code CANALMIND_RERANK_TIMEOUT_SECONDS} 覆盖
     * （解析与非法取值的处置见 {@code LlmRerankerFactory#resolveTimeoutSeconds}），
     * 不必改代码重编译。
     *
     * <p>⚠️ 超时<b>不重试</b>（见类注释的重试口径表）：重试要再等一整个上限，
     * 而且超时多半是模型/网关真的慢，不是随机抖动。
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);

    /**
     * 重试次数上限的默认值：<b>2</b>（即最多 1 次首发 + 2 次额外尝试 = 最多 3 次请求）。
     *
     * <p>取 2 的依据是实测降级率 ~25%：只重试 1 次时"两次都坏"的概率约 6%（40 题里仍有 2～3 条），
     * 重试 2 次降到约 1.6%（40 题里 0～1 条）。再往上收益迅速变小
     * （3 次 → 0.4%），而每多一次就多一次真实的模型调用与等待。
     *
     * <p>⚠️ 重试<b>不能</b>把"链路不稳"改写成"链路正常"：产物里带
     * {@code rerankAttempts} 与 {@code previousDegradeReasons}，重试过的题一眼可见。
     */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** UTF-8 换行/回车用计算字符，不用转义字面量（AGENTS.md §7.2 第 1 条）。 */
    private static final char LF_CHAR = (char) 10;
    private static final char CR_CHAR = (char) 13;
    private static final String LF = String.valueOf(LF_CHAR);
    private static final String CR = String.valueOf(CR_CHAR);

    /** 归一化要"先收 CRLF、再处理落单 CR"，顺序不可交换（AGENTS.md §7.2 第 3 条）。 */
    private static final String CRLF = String.valueOf(new char[]{CR_CHAR, LF_CHAR});

    /** 截断标记：让模型知道正文被截断了（U+2026）。 */
    private static final String ELLIPSIS = String.valueOf((char) 0x2026);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 模型调用的最小缝：送一段提示词，拿回模型原始文本。
     *
     * <p>为什么不直接依赖 {@link ChatClient} 的链式 API：链式调用无法用简单桩替换，
     * 而本类<b>最需要测试的恰恰是"模型给回来的东西不合法时怎么办"</b>。
     * 用一个方法把外部依赖收敛到一处，降级路径就能被逐条稳定覆盖。
     *
     * <p>⚠️ <b>光有正文不够</b>（2026-09-21 补）：正文之外的 {@code finish_reason} 与 token 用量
     * 是区分"<b>被 token 上限截断</b>"与"<b>模型吐了非法 JSON</b>"的唯一依据——
     * 两者都表现为"解析失败"，处置却完全不同（一个要调 {@code maxTokens}，一个要改提示词）。
     * 故新增 {@link #completeWithEvidence}；生产实现
     * （{@code LlmRerankerFactory.deepSeekCompleter}）走 {@code chatModel.call(Prompt)}
     * 拿完整 {@link ChatResponse} 覆写它，⛔ 不要退回
     * {@code client.prompt().user(p).call().content()}（那条路只给得回字符串）。
     *
     * <p>做成 default 方法而不是直接改签名：既有的 lambda 桩（测试与自检）仍然可用，
     * 不必为了一个新字段把所有桩都改成匿名类。
     */
    @FunctionalInterface
    public interface PromptCompleter {

        /** @return 模型返回的原始文本（可能含代码围栏、前后解释文字） */
        String complete(String prompt) throws Exception;

        /**
         * 同上，但把 {@code finish_reason} 与 token 用量一并带出。
         *
         * <p>默认实现退化成"只有正文"（{@code finishReason} 与 token 均为 null）——
         * 此时 {@link LlmReranker} 会把解析失败一律记成"模型输出非法 JSON"，
         * 也就是说<b>截断这一档会退化不可辨</b>。故生产实现必须覆写本方法。
         */
        default Completion completeWithEvidence(String prompt) throws Exception {
            return new Completion(complete(prompt), null, null, null);
        }
    }

    /**
     * 一次模型应答：正文 + <b>旁证</b>（{@code finish_reason} 与 token 用量）。
     *
     * <p>旁证不是"锦上添花"：{@code finish_reason=length} 说明这次输出是<b>被截断</b>的，
     * 而截断的输出必然解析不出完整 JSON——把它与"模型吐了非法 JSON"混成一句，
     * 排查方向就会整个偏掉（该调额度却去改提示词）。
     *
     * @param text             模型返回的原始文本
     * @param finishReason     结束原因（{@code stop} / {@code length} …）；
     *                         拿不到时为 null（如只实现了 {@link PromptCompleter#complete} 的桩）
     * @param promptTokens     提示词 token 用量；未知时为 null
     * @param completionTokens 补全 token 用量；<b>被截断时它就是打到上限的那个数</b>；未知时为 null
     * @param reasoningTokens  补全里花在<b>思考</b>上的 token（2026-09-21 补，见 {@link #reasoningTokensOf}）；
     *                         未知时为 null。<b>它计入 {@code maxTokens} 预算</b>，故截断时它是关键旁证：
     *                         推理吃掉的越多，留给正文的越少
     */
    public record Completion(String text, String finishReason,
                             Integer promptTokens, Integer completionTokens,
                             Integer reasoningTokens) {

        /**
         * 不带推理 token 用量的四参构造（既有桩与自检都在用）。
         *
         * <p>保留它是刻意的：新增一个字段不该逼着所有桩改写——
         * 那些桩本来就拿不到推理用量，"未知"才是它们的真话（而不是 0）。
         */
        public Completion(String text, String finishReason,
                          Integer promptTokens, Integer completionTokens) {
            this(text, finishReason, promptTokens, completionTokens, null);
        }
    }

    /**
     * 模型<b>调通了、但正文是空的</b>（生产实现里由 {@link PromptCompleter} 抛出）。
     *
     * <p>为什么要单独一个类型、而不是让它走进"解析失败"：
     * 空正文与"输出格式不合法"的<b>处置完全不同</b>——
     * 前者是<b>额度/服务端</b>问题（调 maxTokens、调小候选数、换模型），
     * 后者是<b>提示词/模型服从性</b>问题（改提示词、加容错）。
     * 2026-09-21 真实运行踩到过：默认档（候选 30 条）<b>确定性</b>返回空正文，
     * 而降级提示只写「模型返回了空文本」，既看不出原因、也看不出该改哪里，
     * 为此额外花了一次诊断调用才把原因定位到 max_tokens（见下方 catch 里的说明）。
     *
     * <p>故实现方<b>必须把已知的旁证</b>（finishReason / token 用量）塞进 {@code message}，
     * 让下一次遇到的人不必再复现一遍。
     */
    public static class BlankCompletionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BlankCompletionException(String message) {
            super(message);
        }
    }

    /**
     * 取候选正文片段（生产实现接 {@code rag_chunk.content}）。
     *
     * <p>⚠️ 为什么需要它、而不是把正文放进 {@link RetrievalCandidate}：
     * 候选会在融合阶段被合并、在 stageTrace 里被反复记录，若带上正文本体会让
     * 内存与日志都膨胀；更重要的是候选是<b>召回与融合的产物</b>，
     * 而正文是<b>展示/重排时才需要</b>的数据。用一个按需查询的提供者，
     * 边界更清楚（也便于第 4 周换成"取父块全文"之类的策略）。
     */
    @FunctionalInterface
    public interface ChunkSnippetProvider {

        /** @return 该 chunk 的正文；没有可用正文时返回 null 或空串 */
        String snippet(String chunkId);

        /**
         * 显式表示"本批没有正文可用"。
         *
         * <p>用它会触发<b>降级</b>：只看标题与条号的 LLM 重排，收益不可信，
         * 照常返回会被读成"rerank 没效果"。CLI 默认接库，不用这个常量。
         */
        ChunkSnippetProvider NONE = chunkId -> null;
    }

    private final PromptCompleter completer;
    private final ChunkSnippetProvider snippets;
    private final String modelName;
    private final Duration timeout;
    private final int maxCandidates;
    private final int snippetChars;
    private final int maxRetries;
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.rag.rerank}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    /** 用默认上限/超时/截断长度/重试次数。 */
    public LlmReranker(PromptCompleter completer, ChunkSnippetProvider snippets, String modelName) {
        this(completer, snippets, modelName, DEFAULT_TIMEOUT, DEFAULT_MAX_CANDIDATES,
                DEFAULT_SNIPPET_CHARS, DEFAULT_MAX_RETRIES, ObservationRegistry.NOOP);
    }

    /** 同上，只是把超时/上限/截断长度也显式给出（重试次数仍取默认）。 */
    public LlmReranker(PromptCompleter completer, ChunkSnippetProvider snippets, String modelName,
                       Duration timeout, int maxCandidates, int snippetChars) {
        this(completer, snippets, modelName, timeout, maxCandidates, snippetChars,
                DEFAULT_MAX_RETRIES, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 第 559 行的 {@code ai.rag.rerank}）。
     *
     * <p>⚠️ 既有两个 public 构造器保持不变、一律委托给 {@link ObservationRegistry#NOOP}
     * （使用方：{@code LlmRerankerFactory} 与 {@code LlmRerankerTest} 的十余处桩装配），
     * **旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public LlmReranker(PromptCompleter completer, ChunkSnippetProvider snippets, String modelName,
                       Duration timeout, int maxCandidates, int snippetChars,
                       ObservationRegistry observationRegistry) {
        this(completer, snippets, modelName, timeout, maxCandidates, snippetChars,
                DEFAULT_MAX_RETRIES, observationRegistry);
    }

    /** 全参构造器（唯一给字段赋值的地方）。 */
    private LlmReranker(PromptCompleter completer, ChunkSnippetProvider snippets, String modelName,
                        Duration timeout, int maxCandidates, int snippetChars, int maxRetries,
                        ObservationRegistry observationRegistry) {
        this.completer = Objects.requireNonNull(completer, "completer");
        this.snippets = Objects.requireNonNull(snippets, "snippets");
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 不得为空：它要写进产物，说明这批分数是谁给的");
        }
        this.modelName = modelName;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正，实际：" + timeout);
        }
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates 必须 >= 1，实际：" + maxCandidates);
        }
        if (snippetChars < 1) {
            throw new IllegalArgumentException("snippetChars 必须 >= 1，实际：" + snippetChars);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException(
                    "maxRetries 必须 >= 0（0 = 不重试，只发一次请求），实际：" + maxRetries
                            + "：负数会让重试循环的终止条件变得不可读");
        }
        this.maxCandidates = maxCandidates;
        this.snippetChars = snippetChars;
        this.maxRetries = maxRetries;
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    /**
     * 用 Spring AI 的 {@link ChatClient} 构造（与既有 chat 链路同源）。
     *
     * <p>接的是 {@code ChatModel}（DeepSeek starter 提供），故 {@code api-key} / {@code base-url} /
     * {@code chat.options.model} 全部沿用 {@code application.yml} 的 {@code spring.ai.deepseek.*}，
     * <b>不新开一套配置</b>——两套配置必然分叉，而分叉的表现是"重排用的模型与问答用的不是同一个"，
     * 不会报错。
     *
     * <p>⚠️ 走 {@code call().chatResponse()} 而<b>不是</b> {@code call().content()}：
     * 后者只给得回正文字符串，拿不到 {@code finish_reason} 与 token 用量，
     * 于是"被截断"与"吐了非法 JSON"就分不出来了（见 {@link PromptCompleter#completeWithEvidence}）。
     */
    public static LlmReranker forChatClient(ChatClient chatClient, ChunkSnippetProvider snippets,
                                            String modelName) {
        Objects.requireNonNull(chatClient, "chatClient");
        return new LlmReranker(new PromptCompleter() {

            @Override
            public String complete(String prompt) throws Exception {
                return completeWithEvidence(prompt).text();
            }

            @Override
            public Completion completeWithEvidence(String prompt) throws Exception {
                ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
                return completionOf(response, prompt);
            }
        }, snippets, modelName);
    }

    /** 同 {@link #forChatClient}，只是从 {@link ChatModel} 起手（便于上层自行组装 ChatClient 的 advisor）。 */
    public static LlmReranker forChatModel(ChatModel chatModel, ChunkSnippetProvider snippets,
                                           String modelName) {
        Objects.requireNonNull(chatModel, "chatModel");
        return forChatClient(ChatClient.create(chatModel), snippets, modelName);
    }

    /**
     * 从 {@link ChatResponse} 里取出正文与旁证（{@code finish_reason} / token 用量）。
     *
     * <p>正文为空白时抛 {@link BlankCompletionException}，旁证写进异常消息——
     * 空正文<b>不能</b>落回"解析失败"那一档（见该异常的注释）。
     *
     * <p>做成公开静态方法是为了让生产装配（{@code LlmRerankerFactory}）与
     * {@link #forChatClient} 走<b>同一条</b>取值口径：两处各写一遍必然分叉，
     * 而分叉的表现是"CLI 里有 finishReason、Spring 链路里没有"，不会报错。
     */
    public static Completion completionOf(ChatResponse response, String prompt) {
        Generation generation = response == null ? null : response.getResult();
        String text = generation == null || generation.getOutput() == null
                ? null
                : generation.getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new BlankCompletionException(describeBlank(response, prompt));
        }
        String finishReason = generation.getMetadata() == null
                ? null : generation.getMetadata().getFinishReason();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new Completion(text, finishReason,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                reasoningTokensOf(usage));
    }

    /**
     * 从 Spring AI 的 {@link Usage} 里取<b>推理 token</b> 用量；取不到就返 null。
     *
     * <p>为什么要单独做这件事：本项目用的是<b>推理模型</b>（实测
     * {@code completion_tokens=1456} 里 {@code reasoning_tokens=875}），
     * 而<b>思考 token 与正文共享 {@code maxTokens} 预算</b>。于是
     * "被 token 上限截断"这件事的量化依据不止 {@code completionTokens}
     * （它只告诉你"打满了"），还要看<b>打满的那部分里有多少是思考</b>——
     * 全被思考吃掉 ⇒ 该调大额度；思考很少却仍截断 ⇒ 该缩短输出契约或候选数。
     *
     * <p>取值口径：Spring AI 的 {@link Usage} 只暴露 prompt/completion/total，
     * 推理用量藏在厂商原样对象里，只有 {@link Usage#getNativeUsage()} 拿得到它。
     * 该对象的形态<b>不受本项目控制</b>（不同网关给 {@code Map} 或 Jackson {@code JsonNode}），
     * 故两种都认；两条候选路径（顶层 {@code reasoning_tokens} 与
     * {@code completion_tokens_details.reasoning_tokens}）都试，取不到返 null。
     *
     * <p>⛔ 取不到时<b>返 null 而不是 0</b>：0 会让人读成"没有思考"，
     * 而真相是"这一层没告诉我们"——两者指向的结论相反。
     */
    static Integer reasoningTokensOf(Usage usage) {
        Object native_ = usage == null ? null : usage.getNativeUsage();
        if (native_ == null) {
            return null;
        }
        Integer direct = intAt(native_, "reasoning_tokens");
        if (direct != null) {
            return direct;
        }
        Object details = childAt(native_, "completion_tokens_details");
        return details == null ? null : intAt(details, "reasoning_tokens");
    }

    /** 按 key 取子节点：{@code Map} 与 Jackson {@code JsonNode} 两种形态都认。 */
    private static Object childAt(Object node, String key) {
        if (node instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (node instanceof JsonNode json && json.isObject()) {
            JsonNode child = json.get(key);
            return child == null || child.isNull() ? null : child;
        }
        return null;
    }

    /** 按 key 取整数：接受数字与数字字符串；取不到（或不是整数）返 null。 */
    private static Integer intAt(Object node, String key) {
        Object value = childAt(node, key);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode json) {
            return json.isNumber() ? json.intValue() : (json.isTextual() ? parseOrNull(json.textValue()) : null);
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value instanceof String s ? parseOrNull(s) : null;
    }

    private static Integer parseOrNull(String raw) {
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 给"空正文"拼一句带旁证的说明（finishReason + token 用量 + 提示词规模）。
     *
     * <p>这三样是定位这类问题的<b>最小充分信息</b>：
     * {@code finish_reason=length} ⇒ 被截断（额度问题，调 maxTokens）；
     * {@code stop} 但正文为空 ⇒ 网关/服务端问题（该查网关）；
     * prompt 用量异常大 ⇒ 提示词本身有问题（候选太多或片段太长）。
     */
    private static String describeBlank(ChatResponse response, String prompt) {
        StringBuilder sb = new StringBuilder();
        Generation generation = response == null ? null : response.getResult();
        String finish = generation == null || generation.getMetadata() == null
                ? "未知" : String.valueOf(generation.getMetadata().getFinishReason());
        sb.append("finish_reason=").append(finish);
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage != null) {
            sb.append("，prompt ").append(usage.getPromptTokens())
                    .append(" tokens / completion ").append(usage.getCompletionTokens())
                    .append(" tokens");
            Integer reasoning = reasoningTokensOf(usage);
            if (reasoning != null) {
                sb.append("（其中推理 ").append(reasoning).append(" tokens）");
            }
        }
        sb.append("，提示词 ").append(prompt == null ? 0 : prompt.length()).append(" 字符");
        return sb.toString();
    }

    /** 调换等超时（返回新实例）：重排的等待上限是实验条件之一，不该只能靠改常量。 */
    public LlmReranker withTimeout(Duration newTimeout) {
        // ⚠️ 必须把 observationRegistry 一起带过去：漏掉它不会有任何报错，
        //    只表现为"调过一次超时之后，重排的 Span 就再也不出现了"（静默丢埋点）。
        return new LlmReranker(completer, snippets, modelName, newTimeout, maxCandidates,
                snippetChars, maxRetries, observationRegistry);
    }

    /**
     * 调换重试次数上限（返回新实例）。
     *
     * <p>重试次数是<b>实验条件</b>之一（它直接决定"降级率"这个观测量），
     * 故必须能从命令行调，而不是只写在常量里。
     */
    public LlmReranker withMaxRetries(int newMaxRetries) {
        return new LlmReranker(completer, snippets, modelName, timeout, maxCandidates,
                snippetChars, newMaxRetries, observationRegistry);
    }

    @Override
    public String name() {
        return NAME;
    }

    /** 本次使用的模型标识。 */
    public String modelName() {
        return modelName;
    }

    /** 本次的等待上限（毫秒）。 */
    public long timeoutMillis() {
        return timeout.toMillis();
    }

    /** 本次的重试次数上限（不含首发；总请求次数上界 = {@code maxRetries + 1}）。 */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * 重排一批候选。
     *
     * <p>规格书 §7.2 第 559 行要求这一步的 Span（{@code ai.rag.rerank}）记
     * "<b>重排前后顺序对比</b>"。本项目把它记成两个属性：
     * <ul>
     *   <li><b>低基数</b> {@code ai.rag.rerank.changed} = {@code true}/{@code false}
     *       ——"这次重排到底改没改顺序"。它是**封闭的两值**，故能进 Metrics：
     *       "重排改变顺序的比例"因此是一句能算出来的话；</li>
     *   <li><b>高基数</b> {@code ai.rag.result_count} = **重排后**条数。</li>
     * </ul>
     *
     * <p>⚠️ <b>为什么降级也算 changed=false</b>：降级路径的产物是"输入的融合原序前缀"
     * （见 {@code fallback(...)} 与类注释的降级口径），顺序确实没变——
     * 如实报 false。于是"降级率"可以从 {@code changed=false} 与
     * {@code RerankOutcome.degraded()} 两个方向交叉核对。
     *
     * <p>⛔ <b>不把前后两份候选列表写进属性</b>：那是正文级体积，且无法聚合
     * （同 {@code AiSpans.KEY_RAG_RERANK_CHANGED} 的注释）。
     *
     * <p>⚠️⚠️ 一律 {@code observe(...)}，⛔ 不手工 {@code start()}/{@code stop()}
     * （实测见 {@code docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md}）。
     */
    @Override
    public RerankOutcome rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        Observation observation = AiObservation.start(AiSpans.RAG_RERANK, observationRegistry);
        return observation.observe(() -> {
            List<RetrievalCandidate> in = candidates == null ? List.of() : candidates;
            RerankOutcome outcome = doRerank(query, in, topN);
            AiObservation.lowCardinality(observation, AiSpans.KEY_RAG_RERANK_CHANGED,
                    orderChanged(in, outcome.candidates()) ? "true" : "false");
            AiObservation.highCardinality(observation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(outcome.candidates().size()));
            // ⚠️ 只在**真的降级**时写这个键（C-59，2026-09-22）：本链路会静默降级成
            //    "融合原序"，而降级后的产物与「重排本来就没效果」在数据上**完全一样**
            //    ——本项目已实测过 max_tokens 量程不足导致 3/3 必然降级，却只能被读成
            //    "重排无增益"（见 docs/工程复盘/工程检索类/量程不足会把配置掐死记成能力不足/）。
            //    取值用 FailureKind 的**枚举名**：RerankOutcome 已声明"⛔ 不许让读者去猜
            //    degradeReason 的措辞、更不许用字符串匹配来判断"，故这里不自造措辞。
            if (outcome.degraded()) {
                AiObservation.lowCardinality(observation, AiSpans.KEY_DEGRADE_REASON,
                        outcome.failureKind().name());
            }
            return outcome;
        });
    }

    /**
     * 重排**是否改变了候选顺序**（规格书第 559 行"重排前后顺序对比"的机器可读形式）。
     *
     * <p>判据：把结果序列与"输入序列的前 N 条"逐位比较（N = 结果条数）。
     * 之所以比"前缀"而不是比"等长全序列"，是因为结果本来就被截到 {@code topN}
     * （输入 30 条、输出 10 条是常态）——"输出是不是输入的原序前缀"才是有意义的问法。
     *
     * <p>顺序无关紧要的两种情形都如实为 {@code false}：没有候选可排、以及降级保留原序。
     */
    static boolean orderChanged(List<RetrievalCandidate> in, List<RetrievalCandidate> out) {
        if (out.size() > in.size()) {
            // 候选只可能变少（重排是"排序 + 截断"）。多出来说明上游实现出了问题，
            // 此时如实报"变了"，⛔ 不默默返回 false 把一个 bug 说成"顺序没变"。
            return true;
        }
        for (int i = 0; i < out.size(); i++) {
            if (!Objects.equals(in.get(i).chunkId(), out.get(i).chunkId())) {
                return true;
            }
        }
        return false;
    }

    /** 真正的重排（原 {@code rerank} 的实现）：与埋点分开，便于核对"埋点没动过算法"。 */
    private RerankOutcome doRerank(String query, List<RetrievalCandidate> in, int topN) {
        if (topN < 1) {
            throw new IllegalArgumentException("topN 必须 >= 1，实际：" + topN);
        }
        long started = System.nanoTime();
        if (in.isEmpty()) {
            // 没有候选可排：这不是降级（没坏），只是空输入。不调模型，故延迟记 0。
            return RerankOutcome.ok(List.of(), 0L, modelName);
        }
        if (in.size() > maxCandidates) {
            return RerankOutcome.degraded(fallback(in, topN),
                    "候选数 " + in.size() + " 超过上限 " + maxCandidates
                            + "，拒绝调用重排模型（截断后再调用会让排序只在一部分候选里进行，"
                            + "而产物看起来与正常重排无异）；已保留融合原序。"
                            + "请调小 fusionTopK 或调大上限。",
                    0L, modelName, RerankOutcome.FailureKind.TOO_MANY_CANDIDATES);
        }

        String prompt;
        boolean anySnippet;
        try {
            List<String> snippetList = new ArrayList<>(in.size());
            anySnippet = false;
            for (RetrievalCandidate c : in) {
                String s = foldToOneLine(snippets.snippet(c.chunkId()));
                if (!s.isEmpty()) {
                    anySnippet = true;
                }
                snippetList.add(truncate(s, snippetChars));
            }
            if (!anySnippet) {
                return RerankOutcome.degraded(fallback(in, topN),
                        "候选正文片段全部为空：只看标题与条号的 LLM 重排收益不可信，"
                                + "照常返回会被读成「rerank 没效果」。请检查片段来源"
                                + "（生产路径应为 rag_chunk.content）。",
                        0L, modelName, RerankOutcome.FailureKind.EMPTY_SNIPPETS);
            }
            prompt = buildPrompt(query, in, snippetList);
        } catch (RuntimeException e) {
            return RerankOutcome.degraded(fallback(in, topN),
                    "取候选正文片段失败：" + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    elapsedMillis(started), modelName, RerankOutcome.FailureKind.SNIPPET_FAILED);
        }

        // ── 有界重试（只在"可重试原因"上重试，逐条口径见类注释）───────────────
        // 提示词只构造一次：重试必须复用同一串字符，否则"这次结果不一样"
        // 就分不清是模型波动还是提示词被换了（AGENTS.md 的"每轮只改少量变量"）。
        List<String> previousReasons = new ArrayList<>();
        List<RankedEntry> ranking = null;
        int attempts = 0;
        while (ranking == null) {
            attempts++;
            Attempt attempt = attemptRank(prompt, in);
            if (attempt.ranking() != null) {
                ranking = attempt.ranking();
                break;
            }
            if (!attempt.retryable() || attempts > maxRetries) {
                // 最后一次失败进 degradeReason，前几次进 previousDegradeReasons：
                // 两者都必须在产物里，否则"重试了 3 次仍降级"与"一次就降级"长得一样。
                // ⚠️ 还要带上 attempt.kind()：它是"为什么没重试"的唯一机器可读依据
                //    （attempts=1 既可能是"一次就成"、也可能是"这一档压根不重试"）。
                return RerankOutcome.degraded(fallback(in, topN), attempt.failureReason(),
                        elapsedMillis(started), modelName, attempts, previousReasons, attempt.kind());
            }
            previousReasons.add(attempt.failureReason());
        }

        Map<String, RetrievalCandidate> byId = new LinkedHashMap<>();
        for (RetrievalCandidate c : in) {
            byId.putIfAbsent(c.chunkId(), c);
        }
        // 有分者按分数降序（同分保持模型给出的顺序：List.sort 是稳定排序）；
        // 模型漏掉或没给分的，一律按输入原序追加在后面，rerankScore 留 null
        List<RankedEntry> scored = new ArrayList<>();
        List<String> unscoredInRanking = new ArrayList<>();
        for (RankedEntry entry : ranking) {
            if (entry.score() == null) {
                unscoredInRanking.add(entry.chunkId());
            } else {
                scored.add(entry);
            }
        }
        scored.sort(Comparator.comparingDouble(RankedEntry::score).reversed());

        List<RetrievalCandidate> ordered = new ArrayList<>(in.size());
        List<Double> orderedScores = new ArrayList<>(in.size());
        Set<String> used = new HashSet<>();
        for (RankedEntry entry : scored) {
            if (used.add(entry.chunkId())) {
                ordered.add(byId.get(entry.chunkId()));
                orderedScores.add(entry.score());
            }
        }
        for (String id : unscoredInRanking) {
            if (used.add(id)) {
                ordered.add(byId.get(id));
                orderedScores.add(null);
            }
        }
        for (RetrievalCandidate c : in) {
            if (used.add(c.chunkId())) {
                ordered.add(c);
                orderedScores.add(null);
            }
        }

        List<RetrievalCandidate> result = new ArrayList<>(Math.min(topN, ordered.size()));
        for (int i = 0; i < ordered.size() && i < topN; i++) {
            result.add(ordered.get(i).withRerank(i + 1, orderedScores.get(i)));
        }
        return RerankOutcome.ok(List.copyOf(result), elapsedMillis(started), modelName,
                attempts, previousReasons);
    }

    // ── 单次尝试 ──────────────────────────────────────────────────────────

    /**
     * 一次尝试的结果：要么拿到可用的 {@code ranking}，要么带着<b>原因</b>与<b>分类</b>失败。
     *
     * <p>为什么要单独一个类型而不是直接返回 {@link RerankOutcome}：
     * 重试的决策点在"调模型"这一层，而 {@code RerankOutcome} 是"最终结论"那一层。
     * 混在一起会让重试循环得从产物对象里反读原因，且 {@code attempts} 的语义会被写乱。
     *
     * <p>⚠️ "要不要重试"<b>不再单独存一位</b>，而是从 {@code kind} 推导
     * （{@link RerankOutcome.FailureKind#retryable()}）：两个字段各写一遍必然分叉，
     * 而分叉的表现是"某个原因明明写着不重试、却真的重试了"——不会报错。
     */
    private record Attempt(List<RankedEntry> ranking, String failureReason,
                           RerankOutcome.FailureKind kind) {

        static Attempt ok(List<RankedEntry> ranking) {
            return new Attempt(List.copyOf(ranking), null, RerankOutcome.FailureKind.NONE);
        }

        static Attempt failed(String reason, RerankOutcome.FailureKind kind) {
            return new Attempt(null, reason, kind);
        }

        /** 这次失败值不值得再花一次钱。 */
        boolean retryable() {
            return kind.retryable();
        }
    }

    /**
     * 向模型发起一次调用并把输出归一成 {@code ranking}（含"未知 chunk_id"的整次否决）。
     *
     * <p><b>可重试性逐条写死在分支里</b>（⛔ 不做原因字符串匹配：那种判断改一个字就静默失效）：
     * 只有"输出无法解析（含被 token 上限截断）"这一条是
     * {@link RerankOutcome.FailureKind#UNPARSABLE}（= 唯一 {@code retryable} 的一档），
     * 其余（超时 / 网络 / 鉴权 / 空正文 / 中断 / 编造 id）各自成档，一律不重试。
     *
     * <p>⚠️ 分类是<b>数据</b>：产物与 stdout 靠它说清"为什么没重试"，
     * 而不是靠读 {@code degradeReason} 的措辞（见 {@link RerankOutcome.FailureKind}）。
     */
    private Attempt attemptRank(String prompt, List<RetrievalCandidate> in) {
        Completion completion;
        try {
            completion = callWithTimeout(prompt);
        } catch (TimeoutException e) {
            // ⛔ 不重试：重试要再等一整个超时上限，40 题整批可能被拖到小时级
            return Attempt.failed("重排模型调用超时（等待上限 " + timeout.toMillis() + " ms）"
                    + "：已放弃等待并保留融合原序（⚠️ 本次只是放弃等待，"
                    + "底层 HTTP 调用未必真正中断，仍可能在服务端继续）。"
                    + "⚠️ 超时**不重试**（区别于「输出无法解析」）：重试要再等一整个上限，"
                    + "而且超时多半是模型/网关真的慢，不是随机抖动。",
                    RerankOutcome.FailureKind.TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BlankCompletionException blank) {
                // 空正文单独成一档：它的处置（调额度/调候选数）与"调用失败"（查网络与鉴权）
                // 完全不同，混在一起会让人往错的方向排查。
                // ⛔ 也不重试：实测这是**确定性**失败（max_tokens 被推理吃光时 2/2 复现），
                //    同一提示词再来一次只会再花一次钱。
                return Attempt.failed("重排模型返回了空正文（" + blank.getMessage()
                        + "）：已保留融合原序。"
                        + "⚠️ 最常见的原因是 max_tokens 被「推理 token」吃光——"
                        + "本模型先思考再输出，预算耗尽时 content 为空而 finish_reason=length，"
                        + "于是「没输出」与「没调通」在产物里长得一样。"
                        + "处置：调大 maxTokens（重排侧见 LlmRerankerFactory 的 "
                        + "CANALMIND_RERANK_MAX_TOKENS）、调小 fusionTopK"
                        + "（候选越多、理由越长、推理越久），或换更小的重排模型。"
                        + "⚠️ 空正文**不重试**（确定性失败）。",
                        RerankOutcome.FailureKind.BLANK_COMPLETION);
            }
            // ⛔ 不重试（鉴权 401 / 网络 / 5xx）：重试会把真故障掩盖成"重试几次就好了"，
            //    这与本项目「缺 key 不得静默降级」是同一条纪律。
            return Attempt.failed("重排模型调用失败（网络/鉴权/服务端错误）："
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + "。⚠️ 这类失败**不重试**：重试会把「key 过期 / base-url 写错 / 网络不通」"
                    + "掩盖成「重试几次就好了」，而真故障需要人去看。",
                    RerankOutcome.FailureKind.CALL_FAILED);
        } catch (InterruptedException e) {
            // 中断必须恢复中断位，否则上层（线程池/SSE 取消）会看不到中断
            Thread.currentThread().interrupt();
            return Attempt.failed("重排模型调用被中断（InterruptedException）",
                    RerankOutcome.FailureKind.INTERRUPTED);
        }

        List<RankedEntry> ranking;
        try {
            ranking = parseRankingJson(completion.text());
        } catch (RuntimeException e) {
            // ✅ 唯一可重试的一档：模型这一轮"没吐好"，换一次样本很可能就好
            //    （实测降级率 ~25%，见类注释）。截断与"吐了非法 JSON"在这里分开措辞，
            //    否则产物读者分不出该调 maxTokens 还是该改提示词。
            return Attempt.failed(unparsableReason(completion, e),
                    RerankOutcome.FailureKind.UNPARSABLE);
        }

        Set<String> known = new HashSet<>();
        for (RetrievalCandidate c : in) {
            known.add(c.chunkId());
        }
        List<String> unknown = new ArrayList<>();
        for (RankedEntry entry : ranking) {
            if (!known.contains(entry.chunkId())) {
                unknown.add(entry.chunkId());
            }
        }
        if (!unknown.isEmpty()) {
            // 编造 id 说明这次输出整体不可信 → 整次降级，而不是"只忽略那一条"
            // ⛔ 不重试：它**已经解析成功**，属于"模型服从性"而不是"输出坏了"，
            //    本次刻意只放宽到"无法解析 / 被截断"两类，不擅自扩大重试面。
            return Attempt.failed("重排模型返回了候选集之外的 chunk_id " + unknown
                    + "（共 " + unknown.size() + " 个，输入只有 " + in.size() + " 条）"
                    + "：编造 id 说明本次输出不可信，已整次降级并保留融合原序，"
                    + "绝不按编造的 id 造候选",
                    RerankOutcome.FailureKind.UNKNOWN_CHUNK_ID);
        }
        return Attempt.ok(ranking);
    }

    /**
     * "输出解析不了"这一档的原因文案：<b>先看 {@code finish_reason}</b>，区分两种完全不同的病。
     *
     * <p>不区分时的后果（本次要修的正事）：截断与"模型不听话"共用一句"无法解析为约定 JSON"，
     * 而两者的处置相反——前者要<b>调大 {@code maxTokens}</b>（输出被截断，与提示词无关），
     * 后者要<b>改提示词</b>（模型没过输出约束）。产物里分不出来 ⇒ 排查方向整个偏掉。
     *
     * <p>⚠️ 截断这一档的文案里<b>刻意不出现</b>"模型输出非法 JSON"那七个字：
     * 产物读者是靠搜这句话来区分两档的，让它出现在两边等于白分。
     *
     * <p>截断文案里**必须带 token 数**：{@code completionTokens} 就是打到上限的那个数，
     * 它是"到底该把它调多大"的唯一量化依据（只有一句"被截断了"等于没说）。
     *
     * <p>2026-09-21 补 {@code reasoningTokens}：本项目用的是<b>推理模型</b>，
     * 思考 token 与正文<b>共享同一个 {@code maxTokens} 预算</b>。只看
     * {@code completionTokens} 会读成"额度不够"，但真正的原因可能是
     * <b>额度全被思考吃掉</b>（实测一例 {@code completion_tokens=1456} 里
     * {@code reasoning_tokens=875}）——那要调的是额度，而要缩短输出契约/候选数的判断则需要
     * 这两个数一起看。取不到时写"未知"，⛔ 不写 0。
     */
    static String unparsableReason(Completion completion, RuntimeException parseError) {
        if (completion != null && isTruncated(completion.finishReason())) {
            return "重排模型输出被 token 上限截断（finish_reason=" + completion.finishReason()
                    + "，promptTokens=" + tokens(completion.promptTokens())
                    + "，completionTokens=" + tokens(completion.completionTokens())
                    + "，reasoningTokens=" + tokens(completion.reasoningTokens())
                    + "），截断的输出必然不是完整 JSON——病因不是「模型不听话」，"
                    + "调提示词没有用：请调大 maxTokens（重排侧的装配处）"
                    + "或调小 fusionTopK / 缩短候选片段"
                    + "（候选越多、理由越长，越容易写到一半被切）。已保留融合原序。"
                    + "解析器给出的原始报错：" + parseError.getMessage();
        }
        return "重排模型输出无法解析为约定 JSON（模型输出非法 JSON）：" + parseError.getMessage()
                + "，已保留融合原序";
    }

    /** token 数为 null 时写"未知"而不是 0：0 是一个具体事实，未知不是。 */
    private static String tokens(Integer n) {
        return n == null ? "未知" : String.valueOf(n);
    }

    /**
     * {@code finish_reason} 是否表示"被 token 上限截断"。
     *
     * <p>不同网关/厂商的等价写法不止一个（OpenAI 系是 {@code length}，
     * 部分网关写 {@code max_tokens} / {@code max_output_tokens}），故按小写归一后逐个比对——
     * ⛔ 不写 {@code contains("length")} 这类宽判断：{@code finish_reason} 的取值集不受本项目控制，
     * 宽判断会把将来的新取值静默误判成截断，而这正是本次要消灭的那种"分不出来"。
     */
    static boolean isTruncated(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return false;
        }
        String v = finishReason.strip().toLowerCase(Locale.ROOT);
        return v.equals("length")
                || v.equals("max_tokens")
                || v.equals("max_output_tokens")
                || v.equals("token_limit")
                || v.equals("length_limit");
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 降级时的兜底：输入原序的前 topN 条，且<b>不带</b>重排字段。 */
    private static List<RetrievalCandidate> fallback(List<RetrievalCandidate> in, int topN) {
        return List.copyOf(in.size() <= topN ? in : in.subList(0, topN));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 调模型并加等待上限。
     *
     * <p>为什么用"单独一个守护线程 + Future.get(超时)"：Spring AI 的调用是<b>阻塞</b> API，
     * Java 里没有别的方式给它套上限。线程每次调用现建现弃（用完 shutdownNow），
     * 不用共享线程池——共享池在长驻服务里会积压任务，在 CLI 里会留下非守护线程
     * （JVM 退出被拖住，或反过来来不及跑完）。
     */
    private Completion callWithTimeout(String prompt)
            throws TimeoutException, ExecutionException, InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "llm-rerank-call");
            t.setDaemon(true);
            return t;
        });
        try {
            // 用 completeWithEvidence 而不是 complete：正文之外的 finish_reason 与 token 用量
            // 是"被截断"与"吐了非法 JSON"唯一的区分依据（见 PromptCompleter 的注释）
            Future<Completion> future = pool.submit(() -> completer.completeWithEvidence(prompt));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 提示词。
     *
     * <p>候选块用 Jackson 序列化成 JSON，而不是拼字符串：候选正文里可能出现引号、
     * 换行、反斜杠，手工拼装必然出现"某个 chunk 让提示词结构坏掉"，
     * 而症状是模型开始胡说、<b>不报错</b>。
     */
    static String buildPrompt(String query, List<RetrievalCandidate> in, List<String> snippets) {
        List<Object> items = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            RetrievalCandidate c = in.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", c.chunkId());
            item.put("doc", c.docTitle() == null ? c.docId() : c.docTitle());
            item.put("article", c.articleNo() == null ? "" : c.articleNo());
            item.put("snippet", snippets.get(i));
            items.add(item);
        }
        String candidateJson;
        try {
            candidateJson = MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("候选块无法序列化为 JSON", e);
        }
        return "请按与查询的相关性，对下面这批候选重新排序。" + LF
                + LF
                + "查询：" + foldToOneLine(query) + LF
                + LF
                + "候选（共 " + in.size() + " 条；chunk_id 是唯一标识，snippet 可能被截断）：" + LF
                + candidateJson + LF
                + LF
                + "只输出一个 JSON 对象，不要解释、不要 markdown 代码围栏，形如：" + LF
                + OUTPUT_SHAPE + LF
                + "规则：" + LF
                + "1. 只能使用上面出现过的 chunk_id，不得编造，也不要遗漏；" + LF
                + "2. score 取 0 到 1，越大越相关，请给出区分度（不要所有条目都给同一个分）；" + LF
                + "3. ranking 按 score 从高到低排列。" + LF;
    }

    /** 期望的输出形状。用文本块写，这样 JSON 里的双引号不必转义（避免转义层出错）。 */
    static final String OUTPUT_SHAPE = """
            {"ranking":[{"chunk_id":"<chunk_id>","score":0.87,"reason":"<一句话理由>"}]}""";

    /**
     * 解析模型输出。
     *
     * <p>只做<b>一件</b>容错：从第一个 <code>{</code> 到最后一个 <code>}</code> 截取，
     * 以容忍 {@code ```json} 围栏或前后的一句解释。⛔ 不做别的容错
     * （不补引号、不补逗号、不把中文引号当引号）：那些"猜修"会让"模型输出坏了"
     * 变成"我们猜对了"，而猜错时<b>不会报错</b>，只会得到一份看起来正常的错误排序。
     *
     * @return 按模型给出的数组顺序排列的条目；{@code score} 缺失或无法解析时为 null
     *         （留 null 由调用方按"模型没给分"处理，⛔ 不要臆造成 0 分）
     * @throws IllegalArgumentException 语法错、缺 {@code ranking} 数组、条目缺 {@code chunk_id}
     */
    static List<RankedEntry> parseRankingJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型返回了空文本");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("在模型输出里找不到 JSON 对象边界（{ 与 }）");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 语法错：" + e.getMessage(), e);
        }
        JsonNode ranking = root.get("ranking");
        if (ranking == null || !ranking.isArray()) {
            throw new IllegalArgumentException("缺少 ranking 数组（顶层键：" + fieldNames(root) + "）");
        }
        List<RankedEntry> entries = new ArrayList<>(ranking.size());
        for (int i = 0; i < ranking.size(); i++) {
            JsonNode item = ranking.get(i);
            String id = item.path("chunk_id").isTextual() ? item.path("chunk_id").asText() : null;
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ranking 第 " + (i + 1) + " 项缺少 chunk_id");
            }
            entries.add(new RankedEntry(id, readScore(item.get("score"))));
        }
        return entries;
    }

    /** score 允许是数字或数字字符串（模型偶尔会加引号）；其它一律当"没给分"。 */
    private static Double readScore(JsonNode score) {
        if (score == null || score.isNull()) {
            return null;
        }
        double v;
        if (score.isNumber()) {
            v = score.asDouble();
        } else if (score.isTextual()) {
            try {
                v = Double.parseDouble(score.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        // NaN/Infinity 不能写进 JSON、也不能比大小，一律当"没给分"
        return Double.isFinite(v) ? v : null;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * 把任意文本折成单行（换行折成空格）。
     *
     * <p><b>查询与候选正文都要过这一道</b>：提示词里既有查询也有正文，只要有一处带换行，
     * 提示词的分节结构就会被数据破坏——模型看到的是"多出来的一行正文"，
     * 而输出仍然合法、不报错，只是排序依据变了。
     *
     * <p>顺序必须先收 CRLF 再处理落单 CR（AGENTS.md §7.2 第 3 条）：反过来会让每处
     * 换行多出一个空格，同样不报错。
     */
    static String foldToOneLine(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(CRLF, " ").replace(CR, " ").replace(LF, " ").strip();
    }

    static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + ELLIPSIS;
    }

    /**
     * 模型给出的一条排序。
     *
     * @param chunkId 模型给出的 chunk_id
     * @param score   分数；模型没给或给得不合法时为 {@code null}
     */
    record RankedEntry(String chunkId, Double score) {
    }
}
