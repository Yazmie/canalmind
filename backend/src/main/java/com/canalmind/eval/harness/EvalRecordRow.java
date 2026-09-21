package com.canalmind.eval.harness;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条待落库的评测记录（第 4 周第 4 项，决策 C-41）。
 *
 * <p><b>为什么这里不是照抄知识库第 550 行的 record，而是"可落库行"</b>：
 * 知识库那一版是**内存里的评测记录**（含若干运行时对象）；落库需要的是**列**。
 * 两者的差异已在 `V6__add_eval_record.sql` 文件头**逐条写明**（4 处显式偏离），
 * 此处不再重复——⛔ 但要遵守同一条纪律：**结构只此一份**，字段名与 DDL 必须字字相同。
 *
 * <p><b>为什么 {@code split} 是必需字段而不是可选标签</b>：`AGENTS.md` §6 第 4 条要求
 * "保留集不参与 Prompt 调整与规则调参"。第 3 周实测踩过「纪律依赖的开关其实是坏的」
 * （`工程复盘/数据一致性/保留集纪律依赖的开关其实是坏的/`）——纪律不能只靠"记得只看 dev"。
 * 把它做成 NOT NULL 列之后，任何"只统计 dev"的查询都是**可复核的 SQL**，而不是一句承诺。
 *
 * @param runId               本轮 Harness 运行 ID（同轮共享）。**必须由调用方显式给出**，
 *                            ⛔ 不由代码按时间生成——否则同一命令两次运行会产生不同 runId，
 *                            "同轮记录"就再也无法成组查询
 * @param taskId              题号（`L4-008`）
 * @param trialId             同一题的第几次运行，从 1 起
 * @param turnIndex           多轮题的第几轮；单轮题为 {@code null}
 * @param layer               L1–L6
 * @param split               dev / holdout
 * @param judgeKind           oracle / rule / reference_guided（C-39 判分矩阵）
 * @param outcomeStatus       SUCCESS / FAILED / PARTIAL
 * @param scores              各维度分数（落 JSONB）
 * @param confidence          置信信号；低置信进人工复核（Judge 的 needs_human_review 用 0.000 表达）
 * @param rootCauseLayer      失败归因层：DATA/MODEL/AGENT/TOOL/ENV/GRADER/**ROUTER**
 *                            （ROUTER 是 C-37⑤ 新增的取值——复合意图拆错要与答错分开记）
 */
public record EvalRecordRow(
        String runId, String taskId, int trialId, Integer turnIndex,
        String layer, String split, String judgeKind,
        String promptVersion, String modelId, String datasetVersion,
        java.time.LocalDate taskAsOf, String environmentVersion,
        String evidenceSnapshotUri, String inputHash,
        String rawInput, String referenceOutput, String actualOutput,
        String transcriptUri, String routeReason, String sessionId,
        String outcomeStatus, Map<String, Object> scores,
        String judgeModel, String graderVersion, String judgeReasoning,
        String errorCategory, String rootCauseLayer, Double confidence,
        String gitCommit) {

    public EvalRecordRow {
        scores = scores == null ? Map.of() : new LinkedHashMap<>(scores);
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 必须显式给出（⛔ 不许按时间隐式生成）");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (trialId < 1) {
            throw new IllegalArgumentException("trialId 从 1 起，实际为 " + trialId);
        }
    }

    /**
     * 记录 ID：**确定性派生**，不引入随机数与时间。
     *
     * <p>为什么：DDL 里 `(run_id, task_id, trial_id, turn_index)` 有唯一约束
     * （`eval_record_slot_uk`），重复插入应当**被数据库拦下**而不是悄悄写第二条。
     * 若 ID 是随机的，"重跑同一批"会变成"新增一批"，唯一约束就再也起不到防重的作用。
     */
    public String evalId() {
        return runId + ":" + taskId + ":" + trialId + ":" + (turnIndex == null ? "-" : turnIndex);
    }

    /** 供 SQL 内联展示用的一行摘要（⛔ 不含换行）。 */
    public String brief() {
        return taskId + " " + layer + "/" + split + " → " + outcomeStatus
                + (rootCauseLayer == null ? "" : "（归因 " + rootCauseLayer + "）");
    }
}
