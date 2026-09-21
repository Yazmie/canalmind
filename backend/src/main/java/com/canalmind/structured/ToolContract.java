package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 单个受控工具的契约（决策 C-33：阶段一 = 契约化受控工具集）。
 *
 * <p><b>为什么契约只此一份</b>：工具说明同时被三处消费——①送给模型的提示词、
 * ②服务端 JSON Schema 校验、③评测报告里「工具集有哪些」。三处各写一份必然漂移，
 * 故全都读 {@code classpath:structured/tool-contracts.json}（见 {@link ToolCatalog}）。
 *
 * @param name        工具名（模型只能输出这些名字，见 envelope 的 enum）
 * @param title       中文短名（给人看）
 * @param description 用途说明（**给模型看**，是提示词的一部分，须写清"什么时候用"）
 * @param parameters  该工具 parameters 的 JSON Schema（2020-12）
 * @param notes       注意事项（给模型看的边界提醒，如"不能回答什么"）
 */
public record ToolContract(String name, String title, String description,
                           JsonNode parameters, String notes) {

    public ToolContract {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameters, "parameters");
        if (name.isBlank()) {
            throw new IllegalArgumentException("工具名不得为空");
        }
        if (!parameters.isObject()) {
            throw new IllegalArgumentException("工具 " + name + " 的 parameters 必须是 JSON Schema 对象");
        }
        if (description == null || description.isBlank()) {
            // 描述进提示词：缺了它模型只能靠工具名猜，等于契约失效
            throw new IllegalArgumentException("工具 " + name + " 缺 description（它进提示词，不得为空）");
        }
    }

    /** 送进提示词的工具块（紧凑、可读、无 Markdown 表格，避免模型被格式带偏）。 */
    public String promptBlock(String newline) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append("（").append(title).append("）：").append(description).append(newline);
        sb.append("  参数 Schema：").append(parameters.toString()).append(newline);
        if (notes != null && !notes.isBlank()) {
            sb.append("  注意：").append(notes).append(newline);
        }
        return sb.toString();
    }

    /** 该工具的必填参数名（取自 Schema 的 required，缺省为空）。 */
    public java.util.List<String> requiredParameters() {
        JsonNode required = parameters.path("required");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (required.isArray()) {
            required.forEach(n -> out.add(n.asText()));
        }
        return java.util.List.copyOf(out);
    }
}
