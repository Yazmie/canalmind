package com.canalmind.eval.pipeline;

import com.canalmind.chat.session.ContextAssembler;
import com.canalmind.chat.session.ContextMessage;
import com.canalmind.chat.session.SessionTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4 运行器的契约测试。
 *
 * <p>⚠️ 本类里**最重要**的一条是 {@link #flattenMustKeepRawParamsBlock()}：
 * 它是"上下文没接上、分数却很好看"那个假 PASS 的回归闸门。
 */
class L4RunCliTest {

    private static final String RAW = ContextAssembler.RAW_PARAMS_HEADER
            + "\n第 1 轮：{\"tonnage\":1200}";

    @Test
    @DisplayName("⛔⚠️ 拍平必须保留【最近原始参数】块——丢了它 L4 会给出假 PASS")
    void flattenMustKeepRawParamsBlock() {
        List<ContextMessage> messages = List.of(
                ContextMessage.system("基底系统提示（抽取器自己有更专业的，故可丢）"),
                ContextMessage.system(RAW),
                ContextMessage.user("建材过闸费，1200 吨"),
                ContextMessage.assistant("OK"),
                ContextMessage.user("那推到明年呢？"));

        String flat = L4RunCli.flatten(messages, "那推到明年呢？");

        // 这一行断言就是那个 bug 的闸门：早先 flatten 跳过**所有** system 消息，
        // 于是原始参数块被一并丢掉，而抽取器仍能从上一轮问句的措辞里猜对 ⇒ 假 PASS。
        assertTrue(flat.contains(ContextAssembler.RAW_PARAMS_HEADER),
                "【最近原始参数】块丢失：第二轮将无从按 C-38 改写参数");
        assertTrue(flat.contains("{\"tonnage\":1200}"), "原始参数正文丢失");
        assertTrue(flat.contains("建材过闸费，1200 吨"), "上一轮问句丢失");
        assertTrue(flat.contains("【本轮问题】那推到明年呢？"), "本轮问题标记丢失");
    }

    @Test
    @DisplayName("基底系统提示可以丢（抽取器自带系统提示）")
    void flattenMayDropBaseSystemPrompt() {
        String flat = L4RunCli.flatten(List.of(
                ContextMessage.system("基底系统提示"), ContextMessage.user("问题")), "问题");
        assertFalse(flat.contains("基底系统提示"));
    }

    @Test
    @DisplayName("多轮时上文的问句与本轮标记同时存在——抽取器才分得清哪条是当前轮")
    void flattenMarksCurrentQuestionEvenWhenHistoryRepeats() {
        String flat = L4RunCli.flatten(List.of(
                ContextMessage.user("第一轮问题"), ContextMessage.user("第二轮问题")), "第二轮问题");
        assertTrue(flat.contains("第一轮问题"), "上文问句应保留");
        // 两轮问题文本可能完全相同（用户原样再问），此时若无标记就无从分辨
        assertEquals(1, flat.split(java.util.regex.Pattern.quote("【本轮问题】"), -1).length - 1);
    }

    @Test
    @DisplayName("会话摘要块也保留（它代表更早的轮次）")
    void flattenKeepsSummaryBlock() {
        String flat = L4RunCli.flatten(List.of(
                ContextMessage.system(ContextAssembler.SUMMARY_HEADER + "\n更早：用户在问过闸费")),
                "那明年呢");
        assertTrue(flat.contains(ContextAssembler.SUMMARY_HEADER));
    }

    @Test
    @DisplayName("归一：只抹掉千分位与尾零，不改语义")
    void normOnlyNormalizesFormatting() {
        assertEquals("1200", L4RunCli.norm("1200.0"));
        assertEquals("1200", L4RunCli.norm(" 1,200 "));
        assertEquals("2027-01-05", L4RunCli.norm("2027-01-05"));
        assertEquals("", L4RunCli.norm(null));
        // ⛔ 不得把不同语义的值归成同一个（否则"没继承对"会被判成"对"）
        assertFalse(L4RunCli.norm("1200").equals(L4RunCli.norm("1201")));
        assertFalse(L4RunCli.norm("2600").equals(L4RunCli.norm("800")));
    }

    @Test
    @DisplayName("压缩桩：一旦被调用必须抛，且计数可核对（本轮不允许它静默生效）")
    void forbiddenSummarizerThrowsLoudly() {
        L4RunCli.ForbiddenSummarizer s = new L4RunCli.ForbiddenSummarizer();
        assertEquals(0, s.calls());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> s.summarize("", List.of(SessionTurn.of(1, "q", "a", "STRUCTURED"))));
        assertTrue(e.getMessage().contains("D10"), "抛出信息必须指明是 D10 未决导致的，便于排查");
        assertEquals(1, s.calls(), "被调用过就必须计数，⛔ 不能静默");
    }

    @Test
    @DisplayName("实参转文本：JsonNode 逐字段取值，数值不得带上 JsonNode 的引号")
    void stringifyReadsJsonFields() throws Exception {
        Map<String, String> m = L4RunCli.stringify(new ObjectMapper()
                .readTree("{\"tonnage\":1200,\"cargo_category\":\"建材\",\"days\":null}"));
        assertEquals("1200", m.get("tonnage"));
        assertEquals("建材", m.get("cargo_category"));
        // ⚠️ 传了 null 与没传，对系统是同一件事（都走默认值）⇒ 判分上必须同一种结果，
        //    故约定：null 值一律按"缺失"处理（不放进表里），⛔ 不许变成 "null" 这个字符串。
        assertFalse(m.containsKey("days"), "null 值必须按缺失处理");
        assertEquals(2, m.size());
    }

    @Test
    @DisplayName("实参转文本：非对象/null 不抛异常，返回空表（降级路径也要能跑完）")
    void stringifyToleratesBadInput() throws Exception {
        assertTrue(L4RunCli.stringify(null).isEmpty());
        assertTrue(L4RunCli.stringify(new ObjectMapper().readTree("\"不是对象\"")).isEmpty());
        assertTrue(L4RunCli.stringify(new ObjectMapper().readTree("{}")).isEmpty());
    }

    @Test
    @DisplayName("分批通过率按 itemPass 读布尔值，不靠标签文本（本项目在此栽过两次）")
    void bySplitReadsBooleanNotLabel() throws Exception {
        var m = new ObjectMapper();
        var arr = m.createArrayNode();
        var a = arr.addObject();
        a.put("split", "dev");
        a.put("itemPass", true);
        var b = arr.addObject();
        b.put("split", "dev");
        b.put("itemPass", false);
        var c = arr.addObject();
        c.put("split", "holdout");
        c.put("itemPass", true);
        var out = L4RunCli.bySplit(arr);
        assertEquals(0.5, out.path("dev").asDouble(), 1e-9);
        assertEquals(1.0, out.path("holdout").asDouble(), 1e-9);
    }

    @Test
    @DisplayName("续跑时未完成的题不计入分批（否则分母被稀释、通过率被拉低）")
    void bySplitSkipsItemsWithoutItemPass() throws Exception {
        var m = new ObjectMapper();
        var arr = m.createArrayNode();
        var a = arr.addObject();
        a.put("split", "dev");
        a.put("itemPass", true);
        arr.addObject().put("split", "dev"); // 只跑了路由/未作答的残留
        var out = L4RunCli.bySplit(arr);
        assertEquals(1.0, out.path("dev").asDouble(), 1e-9);
    }
}
