package com.canalmind.intent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.canalmind.structured.DeepSeekJsonInvoker;
import com.canalmind.structured.JsonChatInvoker;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 意图路由命令行（第 4 周第 1 项）。
 *
 * <pre>
 *   --selftest                 离线自检（不连库、不调模型）：拿**真实数据集**逐题断言路由
 *   --query "…"                对一条问题打路由结论（规则优先，离线）
 *   --query-file &lt;path&gt;       对多行问题打路由结论（# 开头的行与空行跳过）
 *   --classifier-live          允许在规则未命中时调用真实分类器（**付费**，默认关闭）
 *   --json &lt;path&gt;             机读产物（LF、键排序、无时间戳 ⇒ 可安全 diff）
 *   --golden &lt;path&gt;           覆盖数据集路径（默认自动定位 docs/评测报告/…）
 * </pre>
 *
 * <h2>自检为什么用真实数据集、而不是手写几个例子</h2>
 * 手写例子只能证明"我造的规则能匹配我造的句子"。{@code --selftest} 直接读
 * {@code golden-set-v3.json}（L1/L3/L4/L5/L6，120 条）与 {@code golden-l2-v2.json}（L2，40 条），
 * **逐题**打印实际路由，并对有明确期望的层做**全量**断言（⛔ 不抽样、⛔ 不只看通过率）。
 * 依据：`AGENTS.md` §6 第 1 条（每个数字都要有出处）+ §0.3 坑 #4（逐格全量 diff）。
 */
public final class IntentRouterCli {

    private static final String LF = String.valueOf((char) 10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 自检时断言"该判成什么"的层 → 期望通道。 */
    private static final Map<String, String> ASSERTED = new LinkedHashMap<>();

    static {
        // ⚠️ 这里**故意只有四层**。L2 / L3 不断言，理由写在 IntentRouter 的类注释里：
        //    L2 是段级（检索）数据集，路由不参与它的指标；L3 的 dataset 标签是"行为"而非"路由"。
        ASSERTED.put("L1", RouteDecision.CHANNEL_STRUCTURED);
        ASSERTED.put("L4", RouteDecision.CHANNEL_STRUCTURED);
        ASSERTED.put("L5", RouteDecision.CHANNEL_COMPOSITE);
        ASSERTED.put("L6", RouteDecision.CHANNEL_STRUCTURED);
    }

    private IntentRouterCli() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Exception ex) {
            System.err.println("异常：" + ex);
            ex.printStackTrace();
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException {
        boolean selftest = false;
        boolean classifierLive = false;
        String query = null;
        Path queryFile = null;
        Path jsonOut = null;
        Path golden = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--selftest" -> selftest = true;
                case "--classifier-live" -> classifierLive = true;
                case "--query" -> query = args[++i];
                case "--query-file" -> queryFile = Path.of(args[++i]);
                case "--json" -> jsonOut = outputPath(Path.of(args[++i]));
                case "--golden" -> golden = Path.of(args[++i]);
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
            return selftest(golden, jsonOut, classifierLive);
        }
        if (query == null && queryFile == null) {
            printUsage();
            return 2;
        }

        IntentRouter router = router(classifierLive);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("classifierLive", classifierLive);
        ArrayNode results = out.putArray("results");

        if (query != null) {
            RouteDecision d = router.route(query);
            printDecision(1, query, d);
            results.add(node(query, d, null));
        }
        if (queryFile != null) {
            int n = 0;
            for (String line : Files.readAllLines(queryFile, StandardCharsets.UTF_8)) {
                String q = line.strip();
                if (q.isEmpty() || q.startsWith("#")) {
                    continue;
                }
                n++;
                RouteDecision d = router.route(q);
                printDecision(n, q, d);
                results.add(node(q, d, null));
            }
        }
        if (jsonOut != null) {
            writeJson(jsonOut, out);
            System.out.println("机读产物：" + jsonOut);
        }
        return 0;
    }

