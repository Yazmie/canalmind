package com.canalmind.eval.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-52：Judge 类三层（L3/L5/L6）落库读取器的契约测试。
 *
 * <p>为什么必须有这组测试：该读取器要在**已有产物**上补出"缺的那一层落库通道"，
 * 而产物的条目 schema **三层不完全一致**（L6 多 3 个规则键）。
 * 一旦照 L3/L5 的键集一刀切，L6 最该被看到的**状态规则校验结果会被静默丢掉**——
 * 丢字段不报错、记录条数也仍然对，是典型的"合法但错"。
 */
class ArtifactIngestJudgeTest {

    private static Path write(Path dir, String name, String json) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** 数据集夹具：只需 `id` 与 `question`（题号用于反证，题面用于回填 rawInput）。 */
    private static Path golden(Path dir) throws IOException {
        return write(dir, "golden-set-v3.json",
                "{\"version\":\"v3\",\"items\":["
                        + "{\"id\":\"L3-001\",\"split\":\"dev\",\"question\":\"问题一\"},"
                        + "{\"id\":\"L3-002\",\"split\":\"dev\",\"question\":\"问题二\"},"
                        + "{\"id\":\"L3-003\",\"split\":\"holdout\",\"question\":\"问题三\"}]}");
    }

    /** L3/L5 形态：条目里**没有** rule* 三键。 */
    private static final String L3_BODY = "{"
            + "\"layer\":\"L3\",\"answersSource\":\"layer-L3-answers-v1.json\","
            + "\"strictMinEach\":4,\"lenientMinMean\":4.0,"
            + "\"judgeModel\":\"deepseek-v4-flash\",\"datasetTotal\":3,\"judgedCount\":3,"
            + "\"judged\":["
            + "{\"taskId\":\"L3-001\",\"split\":\"dev\",\"faithfulness\":5,\"relevance\":5,"
            + "\"completeness\":5,\"mean\":5.0,\"minScore\":5,\"needsHumanReview\":false,"
            + "\"parseOk\":true,\"passStrict\":true,\"passLenient\":true,\"reasoning\":\"达标\"},"
            + "{\"taskId\":\"L3-002\",\"split\":\"dev\",\"faithfulness\":4,\"relevance\":4,"
            + "\"completeness\":3,\"mean\":3.6667,\"minScore\":3,\"needsHumanReview\":true,"
            + "\"parseOk\":true,\"passStrict\":false,\"passLenient\":true,\"reasoning\":\"宽松过\"},"
            + "{\"taskId\":\"L3-003\",\"split\":\"holdout\",\"faithfulness\":3,\"relevance\":3,"
            + "\"completeness\":2,\"mean\":2.6667,\"minScore\":2,\"needsHumanReview\":true,"
            + "\"parseOk\":false,\"passStrict\":false,\"passLenient\":false,\"reasoning\":\"都没过\"}"
            + "]}";

    /** L6 形态：条目里**多出** rule* 三键。 */
    private static final String L6_BODY = "{"
            + "\"layer\":\"L6\",\"answersSource\":\"layer-L6-answers-v1.json\","
            + "\"strictMinEach\":4,\"lenientMinMean\":4.0,"
            + "\"judgeModel\":\"deepseek-v4-flash\",\"datasetTotal\":1,\"judgedCount\":1,"
            + "\"judged\":[{\"taskId\":\"L6-001\",\"split\":\"dev\",\"faithfulness\":5,\"relevance\":5,"
            + "\"completeness\":5,\"mean\":5.0,\"minScore\":5,\"needsHumanReview\":false,"
            + "\"parseOk\":true,\"passStrict\":true,\"passLenient\":true,\"reasoning\":\"追问到位\","
            + "\"ruleExpectedStatus\":\"NEEDS_CLARIFICATION\","
            + "\"ruleActualStatus\":\"NEEDS_CLARIFICATION\",\"rulePass\":true}]}";

    @Test
    @DisplayName("L3 形态：三层状态由 passStrict/passLenient 派生，题面回填，promptVersion 刻意留空")
    void readsStrictLenientIntoOutcomeStatus(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path p = write(dir, "layer-L3-judged-v1.json", L3_BODY);
        List<EvalRecordRow> rows = ArtifactIngest.readJudge(p, g, "run-test", "abc1234");

        assertEquals(3, rows.size());
        assertEquals("SUCCESS", rows.get(0).outcomeStatus());
        assertEquals("PARTIAL", rows.get(1).outcomeStatus());
        assertEquals("FAILED", rows.get(2).outcomeStatus());

        assertEquals("L3", rows.get(0).layer());
        assertEquals("reference_guided", rows.get(0).judgeKind());
        assertEquals("deepseek-v4-flash", rows.get(0).judgeModel());
        assertEquals("dev", rows.get(0).split());
        assertEquals("holdout", rows.get(2).split());
        assertEquals("达标", rows.get(0).judgeReasoning());
        assertEquals("问题一", rows.get(0).rawInput(), "题面应从数据集回填，否则记录查不动");

        // `dataset_version` 是 NOT NULL：由数据集文件哈希给出（且题号已逐条反证过）
        assertNotNull(rows.get(0).datasetVersion(), "dataset_version 不得为空（DDL 是 NOT NULL）");
        assertEquals(rows.get(0).datasetVersion(), rows.get(2).datasetVersion());

        // ⚠️ 刻意留空：judged 产物里没有 Prompt 版本字段。
        // ⛔ 不得用 answersSource 的文件名硬凑成 promptVersion——那是"答案产物版本"，不是 Prompt 版本。
        assertNull(rows.get(0).promptVersion(),
                "promptVersion 必须留空：产物无此字段，用文件名冒充就是合法但错");
        // 答案正文在同目录 answers 产物里，本轮未接入（已登记 C-52）
        assertNull(rows.get(0).referenceOutput());
        assertNull(rows.get(0).actualOutput());
        assertTrue(rows.get(0).evidenceSnapshotUri().contains("layer-L3-judged-v1.json"),
                "证据出处必须能定位到具体产物");
    }

