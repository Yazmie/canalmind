package com.canalmind.structured;

import java.util.List;

/**
 * 参数抽取的结果（**含失败与降级路径**，决策 C-32 / 任务 4）。
 *
 * <p>本记录刻意把"试了几次、每次错在哪、原样应答是什么"全带上：
 * 结构化输出最容易出的问题是"模型输出畸形 → 服务端静默当没抽到"，
 * 那样评测时只能看到"答不出"，看不到**为什么**答不出。
 *
 * @param status         {@link #STATUS_OK} / {@link #STATUS_NEEDS_CLARIFICATION} /
 *                       {@link #STATUS_REJECTED} / {@link #STATUS_DEGRADED}
 * @param degradeKind    降级**或拒绝**的原因，正常为 {@code null}；见 {@link #KIND_*}
 * @param call           抽到的调用（降级为 {@code null}；**拒绝时保留**，因为拒绝理由要指名工具与参数）
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
    /** 抽取产出了调用，但**参数取值越出了契约允许范围**（值越界 / 枚举不符）⇒ 拒绝执行。 */
    public static final String STATUS_REJECTED = "REJECTED";
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
    /**
     * **参数值越界 / 枚举不符**（决策 D24 → 缺口①）→ 拒绝执行。
     *
     * <p>⚠️ 与 {@link #KIND_SCHEMA_INVALID} 的区别是语义性的，⛔ 不是措辞问题：
     * 前者是"**模型把用户确实说了的、但契约不允许的取值原样送进来了**"
     * （如用户明确要求 {@code limit=10000}、把注入串当分组维度），此时重试也改不对
     * ——因为要改的是**用户的诉求**，不是模型的口径 ⇒ 应如实拒绝；
     * 后者是"模型没按契约说话"（缺必填、多余键、格式错）⇒ 重试后降级，交回答层提示重述。
     */
    public static final String KIND_PARAM_VIOLATION = "PARAM_VIOLATION";
    /** 业务日（合法日期）落在业务数据窗口之外 ⇒ 本层没有该区间的数据与费率。 */
    public static final String KIND_OUT_OF_WINDOW = "OUT_OF_WINDOW";
    /** 计费吨位远超仿真业务数据量级（见 {@code SimulatedDataScope}）⇒ 口径外，不得当真实报价。 */
    public static final String KIND_MAGNITUDE_OUT_OF_RANGE = "MAGNITUDE_OUT_OF_RANGE";

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

    public boolean rejected() {
        return STATUS_REJECTED.equals(status);
    }

    public boolean needsClarification() {
        return STATUS_NEEDS_CLARIFICATION.equals(status);
    }

    public static ExtractionOutcome success(ToolCall call, int attempts, List<String> raw, long latencyMs) {
        return new ExtractionOutcome(STATUS_OK, null, call, List.of(), attempts, raw, latencyMs, null);
    }

    /**
     * 抽取出了调用，但参数**取值本身就违规**（值越界 / 枚举不符）⇒ 拒绝执行。
     *
     * <p>⚠️ 此处**刻意保留 {@code call}**（与 {@link #degraded} 不同）：拒绝理由必须指名
     * "哪个工具的哪个参数取了什么值"，否则回答层只能笼统说"参数不合法"，
     * 而数据集要求的是"说明 limit 上限为 50、10000 超出契约范围"这类**可核对**的话术。
     */
    public static ExtractionOutcome rejected(ToolCall call, List<String> errors, int attempts,
                                             List<String> raw, long latencyMs) {
        return new ExtractionOutcome(STATUS_REJECTED, KIND_PARAM_VIOLATION, call, errors, attempts,
                raw, latencyMs, null);
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
