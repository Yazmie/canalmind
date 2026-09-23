package com.canalmind.chat.frame;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.canalmind.intent.RouteDecision;
import com.canalmind.structured.ToolResult;

/**
 * 结构化帧编码的纯单元测试：**不连库、不调模型、不起容器**。
 *
 * <p>重点只测三件会在生产上出事、又无法靠肉眼发现的事：
 * <ol>
 *   <li><b>单行性</b>——载荷里出现真实换行会让 SSE 事件边界错位
 *       （知识库《大模型 API 调用工程实践》第 82–88、110–114 行）；</li>
 *   <li><b>可逆性</b>——正文里的换行、反斜杠（航运场景常见：文件路径、JSON 示例）
 *       必须原样还原，⛔ 不能被"顺手归一化"吃掉；</li>
 *   <li><b>五态如实透传</b>——{@code OK / NO_DATA / NEEDS_CLARIFICATION / REJECTED / DEGRADED}
 *       是本项目刻意保留的区分（{@code ToolResult} 的 javadoc：
 *       "⛔ 不要用空结果集表达差异"），编码层**不得归并**它们。</li>
 * </ol>
 */
class ChatFrameCodecTest {

    /** 真实换行用计算字符，⛔ 不用转义字面量（AGENTS.md §7.2 硬规定）。 */
    private static final String LF = String.valueOf((char) 10);

    /** 反斜杠同理：用计算字符，任何编辑工具都折坏不了它。 */
    private static final String BACKSLASH = String.valueOf((char) 92);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个含换行与反斜杠的"最难缠"正文，正是会让协议出错的那类内容。 */
    private static final String NASTY = "计算过程：3200 × 3.30 元/吨" + LF
            + "路径示例：C:" + BACKSLASH + "new" + BACKSLASH + "path" + LF
            + "（以上两行故意含换行与反斜杠）";

    private static ToolResult okWithNastyText() {
        return ToolResult.ok("quote_fee",
                List.of("费种", "金额(元)"),
                List.of(List.of("过闸费", "10560.00")),
                List.of("过闸费金额 = 10560.00 元", NASTY),
                List.of("工具=quote_fee，参数={}"));
    }

    private static RouteDecision route(String channel) {
        return new RouteDecision(channel,
                List.of(new RouteDecision.RouteStep(channel, "建材 3200 吨过闸费多少", "过闸费多少")),
                "R1-数值诉求-结构化", "命中数值诉求「多少」⇒ 该数值应由数据库算出", false, "", 1.0);
    }

    private static FrameCitation citation() {
        return new FrameCitation("c-1", "过闸费费率表（2026版）", "PLYH-FL-2026-006",
                "第四条", "【建材类过闸费费率】", List.of("第二章 费率标准"), 1, 1, "建材类货物过闸费费率如下");
    }

    @Test
    @DisplayName("★ 核心：含换行的正文编码后仍是**单行**，且可逆还原")
    void encodesToSingleLineAndRoundTrips() throws Exception {
        String json = ChatFrameCodec.encode("s-1", route("STRUCTURED"), okWithNastyText(),
                List.of(citation()));

        // ① 单行性：绝不能出现真实换行 / 回车
        assertThat(json).doesNotContain(LF);
        assertThat(json).doesNotContain(String.valueOf((char) 13));

        // ② 可逆性：还原后与原文逐字符相等（反斜杠不被吞、换行不被折）
        JsonNode root = MAPPER.readTree(json);
        List<String> facts = List.of(
                root.path("tool").path("facts").get(0).asText(),
                root.path("tool").path("facts").get(1).asText());
        assertThat(facts.get(1))
                .as("正文必须原样还原：换行与反斜杠都不能被改写")
                .isEqualTo(NASTY);
    }

