package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 模型给出的一次工具调用意图（已通过 envelope Schema 校验，**尚未**做业务校验）。
 *
 * @param tool      工具名
 * @param arguments 参数（JSON 对象；业务校验与 SQL 参数化都在后续层做）
 * @param missing   模型申报的"必填但用户未给"的参数名
 * @param reason    模型自述的选择理由（仅用于留痕/排查，不参与执行）
 */
public record ToolCall(String tool, JsonNode arguments, List<String> missing, String reason) {

    public ToolCall {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("工具名不得为空");
        }
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments 必须是 JSON 对象");
        }
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /** 是否落在受控工具集内（"none" 表示不匹配任何工具：走检索通道或追问）。 */
    public boolean isToolSelection() {
        return !ToolCatalog.NONE_TOOL.equals(tool);
    }

    /** 参数取值（缺省返回 null，由业务层决定"缺参就追问"还是"用默认值"）。 */
    public String stringArg(String name) {
        JsonNode n = arguments.get(name);
        return n == null || n.isNull() ? null : n.asText();
    }

    /** 数值参数以**字符串**返回，避免把小数转成 double 再算钱（金额一律走 BigDecimal）。 */
    public String numberArg(String name) {
        JsonNode n = arguments.get(name);
        return n == null || n.isNull() ? null : n.asText();
    }

    public Integer intArg(String name, Integer fallback) {
        JsonNode n = arguments.get(name);
        if (n == null || n.isNull()) {
            return fallback;
        }
        return n.isNumber() ? n.asInt() : Integer.valueOf(n.asText().trim());
    }

    public Boolean boolArg(String name, Boolean fallback) {
        JsonNode n = arguments.get(name);
        if (n == null || n.isNull()) {
            return fallback;
        }
        return n.isBoolean() ? n.booleanValue() : Boolean.valueOf(n.asText().trim());
    }

    @Override
    public String toString() {
        return tool + arguments;
    }
}
