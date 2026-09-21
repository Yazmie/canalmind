package com.canalmind.structured;

import java.util.List;

/**
 * 参数抽取的结果（**含失败与降级路径**，决策 C-32 / 任务 4）。
 *
 * <p>本记录刻意把"试了几次、每次错在哪、原样应答是什么"全带上：
 * 结构化输出最容易出的问题是"模型输出畸形 → 服务端静默当没抽到"，
 * 那样评测时只能看到"答不出"，看不到**为什么**答不出。
 *
 * @param status         {@link #STATUS_OK} / {@link #STATUS_NEEDS_CLARIFICATION} / {@link #STATUS_DEGRADED}
 * @param degradeKind    降级原因，正常为 {@code null}；见 {@link #KIND_*}
 * @param call           抽到的调用（降级为 {@code null}）
 * @param errors         校验错误逐条（**会原样喂回给模型重试**，也落进评测产物）
 * @param attempts       实际调用模型次数（1 … 1+maxRetries）
 * @param rawResponses   每次模型的原始应答（排查用；不截断，长度由调用方控制）
 * @param latencyMs      累计耗时（含重试）
 * @param clarification  追问话术（status = NEEDS_CLARIFICATION 时非空）
 */
public record ExtractionOutcome(String status, String degradeKind, ToolCall call,
                               List<String> errors, int attempts, List<String> rawResponses,
                               long latencyMs, String clarification) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    public static final String STATUS_DEGRADED = "DEGRADED";

    /** 模型调用本身失败（网络/鉴权/超时）——**与"模型输出了非法 JSON"是两件事**。 */
    public static final String KIND_MODEL_ERROR = "MODEL_ERROR";
    /** 应答不是合法 JSON。 */
    public static final String KIND_NOT_JSON = "NOT_JSON";
    /** 应答是 JSON，但不满足 envelope 或该工具的参数 Schema。 */
    public static final String KIND_SCHEMA_INVALID = "SCHEMA_INVALID";
    /** 模型选了不存在的工具名。 */
    public static final String KIND_TOOL_UNKNOWN = "TOOL_UNKNOWN";
    /** 模型判定"不属于工具集"（tool=none）→ 交给检索通道。 */
    public static final String KIND_TOOL_NONE = "TOOL_NONE";
    /** 参数不齐（模型申报 missing，或业务校验发现缺参）→ 追问。 */
    public static final String KIND_MISSING_PARAMS = "MISSING_PARAMS";
    /** 业务校验不通过（如日期不在业务窗口、引航费不该带货类）→ 追问或如实说明。 */
    public static final String KIND_BUSINESS_REJECTED = "BUSINESS_REJECTED";

    public ExtractionOutcome {
        errors = errors == null ? List.of() : List.copyOf(errors);
        rawResponses = rawResponses == null ? List.of() : List.copyOf(rawResponses);
    }

    public boolean ok() {
        return STATUS_OK.equals(status);
    }

    public boolean degraded() {
        return STATUS_DEGRADED.equals(status);
    }

    public boolean needsClarification() {
        return STATUS_NEEDS_CLARIFICATION.equals(status);
    }

    public static ExtractionOutcome success(ToolCall call, int attempts, List<String> raw, long latencyMs) {
        return new ExtractionOutcome(STATUS_OK, null, call, List.of(), attempts, raw, latencyMs, null);
    }

    public static ExtractionOutcome clarify(ToolCall call, String kind, String question,
                                            List<String> errors, int attempts, List<String> raw, long latencyMs) {
        return new ExtractionOutcome(STATUS_NEEDS_CLARIFICATION, kind, call, errors, attempts, raw, latencyMs, question);
    }

    public static ExtractionOutcome degraded(String kind, List<String> errors, int attempts,
                                             List<String> raw, long latencyMs, String clarification) {
        return new ExtractionOutcome(STATUS_DEGRADED, kind, null, errors, attempts, raw, latencyMs, clarification);
    }
}
