package com.canalmind.eval.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把**已有**的评测产物读成待落库记录（第 4 周第 4 项）。
 *
 * <h2>为什么 L1 / L2 先走"读产物"而不是重跑</h2>
 * 第 3 周已经产出并入库了 L1 与 L2 的基线产物，且它们的**数据集哈希经核对与当前数据集同版**：
 * <ul>
 *   <li>`execution-accuracy-l1.json` 的 `dataset.sha256` = 当前 `golden-set-v3.json` 的 sha256；</li>
 *   <li>`retrieval-baseline-l2.json` 的 `datasetSha256` 与 `hitsFileSha256` 分别对应当前
 *       `golden-l2-v2.json` 与 `target/eval/vector-l2-v2-hits.json`。</li>
 * </ul>
 * 哈希一致才允许沿用。⛔ **不核哈希就用旧产物，等于把"上一版数据集上的分数"当当前基线**——
 * 这正是本项目已记录过的「中间产物值被当成最终值沿用」一类错误。
 * 沿用它们的直接好处：L1 的 40 次抽取调用与 L2 的 40 次嵌入**全部免掉**（0 次付费调用）。
 *
 * <h2>⛔ 这两层为什么不需要 LLM Judge</h2>
 * 见 {@code JudgePrompt} 的判分矩阵：L1 用**数据库 oracle**（数值逐格比）、
 * L2 用**纯规则指标**（`HitRate@k` / `MRR@k`）。客观正确性交给证据，不交给主观评分
 * （知识库第 196/198 行）。
 */
