package com.canalmind.eval.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import com.canalmind.eval.harness.HarnessCli;
import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 行为口径各层（L3 / L4 / L5 / L6）的作答运行器（第 4 周第 4 项补全）。
 *
 * <pre>
 *   --smoke                 ⚠️ **先跑这个**：只跑 1 道题走完整链路，证明链路是通的（1~2 次付费调用）
 *   --layer L3|L5|L6        按层跑作答，产出"作答结果"JSON（每题 1 次付费调用）
 *       [--limit N]         只跑前 N 题（试跑用）
 *       [--json &lt;path&gt;]     机读产物（LF、键排序、无时间戳）
 *   --route-only --layer X  ⛔ 零付费：只跑路由，不调模型，用于核对分层与拆分
 * </pre>
 *
 * <h2>为什么要先 {@code --smoke}</h2>
 * 本项目有一条实测教训（`工程复盘/工程方法/用合成输入先空跑评测链路.md`）：
 * **"能跑通"和"跑对了"是两件事**。付费批次必须先证明"链路能通"，
 * 否则会把"装配错误"记成"模型答错"，且在花钱之后才发现。
 * ⚠️ 同样地，{@code --route-only} 让"路由拆得对不对"在**不花钱**的前提下先被核一遍。
 *
 * <h2>⛔ 本工具不判分</h2>
 * 它只产出作答。判分是**另一趟**（{@link com.canalmind.eval.judge.LlmJudge} 或规则判分），
 * 这样"同一份作答换判分器复算"不必重新花钱。
 */
public final class LayerRunCli {

    private static final String LF = String.valueOf((char) 10);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUSINESS_TODAY = "2027-09-15";
    /**
     * 作答的 token 预算。
     *
     * <p>⚠️ 第一次批量用 1024，20 道 L5 复合题里**有 7 道返回空内容**。
     * 复合题的提示词最长（数据表 + 5 条片段 + 事实），预算很可能被**推理**吃掉、
     * 正文没剩额度，而接口此时返回的是"内容为空但非错误"的响应。
     * ⇒ 提到 2048，并让 {@link AnswerComposer} 对空内容**重试一次再抛**（⛔ 不静默接受空串）。
     */
    private static final int ANSWER_MAX_TOKENS = 2048;

    private LayerRunCli() {
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

    static int run(String[] args) throws Exception {
        String layer = null;
        String jsonOut = null;
        int limit = Integer.MAX_VALUE;
        boolean smoke = false;
        boolean routeOnly = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--smoke" -> smoke = true;
                case "--route-only" -> routeOnly = true;
                case "--layer" -> layer = args[++i];
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--json" -> jsonOut = HarnessCli.resolve(args[++i]).toString();
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

        Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                List.of(Path.of(".env"), Path.of("../.env")));
        JdbcTemplate jdbc = new JdbcTemplate(HarnessCli.dataSource(env));

        if (smoke) {
            return smoke(jdbc, env);
        }
        if (layer == null) {
            System.err.println("⛔ 必须给出 --layer（或 --smoke）");
            return 2;
        }
        return runLayer(jdbc, env, layer, limit, routeOnly, jsonOut);
    }

    // ------------------------------------------------------------------ 数据集
    record Item(String id, String layer, String split, String question, String rubric,
                String anchor, JsonNode expected) {
    }