    @Test
    @DisplayName("帧版本与顶层结构固定（前端按 v 分支）")
    void carriesVersionAndAgreedTopLevelShape() throws Exception {
        JsonNode root = MAPPER.readTree(ChatFrameCodec.encode("s-2", route("STRUCTURED"),
                okWithNastyText(), List.of(citation())));

        assertThat(root.path("v").asInt()).isEqualTo(ChatFrameCodec.VERSION);
        assertThat(root.path("sessionId").asText()).isEqualTo("s-2");
        assertThat(root.path("route").path("channel").asText()).isEqualTo("STRUCTURED");
        assertThat(root.path("route").path("ruleId").asText()).isEqualTo("R1-数值诉求-结构化");
        assertThat(root.path("tool").path("name").asText()).isEqualTo("quote_fee");
        assertThat(root.path("tool").path("columns").get(1).asText()).isEqualTo("金额(元)");
        assertThat(root.path("tool").path("rows").get(0).get(1).asText())
                .as("金额是 ::text 定型的字符串，⛔ 编码层不得把它变成数字")
                .isEqualTo("10560.00");
        assertThat(root.path("citations").get(0).path("docNo").asText()).isEqualTo("PLYH-FL-2026-006");
        assertThat(root.path("citations").get(0).path("chapterPath").get(0).asText())
                .isEqualTo("第二章 费率标准");
        assertThat(root.path("citations").get(0).path("partIndex").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 五态如实透传：status 与 question / degradeKind 都不被归并")
    void passesThroughAllFiveStatuses() throws Exception {
        record Case(String status, ToolResult result) {
        }
        List<Case> cases = List.of(
                new Case("OK", okWithNastyText()),
                new Case("NO_DATA", ToolResult.noData("query_tariff", List.of("窗口内没有记录"), List.of())),
                new Case("NEEDS_CLARIFICATION", ToolResult.clarify("quote_fee", "请补充计费吨位。", List.of())),
                new Case("REJECTED", ToolResult.rejected("quote_fee", "「引航费」与货类无关", List.of())),
                new Case("DEGRADED", ToolResult.degraded("quote_fee", "OUT_OF_WINDOW", "窗口外", List.of())));

        for (Case c : cases) {
            JsonNode tool = MAPPER.readTree(
                    ChatFrameCodec.encode("s", route("STRUCTURED"), c.result(), List.of()))
                    .path("tool");
            assertThat(tool.path("status").asText()).as("状态必须原样").isEqualTo(c.status());
            // 字段恒在：值为空时是 null，⛔ 不是"时有时无"（否则前端要多一种分支）
            assertThat(tool.has("question")).isTrue();
            assertThat(tool.has("degradeKind")).isTrue();
        }
    }

    @Test
    @DisplayName("追问态带 question、降级态带 degradeKind；空值写 null 而不是省略字段")
    void keepsNullableFieldsPresent() throws Exception {
        JsonNode clarify = MAPPER.readTree(ChatFrameCodec.encode("s", route("STRUCTURED"),
                ToolResult.clarify("quote_fee", "请补充以下信息：计费吨位。", List.of()), List.of()))
                .path("tool");
        assertThat(clarify.path("question").asText()).isEqualTo("请补充以下信息：计费吨位。");
        assertThat(clarify.path("degradeKind").isNull()).isTrue();

        JsonNode degraded = MAPPER.readTree(ChatFrameCodec.encode("s", route("STRUCTURED"),
                ToolResult.degraded(null, "TOOL_NONE", "不属于工具集", List.of()), List.of()))
                .path("tool");
        assertThat(degraded.path("degradeKind").asText()).isEqualTo("TOOL_NONE");
        // 工具名确实可能为 null（ToolResult.degraded 的第一个参数就是 null）
        assertThat(degraded.path("name").isNull()).isTrue();
    }

    @Test
    @DisplayName("没有结果时不出对应对象：route / tool 可为空，citations 恒为数组")
    void toleratesAbsentParts() throws Exception {
        JsonNode root = MAPPER.readTree(ChatFrameCodec.encode(null, null, null, null));
        assertThat(root.path("sessionId").asText())
                .as("会话标识缺省按匿名，⛔ 不写 null（前端不必处理两种空）")
                .isEqualTo("anonymous");
        assertThat(root.has("route")).isFalse();
        assertThat(root.has("tool")).isFalse();
        assertThat(root.path("citations").isArray()).isTrue();
        assertThat(root.path("citations")).isEmpty();
    }

    @Test
    @DisplayName("摘录把多行正文折成单行并截断（引用是「认出是哪段」，不是搬正文）")
    void excerptCollapsesAndTruncates() {
        assertThat(ChatFrameCodec.excerpt(null)).isEmpty();
        assertThat(ChatFrameCodec.excerpt("")).isEmpty();
        assertThat(ChatFrameCodec.excerpt("第一行" + LF + LF + "  第三行"))
                .as("连续换行与多余空白折成一个空格")
                .isEqualTo("第一行 第三行");
        assertThat(ChatFrameCodec.excerpt("  " + LF + " 前后留白 " + LF))
                .as("首尾空白要修掉")
                .isEqualTo("前后留白");

        String long1 = "甲".repeat(ChatFrameCodec.EXCERPT_LIMIT + 50);
        String out = ChatFrameCodec.excerpt(long1);
        assertThat(out).endsWith("…");
        assertThat(out.length()).isEqualTo(ChatFrameCodec.EXCERPT_LIMIT + 1);

        // 与上限等长时**不**加省略号（边界：不是"超过"就截）
        String exact = "乙".repeat(ChatFrameCodec.EXCERPT_LIMIT);
        assertThat(ChatFrameCodec.excerpt(exact)).isEqualTo(exact);
    }
}
