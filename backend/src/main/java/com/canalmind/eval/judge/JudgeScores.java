package com.canalmind.eval.judge;

import java.util.List;

/**
 * Judge 的评分结果（第 4 周第 3 项，决策 C-39）。
 *
 * <p><b>为什么解析失败要单独记一个 {@code parseOk}，而不是"当低分处理"</b>：
 * "Judge 说这个回答很差"与"Judge 的输出我根本没读懂"是两件完全不同的事，
 * 前者是**被测系统的结论**，后者是**评测工具自身的故障**。
 * 混在一起会让"工具坏了"伪装成"模型很差"——本项目在自检脚本上已经踩过一次同类坑
 * （判据解析自己的打印文本，把成功认成失败）。
 *
 * @param faithfulness    事实忠实度 1–5；解析失败时为 0（0 表示"未取得有效评分"）
 * @param relevance       答案相关性 1–5
 * @param completeness    完整性 1–5
 * @param needsHumanReview Judge 自述证据不足、需要人工复核（知识库第 212 行刻意保留的出口）
 * @param reasoning       一句话评分依据
 * @param parseOk         Judge 的应答是否被成功解析成合法三维评分
 * @param rawResponse     Judge 的**原始应答**（⛔ 一律保留：排查要能分清"判错了"与"没读懂"）
 * @param attempts        实际调用次数：**1 = 未重试**，**2 = 因空应答重试过 1 次**
 *                        （2026-09-23，C-62：只对**空应答**重试、上限 1 次）
 */
public record JudgeScores(int faithfulness, int relevance, int completeness,
                          boolean needsHumanReview, String reasoning,
                          boolean parseOk, String rawResponse, int attempts) {

    /** 是否因空应答重试过（供产物写 `judgeAttempts` / 审计用）。 */
    public boolean retried() {
        return attempts > 1;
    }

    /** 三个维度的最小值（用于"最低维度是否达标"的严格口径）。 */
    public int minScore() {
        return Math.min(faithfulness, Math.min(relevance, completeness));
    }

    /** 三维均分；解析失败返回 0。 */
    public double mean() {
        return (faithfulness + relevance + completeness) / 3.0;
    }

    /** 有效评分：解析成功且三维都在 1–5 内。 */
    public boolean valid() {
        return parseOk && inRange(faithfulness) && inRange(relevance) && inRange(completeness);
    }

    /**
     * 严格口径通过：三维**都**达到 {@code minEach} 且未被标记人工复核。
     *
     * <p>⚠️ 口径会显著影响通过率，所以《评测报告》必须**同时**给出严格口径与均分口径
     * （见 {@link #passLenient(double)}），⛔ 不得只报一个数。
     */
    public boolean passStrict(int minEach) {
        return valid() && !needsHumanReview && minScore() >= minEach;
    }

    /** 宽松口径通过：仅看三维均分（用于做"阈值敏感性"对照，不作为主口径）。 */
    public boolean passLenient(double minMean) {
        return valid() && !needsHumanReview && mean() >= minMean;
    }

    /** 解析失败时的结果：保留原文，标为需人工复核，⛔ 不伪装成低分（记 **1** 次调用）。 */
    public static JudgeScores unparsed(String rawResponse, String reason) {
        return unparsed(rawResponse, reason, 1);
    }

    /**
     * 同上，但显式记录调用次数（**2** = 空应答触发过 1 次重试）。
     *
     * <p>⚠️ 重试过仍失败时，{@code reason} 必须**写明"重试 1 次后仍…"**，产物里也会带
     * {@code judgeAttempts=2} —— 否则事后无法区分"一次就空"与"重试过还空"，
     * 而这两者对"是否该升级为点估计"的结论是一样的：**都不能升级**。
     */
    public static JudgeScores unparsed(String rawResponse, String reason, int attempts) {
        return new JudgeScores(0, 0, 0, true, reason, false,
                rawResponse == null ? "" : rawResponse, attempts);
    }

    public List<Integer> asList() {
        return List.of(faithfulness, relevance, completeness);
    }

    private static boolean inRange(int v) {
        return v >= 1 && v <= 5;
    }
}
