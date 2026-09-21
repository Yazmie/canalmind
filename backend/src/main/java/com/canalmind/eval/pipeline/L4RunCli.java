package com.canalmind.eval.pipeline;

import com.canalmind.chat.session.ContextAssembler;
import com.canalmind.chat.session.ContextMessage;
import com.canalmind.chat.session.ContextSummarizer;
import com.canalmind.chat.session.InMemorySessionStore;
import com.canalmind.chat.session.SessionTurn;
import com.canalmind.chat.session.TokenEstimator;
import com.canalmind.eval.harness.HarnessCli;
import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.canalmind.structured.ExtractionOutcome;
import com.canalmind.structured.StructuredQueryService;
import com.canalmind.structured.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L4「多轮参数继承」分层运行器。
 *
 * <p><b>它测什么</b>：数据集每题给一段多轮对话（`expected.dialogue[]`），每轮写明
 * `expectTool` 与 `expectArgs`。第二轮起是**省略式问句**（"那要是推到明年 1 月 5 号才过闸呢？"），
 * 系统必须从**上文**继承没被改动的参数、只改用户真正改掉的那几项。
 *
 * <p><b>怎么判（⛔ 零付费）</b>：L4 的判据是**工具 + 实参**，逐轮与 `expectArgs` 逐键比对，
 * 属规则判分、不调 Judge（见 C-39）。故本层的"判分"是纯本地计算。
 *
 * <p><b>上下文怎么给</b>：用任务 2 的 {@link ContextAssembler}（滑动窗口 + 压缩）装配，
 * 其中 {@code 【最近原始参数】} 块正是 **C-38** 的落点——它保证"上一轮的原始参数"
 * 永不被摘要吃掉，因为改写本轮参数**必须以它为唯一出处**。
 *
 * <p>⚠️ **压缩路径在本轮刻意不启用**：{@link ContextSummarizer} 的生产实现就是决策 **D10**
 * （知识库 `上下文工程` 第 172 行给了滑动窗口 / Compaction / 结构化笔记三条路线，尚未定），
 * 故这里注入一个**一旦被调用就抛异常**的桩，并把"是否触发过"记进产物。
 * 好处：①不替用户预先拍定 D10；②把"2 轮对话不会触发压缩"这句话**变成可核对的实测**，
 * 而不是一句说法。若真触发了，运行器会**响亮地失败**，⛔ 不会静默换成某个没定的摘要口径。
 */
public final class L4RunCli {

    private static final String LAYER = "L4";
    private static final String DATASET = "golden-set-v3.json";
    private static final String BUSINESS_TODAY = "2027-09-15";
    private static final String LF = String.valueOf((char) 10);
    private static final int REPORT_WIDTH = 72;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 压缩桩：**被调用即抛**（理由见类注释：D10 未决，不替用户拍定摘要口径）。 */
    static final class ForbiddenSummarizer implements ContextSummarizer {
        private int calls;

        @Override
        public String summarize(String previousSummary, List<SessionTurn> toCompact) {
            calls++;
            throw new IllegalStateException("L4 上压缩路径不应被触发（触发即意味着窗口预算不足）；"
                    + "D10（上下文超限处理）尚未决策，故本运行器不提供摘要口径。"
                    + "待压缩轮次=" + toCompact.size());
        }

        int calls() {
            return calls;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        int limit = Integer.MAX_VALUE;
        String jsonOut = null;
        boolean echoPrompt = false;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--json" -> jsonOut = args[++i];
                case "--echo-prompt" -> echoPrompt = true;
                default -> { }
            }
        }
        if (echoPrompt) {
            return echoPrompt(limit);
        }

        try {
            Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                    List.of(Path.of(".env"), Path.of("../.env")));
            JdbcTemplate jdbc = new JdbcTemplate(HarnessCli.dataSource(env));
            ChatModel chatModel = LlmRerankerFactory.chatModelFromEnvironment(env, 2048);
            QuestionPipeline pipeline = QuestionPipeline.create(jdbc, env, chatModel, BUSINESS_TODAY);
            StructuredQueryService service = pipeline.structured();

            Path dataset = HarnessCli.resolve("docs/评测报告/" + DATASET);
            JsonNode root = new ObjectMapper().readTree(Files.readString(dataset, StandardCharsets.UTF_8));
            List<JsonNode> items = new ArrayList<>();
            for (JsonNode it : root.path("items")) {
                if (LAYER.equals(it.path("layer").asText())) {
                    items.add(it);
                }
            }
            System.out.println("L4 多轮：数据集 " + items.size() + " 题" + (limit < items.size() ? "，本轮只跑前 " + limit + " 题" : ""));

