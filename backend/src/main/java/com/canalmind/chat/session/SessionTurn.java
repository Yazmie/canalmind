package com.canalmind.chat.session;

/**
 * 会话里的一轮问答：问题 + 应答 + **该轮真实用过的结构化参数原文**。
 *
 * <p><b>{@code structuredParamsJson} 是这一层唯一"不许被摘要吃掉"的东西</b>（决策 C-38③）。
 * 它存的是该轮工具调用的原始参数文本，例如
 * {@code quote_fee(fee_type=过闸费,tonnage=1200,biz_date=2026-12-10)}（`SessionTurn` 的用法示例）。
 * 理由是数据集自己的要求——{@code docs/评测报告/golden-set-v3.json} 里三条题的**第二轮**
 * 正确答案必须取自第一轮的真实返回值：
 * <ul>
 *   <li>{@code L4-008} 第 1762 行 {@code min_gross_tonnage=1520}（{@code rewriteNote} 在第 1767 行）；</li>
 *   <li>{@code L4-019} 第 2157 行 {@code date_from=2026-11-26}（{@code rewriteNote} 在第 2164 行）；</li>
 *   <li>{@code L4-023} 第 2297 行 {@code tonnage=2774}（{@code rewriteNote} 在第 2301 行）。</li>
 * </ul>
 * 这些数值一旦只存在于"摘要的复述"里，就从**可执行参数**退化成**模型的记忆**：
 * 金额/吨位/日期被模型"记得差不多"时，错误会以"数字差一点"的形式静默出现。
 * 故 {@link ContextCompactor} 只允许折叠最后 {@code keepRecentTurns} 轮**之前**的轮次。
 *
 * <p><b>为什么 {@code index} 从 1 开始、且必须 ≥ 1</b>：{@link SessionState#summarizedUpTo()}
 * 用「{@code index < summarizedUpTo} 即已被摘要代表」表达覆盖范围，{@code 0} 表示"什么都没摘要"。
 * 若允许 {@code index=0}，第 0 轮就永远落在"既不算被摘要、也不是后继轮次"的模糊位置，
 * 边界会立刻变成需要额外约定的东西——本项目的纪律是**边界只钉在一处**
 * （`AGENTS.md` §7.2 第 4 条：归一化/边界要钉在数据边界上，⛔ 不要在多个使用点各自容错）。
 * 数据集里的轮次也是 1 起（{@code golden-set-v3.json} 的 {@code "turn": 1}）。
 *
 * @param index                轮次序号，**从 1 开始**且同一会话内递增
 * @param question             该轮用户原话（⛔ 不是改写后的检索查询或工具入参）
 * @param answer               该轮应答正文；未答/降级时允许为空串
 *                            （此时 {@link ContextAssembler} **不发**空助手消息，见 {@link ContextMessage}）
 * @param channel              该轮实际走的通道（structured / retrieval / composite / …），供排查与评测
 * @param structuredParamsJson 该轮工具调用的**原始参数文本**；该轮没有结构化调用时为空串
 */
public record SessionTurn(int index, String question, String answer, String channel,
                          String structuredParamsJson) {

    public SessionTurn {
        if (index < 1) {
            throw new IllegalArgumentException("index 必须 >= 1（理由见类注释），收到：" + index);
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不得为空（index=" + index + "）");
        }
        answer = answer == null ? "" : answer;
        channel = channel == null ? "" : channel;
        structuredParamsJson = structuredParamsJson == null ? "" : structuredParamsJson;
    }

    /** 该轮是否有可执行的结构化参数原文（决定 {@code 最近原始参数} 块里是否出现这一行）。 */
    public boolean hasStructuredParams() {
        return !structuredParamsJson.isBlank();
    }

    /** 无结构化参数的一轮（纯检索/纯规则问答）的简写。 */
    public static SessionTurn of(int index, String question, String answer, String channel) {
        return new SessionTurn(index, question, answer, channel, "");
    }
}
