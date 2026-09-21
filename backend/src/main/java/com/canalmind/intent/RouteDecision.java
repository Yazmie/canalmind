package com.canalmind.intent;

import java.util.List;

/**
 * 一次意图路由的结论（决策 C-37）。
 *
 * <p><b>为什么路由结论要是一个可落库的对象，而不是一个枚举值</b>：规格书第 198 行
 * 引知识库《大模型网关详解》第 150 行的警告——「调用结束后重新路由再记 usage，会把
 * A 产生的费用记到 B 头上」；同文件第 354 行进一步要求「路由日志除模型名外还要记录
 * {@code route_reason}，否则无法还原这次选择依据」。所以路由结论必须**在调用开始前
 * 固定**，并带着"凭什么这么判"一起落进 Trace 与 {@code eval_record.route_reason}。
 *
 * @param channel        见 {@link #CHANNEL_*}；复合意图取 {@code COMPOSITE}
 * @param steps          **串行**执行步骤（决策 C-37③：⛔ 不并行）。
 *                       单通道时长度为 1；复合时为 2（先结构化、后检索）
 * @param ruleId         命中的规则 id；分类器兜底时为 {@code R5-…}；兜底失败为 {@code R6-…}
 * @param reason         {@code route_reason} 正文——**写给排查的人看**，要能一眼看出
 *                       命中了哪条规则、命中了哪些词
 * @param classifierUsed 是否走了分类器（即规则未命中）
 * @param classifierRaw  分类器的**原始应答**（未走分类器时为空串）。
 *                       ⚠️ 刻意保留原文：路由错了要能分清"规则表没覆盖"还是"分类器答错了"，
 *                       否则两类失败长得一模一样（本项目反复踩过这一点）
 * @param confidence     规则命中为 1.0；分类器取模型自报值；兜底路径为 0.0
 */
public record RouteDecision(String channel, List<RouteStep> steps,
                            String ruleId, String reason, boolean classifierUsed,
                            String classifierRaw, double confidence) {

    /** 走结构化通道（受控工具集）。 */
    public static final String CHANNEL_STRUCTURED = "STRUCTURED";
    /** 走文档检索通道。 */
    public static final String CHANNEL_RETRIEVAL = "RETRIEVAL";
    /** 复合意图：拆成串行多步（当前实现固定为「结构化 → 检索」两步）。 */
    public static final String CHANNEL_COMPOSITE = "COMPOSITE";

    public RouteDecision {
        steps = steps == null ? List.of() : List.copyOf(steps);
        classifierRaw = classifierRaw == null ? "" : classifierRaw;
    }

    public boolean composite() {
        return CHANNEL_COMPOSITE.equals(channel);
    }

    /** 落 Trace / 落库用的紧凑单行摘要（⛔ 不含换行，避免污染单行字段）。 */
    public String traceLine() {
        return "channel=" + channel + "; rule=" + ruleId + "; steps=" + steps.size()
                + "; classifier=" + classifierUsed + "; confidence=" + confidence
                + "; reason=" + reason;
    }

    /**
     * 一个串行步骤。
     *
     * @param channel 该步走的通道
     * @param query   该步实际送进通道的文本。
     *                ⚠️ 复合意图的两步都用**原问题全文**：结构化步需要全部参数
     *                （参数散落在整句里），检索步需要完整语境（"那跟这笔港杂费
     *                有没有关系"这类第二问**脱离前文无法检索**）
     * @param focus   该步**聚焦的子问句**（供回答层组织分段、供排查人看懂拆解结果）
     */
    public record RouteStep(String channel, String query, String focus) {
    }
}