            ObjectNode out = new ObjectMapper().createObjectNode();
            out.put("layer", LAYER);
            out.put("dataset", DATASET);
            out.put("businessToday", BUSINESS_TODAY);
            out.put("startedAt", LocalDateTime.now().format(TS).toString());
            out.put("note", "L4 判据=工具+实参（规则判分、零付费）；上下文由 ContextAssembler 装配");
            ArrayNode arr = out.putArray("results");

            // 续跑：已有作答的题整题跳过（与 LayerRunCli 同因：别让付费结果白丢）
            Map<String, JsonNode> previous = new LinkedHashMap<>();
            Path outPath = jsonOut == null ? null : Path.of(jsonOut);
            if (outPath != null && Files.exists(outPath)) {
                JsonNode old = new ObjectMapper().readTree(Files.readString(outPath, StandardCharsets.UTF_8));
                for (JsonNode n : old.path("results")) {
                    previous.put(n.path("taskId").asText(), n);
                }
                System.out.println("  续跑：已发现 " + previous.size() + " 题既有产物");
            }

            int skipped = 0;
            int done = 0;
            int modelCalls = 0;
            int itemPass = 0;
            int turnPass = 0;
            int turnTotal = 0;
            int compactTriggered = 0;
            Map<String, Integer> toolMismatch = new LinkedHashMap<>();
            List<String> failed = new ArrayList<>();

            for (JsonNode it : items) {
                String id = it.path("id").asText();
                if (done >= limit) {
                    break;
                }
                ObjectNode n = arr.addObject();
                n.put("taskId", id);
                n.put("split", it.path("split").asText());
                n.put("question", it.path("question").asText());
                n.put("rubric", it.path("rubric").asText());
                n.put("anchor", it.path("anchor").asText());

                if (previous.containsKey(id)) {
                    arr.remove(arr.size() - 1);
                    arr.add(previous.get(id));
                    skipped++;
                    System.out.println("  " + id + " → 续跑跳过（已有作答）");
                    continue;
                }

                // 每题一个独立会话：⛔ 不同题的上下文绝不能串（否则成了跨题污染）
                String sessionId = "l4-" + id;
                InMemorySessionStore store = new InMemorySessionStore();
                ForbiddenSummarizer summarizer = new ForbiddenSummarizer();
                // 预算给得宽松：L4 只有 2~3 轮，滑窗本就装得下；这里要的是"压缩不触发"的实测
                ContextAssembler assembler = new ContextAssembler(store, summarizer,
                        TokenEstimator.heuristic(), new ContextAssembler.ContextBudget(4000, 6),
                        "用户在追问上一轮的结构化查询，请据此改写本轮工具参数。");

                ArrayNode turns = n.putArray("turns");
                int idx = 0;
                int okTurns = 0;
                boolean allTurnsPass = true;
                for (JsonNode t : it.path("expected").path("dialogue")) {
                    idx++;
                    String expectTool = t.path("expectTool").asText();
                    JsonNode expectArgs = t.path("expectArgs");
                    String userQuestion = t.path("question").asText();

                    ContextAssembler.AssembledContext ctx = assembler.assemble(sessionId, userQuestion);
                    if (ctx.compacted()) {
                        compactTriggered++;
                    }
                    String prompt = flatten(ctx.messages(), userQuestion);

                    StructuredQueryService.AskOutcome ao = service.askDetailed(prompt);
                    modelCalls += 1 + Math.max(0, ao.extraction().rawResponses().size() - 1);

                    ToolCall call = ao.extraction().call();
                    String actualTool = call == null ? null : call.tool();
                    Map<String, String> actualArgs = call == null ? Map.of() : stringify(call.arguments());

                    // ── 规则判分：工具对不对 + 每个期望参数逐键比 ──
                    List<String> argFails = new ArrayList<>();
                    if (call == null) {
                        argFails.add("未抽出任何工具调用（状态=" + ao.extraction().status() + "）");
                    } else {
                        if (!expectTool.equals(actualTool)) {
                            argFails.add("工具错：期望 " + expectTool + "，实际 " + actualTool);
                            toolMismatch.merge(id, 1, Integer::sum);
                        }
                        var keys = expectArgs.fieldNames();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            String want = norm(expectArgs.path(k).asText());
                            String got = norm(actualArgs.get(k));
                            if (!want.equals(got)) {
                                argFails.add("参数 " + k + "：期望 " + want + "，实际 "
                                        + (actualArgs.containsKey(k) ? got : "（缺失）"));
                            }
                        }
                    }
                    boolean turnOk = argFails.isEmpty();
                    turnTotal++;
                    if (turnOk) {
                        turnPass++;
                        okTurns++;
                    } else {
                        allTurnsPass = false;
                    }

                    ObjectNode tn = turns.addObject();
                    tn.put("turn", idx);
                    tn.put("question", userQuestion);
                    tn.put("expectTool", expectTool);
                    tn.put("actualTool", actualTool == null ? "" : actualTool);
                    tn.put("extractionStatus", ao.extraction().status());
                    tn.put("degraded", ao.extraction().degraded());
                    tn.put("needsClarification", ao.extraction().needsClarification());
                    tn.put("toolStatus", ao.result().status());
                    tn.put("turnPass", turnOk);
                    ObjectNode ea = tn.putObject("expectArgs");
                    expectArgs.fields().forEachRemaining(e -> ea.put(e.getKey(), e.getValue().asText()));
                    ObjectNode aa = tn.putObject("actualArgs");
                    actualArgs.forEach(aa::put);
                    ArrayNode fs = tn.putArray("argFails");
                    argFails.forEach(fs::add);

                    // 把这一轮喂回会话：structuredParamsJson 就是"最近原始参数"块的数据源（C-38）
                    assembler.appendTurn(sessionId, new SessionTurn(idx, userQuestion,
                            ao.result().status(), "STRUCTURED", actualArgs.toString()));

                    System.out.println("  " + id + " 第" + idx + "轮 " + (turnOk ? "PASS" : "FAIL")
                            + (turnOk ? "" : "  " + String.join("；", argFails)));
                }

