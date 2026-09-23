package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 服务端 JSON Schema 校验闸门（决策 C-32 的核心件）。
 *
 * <p><b>两层校验</b>：①{@code envelope}（模型的输出整体形状）；
 * ②{@code arguments} 对着**所选工具**的 parameters Schema 再校一遍。
 * 之所以不在 envelope 里用 {@code if/then} 一次校完：拆成两层后错误信息能直接说清
 * "哪个工具的哪个参数错了"，**这句信息要原样喂回给模型重试**，越具体重试越容易成功。
 *
 * <p>⚠️ networknt **2.0.0 的 API 与 1.x 完全不同**（1.x 的 {@code JsonSchemaFactory} /
 * {@code JsonSchema} / {@code SchemaValidatorsConfig} 已不存在），本类按 2.0.0 写：
 * {@code SchemaRegistry.withDefaultDialect(...)} → {@code getSchema(JsonNode)}
 * → {@code validate(ExecutionContext, JsonNode)} → {@code ctx.getErrors()}。
 * 依据是本机 javap 实测，不是记忆（`AGENTS.md` §5 版本纪律）。
 */
public final class SchemaGate {

    private final SchemaRegistry registry;
    private final Schema envelopeSchema;
    private final Map<String, Schema> toolSchemas;
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.tool.param.validate}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    private SchemaGate(SchemaRegistry registry, Schema envelopeSchema, Map<String, Schema> toolSchemas,
                       ObservationRegistry observationRegistry) {
        this.registry = registry;
        this.envelopeSchema = envelopeSchema;
        this.toolSchemas = Map.copyOf(toolSchemas);
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    public static SchemaGate of(ToolCatalog catalog) {
        return of(catalog, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 的 {@code ai.tool.param.validate}）。
     *
     * <p>⚠️ 既有 {@code of(catalog)} 保持不变、委托给 {@link ObservationRegistry#NOOP}：
     * 它被 {@code StructuredQueryCli}（含 {@code --selftest} 的多处桩装配）、
     * {@code SchemaGateTest}、{@code ToolArgumentExtractorTest}、
     * {@code StructuredQueryServiceTest}、{@code ExecutionAccuracyRunTest} 使用，
     * **旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public static SchemaGate of(ToolCatalog catalog, ObservationRegistry observationRegistry) {
        SchemaRegistry reg = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Map<String, Schema> toolSchemas = new LinkedHashMap<>();
        for (ToolContract c : catalog.tools().values()) {
            toolSchemas.put(c.name(), reg.getSchema(c.parameters()));
        }
        return new SchemaGate(reg, reg.getSchema(catalog.envelope()), toolSchemas, observationRegistry);
    }

    /**
     * 校验整体输出形状；返回**人类可读的错误清单**（空 = 通过）。
     *
     * <p>规格书 §7.2 第 552 行要求这一步有自己的 Span（{@code ai.tool.param.validate}），
     * 并记<b>错误条数</b>（高基数 {@code ai.rag.result_count}）。
     * <p>⚠️ 为什么是"错误条数"而不是"通过/不通过"：错误条数是**重试预算消耗的直接证据**
     * ——一次纠错重试里模型改对了 1 处还是 5 处，决定了"该改提示词"还是"该改契约"
     * （知识库《大模型结构化输出》第 161、177–179 行的反模式正是"只提示不校验"）。
     * <p>⚠️ <b>错误文案本身不进属性</b>：它是自由文本、可能含模型输出的片段，
     * 进低基数位会被闸门拒、进高基数位是正文级体积；它另有归宿——原样喂回模型重试
     * （见 {@code ToolArgumentExtractor.withFeedback}）并落在 {@code ExtractionOutcome.errors()}。
     */
    public List<String> validateEnvelope(JsonNode node) {
        Observation observation = AiObservation.start(AiSpans.TOOL_PARAM_VALIDATE, observationRegistry);
        return observation.observe(() -> {
            AiObservation.lowCardinality(observation, AiSpans.KEY_TOOL_VALIDATE_STAGE,
                    AiSpans.VALUE_VALIDATE_STAGE_ENVELOPE);
            List<String> errors = validate(envelopeSchema, node);
            AiObservation.highCardinality(observation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(errors.size()));
            return errors;
        });
    }

    /**
     * 校验所选工具的 arguments；工具名未知时返回一条错误（不抛）。
     *
     * <p>与 {@link #validateEnvelope(JsonNode)} 是**两个独立的 Span**（规格书 §7.2 把
     * "{@code ai.tool.param.validate}" 列为一个节点，而这一层的两次校验是**两次真实的动作**）：
     * envelope 校的是"模型输出的整体形状"，arguments 校的是"所选工具的每个参数"。
     * 合并成一个 Span 会让"错在信封还是错在参数"在 Trace 上分不出来，
     * 而这两者的处置不同（前者多半要改提示词，后者多半是模型没看懂某个参数的契约）。
     * ⇒ 两次校验各自上报 <b>{@link AiSpans#KEY_TOOL_VALIDATE_STAGE}</b> 加以区分——
     * 既保留规格书的节点名，又不让两个同名节点变成无法区分的一份数据。
     */
    public List<String> validateArguments(String tool, JsonNode arguments) {
        return messages(validateArgumentsAsViolations(tool, arguments));
    }

    /**
     * 同 {@link #validateArguments(String, JsonNode)}，但返回**带关键字的结构化违规**。
     *
     * <p>⚠️ <b>为什么需要它</b>（决策 D24 → 缺口①）：服务层必须把「**值越界 / 枚举不符**」
     * 与「形状不合规（缺必填、多余键、格式错）」**分开处置**——前者是"用户要的东西本身
     * 越出了契约允许的范围"，应判 {@code REJECTED} 而不是整条通道降级；后者才是
     * "模型没说清"，继续走原有的降级路径。而 {@code validateArguments} 只回**人类可读的错误文案**
     * （那是要**原样喂回模型重试**的），从文案反解关键字就是「拿给人看的文本当机器判据」——
     * 本项目已因同类错误两次得出错误结论（《评测报告》§6.1 第 3、8 条）。
     * ⇒ 关键字必须从 {@code Error.getKeyword()} 直接取，不从字符串里猜。
     */
    public List<Violation> validateArgumentsAsViolations(String tool, JsonNode arguments) {
        Observation observation = AiObservation.start(AiSpans.TOOL_PARAM_VALIDATE, observationRegistry);
        return observation.observe(() -> {
            AiObservation.lowCardinality(observation, AiSpans.KEY_TOOL_VALIDATE_STAGE,
                    AiSpans.VALUE_VALIDATE_STAGE_ARGUMENTS);
            Schema s = toolSchemas.get(tool);
            List<Violation> violations = s == null
                    ? List.of(new Violation(KEYWORD_UNKNOWN_TOOL, "未知工具：" + tool, ""))
                    : violations(s, arguments);
            AiObservation.highCardinality(observation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(violations.size()));
            return violations;
        });
    }

    /** 一条 Schema 违规：**机器可判的关键字** + 喂回模型的可读文案 + 出错的实例路径。 */
    public record Violation(String keyword, String message, String instancePath) {

        public Violation {
            keyword = keyword == null ? "" : keyword;
            message = message == null ? "" : message;
            instancePath = instancePath == null ? "" : instancePath;
        }
    }

    /** 工具名不在契约里（不是"值越界"，不得判 {@code REJECTED}）。 */
    public static final String KEYWORD_UNKNOWN_TOOL = "unknownTool";

    /**
     * 「**值越界 / 枚举不符**」这一类违规的 JSON Schema 关键字（缺口①的判据）。
     *
     * <p>⛔ <b>刻意只收这几项</b>：{@code required} / {@code additionalProperties} / {@code pattern} /
     * {@code type} 等**形状类**违规**不在其中**——它们要么是「缺参」（本该追问），
     * 要么是「模型输出格式不对」（本该重试后降级），语义与"取值越出允许范围"不同。
     * 把它们混进来会让「缺参」被误判成 {@code REJECTED}，直接违反决策 C-35 的口径。
     */
    private static final Set<String> VALUE_VIOLATION_KEYWORDS = Set.of(
            "enum", "const", "maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum");

    /** 该违规是否属于「值越界 / 枚举不符」（⇒ 服务层可判 {@code REJECTED}）。 */
    public static boolean isValueViolation(Violation v) {
        return v != null && VALUE_VIOLATION_KEYWORDS.contains(v.keyword());
    }

    /** 关键字 → 中文（用于**给用户看的**拒绝理由；⛔ 不参与喂回模型的重试文案）。 */
    private static final Map<String, String> KEYWORD_CN = Map.of(
            "maximum", "不得超过上限",
            "exclusiveMaximum", "必须小于上限",
            "minimum", "不得低于下限",
            "exclusiveMinimum", "必须大于下限",
            "enum", "只能是契约枚举内的固定取值",
            "const", "只能是契约写死的取值");

    /**
     * 把一条「值越界/枚举不符」违规渲染成**给人看的中文**一句话（附原始文案以便核对）。
     *
     * <p>⚠️ 为什么保留英文原文：networknt 的文案里带着**具体边界值**（如
     * {@code must have a maximum value of 50}），而回答层要把这个数字如实说出来；
     * 只写中文标签会把数字丢掉，只写英文原文则用户看不懂。
     */
    public static String describeForUser(Violation v) {
        if (v == null) {
            return "";
        }
        String what = KEYWORD_CN.get(v.keyword());
        String name = pathTail(v.instancePath());
        String where = name.isBlank() ? "参数" : "参数 " + name;
        if (what == null) {
            return where + " 不符合契约（" + v.message() + "）";
        }
        return where + " " + what + "（" + v.message() + "）";
    }

    /** 取实例路径的最后一段（{@code $.limit} / {@code /limit} → {@code limit}）；根路径返回空串。 */
    private static String pathTail(String instancePath) {
        if (instancePath == null || instancePath.isBlank() || "$".equals(instancePath)
                || "/".equals(instancePath)) {
            return "";
        }
        int cut = Math.max(instancePath.lastIndexOf('.'), instancePath.lastIndexOf('/'));
        return cut < 0 ? instancePath : instancePath.substring(cut + 1);
    }

    /** 底层校验（静态，便于单测直接喂 Schema）。 */
    public static List<String> validate(Schema schema, JsonNode node) {
        return messages(violations(schema, node));
    }

    /** 底层校验的**结构化**版本：关键字与文案同源，避免"文案改了、判据就漂了"。 */
    public static List<Violation> violations(Schema schema, JsonNode node) {
        ExecutionContext ctx = new ExecutionContext();
        schema.validate(ctx, node);
        List<Error> errors = ctx.getErrors();
        List<Violation> out = new ArrayList<>();
        if (errors != null) {
            for (Error e : errors) {
                out.add(new Violation(e.getKeyword(), describe(e), locationOf(e)));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> messages(List<Violation> violations) {
        List<String> out = new ArrayList<>(violations.size());
        for (Violation v : violations) {
            out.add(v.message());
        }
        return List.copyOf(out);
    }

    /** 实例路径（如 {@code $.limit}）；拿不到就返回空串（⛔ 不让它把整条校验搞失败）。 */
    private static String locationOf(Error e) {
        try {
            return e.getInstanceLocation() == null ? "" : e.getInstanceLocation().toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * 把一条校验错误渲染成可读文本。
     *
     * <p>⚠️ 不能只信 {@code getMessage()}：它的文案来自消息源（MessageSource），
     * 配置不全时可能为 null。故这里做兜底，用 keyword + 实例路径 + 参数自己拼——
     * **宁可文案生硬，也不能出现空字符串错误**（空错误消息喂回模型等于没喂）。
     */
    public static String describe(Error e) {
        String msg = null;
        try {
            msg = e.getMessage();
        } catch (RuntimeException ignored) {
            // 消息源缺失时 networknt 可能抛错：兜底走下面的自拼文案
        }
        if (msg != null && !msg.isBlank()) {
            return msg;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("不符合 ").append(e.getKeyword());
        if (e.getInstanceLocation() != null) {
            sb.append("（位置 ").append(e.getInstanceLocation()).append('）');
        }
        Object[] args = e.getArguments();
        if (args != null && args.length > 0) {
            sb.append(" 期望/相关值：");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(args[i]);
            }
        }
        return sb.toString();
    }

    /** 供自检用：已注册的工具 Schema 数量（= 契约里的工具数，用于断言"契约与校验器同源"）。 */
    public int toolSchemaCount() {
        return toolSchemas.size();
    }
}
