package com.canalmind.eval.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 评测 Harness 命令行（第 4 周第 4 项）。
 *
 * <pre>
 *   --ingest --run-id &lt;id&gt;     把 L1 / L2 已有产物读成 eval_record 行
 *       [--l1 &lt;path&gt;]           默认 docs/评测报告/execution-accuracy-l1.json
 *       [--l2-hits &lt;path&gt;]      默认 backend/target/eval/vector-l2-v2-hits.json
 *       [--l2-golden &lt;path&gt;]    默认 docs/评测报告/golden-l2-v2.json
 *       [--primary-k 5]         L2 主判据的 k（其余 k 全部落进 scores，换 k 不必重跑）
 *       [--write]               ⛔ 不加则只空跑，不写库
 *       [--json &lt;path&gt;]        机读产物（LF、键排序、无时间戳）
 *   --report --run-id &lt;id&gt;     按层/分片/判分方式读库汇总（分层通过率报告的数据源）
 *   --selftest                 对真实库做一次"写入→读回→清理"往返，并证明唯一约束真的会拦重复
 * </pre>
 *
 * <h2>{@code --run-id} 为什么必须显式给</h2>
 * `eval_record` 的唯一槽位键是 `(run_id, task_id, trial_id, turn_index)`，
 * 而"同轮记录"是靠 `run_id` 成组的。若代码按时间自动生成 runId，
 * **同一条命令跑两次就会被当成两轮**，`--report` 也再也无法回答
 * "这一批到底是同一轮还是两轮混在一起"。所以这里**不提供默认值**：给不出就报错退出。
 */
public final class HarnessCli {

