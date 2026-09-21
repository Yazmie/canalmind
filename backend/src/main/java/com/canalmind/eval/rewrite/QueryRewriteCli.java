package com.canalmind.eval.rewrite;

import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.canalmind.structured.DeepSeekJsonInvoker;
import com.canalmind.structured.JsonChatInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ChatModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C-50 的 **C 臂**：用 LLM 把多轮里的省略问句改写成独立可检索问句（**付费**）。
 *
 * <p>⚠️ **花钱的纪律（本项目硬约定）**：
 * <ul>
 *   <li>**执行前先打印预估调用次数**，并在打印之后才真正发起调用；</li>
 *   <li>支持 {@code --limit} 做**小样本冒烟**（先 2 条验证链路，再跑全量）；</li>
 *   <li>**结果增量落盘且可续跑**：已完成的题不再重复调用——
 *       本项目踩过「产物只在最后写一次，跑到一半失败就全丢」的坑；</li>
 *   <li>**改写失败不得让分母变小**：取不到改写就**回退成原句**并**记为失败**，
 *       分母恒等于数据集条数（同 {@code JudgeRunCli} 的「分母必须来自数据集」）。</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 * java -cp ... com.canalmind.eval.rewrite.QueryRewriteCli \
 *      --dataset ../docs/评测报告/golden-rewrite-dev-v1.json \
 *      --out target/eval/rewrite/armC-rewrites.json \
 *      --queries-out target/eval/rewrite/armC-queries.txt \
 *      --dataset-out target/eval/rewrite/golden-rewrite-armC.json [--limit 2]
 * </pre>
 */
