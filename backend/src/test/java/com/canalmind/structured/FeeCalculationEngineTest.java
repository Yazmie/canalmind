package com.canalmind.structured;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 费用计算引擎的测试（任务 3：纯代码算金额）。
 *
 * <p>口径来源逐条锚定语料，且**金额全部手算**——手算值写在断言里，
 * 再与库内抽样（{@code StructuredQueryCli --verify-engine-vs-db}）相互独立地印证。
 */
class FeeCalculationEngineTest {

    private final FeeCalculationEngine engine = new FeeCalculationEngine();

    private static TariffRuleRow rule(String feeType, String category, String basis, String minTon,
                                     String maxTon, String price, String unit) {
        return new TariffRuleRow(1L, "CT-" + feeType + "-" + category + "-" + minTon, feeType, category, basis,
                new BigDecimal(minTon), maxTon == null ? null : new BigDecimal(maxTon),
                new BigDecimal(price), unit, LocalDate.of(2026, 9, 16), LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("元/吨：吨位 × 单价（过闸费 建材 1000 吨 × 2.55 = 2550.00）")
    void perTonFee() {
        var q = engine.quote(rule("过闸费", "建材", "载货吨位", "500", "2000", "2.55", "元/吨"),
                new BigDecimal("1000"), 1, false, LocalDate.of(2026, 10, 1));
        assertEquals("2550.00", q.amount().toPlainString());
        assertEquals("2550.00", q.baseAmount().toPlainString());
        assertFalse(q.reliefApplied());
        assertFalse(q.surchargeApplied());
        assertEquals("吨位 × 单价", q.formula());
    }

    @Test
    @DisplayName("重点保供 0.70 × 危险货物 1.30 复合：1325.37 × 2.90 = 3843.573 → ×0.91 = 3497.65")
    void reliefAndSurchargeCombine() {
        var q = engine.quote(rule("过闸费", "能源", "载货吨位", "500", "2000", "2.90", "元/吨"),
                new BigDecimal("1325.37"), 1, true, LocalDate.of(2026, 10, 1));
        assertEquals("3497.65", q.amount().toPlainString());
        assertTrue(q.reliefApplied(), "能源属重点保供（语料 06-第十三条）");
        assertTrue(q.surchargeApplied(), "成品油属危险货物（语料 06-第十四条）");
        assertEquals("0.70", q.reliefFactor().toPlainString());
        assertEquals("1.30", q.surchargeFactor().toPlainString());
    }

    @Test
    @DisplayName("危险品加收只对过闸费/港杂费生效（引航费不受影响）")
    void surchargeOnlyForTwoFeeTypes() {
        var pilot = engine.quote(rule("引航费", "不区分", "船舶总吨位", "1000", "5000", "1500.00", "元/次"),
                new BigDecimal("2000"), 1, true, LocalDate.of(2027, 3, 1));
        assertEquals("1500.00", pilot.amount().toPlainString());
        assertFalse(pilot.surchargeApplied(), "引航费不加收（语料 08-第二条 与货类无关）");

        var port = engine.quote(rule("港杂费", "化工", "载货吨位", "1000", "5000", "320.00", "元/次"),
                new BigDecimal("1500"), 1, true, LocalDate.of(2026, 10, 1));
        assertEquals("416.00", port.amount().toPlainString(), "320 × 1.30 = 416.00");
        assertTrue(port.surchargeApplied());
    }

    @Test
    @DisplayName("重点保供折扣只对过闸费生效（装卸费不打折）")
    void reliefOnlyForGateFee() {
        var load = engine.quote(rule("装卸费", "能源", "载货吨位", "0", "2000", "9.00", "元/吨"),
                new BigDecimal("1000"), 1, false, LocalDate.of(2026, 10, 1));
        assertEquals("9000.00", load.amount().toPlainString());
        assertFalse(load.reliefApplied());
    }

    @Test
    @DisplayName("元/次：固定单次费率，与吨位无关（港杂费 建材 320）")
    void perTimeFeeIgnoresTonnage() {
        var a = engine.quote(rule("港杂费", "建材", "载货吨位", "1000", "5000", "320.00", "元/次"),
                new BigDecimal("1000"), 1, false, LocalDate.of(2026, 10, 1));
        var b = engine.quote(rule("港杂费", "建材", "载货吨位", "1000", "5000", "320.00", "元/次"),
                new BigDecimal("4999.99"), 1, false, LocalDate.of(2026, 10, 1));
        assertEquals("320.00", a.amount().toPlainString());
        assertEquals(a.amount().toPlainString(), b.amount().toPlainString(), "元/次与吨位无关");
    }

    @Test
    @DisplayName("元/吨天：吨位 × 单价 × 天数（停泊费 2000 × 0.12 × 3 = 720.00）")
    void perTonDayFee() {
        var q = engine.quote(rule("停泊费", "不区分", "船舶总吨位", "1000", "5000", "0.12", "元/吨天"),
                new BigDecimal("2000"), 3, false, LocalDate.of(2027, 3, 1));
        assertEquals("720.00", q.amount().toPlainString());
        assertEquals(3, q.days());
        assertEquals("吨位 × 单价 × 天数", q.formula());
    }

    @Test
    @DisplayName("非正天数按 1 天计（停泊费：不会算出 0 元）")
    void nonPositiveDaysFallBackToOne() {
        var q = engine.quote(rule("停泊费", "不区分", "船舶总吨位", "1000", "5000", "0.12", "元/吨天"),
                new BigDecimal("2000"), 0, false, LocalDate.of(2027, 3, 1));
        assertEquals("240.00", q.amount().toPlainString());
        assertEquals(1, q.days());
    }

    @Test
    @DisplayName("四舍五入到分（HALF_UP，不是银行家舍入）：22.95 × 0.70 = 16.065 → 16.07")
    void roundsHalfUp() {
        // ⚠️ 这个用例专门守舍入口径：HALF_EVEN 会给 16.06，与语料"四舍五入"及 PostgreSQL round() 都不符
        var q = engine.quote(rule("过闸费", "能源", "载货吨位", "0", "1000", "2.55", "元/吨"),
                new BigDecimal("9"), 1, false, LocalDate.of(2026, 10, 1));
        assertEquals("16.07", q.amount().toPlainString(), "必须是 HALF_UP 到分");
    }

    @Test
    @DisplayName("档位左闭右开：500 属 [500,2000)，2000 不属")
    void bandIsLeftClosedRightOpen() {
        TariffRuleRow r = rule("过闸费", "建材", "载货吨位", "500", "2000", "2.55", "元/吨");
        assertTrue(r.covers(new BigDecimal("500")));
        assertTrue(r.covers(new BigDecimal("1999.99")));
        assertFalse(r.covers(new BigDecimal("2000")), "右开：2000 属下一档");
        assertFalse(r.covers(new BigDecimal("499.99")));
        assertEquals("[500, 2000)", r.bandText());
        // 末档不封顶
        TariffRuleRow last = rule("过闸费", "建材", "载货吨位", "5000", null, "3.65", "元/吨");
        assertTrue(last.covers(new BigDecimal("999999")));
        assertEquals("[5000, ∞)", last.bandText());
    }

    @Test
    @DisplayName("非法输入必须抛错，绝不返回一个「看起来合理」的数")
    void rejectsInvalidInputs() {
        TariffRuleRow r = rule("过闸费", "建材", "载货吨位", "500", "2000", "2.55", "元/吨");
        assertThrows(IllegalArgumentException.class,
                () -> engine.quote(r, new BigDecimal("100"), 1, false, LocalDate.now()), "吨位不在档位内");
        assertThrows(IllegalArgumentException.class,
                () -> engine.quote(r, BigDecimal.ZERO, 1, false, LocalDate.now()), "吨位为 0");
        assertThrows(IllegalArgumentException.class,
                () -> engine.quote(r, new BigDecimal("-1"), 1, false, LocalDate.now()), "吨位为负");
        assertThrows(IllegalArgumentException.class,
                () -> engine.quote(null, new BigDecimal("1000"), 1, false, LocalDate.now()), "规则为空");
        assertThrows(IllegalArgumentException.class,
                () -> engine.quote(rule("过闸费", "建材", "载货吨位", "500", "2000", "2.55", "元/船"),
                        new BigDecimal("1000"), 1, false, LocalDate.now()), "未知计费单位");
    }

    @Test
    @DisplayName("每次测算都留下可核对步骤（金额不能只存结果）")
    void quoteCarriesTraceableSteps() {
        var q = engine.quote(rule("过闸费", "化工", "载货吨位", "0", "1000", "3.00", "元/吨"),
                new BigDecimal("800"), 1, true, LocalDate.of(2026, 10, 1));
        String steps = String.join(String.valueOf((char) 10), q.steps());
        assertTrue(steps.contains("命中规则 CT-过闸费-化工-0"));
        assertTrue(steps.contains("70%"), "步骤须写明重点保供折扣依据");
        assertTrue(steps.contains("30%"), "步骤须写明危险货物加收依据");
        assertTrue(steps.contains("四舍五入到分"));
        assertEquals("2184.00", q.amount().toPlainString(), "800×3=2400 → ×0.7=1680 → ×1.3=2184.00");
    }

    @Test
    @DisplayName("未实现条款逐条登记（C-36：不得静默略过）")
    void unimplementedClausesAreRegistered() {
        List<String> clauses = FeeCalculationEngine.UNIMPLEMENTED_CLAUSES;
        assertTrue(clauses.size() >= 5);
        assertTrue(clauses.stream().anyMatch(s -> s.contains("最低计费吨位")), "「核定最低计费吨位」须在列");
        assertTrue(clauses.stream().allMatch(s -> s.contains("：")), "每条都要带锚点或原因（不得只写一句话）");
    }
}
