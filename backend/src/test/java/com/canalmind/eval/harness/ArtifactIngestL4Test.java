package com.canalmind.eval.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-53b：L4（多轮参数继承）落库读取器的契约测试。
 *
 * <p>为什么必须有这组测试：L4 是一条**规则判分**通道（无 Judge），
 * 它的"分数"完全由产物里的 `itemPass` / `turnsPassed` 决定，
 * 而它的"归因"必须由工具自己的状态派生。这两件事都极易**静默出错**：
 * <ul>
 *   <li>把环级记录写成题级（或反之）会让 L4 的通过率变成 36/50 而不是 15/25，
 *       **与其他五层的分母口径不再可比**——而两条 SQL 都能跑出数字；</li>
 *   <li>只认 `actualTool != expectTool` 这一处工具错证据时，产物形态一变就**静默漏判成"参数错"**。</li>
 * </ul>
 * 故这里既有正向断言，也有**注入式负例**（声明值/题号/题面/分片/failed 清单各改坏一处），
 * 确认每条对账**真的会报错**——"一个从不失败的检查，无法证明它有效"。
 */
class ArtifactIngestL4Test {

    private static Path write(Path dir, String name, String json) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** 数据集夹具：题号用于**反证**、题面用于逐字比对、split 用于保留集纪律。 */
    private static Path golden(Path dir) throws IOException {
        return write(dir, "golden-set-v3.json", """
                {"version":"v3","items":[
                  {"id":"L4-001","split":"dev","question":"问一"},
                  {"id":"L4-002","split":"dev","question":"问二"},
                  {"id":"L4-003","split":"holdout","question":"问三"}]}
                """);
    }

    private static Path artifact(Path dir, String json) throws IOException {
        return write(dir, "layer-L4-inheritance-v1.json", json);
    }

    /**
     * 基准产物：3 题 5 轮，两题未通过，且**两种失败形态各一份**——
     * `L4-002` 是纯参数错（工具对、实参错），`L4-003` 是"该答却追问"。
     */
    private static final String BASE = """
            {
              "layer":"L4","dataset":"golden-set-v3.json","businessToday":"2027-09-15",
              "note":"L4 判据=工具+实参（规则判分、零付费）",
              "results":[
                {"taskId":"L4-001","split":"dev","question":"问一","rubric":"判据一",
                 "turns":[
                   {"turn":1,"question":"问一","expectTool":"query_tariff","actualTool":"query_tariff",
                    "extractionStatus":"OK","degraded":false,"needsClarification":false,"toolStatus":"OK",
                    "turnPass":true,"expectArgs":{"tonnage":"1200"},"actualArgs":{"tonnage":"1200"},"argFails":[]},
                   {"turn":2,"question":"追问一","expectTool":"query_tariff","actualTool":"query_tariff",
                    "extractionStatus":"OK","degraded":false,"needsClarification":false,"toolStatus":"OK",
                    "turnPass":true,"expectArgs":{"biz_date":"2027-01-05"},"actualArgs":{"biz_date":"2027-01-05"},
                    "argFails":[]}],
                 "itemPass":true,"turnsPassed":2,"turnsTotal":2,"summarizerCalls":0,"finishedAt":"10:00:00"},
                {"taskId":"L4-002","split":"dev","question":"问二","rubric":"判据二",
                 "turns":[
                   {"turn":1,"question":"问二","expectTool":"query_waybill","actualTool":"query_waybill",
                    "extractionStatus":"OK","degraded":false,"needsClarification":false,"toolStatus":"OK",
                    "turnPass":true,"expectArgs":{"limit":"20"},"actualArgs":{"limit":"20"},"argFails":[]},
                   {"turn":2,"question":"追问二","expectTool":"query_waybill","actualTool":"query_waybill",
                    "extractionStatus":"OK","degraded":false,"needsClarification":false,"toolStatus":"OK",
                    "turnPass":false,"expectArgs":{"date_from":"2026-09-01"},
                    "actualArgs":{},"argFails":["参数 date_from：期望 2026-09-01，实际 （缺失）"]}],
                 "itemPass":false,"turnsPassed":1,"turnsTotal":2,"summarizerCalls":0,"finishedAt":"10:00:01"},
                {"taskId":"L4-003","split":"holdout","question":"问三","rubric":"判据三",
                 "turns":[
                   {"turn":1,"question":"问三","expectTool":"quote_fee","actualTool":"quote_fee",
                    "extractionStatus":"NEEDS_CLARIFICATION","degraded":false,"needsClarification":true,
                    "toolStatus":"NEEDS_CLARIFICATION","turnPass":false,
                    "expectArgs":{"tonnage":"2774"},"actualArgs":{},
                    "argFails":["参数 tonnage：期望 2774，实际 （缺失）"]}],
                 "itemPass":false,"turnsPassed":0,"turnsTotal":1,"summarizerCalls":0,"finishedAt":"10:00:02"}],
              "judgedCount":5,"turnPass":3,"itemCount":3,"itemPass":1,
              "failed":["L4-002","L4-003"]
            }
            """;