public final class ArtifactIngest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArtifactIngest() {
    }

    /** L1 层：读 `execution-accuracy-l1.json`，每题一条记录。 */
    public static List<EvalRecordRow> readL1(Path artifact, String runId, String gitCommit)
            throws IOException {
        JsonNode root = read(artifact);
        String datasetVersion = shortVersion(root.path("dataset").path("sha256").asText(""));
        String graderVersion = "ea-criteria/C-35";
        LocalDate asOf = parseDateOrNull(root.path("businessToday").asText(""));
        String contractVersion = root.path("contractVersion").asText("");
        String evidence = artifact.toString();

        List<EvalRecordRow> rows = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            boolean ok = r.path("ok").asBoolean(false);
            String status = r.path("status").asText("");
            Map<String, Object> scores = new LinkedHashMap<>();
            scores.put("executionAccuracy", ok ? 1 : 0);
            scores.put("status", status);
            rows.add(new EvalRecordRow(
                    runId, r.path("id").asText(), 1, null,
                    "L1", r.path("split").asText("dev"), "oracle",
                    null, null, datasetVersion, asOf,
                    "tool-contracts=" + contractVersion, evidence,
                    sha256Hex(r.path("question").asText()),
                    r.path("question").asText(),
                    r.path("expectedBrief").asText(""),
                    "status=" + status + "；tool=" + r.path("tool").asText("-")
                            + "；" + r.path("reason").asText(""),
                    null, null, null,
                    ok ? "SUCCESS" : "FAILED", scores,
                    null, graderVersion, null,
                    ok ? null : errorCategory(status), ok ? null : rootCause(status),
                    null, gitCommit));
        }
        return rows;
    }

    /**
     * L2 层：读命中文件 + 数据集，按题算 `HitRate@k` / `MRR@k` 后每题一条记录。
     *
     * <p>⚠️ 题目匹配**按题面文本**而不是按数组下标——两份文件的顺序**没有契约保证**，
     * 按下标对齐会在一方重排后静默错配（错配后的分数看起来完全正常）。
     *
     * @param primaryK 作为 `outcomeStatus` 主判据的 k（其余 k 全部落进 `scores`，
     *                 故换 k 重算不需要重跑，只需重算 SQL）
     */
    public static List<EvalRecordRow> readL2(Path hitsFile, Path goldenFile, String runId,
                                            String gitCommit, int primaryK) throws IOException {
        JsonNode hitsRoot = read(hitsFile);
        JsonNode goldenRoot = read(goldenFile);
        String datasetVersion = shortVersion(sha256HexFile(goldenFile));
        int topK = hitsRoot.path("topK").asInt(0);
        String graderVersion = "retrieval-metrics/rule@" + primaryK;

        Map<String, JsonNode> byQuestion = new LinkedHashMap<>();
        for (JsonNode it : goldenRoot.path("items")) {
            byQuestion.put(it.path("question").asText(), it);
        }

        List<EvalRecordRow> rows = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (JsonNode res : hitsRoot.path("results")) {
            String question = res.path("query").asText();
            JsonNode item = byQuestion.get(question);
            if (item == null) {
                unmatched.add(question);
                continue;
            }
            List<String> ranked = new ArrayList<>();
            for (JsonNode h : res.path("hits")) {
                ranked.add(h.path("chunkId").asText());
            }
            List<String> gold = new ArrayList<>();
            for (JsonNode g : item.path("goldChunkIds")) {
                gold.add(g.asText());
            }
            int rank = rankOfFirstGold(ranked, gold);
            Map<String, Object> scores = new LinkedHashMap<>();
            for (int k : new int[] {5, 10, 20, 30}) {
                scores.put("HitRate@" + k, rank > 0 && rank <= k ? 1 : 0);
                scores.put("MRR@" + k, rank > 0 && rank <= k ? round6(1.0 / rank) : 0.0);
            }
            scores.put("rankOfFirstGold", rank);
            scores.put("goldCount", gold.size());
            scores.put("topK", topK);
            boolean hit = rank > 0 && rank <= primaryK;

            rows.add(new EvalRecordRow(
                    runId, item.path("id").asText(), 1, null,
                    "L2", item.path("split").asText("dev"), "rule",
                    null, null, datasetVersion, null,
                    "embedding=" + hitsRoot.path("profileName").asText(""), hitsFile.toString(),
                    sha256Hex(question), question,
                    String.join(",", gold),
                    "rankOfFirstGold=" + (rank > 0 ? String.valueOf(rank) : "未命中"),
                    null, null, null,
                    hit ? "SUCCESS" : "FAILED", scores,
                    null, graderVersion, null,
                    hit ? null : "检索未命中", hit ? null : "DATA",
                    null, gitCommit));
        }
        if (!unmatched.isEmpty()) {
            // ⛔ 不静默跳过：错配会让分母变小，而分母错了比分子错了更难发现
            throw new IllegalStateException("有 " + unmatched.size()
                    + " 条问题在数据集里找不到对应题目（两份文件不同版？）：" + unmatched);
        }
        return rows;
    }

    /** 首个命中 gold 的名次（1 起）；未命中返回 0。 */
    /** C-52：Judge 类三层（L3 / L5 / L6）允许的层名，⛔ 不接受别的层。 */
    private static final java.util.Set<String> JUDGE_LAYERS = java.util.Set.of("L3", "L5", "L6");

    /**
     * Judge 类三层：读 `layer-L{3,5,6}-judged-v1.json`，每题一条记录（C-52）。
     *
     * <p>补做理由：第 4 周任务 4 是「**完整**评测 Harness，`EvalRecord` 表落库」，
     * 而在此之前 `eval_record` 只有 L1/L2（`readL1`/`readL2` 两条路径），
     * **Judge 三层根本没有入库通道** ⇒ 「分层通过率」报告在 SQL 侧取不到 L3–L6。
     * 本方法**只读既有产物**，⛔ 不重新调用任何付费 API。
     *
     * <p>⚠️ **三层条目 schema 不完全一致**（实测，且本轮曾把这条记窄过——已更正）：
     * 规则三键 `ruleExpectedStatus` / `ruleActualStatus` / `rulePass` **并非 L6 独有**——
     * 落库后按层统计实际是 **L6 15/15 条都有、L5 也有（17/20 条）、L3 一条都没有**。
     * 故这里**按"条目有没有这个键"逐个取**，⛔ **不按层名分支**：
     * ①按层分支会漏掉 L5 的 17 条；②照 L3 的键集一刀切会把规则结果**静默丢掉**，
     * 而丢字段不报错、条数也仍然对——正是"合法但错"。这条口径由
     * `ArtifactIngestJudgeTest` 与落库后的 `scores->>'rulePass'` 非空计数共同守护。
     *
     * <p>⚠️ **刻意留空并在文档里登记的两个字段**：
     * <ul>
     *   <li>`promptVersion` = {@code null}：judged 产物里**没有** Prompt 版本字段。
     *       其 `answersSource`（如 `layer-L3-answers-v1.json`）是**答案产物的版本**，
     *       ⛔ **不等于 Prompt 版本**——把两者等同就是"合法但错"。
     *       C-51 要的 v1 锚点必须另找（生成 Prompt 源码的 git 历史），**不得**用文件名硬凑。</li>
     *   <li>`datasetVersion`：judged 产物**不含**数据集版本，而 DDL 里该列 **NOT NULL**。
     *       解法是**反证**而不是随便填：逐条要求题号能在 `golden-set-v3.json` 里找到，
     *       对不上就报错 ⇒ 数据集版本 = 该数据集文件的哈希（与 L2 同一口径）。</li>
     *   <li>`referenceOutput` / `actualOutput`：judged 产物**不含参考答案与答案正文**
     *       （答案在同目录的 `layer-L*-answers-v1.json`，本轮⛔ 未接入），故留空并登记。</li>
     * </ul>
     *
     * @param judged 形如 {@code layer-L3-judged-v1.json}
     */
    public static List<EvalRecordRow> readJudge(Path judged, Path goldenFile, String runId,
                                                String gitCommit) throws IOException {
        JsonNode root = read(judged);
        String layer = root.path("layer").asText("");
        if (!JUDGE_LAYERS.contains(layer)) {
            throw new IllegalArgumentException(
                    "readJudge 只处理 " + JUDGE_LAYERS + "，实际为「" + layer + "」：" + judged);
        }
        JsonNode items = root.path("judged");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("judged 数组为空或缺失，拒绝落库（分母为 0 比分子错更难发现）：" + judged);
        }
        int declared = root.path("judgedCount").asInt(-1);
        if (declared >= 0 && declared != items.size()) {
            // ⛔ 不静默跳过：与 readL2「声明条数 vs 实际条数」同一条纪律
            throw new IllegalStateException("judgedCount=" + declared + " 与 judged 实际条数="
                    + items.size() + " 不一致：" + judged);
        }

        // `dataset_version` 在 DDL 里是 NOT NULL，而 judged 产物**不含**数据集版本。
        // ⚠️ 解法不是"随便填一个哈希"，而是：数据集文件由 L3/L5/L6 的题号**反证**出来——
        // 逐条要求 taskId 能在数据集里找到，对不上就报错（数据集拿错了要当场炸，不是静默错配）。
        JsonNode goldenRoot = read(goldenFile);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode it : goldenRoot.path("items")) {
            byId.put(it.path("id").asText(), it);
        }
        String datasetVersion = shortVersion(sha256HexFile(goldenFile));
        String judgeModel = root.path("judgeModel").asText(null);
        String answersSource = root.path("answersSource").asText("");
        String evidence = judged.getFileName() + "@" + shortVersion(sha256HexFile(judged))
                + (answersSource.isEmpty() ? "" : " ← " + answersSource);
        String graderVersion = "judge-reference-guided@" + root.path("strictMinEach").asInt()
                + "," + root.path("lenientMinMean").asDouble();
        String rootCauseOnFail = "MODEL";

        List<EvalRecordRow> rows = new ArrayList<>();
        for (JsonNode it : items) {
            String taskId = it.path("taskId").asText();
            if (taskId.isBlank()) {
                throw new IllegalStateException("judged 条目缺 taskId：" + judged);
            }
            JsonNode goldenItem = byId.get(taskId);
            if (goldenItem == null) {
                throw new IllegalStateException("judged 里的题号「" + taskId
                        + "」在数据集 " + goldenFile.getFileName()
                        + " 中找不到（数据集不同版？）——⛔ 不静默落库");
            }
            String question = goldenItem.path("question").asText(null);
            boolean passStrict = it.path("passStrict").asBoolean(false);
            boolean passLenient = it.path("passLenient").asBoolean(false);
            boolean needsReview = it.path("needsHumanReview").asBoolean(false);

            Map<String, Object> scores = new LinkedHashMap<>();
            for (String f : new String[] {"faithfulness", "relevance", "completeness"}) {
                if (it.has(f)) {
                    scores.put(f, it.path(f).asInt());
                }
            }
            scores.put("mean", it.path("mean").asDouble());
            scores.put("minScore", it.path("minScore").asInt());
            scores.put("parseOk", it.path("parseOk").asBoolean(false));
            scores.put("passStrict", passStrict ? 1 : 0);
            scores.put("passLenient", passLenient ? 1 : 0);
            // ⚠️ L6 独有：状态规则校验结果（⛔ 不能因为 L3/L5 没有就跳过）
            if (it.has("rulePass")) {
                scores.put("rulePass", it.path("rulePass").asBoolean(false) ? 1 : 0);
                scores.put("ruleExpectedStatus", it.path("ruleExpectedStatus").asText(""));
                scores.put("ruleActualStatus", it.path("ruleActualStatus").asText(""));
            }

            String status = passStrict ? "SUCCESS" : (passLenient ? "PARTIAL" : "FAILED");
            String errorCategory = switch (status) {
                case "FAILED" -> "Judge 严格与宽松阈值均未达";
                case "PARTIAL" -> "Judge 低于严格阈值但达宽松阈值";
                default -> null;
            };

            rows.add(new EvalRecordRow(
                    runId, taskId, 1, null,
                    layer, it.path("split").asText("dev"), "reference_guided",
                    null, judgeModel, datasetVersion, null,
                    null, evidence, sha256Hex(question != null ? question : taskId),
                    question, null, null,
                    null, null, null,
                    status, scores,
                    judgeModel, graderVersion, it.path("reasoning").asText(null),
                    errorCategory, "FAILED".equals(status) ? rootCauseOnFail : null,
                    needsReview ? 0.0 : null, gitCommit));
        }
        return rows;
    }

    // ------------------------------------------------------------------ L4（规则判分、零付费）
    /** L4 只有这一层名；⛔ 不接受别的层（与 `JUDGE_LAYERS` 同一条纪律）。 */
    private static final String L4_LAYER = "L4";

    /**
     * L4 层：读 {@code layer-L4-inheritance-v1.json}（多轮参数继承，**规则判分**），**每题一条记录**。
     *
     * <p><b>为什么补这条通道</b>：第 4 周任务 4 是「完整评测 Harness + `EvalRecord` 落库」，
     * 而 L4 的分数一直只存在于 docs 侧产物里 —— 于是《评测报告》§2 与看板 §0.3 都写着
     * 「**六层全部在库**」，**而这在 `eval_record` 里从来不是真的**：
     * run `w4-2026-09-22-all6-artifacts-v2` 实测只有 **5 层 135 条**（L1 40 / L2 40 / L3 20 / L5 20 / L6 15），
     * **L4 一条都没有**。本方法把这句话变成可被 SQL 验证的事实。
     *
     * <p><b>⚠️ 为什么是"每题一条"而不是"每轮一条"</b>：DDL 的主口径是
     * 「`SUCCESS` / 该层总题数」，L4 的主口径也是**整题 15/25**（逐轮 36/50 是次要口径）。
     * 若按轮落库，同一句 SQL 会把 L4 算成 36/50，**与其他五层的分母口径不再可比**。
     * ⇒ 轮级明细进 `scores`（`turnsPassed` / `turnsTotal` / `turnRate`）与 `judge_reasoning`，
     * `turn_index` 留 NULL。⛔ 不写两遍（题级 + 轮级）——那会把通过率算重。
     *
     * <p><b>⚠️ 刻意留空并在此登记的四个字段</b>（产物里真的没有，⛔ 不编造）：
     * <ul>
     *   <li>{@code promptVersion} / {@code modelId}：产物**不记录** Prompt 版本与模型 id
     *       （顶层键实测只有 layer/dataset/businessToday/…/bySplit）。</li>
     *   <li>{@code sessionId}：产物不记会话 id。题级行本不需要它，但**C-38 想要的
     *       "按会话+轮次定位"在 L4 这一层仍无法从产物还原** ⇒ 登记为缺口，⛔ 不用题号硬凑。</li>
     *   <li>{@code actualOutput}：产物不含答案正文（只有工具与实参）。</li>
     * </ul>
     *
     * <p><b>⚠️ `graderVersion` 的取法</b>：产物**没有**判分器版本号字段，
     * 而 L4 的判分器就是"工具 + 实参逐键比对"这段代码。故这里用**产物的 sha256 短哈希**锚定
     * （`rule-tool-args@&lt;short&gt;`）——它至少能回答"这条记录出自哪份产物"，
     * 比填一个凭记忆的版本号诚实。⚠️ **该缺口已登记**：将来若重跑 L4，应在产物里加 `graderVersion`。
     *
     * <p><b>本方法只读既有产物</b>，⛔ 不重新调用任何付费 API（L4 判分本身零付费，
     * 但**重跑作答**要 53 次模型调用）。
     *
     * @param artifact   {@code layer-L4-inheritance-v1.json}
     * @param goldenFile L4 题号所在的数据集（用于**反证**题号 + 作为 `dataset_version`）
     */
    public static List<EvalRecordRow> readL4(Path artifact, Path goldenFile, String runId,
                                             String gitCommit) throws IOException {
        JsonNode root = read(artifact);
        String layer = root.path("layer").asText("");
        if (!L4_LAYER.equals(layer)) {
            throw new IllegalArgumentException(
                    "readL4 只处理 " + L4_LAYER + "，实际为「" + layer + "」：" + artifact);
        }
        JsonNode items = root.path("results");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("results 数组为空或缺失，拒绝落库（分母为 0 比分子错更难发现）：" + artifact);
        }

        // ⚠️ 数据集必须**按产物自己声明的那一个**给进来：产物自带 `dataset` 字段，
        //    若拿另一版数据集来"反证"，题号照样能找到、于是静默错配 ⇒ 这里直接比文件名。
        String declaredDataset = root.path("dataset").asText("");
        if (!declaredDataset.equals(goldenFile.getFileName().toString())) {
            throw new IllegalStateException("产物声明的 dataset=「" + declaredDataset + "」，与传入的数据集「"
                    + goldenFile.getFileName() + "」不一致（不同版数据集？）——⛔ 不静默落库");
        }

        // ---- 声明值 vs 逐条累加：四组都必须对上 ----
        int declaredItemCount = root.path("itemCount").asInt(-1);
        int declaredItemPass = root.path("itemPass").asInt(-1);
        int declaredTurns = root.path("judgedCount").asInt(-1);
        int declaredTurnPass = root.path("turnPass").asInt(-1);
        int realItemPass = 0;
        int realTurns = 0;
        int realTurnPass = 0;
        for (JsonNode it : items) {
            if (it.path("itemPass").asBoolean(false)) {
                realItemPass++;
            }
            // ⚠️ **题级**一致性检查放在这里（而不是循环之后）：否则注入一条
            //    "turnsTotal 与 turns 条数不符"的坏产物时，会先撞上累计检查、
            //    报出 `judgedCount` 这个**不相干的字段名**，排错方向被带偏。
            int turnsTotal = it.path("turnsTotal").asInt(0);
            int turnsReal = it.path("turns").size();
            if (turnsTotal != turnsReal) {
                throw new IllegalStateException("L4 题「" + it.path("taskId").asText() + "」turnsTotal="
                        + turnsTotal + " 与 turns 实际条数=" + turnsReal + " 不一致——⛔ 不静默落库");
            }
            realTurns += turnsTotal;
            realTurnPass += it.path("turnsPassed").asInt(0);
        }
        requireEqual("itemCount", declaredItemCount, items.size(), artifact);
        requireEqual("itemPass", declaredItemPass, realItemPass, artifact);
        requireEqual("judgedCount", declaredTurns, realTurns, artifact);
        requireEqual("turnPass", declaredTurnPass, realTurnPass, artifact);

        // `failed` 是产物自带的失败题号清单。它与逐条 `itemPass` 必须一致——
        // 两处不一致时"哪些题没过"会有两个答案，而下游 badcase 聚类只读其中一个。
        java.util.Set<String> declaredFailed = new java.util.LinkedHashSet<>();
        for (JsonNode f : root.path("failed")) {
            declaredFailed.add(f.asText());
        }
        java.util.Set<String> realFailed = new java.util.LinkedHashSet<>();
        for (JsonNode it : items) {
            if (!it.path("itemPass").asBoolean(false)) {
                realFailed.add(it.path("taskId").asText());
            }
        }
        if (!declaredFailed.equals(realFailed)) {
            throw new IllegalStateException("产物 failed=" + declaredFailed
                    + " 与逐条 itemPass=false 的题号=" + realFailed + " 不一致：" + artifact);
        }

        JsonNode goldenRoot = read(goldenFile);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode it : goldenRoot.path("items")) {
            byId.put(it.path("id").asText(), it);
        }
        String datasetVersion = shortVersion(sha256HexFile(goldenFile));
        LocalDate taskAsOf = parseDateOrNull(root.path("businessToday").asText(null));
        String graderVersion = "rule-tool-args@" + shortVersion(sha256HexFile(artifact));
        String evidence = artifact.getFileName() + "@" + shortVersion(sha256HexFile(artifact))
                + " ← L4RunCli（工具+实参逐键比对，规则判分、零付费）";

        List<EvalRecordRow> rows = new ArrayList<>();
        for (JsonNode it : items) {
            String taskId = it.path("taskId").asText();
            if (taskId.isBlank()) {
                throw new IllegalStateException("results 条目缺 taskId：" + artifact);
            }
            JsonNode goldenItem = byId.get(taskId);
            if (goldenItem == null) {
                throw new IllegalStateException("L4 题号「" + taskId + "」在数据集 "
                        + goldenFile.getFileName() + " 中找不到（数据集不同版？）——⛔ 不静默落库");
            }
            String question = goldenItem.path("question").asText(null);
            // 题面逐字比对：题号对得上但题面变了，说明产物与数据集不是同一版
            if (question == null || !question.equals(it.path("question").asText(""))) {
                throw new IllegalStateException("L4 题「" + taskId + "」的题面与数据集不一致"
                        + "（产物 ← 旧版数据集？）——⛔ 不静默落库");
            }
            String split = it.path("split").asText("dev");
            String goldenSplit = goldenItem.path("split").asText("dev");
            if (!split.equals(goldenSplit)) {
                throw new IllegalStateException("L4 题「" + taskId + "」的 split=「" + split
                        + "」与数据集「" + goldenSplit + "」不一致——保留集纪律的落库形态必须可信");
            }
            JsonNode turns = it.path("turns");
            int turnsTotal = it.path("turnsTotal").asInt(0);

            List<JsonNode> failing = new ArrayList<>();
            StringBuilder route = new StringBuilder();
            for (JsonNode t : turns) {
                if (route.length() > 0) {
                    route.append("; ");
                }
                route.append("轮").append(t.path("turn").asInt()).append(' ')
                        .append(t.path("actualTool").asText("?"))
                        .append('/').append(t.path("extractionStatus").asText("?"));
                if (!t.path("turnPass").asBoolean(false)) {
                    failing.add(t);
                }
            }
            int turnsPassed = it.path("turnsPassed").asInt(0);
            boolean itemPass = it.path("itemPass").asBoolean(false);
            int argFailCount = 0;
            for (JsonNode t : failing) {
                argFailCount += t.path("argFails").size();
            }

            Map<String, Object> scores = new LinkedHashMap<>();
            scores.put("itemPass", itemPass ? 1 : 0);
            scores.put("turnsPassed", turnsPassed);
            scores.put("turnsTotal", turnsTotal);
            scores.put("turnRate", turnsTotal == 0 ? 0d : round6((double) turnsPassed / turnsTotal));
            scores.put("argFailCount", argFailCount);
            scores.put("summarizerCalls", it.path("summarizerCalls").asInt(0));

            String status = itemPass ? "SUCCESS" : "FAILED";
            L4Fail fail = failing.isEmpty() ? null : classifyL4Fail(failing);

            rows.add(new EvalRecordRow(
                    runId, taskId, 1, null,
                    L4_LAYER, split, "rule",
                    null, null, datasetVersion,
                    taskAsOf, null,
                    evidence, sha256Hex(question),
                    question, it.path("rubric").asText(null), null,
                    null, route.toString(), null,
                    status, scores,
                    null, graderVersion, describeL4Fail(failing),
                    fail == null ? null : fail.category(),
                    fail == null ? null : fail.rootCause(),
                    null, gitCommit));
        }
        return rows;
    }

    /** 声明值与实际值必须相等；⛔ 不一致就炸，不静默跳过（同 `readJudge` 的 judgedCount 纪律）。 */
    private static void requireEqual(String field, int declared, int real, Path artifact) {
        if (declared != real) {
            throw new IllegalStateException("产物的 " + field + "=" + declared
                    + " 与逐条累加=" + real + " 不一致：" + artifact);
        }
    }

    /** L4 失败现象 + 归因层（**规则判分没有 Judge**，故按工具自己的状态派生）。 */
    private record L4Fail(String category, String rootCause) {
    }

    /**
     * 失败归因：**顺序即优先级**，且只用于打**一个**标签。
     *
     * <p>优先级依据与 `errorCategory(String)` 同源——**以工具的实际状态为主**，
     * 而不是以"我觉得哪里错了"为主：`extractionStatus` / `toolStatus` 报
     * `NEEDS_CLARIFICATION` 就是「该答却追问」，即使它的 `argFails` 里同时列着缺失参数
     * （实测 `L4-007` / `L4-019` / `L4-023` 都是这个形态：参数缺失是**结果是**，追问是**现象**）。
     *
     * <p>⚠️ 工具选错**有两处证据**：`actualTool != expectTool`，以及 `argFails` 里以
     * 「工具错」开头的那条（实测 `L4-015` 两者同时出现）。**两处都认**，
     * 否则只认前者的实现会在产物形态变化时静默漏判。
     *
     * <p>⛔ 打标签**不会丢信息**：全部逐键差异都写进 `judgeReasoning`，
     * 这个 `category` 只服务于 badcase 聚类。
     */
    private static L4Fail classifyL4Fail(List<JsonNode> failingTurns) {
        boolean clarification = false;
        boolean degraded = false;
        boolean toolWrong = false;
        boolean argWrong = false;
        for (JsonNode t : failingTurns) {
            if (t.path("needsClarification").asBoolean(false)
                    || "NEEDS_CLARIFICATION".equals(t.path("extractionStatus").asText())
                    || "NEEDS_CLARIFICATION".equals(t.path("toolStatus").asText())) {
                clarification = true;
            }
            if (t.path("degraded").asBoolean(false)) {
                degraded = true;
            }
            if (!t.path("actualTool").asText().equals(t.path("expectTool").asText())) {
                toolWrong = true;
            }
            for (JsonNode f : t.path("argFails")) {
                if (f.asText().startsWith("工具错")) {
                    toolWrong = true;
                } else {
                    argWrong = true;
                }
            }
        }
        if (clarification) {
            return new L4Fail("该答却追问", "MODEL");
        }
        if (degraded) {
            return new L4Fail("通道降级", "AGENT");
        }
        if (toolWrong) {
            return new L4Fail("工具选择错误", "ROUTER");
        }
        if (argWrong) {
            return new L4Fail("参数继承错误", "MODEL");
        }
        return new L4Fail("答案不符", "MODEL");
    }

    /** 失败逐键差异（**全部**失败轮，不只第一条）——这是 L4 唯一能拿到的判分理由。 */
    private static String describeL4Fail(List<JsonNode> failingTurns) {
        if (failingTurns.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : failingTurns) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("轮").append(t.path("turn").asInt()).append("未通过：");
            JsonNode af = t.path("argFails");
            if (af.isArray() && !af.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (JsonNode f : af) {
                    parts.add(f.asText());
                }
                sb.append(String.join("；", parts));
            } else {
                sb.append("argFails 为空（工具与实参照契约都对上但仍判失败，属 D 类判据缺口）");
            }
        }
        return sb.toString();
    }

    static int rankOfFirstGold(List<String> ranked, List<String> gold) {
        for (int i = 0; i < ranked.size(); i++) {
            if (gold.contains(ranked.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 失败现象分类：用工具实际状态而不是笼统的"答错"，便于 badcase 聚类。 */
    static String errorCategory(String status) {
        return switch (status == null ? "" : status) {
            case "NEEDS_CLARIFICATION" -> "该答却追问";
            case "REJECTED" -> "该答却拒绝";
            case "DEGRADED" -> "通道降级";
            case "NO_DATA" -> "无数据";
            default -> "答案不符";
        };
    }

    /**
     * 失败归因层。取值集合见 DDL 的 CHECK（DATA/MODEL/AGENT/TOOL/ENV/GRADER/ROUTER）。
     *
     * <p>⚠️ 这里刻意**不写 ROUTER**：L1 题面已含全部参数、只走结构化通道，
     * 路由不参与这层的成败。ROUTER 只用于复合/多轮层拆错步的归因（C-37⑤）。
     */
    static String rootCause(String status) {
        return switch (status == null ? "" : status) {
            case "NO_DATA" -> "DATA";
            case "NEEDS_CLARIFICATION" -> "MODEL";
            default -> "TOOL";
        };
    }

    private static JsonNode read(Path p) throws IOException {
        if (!Files.exists(p)) {
            throw new IOException("产物文件不存在：" + p.toAbsolutePath());
        }
        return MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8));
    }

    static LocalDate parseDateOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 数据集版本列只有 32 字符，故用"短哈希"而不是整条 sha256 —— 但保留前 8 位足以区分版本。 */
    static String shortVersion(String sha256) {
        return sha256 == null || sha256.isBlank() ? "unknown" : sha256.substring(0, 8);
    }

    static double round6(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    static String sha256Hex(String text) {
        return hex(sha256Bytes(text.getBytes(StandardCharsets.UTF_8)));
    }

    static String sha256HexFile(Path p) throws IOException {
        return hex(sha256Bytes(Files.readAllBytes(p)));
    }

    private static byte[] sha256Bytes(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不提供 SHA-256", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
