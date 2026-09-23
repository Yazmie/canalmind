package com.canalmind.structured;

import com.canalmind.rag.cli.CliConsole;
import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化查询段的命令行入口（第 3 周）。
 *
 * <pre>
 * 用法：
 *   --query "问句"                端到端：抽取 → 业务校验 → 参数化 SQL/费用引擎 → 确定性事实
 *   --query-file 文件             批量（每行一题，空行与 # 开头跳过）
 *   --extract-only                只抽取不执行（评测"抽取命中率"用）
 *   --verify-engine-vs-db [N]     费用引擎与库内 fee_record 逐分对账（默认 200 行）
 *   --selftest                    离线自检（不连库、不调模型）：契约/校验闸门/重试降级/引擎手算
 *   --json 出参文件               把结果写成机读产物（确定性：键排序、LF、无 BOM）
 * </pre>
 *
 * <p>⚠️ 中文一律走 {@code --query-file}：Windows 控制台是 GBK，中文经 argv 会被改坏
 * （第 2 周已在 BM25 通道踩过）。
 */
public final class StructuredQueryCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LF = String.valueOf((char) 10);

    /** 参数抽取的输出额度：契约 + 一次调用意图，实测几百字足够；缺省 1024。 */
    public static final String STRUCTURED_MAX_TOKENS_ENV = "CANALMIND_STRUCTURED_MAX_TOKENS";
    public static final int DEFAULT_STRUCTURED_MAX_TOKENS = 1024;

    /** 业务当天（系统"今天"）：与数据窗口一致，见 `docs/数据模型-ER图.md` §四。 */
    public static final String BUSINESS_TODAY = "2027-09-15";

    public static void main(String[] args) {
        CliConsole.pinUtf8Stdout();
        int code;
        try {
            code = run(args);
        } catch (Exception e) {
            System.err.println("[FAIL] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        String query = null;
        Path queryFile = null;
        boolean extractOnly = false;
        Path jsonOut = null;
        Integer verifyRows = null;
        boolean selftest = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--query" -> query = args[++i];
                case "--query-file" -> queryFile = Path.of(args[++i]);
                case "--extract-only" -> extractOnly = true;
                case "--json" -> jsonOut = Path.of(args[++i]);
                case "--selftest" -> selftest = true;
                case "--verify-engine-vs-db" -> {
                    if (i + 1 < args.length && args[i + 1].matches("\\d+")) {
                        verifyRows = Integer.valueOf(args[++i]);
                    } else {
                        verifyRows = 200;
                    }
                }
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

        if (selftest) {
            return selftest(jsonOut);
        }
        if (verifyRows != null) {
            return verifyEngineVsDb(verifyRows, jsonOut);
        }
        if (query == null && queryFile == null) {
            printUsage();
            return 2;
        }

        ToolCatalog catalog = ToolCatalog.loadDefault();
        Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                List.of(Path.of(".env"), Path.of("../.env")));
        int maxTokens = resolveStructuredMaxTokens(env);
        JsonChatInvoker invoker = new DeepSeekJsonInvoker(
                LlmRerankerFactory.chatModelFromEnvironment(env, maxTokens), maxTokens);
        ToolArgumentExtractor extractor = new ToolArgumentExtractor(catalog, SchemaGate.of(catalog),
                invoker, ToolArgumentExtractor.DEFAULT_MAX_RETRIES, BUSINESS_TODAY);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("contractVersion", catalog.contractVersion());
        out.put("businessToday", BUSINESS_TODAY);
        out.put("extractOnly", extractOnly);
        ArrayNode results = out.putArray("results");

        if (query != null) {
            if (extractOnly) {
                results.add(extractionNode(query, extractor));
            } else {
                StructuredQueryService service = service(catalog, extractor, env);
                results.add(resultNode(query, service.ask(query)));
            }
        }
        if (queryFile != null) {
            StructuredQueryService service = extractOnly ? null : service(catalog, extractor, env);
            int n = 0;
            for (String line : Files.readAllLines(queryFile, StandardCharsets.UTF_8)) {
                String q = line.strip();
                if (q.isEmpty() || q.startsWith("#")) {
                    continue;
                }
                n++;
                System.out.println("---- [" + n + "] " + q);
                if (extractOnly) {
                    ExtractionOutcome o = extractor.extract(q);
                    System.out.println("     状态=" + o.status() + " 工具=" + (o.call() == null ? "-" : o.call().tool())
                            + " 尝试=" + o.attempts() + " 耗时=" + o.latencyMs() + "ms");
                    results.add(extractionNode(q, extractor));
                } else {
                    ToolResult r = service.ask(q);
                    printResult(r);
                    results.add(resultNode(q, r));
                }
            }
        }

        if (jsonOut != null) {
            writeJson(jsonOut, out);
            System.out.println("机读产物：" + jsonOut);
        }
        return 0;
    }

    private static StructuredQueryService service(ToolCatalog catalog, ToolArgumentExtractor extractor,
                                                 Map<String, String> env) {
        DriverManagerDataSource ds = dataSource(env);
        return new StructuredQueryService(catalog, extractor,
                new TariffRuleRepository(new JdbcTemplate(ds)),
                new StructuredQueryRepository(new JdbcTemplate(ds)),
                new FeeCalculationEngine(), BUSINESS_TODAY);
    }

    static DriverManagerDataSource dataSource(Map<String, String> env) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        String host = envOr(env, "POSTGRES_HOST", "127.0.0.1");
        String port = envOr(env, "POSTGRES_PORT", "5432");
        String db = envOr(env, "POSTGRES_DB", "canalmind");
        ds.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + db);
        ds.setUsername(envOr(env, "POSTGRES_USER", "postgres"));
        ds.setPassword(envOr(env, "POSTGRES_PASSWORD", ""));
        return ds;
    }

    /**
     * 先查**已合并的 env**（含 `.env` 文件），再退回进程环境变量。
     *
     * <p>⚠️ 不能只用 {@link CliConsole#envOr}：它读的是 `System.getenv`，**看不到 `.env` 文件**。
     * 实测表现是连库报 {@code SCRAM-based authentication, but the password is an empty string}
     * ——密码明明填在 `.env` 里，程序却拿着空串去连（本机口令不像空口令那样"碰巧能连"，
     * 所以它一定会以鉴权失败的形式暴露，而不是静默用错账号）。
     */
    private static String envOr(Map<String, String> env, String name, String fallback) {
        String value = env == null ? null : env.get(name);
        if (value == null || value.isBlank()) {
            value = CliConsole.envOr(name, fallback);
        }
        return value;
    }

    static int resolveStructuredMaxTokens(Map<String, String> env) {
        String raw = env == null ? null : env.get(STRUCTURED_MAX_TOKENS_ENV);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STRUCTURED_MAX_TOKENS;
        }
        try {
            int v = Integer.parseInt(raw.strip());
            if (v <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return v;
        } catch (NumberFormatException e) {
            // ⛔ 不静默退回默认值：额度写错却拿到"默认额度"的产物，是最难查的一类偏差
            throw new IllegalArgumentException(STRUCTURED_MAX_TOKENS_ENV + " 必须是正整数，实际为 \"" + raw + "\"");
        }
    }

    // ==================== 输出 ====================

    private static void printResult(ToolResult r) {
        System.out.println("     状态=" + r.status() + (r.degradeKind() == null ? "" : "（" + r.degradeKind() + "）"));
        for (String f : r.facts()) {
            System.out.println("     " + f);
        }
        if (!r.rows().isEmpty()) {
            System.out.println("     列：" + String.join(" | ", r.columns()));
            for (List<String> row : r.rows()) {
                System.out.println("     " + String.join(" | ", row));
            }
        }
        for (String t : r.traces()) {
            System.out.println("     · " + t);
        }
    }

    private static ObjectNode resultNode(String question, ToolResult r) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("question", question);
        n.put("status", r.status());
        if (r.degradeKind() != null) {
            n.put("degradeKind", r.degradeKind());
        }
        if (r.tool() != null) {
            n.put("tool", r.tool());
        }
        if (r.question() != null) {
            n.put("clarification", r.question());
        }
        ArrayNode facts = n.putArray("facts");
        r.facts().forEach(facts::add);
        ArrayNode cols = n.putArray("columns");
        r.columns().forEach(cols::add);
        ArrayNode rows = n.putArray("rows");
        for (List<String> row : r.rows()) {
            ArrayNode rr = rows.addArray();
            row.forEach(rr::add);
        }
        ArrayNode traces = n.putArray("traces");
        r.traces().forEach(traces::add);
        return n;
    }

    private static ObjectNode extractionNode(String question, ToolArgumentExtractor extractor) {
        ExtractionOutcome o = extractor.extract(question);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("question", question);
        n.put("status", o.status());
        n.put("attempts", o.attempts());
        n.put("latencyMs", o.latencyMs());
        if (o.degradeKind() != null) {
            n.put("degradeKind", o.degradeKind());
        }
        if (o.call() != null) {
            n.put("tool", o.call().tool());
            n.set("arguments", o.call().arguments());
            ArrayNode miss = n.putArray("missing");
            o.call().missing().forEach(miss::add);
        }
        ArrayNode errs = n.putArray("errors");
        o.errors().forEach(errs::add);
        ArrayNode raw = n.putArray("rawResponses");
        o.rawResponses().forEach(raw::add);
        return n;
    }

    private static void writeJson(Path out, JsonNode node) throws IOException {
        // 确定性产物：缩进 + LF + UTF-8 无 BOM（与第 2 周产物同规矩）
        String text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                .replace(String.valueOf((char) 13) + String.valueOf((char) 10), LF);
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(out, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void printUsage() {
        System.out.println("用法：");
        System.out.println("  --query \"问句\"               端到端（抽取 → 校验 → 参数化执行）");
        System.out.println("  --query-file 文件             批量（每行一题）");
        System.out.println("  --extract-only                只抽取不执行");
        System.out.println("  --verify-engine-vs-db [N]     费用引擎与库内 fee_record 逐分对账（默认 200）");
        System.out.println("  --selftest                    离线自检（不连库、不调模型）");
        System.out.println("  --json 出参文件               机读产物");
    }

    // ==================== 自检与对账 ====================

    /** 离线自检：契约、校验闸门、重试/降级、费用引擎手算——全都不依赖 DB 与模型。 */
    static int selftest(Path jsonOut) throws IOException {
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        ToolCatalog catalog = ToolCatalog.loadDefault();
        SchemaGate gate = SchemaGate.of(catalog);
        checks.add("契约加载：版本 " + catalog.contractVersion() + "，工具 "
                + catalog.tools().keySet() + "，货种 " + catalog.cargoTaxonomy().size()
                + "，业务规则 " + catalog.businessRules().size());
        expect(catalog.tools().size() >= 3 && catalog.tools().size() <= 5,
                "工具数量须在 3–5（C-33），实际 " + catalog.tools().size(), failures);
        expect(gate.toolSchemaCount() == catalog.tools().size(),
                "校验闸门注册的工具 Schema 数须等于契约工具数", failures);

        List<String> enumValues = new ArrayList<>();
        catalog.envelope().path("properties").path("tool").path("enum").forEach(n -> enumValues.add(n.asText()));
        expect(enumValues.containsAll(catalog.tools().keySet()) && enumValues.contains(ToolCatalog.NONE_TOOL),
                "envelope 的 tool 枚举须等于工具名 + none，实际 " + enumValues, failures);
        expect(catalog.cargoTaxonomy().size() == 21, "货种表须 21 种，实际 " + catalog.cargoTaxonomy().size(), failures);
        expect(catalog.businessRules().size() >= 5, "业务规则不得为空", failures);

        // --- 校验闸门：正例通过、四类反例都要被拦（且错误消息非空）---
        String good = "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过闸费\","
                + "\"cargo_category\":\"建材\",\"tonnage\":1200,\"biz_date\":\"2026-10-01\"},"
                + "\"missing\":[],\"reason\":\"查费率\"}";
        JsonNode goodNode = MAPPER.readTree(good);
        List<String> goodErrors = gate.validateEnvelope(goodNode);
        expect(goodErrors.isEmpty(), "合法输出不该报错，实际 " + goodErrors, failures);
        expect(gate.validateArguments("query_tariff", goodNode.path("arguments")).isEmpty(),
                "合法参数不该报错", failures);

        checkRejected(gate, catalog, "枚举越界", "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过路费\","
                + "\"cargo_category\":\"建材\",\"tonnage\":1200,\"biz_date\":\"2026-10-01\"},\"missing\":[],\"reason\":\"x\"}",
                "query_tariff", failures);
        checkRejected(gate, catalog, "多余字段", "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过闸费\","
                + "\"cargo_category\":\"建材\",\"tonnage\":1200,\"biz_date\":\"2026-10-01\",\"extra\":1},\"missing\":[],\"reason\":\"x\"}",
                "query_tariff", failures);
        checkRejected(gate, catalog, "日期格式", "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过闸费\","
                + "\"cargo_category\":\"建材\",\"tonnage\":1200,\"biz_date\":\"2026/10/01\"},\"missing\":[],\"reason\":\"x\"}",
                "query_tariff", failures);
        checkRejected(gate, catalog, "吨位为 0", "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过闸费\","
                + "\"cargo_category\":\"建材\",\"tonnage\":0,\"biz_date\":\"2026-10-01\"},\"missing\":[],\"reason\":\"x\"}",
                "query_tariff", failures);
        checkRejected(gate, catalog, "envelope 越界", "{\"tool\":\"query_tariff\",\"arguments\":{},\"missing\":[],"
                + "\"reason\":\"x\",\"thought\":\"多余键\"}", "query_tariff", failures);
        checkRejected(gate, catalog, "缺必填参数", "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过闸费\"},"
                + "\"missing\":[],\"reason\":\"x\"}", "query_tariff", failures);

        // --- 抽取器：重试与三条降级路径（用桩演剧本，不打真实模型）---
        String validReply = good;
        expect(extractWith(catalog, gate, List.of(validReply)).ok(), "首次合规应答应直接成功", failures);
        ExtractionOutcome retried = extractWith(catalog, gate, List.of("not json at all", validReply));
        expect(retried.ok() && retried.attempts() == 2, "先畸形后合规应重试一次成功，实际 " + retried.status() + "/" + retried.attempts(), failures);
        ExtractionOutcome fenced = extractWith(catalog, gate, List.of("```json" + LF + validReply + LF + "```"));
        expect(fenced.ok(), "带代码块围栏的应答应被剥围栏后接受（实测常态）", failures);
        ExtractionOutcome bad = extractWith(catalog, gate, List.of("nope", "nope", "nope", "nope"));
        expect(bad.degraded() && ExtractionOutcome.KIND_NOT_JSON.equals(bad.degradeKind()) && bad.attempts() == 3,
                "全畸形应答应重试到上限后降级 NOT_JSON，实际 " + bad.status() + "/" + bad.degradeKind() + "/" + bad.attempts(), failures);
        ExtractionOutcome valueViolation = extractWith(catalog, gate,
                List.of("{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过路费\","
                        + "\"cargo_category\":\"建材\",\"tonnage\":1200,\"biz_date\":\"2026-10-01\"},"
                        + "\"missing\":[],\"reason\":\"x\"}"));
        expect(valueViolation.rejected()
                        && ExtractionOutcome.KIND_PARAM_VIOLATION.equals(valueViolation.degradeKind()),
                "值越界/枚举不符应**拒绝执行**（缺口① / D24 → C-65），实际 "
                        + valueViolation.status() + "/" + valueViolation.degradeKind(), failures);
        expect(!valueViolation.errors().isEmpty() && !valueViolation.errors().get(0).isBlank(),
                "拒绝必须带**非空**理由（否则回答层只能说「参数不合法」）", failures);
        ExtractionOutcome schemaBad = extractWith(catalog, gate,
                List.of("{\"tool\":\"query_tariff\",\"arguments\":{\"tonnage\":1200,"
                        + "\"biz_date\":\"2026-10-01\"},\"missing\":[],\"reason\":\"x\"}"));
        expect(schemaBad.degraded() && ExtractionOutcome.KIND_SCHEMA_INVALID.equals(schemaBad.degradeKind()),
                "缺必填（形状类违规）仍应降级 SCHEMA_INVALID，实际 " + schemaBad.status() + "/"
                        + schemaBad.degradeKind(), failures);
        expect(!schemaBad.errors().isEmpty() && !schemaBad.errors().get(0).isBlank(),
                "降级必须带**非空**错误消息（空消息喂回模型等于没喂）", failures);
        ExtractionOutcome noneTool = extractWith(catalog, gate,
                List.of("{\"tool\":\"none\",\"arguments\":{},\"missing\":[],\"reason\":\"政策咨询\"}"));
        expect(noneTool.degraded() && ExtractionOutcome.KIND_TOOL_NONE.equals(noneTool.degradeKind()),
                "tool=none 应降级为 TOOL_NONE（交给检索通道），实际 " + noneTool.degradeKind(), failures);
        ExtractionOutcome missing = extractWith(catalog, gate,
                List.of("{\"tool\":\"quote_fee\",\"arguments\":{\"fee_type\":\"过闸费\"},\"missing\":[\"tonnage\",\"biz_date\"],\"reason\":\"缺吨位\"}"));
        expect(missing.needsClarification() && missing.call() != null && missing.call().missing().size() == 2,
                "模型申报缺参应转追问且保留缺失清单，实际 " + missing.status(), failures);
        ExtractionOutcome modelErr = extractThrowing(catalog, gate);
        expect(modelErr.degraded() && ExtractionOutcome.KIND_MODEL_ERROR.equals(modelErr.degradeKind())
                        && modelErr.attempts() == 3,
                "模型调用持续失败应重试后降级 MODEL_ERROR，实际 " + modelErr.degradeKind() + "/" + modelErr.attempts(), failures);

        // --- 费用引擎：**手算**对照（与数据库抽样值相互独立）---
        FeeCalculationEngine engine = new FeeCalculationEngine();
        expectQuote(engine, rule("过闸费", "建材", "载货吨位", "500", "2000", "2.55", "元/吨"),
                new BigDecimal("1000"), 1, false, LocalDate.of(2026, 10, 1), "2550.00", failures, "过闸费 建材 1000 吨");
        expectQuote(engine, rule("过闸费", "能源", "载货吨位", "500", "2000", "2.90", "元/吨"),
                new BigDecimal("1325.37"), 1, true, LocalDate.of(2026, 10, 1), "3497.65", failures,
                "过闸费 能源(危险+重点保供) 1325.37 吨（0.70×1.30）");
        expectQuote(engine, rule("装卸费", "集装箱", "载货吨位", "2000", null, "32.00", "元/吨"),
                new BigDecimal("4343.08"), 1, false, LocalDate.of(2027, 6, 20), "138978.56", failures,
                "装卸费 集装箱 4343.08 吨");
        expectQuote(engine, rule("引航费", "不区分", "船舶总吨位", "1000", "5000", "1500.00", "元/次"),
                new BigDecimal("2000"), 1, false, LocalDate.of(2027, 3, 1), "1500.00", failures, "引航费 总吨位 2000");
        expectQuote(engine, rule("停泊费", "不区分", "船舶总吨位", "1000", "5000", "0.12", "元/吨天"),
                new BigDecimal("2000"), 3, false, LocalDate.of(2027, 3, 1), "720.00", failures, "停泊费 总吨位 2000 × 3 天");
        expectQuote(engine, rule("港杂费", "建材", "载货吨位", "1000", "5000", "320.00", "元/次"),
                new BigDecimal("4343.08"), 1, false, LocalDate.of(2026, 10, 1), "320.00", failures, "港杂费 元/次 与吨位无关");
        checks.add("费用引擎手算对照 6 例（含重点保供 0.70 × 危险品 1.30 复合）");
        expect(FeeCalculationEngine.UNIMPLEMENTED_CLAUSES.size() >= 5,
                "未实现条款清单不得为空（C-36 要求逐条登记）", failures);

        ObjectNode node = MAPPER.createObjectNode();
        node.put("mode", "selftest");
        node.put("contractVersion", catalog.contractVersion());
        ArrayNode arr = node.putArray("checks");
        checks.forEach(arr::add);
        ArrayNode fails = node.putArray("failures");
        failures.forEach(fails::add);
        if (jsonOut != null) {
            writeJson(jsonOut, node);
        }
        for (String c : checks) {
            System.out.println("  [OK] " + c);
        }
        if (failures.isEmpty()) {
            System.out.println("[PASS] 结构化查询段自检通过（" + checks.size() + " 项）");
            return 0;
        }
        failures.forEach(f -> System.out.println("  [FAIL] " + f));
        System.out.println("[FAIL] 自检失败 " + failures.size() + " 项");
        return 1;
    }

    private static void checkRejected(SchemaGate gate, ToolCatalog catalog, String what, String json,
                                     String tool, List<String> failures) {
        try {
            JsonNode node = MAPPER.readTree(json);
            List<String> envErrors = gate.validateEnvelope(node);
            List<String> argErrors = envErrors.isEmpty()
                    ? gate.validateArguments(tool, node.path("arguments")) : envErrors;
            boolean rejected = !envErrors.isEmpty() || !argErrors.isEmpty();
            boolean messageOk = !rejected || (argErrors.isEmpty() ? envErrors : argErrors).stream()
                    .anyMatch(s -> s != null && !s.isBlank());
            expect(rejected, "「" + what + "」必须被拦下", failures);
            expect(messageOk, "「" + what + "」的错误消息不得为空", failures);
        } catch (IOException e) {
            failures.add("「" + what + "」测试夹具本身不是合法 JSON：" + e.getMessage());
        }
    }

    private static void expectQuote(FeeCalculationEngine engine, TariffRuleRow rule, BigDecimal tonnage,
                                    int days, boolean dangerous, LocalDate bizDate, String expected,
                                    List<String> failures, String label) {
        try {
            BigDecimal actual = engine.quote(rule, tonnage, days, dangerous, bizDate).amount();
            expect(actual.toPlainString().equals(expected),
                    label + " 手算应为 " + expected + "，实际 " + actual.toPlainString(), failures);
        } catch (RuntimeException e) {
            failures.add(label + " 抛异常：" + e.getMessage());
        }
    }

    private static void expect(boolean cond, String failure, List<String> failures) {
        if (!cond) {
            failures.add(failure);
        }
    }

    private static TariffRuleRow rule(String feeType, String category, String basis, String minTon,
                                     String maxTon, String price, String unit) {
        return new TariffRuleRow(1L, "CT-" + feeType + "-" + category + "-" + minTon, feeType, category, basis,
                new BigDecimal(minTon), maxTon == null ? null : new BigDecimal(maxTon),
                new BigDecimal(price), unit, LocalDate.of(2026, 9, 16), null);
    }

    private static ExtractionOutcome extractWith(ToolCatalog catalog, SchemaGate gate, List<String> replies) {
        List<String> queue = new ArrayList<>(replies);
        // ⚠️ 剧本用尽后**重复最后一个应答**，而不是返回空串：模型重试时通常仍给同样形状的错答；
        // 而"用尽就返空串"会把降级原因从 SCHEMA_INVALID 变成 NOT_JSON，
        // 让**被测逻辑的问题伪装成另一类失败**（实测踩到，自检一度红在这里）。
        String[] last = {""};
        JsonChatInvoker stub = (sys, user) -> {
            if (!queue.isEmpty()) {
                last[0] = queue.remove(0);
            }
            return last[0];
        };
        return new ToolArgumentExtractor(catalog, gate, stub,
                ToolArgumentExtractor.DEFAULT_MAX_RETRIES, BUSINESS_TODAY).extract("测试问题");
    }

    private static ExtractionOutcome extractThrowing(ToolCatalog catalog, SchemaGate gate) {
        JsonChatInvoker stub = (sys, user) -> {
            throw new IllegalStateException("模拟网络失败");
        };
        return new ToolArgumentExtractor(catalog, gate, stub,
                ToolArgumentExtractor.DEFAULT_MAX_RETRIES, BUSINESS_TODAY).extract("测试问题");
    }

    /**
     * 费用引擎 vs 数据库：抽 N 行 {@code fee_record}，用 Java 引擎按 {@code calc_basis} 重算并逐分对账。
     *
     * <p>这是任务 3 最强的证据：库内金额由 **Python 生成器**算出，Java 引擎独立实现同一口径，
     * 两边一致才说明"纯代码费用引擎"与既有数据是同一条口径（**不是自证**）。
     */
    static int verifyEngineVsDb(int rows, Path jsonOut) throws IOException {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                List.of(Path.of(".env"), Path.of("../.env")));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(env));
        FeeCalculationEngine engine = new FeeCalculationEngine();

        String sql = """
                SELECT f.id, f.fee_type, f.amount::text AS amount, f.calc_basis,
                       r.rule_code, r.cargo_category, r.tonnage_basis, r.min_ton, r.max_ton,
                       r.unit_price, r.unit, r.effective_from, r.effective_to
                  FROM fee_record f
                  JOIN tariff_rule r ON r.id = f.rule_id
                 ORDER BY md5(f.id::text)
                 LIMIT ?
                """;
        ObjectNode node = MAPPER.createObjectNode();
        node.put("mode", "verify-engine-vs-db");
        node.put("sampled", rows);
        ArrayNode mismatches = node.putArray("mismatches");
        int[] stats = new int[3]; // 0=比对行数 1=一致 2=不一致

        jdbc.query(sql, rs -> {
            stats[0]++;
            TariffRuleRow rule = new TariffRuleRow(1L, rs.getString("rule_code"), rs.getString("fee_type"),
                    rs.getString("cargo_category"), rs.getString("tonnage_basis"),
                    rs.getBigDecimal("min_ton"), rs.getBigDecimal("max_ton"),
                    rs.getBigDecimal("unit_price"), rs.getString("unit"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class));
            JsonNode basis;
            try {
                basis = MAPPER.readTree(rs.getString("calc_basis"));
            } catch (Exception e) {
                throw new IllegalStateException("calc_basis 不是合法 JSON（id=" + rs.getLong("id") + "）", e);
            }
            BigDecimal tonnage = new BigDecimal(basis.path("tonnage").asText());
            int days = basis.path("days").asInt(1);
            boolean dangerous = "1.30".equals(basis.path("surcharge").asText());
            LocalDate bizDate = LocalDate.parse(basis.path("as_of").asText());
            BigDecimal expected = new BigDecimal(rs.getString("amount"));
            BigDecimal actual = engine.quote(rule, tonnage, days, dangerous, bizDate).amount();
            if (actual.compareTo(expected) == 0) {
                stats[1]++;
            } else {
                stats[2]++;
                ObjectNode m = mismatches.addObject();
                m.put("feeRecordId", rs.getLong("id"));
                m.put("ruleCode", rule.ruleCode());
                m.put("expected", expected.toPlainString());
                m.put("actual", actual.toPlainString());
                m.put("tonnage", tonnage.toPlainString());
                m.put("days", days);
                m.put("dangerous", dangerous);
            }
        }, rows);

        node.put("compared", stats[0]);
        node.put("matched", stats[1]);
        node.put("mismatched", stats[2]);
        if (jsonOut != null) {
            writeJson(jsonOut, node);
        }
        System.out.println("对账行数 " + stats[0] + "，一致 " + stats[1] + "，不一致 " + stats[2]);
        if (stats[2] > 0) {
            System.out.println("[FAIL] 费用引擎与库内金额存在差异（前 3 条见机读产物）");
            return 1;
        }
        if (stats[0] == 0) {
            System.out.println("[FAIL] 抽样 0 行：数据库可能为空或未连上，**不能当作通过**");
            return 1;
        }
        System.out.println("[PASS] 费用引擎与库内 fee_record 逐分一致（Java 引擎 ⟷ Python 生成器同口径）");
        System.out.println("       （样本 " + stats[0] + " 行，契约版本 " + catalog.contractVersion() + "）");
        return 0;
    }
}
