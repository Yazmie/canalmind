package com.canalmind.eval.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-50 的 C 臂（LLM 改写器）：**提示词与解析契约**的零付费测试。
 *
 * <p>把提示词与解析从模型调用里拆出来后，本类**不联网、不花钱**即可守住契约；
 * 真正付费的只有"按这些提示词去问模型"那一步。
 */
class QueryRewritePromptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("系统提示词必须钉住三条硬约束与 JSON 输出约定")
    void systemPromptCarriesHardConstraints() {
        String p = QueryRewritePrompt.systemPrompt();
        assertTrue(p.contains("独立检索"), "必须要求改写成可独立检索的问句");
        assertTrue(p.contains("显式写进"), "必须要求把上文已确认的实体写进改写句");
        assertTrue(p.contains("不得引入上文"), "必须禁止引入上文没有的信息（否则会猜错费率版本）");
        assertTrue(p.contains("原样返回原句"), "必须有「拿不准就原样返回」的保守退化（失败安全）");
        assertTrue(p.contains("rewritten"), "必须约定 JSON 字段名，否则解析无处可依");
        assertFalse(p.contains("\\n"), "⛔ 提示词里不得残留转义字面量（换行要用计算字符）");
    }

    @Test
    @DisplayName("用户提示词必须把对话上文与待改写句都带上")
    void userPromptCarriesBothTurns() {
        String p = QueryRewritePrompt.userPrompt("我这条船船宽 33 米。", "这算超限吗？");
        assertTrue(p.contains("我这条船船宽 33 米。"), "缺了上文就无从还原指代");
        assertTrue(p.contains("这算超限吗？"), "待改写句必须在提示里");
        assertTrue(p.indexOf("我这条船船宽 33 米。") < p.indexOf("这算超限吗？"), "上文应排在待改写句之前");
    }

    @Test
    @DisplayName("上/下文为 null 不得抛异常，也不得写出字面量 null")
    void nullSafe() {
        String p = QueryRewritePrompt.userPrompt(null, null);
        assertNotNull(p);
        assertFalse(p.contains("null"), "⛔ 不得把 Java 的 null 字面量写进提示词");
    }

    @Test
    @DisplayName("正常 JSON 能取到改写句")
    void parsesNormalJson() throws Exception {
        var node = MAPPER.readTree("{\"rewritten\":\"船宽 33 米的船算超限吗？\"}");
        assertEquals("船宽 33 米的船算超限吗？", QueryRewritePrompt.parseRewritten(node));
    }

    @Test
    @DisplayName("⛔ 空改写是**失败**，必须与「原样返回」区分开")
    void emptyRewriteIsFailureButEchoIsNot() throws Exception {
        // 模型没给改写 ⇒ 失败（返回 null，调用方要计入失败率）
        assertNull(QueryRewritePrompt.parseRewritten(MAPPER.readTree("{\"rewritten\":\"\"}")));
        assertNull(QueryRewritePrompt.parseRewritten(MAPPER.readTree("{\"rewritten\":\"   \"}")));
        assertNull(QueryRewritePrompt.parseRewritten(MAPPER.readTree("{\"other\":\"x\"}")));
        assertNull(QueryRewritePrompt.parseRewritten(MAPPER.readTree("{\"rewritten\":123}")),
                "字段类型不对也算失败，⛔ 不得把数字转成字符串凑合");
        assertNull(QueryRewritePrompt.parseRewritten(null));

        // 模型按约束 5 原样返回 ⇒ **合法**的保守退化，⛔ 不是失败
        String echoed = QueryRewritePrompt.parseRewritten(
                MAPPER.readTree("{\"rewritten\":\"这算超限吗？\"}"));
        assertEquals("这算超限吗？", echoed, "原样返回是合法的保守退化，不能被当成失败抹掉");
    }
}
