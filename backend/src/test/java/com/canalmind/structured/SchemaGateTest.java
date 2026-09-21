package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端 JSON Schema 校验闸门的测试（决策 C-32 的核心件）。
 *
 * <p>⚠️ 这里刻意**逐条断言"错误消息非空"**：错误消息要原样喂回给模型重试，
 * 若 networknt 因消息源缺失返回 null，重试就在"什么都没告诉模型"的情况下空转——
 * 表现是"重试了 2 次还是不合规"，却看不出原因。故消息非空是**功能契约**，不是装饰。
 */
class SchemaGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolCatalog CATALOG = ToolCatalog.loadDefault();
    private static final SchemaGate GATE = SchemaGate.of(CATALOG);

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    @DisplayName("合法的工具调用通过两层校验")
    void acceptsValidCall() throws Exception {
        JsonNode node = json("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_name":"成品油",
                 "tonnage":1325.37,"biz_date":"2026-10-01","days":1},
                 "missing":[],"reason":"测算过闸费"}
                """);
        assertTrue(GATE.validateEnvelope(node).isEmpty(), "envelope 应通过");
        assertTrue(GATE.validateArguments("quote_fee", node.path("arguments")).isEmpty(), "参数应通过");
    }

    @Test
    @DisplayName("envelope 层：缺键、多余键、工具名非字符串 都要被拦且给非空消息")
    void rejectsBadEnvelope() throws Exception {
        expectRejected("""
                {"tool":"query_tariff","arguments":{},"missing":[]}
                """, null, "缺 reason");
        expectRejected("""
                {"tool":"query_tariff","arguments":{},"missing":[],"reason":"x","thought":"多余"}
                """, null, "多余键");
        expectRejected("""
                {"tool":123,"arguments":{},"missing":[],"reason":"x"}
                """, null, "工具名类型错");
        expectRejected("""
                {"tool":"query_tariff","arguments":[],"missing":[],"reason":"x"}
                """, null, "arguments 不是对象");
        expectRejected("""
                {"tool":"query_tariff","arguments":{},"missing":"tonnage","reason":"x"}
                """, null, "missing 不是数组");
    }

    @Test
    @DisplayName("参数层：枚举越界 / 日期格式 / 非正吨位 / 缺必填 / 超上限 逐条拦下")
    void rejectsBadArguments() throws Exception {
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过路费","cargo_category":"建材",
                 "tonnage":1200,"biz_date":"2026-10-01"},"missing":[],"reason":"x"}
                """, "query_tariff", "枚举越界");
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":1200,"biz_date":"2026/10/01"},"missing":[],"reason":"x"}
                """, "query_tariff", "日期格式错");
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":0,"biz_date":"2026-10-01"},"missing":[],"reason":"x"}
                """, "query_tariff", "吨位为 0");
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":-5,"biz_date":"2026-10-01"},"missing":[],"reason":"x"}
                """, "query_tariff", "吨位为负");
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":2000000,"biz_date":"2026-10-01"},"missing":[],"reason":"x"}
                """, "query_tariff", "吨位超上限");
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费"},"missing":[],"reason":"x"}
                """, "query_tariff", "缺必填参数");
        expectRejected("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":1200,"biz_date":"2026-10-01","evil":"x=1 or 1=1"}, "missing":[],"reason":"x"}
                """, "query_tariff", "多余参数（注入面直接挡在 Schema 层）");
        expectRejected("""
                {"tool":"query_traffic","arguments":{"date_from":"2026-10-01","date_to":"2026-10-31",
                 "metric":"运费"},"missing":[],"reason":"x"}
                """, "query_traffic", "统计口径枚举越界");
        expectRejected("""
                {"tool":"query_traffic","arguments":{"date_from":"2026-10-01","date_to":"2026-10-31",
                 "metric":"吨位","group_by":"船舶"},"missing":[],"reason":"x"}
                """, "query_traffic", "分组维度越界");
        expectRejected("""
                {"tool":"query_waybill","arguments":{"waybill_no":"WB2026ABC"},"missing":[],"reason":"x"}
                """, "query_waybill", "运单号格式");
    }

    @Test
    @DisplayName("引航费/停泊费不传货类也应通过（这两类与货类无关）")
    void perTimeFeeTypesDoNotNeedCategory() throws Exception {
        JsonNode node = json("""
                {"tool":"query_tariff","arguments":{"fee_type":"引航费","tonnage":2000,
                 "biz_date":"2027-03-01"},"missing":[],"reason":"查引航费费率"}
                """);
        assertTrue(GATE.validateEnvelope(node).isEmpty());
        assertTrue(GATE.validateArguments("query_tariff", node.path("arguments")).isEmpty());
    }

    @Test
    @DisplayName("校验闸门注册的 Schema 数与契约一致（同源，不各写一份）")
    void gateUsesContractSchemas() {
        assertTrue(GATE.toolSchemaCount() == CATALOG.tools().size(),
                "校验器里的工具 Schema 必须与契约同源");
    }

    private static void expectRejected(String jsonText, String tool, String what) throws Exception {
        JsonNode node = json(jsonText);
        List<String> envErrors = GATE.validateEnvelope(node);
        List<String> errors = envErrors;
        if (tool != null && envErrors.isEmpty()) {
            errors = GATE.validateArguments(tool, node.path("arguments"));
        }
        assertFalse(errors.isEmpty(), "「" + what + "」应被拦下，实际通过");
        assertTrue(errors.stream().anyMatch(s -> s != null && !s.isBlank()),
                "「" + what + "」的错误消息不得为空（空消息喂回模型等于没喂）");
    }
}