    @Test
    @DisplayName("条目带 rule* 三键时必须落进 scores（L6 全条带、L5 部分条带；⛔ 按层分支或一刀切都会丢）")
    void preservesL6OnlyRuleKeys(@TempDir Path dir) throws IOException {
        Path g = write(dir, "golden-set-v3.json",
                "{\"version\":\"v3\",\"items\":[{\"id\":\"L6-001\",\"split\":\"dev\",\"question\":\"问六\"}]}");
        Path p = write(dir, "layer-L6-judged-v1.json", L6_BODY);
        List<EvalRecordRow> rows = ArtifactIngest.readJudge(p, g, "run-test", "abc1234");

        assertEquals(1, rows.size());
        assertEquals("L6", rows.get(0).layer());
        var scores = rows.get(0).scores();
        assertEquals(1, scores.get("rulePass"), "L6 的 rulePass 被丢了——L6 最该被看到的正是它");
        assertEquals("NEEDS_CLARIFICATION", scores.get("ruleExpectedStatus"));
        assertEquals("NEEDS_CLARIFICATION", scores.get("ruleActualStatus"));
        assertEquals(5, scores.get("faithfulness"));
        assertEquals(5, scores.get("completeness"));
    }

    @Test
    @DisplayName("L3/L5 形态不含 rule* 键时，scores 里也不该凭空多出这三个键")
    void doesNotInventRuleKeys(@TempDir Path dir) throws IOException {
        Path p = write(dir, "layer-L3-judged-v1.json", L3_BODY);
        var scores = ArtifactIngest.readJudge(p, golden(dir), "run-test", "abc1234").get(0).scores();
        assertFalse(scores.containsKey("rulePass"),
                "L3 没有规则校验，凭空补 rulePass=0 会把「没做这项检查」误报成「检查没过」");
    }

    @Test
    @DisplayName("needsHumanReview 用 confidence=0.000 表达（与 EvalRecordRow 既有约定一致）")
    void needsHumanReviewBecomesZeroConfidence(@TempDir Path dir) throws IOException {
        Path p = write(dir, "layer-L3-judged-v1.json", L3_BODY);
        List<EvalRecordRow> rows = ArtifactIngest.readJudge(p, golden(dir), "run-test", "abc1234");
        assertNull(rows.get(0).confidence(), "不需复核时为 null");
        assertEquals(0.0, rows.get(1).confidence(), "需复核时用 0.000 表达");
    }

    @Test
    @DisplayName("⛔ 非 Judge 层必须拒绝：不得把 L4 之类悄悄按 Judge 口径落库")
    void rejectsNonJudgeLayer(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path p = write(dir, "layer-L4-judged-v1.json", L3_BODY.replace("\"L3\"", "\"L4\""));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ArtifactIngest.readJudge(p, g, "run-test", "abc1234"));
        assertTrue(ex.getMessage().contains("L4"), "报错要带上实际层名，便于定位: " + ex.getMessage());
    }

    @Test
    @DisplayName("⛔ 题号在数据集里找不到必须报错（数据集拿错了要当场炸，不是静默错配）")
    void rejectsTaskIdMissingFromDataset(@TempDir Path dir) throws IOException {
        Path g = golden(dir); // 该数据集里没有 L6-001
        Path p = write(dir, "layer-L6-judged-v1.json", L6_BODY);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readJudge(p, g, "run-test", "abc1234"));
        assertTrue(ex.getMessage().contains("L6-001"), "报错要点出是哪个题号: " + ex.getMessage());
    }

    @Test
    @DisplayName("⛔ judgedCount 与实际条数不一致必须报错（分母错了比分子错了更难发现）")
    void rejectsCountMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path p = write(dir, "layer-L3-judged-v1.json",
                L3_BODY.replace("\"judgedCount\":3", "\"judgedCount\":99"));
        assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readJudge(p, g, "run-test", "abc1234"));
    }

    @Test
    @DisplayName("⛔ judged 为空必须报错，不得落 0 条后当作成功")
    void rejectsEmptyJudged(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        String empty = L3_BODY.replaceAll("\"judged\":\\[.*\\]", "\"judged\":[]")
                .replace("\"judgedCount\":3", "\"judgedCount\":-1");
        Path p = write(dir, "layer-L3-judged-v1.json", empty);
        assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readJudge(p, g, "run-test", "abc1234"));
    }
}
