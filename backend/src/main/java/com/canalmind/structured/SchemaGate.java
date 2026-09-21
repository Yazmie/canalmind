package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private SchemaGate(SchemaRegistry registry, Schema envelopeSchema, Map<String, Schema> toolSchemas) {
        this.registry = registry;
        this.envelopeSchema = envelopeSchema;
        this.toolSchemas = Map.copyOf(toolSchemas);
    }

    public static SchemaGate of(ToolCatalog catalog) {
        SchemaRegistry reg = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Map<String, Schema> toolSchemas = new LinkedHashMap<>();
        for (ToolContract c : catalog.tools().values()) {
            toolSchemas.put(c.name(), reg.getSchema(c.parameters()));
        }
        return new SchemaGate(reg, reg.getSchema(catalog.envelope()), toolSchemas);
    }

    /** 校验整体输出形状；返回**人类可读的错误清单**（空 = 通过）。 */
    public List<String> validateEnvelope(JsonNode node) {
        return validate(envelopeSchema, node);
    }

    /** 校验所选工具的 arguments；工具名未知时返回一条错误（不抛）。 */
    public List<String> validateArguments(String tool, JsonNode arguments) {
        Schema s = toolSchemas.get(tool);
        if (s == null) {
            return List.of("未知工具：" + tool);
        }
        return validate(s, arguments);
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
