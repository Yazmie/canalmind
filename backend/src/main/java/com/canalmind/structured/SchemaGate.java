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
        Observation observation = AiObservation.start(AiSpans.TOOL_PARAM_VALIDATE, observationRegistry);
        return observation.observe(() -> {
            AiObservation.lowCardinality(observation, AiSpans.KEY_TOOL_VALIDATE_STAGE,
                    AiSpans.VALUE_VALIDATE_STAGE_ARGUMENTS);
            Schema s = toolSchemas.get(tool);
            List<String> errors = s == null ? List.of("未知工具：" + tool) : validate(s, arguments);
            AiObservation.highCardinality(observation, AiSpans.KEY_RAG_RESULT_COUNT,
                    Integer.toString(errors.size()));
            return errors;
        });
    }

    /** 底层校验（静态，便于单测直接喂 Schema）。 */
    public static List<String> validate(Schema schema, JsonNode node) {
        ExecutionContext ctx = new ExecutionContext();
        schema.validate(ctx, node);
        List<Error> errors = ctx.getErrors();
        List<String> out = new ArrayList<>();
        if (errors != null) {
            for (Error e : errors) {
                out.add(describe(e));
            }
        }
        return List.copyOf(out);
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
