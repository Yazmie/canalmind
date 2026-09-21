package com.canalmind.structured;

import java.util.List;

/**
 * 一次受控工具执行的结果（**含"没数据""要追问""降级"这些非成功态**）。
 *
 * @param status  见 {@link #STATUS_*}；⛔ 不要用"空结果集"表达差异——那会把
 *                "查了但没有"与"根本没查成"混成一件事
 * @param columns 列名（给人看，也用于对照数据库 oracle）
 * @param rows    数据行（数值已在 SQL 里 {@code ::text} 定型，保证可逐字符对账）
 * @param facts   **确定性事实**：回答层必须以此为准（金额、规则码、档位、生效期都在这）
 * @param traces  执行留痕：参数、命中规则、窗口裁剪、重试次数等（排查与评测都读它）
 * @param question 需要追问时的问题文本
 */
public record ToolResult(String tool, String status, List<String> columns, List<List<String>> rows,
                         List<String> facts, List<String> traces, String degradeKind, String question) {

    /** 正常返回（含"0 行"由 {@link #noData} 表达）。 */
    public static final String STATUS_OK = "OK";
    /** 查了但业务窗口内没有数据（如实说明，不是错误）。 */
    public static final String STATUS_NO_DATA = "NO_DATA";
    /** 需要用户补充信息（缺必填参数）。 */
    public static final String STATUS_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    /** 参数或业务规则不允许执行（如"引航费不该带货类"）。 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 通道降级（模型失败/输出不合规/工具不匹配）。 */
    public static final String STATUS_DEGRADED = "DEGRADED";

    public ToolResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        facts = facts == null ? List.of() : List.copyOf(facts);
        traces = traces == null ? List.of() : List.copyOf(traces);
    }

    public boolean ok() {
        return STATUS_OK.equals(status);
    }

    public static ToolResult ok(String tool, List<String> columns, List<List<String>> rows,
                                List<String> facts, List<String> traces) {
        return new ToolResult(tool, STATUS_OK, columns, rows, facts, traces, null, null);
    }

    public static ToolResult noData(String tool, List<String> facts, List<String> traces) {
        return new ToolResult(tool, STATUS_NO_DATA, List.of(), List.of(), facts, traces, null, null);
    }

    public static ToolResult clarify(String tool, String question, List<String> traces) {
        return new ToolResult(tool, STATUS_NEEDS_CLARIFICATION, List.of(), List.of(),
                List.of("需要补充信息后才能查询：" + question), traces, null, question);
    }

    public static ToolResult rejected(String tool, String reason, List<String> traces) {
        return new ToolResult(tool, STATUS_REJECTED, List.of(), List.of(),
                List.of("无法执行：" + reason), traces, null, reason);
    }

    public static ToolResult degraded(String tool, String kind, String reason, List<String> traces) {
        return new ToolResult(tool, STATUS_DEGRADED, List.of(), List.of(),
                List.of("结构化通道降级（" + kind + "）：" + reason), traces, kind, null);
    }
}
