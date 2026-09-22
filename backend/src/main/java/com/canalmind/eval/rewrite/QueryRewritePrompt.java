package com.canalmind.eval.rewrite;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 航运版**检索查询改写器**的提示词与响应解析（C-50 的 C 臂）。
 *
 * <p>**为什么需要它**：多轮追问里最新一句常含**指代**（「这两者」「它」）与**省略**
 * （承前省掉货类 / 费率版本 / 吨位 / 单证类型）。这类问句**脱离对话历史几乎无法检索**，
 * 故在检索前要补全成一个**能独立检索**的问句——这就是决策点 D8。
 *
 * <p>⚠️ **本类只做"造提示词 + 解析响应"**，把真正的模型调用留给调用方，
 * 于是绝大多数测试**零付费**（不碰网络也能验证提示词与解析契约）。
 *
 * <p>⚠️ **为什么要求 JSON 而不是让模型直接回一句改写文本**：
 * 直接回文本就得从**人话**里抠出改写句——本项目已因「拿给人看的标签当机器判据」
 * 两次得出错误的通过结论（《评测报告》§6.1 第 3、8 条）。要求结构化输出后，
 * 「模型没给出改写」与「模型给了一段解释」是**两种可区分的失败**，⛔ 不会被当成正常结果。
 */
public final class QueryRewritePrompt {

    /**
     * 改写提示词的**版本标识**（写进产物与 Trace）。
     *
     * <p>为什么必须有它：改写器的提示词是 C-50 消融的<b>自变量之一</b>——
     * 同一批题换一版提示词就是另一次实验。产物里的 {@code promptVersion} 与
     * Trace 的高基数 {@code ai.config.version} 都取这一个常量，
     * ⛔ 不许两处各写一遍字面量（两处各写一遍必然分叉，而分叉的表现是
     * "产物说 A 版、Trace 说 B 版"——不会报错）。
     */
    public static final String PROMPT_VERSION = "query-rewrite-v1";

    /** ⚠️ 真实换行用计算字符，⛔ 不写转义字面量（AGENTS §7.2 的换行纪律）。 */
    private static final String LF = String.valueOf((char) 10);

    private QueryRewritePrompt() {
    }

    /**
     * 系统提示词：航空版改写器的**全部约束**都在这里。
     *
     * <p>⚠️ 三条硬约束各有明确理由：
     * <ul>
     *   <li>**必须把上文已确认的实体显式写进改写句**——不写就等于没改写（本消融 A/B 两臂
     *       的实测差就来自"实体有没有带上"）；</li>
     *   <li>**⛔ 不得引入上文没有的信息**——改写器一旦开始"猜"，就会把 2026 版猜成 2027 版；
     *       本项目的 RW-001/RW-002 正是**只差版本**的对照对，猜版本会直接错到另一条款上；</li>
     *   <li>**拿不准时原样返回**——⛔ 宁可退化成"没改写"，也不要"改错了"，
     *       因为改错会让本来命中的题变成不命中（消融必须能量化这种退化）。</li>
     * </ul>
     */
    public static String systemPrompt() {
        return "你是平陆运河航运物流业务助手的「检索查询改写器」。" + LF
                + "用户在多轮对话里的最新一句常含指代（如「它」「这两者」「这种」）与省略" + LF
                + "（承前省掉货类、费率版本、吨位、船型、单证类型等）。" + LF
                + "你的**唯一任务**：把最新一句补全成一个**脱离对话历史也能独立检索**的问句。" + LF + LF
                + "硬性约束：" + LF
                + "1. 必须把上文中已经确认的限定信息**显式写进**改写句（货类、费率版本、吨位、船型、" + LF
                + "   单证类型、业务类型、术语全称等）；" + LF
                + "2. ⛔ 不得引入上文**没有出现**的信息，⛔ 不得凭空猜测或补默认值；" + LF
                + "3. ⛔ 不得回答问题、不得解释、不得给建议；" + LF
                + "4. 专有名词（如 GT、DWT、PLYH-GZ-2026-001、「重点保供物资」）保持原样；" + LF
                + "5. 若无法确定指代或省略的对象，就**原样返回原句**（宁可不改写，也不要改错）。" + LF + LF
                + "只输出一个 JSON 对象，不要任何多余文字：" + LF
                + "{\"rewritten\": \"改写后的独立问句\"}";
    }

    /** 用户提示词：给出对话上文与待改写的最新一句。 */
    public static String userPrompt(String priorTurn, String currentQuestion) {
        return "【对话上文（上一轮用户说的）】" + LF
                + safe(priorTurn) + LF + LF
                + "【用户最新一句（待改写）】" + LF
                + safe(currentQuestion) + LF + LF
                + "请输出改写后的独立问句。";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 从一个已解析的响应里取改写句；取不到就返回 {@code null}。
     *
     * <p>⚠️ **空改写必须与"改写等于原句"区分对待**：前者是**失败**（要计入失败率），
     * 后者是**合法的保守退化**（模型按约束 5 原样返回）。⛔ 不得把两者混成一个"没改写"。
     *
     * @return 改写句；缺失、空白或字段类型不对时返回 {@code null}
     */
    public static String parseRewritten(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode v = root.path("rewritten");
        if (!v.isTextual()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
