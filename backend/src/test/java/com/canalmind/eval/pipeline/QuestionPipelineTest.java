package com.canalmind.eval.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.canalmind.intent.IntentRouter;
import com.canalmind.intent.RouteDecision;
import com.canalmind.structured.ExtractionOutcome;
import com.canalmind.structured.ToolResult;

/**
 * 编排层契约测试。
 *
 * <p>⚠️ 这里**不测**"答案答得对不对"（那是 Judge 的活），只钉住三件**逻辑上不许变**的事：
 * <ol>
 *   <li>{@code TOOL_NONE} 的判别口径——它决定"要不要转交检索"；</li>
 *   <li><b>守卫类路由不得回退</b>——这是安全边界，回退等于绕过契约守卫；</li>
 *   <li>作答提示词里那三条硬约束**真的还在**——它们是被 Judge 判的行为前提，
 *       一旦有人顺手删掉一句，L3/L6 的分数会变，而**单测是唯一能提前发现的地方**。</li>
 * </ol>
 */
class QuestionPipelineTest {

    private static RouteDecision route(String channel, String ruleId) {
        return new RouteDecision(channel, List.of(), ruleId, "测试构造", false, "", 1.0);
    }

    // ─────────────────────────── ① TOOL_NONE 判别 ───────────────────────────

    @Test
    @DisplayName("DEGRADED + TOOL_NONE ⇒ 判定为「不属于工具集」，应当转交检索")
    void toolNoneIsDetected() {
        ToolResult r = ToolResult.degraded("none", ExtractionOutcome.KIND_TOOL_NONE, "不属于工具集",
                List.of());
        assertThat(QuestionPipeline.isToolNone(r)).isTrue();
    }

    @Test
    @DisplayName("其余状态都不算 TOOL_NONE：OK / NO_DATA / REJECTED / DEGRADED+TOOL_UNKNOWN / null")
    void otherStatusesAreNotToolNone() {
        assertThat(QuestionPipeline.isToolNone(null)).isFalse();
        assertThat(QuestionPipeline.isToolNone(
                ToolResult.noData("quote_fee", List.of("无数据"), List.of()))).isFalse();
        assertThat(QuestionPipeline.isToolNone(
                ToolResult.degraded("quote_fee", ExtractionOutcome.KIND_TOOL_UNKNOWN, "参数不合法",
                        List.of()))).isFalse();
    }

    @Test
    @DisplayName("⚠️ DEGRADED 但 kind 是 TOOL_UNKNOWN ⇒ 不得回退（那是真降级，不是走错通道）")
    void degradedWithUnknownKindMustNotFallBack() {
        // 这两者的区别是**本质**的：TOOL_NONE = 本题本来就不该走工具集（走错通道，可回退）；
        // TOOL_UNKNOWN = 该走工具集但参数/契约出了问题（是真实的失败，回退会把失败掩盖掉）。
        assertThat(ExtractionOutcome.KIND_TOOL_NONE).isNotEqualTo(ExtractionOutcome.KIND_TOOL_UNKNOWN);
        assertThat(QuestionPipeline.isToolNone(ToolResult.degraded("quote_fee",
                ExtractionOutcome.KIND_TOOL_UNKNOWN, "重试仍不合法", List.of()))).isFalse();
    }

    // ─────────────────────────── ② 守卫不得回退 ───────────────────────────

    @Test
    @DisplayName("R0 越权/注入题被钉住 ⇒ 禁止回退检索（否则回退会绕过契约守卫）")
    void guardPinnedRoutesMustNotFallBack() {
        assertThat(QuestionPipeline.isGuardPinned(
                route(RouteDecision.CHANNEL_STRUCTURED, IntentRouter.RULE_R0_GUARD))).isTrue();
    }

    @Test
    @DisplayName("非守卫规则可回退（R1 数值诉求、R4 弱结构化都要能回退）")
    void nonGuardRoutesMayFallBack() {
        assertThat(QuestionPipeline.isGuardPinned(
                route(RouteDecision.CHANNEL_STRUCTURED, IntentRouter.RULE_R1_STRUCTURED))).isFalse();
        assertThat(QuestionPipeline.isGuardPinned(
                route(RouteDecision.CHANNEL_STRUCTURED, IntentRouter.RULE_R4_WEAK_STRUCTURED))).isFalse();
    }

    // ─────────────────────────── ③ 作答提示词的三条硬约束 ───────────────────────────

    @Test
    @DisplayName("提示词含「不得编造数字」约束")
    void systemPromptForbidsFabrication() {
        String p = AnswerComposer.systemPrompt();
        assertThat(p).contains(AnswerComposer.NO_FABRICATION_RULE);
        assertThat(p).contains("绝对不要自己计算");
    }

