package com.canalmind.structured;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「「5000 **米**」被当成吨位」这条守卫的**单元级**测试（缺口③）。
 *
 * <p>为什么单独测它：这条守卫的**误判代价很高**——误判会把"问句里本来就有长度"的正常提问
 * （如"吃水 5 米的船装 5000 吨货"）拦成追问。故这里把三条判定规则逐条钉住，
 * 并把**反例**（必须不触发）与正例放在一起。
 *
 * <p>⛔ 本类纯函数、零外部依赖：不连库、不打模型。
 */
class TonnageUnitGuardTest {

    private static final BigDecimal T5000 = new BigDecimal("5000");

    @Test
    @DisplayName("正例：`L6-006` 原句「机制砂 5000 米」⇒ 命中，且说明里点出长度/吨")
    void detectsLengthUnitMistake() {
        String why = TonnageUnitGuard.explainIfLengthUnitMistake(
                "有票机制砂 5000 米，2027 年 5 月 5 号过闸，过闸费帮我算下。", T5000);
        assertNotNull(why, "「5000 米」应与 tonnage=5000 判为同一来源");
        assertTrue(why.contains("长度"), why);
        assertTrue(why.contains("吨"), why);
    }

    @Test
    @DisplayName("正例：紧写（无空格）与其它长度单位同样命中")
    void detectsWithoutSpacesAndOtherUnits() {
        assertNotNull(TonnageUnitGuard.explainIfLengthUnitMistake("货长 3000米，运一趟。", new BigDecimal("3000")));
        assertNotNull(TonnageUnitGuard.explainIfLengthUnitMistake("航道 12 公里。", new BigDecimal("12")));
    }

    @Test
    @DisplayName("反例①：数字与吨位**不等** ⇒ 不判（问句里的长度属于别的量）")
    void doesNotTriggerWhenValuesDiffer() {
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake("船长 200 米，载货 3000 吨。", new BigDecimal("3000")));
    }

    @Test
    @DisplayName("反例②：同句已给出**同值吨位** ⇒ 不判（吨位有正当来源）")
    void doesNotTriggerWhenTonnesMentioned() {
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake(
                "吃水 5 米的船，装 5000 吨机制砂，过闸费多少？", T5000));
    }

    @Test
    @DisplayName("反例③：面积/体积单位不是长度 ⇒ 不匹配（5000 立方米）")
    void doesNotTriggerOnVolumeUnit() {
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake("5000 立方米机制砂。", T5000));
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake("堆场 5000 平方米。", T5000));
    }

    @Test
    @DisplayName("反例④：问句为空/吨位缺失 ⇒ 一律不判（不猜）")
    void doesNotTriggerOnBlankInput() {
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake(null, T5000));
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake("   ", T5000));
        assertNull(TonnageUnitGuard.explainIfLengthUnitMistake("机制砂 5000 米。", null));
    }

    @Test
    @DisplayName("「厘米」不会被「米」抢先匹配（单位表次序敏感）")
    void centimeterIsMatchedAsCentimeter() {
        String why = TonnageUnitGuard.explainIfLengthUnitMistake("货物尺寸 5000 厘米。", T5000);
        assertNotNull(why, why);
        assertTrue(why.contains("厘米"), "必须按「厘米」报出，不能截成「米」：" + why);
    }
}
