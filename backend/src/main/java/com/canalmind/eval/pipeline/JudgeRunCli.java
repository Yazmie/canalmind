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
import com.canalmind.eval.judge.JudgeInput;
import com.canalmind.eval.judge.JudgeScores;
import com.canalmind.eval.judge.LlmJudge;
import com.canalmind.rag.retrieve.LlmRerankerFactory;
import com.canalmind.structured.DeepSeekJsonInvoker;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 行为口径各层的**判分**运行器（L3 / L5 / L6）。
 *
 * <pre>
 *   --layer L3|L5|L6 --answers &lt;作答产物路径&gt; [--json &lt;判分产物&gt;] [--limit N]
 * </pre>
 *
 * <h2>⛔ 只对"措辞"用 Judge，"客观对错"一律用规则</h2>
 * 本类刻意**并存两套判据**，因为它们回答的是两个不同问题：
 * <table>
 *   <tr><th>判据</th><th>回答的问题</th><th>成本</th><th>可否被主观性污染</th></tr>
 *   <tr><td><b>规则</b>（金额是否出现、工具状态是否等于期望）</td>
 *       <td>"算对了吗"</td><td>0</td><td>❌ 不会——是字符串/枚举比对</td></tr>
 *   <tr><td><b>Judge</b>（三维打分）</td>
 *       <td>"说清楚了吗、依据引了吗"</td><td>1 次调用/题</td><td>⚠️ 会（见下）</td></tr>
 * </table>
 *
 * <p>若把"金额算得对不对"也交给 Judge，就会把**代码计算的客观结果**变成**模型的印象分**，
 * 于是同一份作答能被判成不同结果。所以金额与状态**只走规则**，
 * Judge 只判"是否说明了归入哪一类、是否引到条款、是否如实拒答"。
 *
 * <h2>⚠️ Judge 的已知局限（写进《评测报告》，不许隐去）</h2>
 * Judge 与被测系统用的是**同一个模型**（本项目只有一路中转，无跨厂商 Judge），
 * 故知识库《AI 应用评测体系》第 188–192 行点名的**自增强偏差无法排除**：
 * 模型可能偏好自己风格的答案。⇒ 结果里必须同时报**严格口径与均分口径**，
 * 并把 `needsHumanReview` 的题数单独列出，⛔ 不得只报一个更好看的数。
 */
public final class JudgeRunCli {

    private static final String LF = String.valueOf((char) 10);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 裁判的 token 预算。
     *
     * <p>⚠️ 首次判分用 1024：L3 **17/20**、L6 **7/15**、L5 **19/20** 解析失败，
     * 且失败项的 `rawResponse` **长度为 0**、原因串是「裁判返回空应答」——
     * 即**不是 JSON 格式错，而是根本没返回内容**。
     * 提到 2048 后单题复测**仍然为空**，故继续上调。
     * 这与作答侧那次空内容（`工程复盘/工程方法/验证工具本身也要被验证/空作答被当成成功作答.md`）
     * 是**同一个机制**：长输入下预算被推理吃掉、正文没剩额度。
     * ⛔ 关键区别：**本轮绝不能把这种空应答当成"答错"**，否则三维 0 分会伪装成模型的失败。
     */
    private static final int JUDGE_MAX_TOKENS = 4096;

    /**
     * 落进产物与库里的**判分模型名**（`judgeModel` / `judge_model`）。
     *
     * <p>⚠️ **它只是一个标签**：真正发出去的模型名由
     * {@link LlmRerankerFactory#chatModelFromEnvironment} 从 {@code .env} 的
     * {@code DEEPSEEK_CHAT_MODEL}（小档）取，两者**必须一致**，否则产物会谎报
     * "这批分是哪个模型打的"——而本项目的归因纪律正依赖这一列（换了模型而指标变了，
     * 与改了提示词而指标变了，必须分得开）。
     *
     * <p>2026-09-23（**C-61**）随小档改名：{@code deepseek-v4-flash} → {@code glm-5.3-flash}。
     * ⚠️ 改名**之前**的判分产物（`docs/评测报告/layer-L*-judged-*.json`）里仍写着旧名，
     * 那是**历史值，⛔ 不改写**；库里已落库的行同样保持旧值。
     */
    private static final String JUDGE_MODEL = "glm-5.3-flash";

    /** 严格口径：三维每维都要 ≥ 该值。 */
    private static final int STRICT_MIN_EACH = 4;