                n.put("itemPass", allTurnsPass);
                n.put("turnsPassed", okTurns);
                n.put("turnsTotal", turns.size());
                n.put("summarizerCalls", summarizer.calls());
                if (allTurnsPass) {
                    itemPass++;
                } else {
                    failed.add(id);
                }
                done++;

                n.put("finishedAt", LocalDateTime.now().format(TS).toString());
                // 边跑边写：进程被杀时已付费的作答必须留在盘上（与 LayerRunCli 同因）
                if (outPath != null) {
                    writeJson(outPath, out);
                }
            }

            out.put("judgedCount", turnTotal);
            out.put("turnPass", turnPass);
            out.put("turnRate", round4(turnTotal == 0 ? 0 : (double) turnPass / turnTotal));
            out.put("itemCount", itemPass + failed.size());
            out.put("itemPass", itemPass);
            out.put("itemRate", round4(itemPass + failed.size() == 0 ? 0
                    : (double) itemPass / (itemPass + failed.size())));
            out.put("modelCalls", modelCalls);
            out.put("compactionTriggered", compactTriggered);
            out.put("summarizerNeverCalled", compactTriggered == 0);
            out.put("skipped", skipped);
            ArrayNode fArr = out.putArray("failed");
            failed.forEach(fArr::add);
            out.put("bySplit", bySplit(arr));
            if (outPath != null) {
                writeJson(outPath, out);
            }

            System.out.println("-".repeat(REPORT_WIDTH));
            System.out.println("逐轮通过 " + turnPass + "/" + turnTotal + " = " + out.path("turnRate").asText());
            System.out.println("整题通过 " + itemPass + "/" + (itemPass + failed.size()) + " = "
                    + out.path("itemRate").asText());
            System.out.println("工具判错题数 " + toolMismatch.size() + "；压缩触发次数 " + compactTriggered);
            if (!failed.isEmpty()) {
                System.out.println("未通过：" + String.join(" ", failed));
            }
            System.out.println("付费模型调用（含重试）" + modelCalls + " 次");
            return 0;
        } catch (Exception e) {
            System.err.println("[FATAL] L4 运行失败：" + e);
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * ⛔ **零付费**地把"每轮真正喂给抽取器的那串文本"打出来。
     *
     * <p>存在的理由：本项目的判分/作答缺陷**反复**出现在"喂进去的输入其实是空的/缺的"
     * 这一层（见《评测报告》§6.1 第 6、10 条，两次都把链路缺陷报成了能力数字）。
     * 而 L4 的通过率**完全依赖**"第二轮提示里真的带着第一轮"——若上文没接上，
     * 抽取器仍可能靠猜凑对，于是**得到一个假 PASS**。
     * 故此处把上文的装配结果做成可肉眼核对的一步，⛔ 不靠"分数好看"来推断上下文接上了。
     */
    static int echoPrompt(int limit) {
        try {
            Path dataset = HarnessCli.resolve("docs/评测报告/" + DATASET);
            JsonNode root = new ObjectMapper().readTree(Files.readString(dataset, StandardCharsets.UTF_8));
            int shown = 0;
            for (JsonNode it : root.path("items")) {
                if (!LAYER.equals(it.path("layer").asText()) || shown >= limit) {
                    continue;
                }
                shown++;
                System.out.println("=".repeat(REPORT_WIDTH));
                System.out.println("【" + it.path("id").asText() + "】");
                InMemorySessionStore store = new InMemorySessionStore();
                ContextAssembler assembler = new ContextAssembler(store, new ForbiddenSummarizer(),
                        TokenEstimator.heuristic(), new ContextAssembler.ContextBudget(4000, 6),
                        "用户在追问上一轮的结构化查询，请据此改写本轮工具参数。");
                int idx = 0;
                for (JsonNode t : it.path("expected").path("dialogue")) {
                    idx++;
                    String q = t.path("question").asText();
                    ContextAssembler.AssembledContext ctx = assembler.assemble("l4-" + it.path("id").asText(), q);
                    System.out.println("── 第" + idx + "轮 装配出 " + ctx.messages().size()
                            + " 条消息，估算 " + ctx.estimatedTokens() + " token，压缩=" + ctx.compacted() + " ──");
                    System.out.println(flatten(ctx.messages(), q));
                    // 用真实的期望参数占位回填，模拟"上一轮确实抽到了这些参数"
                    assembler.appendTurn("l4-" + it.path("id").asText(), new SessionTurn(idx, q, "OK",
                            "STRUCTURED", t.path("expectArgs").toString()));
                }
            }
            System.out.println("=".repeat(REPORT_WIDTH));
            System.out.println("（零付费：本次未调用任何模型）");
            return 0;
        } catch (Exception e) {
            System.err.println("[FATAL] " + e);
            return 1;
        }
    }

    /** 把装配出来的消息拍平成一次抽取调用的输入（抽取器只接受单串问题）。 */
    static String flatten(List<ContextMessage> messages, String newQuestion) {
        StringBuilder sb = new StringBuilder();
        for (ContextMessage m : messages) {
            String c = m.content();
            if (ContextMessage.ROLE_SYSTEM.equals(m.role())
                    && !c.startsWith(ContextAssembler.RAW_PARAMS_HEADER)
                    && !c.startsWith(ContextAssembler.SUMMARY_HEADER)) {
                // ⛔⚠️ 这里只允许丢**基底系统提示**（抽取器自带的系统提示更专业）。
                // **必须保留**【最近原始参数】与【会话摘要】两块：
                // 前者是 C-38 明文规定的"改写本轮参数**唯一出处**"，后者代表更早轮次。
                // 实测踩到：早先这里写的是"跳过所有 system"⇒ 把原始参数块一并丢掉
                // ⇒ 第二轮拿不到上一轮的结构化参数，只能靠上一轮**问句的措辞**去猜，
                // 而它**照样能猜对**（L4-001/002 实测 4/4 全 PASS）。
                // 也就是说：**上下文没接上，分数却很好看**——正是本项目反复栽的
                // "把链路缺陷当成能力数字"那一类（《评测报告》§6.1 第 6、10 条）。
                continue;
            }
            sb.append(c).append(LF);
        }
        // 必须显式标出"哪一条才是本轮"：上文里已经有前面几轮的问句，不加标记抽取器无从分辨。
        sb.append("【本轮问题】").append(newQuestion);
        return sb.toString();
    }

    /** 值归一：去掉千分位、去尾零、日期原样（⛔ 只在"同一含义的不同写法"上归一，不改语义）。 */
    static String norm(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim().replace(",", "").replace("，", "");
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    /**
     * {@code ToolCall.arguments()} 的类型是 JsonNode（见其 record 定义），故按字段逐个取文本。
     *
     * <p>⚠️ **值为 {@code null} 的键一律按"缺失"处理**（不放进表里）——判分时"传了 null"
     * 与"没传"对系统是同一件事（都会走默认值），⛔ 不能让它们变成两种不同的判定结果。
     */
    static Map<String, String> stringify(JsonNode args) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args != null && args.isObject()) {
            args.fields().forEachRemaining(e -> {
                if (e.getValue() != null && !e.getValue().isNull()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return out;
    }

    static ObjectNode bySplit(ArrayNode results) {
        ObjectMapper m = new ObjectMapper();
        ObjectNode out = m.createObjectNode();
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (JsonNode n : results) {
            if (!n.has("itemPass")) {
                continue;
            }
            int[] c = agg.computeIfAbsent(n.path("split").asText(), k -> new int[2]);
            c[0]++;
            if (n.path("itemPass").asBoolean()) {
                c[1]++;
            }
        }
        agg.forEach((k, c) -> out.put(k, round4(c[0] == 0 ? 0 : (double) c[1] / c[0])));
        return out;
    }

    static void writeJson(Path p, JsonNode node) {
        try {
            ObjectMapper m = new ObjectMapper();
            var printer = new com.fasterxml.jackson.core.util.DefaultPrettyPrinter();
            printer.indentObjectsWith(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", LF));
            printer.indentArraysWith(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", LF));
            m.writer(printer).writeValue(p.toFile(), node);
        } catch (Exception e) {
            throw new IllegalStateException("写产物失败：" + p, e);
        }
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private L4RunCli() {
    }
}
