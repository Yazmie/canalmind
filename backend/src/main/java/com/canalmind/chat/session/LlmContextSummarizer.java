package com.canalmind.chat.session;

import com.canalmind.structured.JsonChatInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@link ContextSummarizer} 的**生产实现**（决策 **C-53**，原 D10 收口）：
 * 用模型把更早的若干轮折成一段摘要。
 *
 * <h2>它守住的三条约定（都来自接口注释，⛔ 不是我自己加的）</h2>
 * <ol>
 *   <li><b>必须并入上一版摘要</b>：否则每压缩一次就会丢掉比这次待折叠轮次**更早**的内容——
 *       历史会被一段段"合法地"侵蚀掉，而每次都只丢一点点，很难被发现。</li>
 *   <li><b>⛔ 失败必须抛给调用方，不得就地兜底</b>：摘要是一次模型调用，会超时/限流/返回空。
 *       本类**不吞异常、不返回空串**——失败时由 {@link ContextAssembler} 保留全部原文并把
 *       失败写进 {@code note}。⛔「摘要失败就当作没有更早轮次」是一次**静默丢历史**
 *       （同族教训：`docs/工程复盘/工程方法/桩替身用尽后的默认值会伪装失败原因.md`）。</li>
 *   <li><b>空摘要一律当失败</b>：折叠之后原文就会被丢掉，返回空摘要等于把历史折没了。
 *       ⚠️ 故本类把"模型没给出摘要"与"模型给了一段解释"都判为**失败并抛异常**，
 *       而不是返回一个空串让下游去猜。</li>
 * </ol>
 *
 * <h2>⚠️ 为什么要求 JSON 而不是让模型直接回一段摘要文本</h2>
 * 直接回文本就得从**人话**里抠摘要，于是"模型拒答/回了一段解释"会被当成一段合法摘要存下来。
 * 本项目已两次因「拿给人看的标签当机器判据」得出错误结论（《评测报告》§6.1）。
 * 要求结构化输出后，「没给摘要」是一种**可区分的失败**。
 */
public final class LlmContextSummarizer implements ContextSummarizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LF = String.valueOf((char) 10);

    private final JsonChatInvoker invoker;

    public LlmContextSummarizer(JsonChatInvoker invoker) {
        this.invoker = java.util.Objects.requireNonNull(invoker, "invoker");
    }

    /**
     * 系统提示词：把"压缩"和"总结"区分开，并钉死**不许引入新信息**。
     *
     * <p>⚠️ 特别要求保留**结构化参数原文**：{@code SessionTurn.structuredParamsJson} 是
     * C-38③ 点名"唯一不许被摘要吃掉"的东西（数据集里 {@code L4-008}/{@code L4-019}/{@code L4-023}
     * 的第二轮就靠它）。摘要把它改写成自然语言，后续轮次就再也拿不到可执行的参数了。
     */
    public static String systemPrompt() {
        return "你是平陆运河航运物流业务助手的多轮会话「上下文压缩器」。" + LF
                + "把**更早的若干轮**对话压缩成一段简短摘要，供后续轮次参考。" + LF + LF
                + "硬性约束：" + LF
                + "1. **只压缩已出现的事实**，⛔ 不得引入任何新信息、不得推测、不得补全；" + LF
                + "2. **必须保留所有已确认的实体与数值**：货类、吨位、船型、费率版本、日期、" + LF
                + "   单证类型、条款编号、金额等，⛔ 一个都不许省；" + LF
                + "3. **结构化参数必须逐字保留**：凡是标注了「原始参数」的行，⛔ 必须**原样抄下来**，" + LF
                + "   ⛔ 不得改写成自然语言、不得省略字段（后续轮次要拿它去执行）；" + LF
                + "4. 若给了「上一版摘要」，必须**把它并入**新摘要（⛔ 不得只写这一批轮次）；" + LF
                + "5. ⛔ 不要回答问题、不要给建议、不要写开场白；" + LF
                + "6. 摘要控制在 300 字以内，用陈述句罗列事实。" + LF + LF
                + "只输出一个 JSON 对象，不要任何多余文字：" + LF
                + "{\"summary\": \"摘要正文\"}";
    }

    /** 用户提示词：上一版摘要 + 待折叠轮次（含结构化参数原文）。 */
    public static String userPrompt(String previousSummary, List<SessionTurn> toCompact) {
        StringBuilder sb = new StringBuilder();
        sb.append("【上一版摘要】").append(LF)
                .append(previousSummary == null || previousSummary.isBlank()
                        ? "（无，这是第一次压缩）" : previousSummary).append(LF).append(LF);
        sb.append("【本次待折叠轮次（按时间升序）】").append(LF);
        for (SessionTurn t : toCompact) {
            sb.append("第 ").append(t.index()).append(" 轮").append(LF)
                    .append("用户问：").append(t.question()).append(LF)
                    .append("系统答：").append(t.answer() == null || t.answer().isBlank()
                            ? "（本轮无应答）" : t.answer()).append(LF);
            if (t.hasStructuredParams()) {
                // ⚠️ "原始参数"这四个字必须与系统提示词第 3 条对得上，否则那条约束就落空了
                sb.append("原始参数（⛔ 必须原样保留）：").append(t.structuredParamsJson()).append(LF);
            }
            sb.append(LF);
        }
        sb.append("请输出压缩后的摘要。");
        return sb.toString();
    }

    @Override
    public String summarize(String previousSummary, List<SessionTurn> toCompact) throws Exception {
        // ⚠️ 不 try/catch：模型失败就是要让调用方看见（接口注释第 2 条）
        String raw = invoker.invoke(systemPrompt(), userPrompt(previousSummary, toCompact));
        String summary = parseSummary(raw);
        if (summary == null) {
            throw new IllegalStateException(
                    "压缩器没有返回摘要（原文前 200 字符：" + preview(raw) + "）。"
                            + "⛔ 这不是「没有更早轮次」，是摘要失败：调用方应保留全部原文并记入 note，"
                            + "⛔ 不得当成压缩成功。");
        }
        return summary;
    }

    /**
     * 从响应里取摘要；取不到返回 {@code null}。
     *
     * <p>缺失、空白、字段类型不对——**都算失败**，⛔ 不把数字转成字符串凑合。
     */
    static String parseSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(raw).path("summary");
            if (!n.isTextual()) {
                return null;
            }
            String s = n.asText().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String preview(String raw) {
        if (raw == null) {
            return "(null)";
        }
        return raw.length() <= 200 ? raw : raw.substring(0, 200) + "…";
    }
}