    /** 宽松口径：三维均分 ≥ 该值（仅作阈值敏感性对照，⛔ 不作主口径）。 */
    private static final double LENIENT_MIN_MEAN = 4.0;

    private JudgeRunCli() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception ex) {
            System.err.println("异常：" + ex.getMessage());
            ex.printStackTrace();
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        String layer = null;
        String answers = null;
        String jsonOut = null;
        int limit = Integer.MAX_VALUE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--layer" -> layer = args[++i];
                case "--answers" -> answers = args[++i];
                case "--json" -> jsonOut = HarnessCli.resolve(args[++i]).toString();
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("未知参数：" + args[i]);
                    return 2;
                }
            }
        }
        if (layer == null || answers == null) {
            System.err.println("⛔ 必须给出 --layer 与 --answers");
            return 2;
        }

        Path ap = HarnessCli.resolve(answers);
        if (!Files.exists(ap)) {
            System.err.println("⛔ 找不到作答产物：" + ap.toAbsolutePath());
            return 2;
        }
        JsonNode root = MAPPER.readTree(Files.readString(ap, StandardCharsets.UTF_8));
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            if (r.hasNonNull("answer") && !r.path("answer").asText("").isBlank()) {
                results.add(r);
            }
        }
        int total = results.size();
        if (total > limit) {
            results = results.subList(0, limit);
        }
        // ⚠️ 分母必须来自**数据集**而不是"成功作答的题数"：异常题也要进分母，
        //    否则失败会被悄悄从通过率里抹掉，报告就虚高了。
        JsonNode src = root;
        int datasetTotal = src.path("results").size();
        System.out.println("=== " + layer + " 判分（" + results.size() + " 题可判 / 数据集 "
                + datasetTotal + " 题，预计 " + results.size() + " 次付费调用）===");

        Map<String, String> env = LlmRerankerFactory.environmentWithDotEnv(
                List.of(Path.of(".env"), Path.of("../.env")));
        ChatModel chatModel = LlmRerankerFactory.chatModelFromEnvironment(env, JUDGE_MAX_TOKENS);
        JdbcTemplate jdbc = new JdbcTemplate(HarnessCli.dataSource(env));
        LlmJudge judge = new LlmJudge(new DeepSeekJsonInvoker(chatModel, JUDGE_MAX_TOKENS),
                JUDGE_MODEL);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("layer", layer);
        out.put("answersSource", ap.getFileName().toString());
        out.put("strictMinEach", STRICT_MIN_EACH);
        out.put("lenientMinMean", LENIENT_MIN_MEAN);
        out.put("judgeModel", JUDGE_MODEL);
        out.put("judgeSameVendorAsSystem", true);
        out.put("datasetTotal", datasetTotal);
        ArrayNode arr = out.putArray("judged");

        // ── 断点续跑：已**解析成功**的题不重复判 ──
        // ⚠️ 判分是**付费**的，而"解析失败"的题必须重判（它的 0 分不是真实分数）。
        //    只跳过 parseOk=true 的：⛔ 若把解析失败的也跳过，那 17 道 L3 的假 0 分就会被固化。
        Map<String, JsonNode> prevJudged = new LinkedHashMap<>();
        if (jsonOut != null && Files.exists(Path.of(jsonOut))) {
            JsonNode p = MAPPER.readTree(Files.readString(Path.of(jsonOut), StandardCharsets.UTF_8));
            for (JsonNode j : p.path("judged")) {
                if (j.path("parseOk").asBoolean(false)) {
                    prevJudged.put(j.path("taskId").asText(), j);
                }
            }
        }

        int strict = 0;
        int lenient = 0;
        int parseFail = 0;
        int humanReview = 0;
        int ruleChecked = 0;
        int rulePassed = 0;
        int skipped = 0;
        Map<String, int[]> bySplit = new TreeMap<>();

        for (JsonNode r : results) {
            String id = r.path("taskId").asText();
            String split = r.path("split").asText("dev");

            if (prevJudged.containsKey(id)) {
                JsonNode pj = prevJudged.get(id);
                arr.add(pj);
                boolean ps = pj.path("passStrict").asBoolean(false);
                if (ps) {
                    strict++;
                }
                if (pj.path("passLenient").asBoolean(false)) {
                    lenient++;
                }
                if (pj.path("needsHumanReview").asBoolean(false)) {
                    humanReview++;
                }
                if (pj.has("rulePass")) {
                    ruleChecked++;
                    if (pj.path("rulePass").asBoolean(false)) {
                        rulePassed++;
                    }
                }
                bySplit.computeIfAbsent(split, k -> new int[2]);
                bySplit.get(split)[0]++;
                if (ps) {
                    bySplit.get(split)[1]++;
                }
                skipped++;
                System.out.println("  " + id + " → 续跑跳过（已判分且解析成功）");
                continue;
            }

            String answer = r.path("answer").asText();
            JudgeInput in = buildInput(id, layer, r, contextOf(r, jdbc));
            JudgeScores s = judge.judge(in);

            ObjectNode n = arr.addObject();
            n.put("taskId", id);
            n.put("split", split);
            n.put("faithfulness", s.faithfulness());
            n.put("relevance", s.relevance());
            n.put("completeness", s.completeness());
            n.put("mean", round4(s.mean()));
            n.put("minScore", s.minScore());
            n.put("needsHumanReview", s.needsHumanReview());
            n.put("parseOk", s.parseOk());
            n.put("passStrict", s.passStrict(STRICT_MIN_EACH));
            n.put("passLenient", s.passLenient(LENIENT_MIN_MEAN));
            n.put("reasoning", s.reasoning());
            // ⚠️ 解析失败时**必须把原始应答留下来**：那是唯一能判断
            //    "是提示词问题 / 还是被 token 预算截断 / 还是模型没按格式"的证据。
            //    实测第一次判分 L3 有 17/20 解析失败、L6 有 7/15，当时产物里**没存原文**，
            //    于是只能靠猜；补上后同类问题下次一眼可判。⛔ 只存失败项，避免产物膨胀。
            if (!s.parseOk()) {
                n.put("rawResponse", truncate(s.rawResponse(), 2000));
            }

            // ── 规则判据（零成本、客观）──
            // ⚠️ 计数**必须读节点里那个布尔值**，⛔ 不许拿标签文本去比。
            //    实测踩到：原来写 `"PASS".equals(ruleVerdict)`，而 ruleCheck 返回的是
            //    `"PASS（状态一致）"` 这样的**带说明的标签** ⇒ 恒为 false ⇒
            //    逐题 `rulePass=True` 却汇总出 `0/15`。
            //    这与本类早先在 `verify_eval_record.sql` 上犯过的是**同一个错**：
            //    **拿人看的文字当机器判据**。判据只能是那个布尔值。
            String ruleVerdict = ruleCheck(layer, r, n);
            if (ruleVerdict != null) {
                ruleChecked++;
                if (n.path("rulePass").asBoolean(false)) {
                    rulePassed++;
                }
            }

            if (s.passStrict(STRICT_MIN_EACH)) {
                strict++;
            }
            if (s.passLenient(LENIENT_MIN_MEAN)) {
                lenient++;
            }
            if (!s.parseOk()) {
                parseFail++;
            }
            if (s.needsHumanReview()) {
                humanReview++;
            }
            bySplit.computeIfAbsent(split, k -> new int[2]);
            bySplit.get(split)[0]++;
            if (s.passStrict(STRICT_MIN_EACH)) {
                bySplit.get(split)[1]++;
            }
            System.out.println("  " + id + " 三维 " + s.faithfulness() + "/" + s.relevance() + "/"
                    + s.completeness() + " 均分 " + round4(s.mean())
                    + " 严格" + (s.passStrict(STRICT_MIN_EACH) ? "通过" : "未过")
                    + (ruleVerdict == null ? "" : "，规则判据 " + ruleVerdict));
            // 边判边写：**与 LayerRunCli 同因**——进程被杀时，已付费拿到的判分必须留在盘上。
            // 实测踩到：L5 判分跑到一半随会话暂停被杀，而产物只在**最后**写一次 ⇒
            // 那 20 次判分调用全部白花。⛔ 别把这一行挪到循环外。
            if (jsonOut != null) {
                writeJson(Path.of(jsonOut), out);
            }
        }

        out.put("judgedCount", results.size());
        out.put("strictPass", strict);
        out.put("lenientPass", lenient);
        out.put("strictRate", round4(results.isEmpty() ? 0 : (double) strict / results.size()));
        out.put("lenientRate", round4(results.isEmpty() ? 0 : (double) lenient / results.size()));
        out.put("parseFail", parseFail);
        out.put("skipped", skipped);
        out.put("needsHumanReview", humanReview);
        out.put("ruleChecked", ruleChecked);
        out.put("rulePassed", rulePassed);
        out.put("ruleRate", round4(ruleChecked == 0 ? 0 : (double) rulePassed / ruleChecked));
        ObjectNode splitNode = out.putObject("strictBySplit");
        bySplit.forEach((k, v) -> splitNode.put(k, v[0] == 0 ? 0.0 : round4((double) v[1] / v[0])));

        if (jsonOut != null) {
            writeJson(Path.of(jsonOut), out);
            System.out.println();
            System.out.println("判分产物：" + jsonOut);
        }

        System.out.println();
        System.out.println("严格口径通过（三维各≥" + STRICT_MIN_EACH + "）：" + strict + "/" + results.size()
                + " = " + round4(results.isEmpty() ? 0 : (double) strict / results.size()));
        System.out.println("宽松口径通过（均分≥" + LENIENT_MIN_MEAN + "）：" + lenient + "/" + results.size()
                + " = " + round4(results.isEmpty() ? 0 : (double) lenient / results.size()));
        System.out.println("规则判据通过（客观）：" + rulePassed + "/" + ruleChecked);
        System.out.println("解析失败 " + parseFail + " 题；标为需人工复核 " + humanReview + " 题");
        System.out.println("分批严格通过率：" + splitNode);
        // ⚠️ 解析失败**必须显式报警**：它等于"没判成"，⛔ 不能被当成低分混进通过率
        if (parseFail > 0) {
            System.out.println("[WARN] 有 " + parseFail + " 题 Judge 应答未解析成功——它们已按 0 分计入，"
                    + "必须先查明是提示词问题还是模型问题再引用本层数字");
        }
        System.out.println("[PASS] " + layer + " 判分完成（⚠️ Judge 与被测系统同模型，自增强偏差未排除）");
        return 0;
    }

    /**
     * 用数据集里的客观期望构造判分输入。
     *
     * <p>⚠️ `referenceAnswer` **不是**我编的：它逐字取自 `golden-set-v3.json` 的
     * `expected.answerMustContain` / `whyNoAnswer` / `expectStatus`，
     * 即"出题时就写死的评分要点"。这样 Judge 是**参照判分**（Reference-guided），
     * 而不是让模型凭印象打分。
     */
    static JudgeInput buildInput(String id, String layer, JsonNode r, String context) {
        String nl = LF;
        JsonNode exp = r.path("expected");
        List<String> oracle = new ArrayList<>();
        StringBuilder ref = new StringBuilder();

        switch (layer) {
            case "L3" -> {
                ref.append("本题应当**拒答**：不给出具体数值。原因：")
                        .append(exp.path("whyNoAnswer").asText("")).append(nl);
                if (exp.path("mustSayNoData").asBoolean(false)) {
                    ref.append("必须明确说明「没有该数据 / 知识库与数据库都不存在此值」。").append(nl);
                    oracle.add("mustSayNoData=true");
                }
            }
            case "L5" -> {
                for (JsonNode a : exp.path("answerMustContain")) {
                    ref.append("- ").append(a.asText()).append(nl);
                }
                String mustCite = exp.path("needRetrieval").path("mustCite").asText("");
                if (!mustCite.isBlank()) {
                    ref.append("语料依据必须引到：").append(mustCite).append(nl);
                }
            }
            case "L6" -> {
                String status = exp.path("expectStatus").asText("");
                ref.append("本题应当以 `").append(status).append("` 回应")
                        .append("（契约守卫，kind=").append(exp.path("guardKind").asText("-")).append("）。")
                        .append(nl);
                List<String> missing = new ArrayList<>();
                for (JsonNode m : exp.path("missingParams")) {
                    missing.add(m.asText());
                }
                if (!missing.isEmpty()) {
                    ref.append("必须向用户追问这些必填参数：").append(String.join("、", missing)).append(nl);
                }
                ref.append("⛔ 不得默认任一参数后直接给出金额。").append(nl);
                if (!status.isBlank()) {
                    oracle.add("expectStatus=" + status);
                }
                for (String m : missing) {
                    oracle.add("missingParam=" + m);
                }
            }
            default -> ref.append("（本层无参考答案要点）");
        }

        return new JudgeInput(id, layer, r.path("question").asText(), context,
                ref.toString().strip(), r.path("rubric").asText(""), oracle, r.path("answer").asText());
    }

    /**
     * 取判分用的上下文。
     *
     * <p>⚠️ **这里必须能在 `contextExcerpt` 缺失时回库重建**，理由是**止损**：
     * 首次判分时作答产物漏写了该字段 ⇒ 判分器拿到**空上下文** ⇒ 它把"有依据的引用"
     * 判成"语料中无依据"，L3/L5/L6 的 faithfulness 被系统性压低。
     * 若只能靠"重跑作答"来补，那就要**再付一遍作答的钱**；而 `contextChunkIds` 已在产物里，
     * **回库取正文是零成本的**，且与作答当时用的是同一段正文（同一 `chunk_id`、同一张表）。
     * ⛔ 注意：这只是**补上下文**，不改答案本身——所以不会把"作答缺陷"掩盖成"判分缺陷"。
     */
    static String contextOf(JsonNode r, JdbcTemplate jdbc) {
        String stored = r.path("contextExcerpt").asText("");
        if (!stored.isBlank()) {
            return stored;
        }
        JsonNode ids = r.path("contextChunkIds");
        if (ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode idNode : ids) {
            String id = idNode.asText();
            List<String> got = jdbc.queryForList("SELECT content FROM rag_chunk WHERE chunk_id = ?",
                    String.class, id);
            sb.append("[").append(n + 1).append("] ")
                    .append(got.isEmpty() || got.get(0) == null ? "" : got.get(0)).append(LF);
            n++;
        }
        return sb.toString();
    }

    /**
     * 规则判据：只在**数据集给了客观期望**时才做，否则返回 {@code null}（表示"本层无规则判据"）。
     *
     * <p>⛔ 不要为了"看起来更客观"而给 L3 强造一个规则判据——L3 的对错本身就是语义性的
     * （"有没有解释清楚为什么不给数"），硬做关键词匹配会把"换了种说法但答对了"判成错。
     */
    static String ruleCheck(String layer, JsonNode r, ObjectNode n) {
        JsonNode exp = r.path("expected");
        if ("L6".equals(layer)) {
            String expected = exp.path("expectStatus").asText("");
            String actual = r.path("toolStatus").asText("");
            n.put("ruleExpectedStatus", expected);
            n.put("ruleActualStatus", actual);
            boolean ok = !expected.isBlank() && expected.equals(actual);
            n.put("rulePass", ok);
            return ok ? "PASS（状态一致）" : "FAIL（期望 " + expected + "，实际 " + actual + "）";
        }
        if (!"L5".equals(layer)) {
            return null;
        }
        // L5：`answerMustContain` 的**首条**是金额（如「金额 2839.20 元」），这一条是客观的
        JsonNode must = exp.path("answerMustContain");
        if (must.isEmpty()) {
            return null;
        }
        String first = must.get(0).asText();
        String amount = firstAmount(first);
        if (amount == null) {
            return null;
        }
        String answer = r.path("answer").asText("");
        boolean ok = containsAmount(answer, amount);
        n.put("ruleExpectedAmount", amount);
        n.put("rulePass", ok);
        return ok ? "PASS（金额 " + amount + " 已出现）" : "FAIL（金额 " + amount + " 未出现）";
    }

    /** 从「金额 2839.20 元」里抽数字串。 */
    static String firstAmount(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d[\\d,]*(?:\\.\\d+)?)").matcher(s == null ? "" : s);
        return m.find() ? m.group(1).replace(",", "") : null;
    }

    /**
     * 金额比对：把千分位与末尾零归一后再比。
     *
     * <p>⚠️ 归一化是必要的：答案里写 `2,839.20`、`2839.20 元`、`2839.2` 都对，
     * 只做字面 `contains` 会把前两种判成错。
     */
    static boolean containsAmount(String answer, String amount) {
        if (answer == null || amount == null) {
            return false;
        }
        String bare = answer.replace(",", "").replace("，", "");
        if (bare.contains(amount)) {
            return true;
        }
        // 去末尾零后再比：2839.20 → 2839.2
        String trimmed = amount.contains(".") ? amount.replaceAll("0+$", "").replaceAll("\\.$", "") : amount;
        return !trimmed.equals(amount) && bare.contains(trimmed);
    }

    static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** 截断用于落盘的原始应答；⛔ 只截长度，不改内容（改内容就不是证据了）。 */
    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[已截断，" + s.length() + " 字符]";
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

    /** 供测试：暴露打分口径常量。 */
    static Map<String, Object> thresholds() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strictMinEach", STRICT_MIN_EACH);
        m.put("lenientMinMean", LENIENT_MIN_MEAN);
        return m;
    }
}