    static List<Item> loadLayer(String layer, int limit) throws IOException {
        Path p = locate("golden-set-v3.json");
        JsonNode root = MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8));
        List<Item> out = new ArrayList<>();
        for (JsonNode it : root.path("items")) {
            if (!layer.equals(it.path("layer").asText())) {
                continue;
            }
            out.add(new Item(it.path("id").asText(), layer, it.path("split").asText("dev"),
                    it.path("question").asText(), it.path("rubric").asText(""),
                    it.path("anchor").asText(""), it.path("expected")));
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("数据集里没有 " + layer + " 的题目（层名写错了？）");
        }
        return out;
    }

    /**
     * 定位数据集：先按当前目录，再按上一级。
     * ⚠️ 输出路径也走同一套锚定（见 {@link HarnessCli#resolve}）——否则产物会落进另一棵树。
     */
    static Path locate(String name) {
        Path p = HarnessCli.resolve("docs/评测报告/" + name);
        if (!Files.exists(p)) {
            throw new IllegalStateException("找不到数据集：" + p.toAbsolutePath());
        }
        return p;
    }

    // ------------------------------------------------------------------ smoke
    /**
     * 用**真实数据集的第一道 L3 题**走完整链路。
     *
     * <p>⚠️ 为什么不用合成问题：合成的问句可能在路由层就走不到我们要验的分支，
     * 于是"链路通了"是假的。用数据集真题，验的是**真实路径**。
     */
    static int smoke(JdbcTemplate jdbc, Map<String, String> env) throws Exception {
        System.out.println("=== 链路冒烟（真实数据集 L3 首题，预计 1~2 次付费调用）===");
        List<Item> items = loadLayer("L3", 1);
        Item item = items.get(0);
        System.out.println("题号：" + item.id() + "（" + item.split() + "）");
        System.out.println("问句：" + item.question());

        ChatModel chatModel = LlmRerankerFactory.chatModelFromEnvironment(env, ANSWER_MAX_TOKENS);
        QuestionPipeline pipeline = QuestionPipeline.create(jdbc, env, chatModel, BUSINESS_TODAY);

        long t0 = System.currentTimeMillis();
        QuestionPipeline.Answer a = pipeline.run(item.question());
        long ms = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("通道        ：" + a.channel());
        System.out.println("实际依据    ：" + a.effectiveChannel()
                + (a.retrievalFallback() ? "（← TOOL_NONE 回退到检索）" : ""));
        System.out.println("规则        ：" + a.route().ruleId());
        System.out.println("路由判据    ：" + a.route().reason());
        System.out.println("工具状态    ：" + (a.toolStatus() == null ? "-" : a.toolStatus()));
        System.out.println("检索片段    ：" + a.contextChunkIds().size() + " 条 " + a.contextChunkIds());
        System.out.println("模型调用    ：" + a.modelCalls() + " 次");
        System.out.println("耗时        ：" + ms + "ms");
        System.out.println();
        System.out.println("---- 作答（" + a.text().length() + " 字）----");
        System.out.println(a.text());

        boolean ok = !a.text().isBlank();
        System.out.println();
        if (!ok) {
            System.out.println("[FAIL] 作答为空——链路没通，⛔ 先别开始批量");
            return 3;
        }
        System.out.println("[PASS] 链路已跑通；⛔ 本次只验「能通」，未验「答对」（判分是另一趟）");
        return 0;
    }

    // ------------------------------------------------------------------ 按层
    static int runLayer(JdbcTemplate jdbc, Map<String, String> env, String layer, int limit,
                        boolean routeOnly, String jsonOut) throws Exception {
        List<Item> items = loadLayer(layer, limit);
        System.out.println("=== " + layer + " 作答运行（" + items.size() + " 题"
                + (routeOnly ? "，⛔ route-only：零付费调用" : "，预计 " + items.size() + " 次付费调用")
                + "）===");

        ChatModel chatModel = routeOnly ? null
                : LlmRerankerFactory.chatModelFromEnvironment(env, ANSWER_MAX_TOKENS);
        QuestionPipeline pipeline = routeOnly ? null
                : QuestionPipeline.create(jdbc, env, chatModel, BUSINESS_TODAY);
        // route-only 也要真路由，故单独装一个路由器（零付费：rulesOnly）
        var router = com.canalmind.intent.IntentRouter.rulesOnly();

        ObjectNode root = MAPPER.createObjectNode();
        root.put("layer", layer);
        root.put("routeOnly", routeOnly);
        root.put("dataset", "golden-set-v3.json");
        root.put("businessToday", BUSINESS_TODAY);
        ArrayNode arr = root.putArray("results");
        Map<String, Integer> byChannel = new TreeMap<>();
        int failed = 0;
        int skipped = 0;

        // ── 断点续跑：把上一次已写出的作答读回来，跳过已完成的题 ──
        // ⚠️ 这条不是"优化"，是**止损**：作答是**付费**产物，而进程可能被超时杀掉。
        //    实测踩过——20 题跑到第 15 题被杀，因为产物只在**最后**写一次，
        //    于是那 15 题约 30 次调用**白花了**。所以：边跑边写 + 重跑能续。
        Map<String, JsonNode> previous = new LinkedHashMap<>();
        if (jsonOut != null && Files.exists(Path.of(jsonOut))) {
            JsonNode prev = MAPPER.readTree(Files.readString(Path.of(jsonOut), StandardCharsets.UTF_8));
            for (JsonNode r : prev.path("results")) {
                if (r.hasNonNull("answer") && !r.path("answer").asText("").isBlank()) {
                    previous.put(r.path("taskId").asText(), r);
                }
            }
        }

        for (Item it : items) {
            if (previous.containsKey(it.id())) {
                arr.add(previous.get(it.id()));
                byChannel.merge(previous.get(it.id()).path("effectiveChannel")
                        .asText(previous.get(it.id()).path("channel").asText("?")), 1, Integer::sum);
                skipped++;
                System.out.println("  " + it.id() + " → 续跑跳过（已有作答）");
                continue;
            }
            ObjectNode n = arr.addObject();
            n.put("taskId", it.id());
            n.put("split", it.split());
            n.put("question", it.question());
            n.put("rubric", it.rubric());
            n.put("anchor", it.anchor());
            n.set("expected", it.expected());
            if (routeOnly) {
                var d = router.route(it.question());
                n.put("channel", d.channel());
                n.put("ruleId", d.ruleId());
                n.put("routeReason", d.reason());
                byChannel.merge(d.channel(), 1, Integer::sum);
                System.out.println("  " + it.id() + " → " + d.channel() + "（" + d.ruleId() + "）");
            } else {
                try {
                    QuestionPipeline.Answer a = pipeline.run(it.question());
                    n.put("channel", a.channel());
                    n.put("effectiveChannel", a.effectiveChannel());
                    n.put("retrievalFallback", a.retrievalFallback());
                    n.put("ruleId", a.route().ruleId());
                    n.put("routeReason", a.route().reason());
                    n.put("answer", a.text());
                    n.put("toolStatus", a.toolStatus());
                    if (a.tool() != null) {
                        n.put("tool", a.tool().tool());
                        if (a.tool().degradeKind() != null) {
                            n.put("degradeKind", a.tool().degradeKind());
                        }
                    }
                    ArrayNode ids = n.putArray("contextChunkIds");
                    a.contextChunkIds().forEach(ids::add);
                    n.put("modelCalls", a.modelCalls());
                    byChannel.merge(a.effectiveChannel(), 1, Integer::sum);
                    System.out.println("  " + it.id() + " → 路由 " + a.channel()
                            + " / 实际 " + a.effectiveChannel()
                            + (a.retrievalFallback() ? "（回退）" : "")
                            + "，作答 " + a.text().length() + " 字"
                            + (a.toolStatus() == null ? "" : "，工具 " + a.toolStatus()));
                } catch (RuntimeException ex) {
                    // ⛔ 不静默跳过：失败必须留在产物里，否则分母会悄悄变小
                    failed++;
                    n.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    System.out.println("  " + it.id() + " → ❌ " + n.get("error").asText());
                }
            }
            // 边跑边写：进程被超时杀掉时，已付费拿到的作答必须留在盘上（见上面的止损说明）。
            // ⛔ 不要挪到循环外"只写一次"——那正是上一次白花 30 次调用的原因。
            if (jsonOut != null && !routeOnly) {
                writeJson(Path.of(jsonOut), root);
            }
        }

        ObjectNode dist = root.putObject("channelDistribution");
        byChannel.forEach(dist::put);
        root.put("failed", failed);
        root.put("skipped", skipped);
        if (jsonOut != null) {
            writeJson(Path.of(jsonOut), root);
            System.out.println();
            System.out.println("机读产物：" + jsonOut);
        }
        System.out.println();
        System.out.println("通道分布（按实际依据通道）：" + byChannel);
        if (skipped > 0) {
            System.out.println("续跑跳过 " + skipped + " 题（本次未重复付费）");
        }
        if (failed > 0) {
            System.out.println("[FAIL] " + failed + " 题抛异常——先查原因，⛔ 别把异常记成答错");
            return 3;
        }
        System.out.println("[PASS] " + items.size() + " 题作答完成"
                + (routeOnly ? "（未调用模型）" : ""));
        return 0;
    }

    private static void writeJson(Path out, JsonNode node) throws IOException {
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
        System.out.println("行为口径各层作答运行器");
        System.out.println("  --smoke                     只跑 1 题走完整链路（⛔ 先跑这个）");
        System.out.println("  --layer L3|L5|L6            按层作答");
        System.out.println("  --route-only                零付费：只跑路由");
        System.out.println("  --limit N / --json <path>");
    }
}