    private static IntentRouter router(boolean classifierLive) {
        if (!classifierLive) {
            return IntentRouter.rulesOnly();
        }
        Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                List.of(Path.of(".env"), Path.of("../.env")));
        int maxTokens = 512;
        JsonChatInvoker invoker = new DeepSeekJsonInvoker(
                LlmRerankerFactory.chatModelFromEnvironment(env, maxTokens), maxTokens);
        return new IntentRouter(invoker);
    }

    // ------------------------------------------------------------------ 自检
    private static int selftest(Path golden, Path jsonOut, boolean classifierLive) throws IOException {
        List<Item> items = loadItems(golden);
        if (items.isEmpty()) {
            System.err.println("[FAIL] 一条评测题都没读到——数据集路径不对，或文件结构变了");
            return 3;
        }

        IntentRouter router = router(classifierLive);

        System.out.println("=== 意图路由自检（第 4 周第 1 项，决策 C-37）===");
        System.out.println("数据集：" + items.size() + " 条（来自 golden-set-v3.json + golden-l2-v2.json）");
        Map<String, Integer> byLayer = new TreeMap<>();
        for (Item it : items) {
            byLayer.merge(it.layer, 1, Integer::sum);
        }
        System.out.println("分层：" + byLayer);

        // 逐题路由
        Map<String, List<Result>> grouped = new TreeMap<>();
        List<Result> all = new ArrayList<>();
        for (Item it : items) {
            RouteDecision d = router.route(it.question);
            Result r = new Result(it, d);
            all.add(r);
            grouped.computeIfAbsent(it.layer, k -> new ArrayList<>()).add(r);
        }

        System.out.println();
        System.out.println("--- 断言（逐题全量，⛔ 不抽样）---");
        int failedGroups = 0;
        for (Map.Entry<String, String> e : ASSERTED.entrySet()) {
            String layer = e.getKey();
            String want = e.getValue();
            List<Result> rs = grouped.getOrDefault(layer, List.of());
            List<Result> miss = new ArrayList<>();
            for (Result r : rs) {
                if (!want.equals(r.decision.channel())) {
                    miss.add(r);
                }
            }
            if (miss.isEmpty()) {
                System.out.println("  [PASS] " + layer + " " + rs.size() + "/" + rs.size()
                        + " 判为 " + want);
            } else {
                failedGroups++;
                System.out.println("  [FAIL] " + layer + " " + (rs.size() - miss.size()) + "/"
                        + rs.size() + " 判为 " + want + "，以下 " + miss.size() + " 条不符：");
                for (Result r : miss) {
                    System.out.println("         " + r.item.id + " → 实际 " + r.decision.channel()
                            + "（规则 " + r.decision.ruleId() + "）");
                    System.out.println("            理由：" + r.decision.reason());
                    System.out.println("            题面：" + r.item.question);
                }
            }
        }

        System.out.println();
        System.out.println("--- 分布（不断言，理由见 IntentRouter 类注释）---");
        for (Map.Entry<String, List<Result>> e : grouped.entrySet()) {
            Map<String, Integer> dist = new TreeMap<>();
            for (Result r : e.getValue()) {
                dist.merge(r.decision.channel(), 1, Integer::sum);
            }
            String note = ASSERTED.containsKey(e.getKey()) ? "" : "   ← 该层不做路由断言";
            System.out.println("  " + e.getKey() + "（" + e.getValue().size() + " 条）：" + dist + note);
        }
        System.out.println();
        System.out.println("  路由通道合计：");
        Map<String, Integer> total = new TreeMap<>();
        for (Result r : all) {
            total.merge(r.decision.channel(), 1, Integer::sum);
        }
        total.forEach((k, v) -> System.out.println("    " + k + " = " + v));

        System.out.println();
        System.out.println("--- 规则覆盖率（未命中任何规则 ⇒ 需分类器兜底的题）---");
        List<Result> needClassifier = new ArrayList<>();
        for (Result r : all) {
            if (IntentRouter.RULE_R6_FALLBACK.equals(r.decision.ruleId())) {
                needClassifier.add(r);
            }
        }
        int rulesCovered = all.size() - needClassifier.size();
        System.out.printf("  规则命中 %d/%d（%.4f）；需分类器兜底 %d 条%n",
                rulesCovered, all.size(), (double) rulesCovered / all.size(), needClassifier.size());
        for (Result r : needClassifier) {
            System.out.println("        待兜底 " + r.item.id + "：" + r.item.question);
        }

        System.out.println();
        System.out.println("--- 规则命中分布（哪条规则在干活）---");
        Map<String, Integer> ruleDist = new TreeMap<>();
        for (Result r : all) {
            ruleDist.merge(r.decision.ruleId(), 1, Integer::sum);
        }
        ruleDist.forEach((k, v) -> System.out.println("    " + k + " = " + v));

        if (jsonOut != null) {
            writeJson(jsonOut, product(all, byLayer, rulesCovered));
            System.out.println();
            System.out.println("机读产物：" + jsonOut);
        }

        System.out.println();
        if (failedGroups > 0) {
            System.out.println("[FAIL] 有 " + failedGroups + " 层的路由断言未通过（详见上方逐题清单）");
            return 3;
        }
        System.out.println("[PASS] 四层路由断言全部通过（L1 40 / L4 25 / L5 20 / L6 15 = 100 条逐题核对）");
        return 0;
    }

    private static ObjectNode product(List<Result> all, Map<String, Integer> byLayer,
                                      int rulesCovered) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("builtFor", "第 4 周第 1 项：意图路由（决策 C-37）");
        root.put("router", "规则优先 + 分类器兜底 + 复合串行拆分");
        ObjectNode layers = root.putObject("layers");
        byLayer.forEach(layers::put);
        ObjectNode dist = root.putObject("channelTotals");
        Map<String, Integer> total = new TreeMap<>();
        for (Result r : all) {
            total.merge(r.decision.channel(), 1, Integer::sum);
        }
        total.forEach(dist::put);
        root.put("rulesCovered", rulesCovered);
        root.put("needClassifier", all.size() - rulesCovered);
        ObjectNode asserted = root.putObject("assertedLayers");
        ASSERTED.forEach(asserted::put);

        ArrayNode arr = root.putArray("results");
        for (Result r : all) {
            arr.add(node(r.item.question, r.decision, r.item));
        }
        return root;
    }

    // ------------------------------------------------------------------ 装载
    /** 一条评测题（只取路由需要的字段）。 */
    record Item(String id, String layer, String question, String datasetChannel) {
    }

    static List<Item> loadItems(Path goldenOverride) throws IOException {
        List<Item> out = new ArrayList<>();
        Path v3 = goldenOverride != null ? goldenOverride : locate("golden-set-v3.json");
        if (v3 != null && Files.exists(v3)) {
            JsonNode root = MAPPER.readTree(Files.readString(v3, StandardCharsets.UTF_8));
            for (JsonNode it : root.path("items")) {
                String layer = it.path("layer").asText();
                String q = it.path("question").asText();
                if ("L4".equals(layer)) {
                    // ⚠️ L4 是**多轮**题：`question` 字段只是首轮题面，真实首轮在 dialogue[0]。
                    //    路由评测只判首轮（后续轮次由会话上下文改写后再判，见 C-38）。
                    JsonNode d0 = it.path("expected").path("dialogue").path(0).path("question");
                    if (!d0.isMissingNode() && !d0.asText().isBlank()) {
                        q = d0.asText();
                    }
                }
                out.add(new Item(it.path("id").asText(), layer, q,
                        it.path("expected").path("channel").asText("")));
            }
        }
        Path l2 = goldenOverride != null
                ? goldenOverride.getParent().resolve("golden-l2-v2.json")
                : locate("golden-l2-v2.json");
        if (l2 != null && Files.exists(l2)) {
            JsonNode root = MAPPER.readTree(Files.readString(l2, StandardCharsets.UTF_8));
            for (JsonNode it : root.path("items")) {
                out.add(new Item(it.path("id").asText(), "L2", it.path("question").asText(),
                        it.path("expected").path("channel").asText("")));
            }
        }
        return out;
    }

    /**
     * 定位数据集：先看当前目录（在仓库根跑），再看上一级（在 backend/ 跑）。
     *
     * <p>⛔ 找不到就返回 {@code null} 并让调用方报错——**不静默改用空数据集**：
     * "一条题都没读到却报 PASS" 是本项目最典型的一类假绿。
     */
    static Path locate(String name) {
        for (String prefix : new String[] {"docs/评测报告/", "../docs/评测报告/"}) {
            Path p = Path.of(prefix + name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 解析**输出**路径：相对路径一律锚到仓库根，⛔ 不锚到"当前在哪"。
     *
     * <p><b>为什么必须这样</b>：本项目的命令**既可能在仓库根跑、也可能在 `backend/` 跑**
     * （`mvn` 必须在 `backend/`，而 `docs/` 在上一级）。若相对输出路径按 CWD 解析，
     * 同一条命令在两处会写进**两棵不同的目录树**。
     *
     * <p>⚠️ **实测踩到过**：`--json docs/评测报告/intent-routing.json` 在 `backend/` 下跑时
     * 产物落进了 `backend/docs/评测报告/`，而文档（`DECISIONS.md`、`评测报告.md`）
     * 引用的是根下的 `docs/评测报告/`——**"产物已生成"与"引用它的文档"指向两个文件，
     * 且两边都不报错**。当时的记录还把字节数与 LF 数写成了**上一次运行的**值。
     * 同族条目：`工程复盘/环境坑/同一个相对路径解析到两个目录.md`。
     *
     * <p>锚点取"存在 `docs/评测报告/` 的那一层"，与 {@link #locate} 用同一套前缀，
     * 保证**读入与写出锚在同一棵树**。
     */
    static Path outputPath(Path p) {
        if (p == null || p.isAbsolute()) {
            return p;
        }
        for (String prefix : new String[] {"", "../"}) {
            if (Files.exists(Path.of(prefix + "docs/评测报告"))) {
                return Path.of(prefix).resolve(p);
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ 输出
    record Result(Item item, RouteDecision decision) {
    }

    private static void printDecision(int n, String question, RouteDecision d) {
        System.out.println("---- [" + n + "] " + question);
        System.out.println("     通道=" + d.channel() + "  规则=" + d.ruleId()
                + "  步骤=" + d.steps().size()
                + "  分类器=" + d.classifierUsed()
                + "  置信=" + String.format(Locale.ROOT, "%.2f", d.confidence()));
        System.out.println("     理由=" + d.reason());
        for (int i = 0; i < d.steps().size(); i++) {
            RouteDecision.RouteStep s = d.steps().get(i);
            System.out.println("     步骤" + (i + 1) + "：" + s.channel() + " ← 聚焦：" + s.focus());
        }
    }

    private static ObjectNode node(String question, RouteDecision d, Item item) {
        ObjectNode n = MAPPER.createObjectNode();
        if (item != null) {
            n.put("id", item.id());
            n.put("layer", item.layer());
            if (!item.datasetChannel().isEmpty()) {
                n.put("datasetChannel", item.datasetChannel());
            }
        }
        n.put("question", question);
        n.put("channel", d.channel());
        n.put("ruleId", d.ruleId());
        n.put("reason", d.reason());
        n.put("classifierUsed", d.classifierUsed());
        n.put("confidence", d.confidence());
        ArrayNode steps = n.putArray("steps");
        for (RouteDecision.RouteStep s : d.steps()) {
            ObjectNode sn = steps.addObject();
            sn.put("channel", s.channel());
            sn.put("query", s.query());
            sn.put("focus", s.focus());
        }
        return n;
    }

    /** 写 JSON：**显式钉死 LF 与键序**，⛔ 不用平台默认（`AGENTS.md` §7.2 的 Jackson 陷阱）。 */
    static void writeJson(Path out, JsonNode node) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentObjectsWith(new DefaultIndenter("  ", LF));
        pp.indentArraysWith(new DefaultIndenter("  ", LF));
        ObjectMapper m = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        String text = m.writer(pp).writeValueAsString(node) + LF;
        Files.write(out, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void printUsage() {
        System.out.println("意图路由 CLI（第 4 周第 1 项）");
        System.out.println("  --selftest                 离线自检：拿真实数据集逐题断言路由（退出码 0 才算通过）");
        System.out.println("  --query \"…\"               对一条问题打路由结论（离线，只用规则）");
        System.out.println("  --query-file <path>        对多行问题打路由结论（# 开头与空行跳过）");
        System.out.println("  --classifier-live          规则未命中时调用真实分类器（**付费**，默认关闭）");
        System.out.println("  --json <path>              机读产物（LF、键排序、无时间戳）");
        System.out.println("  --golden <path>            覆盖 golden-set-v3.json 的路径");
    }
}
