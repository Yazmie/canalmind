package com.canalmind.structured;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数抽取的测试：**重试与降级剧本全部用桩演出来**（不打真实模型）。
 *
 * <p>这些用例是"工具异常与降级"（第 3 周任务 4）的可执行证据：
 * 每条降级路径都断言**具体原因**与**尝试次数**，而不只是"没成功"。
 */
class ToolArgumentExtractorTest {

    private static final ToolCatalog CATALOG = ToolCatalog.loadDefault();
    private static final SchemaGate GATE = SchemaGate.of(CATALOG);
    private static final String LF = String.valueOf((char) 10);

    private static final String VALID = """
            {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_name":"成品油",
             "tonnage":1325.37,"biz_date":"2026-10-01"},
             "missing":[],"reason":"测算过闸费"}
            """;

    /** 记录每次收到的用户提示词，便于断言"错误反馈真的喂回去了"。 */
    private static final class StubInvoker implements JsonChatInvoker {
        private final List<String> replies;
        final List<String> seenUsers = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        StubInvoker(String... replies) {
            this.replies = new ArrayList<>(List.of(replies));
        }

        @Override
        public String invoke(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            seenUsers.add(userPrompt);
            assertNotNull(systemPrompt, "系统提示词不得为空");
            assertTrue(systemPrompt.contains("quote_fee"), "系统提示词必须带工具契约");
            if (replies.isEmpty()) {
                return "{}";
            }
            return replies.size() == 1 ? replies.get(0) : replies.remove(0);
        }
    }

    private static ToolArgumentExtractor extractor(JsonChatInvoker invoker) {
        return new ToolArgumentExtractor(CATALOG, GATE, invoker,
                ToolArgumentExtractor.DEFAULT_MAX_RETRIES, "2027-09-15");
    }

    @Test
    @DisplayName("首次合规：1 次调用即成功，工具与参数原样保留")
    void succeedsOnFirstTry() {
        StubInvoker stub = new StubInvoker(VALID);
        ExtractionOutcome o = extractor(stub).extract("成品油 1325.37 吨走平陆运河，过闸费多少？");
        assertTrue(o.ok());
        assertEquals(1, o.attempts());
        assertEquals(1, stub.calls.get());
        assertEquals("quote_fee", o.call().tool());
        assertEquals("成品油", o.call().stringArg("cargo_name"));
        assertEquals("1325.37", o.call().numberArg("tonnage"), "数值以字符串保留，避免 double 参与算钱");
        assertTrue(o.errors().isEmpty());
        assertEquals(1, o.rawResponses().size(), "原始应答必须留痕");
    }

    @Test
    @DisplayName("先畸形后合规：重试一次成功，且第二次提示词里带上了具体错误与「不要编造」提醒")
    void retriesWithErrorFeedback() {
        StubInvoker stub = new StubInvoker("我认为应该查询费率", VALID);
        ExtractionOutcome o = extractor(stub).extract("过闸费多少");
        assertTrue(o.ok());
        assertEquals(2, o.attempts());
        assertEquals(2, o.rawResponses().size(), "两次应答都要留痕（含畸形那次）");
        String second = stub.seenUsers.get(1);
        assertTrue(second.contains("上一次输出不合规"), "必须把上一轮错误反馈回去");
        assertTrue(second.contains("不是合法 JSON"), "反馈要说清错在哪一类");
        assertTrue(second.contains("不要编造"), "必须明确禁止编造参数（错答比不答危险）");
    }

    @Test
    @DisplayName("参数**值**越界（枚举不符）：错误反馈里点名违规字段，重试用尽后判 REJECTED")
    void valueViolationBecomesRejected() {
        StubInvoker stub = new StubInvoker(
                "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过路费\",\"cargo_category\":\"建材\","
                        + "\"tonnage\":1200,\"biz_date\":\"2026-10-01\"},\"missing\":[],\"reason\":\"x\"}");
        ExtractionOutcome o = extractor(stub).extract("过路费多少");
        // ⚠️ 本用例原先断言 DEGRADED + SCHEMA_INVALID——那是 D24 登记的缺口①：
        //    「值越界 / 枚举不符」是**用户诉求本身越出契约**（重试也改不对），应如实拒绝；
        //    而"缺必填/多余键/格式错"才是"模型没说清"，仍走降级（见下一个用例）。
        assertTrue(o.rejected(), "枚举不符应判 REJECTED，实际 " + o.status());
        assertEquals(ExtractionOutcome.KIND_PARAM_VIOLATION, o.degradeKind());
        assertEquals(3, o.attempts(), "1 次 + 重试 2 次（C-32 写死 ≤2）");
        assertEquals(3, stub.calls.get());
        assertFalse(o.errors().isEmpty());
        assertNotNull(o.call(), "拒绝要指名工具与实参（与降级不同，这里保留调用）");
        assertEquals("query_tariff", o.call().tool());
        assertTrue(o.errors().get(0).contains("fee_type"), "拒绝理由要点名违规参数：" + o.errors().get(0));
        assertTrue(stub.seenUsers.get(1).contains("过路费"), "反馈须带上具体越界取值");
    }

