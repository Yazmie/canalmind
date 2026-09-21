package com.canalmind.chat.session;

import com.canalmind.structured.JsonChatInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-53：{@code ContextSummarizer} 生产实现的契约测试（**零付费**：桩住 invoker，不联网）。
 *
 * <p>⚠️ 本类守的是三条**接口注释里已经写死**的约定：并入上一版摘要、失败必须抛、空摘要算失败。
 * 这三条都属于"错了也不会报错、只是历史悄悄变少"的类型，故必须由测试钉住。
 */
class LlmContextSummarizerTest {

    /** 桩：记录收到的提示词，并按预设返回。 */
    private static JsonChatInvoker stub(String returnValue, AtomicReference<String[]> seen) {
        return new JsonChatInvoker() {
            @Override
            public String invoke(String systemPrompt, String userPrompt) {
                if (seen != null) {
                    seen.set(new String[] {systemPrompt, userPrompt});
                }
                return returnValue;
            }
        };
    }

    private static SessionTurn turn(int i, String q, String a, String params) {
        return new SessionTurn(i, q, a, "composite", params == null ? "" : params);
    }

    @Test
    @DisplayName("正常路径：从 JSON 里取 summary")
    void parsesSummary() throws Exception {
        var s = new LlmContextSummarizer(stub("{\"summary\":\"用户问过 1200 吨的过闸费。\"}", null));
        assertEquals("用户问过 1200 吨的过闸费。",
                s.summarize("", List.of(turn(1, "1200 吨过闸费多少？", "……", ""))));
    }

    @Test
    @DisplayName("⛔ 必须把上一版摘要并入提示词，否则更早的历史会被一段段吃掉")
    void mergesPreviousSummary() throws Exception {
        var seen = new AtomicReference<String[]>();
        var s = new LlmContextSummarizer(stub("{\"summary\":\"x\"}", seen));
        s.summarize("更早：用户问过 2026 版费率。", List.of(turn(3, "那 300 吨呢？", "……", "")));

        String user = seen.get()[1];
        assertTrue(user.contains("更早：用户问过 2026 版费率。"), "上一版摘要必须进提示词");
        assertTrue(user.contains("那 300 吨呢？"), "待折叠轮次必须进提示词");
        assertTrue(seen.get()[0].contains("并入"), "系统提示词必须要求并入上一版摘要");
    }

    @Test
    @DisplayName("⛔ C-38③：结构化参数原文必须逐字保留，且系统提示词要求不许改写")
    void structuredParamsSurviveVerbatim() throws Exception {
        var seen = new AtomicReference<String[]>();
        var s = new LlmContextSummarizer(stub("{\"summary\":\"x\"}", seen));
        String params = "quote_fee(fee_type=过闸费,tonnage=1200,biz_date=2026-12-10)";
        s.summarize("", List.of(turn(1, "多少钱？", "……", params)));

        assertTrue(seen.get()[1].contains(params),
                "结构化参数原文必须原样进提示词（这是唯一不许被摘要吃掉的东西）");
        assertTrue(seen.get()[1].contains("原始参数"), "提示词要标出「原始参数」这几个字");
        assertTrue(seen.get()[0].contains("逐字保留"), "系统提示词必须要求逐字保留参数");
        assertFalse(seen.get()[0].contains("\\n"), "⛔ 不得残留转义字面量");
    }

    @Test
    @DisplayName("⛔ 空摘要 / 缺字段 / 类型不对 / 原始响应为空 —— 一律抛，绝不返回空串")
    void emptySummaryIsFailure() {
        var turns = List.of(turn(1, "q", "a", ""));
        for (String bad : new String[] {"{\"summary\":\"\"}", "{\"summary\":\"   \"}",
                "{\"other\":\"x\"}", "{\"summary\":123}", "", "   ", "not json at all", null}) {
            var s = new LlmContextSummarizer(stub(bad, null));
            assertThrows(IllegalStateException.class, () -> s.summarize("", turns),
                    "原始响应「" + bad + "」必须被判为失败——"
                            + "返回空串会让下游把历史折没了却以为压缩成功");
        }
    }

    @Test
    @DisplayName("⛔ 模型调用失败必须原样抛给调用方，不得就地兜底")
    void modelFailurePropagates() {
        var boom = new JsonChatInvoker() {
            @Override
            public String invoke(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("模拟限流");
            }
        };
        var s = new LlmContextSummarizer(boom);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.summarize("", List.of(turn(1, "q", "a", ""))));
        assertEquals("模拟限流", ex.getMessage(),
                "⛔ 不得包装/吞掉原异常——调用方要靠它决定是否保留原文");
    }

    @Test
    @DisplayName("无应答 / 无结构化参数的轮次也要能压，且不得写出 null 字面量")
    void toleratesBlankFields() throws Exception {
        var seen = new AtomicReference<String[]>();
        var s = new LlmContextSummarizer(stub("{\"summary\":\"x\"}", seen));
        s.summarize("", List.of(turn(1, "问", "", "")));   // answer 空、无参数

        String user = seen.get()[1];
        assertTrue(user.contains("（本轮无应答）"));
        assertFalse(user.contains("null"), "⛔ 不得把 Java 的 null 写进提示词");
        assertFalse(user.contains("原始参数"), "没有结构化参数时不该出现「原始参数」行");
    }
}
