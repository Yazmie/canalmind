package com.canalmind.eval.judge;

/** 评测裁判（第 4 周第 3 项，C-39）。接口化的目的与 {@code JsonChatInvoker} 相同：让评测编排可离线测。 */
public interface Judge {

    /** 判一条。**任何失败都必须落成 {@link JudgeScores#unparsed}，⛔ 不许抛异常打断整批评测。** */
    JudgeScores judge(JudgeInput input);

    /** Judge 用的模型标识，落进《评测报告》的版本区（同源偏差必须可见，见 {@link JudgePrompt}）。 */
    String modelName();
}