    @Test
    @DisplayName("越界值夹在同一次输出的形状错里：**仍判 REJECTED**（越界不因同时漏字段而变得可满足）")
    void valueViolationMixedWithShapeErrorStillRejected() {
        // 这条应答同时违反：①枚举（fee_type=过路费）②必填（缺 tonnage / biz_date）
        StubInvoker stub = new StubInvoker(
                "{\"tool\":\"query_tariff\",\"arguments\":{\"fee_type\":\"过路费\"},\"missing\":[],\"reason\":\"x\"}");
        ExtractionOutcome o = extractor(stub).extract("过路费多少");
        assertTrue(o.rejected(), "只要出现越界/枚举违规就应拒绝，实际 " + o.status());
        assertEquals(ExtractionOutcome.KIND_PARAM_VIOLATION, o.degradeKind());
    }

    @Test
    @DisplayName("形状不合规（缺必填）：仍是降级 SCHEMA_INVALID，⛔ 不得被当成「值越界」判拒绝")
    void shapeViolationStaysDegraded() {
        StubInvoker stub = new StubInvoker(
                "{\"tool\":\"query_tariff\",\"arguments\":{\"tonnage\":1000},\"missing\":[],\"reason\":\"x\"}");
        ExtractionOutcome o = extractor(stub).extract("过闸费多少");
        assertTrue(o.degraded(), "缺必填属于「模型没说清」，实际 " + o.status());
        assertEquals(ExtractionOutcome.KIND_SCHEMA_INVALID, o.degradeKind());
        assertNull(o.call(), "降级时不得留下半成品调用");
    }

    @Test
    @DisplayName("全程非 JSON：降级 NOT_JSON（与 SCHEMA_INVALID 分得开）")
    void degradesNotJson() {
        StubInvoker stub = new StubInvoker("抱歉，我不确定", "还是不确定", "真不确定");
        ExtractionOutcome o = extractor(stub).extract("随便问问");
        assertTrue(o.degraded());
        assertEquals(ExtractionOutcome.KIND_NOT_JSON, o.degradeKind());
        assertEquals(3, o.attempts());
    }

    @Test
    @DisplayName("模型调用抛异常：重试用尽后降级 MODEL_ERROR（与「输出畸形」区分）")
    void degradesOnModelError() {
        AtomicInteger calls = new AtomicInteger();
        JsonChatInvoker failing = (sys, user) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("connection reset");
        };
        ExtractionOutcome o = extractor(failing).extract("过闸费多少");
        assertTrue(o.degraded());
        assertEquals(ExtractionOutcome.KIND_MODEL_ERROR, o.degradeKind());
        assertEquals(3, o.attempts());
        assertEquals(3, calls.get());
        assertTrue(o.errors().get(0).contains("connection reset"), "原始异常信息要留痕，便于区分网络/鉴权/超时");
    }

    @Test
    @DisplayName("模型判定不属于工具集：直接降级 TOOL_NONE，不重试（该走检索通道）")
    void toolNoneDegradesWithoutRetry() {
        StubInvoker stub = new StubInvoker(
                "{\"tool\":\"none\",\"arguments\":{},\"missing\":[],\"reason\":\"政策咨询\"}");
        ExtractionOutcome o = extractor(stub).extract("平陆运河什么时候通航？");
        assertTrue(o.degraded());
        assertEquals(ExtractionOutcome.KIND_TOOL_NONE, o.degradeKind());
        assertEquals(1, stub.calls.get(), "不匹配工具集不是错误，不该浪费重试");
    }

    @Test
    @DisplayName("模型申报缺参：转追问并保留缺失清单，不逼模型编值")
    void missingParamsBecomeClarification() {
        StubInvoker stub = new StubInvoker("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费"},
                 "missing":["tonnage","biz_date"],"reason":"用户没说吨位和日期"}
                """);
        ExtractionOutcome o = extractor(stub).extract("过闸费怎么算");
        assertTrue(o.needsClarification());
        assertEquals(ExtractionOutcome.KIND_MISSING_PARAMS, o.degradeKind());
        assertEquals(List.of("tonnage", "biz_date"), o.call().missing());
        assertEquals(1, stub.calls.get());
    }

    @Test
    @DisplayName("宽松解析：代码块围栏、前后缀文字都能被纠正（实测常态），但**仍要过 Schema**")
    void repairsWrappedJson() {
        StubInvoker fenced = new StubInvoker("```json" + LF + VALID + LF + "```");
        assertTrue(extractor(fenced).extract("过闸费多少").ok(), "带围栏应被剥离后接受");

        StubInvoker chatty = new StubInvoker("好的，这是结果：" + VALID + LF + "希望有帮助。");
        assertTrue(extractor(chatty).extract("过闸费多少").ok(), "前后缀文字应被截取后接受");

        // 但"纠正格式"不等于"降低标准"：围栏里的内容不合规仍要拦
        StubInvoker badFenced = new StubInvoker("```json" + LF
                + "{\"tool\":\"query_tariff\",\"arguments\":{\"tonnage\":1000},\"missing\":[],\"reason\":\"x\"}"
                + LF + "```");
        ExtractionOutcome o = extractor(badFenced).extract("过闸费多少");
        assertTrue(o.degraded(), "缺必填仍是降级（不是 REJECTED），实际 " + o.status());
        assertEquals(ExtractionOutcome.KIND_SCHEMA_INVALID, o.degradeKind());
    }

    @Test
    @DisplayName("用户提示词带业务当天（模型不该自己猜「今天」）")
    void userPromptCarriesBusinessToday() {
        StubInvoker stub = new StubInvoker(VALID);
        extractor(stub).extract("现在过闸费多少");
        assertTrue(stub.seenUsers.get(0).contains("2027-09-15"), "业务当天必须进提示词");
        assertTrue(stub.seenUsers.get(0).contains("现在过闸费多少"), "用户原话要原样带上");
    }
}
