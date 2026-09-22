package com.canalmind.rag.retrieve;

import com.canalmind.chat.DeepSeekHttpErrorHandler;
import com.canalmind.observability.SpanTreeExporter;
import com.canalmind.rag.retrieve.LlmReranker.ChunkSnippetProvider;
import com.canalmind.rag.retrieve.LlmReranker.PromptCompleter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.retry.RetryUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 按外部配置装配 {@link LlmReranker}（第 2 周第 5 项，C-27）。
 *
 * <h2>为什么要有这一层（与 {@code EmbeddingClientFactory} 同一个理由）</h2>
 *
 * <p>应用侧（{@code application.yml} 的 {@code spring.ai.deepseek.*}）与命令行侧
 * （独立 {@code main}，<b>没有 Spring 上下文</b>）必须解析出<b>完全相同的模型三元组</b>
 * （base-url / model / api-key）。两处各读一遍配置，早晚出现"问答读的是 A、重排读的是 B"，
 * 而<b>不会报错</b>——只会让重排的分数与问答的依据来自不同模型。
 *
 * <h2>⚠️ 缺 key 必须报错，不许静默降级（C-27 配套硬约束）</h2>
 *
 * <p>{@link #requireApiKey} 在缺少 {@code DEEPSEEK_API_KEY} 时<b>抛异常</b>。
 * 理由：若改成"没 key 就自动关掉 rerank"，跑出来的产物与"rerank 没效果"在数据上完全一样，
 * 于是"没跑成"会被读成"rerank 没有增益"——这正是第 4 周消融最需要避免的误读。
 * 「无 rerank」那一档必须由用户<b>显式</b>用 {@code --no-rerank} 选择。
 *
 * <h2>⚠️ CLI 读不到 {@code .env} 是 Spring 才有的行为</h2>
 *
 * <p>{@code application.yml} 里用 {@code spring.config.import} 显式导入了
 * {@code .env}（AGENTS.md §7.2 记过这个坑），但那是 <b>Spring 的能力</b>：
 * 独立 {@code main} 的 {@link System#getenv()} <b>看不到</b> {@code .env} 里的键。
 * 若不处理，表现就是"密钥明明填在 .env 里，CLI 却报没有 key"。
 * 故 {@link #environmentWithDotEnv} 自己读 {@code ./.env} 与 {@code ../.env}
 * （两个路径都读，理由与 {@code application.yml} 完全相同：工作目录随启动方式变化），
 * <b>进程环境变量优先</b>（与 Spring 的优先级一致）。
 *
 * <p>解析规则与 {@code backend/scripts/verify_bm25.py} 的 {@code load_env} 保持一致：
 * 跳过空行与 {@code #} 注释；只认含 {@code =} 的行；左右空白去掉。
 * 额外容忍最外层成对的引号（有些人习惯写 {@code KEY="value"}）。
 *
 * <h2>⚠️ 重排侧的三个「实验条件」都从环境变量取（2026-09-21）</h2>
 *
 * <p>{@link #RERANK_MAX_TOKENS_ENV}（默认 {@link #DEFAULT_RERANK_MAX_TOKENS} = 8192）、
 * {@link #RERANK_TIMEOUT_SECONDS_ENV}（默认 {@link #DEFAULT_RERANK_TIMEOUT_SECONDS} = 90 s）
 * 与 {@link LlmReranker#DEFAULT_MAX_RETRIES}（{@code --rerank-retry}）决定的是
 * <b>这一路能不能跑起来</b>，而不是实现细节：实测 {@code rerankTopN=30} 时额度 4096
 * 会确定性降级（依据见 {@link #DEFAULT_RERANK_MAX_TOKENS}），而超时 30 s
 * 正好压在实测均值 29.4 s 上、会把本来能成的调用判成超时降级
 * （依据见 {@link #DEFAULT_RERANK_TIMEOUT_SECONDS}）——
 * 而两种「降级」在产物里看起来都与「重排没有效果」一样。
 *
 * <p>⛔ 它们<b>只作用于重排这一条装配线</b>（本类的 {@link #deepSeekChatModel}）：
 * 主对话链路的额度在 {@code application.yml}，两条链路的提示词长度与任务不同，
 * 一起改会让"是重排变了还是问答变了"分不出来。非法取值一律报错，不静默退回默认值
 * （见 {@link #resolveMaxTokens} 与 {@link #resolveTimeoutSeconds}）。
 */
public final class LlmRerankerFactory {

    /** 与 {@code application.yml} 的 {@code spring.ai.deepseek.api-key} 同一个键。 */
    public static final String API_KEY_ENV = "DEEPSEEK_API_KEY";

    /** 与 {@code application.yml} 的 {@code spring.ai.deepseek.base-url} 默认值一致。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /** 与 {@code application.yml} 的 {@code spring.ai.deepseek.chat.options.model} 默认值一致。 */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    /**
     * **强档**模型名（**C-56**）：最终生成用它，路由/改写/参数抽取仍用小档。
     * 规格书第 662 行的分工就是"小模型做路由改写、大模型做生成"。
     *
     * <p>⚠️ **实测值**（2026-09-22，本项目中继 {@code DEEPSEEK_BASE_URL=https://fastmodel.top/v1}，
     * 6 次极短调用）：{@code deepseek-v4-pro} **3/3 成功**、响应**回显** {@code deepseek-v4-pro}
     * （未被中继静默回落成别的模型）、2.8–3.9 s、约 200 token；
     * 而字面量 {@code deepseek-chat} **3/3 返回 HTTP 503**（该中继不提供它）。
     * 本机 {@code .env} 的小档是 {@code deepseek-v4-flash} ⇒ 两档同族。
     * 原始记录：{@code backend/target/probe/strong-model.txt}（构建产物目录，不入 git）。
     */
    public static final String DEFAULT_ANSWER_MODEL = "deepseek-v4-pro";

    /** 强档模型名的环境变量键；**置空 ⇒ 一键回退到小档**（⛔ 不需要改代码）。 */
    public static final String ANSWER_MODEL_ENV = "DEEPSEEK_ANSWER_MODEL";

    /**
     * 覆盖<b>重排侧</b>输出额度的环境变量（⛔ 只影响重排，不动主对话那条链路）。
     *
     * <p>刻意做成环境变量而不是命令行参数：{@code maxTokens} 是<b>实验条件</b>，
     * 而它不该随"某次跑了几条题"变化——跑一次真实链路（40 题 × 30 候选）很贵，
     * 若它埋在常量里，调它就是"改代码 + 重新编译 + 重跑"，而忘了改回来的后果
     * 是两次产物不可比（AGENTS.md §6 第 2 条"每轮只改少量变量"）。
     */
    public static final String RERANK_MAX_TOKENS_ENV = "CANALMIND_RERANK_MAX_TOKENS";

    /**
     * 重排输出的默认额度：<b>8192</b>（2026-09-21 由用户依实测证据拍定，原值 4096）。
     *
     * <p>实测证据（3 条真实链路试点，{@code rerankTopN=30} 档）：
     * <b>3/3 全部降级</b>，提示词 8710 / 9041 / 9556 字符，
     * {@code finish_reason} 均为 <b>LENGTH</b> 且 {@code completion} 恒等于 <b>4096 = 原 maxTokens</b>
     * ——即推理 token 把预算吃光、正文写不出来。
     *
     * <p>故 4096 在"候选 30 条"这一档下是<b>必然降级</b>（不是偶发）：
     * 推理 token 与正文<b>共享同一个预算</b>，提示词越长 / 候选越多 ⇒ 推理越久 ⇒ 正文越容易写不出来。
     */
    public static final int DEFAULT_RERANK_MAX_TOKENS = 8192;

    /**
     * 覆盖<b>重排侧</b>单次调用<b>等待上限</b>的环境变量（单位：<b>秒</b>；
     * ⛔ 只影响重排，不动主对话那条链路）。
     *
     * <p>与 {@link #RERANK_MAX_TOKENS_ENV} 同一个理由做成环境变量而不是命令行参数：
     * 等待上限是<b>实验条件</b>，而跑一次真实链路（40 题 × 30 候选）很贵——
     * 若它只埋在常量里，调它就是「改代码 + 重新编译 + 重跑」，
     * 而忘了改回来的后果是两次产物不可比（AGENTS.md §6 第 2 条"每轮只改少量变量"）。
     */
    public static final String RERANK_TIMEOUT_SECONDS_ENV = "CANALMIND_RERANK_TIMEOUT_SECONDS";

    /**
     * 重排单次调用的默认等待上限：<b>90 秒</b>（2026-09-21 由用户依实测证据拍定，原值 30 s）。
     *
     * <p>实测证据（{@code rerankTopN=30} 档：30 条候选、提示词约 8.7～9.6k 字符，
     * 40 题整批的真实调用统计）：单题重排耗时
     * <b>最小 21.6 s / 均值 29.4 s / 最大 43.5 s</b>，
     * 且本轮实测到一次<b>成功</b>的重排耗时 <b>29.875 s</b>（离 30 s 只差 0.1 秒）。
     * ⇒ 30 s 正好压在均值上，会把大量本来能成功的调用判成「超时降级」，
     * 而产物里的表现与「重排没效果」完全一样（都是降级 + 保留融合原序）。
     *
     * <p>⚠️ 取值与 {@link LlmReranker#DEFAULT_TIMEOUT} <b>同源</b>（直接由它换算），
     * ⛔ 不要在这里另写一个字面量：两处各写一遍必然分叉，
     * 而分叉的表现是「banner 打印 90 s、实际等待 30 s」——不会报错。
     */
    public static final int DEFAULT_RERANK_TIMEOUT_SECONDS =
            (int) LlmReranker.DEFAULT_TIMEOUT.toSeconds();

    private LlmRerankerFactory() {
    }

    /** 从进程环境变量装配（不含 .env，见类注释——需要 .env 请用 {@link #environmentWithDotEnv}）。 */
    public static LlmReranker fromEnvironment(Map<String, String> env, ChunkSnippetProvider snippets) {
        return fromEnvironment(env, snippets, LlmReranker.DEFAULT_MAX_RETRIES);
    }

    /**
     * 按环境变量装配 DeepSeek {@code ChatModel}（**构造不发任何网络请求**）。
     *
     * <p>抽成 public 是为了让**结构化查询段**（第 3 周）不用把同一段装配逻辑抄第二遍：
     * 两份装配代码必然分叉，而分叉的表现是"CLI 里是 JSON Mode、链路里不是"这种
     * **不报错的静默差异**——正是 {@code DeepSeekRequestShapeTest} 守着的那类问题。
     * 装配出的模型默认带 {@code temperature=0.0} 与 {@code ResponseFormat=json_object}。
     *
     * @param env       环境变量（含 {@code DEEPSEEK_API_KEY}；缺 key 由 {@link #requireApiKey} 抛错）
     * @param maxTokens 输出额度（由调用方按自己的场景给：重排 8192、参数抽取 1024）
     */
    public static DeepSeekChatModel chatModelFromEnvironment(Map<String, String> env, int maxTokens) {
        String apiKey = requireApiKey(env);
        String baseUrl = value(env, "DEEPSEEK_BASE_URL", DEFAULT_BASE_URL);
        String model = value(env, "DEEPSEEK_CHAT_MODEL", DEFAULT_MODEL);
        return deepSeekChatModel(apiKey, baseUrl, model, maxTokens);
    }

    /**
     * 强档模型名（**C-56**，见 {@link #DEFAULT_ANSWER_MODEL} 的实测记录）。
     *
     * <p>⚠️ **留空即回退**：{@code DEEPSEEK_ANSWER_MODEL=}（空串）或显式写成空白 ⇒
     * 返回 {@code DEEPSEEK_CHAT_MODEL}（小档）⇒ 一键退回"两档合一"的既有行为。
     * 这样"换个模型"这件事不需要改代码、也不需要重新构建。
     */
    public static String answerModel(Map<String, String> env) {
        // ⚠️ 必须**先看原始值**再走 value(...)：value() 会把"空串/空白"折叠成 fallback，
        //    于是"键存在但显式置空 ⇒ 回退小档"这条语义会被它吃掉（本实现第一版就是这么错的，
        //    被 AnswerModelTierTest 当场抓到）。两种"没给强档"要分开：
        //    ①键**不存在** ⇒ 用默认强档（配置漏了就按裁示用强档）；
        //    ②键存在但**空白** ⇒ 回退小档（运维的一键回退开关）。
        String raw = env == null ? null : env.get(ANSWER_MODEL_ENV);
        if (raw != null && raw.isBlank()) {
            return value(env, "DEEPSEEK_CHAT_MODEL", DEFAULT_MODEL);
        }
        return value(env, ANSWER_MODEL_ENV, DEFAULT_ANSWER_MODEL);
    }

    /**
     * 最终生成用的**强档** {@code ChatModel}（**C-56**）。
     * ⚠️ 只给最终生成用；路由 / 查询改写 / 参数抽取一律继续用
     * {@link #chatModelFromEnvironment}（小档），这才是规格书第 662 行的分工。
     */
    public static DeepSeekChatModel answerModelFromEnvironment(Map<String, String> env, int maxTokens) {
        String apiKey = requireApiKey(env);
        String baseUrl = value(env, "DEEPSEEK_BASE_URL", DEFAULT_BASE_URL);
        return deepSeekChatModel(apiKey, baseUrl, answerModel(env), maxTokens);
    }

    /**
     * 同上，但把<b>重试次数上限</b>也显式给出（{@code RetrievalQueryCli --rerank-retry}）。
     *
     * <p>重试次数是实验条件之一：它直接决定"降级率"这个观测量，
     * 故必须能从命令行调并随产物可追溯，而不是埋在常量里。
     *
     * <p>输出额度从 {@link #RERANK_MAX_TOKENS_ENV} 取（缺省
     * {@link #DEFAULT_RERANK_MAX_TOKENS}），单次等待上限从
     * {@link #RERANK_TIMEOUT_SECONDS_ENV} 取（缺省 {@link #DEFAULT_RERANK_TIMEOUT_SECONDS}），
     * 非法取值直接报错——见 {@link #resolveMaxTokens} 与 {@link #resolveTimeoutSeconds}。
     */
    public static LlmReranker fromEnvironment(Map<String, String> env, ChunkSnippetProvider snippets,
                                              int maxRetries) {
        return fromEnvironment(env, snippets, maxRetries, UnaryOperator.identity());
    }

    /**
     * 同上，但允许在<b>装配点</b>给 {@link PromptCompleter} 套一层装饰。
     *
     * <p>当前唯一用途是 {@code --dump-rerank}（{@link RerankDump}）：把实际发出的提示词与原始应答
     * 落盘。做成"装配点传入"而不是"重排器自己 new 一个"：装饰必须包住<b>真正要发出去的那次调用</b>，
     * 若在重排器内部另建一条通路，落盘的就不是真实证据了。
     *
     * <p>⚠️ 超时与重试仍由本方法统一落进重排器（见下方注释）——装饰器只旁听，
     * 不改变额度、超时、重试这三样实验条件。
     */
    public static LlmReranker fromEnvironment(Map<String, String> env, ChunkSnippetProvider snippets,
                                              int maxRetries,
                                              UnaryOperator<PromptCompleter> completerWrapper) {
        // ⚠️ 缺省给一个**真的**注册表（不是 NOOP）：CLI / 评测路径没有 Spring 容器，
        //    `LlmRerankerFactory` 就是那条路径的依赖注入根——若这里给 NOOP，
        //    那么"真实运行时到底有没有 ai.rag.rerank 这个节点"就永远无法回答
        //    （与 QuestionPipeline.create 的既有重载同一理由）。
        // ⚠️ 外面再套一层 `SpanTreeExporter.attachIfEnabled`（**C-54**，默认关）：
        //    设了 `-Dcanalmind.observability.export=<文件>` 才会把检索这棵树导成 JSON。
        return fromEnvironment(env, snippets, maxRetries, completerWrapper,
                SpanTreeExporter.attachIfEnabled(ObservationRegistry.create()));
    }

    /**
     * 同上，但**显式给出观测注册表**（规格书 §7.2 第 559 行的 {@code ai.rag.rerank}）。
     *
     * <p>⚠️ 既有 4 参重载保持不变（委托给本方法并传 {@link ObservationRegistry#create()}），
     * 故既有调用点（{@code RetrievalQueryCli} 与 {@code LlmRerankerFactoryTest}）
     * **行为零变化**。
     *
     * @param observationRegistry 由装配点注入；传 {@link ObservationRegistry#NOOP} 表示"本次不埋点"
     */
    public static LlmReranker fromEnvironment(Map<String, String> env, ChunkSnippetProvider snippets,
                                              int maxRetries,
                                              UnaryOperator<PromptCompleter> completerWrapper,
                                              ObservationRegistry observationRegistry) {
        String apiKey = requireApiKey(env);
        String baseUrl = value(env, "DEEPSEEK_BASE_URL", DEFAULT_BASE_URL);
        String model = value(env, "DEEPSEEK_CHAT_MODEL", DEFAULT_MODEL);
        int maxTokens = resolveMaxTokens(env);
        int timeoutSeconds = resolveTimeoutSeconds(env);
        PromptCompleter completer = completerWrapper == null
                ? deepSeekCompleter(apiKey, baseUrl, model, maxTokens)
                : completerWrapper.apply(deepSeekCompleter(apiKey, baseUrl, model, maxTokens));
        // ⚠️ 三个上限（超时/候选数/片段长度）取与既有 3 参构造器**同源的常量**，
        //    只是多了注册表——⛔ 不在这里另写一套默认值（两处各写一遍必然分叉）。
        return new LlmReranker(completer, snippets, model,
                LlmReranker.DEFAULT_TIMEOUT, LlmReranker.DEFAULT_MAX_CANDIDATES,
                LlmReranker.DEFAULT_SNIPPET_CHARS, observationRegistry)
                .withMaxRetries(maxRetries)
                // ⚠️ 必须真的落进重排器：漏掉这一行时不会有任何报错，
                //    只会在真实链路里"重排老是超时降级"，而产物与"重排没效果"一样。
                .withTimeout(Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * 解析重排侧的输出额度：{@link #RERANK_MAX_TOKENS_ENV} 优先，缺省
     * {@link #DEFAULT_RERANK_MAX_TOKENS}。
     *
     * <p><b>非法取值一律抛异常，⛔ 不静默退回默认值、更不静默当成 0</b>：
     * <ul>
     *   <li>画成 0 最可怕——额度为 0 时模型一个字都写不出来，而症状是"每次重排都降级为空正文"，
     *       与"额度太小"长得一样，却指向完全不同的原因；</li>
     *   <li>静默退回 8192 同样危险：用户明明把 {@code CANALMIND_RERANK_MAX_TOKENS=16384}
     *       写进了 {@code .env}（或写错成 {@code 16384 }、{@code 16k}），却拿到一份
     *       "额度还是 8192"的产物，而产物里<b>看不出</b>这一点——这正是本项目反复要挡的
     *       "没跑成看起来像没效果"。</li>
     * </ul>
     * 报错信息里给出合法取值与当前原始取值，让人一眼看出该改哪。
     *
     * @param env 环境变量（可为 null，视为空）
     * @throws IllegalArgumentException 取值不是正整数
     */
    static int resolveMaxTokens(Map<String, String> env) {
        String raw = env == null ? null : env.get(RERANK_MAX_TOKENS_ENV);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RERANK_MAX_TOKENS;
        }
        String trimmed = raw.strip();
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    RERANK_MAX_TOKENS_ENV + " 必须是正整数，实际取值「" + raw + "」（未取默认值 "
                            + DEFAULT_RERANK_MAX_TOKENS + "：静默退回会让人以为额度已按预期生效）。"
                            + "合法示例：" + RERANK_MAX_TOKENS_ENV + "=16384");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                    RERANK_MAX_TOKENS_ENV + " 必须 >= 1，实际取值「" + raw + "」。"
                            + "⚠️ 0 会让模型一个字都写不出来，而症状表现为"
                            + "「每次重排都降级为空正文」，与「额度太小」无法区分，故直接拒绝。");
        }
        return parsed;
    }

    /**
     * 解析重排侧的单次等待上限（秒）：{@link #RERANK_TIMEOUT_SECONDS_ENV} 优先，
     * 缺省 {@link #DEFAULT_RERANK_TIMEOUT_SECONDS}。
     *
     * <p><b>非法取值一律抛异常，⛔ 不静默退回默认值</b>（理由与 {@link #resolveMaxTokens} 相同，
     * 但这里的后果更隐蔽）：
     * <ul>
     *   <li>画成 0 或负数最可怕——等待上限为 0 时每次都<b>立刻</b>超时降级，
     *       而"降级 + 保留融合原序"在产物里与「重排本来就没效果」<b>完全一样</b>；
     *   <li>静默退回 90 同样危险：用户明明把 {@code CANALMIND_RERANK_TIMEOUT_SECONDS=120}
     *       写进了 {@code .env}（或写错成 {@code 120s}、{@code 120.0}、{@code 2m}），
     *       却拿到一份"实际仍是 90 s"的产物，而产物里<b>看不出</b>这一点——
     *       于是"这次又超时降级了几条"会被读成模型太慢，实际是配置没生效。
     * </ul>
     * 报错信息里给出合法取值与当前原始取值，让人一眼看出该改哪。
     *
     * @param env 环境变量（可为 null，视为空）
     * @throws IllegalArgumentException 取值不是正整数（秒）
     */
    static int resolveTimeoutSeconds(Map<String, String> env) {
        String raw = env == null ? null : env.get(RERANK_TIMEOUT_SECONDS_ENV);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RERANK_TIMEOUT_SECONDS;
        }
        String trimmed = raw.strip();
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    RERANK_TIMEOUT_SECONDS_ENV + " 必须是正整数（单位：秒），实际取值「" + raw
                            + "」（未取默认值 " + DEFAULT_RERANK_TIMEOUT_SECONDS
                            + "：静默退回会让人以为超时已按预期生效，"
                            + "而超时降级在产物里与「重排没效果」无法区分）。"
                            + "合法示例：" + RERANK_TIMEOUT_SECONDS_ENV + "=120");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                    RERANK_TIMEOUT_SECONDS_ENV + " 必须 >= 1（单位：秒），实际取值「" + raw + "」。"
                            + "⚠️ 0 或负数会让每次调用都**立刻**超时降级"
                            + "（上限为 0 时连一次往返都等不到），而症状表现为"
                            + "「重排完全没效果」，与「模型太慢」无法区分，故直接拒绝。");
        }
        return parsed;
    }

    /**
     * 取 API key；缺失或为空白即抛异常。
     *
     * <p>消息里给出三条可执行的出路，而不是只说"缺 key"：使用者此时最需要知道的是
     * <b>怎么把这个对照跑起来</b>（填 .env / 用环境变量 / 显式跑无 rerank 档）。
     */
    public static String requireApiKey(Map<String, String> env) {
        String key = value(env, API_KEY_ENV, null);
        if (key == null) {
            throw new IllegalStateException(
                    "重排需要 " + API_KEY_ENV + "，但当前环境里没有它，故拒绝运行（不静默降级）。"
                            + "三条出路：① 在仓库根或 backend/ 的 .env 里填 " + API_KEY_ENV + "=sk-…"
                            + "（本入口会读这两个位置的 .env）；② 用真实环境变量传入；"
                            + "③ 若本次只想跑「无 rerank」对照档，显式加 --no-rerank。"
                            + "⚠️ 刻意不自动关掉 rerank：那样跑出来的产物与「rerank 没效果」无法区分。");
        }
        return key;
    }

    /**
     * 环境变量 + {@code .env} 合并（进程环境变量优先）。
     *
     * @param envFiles 按顺序读的 .env 候选路径（先读到的不被后面的覆盖）
     */
    public static Map<String, String> environmentWithDotEnv(List<Path> envFiles) {
        return environmentWithDotEnv(envFiles, System.getenv());
    }

    /**
     * 同上，但进程环境变量由调用方传入。
     *
     * <p>做成可注入是为了<b>可测</b>：合并顺序（.env 在前、进程环境覆盖它）是这条链路里
     * 唯一"错了也不会报错"的地方——顺序写反时，本机旧 key 会盖掉 {@code .env} 里的新 key，
     * 表现只是"鉴权失败"，看不出是配置来源问题。测试必须能在不碰真实环境变量的前提下验它。
     *
     * @param processEnv 进程环境变量（优先级最高；可为 null，视为空）
     */
    public static Map<String, String> environmentWithDotEnv(List<Path> envFiles,
                                                           Map<String, String> processEnv) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (envFiles != null) {
            for (Path p : envFiles) {
                merged.putAll(readDotEnv(p));
            }
        }
        // 进程环境变量放最后 = 优先级最高（与 Spring 的外部化配置顺序一致）
        if (processEnv != null) {
            merged.putAll(processEnv);
        }
        return merged;
    }

    /** 读一个 .env（不存在则返回空表，不报错——与 {@code optional:} 导入的语义一致）。 */
    public static Map<String, String> readDotEnv(Path path) {
        Map<String, String> out = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path)) {
            return out;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + path + " 失败：" + e.getMessage(), e);
        }
        for (String raw : lines) {
            String line = raw.strip();
            // UTF-8 BOM 只可能出现在首行；不剥会让第一个键变成 "\uFEFFKEY" 而静默取不到值
            if (!line.isEmpty() && line.charAt(0) == (char) 0xFEFF) {
                line = line.substring(1).strip();
            }
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            String key = line.substring(0, eq).strip();
            String v = unquote(line.substring(eq + 1).strip());
            if (!key.isEmpty()) {
                out.put(key, v);
            }
        }
        return out;
    }

    /**
     * 组装 DeepSeek 的提示词调用。
     *
     * <p>三处刻意的设定（都属"错了不会报错、只会让结果看起来正常"的一类）：
     * <ol>
     *   <li>{@code temperature = 0.0}：重排是排序任务，要的是稳定复现，
     *       不是创造性。评测纪律要求每轮只改少量变量，温度就是其中一项，
     *       故把它钉死在装配处而不是留给默认值；</li>
     *   <li>{@code responseFormat = JSON_OBJECT}：让服务端约束输出为 JSON 对象，
     *       从源头减少"围栏/解释文字"这类需要容错的输出。
     *       ⚠️ 但解析侧仍然按"可能不合法"处理——服务端约束不等于保证；</li>
     *   <li>{@code maxTokens = }{@link #DEFAULT_RERANK_MAX_TOKENS}（当前 <b>8192</b>，
     *       可用 {@link #RERANK_MAX_TOKENS_ENV} 覆盖）：给输出封顶。
     *       ⚠️ 它是<b>重排这一条装配线专属</b>的取值，⛔ 不要顺手改主对话那条链路
     *       （{@code application.yml} 的 {@code spring.ai.deepseek.chat.options.max-tokens}）：
     *       两条链路的提示词长度与任务完全不同，一起改会让"是重排变了还是问答变了"分不出来。
     *       <p><b>为什么不是原来的 4096（实测依据，2026-09-21）</b>：{@code rerankTopN=30} 档
     *       3 条真实试点题<b>全部降级</b>，提示词 8710 / 9041 / 9556 字符，
     *       {@code finish_reason} 均为 <b>LENGTH</b> 且 {@code completion} 恒等于
     *       <b>4096 = 当时的 maxTokens</b>——正文一个字都没写出来。
     *       根因是本模型<b>先推理再输出、推理 token 与正文共享同一个预算</b>，
     *       故提示词越长 / 候选越多 ⇒ 推理越久 ⇒ 正文越容易写不出来。
     *       ⇒ 4096 在"候选 30 条"这一档下是<b>必然降级</b>，第四路（{@code rrf+llm-rerank}）
     *       在默认额度下根本跑不起来，而不是"重排没有增益"。
     *       <p>⚠️ 另有一条更早的实测：同条件把额度调到 16384 时 40 题实跑 <b>0/40 降级</b>。
     *       8192 是本轮用户定的默认值，它在 30 候选档是否够<b>尚无实测</b>——
     *       要更宽就设 {@code CANALMIND_RERANK_MAX_TOKENS=16384}（本机网关实测接受该值），
     *       <b>不必改代码</b>。
     *       ⛔ 上限不是成本：{@code max_tokens} 只是截断线，按实际用量计费。</li>
     * </ol>
     *
     * @param maxTokens 输出额度（正整数；来自 {@link #resolveMaxTokens}）
     */
    static PromptCompleter deepSeekCompleter(String apiKey, String baseUrl, String model,
                                            int maxTokens) {
        DeepSeekChatModel chatModel = deepSeekChatModel(apiKey, baseUrl, model, maxTokens);
        // ⚠️ 这里**不用** ChatClient 链式调用（`client.prompt().user(p).call().content()`）：
        // 那条路只给得回正文字符串，拿不到 finishReason 与 token 用量，
        // 于是"空正文"就变成一句没有旁证的话（这正是 2026-09-21 多花一次诊断调用的原因），
        // 且"输出被 token 上限截断"与"模型吐了非法 JSON"会**共用同一条降级原因**、分不出来。
        // 换成直接 call(Prompt) 取完整 ChatResponse，才能把旁证交给 LlmReranker。
        //
        // ⚠️ 取值口径统一走 LlmReranker.completionOf（不在这里再写一遍）：
        // 两处各写一遍必然分叉，而分叉的表现是"CLI 里有 finishReason、Spring 链路里没有"，不会报错。
        return new PromptCompleter() {

            @Override
            public String complete(String prompt) {
                return completeWithEvidence(prompt).text();
            }

            @Override
            public LlmReranker.Completion completeWithEvidence(String prompt) {
                ChatResponse response = chatModel.call(new Prompt(prompt));
                return LlmReranker.completionOf(response, prompt);
            }
        };
    }

    /**
     * 按给定额度装配重排用的 DeepSeek 模型（构造<b>不发任何网络请求</b>）。
     *
     * <p>单独抽出来是为了让"额度真的落进模型默认选项"这件事<b>可测</b>：
     * 额度是本次修正的<b>实验条件</b>，若 {@code maxTokens(...)} 被漏掉或被别的默认值盖掉，
     * 表现是"改了半天还是降级"，而<b>不会报错</b>（同
     * {@code DeepSeekRequestShapeTest} 守的那类静默分叉）。
     */
    static DeepSeekChatModel deepSeekChatModel(String apiKey, String baseUrl, String model,
                                               int maxTokens) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                // C-58：装自己的错误处理器，把 HTTP 失败**如实**翻译成带状态码与 Retry-After 的
                // LlmHttpFailure。⛔ 不装的话，Spring AI 会抛 NonTransientAiException 并把状态码
                // 只留在消息文本里（实测），下游就只能靠解析散文来判"是不是 429"了。
                .responseErrorHandler(new DeepSeekHttpErrorHandler())
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .maxTokens(maxTokens)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build())
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(options)
                // 这两个必须显式给：DeepSeekChatModel 的构造器对它们做了非空断言
                // （反编译实测 "retryTemplate cannot be null" / "observationRegistry cannot be null"），
                // 留空会在构造时报错，而不是"用个默认值接着跑"。
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }


    private static String value(Map<String, String> env, String key, String fallback) {
        String v = env == null ? null : env.get(key);
        return (v == null || v.isBlank()) ? fallback : v.strip();
    }

    /** 去掉最外层成对的引号（单引号或双引号），只去一层。 */
    private static String unquote(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }
}
