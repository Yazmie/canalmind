package com.canalmind.eval.judge;

import java.util.List;

/**
 * 交给 Judge 的一次评判请求（第 4 周第 3 项，决策 C-39）。
 *
 * @param taskId        评测题号（`L3-001` 这种），落进 {@code eval_record.task_id}
 * @param layer         层次（L3/L5/L6 —— ⛔ 只有这三层用 Judge，理由见 {@link JudgePrompt}）
 * @param question      用户问题原文
 * @param context       检索段实际召回的语料片段（拼好的文本；无则空串）
 * @param referenceAnswer 参考结论。取自数据集的 {@code expected.answerMustContain} /
 *                        {@code expected.whyNoAnswer} 等字段，**⛔ 不得由代码编造**
 * @param rubric        该题**专属**评分要点。取自数据集每条题目的 {@code rubric} 字段——
 *                       本项目的数据集本来就为每道题写了精确判据，Judge 不该退化成只看通用三维
 * @param oracleFacts   服务端**已经核对过**的事实（如确定性费用引擎算出的金额、工具实际返回的状态）。
 *                       ⛔ 这些事实是判"数字对不对"的唯一依据，Judge 不许自己重算（知识库第 196 行的禁令：
 *                       数学/SQL 的正确性不能只交给 LLM Judge）
 * @param answer        被测系统的回答原文
 */
public record JudgeInput(String taskId, String layer, String question, String context,
                         String referenceAnswer, String rubric, List<String> oracleFacts,
                         String answer) {

    public JudgeInput {
        oracleFacts = oracleFacts == null ? List.of() : List.copyOf(oracleFacts);
        context = orEmpty(context);
        referenceAnswer = orEmpty(referenceAnswer);
        rubric = orEmpty(rubric);
        answer = orEmpty(answer);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
