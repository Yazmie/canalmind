package com.canalmind.eval.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.canalmind.config.PromptVersions;
import com.canalmind.intent.RouteDecision;
import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;
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

    /**
     * 本次作答是否启用 **v2 依据强化**（C-51）。
     *
     * <p>⚠️ 默认 {@code false} ⇒ **既有行为零变化**：不显式选 v2 时，
     * 本类发出的提示词与 L3/L5/L6 既有出分产物所依据的**完全一致**。
     */
    private final boolean citationV2;

    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.llm.generate}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    /** 既有构造器：**不启用 v2**（保持既有行为零变化）。 */
    public AnswerComposer(ChatModel chatModel) {
        this(chatModel, null, Map.of(), ObservationRegistry.NOOP);
    }

    /**
     * 按层取 Prompt 版本（C-51）。
     *
     * <p>⚠️ 层名是**必须**传进来的：v2 只对 {@code L5} 生效，
     * 而这条作用域纪律由 {@link PromptVersions#appliesToLayer} 统一裁决
     * ⇒ ⛔ 不在本类里另写一份判断，免得两处口径漂移。
     *
     * @param layer 层名（如 {@code L5}）；为 {@code null} 或非目标层 ⇒ 走 v1
     * @param env   进程环境（读 {@code CANALMIND_SYSTEM_PROMPT_VERSION}）
     */
    public AnswerComposer(ChatModel chatModel, String layer, Map<String, String> env) {
        this(chatModel, layer, env, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 第 562 行的 {@code ai.llm.generate}）。
     *
     * <p>⚠️ 既有两个构造器保持不变、一律委托给 {@link ObservationRegistry#NOOP}
     * （使用方：{@code QuestionPipeline.create}、{@code LayerRunCli} / {@code L4RunCli}、
     * {@code AnswerComposerPromptVersionTest}），**旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public AnswerComposer(ChatModel chatModel, String layer, Map<String, String> env,
                          ObservationRegistry observationRegistry) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.citationV2 = PromptVersions.appliesToLayer(layer, env);
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    /**
     * 本次作答所用的**提示词版本名**（写进 Trace 的高基数 {@code ai.config.version}）。
     *
     * <p>⚠️ 版本名的来源必须是**同一个判据**（{@code citationV2}）：
     * 另写一套"按 layer+env 重算"的逻辑早晚与它分叉，而分叉的表现是
     * 「产物说 v1、实际发的是 v2」——不会报错（这正是 C-51 记录过的那类错）。
     */
    String promptVersion() {
        return citationV2 ? PromptVersions.V2 : PromptVersions.V1;
    }

    /** 本次作答是否启用了 v2（供产物落盘时标注 Prompt 版本，便于溯源）。 */
    boolean citationV2Enabled() {
        return citationV2;
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

    /**
     * 本层**实际**使用的系统提示词（C-51）：{@code citationV2} 为真时把 v2 追加块接在末尾。
     *
     * <p>⚠️ **v1 一个字都不改**：{@code citationV2 == false} 时返回的就是
     * {@link #systemPrompt()} 的原样结果 ⇒ 既有产物仍出自同一段文字。
     * <p>⚠️ 追加块与 Web 侧**共用同一份文字**（{@code PromptVersions.citationBlock()}），
     * ⛔ 不在本类里另抄一份。
     */
    static String systemPrompt(boolean citationV2) {
        String base = systemPrompt();
        return citationV2
                ? base + String.valueOf((char) 10) + PromptVersions.citationBlock()
                : base;
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

    /**
     * 作答。
     *
     * <p>规格书 §7.2 第 562 行给模型调用一个 Span（{@code ai.llm.generate}）。
     * 这里记两个属性：
     * <ul>
     *   <li><b>高基数</b> {@code ai.config.version} = 本次的提示词版本名（{@code v1}/{@code v2}）——
     *       "这条 Trace 是哪一版提示词产出的"必须能从 Trace 直接看出来，
     *       否则 C-51 那种"同一次运行里 L5 用 v2、其余层用 v1"的对比就无法归因；</li>
     *   <li><b>高基数</b> {@code attempt} = **实际调用了几次模型**（1 或 2）。</li>
     * </ul>
     *
     * <p>⚠️ 为什么连 {@code attempt} 也记（规格书只要求了版本）：下面的重试是
     * 「模型返回空正文就再调一次」，而**空正文曾经是一处静默失败**——
     * 第 4 周 L5 首次批量实测 20 题里 <b>7 题返回空串</b>，运行器把它当成"成功作答"
     * （见本方法内注释）。只把重试写进 stderr 的 WARN 是不够的：
     * 那次告警**没有任何产物承载**，事后翻 Trace 只会看到"这条作答正常"。
     * 记下 {@code attempt=2} 就等于把"这一次作答其实重试过"钉进 Trace。
     * <p>⛔ 但**不**为两次尝试各建一个 Span（与 {@code ai.tool.param.extract} 的逐次不同）：
     * 这里两次尝试的**输入完全相同**（同一提示词、同一参数），
     * 分不出的信息（哪一次"更快/更省"）并不存在；要区分的是"有没有重试过"，
     * 而那一个 {@code attempt} 就够了。⚠️ 如实记这笔差异，而不是假装两处口径一致。
     */
    public String compose(String question, RouteDecision route, ToolResult tool, String context) {
        Observation observation = AiObservation.start(AiSpans.LLM_GENERATE, observationRegistry);
        return observation.observe(() -> {
            AiObservation.highCardinality(observation, AiSpans.KEY_CONFIG_VERSION, promptVersion());
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
                    AiObservation.attempt(observation, attempt);
                    return text.strip();
                }
                System.err.println("  [WARN] 作答第 " + attempt + " 次返回空内容，"
                        + (attempt == 1 ? "重试一次" : "放弃并抛错") + "（prompt 长度 "
                        + userPrompt(question, route, tool, context).length() + " 字符）");
            }
            throw new IllegalStateException(
                    "作答模型连续两次返回空内容（question=" + question + "）。"
                            + "⛔ 这**不是**答错，是链路未能产出作答：请先查 token 预算与提示词长度，"
                            + "别把空答案当成「模型答错」记进评测。");
        });
    }

    private String callOnce(String question, RouteDecision route, ToolResult tool, String context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(citationV2)));
        messages.add(new UserMessage(userPrompt(question, route, tool, context)));
        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("作答模型没有返回内容（question=" + question + "）");
        }
        return response.getResult().getOutput().getText();
    }
}