    private static final String LF = String.valueOf((char) 10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_L1 = "docs/评测报告/execution-accuracy-l1.json";
    private static final String DEFAULT_L2_HITS = "target/eval/vector-l2-v2-hits.json";
    private static final String DEFAULT_L2_GOLDEN = "docs/评测报告/golden-l2-v2.json";

    /**
     * C-52：Judge 类三层的 judged 产物默认路径。
     *
     * <p>三层**必须一起落库**——只落 L3 会让「分层通过率」在 SQL 侧看起来"有 Judge 层了"，
     * 而 L5/L6 仍然缺席，这种"部分补齐"比全缺更难发现。
     */
    private static final String[] DEFAULT_JUDGED = {
            "docs/评测报告/layer-L3-judged-v1.json",
            "docs/评测报告/layer-L5-judged-v1.json",
            "docs/评测报告/layer-L6-judged-v1.json"};

    /** C-52：三层题号所在的数据集——落库时用它**反证**题号，并作为 `dataset_version`。 */
    private static final String DEFAULT_JUDGE_GOLDEN = "docs/评测报告/golden-set-v3.json";

    private HarnessCli() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception ex) {
            System.err.println("异常：" + ex.getMessage());
            ex.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        String runId = null;
        String l1 = null;
        String l2Hits = null;
        String l2Golden = null;
        java.util.List<String> judgeJudged = new java.util.ArrayList<>();
        String judgeGoldenPath = null;
        String jsonOut = null;
        String gitCommit = null;
        int primaryK = 5;
        boolean ingest = false;
        boolean report = false;
        boolean selftest = false;
        boolean write = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ingest" -> ingest = true;
                case "--report" -> report = true;
                case "--selftest" -> selftest = true;
                case "--write" -> write = true;
                case "--run-id" -> runId = args[++i];
                case "--l1" -> l1 = args[++i];
                case "--l2-hits" -> l2Hits = args[++i];
                case "--l2-golden" -> l2Golden = args[++i];
                // C-52：Judge 类三层（L3/L5/L6）的 judged 产物，可重复给（顺序即入库顺序）
                case "--l3-judged", "--l5-judged", "--l6-judged" -> judgeJudged.add(args[++i]);
                case "--judge-golden" -> judgeGoldenPath = args[++i];
                case "--primary-k" -> primaryK = Integer.parseInt(args[++i]);
                case "--json" -> jsonOut = Path.of(args[++i]).toString();
                case "--git-commit" -> gitCommit = args[++i];
                case "--help", "-h" -> {
                    printUsage();
                    return 0;
                }
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    printUsage();
                    return 2;
                }
            }
        }

        JdbcTemplate jdbc = jdbcTemplate();
        EvalRecordWriter writer = new EvalRecordWriter(jdbc);

        if (selftest) {
            return selftest(jdbc, writer);
        }
        if (runId == null || runId.isBlank()) {
            System.err.println("⛔ 必须显式给出 --run-id（理由见类注释：它缺失就无法回答\"这是同一轮还是两轮\"）");
            return 2;
        }
        if (report) {
            return report(writer, runId, jsonOut);
        }
        if (ingest) {
            // C-52：未显式给 Judge 产物时，走三层默认路径（缺哪个就显式跳过哪个）
            java.util.List<String> judgePaths = new java.util.ArrayList<>();
            if (judgeJudged.isEmpty()) {
                for (String d : DEFAULT_JUDGED) {
                    judgePaths.add(resolve(d).toString());
                }
            } else {
                for (String p : judgeJudged) {
                    judgePaths.add(resolve(p).toString());
                }
            }
            Path judgeGolden = resolve(judgeGoldenPath == null ? DEFAULT_JUDGE_GOLDEN : judgeGoldenPath);
            return ingest(writer, runId, gitCommit, primaryK,
                    resolve(l1 == null ? DEFAULT_L1 : l1),
                    resolve(l2Hits == null ? DEFAULT_L2_HITS : l2Hits),
                    resolve(l2Golden == null ? DEFAULT_L2_GOLDEN : l2Golden),
                    judgePaths, judgeGolden, write, jsonOut);
        }
        printUsage();
        return 2;
    }

    // ------------------------------------------------------------------ ingest
    private static int ingest(EvalRecordWriter writer, String runId, String gitCommit, int primaryK,
                              Path l1, Path l2Hits, Path l2Golden, java.util.List<String> judgeJudged,
                              Path judgeGolden, boolean write, String jsonOut)
            throws IOException {
        List<EvalRecordRow> rows = new ArrayList<>();
        if (Files.exists(l1)) {
            rows.addAll(ArtifactIngest.readL1(l1, runId, gitCommit));
        } else {
            System.out.println("⚠️ 跳过 L1：产物不存在 " + l1);
        }
        if (Files.exists(l2Hits) && Files.exists(l2Golden)) {
            rows.addAll(ArtifactIngest.readL2(l2Hits, l2Golden, runId, gitCommit, primaryK));
        } else {
            System.out.println("⚠️ 跳过 L2：命中文件或数据集不存在（" + l2Hits + " / " + l2Golden + "）");
        }
        // C-52：Judge 类三层（L3 / L5 / L6）。⛔ 缺失即显式跳过，不静默少算分母。
        for (String p : judgeJudged) {
            Path jp = Path.of(p);
            if (Files.exists(jp)) {
                List<EvalRecordRow> rs = ArtifactIngest.readJudge(jp, judgeGolden, runId, gitCommit);
                rows.addAll(rs);
                System.out.println("  " + jp.getFileName() + " → " + rs.size() + " 条");
            } else {
                System.out.println("⚠️ 跳过 Judge 层：产物不存在 " + jp);
            }
        }
        if (rows.isEmpty()) {
            System.err.println("[FAIL] 一条记录都没造出来——产物路径不对？⛔ 空产物不得当作成功");
            return 3;
        }

        System.out.println("=== Harness 落库（第 4 周第 4 项，决策 C-41）===");
        System.out.println("run_id = " + runId);
        System.out.println("本次将写入 " + rows.size() + " 条记录（layer/split 分布如下）：");
        System.out.println("  " + distribution(rows));
        System.out.println("判分方式：L1 = oracle（数据库逐格）、L2 = rule（纯规则指标）、"
                + "L3/L5/L6 = reference_guided（LLM Judge，读既有 judged 产物，⛔ 不重新调用付费 API）");

        if (!write) {
            System.out.println();
            System.out.println("⛔ 未加 --write ⇒ 空跑，不写库。前 3 条预览：");
            for (int i = 0; i < Math.min(3, rows.size()); i++) {
                System.out.println("    " + rows.get(i).brief());
            }
        } else {
            int n = writer.insertAll(rows);
            // ⚠️ 证据取自**数据库**，不是取自上面的循环变量
            int inDb = writer.countByRun(runId);
            System.out.println();
            System.out.println("写入返回 " + n + " 条；库内 run_id=" + runId + " 实到 " + inDb + " 条");
            if (inDb != n) {
                System.err.println("[FAIL] 库内条数与写入条数不一致（" + inDb + " vs " + n + "）");
                return 3;
            }
            System.out.println();
            System.out.println("库内汇总（按层/分片/判分方式/结论）：");
            for (Map<String, Object> r : writer.summarizeByLayer(runId)) {
                System.out.println("    " + r);
            }
        }

        if (jsonOut != null) {
            writeJson(Path.of(jsonOut), rows, runId);
            System.out.println();
            System.out.println("机读产物：" + jsonOut);
        }
        System.out.println();
        System.out.println("[PASS] " + (write ? "已写入并核对库内条数" : "空跑完成（未写库）"));
        return 0;
    }

    // ------------------------------------------------------------------ report
    private static int report(EvalRecordWriter writer, String runId, String jsonOut)
            throws IOException {
        List<Map<String, Object>> rows = writer.summarizeByLayer(runId);
        System.out.println("=== 分层通过率（数据取自库内 eval_record，run_id=" + runId + "）===");
        if (rows.isEmpty()) {
            System.out.println("⚠️ 该 run_id 在库里没有任何记录——先跑 --ingest --write");
            return 3;
        }
        System.out.println();
        System.out.printf("%-4s %-8s %-18s %-8s %6s%n", "层", "分片", "判分方式", "结论", "条数");
        int total = 0;
        Map<String, Integer> byLayer = new TreeMap<>();
        Map<String, Integer> okByLayer = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            int n = ((Number) r.get("n")).intValue();
            total += n;
            String layer = String.valueOf(r.get("layer"));
            byLayer.merge(layer, n, Integer::sum);
            if ("SUCCESS".equals(String.valueOf(r.get("outcome_status")))) {
                okByLayer.merge(layer, n, Integer::sum);
            }
            System.out.printf("%-4s %-8s %-18s %-8s %6d%n",
                    layer, r.get("split"), r.get("judge_kind"), r.get("outcome_status"), n);
        }
        System.out.println();
        System.out.println("分层通过率（主口径：SUCCESS / 该层总数）：");
        for (Map.Entry<String, Integer> e : byLayer.entrySet()) {
            int ok = okByLayer.getOrDefault(e.getKey(), 0);
            System.out.printf("  %-4s %3d/%3d = %.4f%n", e.getKey(), ok, e.getValue(),
                    (double) ok / e.getValue());
        }
        System.out.println("  合计 " + byLayer.values().stream().mapToInt(Integer::intValue).sum()
                + " 条");
        if (total == 0) {
            System.err.println("[FAIL] 汇总条数为 0");
            return 3;
        }
        if (jsonOut != null) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("runId", runId);
            ArrayNode arr = root.putArray("buckets");
            for (Map<String, Object> r : rows) {
                ObjectNode n = arr.addObject();
                r.forEach((k, v) -> n.put(k, v == null ? null : String.valueOf(v)));
            }
            ObjectNode rates = root.putObject("passRateByLayer");
            byLayer.forEach((k, v) -> rates.put(k, (double) okByLayer.getOrDefault(k, 0) / v));
            writeJsonNode(Path.of(jsonOut), root);
            System.out.println();
            System.out.println("机读产物：" + jsonOut);
        }
        return 0;
    }

    // ------------------------------------------------------------------ selftest
    /**
     * 对**真实库**做一次往返自检。
     *
     * <p>为什么自检必须写真库而不是用内存桩：本项要验的正是**DDL 约束与 Java 绑定是否对齐**
     * （30 列是否逐位对上、`scores` 的 `?::jsonb` 转换是否成立、唯一槽位键是否真的拦重复）。
     * 用桩件只能证明我自己写的绑定表和自己一致，那正是本项目反复批评的"空断言"。
     * ⛔ 自检用 `selftest-` 前缀的 run_id，并在结束时**清干净**。
     */
    private static int selftest(JdbcTemplate jdbc, EvalRecordWriter writer) {
        String runId = "selftest-evalrecord-writer";
        int failures = 0;
        System.out.println("=== EvalRecord 写入器自检（对真实库，结束即清理）===");

        jdbc.update("DELETE FROM eval_record WHERE run_id = ?", runId);
        int before = writer.countByRun(runId);
        System.out.println("  清理后库内条数 = " + before);

        EvalRecordRow row = new EvalRecordRow(
                runId, "SELFTEST-001", 1, null, "L1", "dev", "oracle",
                null, null, "selftest", java.time.LocalDate.parse("2027-09-15"),
                "selftest", "selftest://artifact", ArtifactIngest.sha256Hex("自检题面"),
                "自检题面", "自检参考答案", "自检实际输出",
                null, "channel=STRUCTURED; rule=R1", null,
                "SUCCESS", Map.of("executionAccuracy", 1, "status", "OK"),
                null, "selftest", "自检", null, null, null, null);

        try {
            int n = writer.insertAll(List.of(row));
            failures += expect(n == 1, "写入返回 " + n + "，应为 1");
            int inDb = writer.countByRun(runId);
            failures += expect(inDb == 1, "库内条数 " + inDb + "，应为 1");

            // 读回并核对 JSONB 字段真的落成了结构化数据（不是一坨字符串）
            Map<String, Object> back = jdbc.queryForMap(
                    "SELECT scores->>'status' AS st, scores->>'executionAccuracy' AS ea, "
                            + "layer, split, judge_kind, outcome_status FROM eval_record WHERE run_id = ?",
                    runId);
            failures += expect("OK".equals(back.get("st")), "scores->>'status' 读回为 " + back.get("st"));
            failures += expect("1".equals(back.get("ea")), "scores->>'executionAccuracy' 读回为 " + back.get("ea"));
            failures += expect("L1".equals(back.get("layer")), "layer 读回为 " + back.get("layer"));

            // 唯一槽位键必须真的拦重复（否则"同一轮跑两遍"会被静默掩盖）
            boolean blocked = false;
            try {
                writer.insertAll(List.of(row));
            } catch (RuntimeException e) {
                blocked = true;
            }
            failures += expect(blocked, "重复插入同一槽位未被拦下——唯一约束没生效");
            failures += expect(writer.countByRun(runId) == 1,
                    "重复插入后库内条数变了（" + writer.countByRun(runId) + "），说明发生了覆盖或追加");

            // eval_id 必须是确定性派生（否则唯一约束拦不住、且重跑会变成新增）
            failures += expect(row.evalId().equals("selftest-evalrecord-writer:SELFTEST-001:1:-"),
                    "evalId 派生结果意外：" + row.evalId());

            // 绑定列数守卫必须真会抛（放在构造器里就能被这条自检覆盖）
            failures += expect(columnGuardWorks(), "列数守卫未按预期抛出");
        } finally {
            int removed = jdbc.update("DELETE FROM eval_record WHERE run_id = ?", runId);
            int left = writer.countByRun(runId);
            System.out.println("  自检结束：已清理 " + removed + " 条，库内剩余 " + left + " 条");
            failures += expect(left == 0, "自检残留 " + left + " 条未清理");
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("[FAIL] 自检有 " + failures + " 项未通过");
            return 3;
        }
        System.out.println("[PASS] 写入→读回→唯一约束→清理 全部通过");
        return 0;
    }

    /** 证明列数守卫真的会抛，而不是一行永远不会执行的代码。 */
    private static boolean columnGuardWorks() {
        try {
            EvalRecordWriter.assertColumnCount();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int expect(boolean cond, String failure) {
        if (!cond) {
            System.out.println("  [FAIL] " + failure);
            return 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ 工具
    public static DriverManagerDataSource dataSource(Map<String, String> env) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        // ⚠️ 优先整条 POSTGRES_URL：`.env` 里已有它，且它与 application.yml 第 56 行的默认值不同
        //    （默认值是拼好的 55432，而实测 55432 未监听、5432 在监听）。理由见 DECISIONS C-37 变更日志。
        String url = envOr(env, "POSTGRES_URL", null);
        if (url == null || url.isBlank()) {
            String host = envOr(env, "POSTGRES_HOST", "127.0.0.1");
            String port = envOr(env, "POSTGRES_PORT", "5432");
            String db = envOr(env, "POSTGRES_DB", "canalmind");
            url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }
        ds.setUrl(url);
        ds.setUsername(envOr(env, "POSTGRES_USER", "postgres"));
        ds.setPassword(envOr(env, "POSTGRES_PASSWORD", ""));
        return ds;
    }

    private static JdbcTemplate jdbcTemplate() {
        Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                List.of(Path.of(".env"), Path.of("../.env")));
        return new JdbcTemplate(dataSource(env));
    }

    private static String envOr(Map<String, String> env, String name, String fallback) {
        String v = env == null ? null : env.get(name);
        if (v == null || v.isBlank()) {
            v = com.canalmind.rag.cli.CliConsole.envOr(name, fallback);
        }
        return v;
    }

    /**
     * 解析默认产物路径：先按当前目录，再按上一级目录。
     *
     * <p>为什么要两个都试：本项目的命令**既可能在仓库根跑、也可能在 `backend/` 跑**
     * （`mvn` 必须在 `backend/`，而 `docs/` 在上一级），同一个相对路径在两处指向不同文件。
     * 这已被记为本项目一条环境坑（`工程复盘/环境坑/同一个相对路径解析到两个目录.md`）。
     * ⛔ 找不到时**返回原路径让调用方报"不存在"**，而不是静默换成别的文件。
     */
    public static Path resolve(String path) {
        Path direct = Path.of(path);
        if (Files.exists(direct)) {
            return direct;
        }
        Path up = Path.of("..").resolve(path);
        return Files.exists(up) ? up : direct;
    }

    static String distribution(List<EvalRecordRow> rows) {        Map<String, Integer> d = new TreeMap<>();
        for (EvalRecordRow r : rows) {
            d.merge(r.layer() + "/" + r.split(), 1, Integer::sum);
        }
        return d.toString();
    }

    private static void writeJson(Path out, List<EvalRecordRow> rows, String runId)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("runId", runId);
        root.put("count", rows.size());
        ObjectNode dist = root.putObject("distribution");
        Map<String, Integer> d = new TreeMap<>();
        for (EvalRecordRow r : rows) {
            d.merge(r.layer() + "/" + r.split(), 1, Integer::sum);
        }
        d.forEach(dist::put);
        ArrayNode arr = root.putArray("records");
        for (EvalRecordRow r : rows) {
            ObjectNode n = arr.addObject();
            n.put("evalId", r.evalId());
            n.put("taskId", r.taskId());
            n.put("layer", r.layer());
            n.put("split", r.split());
            n.put("judgeKind", r.judgeKind());
            n.put("outcomeStatus", r.outcomeStatus());
            n.put("inputHash", r.inputHash());
            n.put("rootCauseLayer", r.rootCauseLayer());
            n.put("errorCategory", r.errorCategory());
            ObjectNode s = n.putObject("scores");
            r.scores().forEach((k, v) -> {
                if (v instanceof Integer i) {
                    s.put(k, i);
                } else if (v instanceof Double dd) {
                    s.put(k, dd);
                } else {
                    s.put(k, String.valueOf(v));
                }
            });
        }
        writeJsonNode(out, root);
    }

    private static void writeJsonNode(Path out, JsonNode node) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentObjectsWith(new DefaultIndenter("  ", LF));
        pp.indentArraysWith(new DefaultIndenter("  ", LF));
        ObjectMapper m = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        Files.write(out, (m.writer(pp).writeValueAsString(node) + LF).getBytes(StandardCharsets.UTF_8));
    }

    private static void printUsage() {
        System.out.println("评测 Harness CLI（第 4 周第 4 项）");
        System.out.println("  --selftest                 对真实库做写入→读回→唯一约束→清理 往返自检");
        System.out.println("  --ingest --run-id <id>     把 L1/L2 已有产物读成 eval_record 行（默认空跑）");
        System.out.println("  --write                    真正写库（⛔ 不加则只空跑）");
        System.out.println("  --report --run-id <id>     读库汇总分层通过率");
        System.out.println("  --l1 / --l2-hits / --l2-golden / --primary-k / --json / --git-commit");
        System.out.println("  --l3-judged / --l5-judged / --l6-judged   Judge 三层产物（C-52；不给则用默认路径）");
    }
}
