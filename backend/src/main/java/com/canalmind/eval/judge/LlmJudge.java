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
 *   <li><b>空应答只重试一次，且只对"空"重试。</b>（2026-09-23，**C-62**，用户批准）
 *       判分端空应答长期存在且**随机游走**——实测同一题重跑可能变正常、也可能新冒出来
 *       （L3/L5/L6 重判前后：旧空→新正常 3 题、旧正常→新空 2 题，见 `DECISIONS.md` C-60）。
 *       故加"空 ⇒ 再试 1 次"，三条边界缺一不可：<br>
 *       ①**只对空白应答**触发；网络异常与"非空但解析失败"（含不是 JSON、缺字段）**一律不重试**
 *       ——它们不是空应答，重试它们等于悄悄改变判分成本；<br>
 *       ②**上限 1 次**，⛔ 不循环重试；<br>
 *       ③重试后仍空 ⇒ **如实记为"仍未取得有效评分"**，⛔ 不伪造分数，也⛔ 不许写成"点估计"。<br>
 *       调用次数落在 {@link JudgeScores#attempts()}（1/2），判分产物写 `judgeAttempts`：
 *       事后审计要能一眼看出"这题重试过、重试后是成功还是仍空"。</li>
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

    /**
     * 判分一次；**仅当裁判应答为空**时再试 **1** 次（上限 1，见类注释第 4 条）。
     *
     * <p>⛔ 非空应答**一次都不多调**——这是用户批准的范围（C-62）：
     * 只加"空应答重试"，⛔ 不改判分预算、⛔ 不改提示词、⛔ 不改模型档位。
     */
    @Override
    public JudgeScores judge(JudgeInput input) {
        String raw;
        try {
            raw = invoker.invoke(JudgePrompt.systemPrompt(), JudgePrompt.userPrompt(input));
        } catch (Exception ex) {
            // ⛔ 不抛出去：单条失败不能带走整批（见类注释第 1 条）。
            // ⚠️ 也**不重试**：这是"调用失败"，不是"空应答"（类注释第 4 条①）。
            return JudgeScores.unparsed("",
                    "裁判调用失败（" + ex.getClass().getSimpleName() + "：" + ex.getMessage() + "）");
        }
        if (!isBlank(raw)) {
            // 非空 ⇒ 恰好 1 次调用；即使解析失败也不重试（那不是空应答）
            return parse(raw);
        }

        // ── 空应答 ⇒ 恰好重试 1 次 ──
        String retryRaw;
        try {
            retryRaw = invoker.invoke(JudgePrompt.systemPrompt(), JudgePrompt.userPrompt(input));
        } catch (Exception ex) {
            return JudgeScores.unparsed("",
                    "裁判返回空应答，重试 1 次时调用失败（" + ex.getClass().getSimpleName()
                            + "：" + ex.getMessage() + "）", 2);
        }
        JudgeScores again = parse(retryRaw);
        if (again.parseOk()) {
            return new JudgeScores(again.faithfulness(), again.relevance(), again.completeness(),
                    again.needsHumanReview(), again.reasoning(), true, again.rawResponse(), 2);
        }
        // ⛔ 重试后仍空（或仍不可解析）：**如实记为"仍未取得有效评分"**，⛔ 不伪造分数
        return JudgeScores.unparsed(again.rawResponse(),
                isBlank(again.rawResponse())
                        ? "裁判返回空应答，重试 1 次后仍为空"
                        : "裁判返回空应答，重试 1 次后仍不可解析：" + again.reasoning(),
                2);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
        return new JudgeScores(f, r, c, human, reasoning, true, raw, 1);
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
