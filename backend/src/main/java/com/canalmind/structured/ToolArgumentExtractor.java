package com.canalmind.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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

    public ToolArgumentExtractor(ToolCatalog catalog, SchemaGate gate, JsonChatInvoker invoker,
                                 int maxRetries, String businessToday) {
        this(catalog, gate, invoker, new ObjectMapper(), maxRetries, businessToday);
    }

    public ToolArgumentExtractor(ToolCatalog catalog, SchemaGate gate, JsonChatInvoker invoker,
                                 ObjectMapper mapper, int maxRetries, String businessToday) {
        this.catalog = catalog;
        this.gate = gate;
        this.invoker = invoker;
        this.mapper = mapper;
        this.maxRetries = Math.max(0, maxRetries);
        this.businessToday = businessToday;
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
            String reply;
            try {
                reply = invoker.invoke(system, withFeedback(baseUser, errors));
            } catch (Exception e) {
                errors = List.of("调用模型失败：" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " - " + e.getMessage()));
                log.warn("参数抽取：第 {} 次调用模型失败：{}", attempt, errors.get(0));
                continue;
            }
            raw.add(reply);

            JsonNode node = parseJson(reply);
            if (node == null) {
                errors = List.of("应答不是合法 JSON（必须是单个 JSON 对象，不要代码块围栏、不要解释文字）");
                continue;
            }

            errors = gate.validateEnvelope(node);
            if (!errors.isEmpty()) {
                continue;
            }
            String tool = node.path("tool").asText("");
            if (ToolCatalog.NONE_TOOL.equals(tool)) {
                // 不属于工具集：这是**正确的降级**（该走文档检索通道），不算失败
                return ExtractionOutcome.degraded(ExtractionOutcome.KIND_TOOL_NONE, List.of(), attempts,
                        raw, ms(start), null);
            }
            if (!catalog.hasTool(tool)) {
                errors = List.of("未知工具名：" + tool + "（合法取值：" + catalog.tools().keySet() + "）");
                continue;
            }

            List<String> missing = readStringArray(node.path("missing"));
            JsonNode args = node.path("arguments");
            String reason = node.path("reason").asText("");
            if (!missing.isEmpty()) {
                // 模型申报缺参：**不逼它编值**，直接追问；此路径不再校 arguments 的 required
                // （模型已明确说"这个参数用户没给"，此时校 required 只会逼它虚构）
                ToolCall partial = new ToolCall(tool, args, missing, reason);
                return ExtractionOutcome.clarify(partial, ExtractionOutcome.KIND_MISSING_PARAMS, null,
                        List.of(), attempts, raw, ms(start));
            }

            List<String> argErrors = gate.validateArguments(tool, args);
            if (!argErrors.isEmpty()) {
                errors = argErrors;
                continue;
            }

            ToolCall call = new ToolCall(tool, args, missing, reason);
            return ExtractionOutcome.success(call, attempts, raw, ms(start));
        }

        String kind = errors.isEmpty() ? ExtractionOutcome.KIND_MODEL_ERROR
                : (errors.get(0).startsWith("调用模型失败") ? ExtractionOutcome.KIND_MODEL_ERROR
                : (errors.get(0).startsWith("应答不是合法 JSON") ? ExtractionOutcome.KIND_NOT_JSON
                : ExtractionOutcome.KIND_SCHEMA_INVALID));
        return ExtractionOutcome.degraded(kind, errors, attempts, raw, ms(start), null);
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
