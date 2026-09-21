package com.canalmind.eval.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把评测记录写入 `eval_record` 表（第 4 周第 4 项，决策 C-41）。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li><b>批量写入、失败即整体回滚</b>：一批记录要么全在、要么全不在。
 *       半批入库会让"分层通过率"的**分母**悄悄变小，而分母错了比分子错了更难发现。</li>
 *   <li><b>重复槽位交给数据库唯一约束裁决，⛔ 不做 upsert</b>：
 *       DDL 里有 `eval_record_slot_uk (run_id, task_id, trial_id, turn_index)`
 *       （`NULLS NOT DISTINCT`）。若这里改成 `ON CONFLICT DO UPDATE`，
 *       "同一轮跑了两遍"就会被静默掩盖——而"跑了两次且结果不同"恰恰最该被看见。</li>
 *   <li><b>{@code scores} 走 {@code ?::jsonb} 显式转换</b>：JDBC 无原生 JSONB 映射，
 *       传字符串再在 SQL 里转型是本项目已验证的写法。</li>
 * </ol>
 */
public final class EvalRecordWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ⚠️ 列名与顺序**必须**与 `V6__add_eval_record.sql` 字字相同（结构只此一份）。 */
    static final String INSERT_SQL = "INSERT INTO eval_record ("
            + "eval_id, run_id, task_id, trial_id, turn_index, layer, split, judge_kind, "
            + "prompt_version, model_id, dataset_version, task_as_of, environment_version, "
            + "evidence_snapshot_uri, input_hash, raw_input, reference_output, actual_output, "
            + "transcript_uri, route_reason, session_id, outcome_status, scores, "
            + "judge_model, grader_version, judge_reasoning, error_category, root_cause_layer, "
            + "confidence, git_commit"
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, "
            + "?, ?, ?, ?, ?, ?, ?)";

    private static final int COLUMN_COUNT = 30;

    private final JdbcTemplate jdbc;

    public EvalRecordWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 在构造时就校验"SQL 占位符数 == 绑定列数"。放在这里而不是测试里，
        // 是因为未来给 eval_record 加一列时最容易漏的就是这一处，而漏了会以
        // "参数索引越界"这种离题报错在写库那一刻才暴露。
        assertColumnCount();
    }

    /** 批量写入。返回写成功的行数；任何一行失败都抛异常（见类注释第 1 条）。 */
    public int insertAll(List<EvalRecordRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (EvalRecordRow row : rows) {
            args.add(bind(row));
        }
        jdbc.batchUpdate(INSERT_SQL, args);
        return rows.size();
    }

    /** 把一条记录摊成 30 个绑定值。⛔ 顺序必须与 {@link #INSERT_SQL} 的列序逐位对应。 */
    static Object[] bind(EvalRecordRow r) {
        Object[] a = new Object[COLUMN_COUNT];
        a[0] = r.evalId();
        a[1] = r.runId();
        a[2] = r.taskId();
        a[3] = r.trialId();
        a[4] = r.turnIndex();
        a[5] = r.layer();
        a[6] = r.split();
        a[7] = r.judgeKind();
        a[8] = r.promptVersion();
        a[9] = r.modelId();
        a[10] = r.datasetVersion();
        a[11] = r.taskAsOf();
        a[12] = r.environmentVersion();
        a[13] = r.evidenceSnapshotUri();
        a[14] = r.inputHash();
        a[15] = r.rawInput();
        a[16] = r.referenceOutput();
        a[17] = r.actualOutput();
        a[18] = r.transcriptUri();
        a[19] = r.routeReason();
        a[20] = r.sessionId();
        a[21] = r.outcomeStatus();
        a[22] = toJson(r.scores());
        a[23] = r.judgeModel();
        a[24] = r.graderVersion();
        a[25] = r.judgeReasoning();
        a[26] = r.errorCategory();
        a[27] = r.rootCauseLayer();
        a[28] = r.confidence();
        a[29] = r.gitCommit();
        return a;
    }

    /** 分数入 JSONB。⛔ 键序稳定（TreeMap），否则同一份分数两次落库文本不同。 */
    static String toJson(Map<String, Object> scores) {
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(scores));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分数无法序列化为 JSON：" + scores, e);
        }
    }

    static void assertColumnCount() {
        int marks = 0;
        for (int i = 0; i < INSERT_SQL.length(); i++) {
            if (INSERT_SQL.charAt(i) == '?') {
                marks++;
            }
        }
        if (marks != COLUMN_COUNT) {
            throw new IllegalStateException(
                    "INSERT 占位符 " + marks + " 个，但绑定 " + COLUMN_COUNT + " 列——改 DDL 时漏改了这里");
        }
    }

    /** 自检用：按 run_id 计数（**走数据库**，而不是数自己的循环变量）。 */
    public int countByRun(String runId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM eval_record WHERE run_id = ?",
                Integer.class, runId);
        return n == null ? 0 : n;
    }

    /** 自检用：按层/分片/判分方式/结论统计，直接考数据库里的行。 */
    public List<Map<String, Object>> summarizeByLayer(String runId) {
        return jdbc.queryForList("SELECT layer, split, judge_kind, outcome_status, count(*) AS n"
                + " FROM eval_record WHERE run_id = ?"
                + " GROUP BY layer, split, judge_kind, outcome_status"
                + " ORDER BY layer, split, judge_kind, outcome_status", runId);
    }
}