    @Test
    @DisplayName("提示词含四种状态如实传达约束（L3 整层考的就是这个）")
    void systemPromptRequiresFaithfulStatus() {
        String p = AnswerComposer.systemPrompt();
        assertThat(p).contains("未查询到");
        assertThat(p).contains("已拒绝");
        assertThat(p).contains("需要追问");
        assertThat(p).contains("降级");
    }

    @Test
    @DisplayName("提示词含复合题分段约束（C-37⑤：金额段 + 依据段）")
    void systemPromptRequiresSegments() {
        assertThat(AnswerComposer.systemPrompt()).contains(AnswerComposer.SEGMENT_RULE);
    }

    @Test
    @DisplayName("工具状态必须翻成明确的中文结论，⛔ 不能让模型自己看状态码猜")
    void statusLineTranslatesEveryStatus() {
        assertThat(AnswerComposer.statusLine(null)).contains("检索通道");
        assertThat(AnswerComposer.statusLine(
                ToolResult.ok("query_tariff", List.of(), List.of(), List.of(), List.of())))
                .contains("已查询到数据");
        assertThat(AnswerComposer.statusLine(
                ToolResult.noData("query_tariff", List.of(), List.of()))).contains("未查询到");
        assertThat(AnswerComposer.statusLine(
                ToolResult.rejected("query_waybill", "超范围", List.of()))).contains("已拒绝");
        assertThat(AnswerComposer.statusLine(
                ToolResult.clarify("quote_fee", "请问计费吨位是多少？", List.of()))).contains("需要追问");
        assertThat(AnswerComposer.statusLine(
                ToolResult.degraded("quote_fee", ExtractionOutcome.KIND_TOOL_UNKNOWN, "x", List.of())))
                .contains("降级");
    }

    @Test
    @DisplayName("作答输入把事实与数据表摊平，且金额只作为给定事实传入（不由模型算）")
    void userPromptFlattensToolResult() {
        ToolResult r = ToolResult.ok("quote_fee", List.of("金额"), List.of(List.of("2839.20")),
                List.of("命中 2026 版能源 [500,2000) 段"), List.of("trace"));
        String p = AnswerComposer.userPrompt("1200 吨成品油过闸费？",
                route(RouteDecision.CHANNEL_STRUCTURED, IntentRouter.RULE_R1_STRUCTURED), r, "");
        assertThat(p).contains("2839.20");
        assertThat(p).contains("【数据表】");
        assertThat(p).contains("【事实】");
        assertThat(p).contains("命中 2026 版能源");
    }

    // ─────────────────────────── ④ 实际依据通道 ───────────────────────────

    @Test
    @DisplayName("回退发生时 effectiveChannel 报 RETRIEVAL，而 route 仍是 STRUCTURED（两者都要留）")
    void effectiveChannelReflectsFallback() {
        RouteDecision d = route(RouteDecision.CHANNEL_STRUCTURED, IntentRouter.RULE_R1_STRUCTURED);
        QuestionPipeline.Answer fellBack =
                new QuestionPipeline.Answer("q", d, "a", null, List.of("c1"), "ctx", 1, true);
        QuestionPipeline.Answer noFallback =
                new QuestionPipeline.Answer("q", d, "a", null, List.of(), "", 1, false);
        assertThat(fellBack.channel()).isEqualTo(RouteDecision.CHANNEL_STRUCTURED);
        assertThat(fellBack.effectiveChannel()).isEqualTo(RouteDecision.CHANNEL_RETRIEVAL);
        assertThat(noFallback.effectiveChannel()).isEqualTo(RouteDecision.CHANNEL_STRUCTURED);
    }

    // ─────────────────────────── ⑤ 判分的客观口径 ───────────────────────────

    @Test
    @DisplayName("金额抽取：从「金额 2839.20 元」里取到数字串")
    void firstAmountExtracts() {
        assertThat(JudgeRunCli.firstAmount("金额 2839.20 元")).isEqualTo("2839.20");
        assertThat(JudgeRunCli.firstAmount("金额 19,200.00 元")).isEqualTo("19200.00");
        assertThat(JudgeRunCli.firstAmount("无金额")).isNull();
    }

    @Test
    @DisplayName("金额比对：千分位与末尾零归一，2,839.20 / 2839.2 都要算命中")
    void containsAmountNormalizes() {
        assertThat(JudgeRunCli.containsAmount("应收 2839.20 元", "2839.20")).isTrue();
        assertThat(JudgeRunCli.containsAmount("应收 2,839.20 元", "2839.20")).isTrue();
        assertThat(JudgeRunCli.containsAmount("应收 2839.2 元", "2839.20")).isTrue();
        assertThat(JudgeRunCli.containsAmount("应收 2610.00 元", "2839.20")).isFalse();
    }

    @Test
    @DisplayName("判分阈值：严格口径与宽松口径必须同时存在（⛔ 不得只报一个）")
    void bothThresholdsExist() {
        assertThat(JudgeRunCli.thresholds()).containsKeys("strictMinEach", "lenientMinMean");
    }
}
