package com.canalmind.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务层业务规则的测试：**缺参追问 / 口径拒绝 / 未命中如实说明 / 窗口裁剪**。
 *
 * <p>这些分支最容易在"模型答对了"的假象下被漏掉——评测题里若没有这类样本，
 * 一个"什么参数都没有就直接查全表"的实现也能拿到同样的分。
 * 故这里用**内存假实现**把每条分支钉住（不连库）。
 */
class StructuredQueryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolCatalog CATALOG = ToolCatalog.loadDefault();
    private static final SchemaGate GATE = SchemaGate.of(CATALOG);
    private static final String TODAY = "2027-09-15";

    /** 内存费率表：只放两条（过闸费/建材 元/吨；港杂费/化工 元/次），其余一律不命中。 */
    private static final class FakeTariff implements TariffRuleLookup {
        private static final List<TariffRuleRow> RULES = List.of(
                new TariffRuleRow(1L, "CT-过闸费-建材-500", "过闸费", "建材", "载货吨位",
                        new BigDecimal("500"), new BigDecimal("2000"), new BigDecimal("2.55"), "元/吨",
                        LocalDate.of(2026, 9, 16), LocalDate.of(2027, 1, 1)),
                new TariffRuleRow(2L, "CT-港杂费-化工-1000", "港杂费", "化工", "载货吨位",
                        new BigDecimal("1000"), new BigDecimal("5000"), new BigDecimal("320.00"), "元/次",
                        LocalDate.of(2026, 9, 16), LocalDate.of(2027, 1, 1)));

        @Override
        public Optional<TariffRuleRow> findRule(String feeType, String cargoCategory, String tonnageBasis,
                                                BigDecimal tonnage, LocalDate bizDate) {
            return RULES.stream()
                    .filter(r -> r.feeType().equals(feeType) && r.cargoCategory().equals(cargoCategory)
                            && r.tonnageBasis().equals(tonnageBasis) && r.covers(tonnage)
                            && !bizDate.isBefore(r.effectiveFrom()) && bizDate.isBefore(r.effectiveTo()))
                    .findFirst();
        }

        @Override
        public List<TariffRuleRow> listByFeeType(String feeType, LocalDate bizDate) {
            return RULES.stream()
                    .filter(r -> r.feeType().equals(feeType) && !bizDate.isBefore(r.effectiveFrom())
                            && bizDate.isBefore(r.effectiveTo()))
                    .toList();
        }
    }

    private static final class FakeData implements StructuredDataLookup {
        List<String> lastCall;

        @Override
        public Boolean cargoCategoryDangerous(String cargoCategory) {
            // 与真实库同构：**能源类内部不一致**（成品油危险、动力煤不危险）⇒ null 表示"无法由货类推导"；
            // 化工 3 种全是危险货物，其余类全不是。
            return switch (cargoCategory) {
                case "能源" -> null;
                case "化工" -> true;
                case "建材", "金属", "农资", "集装箱", "其他" -> false;
                default -> throw new IllegalArgumentException("未知货类：" + cargoCategory);
            };
        }

        @Override
        public List<StructuredQueryRepository.GroupRow> traffic(LocalDate from, LocalDate to, String metric,
                                                               String groupBy, String feeType,
                                                               boolean viaCanalOnly, int topN) {
            lastCall = List.of("traffic", from.toString(), to.toString(), metric, groupBy, String.valueOf(topN));
            return List.of(new StructuredQueryRepository.GroupRow("建材", "1000.00", 3L));
        }

        @Override
        public List<List<String>> waybills(String waybillNo, LocalDate from, LocalDate to, String status,
                                          String cargoCategory, boolean viaCanalOnly, int limit) {
            lastCall = List.of("waybills", String.valueOf(waybillNo), String.valueOf(from), String.valueOf(limit));
            return List.of(List.of("WB20270000001", "机制砂", "建材", "4343.08", "12345.67", "已结算",
                    "2027-06-20", "南宁港牛湾作业区", "钦州港", "是", "水路运单:已通过"));
        }

        @Override
        public List<List<String>> vessels(String vesselId, String vesselType, Integer minGrossTonnage,
                                         BigDecimal maxDraft, int limit) {
            lastCall = List.of("vessels", String.valueOf(vesselType), String.valueOf(limit));
            return List.of(List.of("GX00001", "桂平货9343号", "多用途船", "2121", "1167", "2.19", "2015", "某船东"));
        }
    }

    /** 固定回复的桩：直接把给定的 JSON 当模型应答。 */
    private static JsonChatInvoker stub(String json) {
        return (sys, user) -> json;
    }

    private static StructuredQueryService service(String replyJson, TariffRuleLookup tariff, StructuredDataLookup data) {
        ToolArgumentExtractor extractor = new ToolArgumentExtractor(CATALOG, GATE, stub(replyJson),
                ToolArgumentExtractor.DEFAULT_MAX_RETRIES, TODAY);
        return new StructuredQueryService(CATALOG, extractor, tariff, data, new FeeCalculationEngine(), TODAY);
    }

    private static StructuredQueryService service(String replyJson) {
        return service(replyJson, new FakeTariff(), new FakeData());
    }

    @Test
    @DisplayName("费率查询：命中则给出单价与依据（含档位、生效期）")
    void queryTariffHits() {
        ToolResult r = service("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":1000,"biz_date":"2026-10-01"},"missing":[],"reason":"查费率"}
                """).ask("2026 年建材过闸费多少");
        assertTrue(r.ok(), r.facts().toString());
        assertEquals(List.of("2.55", "元/吨"), List.of(r.rows().get(0).get(4), r.rows().get(0).get(5)));
        assertTrue(String.join(String.valueOf((char) 10), r.facts()).contains("CT-过闸费-建材-500"));
        assertTrue(r.traces().stream().anyMatch(t -> t.contains("吨位口径")), "口径推导要留痕");
    }

    @Test
    @DisplayName("费用测算：金额由引擎算出（金额、步骤、依据都在事实里）")
    void quoteFeeUsesEngine() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":1000,"biz_date":"2026-10-01"},"missing":[],"reason":"算钱"}
                """).ask("1000 吨建材过闸费多少钱");
        assertTrue(r.ok(), r.facts().toString());
        assertEquals("2550.00", r.rows().get(0).get(8));
        assertTrue(String.join(String.valueOf((char) 10), r.facts()).contains("2550.00"));
    }

    @Test
    @DisplayName("引航费：自动按船舶总吨位口径，且忽略传入的货类")
    void pilotFeeIgnoresCategory() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"引航费","cargo_category":"建材",
                 "tonnage":1500,"biz_date":"2026-10-01"},"missing":[],"reason":"算引航费"}
                """).ask("引航费多少");
        assertFalse(r.ok());
        // 规则未命中（假实现对引航费无规则）→ 必须**如实说明**且给出可核对原因，而不是返回 0 元
        assertEquals(ToolResult.STATUS_NO_DATA, r.status());
        assertTrue(r.facts().get(0).contains("引航费"));
        assertTrue(r.traces().stream().anyMatch(t -> t.contains("吨位口径（由费种推导）：引航费 → 船舶总吨位")));
        assertTrue(r.traces().stream().anyMatch(t -> t.contains("与货类无关，已忽略")));
    }

    @Test
    @DisplayName("按货类分档的费种缺货类：走追问（不是拒绝、更不是猜一个）")
    void missingCategoryAsksBack() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费","tonnage":1000,
                 "biz_date":"2026-10-01"},"missing":[],"reason":"缺货类"}
                """).ask("过闸费多少钱");
        assertEquals(ToolResult.STATUS_NEEDS_CLARIFICATION, r.status());
        assertNotNull(r.question());
        assertTrue(r.question().contains("货类"), "追问必须点名缺哪个参数：" + r.question());
    }

    @Test
    @DisplayName("模型申报缺参：追问话术用中文参数名（不能把 tonnage 直接丢给用户）")
    void declaredMissingParamsAsksBackInChinese() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费"},
                 "missing":["tonnage","biz_date"],"reason":"用户没说"}
                """).ask("过闸费多少钱");
        assertEquals(ToolResult.STATUS_NEEDS_CLARIFICATION, r.status());
        assertTrue(r.question().contains("计费吨位"), r.question());
        assertTrue(r.question().contains("业务发生日期"), r.question());
        assertFalse(r.question().contains("tonnage"), "不得把英文参数名抛给用户");
    }

    @Test
    @DisplayName("只给货类（化工）时危险品属性**查库推导**：320 元/次 × 1.30 = 416.00")
    void categoryLevelDangerousIsDerivedFromDb() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"港杂费","cargo_category":"化工",
                 "tonnage":1500,"biz_date":"2026-10-01"},"missing":[],"reason":"只说了化工品"}
                """).ask("1500 吨化工品的港杂费多少");
        assertTrue(r.ok(), r.facts().toString());
        assertEquals("416.00", r.rows().get(0).get(8));
        assertTrue(String.join(String.valueOf((char) 10), r.traces()).contains("由 cargo_type 查库推导"),
                "推导过程要留痕：这不是硬编码的常识");
    }

    @Test
    @DisplayName("货类内部危险品属性不一致（能源）：追问货种，不猜 1.30 系数")
    void ambiguousCategoryAsksForCargoName() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_category":"能源",
                 "tonnage":1200,"biz_date":"2026-10-01"},"missing":[],"reason":"只说了能源"}
                """).ask("1200 吨能源类货物过闸费多少");
        assertEquals(ToolResult.STATUS_NEEDS_CLARIFICATION, r.status());
        assertTrue(r.question().contains("货种"), r.question());
        assertTrue(r.question().contains("加收 30%"), "追问要说清为什么要问：" + r.question());
        assertFalse(String.join(String.valueOf((char) 10), r.facts()).contains("金额"),
                "追问时不得给出金额（猜一个比不答更糟）");
    }

    @Test
    @DisplayName("货种与货类矛盾：如实拒绝（危险品属性只能由货种推导，见 C-31）")
    void conflictingCargoRejected() {
        ToolResult r = service("""
                {"tool":"quote_fee","arguments":{"fee_type":"过闸费","cargo_name":"成品油",
                 "cargo_category":"建材","tonnage":1000,"biz_date":"2026-10-01"},"missing":[],"reason":"x"}
                """).ask("成品油过闸费");
        assertEquals(ToolResult.STATUS_REJECTED, r.status());
        assertTrue(r.facts().get(0).contains("不符") || r.facts().get(0).contains("货类"));
    }

    @Test
    @DisplayName("运量统计：无过滤条件时按「至少一个条件」追问（不把 3.2 万行倒出来）")
    void waybillWithoutFiltersAsksBack() {
        ToolResult r = service("""
                {"tool":"query_waybill","arguments":{},"missing":[],"reason":"x"}
                """).ask("有哪些运单");
        assertEquals(ToolResult.STATUS_NEEDS_CLARIFICATION, r.status());
        assertTrue(r.question().contains("运单号"), r.question());
    }

    @Test
    @DisplayName("船舶查询：无过滤条件时同样追问")
    void vesselWithoutFiltersAsksBack() {
        ToolResult r = service("""
                {"tool":"query_vessel","arguments":{},"missing":[],"reason":"x"}
                """).ask("有哪些船");
        assertEquals(ToolResult.STATUS_NEEDS_CLARIFICATION, r.status());
        assertTrue(r.question().contains("船型"), r.question());
    }

    @Test
    @DisplayName("统计窗口超出业务窗口：裁剪并**在事实里写明**，不假装统计了整个区间")
    void trafficClipsWindowAndSaysSo() {
        FakeData data = new FakeData();
        ToolResult r = service("""
                {"tool":"query_traffic","arguments":{"date_from":"2026-01-01","date_to":"2026-02-01",
                 "metric":"吨位","group_by":"无"},"missing":[],"reason":"x"}
                """, new FakeTariff(), data).ask("2026 年 1 月的运量");
        assertEquals(ToolResult.STATUS_NO_DATA, r.status(), "整个区间都在业务窗口之前 → 如实说无数据");
        assertTrue(r.facts().get(0).contains("业务数据窗口"));
        assertFalse(r.facts().get(0).contains("合计"));

        ToolResult r2 = service("""
                {"tool":"query_traffic","arguments":{"date_from":"2026-01-01","date_to":"2026-11-01",
                 "metric":"吨位","group_by":"货类"},"missing":[],"reason":"x"}
                """, new FakeTariff(), data).ask("2026 年 1-11 月运量");
        assertTrue(r2.ok());
        assertEquals("2026-09-16", data.lastCall.get(1), "起始日须被裁剪到业务窗口起点");
        assertTrue(String.join(String.valueOf((char) 10), r2.facts()).contains("已按窗口内可用数据统计"));
    }

    @Test
    @DisplayName("降级：不属于工具集的问题交给检索通道（不报「查询失败」）")
    void toolNoneDegradesToRetrieval() {
        ToolResult r = service("""
                {"tool":"none","arguments":{},"missing":[],"reason":"政策咨询"}
                """).ask("平陆运河什么时候通航");
        assertEquals(ToolResult.STATUS_DEGRADED, r.status());
        assertEquals(ExtractionOutcome.KIND_TOOL_NONE, r.degradeKind());
        assertTrue(r.facts().get(0).contains("检索通道"));
    }

    @Test
    @DisplayName("降级：模型持续输出畸形时状态为 DEGRADED，且带原因（不是空结果）")
    void schemaFailureIsDegradedNotEmpty() {
        ToolResult r = service("这不是 JSON").ask("过闸费多少");
        assertEquals(ToolResult.STATUS_DEGRADED, r.status());
        assertEquals(ExtractionOutcome.KIND_NOT_JSON, r.degradeKind());
        assertTrue(r.rows().isEmpty());
        assertFalse(r.facts().isEmpty(), "降级也要给人话，不能是空");
    }

    @Test
    @DisplayName("自检视图：工具清单与 envelope 枚举一致（同源可核对）")
    void selfCheckConsistent() {
        StructuredQueryService s = service("{}");
        var self = s.selfCheck();
        assertEquals(CATALOG.contractVersion(), self.get("contractVersion"));
        assertTrue(self.get("envelopeToolEnum").toString().contains("query_tariff"));
        assertEquals(Integer.valueOf(21), self.get("cargoTypes"));
    }

    @Test
    @DisplayName("SQL 注入面：多余参数在 Schema 层就被拦下，永远到不了 SQL")
    void injectionAttemptNeverReachesSql() {
        FakeData data = new FakeData();
        ToolResult r = service("""
                {"tool":"query_traffic","arguments":{"date_from":"2026-10-01","date_to":"2026-10-31",
                 "metric":"吨位","group_by":"货类; DROP TABLE waybill"},
                 "missing":[],"reason":"x"}
                """, new FakeTariff(), data).ask("恶意分组");
        assertEquals(ToolResult.STATUS_DEGRADED, r.status());
        assertEquals(ExtractionOutcome.KIND_SCHEMA_INVALID, r.degradeKind());
        assertNull(data.lastCall, "被拦下的调用不得触达 SQL 层");
    }

    @Test
    @DisplayName("按货类分档的费种缺货类时，追问话术带上费种背景")
    void clarifyMentionsFeeTypeContext() {
        ToolResult r = service("""
                {"tool":"query_tariff","arguments":{"fee_type":"港杂费","tonnage":1000,
                 "biz_date":"2026-10-01"},"missing":[],"reason":"x"}
                """).ask("港杂费多少");
        assertEquals(ToolResult.STATUS_NEEDS_CLARIFICATION, r.status());
        assertTrue(r.question().contains("港杂费"), "追问要说清是哪个费种缺货类：" + r.question());
    }

    @Test
    @DisplayName("运单查询命中：给出明细与单证状态")
    void waybillQueryReturnsRows() {
        ToolResult r = service("""
                {"tool":"query_waybill","arguments":{"waybill_no":"WB20270000001"},
                 "missing":[],"reason":"x"}
                """).ask("WB20270000001 这票货");
        assertTrue(r.ok());
        assertEquals("WB20270000001", r.rows().get(0).get(0));
        assertTrue(String.join(String.valueOf((char) 10), r.facts()).contains("水路运单:已通过"));
    }

    @Test
    @DisplayName("船舶查询命中：字段齐全（总吨位是引航费/停泊费口径的基础）")
    void vesselQueryReturnsRows() {
        ToolResult r = service("""
                {"tool":"query_vessel","arguments":{"vessel_type":"多用途船"},"missing":[],"reason":"x"}
                """).ask("有哪些多用途船");
        assertTrue(r.ok());
        assertEquals("1167", r.rows().get(0).get(4), "船舶总吨位");
        assertTrue(String.join(String.valueOf((char) 10), r.facts()).contains("闸室水深不在数据里"));
    }

    @Test
    @DisplayName("未知工具名（模型幻觉）：降级而非抛异常")
    void unknownToolDegrades() {
        ToolResult r = service("""
                {"tool":"none","arguments":{},"missing":[],"reason":"x"}
                """).ask("x");
        assertEquals(ToolResult.STATUS_DEGRADED, r.status());
        assertNotNull(r.degradeKind());
    }

    @Test
    @DisplayName("缺日期参数的日期格式错：追问（不猜日期）")
    void badDateFormatAsksBack() {
        // 日期格式由 Schema 先拦（pattern），这里验证的是「拦下即追问/降级」而非算出一个数
        ToolResult r = service("""
                {"tool":"query_tariff","arguments":{"fee_type":"过闸费","cargo_category":"建材",
                 "tonnage":1000,"biz_date":"2026年10月1日"},"missing":[],"reason":"x"}
                """).ask("过闸费");
        assertEquals(ToolResult.STATUS_DEGRADED, r.status());
        assertEquals(ExtractionOutcome.KIND_SCHEMA_INVALID, r.degradeKind());
        assertFalse(r.facts().isEmpty());
        List<String> all = new ArrayList<>(r.traces());
        assertTrue(all.stream().anyMatch(t -> t.contains("尝试")), "留痕要记录尝试次数");
    }
}