public final class QueryRewriteCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 改写句很短，512 足够；⛔ 别学重排那样给 8192（本处不需要长推理）。 */
    private static final int MAX_TOKENS = 512;
    private static final String LF = String.valueOf((char) 10);

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
        Path dataset = null;
        Path out = null;
        Path queriesOut = null;
        Path datasetOut = null;
        int limit = Integer.MAX_VALUE;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dataset" -> dataset = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--queries-out" -> queriesOut = Path.of(args[++i]);
                case "--dataset-out" -> datasetOut = Path.of(args[++i]);
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    System.err.println("  --dataset <rw.json> --out <rewrites.json> "
                            + "--queries-out <txt> --dataset-out <derived.json> [--limit N]");
                    return 2;
                }
            }
        }
        if (dataset == null || out == null) {
            System.err.println("必须给 --dataset 与 --out");
            return 2;
        }

        JsonNode root = MAPPER.readTree(Files.readString(dataset, StandardCharsets.UTF_8));
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode it : root.path("items")) {
            items.add(it);
            if (items.size() >= limit) {
                break;
            }
        }
        if (items.isEmpty()) {
            System.err.println("[FAIL] 数据集里没有条目：" + dataset);
            return 3;
        }

        // ---- 续跑：已完成的题不重复调用
        Map<String, String> done = new LinkedHashMap<>();
        Map<String, String> rawById = new LinkedHashMap<>();
        if (Files.exists(out)) {
            JsonNode prev = MAPPER.readTree(Files.readString(out, StandardCharsets.UTF_8));
            for (JsonNode r : prev.path("results")) {
                done.put(r.path("id").asText(), r.path("rewritten").asText(""));
                rawById.put(r.path("id").asText(), r.path("rawResponse").asText(""));
            }
            System.out.println("续跑：已有 " + done.size() + " 条结果，将跳过这些题");
        }
        List<JsonNode> pending = new ArrayList<>();
        for (JsonNode it : items) {
            if (!done.containsKey(it.path("id").asText())) {
                pending.add(it);
            }
        }

        System.out.println("=== C-50 的 C 臂：LLM 检索查询改写 ===");
        System.out.println("数据集：" + dataset);
        System.out.println("本次题数 " + items.size() + "（已完成 " + done.size()
                + "，待调用 " + pending.size() + "）");
        // ⚠️ 先报预估再动手
        System.out.println("预计付费调用：" + pending.size() + " 次");
        if (pending.isEmpty()) {
            System.out.println("无待办，直接汇总产物。");
        }

        boolean anyCall = !pending.isEmpty();
        ChatModel chatModel = null;
        JsonChatInvoker invoker = null;
        if (anyCall) {
            Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                    List.of(Path.of(".env"), Path.of("../.env")));
            chatModel = LlmRerankerFactory.chatModelFromEnvironment(env, MAX_TOKENS);
            invoker = new DeepSeekJsonInvoker(chatModel, MAX_TOKENS);
        }

        int failCount = 0;
        for (JsonNode it : items) {
            String id = it.path("id").asText();
            if (done.containsKey(id)) {
                continue;
            }
            String turn1 = it.path("turns").path(0).path("question").asText("");
            String turn2 = it.path("turns").path(1).path("question").asText("");
            String raw = "";
            String rewritten = null;
            try {
                raw = invoker.invoke(QueryRewritePrompt.systemPrompt(),
                        QueryRewritePrompt.userPrompt(turn1, turn2));
                JsonNode parsed = MAPPER.readTree(raw);
                rewritten = QueryRewritePrompt.parseRewritten(parsed);
            } catch (Exception ex) {
                System.err.println(id + " 调用/解析失败：" + ex.getMessage());
            }
            if (rewritten == null) {
                // ⛔ 取不到改写**不得**让分母变小：回退成原句并记为失败
                rewritten = turn2;
                failCount++;
                System.out.println(id + " ⚠️ 未取到改写 ⇒ 回退原句并计为失败");
            } else if (!rewritten.equals(turn2)) {
                System.out.println(id + " 改写：" + rewritten);
            } else {
                System.out.println(id + " （模型按约束原样返回）");
            }
            done.put(id, rewritten);
            rawById.put(id, raw);
            writeOut(out, root, done, rawById);   // 增量落盘
        }

        System.out.println("改写失败（回退原句）：" + failCount + " / " + items.size());

        // ---- 派生 C 臂查询文件 + 派生数据集（供 eval_retrieval.py 复用既有口径）
        if (queriesOut != null) {
            List<String> lines = new ArrayList<>();
            lines.add("# C-50 Query Rewrite 消融 C 臂（LLM 改写）：" + items.size() + " 条");
            for (JsonNode it : items) {
                lines.add(done.get(it.path("id").asText()));
            }
            writeLines(queriesOut, lines);
            System.out.println("查询文件已写出：" + queriesOut);
        }
        if (datasetOut != null) {
            List<ObjectNode> rows = new ArrayList<>();
            for (JsonNode it : items) {
                ObjectNode row = MAPPER.createObjectNode();
                row.put("id", it.path("id").asText() + "-C");
                row.put("question", done.get(it.path("id").asText()));
                row.put("questionType", it.path("questionType").asText(""));
                row.put("topicClass", it.path("topicClass").asText(""));
                row.put("split", it.path("split").asText("dev"));
                row.set("goldChunkIds", it.path("goldChunkIds"));
                if (it.has("acceptableChunkIds")) {
                    row.set("acceptableChunkIds", it.path("acceptableChunkIds"));
                }
                rows.add(row);
            }
            ObjectNode g = MAPPER.createObjectNode();
            g.put("datasetId", "canalmind-golden-rewrite-derived");
            g.put("datasetVersion", "v1-派生-C臂");
            g.put("layer", "L2-RW-C");
            g.put("derivedFrom", "docs/评测报告/golden-rewrite-dev-v1.json");
            g.put("derivedBy", "QueryRewriteCli");
            g.put("derivationRule", "question := LLM 改写句；取不到改写时回退 turns[1].question");
            g.put("warning", "⚠️ 派生文件，⛔ 不是数据集本身；⚠️ 回退条数见 rewrites 产物的 fallbackCount");
            var arr = MAPPER.createArrayNode();
            rows.forEach(arr::add);
            g.set("items", arr);
            writeJson(datasetOut, g);
            System.out.println("派生数据集已写出：" + datasetOut);
        }
        return failCount == items.size() ? 4 : 0;
    }

    private static void writeOut(Path out, JsonNode root, Map<String, String> done,
                                 Map<String, String> rawById) throws IOException {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("source", "docs/评测报告/golden-rewrite-dev-v1.json");
        o.put("promptVersion", "query-rewrite-v1");
        o.put("purpose", "C-50 的 C 臂：LLM 把多轮省略问句改写为独立可检索问句");
        int fallback = 0;
        for (Map.Entry<String, String> e : done.entrySet()) {
            if (e.getValue() != null && e.getValue().equals(rawFor(root, e.getKey()))) {
                fallback++;
            }
        }
        o.put("finishedCount", done.size());
        o.put("fallbackCount", fallback);
        var arr = MAPPER.createArrayNode();
        for (Map.Entry<String, String> e : done.entrySet()) {
            ObjectNode r = MAPPER.createObjectNode();
            r.put("id", e.getKey());
            r.put("rawQuestion", rawFor(root, e.getKey()));
            r.put("rewritten", e.getValue());
            r.put("rawResponse", rawById.getOrDefault(e.getKey(), ""));
            arr.add(r);
        }
        o.set("results", arr);
        writeJson(out, o);
    }

    private static String rawFor(JsonNode root, String id) {
        for (JsonNode it : root.path("items")) {
            if (it.path("id").asText().equals(id)) {
                return it.path("turns").path(1).path("question").asText("");
            }
        }
        return "";
    }

    /** 确定性写出：UTF-8 无 BOM + 仅 LF（AGENTS §7.2 的产物纪律）。 */
    private static void writeJson(Path p, JsonNode node) throws IOException {
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        String s = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                .replace("\r\n", LF).replace("\r", LF);
        Files.write(p, (s + LF).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeLines(Path p, List<String> lines) throws IOException {
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        Files.write(p, (String.join(LF, lines) + LF).getBytes(StandardCharsets.UTF_8));
    }
}