    private static EvalRecordRow row(List<EvalRecordRow> rows, String taskId) {
        return rows.stream().filter(r -> r.taskId().equals(taskId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("题级：itemPass 决定 SUCCESS/FAILED；轮数、逐轮通过数与轮率进 scores；judge_kind=rule")
    void readsItemPassAndKeepsTurnDetail(@TempDir Path dir) throws IOException {
        List<EvalRecordRow> rows = ArtifactIngest.readL4(artifact(dir, BASE), golden(dir), "run-x", "abc123");
        assertEquals(3, rows.size(), "**题级**一条（不是轮级）——分母口径必须与其他五层一致");

        EvalRecordRow pass = row(rows, "L4-001");
        assertEquals("SUCCESS", pass.outcomeStatus());
        assertEquals("L4", pass.layer());
        assertEquals("rule", pass.judgeKind());
        assertNull(pass.turnIndex(), "题级行的 turn_index 必须为 NULL");
        assertEquals(2, pass.scores().get("turnsPassed"));
        assertEquals(2, pass.scores().get("turnsTotal"));
        assertEquals(1.0d, pass.scores().get("turnRate"));
        assertEquals(0, pass.scores().get("argFailCount"));
        assertEquals("判据一", pass.referenceOutput(), "rubric 落 reference_output（产物里最接近\"期望\"的表述）");
        assertEquals("轮1 query_tariff/OK; 轮2 query_tariff/OK", pass.routeReason());
        assertNull(pass.judgeReasoning(), "通过题没有逐键差异");
        assertNull(pass.errorCategory());
        assertNull(pass.rootCauseLayer());
        assertEquals(LocalDate.of(2027, 9, 15), pass.taskAsOf(), "businessToday 落 task_as_of");

        EvalRecordRow fail = row(rows, "L4-002");
        assertEquals("FAILED", fail.outcomeStatus());
        assertEquals(1, fail.scores().get("turnsPassed"));
        assertEquals(2, fail.scores().get("turnsTotal"));
        assertEquals(0.5d, fail.scores().get("turnRate"));
        assertEquals(1, fail.scores().get("argFailCount"));
        assertEquals("参数继承错误", fail.errorCategory());
        assertEquals("MODEL", fail.rootCauseLayer());
        assertTrue(fail.judgeReasoning().contains("date_from"), "逐键差异必须留证：" + fail.judgeReasoning());
        assertTrue(fail.judgeReasoning().contains("轮2未通过"));

        assertEquals("holdout", row(rows, "L4-003").split(), "保留集纪律的落库形态");
    }

    @Test
    @DisplayName("失败归因：工具状态报 NEEDS_CLARIFICATION 时判「该答却追问」，即使 argFails 里同时列着缺参数")
    void classifiesClarificationBeforeArgFail(@TempDir Path dir) throws IOException {
        List<EvalRecordRow> rows = ArtifactIngest.readL4(artifact(dir, BASE), golden(dir), "run-x", null);
        EvalRecordRow r = row(rows, "L4-003");
        assertEquals("该答却追问", r.errorCategory(),
                "参数缺失是**结果是**、追问是**现象**——口径与 errorCategory(String) 同源");
        assertEquals("MODEL", r.rootCauseLayer());
    }

    @Test
    @DisplayName("失败归因：工具错的两处证据都要认——产物没带 actualTool/expectTool 时仍能从 argFails 认出")
    void classifiesToolMismatchFromArgFails(@TempDir Path dir) throws IOException {
        // 这份产物**刻意不带** actualTool / expectTool（模拟产物形态变化）：
        // 只认 `actualTool != expectTool` 的实现会在这里把它静默判成「参数继承错误」。
        String body = """
                {"layer":"L4","dataset":"golden-set-v3.json","businessToday":"2027-09-15",
                 "results":[
                   {"taskId":"L4-003","split":"holdout","question":"问三","rubric":"判据三",
                    "turns":[{"turn":1,"question":"问三","extractionStatus":"OK","turnPass":false,
                              "argFails":["工具错：期望 query_tariff，实际 quote_fee"]}],
                    "itemPass":false,"turnsPassed":0,"turnsTotal":1,"summarizerCalls":0}],
                 "judgedCount":1,"turnPass":0,"itemCount":1,"itemPass":0,"failed":["L4-003"]}
                """;
        List<EvalRecordRow> rows = ArtifactIngest.readL4(artifact(dir, body), golden(dir), "run-x", null);
        EvalRecordRow r = row(rows, "L4-003");
        assertEquals("工具选择错误", r.errorCategory());
        assertEquals("ROUTER", r.rootCauseLayer());
    }

    @Test
    @DisplayName("拒绝：层名不是 L4")
    void rejectsNonL4Layer(@TempDir Path dir) throws IOException {
        Path a = artifact(dir, BASE.replace("\"layer\":\"L4\"", "\"layer\":\"L5\""));
        Path g = golden(dir);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("L5"));
    }

    @Test
    @DisplayName("拒绝：results 为空（分母为 0 比分子错更难发现）")
    void rejectsEmptyResults(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, "{\"layer\":\"L4\",\"dataset\":\"golden-set-v3.json\",\"results\":[]}");
        assertThrows(IllegalStateException.class, () -> ArtifactIngest.readL4(a, g, "run-x", null));
    }

    @Test
    @DisplayName("拒绝：itemPass 声明值与逐条累加不一致")
    void rejectsItemPassMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"itemPass\":1,", "\"itemPass\":2,"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("itemPass"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：judgedCount（声明轮数）与逐条 turnsTotal 之和不一致")
    void rejectsTurnCountMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"judgedCount\":5", "\"judgedCount\":50"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("judgedCount"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：failed 清单与逐条 itemPass=false 的题号不一致（两处会给出两个答案）")
    void rejectsFailedListMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"failed\":[\"L4-002\",\"L4-003\"]", "\"failed\":[\"L4-002\"]"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("failed"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：题号在数据集里找不到")
    void rejectsTaskIdMissingFromDataset(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        // ⚠️ failed 清单**一起改**，否则会先撞上"failed 与 itemPass 不一致"那条检查，
        //    测到的就不是数据集反证了（检查撞车会让测试名与实测行为不符）
        String body = BASE.replace("\"taskId\":\"L4-003\"", "\"taskId\":\"L4-999\"")
                .replace("\"failed\":[\"L4-002\",\"L4-003\"]", "\"failed\":[\"L4-002\",\"L4-999\"]");
        Path a = artifact(dir, body);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("L4-999"), ex.getMessage());
        assertTrue(ex.getMessage().contains("找不到"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：题面与数据集不一致（题号对得上但题面变了 = 不是同一版数据集）")
    void rejectsQuestionMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"question\":\"问二\"", "\"question\":\"问二（改过）\""));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("题面"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：split 与数据集不一致（保留集纪律的落库形态必须可信）")
    void rejectsSplitMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"split\":\"holdout\"", "\"split\":\"dev\""));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("split"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：产物声明的 dataset 与传入的数据集文件名不同（拿别版数据集来反证也会\"通过\"）")
    void rejectsDatasetNameMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"dataset\":\"golden-set-v3.json\"",
                "\"dataset\":\"golden-set-v2.json\""));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("golden-set-v2.json"), ex.getMessage());
    }

    @Test
    @DisplayName("拒绝：题级 turnsTotal 与 turns 实际条数不一致")
    void rejectsTurnsTotalMismatch(@TempDir Path dir) throws IOException {
        Path g = golden(dir);
        Path a = artifact(dir, BASE.replace("\"itemPass\":false,\"turnsPassed\":0,\"turnsTotal\":1",
                "\"itemPass\":false,\"turnsPassed\":0,\"turnsTotal\":9"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactIngest.readL4(a, g, "run-x", null));
        assertTrue(ex.getMessage().contains("turnsTotal"), ex.getMessage());
    }
}
