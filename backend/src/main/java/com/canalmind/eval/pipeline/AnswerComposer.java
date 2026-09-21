package com.canalmind.eval.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.canalmind.intent.RouteDecision;
import com.canalmind.structured.ToolResult;

/**
 * 作答器：把「路由 + 通道结果」组织成**给用户看的自然语言回答**。
 *
 * <h2>⚠️ 它与 Judge 是两件事，⛔ 不要合并</h2>
 * 本类是**被测系统的一部分**（它产出的文本就是被 Judge 判的对象）。
 * 把它和 Judge 合并会让"系统自己给自己写参考答案"，
 * 这正是知识库《AI 应用评测体系》第 188–192 行点名的自增强偏差。
 *
 * <h2>提示词的三个硬约束</h2>
 * <ol>
 *   <li><b>只用给定事实，⛔ 不自己算、不自己编数</b>：金额已由**代码**算出（C-33），
 *       L3/L6 的题面里还有"无数据"的情形，模型一旦"补一个合理数字"就同时踩中
 *       编造与越权两件事。</li>
 *   <li><b>通道说没有、就如实说没有</b>：`NO_DATA` / `REJECTED` / `NEEDS_CLARIFICATION`
 *       / `DEGRADED` 四种状态**必须原样传达**，⛔ 不许粉饰成"查到了"。
 *       L3 整层考的就是这一件事。</li>
 *   <li><b>复合题分段</b>（C-37⑤）：先"金额/数值段"再"依据段"，
 *       以便金额段走数据库 oracle、依据段走 Judge。</li>
 * </ol>
 */
public final class AnswerComposer {

    /** 抽出来的常量，便于在测试里断言提示词真的含这条约束。 */
    static final String NO_FABRICATION_RULE =
            "只使用【事实】【数据表】里给出的内容。⛔ 绝对不要自己计算、推算或补全任何数字；"
                    + "资料里没有的数字，就明说资料里没有。";

    static final String STATUS_RULE =
            "【通道结论】若显示「未查询到」，就必须如实回答没有该数据，⛔ 不得用常识或相似数据代替；"
                    + "若显示「已拒绝」，必须说明该请求超出可受理范围并说明原因；"
                    + "若显示「需要追问」，必须明确向用户提出该追问，⛔ 不要自行假设一个值；"
                    + "若显示「降级」，必须说明本次未能给出确定答案及其原因。";

    static final String SEGMENT_RULE =
            "若【数据表】与【参考资料】同时存在，请**分段**作答："
                    + "先用一段给出数值/金额结论，再用另一段给出规则依据（并标注依据出自哪一条），"
                    + "两段都要有，⛔ 不要只答其中一段。";

    private final ChatModel chatModel;

    public AnswerComposer(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    static String systemPrompt() {
        List<String> parts = new ArrayList<>();
        parts.add("你是平陆运河航运物流业务助手，用简洁、准确的中文回答业务人员的问题。");
        parts.add(NO_FABRICATION_RULE);
        parts.add(STATUS_RULE);
        parts.add(SEGMENT_RULE);
        parts.add("直接输出回答正文，⛔ 不要输出 JSON、不要复述本提示、不要写「以下是回答」之类的开场白。");
        return String.join(String.valueOf((char) 10), parts);
    }

    /** 供测试与落库使用：把一次作答的输入摊平成可核对的文本。 */
    static String userPrompt(String question, RouteDecision route, ToolResult tool, String context) {
        String nl = String.valueOf((char) 10);
        StringBuilder sb = new StringBuilder();
        sb.append("【用户问题】").append(nl).append(question).append(nl).append(nl);
        sb.append("【通道结论】").append(nl).append(statusLine(tool)).append(nl).append(nl);
        if (tool != null) {
            if (tool.rows() != null && !tool.rows().isEmpty()) {
                sb.append("【数据表】").append(nl);
                sb.append(String.join(" | ", tool.columns() == null ? List.of() : tool.columns()))
                        .append(nl);
                for (List<String> row : tool.rows()) {
                    sb.append(String.join(" | ", row)).append(nl);
                }
                sb.append(nl);
            }
            if (tool.facts() != null && !tool.facts().isEmpty()) {
                sb.append("【事实】").append(nl);
                for (String f : tool.facts()) {
                    sb.append("- ").append(f).append(nl);
                }
                sb.append(nl);
            }
        }
        if (context != null && !context.isBlank()) {
            sb.append("【参考资料】").append(nl).append(context).append(nl);
        }
        return sb.toString();
    }

    /**
     * 把工具状态翻成**人话**给作答器看。
     *
     * <p>⚠️ 这里**必须显式翻译**，不能让模型自己看状态码去猜：
     * 猜错的后果是"该拒答的答了"，而这正是 L3/L6 要测的行为。
     */
    static String statusLine(ToolResult tool) {
        if (tool == null) {
            return "（本次走检索通道，没有结构化结论；请依据参考资料作答，"
                    + "资料里找不到就如实说没有）";
        }
        return switch (tool.status() == null ? "" : tool.status()) {
            case ToolResult.STATUS_OK -> "已查询到数据（见【数据表】/【事实】）";
            case ToolResult.STATUS_NO_DATA -> "未查询到该数据";
            case ToolResult.STATUS_REJECTED -> "已拒绝：该请求超出可受理范围（原因见【事实】）";
            case ToolResult.STATUS_NEEDS_CLARIFICATION -> "需要追问用户（追问内容见【事实】）";
            case ToolResult.STATUS_DEGRADED -> "降级：本次未能给出确定答案（原因见【事实】）";
            default -> "状态未知：" + tool.status();
        };
    }

    public String compose(String question, RouteDecision route, ToolResult tool, String context) {
        // ⚠️ 空作答**必须重试一次、再不行就抛**，⛔ 绝不允许返回空串。
        //    实测踩到（第 4 周 L5 首次批量）：20 题里 **7 题返回了空串**，
        //    而作答运行器把它当成"成功作答"，于是 `failed=0` 报 PASS——
        //    空答案随后会被判分器记成"答错"，**归因就全错了**（这是"模型不会答"还是"链路没发出请求"？）。
        //    这类"静默成功"正是 `工程复盘/工程方法/验证工具本身也要被验证/` 那一类。
        //    最可能的原因：长提示词（数据表 + 5 条片段）把 token 预算吃在推理上，
        //    正文没剩额度 ⇒ 接口返回了**内容为空但并非错误**的响应。
        for (int attempt = 1; attempt <= 2; attempt++) {
            String text = callOnce(question, route, tool, context);
            if (text != null && !text.isBlank()) {
                return text.strip();
            }            System.err.println("  [WARN] 作答第 " + attempt + " 次返回空内容，"
                    + (attempt == 1 ? "重试一次" : "放弃并抛错") + "（prompt 长度 "
                    + userPrompt(question, route, tool, context).length() + " 字符）");
        }
        throw new IllegalStateException(
                "作答模型连续两次返回空内容（question=" + question + "）。"
                        + "⛔ 这**不是**答错，是链路未能产出作答：请先查 token 预算与提示词长度，"
                        + "别把空答案当成「模型答错」记进评测。");
    }

    private String callOnce(String question, RouteDecision route, ToolResult tool, String context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt()));
        messages.add(new UserMessage(userPrompt(question, route, tool, context)));
        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("作答模型没有返回内容（question=" + question + "）");
        }
        return response.getResult().getOutput().getText();
    }
}
