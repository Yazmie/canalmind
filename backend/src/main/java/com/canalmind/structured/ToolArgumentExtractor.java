package com.canalmind.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 参数抽取：模型出参数 → 服务端校验 → 错了把**具体错误**喂回重试（≤{@value #DEFAULT_MAX_RETRIES} 次）→ 仍不行就降级。
 *
 * <p>这是决策 **C-32** 的落地：JSON Mode 只保证"是合法 JSON"，
 * <b>不保证满足 Schema</b>，所以合规性必须服务端校验；校验失败也不是直接失败，
 * 而是带上错误反馈重试——知识库《大模型结构化输出》第 161、177–179 行的反模式正是"只提示不校验"。
 *
 * <p><b>降级路径逐条明确</b>（任务 4 要求"工具异常与降级"）：
 * <table>
 *   <tr><td>模型调用异常</td><td>重试；用尽 → {@code MODEL_ERROR}</td></tr>
 *   <tr><td>应答非 JSON</td><td>先尝试剥离代码块围栏/截取首个花括号对象；仍不行 → 重试 → {@code NOT_JSON}</td></tr>
 *   <tr><td>Envelope/参数 Schema 不合规</td><td>重试 → {@code SCHEMA_INVALID}</td></tr>
 *   <tr><td>模型选了不存在的工具</td><td>重试（枚举本已挡住，这是双保险）→ {@code TOOL_UNKNOWN}</td></tr>
 *   <tr><td>模型判定不属工具集</td><td>不重试，直接 {@code TOOL_NONE}（交给检索通道）</td></tr>
 *   <tr><td>缺必填参数</td><td>不重试，直接追问（{@code MISSING_PARAMS}）——**不能逼模型编一个值**</td></tr>
 * </table>
 */
public final class ToolArgumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(ToolArgumentExtractor.class);

    /** 决策 C-32 写死：错误反馈重试 ≤2 次（即最多 3 次调用）。 */
    public static final int DEFAULT_MAX_RETRIES = 2;

    private final ToolCatalog catalog;
    private final SchemaGate gate;
    private final JsonChatInvoker invoker;
    private final ObjectMapper mapper;
    private final int maxRetries;
    private final String businessToday;
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.tool.param.extract}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    public ToolArgumentExtractor(ToolCatalog catalog, SchemaGate gate, JsonChatInvoker invoker,
                                 int maxRetries, String businessToday) {
        this(catalog, gate, invoker, new ObjectMapper(), maxRetries, businessToday);
    }

    public ToolArgumentExtractor(ToolCatalog catalog, SchemaGate gate, JsonChatInvoker invoker,
                                 ObjectMapper mapper, int maxRetries, String businessToday) {
        this(catalog, gate, invoker, mapper, maxRetries, businessToday, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 的 {@code ai.tool.param.extract}）。
     *
     * <p>⚠️ 既有两个构造器保持不变、一律委托给 {@link ObservationRegistry#NOOP}：
     * 它们被 {@code StructuredQueryCli}（含 {@code --selftest} 的 5 处桩装配）、
     * {@code ExecutionAccuracyRunTest}、{@code ToolArgumentExtractorTest}、
     * {@code StructuredQueryServiceTest} 使用，**旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public ToolArgumentExtractor(ToolCatalog catalog, SchemaGate gate, JsonChatInvoker invoker,
                                 int maxRetries, String businessToday,
                                 ObservationRegistry observationRegistry) {
        this(catalog, gate, invoker, new ObjectMapper(), maxRetries, businessToday,
                observationRegistry);
    }

    /** 全参构造器（唯一给字段赋值的地方）。 */
    public ToolArgumentExtractor(ToolCatalog catalog, SchemaGate gate, JsonChatInvoker invoker,
                                 ObjectMapper mapper, int maxRetries, String businessToday,
                                 ObservationRegistry observationRegistry) {
        this.catalog = catalog;
        this.gate = gate;
        this.invoker = invoker;
        this.mapper = mapper;
        this.maxRetries = Math.max(0, maxRetries);
        this.businessToday = businessToday;
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    public ExtractionOutcome extract(String question) {
        String system = catalog.systemPrompt();
        String baseUser = catalog.userPrompt(question, businessToday);
        List<String> raw = new ArrayList<>();
        List<String> errors = List.of();
        int attempts = 0;
        long start = System.nanoTime();

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            attempts = attempt;
            // ⚠️ 两个**final 副本**：`errors` 与 `attempt` 在循环里都会被改写，
            //    直接用它们做 lambda 捕获会编译不过（"must be final or effectively final"）。
            //    ⚠️ 实测教训：这条编译错误在本机曾被静默放过（主源码编译报 0 错、
            //    运行时才以 Unresolved compilation problems 抛 Error），故这里写开、不再耍花招。
            final List<String> errorsSoFar = errors;
            final int attemptNo = attempt;
            // ⚠️⚠️ **每次重试各建一个 Span**，并把本次序号写进高基数属性 attempt
            // （规格书 §7.2 的 ai.tool.param.extract；知识库第 55 行原文
            // "每次重试都新建 Span 并用 attempt 标明次数"，第 269 行同）。
            // 理由：不这么做，"第一次超时、第二次成功"会被压成**一条**记录，
            // 于是"链路不稳"与"一次就成"在产物里长得一样——正是本项目最想避免的
            // 「没跑成看起来像没失败」。attempt 从 1 起（见 AiObservation.attempt 的注释）。
            //
            // ⚠️⚠️ 一律 observe(...)，⛔ 不手工 start()/stop()：手工写法下父指针全是 null，
            // 整棵树静默变平（实测见 docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md）。
            Observation observation = AiObservation.start(AiSpans.TOOL_PARAM_EXTRACT,
                    observationRegistry);
            AiObservation.attempt(observation, attempt);
            AttemptOutcome outcome = observation.observe(
                    () -> attemptOnce(system, baseUser, errorsSoFar, attemptNo, raw, start));
            if (outcome.finalOutcome() != null) {
                return outcome.finalOutcome();
            }
            errors = outcome.errors();
        }

        String kind = errors.isEmpty() ? ExtractionOutcome.KIND_MODEL_ERROR
                : (errors.get(0).startsWith("调用模型失败") ? ExtractionOutcome.KIND_MODEL_ERROR
                : (errors.get(0).startsWith("应答不是合法 JSON") ? ExtractionOutcome.KIND_NOT_JSON
                : ExtractionOutcome.KIND_SCHEMA_INVALID));
        return ExtractionOutcome.degraded(kind, errors, attempts, raw, ms(start), null);
    }

    /**
     * 一次尝试（原来的循环体）。
     *
     * <p>⚠️ <b>为什么必须把它抽出来</b>：埋点要求"每次尝试各一个 Span"，而 Span 必须用
     * {@code observe(...)} 包住<b>一整段</b>业务（见 {@link #extract(String)} 的注释），
     * 于是循环体必须是"一个有返回值的表达式"。抽出来之后 {@code continue} 变成
     * {@link AttemptOutcome#retry}、{@code return} 变成 {@link AttemptOutcome#done}，
     * <b>判定分支一条没动</b>（逐条对照原实现：模型异常 / 非 JSON / envelope 不合规 /
     * TOOL_NONE / 未知工具 / 申报缺参 / arguments 不合规 / 成功）。
     *
     * @param errorsSoFar 上一次尝试留下的错误（用于 {@link #withFeedback}）；⛔ 不是本次的
     * @param raw         原始应答累积器（**穿透**：重试时也要把畸形那次留下，
     *                    否则"模型输出过一次畸形"这件事不留痕）
     */
    private AttemptOutcome attemptOnce(String system, String baseUser, List<String> errorsSoFar,
                                       int attempt, List<String> raw, long startNanos) {
        String reply;
        try {
            reply = invoker.invoke(system, withFeedback(baseUser, errorsSoFar));
        } catch (Exception e) {
            List<String> errs = List.of("调用模型失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            log.warn("参数抽取：第 {} 次调用模型失败：{}", attempt, errs.get(0));
            return AttemptOutcome.retry(errs);
        }
        raw.add(reply);

        JsonNode node = parseJson(reply);
        if (node == null) {
            return AttemptOutcome.retry(List.of(
                    "应答不是合法 JSON（必须是单个 JSON 对象，不要代码块围栏、不要解释文字）"));
        }

        List<String> envelopeErrors = gate.validateEnvelope(node);
        if (!envelopeErrors.isEmpty()) {
            return AttemptOutcome.retry(envelopeErrors);
        }
        String tool = node.path("tool").asText("");
        if (ToolCatalog.NONE_TOOL.equals(tool)) {
            // 不属于工具集：这是**正确的降级**（该走文档检索通道），不算失败
            return AttemptOutcome.done(ExtractionOutcome.degraded(
                    ExtractionOutcome.KIND_TOOL_NONE, List.of(), attempt, raw, ms(startNanos), null));
        }
        if (!catalog.hasTool(tool)) {
            return AttemptOutcome.retry(List.of(
                    "未知工具名：" + tool + "（合法取值：" + catalog.tools().keySet() + "）"));
        }

        List<String> missing = readStringArray(node.path("missing"));
        JsonNode args = node.path("arguments");
        String reason = node.path("reason").asText("");
        if (!missing.isEmpty()) {
            // 模型申报缺参：**不逼它编值**，直接追问；此路径不再校 arguments 的 required
            // （模型已明确说"这个参数用户没给"，此时校 required 只会逼它虚构）
            ToolCall partial = new ToolCall(tool, args, missing, reason);
            return AttemptOutcome.done(ExtractionOutcome.clarify(partial,
                    ExtractionOutcome.KIND_MISSING_PARAMS, null, List.of(), attempt, raw,
                    ms(startNanos)));
        }

        List<String> argErrors = gate.validateArguments(tool, args);
        if (!argErrors.isEmpty()) {
            return AttemptOutcome.retry(argErrors);
        }

        ToolCall call = new ToolCall(tool, args, missing, reason);
        return AttemptOutcome.done(ExtractionOutcome.success(call, attempt, raw, ms(startNanos)));
    }

    /**
     * 一次尝试的结果：要么**终局**（给出结论、直接返回），要么**带错误继续重试**。
     *
     * <p>⛔ 为什么不直接返回 {@link ExtractionOutcome} 并用 {@code null} 表示"继续重试"：
     * 那样无法区分"要继续重试"与"这次真的产出了 null"（后者是 bug 而非流程）。
     * 用两个字段把两种意图写开，读的人不必靠约定去猜。
     */
    private record AttemptOutcome(ExtractionOutcome finalOutcome, List<String> errors) {

        static AttemptOutcome done(ExtractionOutcome outcome) {
            return new AttemptOutcome(Objects.requireNonNull(outcome, "outcome"), List.of());
        }

        static AttemptOutcome retry(List<String> errors) {
            return new AttemptOutcome(null, List.copyOf(errors));
        }
    }

    /**
     * 把上一轮的错误拼进用户提示词。
     *
     * <p>⚠️ 明确告诉模型"缺必填参数时请放进 missing，不要编造"——
     * 否则模型会为了"输出合规"而虚构日期/吨位，那比抽不出来更糟（**错答比不答危险**）。
     */
    private String withFeedback(String baseUser, List<String> errors) {
        if (errors.isEmpty()) {
            return baseUser;
        }
        String nl = String.valueOf((char) 10);
        StringBuilder sb = new StringBuilder(baseUser).append(nl);
        sb.append("上一次输出不合规，请修正后重新输出 JSON：").append(nl);
        for (String e : errors) {
            sb.append("- ").append(e).append(nl);
        }
        sb.append("提示：必填参数若用户未提供，请把它放进 missing 数组，**不要编造取值**。").append(nl);
        return sb.toString();
    }

    /**
     * 宽松解析：先直接读；失败则剥掉 Markdown 代码块围栏；再失败则截取首个平衡的 {...}。
     *
     * <p>理由是**实测常态**：即便提示词明令"不要围栏"，模型仍常带围栏。
     * 剥围栏属于"纠正格式"而非"降低标准"——最终仍需通过 Schema 校验。
     */
    JsonNode parseJson(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        JsonNode direct = tryRead(reply.trim());
        if (direct != null) {
            return direct.isObject() ? direct : null;
        }
        String stripped = stripFences(reply.trim());
        JsonNode node = tryRead(stripped);
        if (node != null && node.isObject()) {
            return node;
        }
        String braces = firstBalancedObject(stripped);
        if (braces != null) {
            JsonNode n = tryRead(braces);
            if (n != null && n.isObject()) {
                return n;
            }
        }
        return null;
    }

    private JsonNode tryRead(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String stripFences(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNl = text.indexOf((char) 10);
        if (firstNl < 0) {
            return text;
        }
        String body = text.substring(firstNl + 1);
        int fence = body.lastIndexOf("```");
        return (fence >= 0 ? body.substring(0, fence) : body).trim();
    }

    /** 截取首个花括号平衡的子串（处理"前言 + JSON + 后记"）。 */
    private static String firstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static List<String> readStringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return List.copyOf(out);
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
