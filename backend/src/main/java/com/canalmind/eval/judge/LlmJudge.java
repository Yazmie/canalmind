package com.canalmind.eval.judge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.canalmind.structured.JsonChatInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用大模型当裁判的实现（第 4 周第 3 项，C-39）。
 *
 * <h2>三条工程纪律</h2>
 * <ol>
 *   <li><b>单条失败不得打断整批。</b>55 条裁判要花钱、要花时间，因为第 37 条网络抖动
 *       就丢掉前面 36 条的结果，是最亏的一种失败。故所有异常都收敛成
 *       {@link JudgeScores#unparsed}，由调用方统计"裁判故障率"。</li>
 *   <li><b>原始应答一律保留。</b>排查时必须能分清"Judge 判错了"与"我没读懂 Judge"——
 *       这两类在只看分数时完全同形。</li>
 *   <li><b>0 分只表示"未取得有效评分"，不表示"答得很差"。</b>所以 {@link JudgeScores#valid()}
 *       是分开的一位，统计通过率时**分母必须剔除无效评分并单独报出条数**，
 *       ⛔ 不得把裁判故障算成被测系统答错。</li>
 * </ol>
 */
public final class LlmJudge implements Judge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 容忍模型把 JSON 包在 ```json 围栏里（实测中转模型偶发）。 */
    private static final Pattern FENCE =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final JsonChatInvoker invoker;
    private final String modelName;

    public LlmJudge(JsonChatInvoker invoker, String modelName) {
        this.invoker = invoker;
        this.modelName = modelName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public JudgeScores judge(JudgeInput input) {
        String raw;
        try {
            raw = invoker.invoke(JudgePrompt.systemPrompt(), JudgePrompt.userPrompt(input));
        } catch (Exception ex) {
            // ⛔ 不抛出去：单条失败不能带走整批（见类注释第 1 条）
            return JudgeScores.unparsed("",
                    "裁判调用失败（" + ex.getClass().getSimpleName() + "：" + ex.getMessage() + "）");
        }
        return parse(raw);
    }

    /**
     * 解析 Judge 应答。**容错范围明确且有限**：允许围栏包裹、允许字段顺序任意、允许多余字段；
     * ⛔ 不允许猜分数——缺字段或超出 1–5 一律判为"未取得有效评分"。
     */
    static JudgeScores parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return JudgeScores.unparsed(raw, "裁判返回空应答");
        }
        String body = stripFence(raw);
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception ex) {
            return JudgeScores.unparsed(raw, "裁判应答不是合法 JSON：" + ex.getMessage());
        }
        if (node == null || !node.isObject()) {
            return JudgeScores.unparsed(raw, "裁判应答不是 JSON 对象");
        }
        Integer f = score(node, "faithfulness");
        Integer r = score(node, "relevance");
        Integer c = score(node, "completeness");
        if (f == null || r == null || c == null) {
            return JudgeScores.unparsed(raw, "裁判应答缺少三维分数或分数不在 1-5 内：" + node);
        }
        boolean human = node.path("needs_human_review").asBoolean(false);
        String reasoning = node.path("reasoning").asText("");
        return new JudgeScores(f, r, c, human, reasoning, true, raw);
    }

    private static String stripFence(String raw) {
        Matcher m = FENCE.matcher(raw);
        return m.find() ? m.group(1).strip() : raw.strip();
    }

    /** 取一个 1–5 的整数分；取不到或越界返回 {@code null}（⛔ 不夹取、不默认）。 */
    private static Integer score(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isInt() && !v.isLong()) {
            // 也接受 "4" 这种字符串形式（中转模型偶发），但必须是纯整数
            String t = v.asText("");
            if (!t.matches("\\d+")) {
                return null;
            }
            int parsed = Integer.parseInt(t);
            return parsed >= 1 && parsed <= 5 ? parsed : null;
        }
        int parsed = v.asInt();
        return parsed >= 1 && parsed <= 5 ? parsed : null;
    }
}
