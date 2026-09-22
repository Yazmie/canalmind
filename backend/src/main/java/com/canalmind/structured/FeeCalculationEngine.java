package com.canalmind.structured;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 费用计算引擎（任务 3：**纯代码，模型只抽参数**）。
 *
 * <p><b>为什么金额必须由代码算</b>：语料里费率是"分档 × 加减免 × 按天"的组合，
 * 让模型做这串算术等于把可审计的金额交给一个概率模型；而且金额要能和
 * 数据库里的 {@code fee_record} **逐分对账**（Execution Accuracy 的 oracle 就是数据库执行结果）。
 *
 * <p><b>与库侧生成器的口径必须一字不差</b>（否则同题两通道给不同答案，正是 C-31 修掉的那类问题）。
 * 本类照抄 {@code backend/scripts/generate_seed_data.py} 的 {@code _fee_amount} + 减免/附加 +
 * {@code ROUND_HALF_UP} 到分，锚点如下：
 * <ul>
 *   <li>计费单位三型（元/吨、元/次、元/吨天）：语料 {@code 25-第二十七条}</li>
 *   <li>吨位分档左闭右开：语料 {@code 25-第二十九条}</li>
 *   <li>危险货物过闸费/港杂费加收 30%：语料 {@code 06-第十四条} / {@code 07-第十二条} / {@code 05-第十一条}</li>
 *   <li>重点保供（能源/农资/化工）过闸费按 70%：语料 {@code 06-第十三条}</li>
 * </ul>
 *
 * <p>⚠️ <b>未实现的语料条款在这里点名，不静默略过</b>（决策 C-36）：
 * 「核定最低计费吨位」全语料无定义；引航中止 50%（{@code 08-第六条}）、
 * 自备设备装卸半价（{@code 09-第九条 2}）、装/卸各计一次（{@code 09-第八条 2}）在库内
 * 无对应字段、不可触发。故本引擎**只实现可验证的部分**，未实现项登记在
 * {@link #UNIMPLEMENTED_CLAUSES} 与《评测报告》。
 */
public final class FeeCalculationEngine {

    /** 金额精度：到分（语料"四舍五入到分"）。 */
    public static final int MONEY_SCALE = 2;

    public static final String UNIT_PER_TON = "元/吨";
    public static final String UNIT_PER_TIME = "元/次";
    public static final String UNIT_PER_TON_DAY = "元/吨天";

    /** 重点保供物资货类（语料 `06-第十三条`：能源、农资、化工）。 */
    public static final Set<String> RELIEF_CATEGORIES = Set.of("能源", "农资", "化工");
    public static final Set<String> RELIEF_FEE_TYPES = Set.of("过闸费");
    public static final BigDecimal RELIEF_FACTOR = new BigDecimal("0.70");

    /** 危险货物加收 30%（语料 `06-第十四条` / `09-第六条`）。 */
    public static final Set<String> SURCHARGE_FEE_TYPES = Set.of("过闸费", "港杂费");
    public static final BigDecimal SURCHARGE_FACTOR = new BigDecimal("1.30");

    public static final BigDecimal ONE = new BigDecimal("1.00");

    /** 停泊费按天，其余费种与天数无关。 */
    public static final String PER_DAY_FEE_TYPE = "停泊费";

    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.fee.calculate}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    /**
     * 默认构造（**不埋点**）：委托给 {@link ObservationRegistry#NOOP}。
     *
     * <p>⚠️ 本类原先**没有**任何显式构造器（用隐式默认构造器），
     * 故这里补一个显式 no-arg 保持既有调用点（{@code StructuredQueryCli}、
     * {@code StructuredQueryServiceTest}、{@code FeeCalculationEngineTest}、
     * {@code QuestionPipeline.create}）**零变化**。
     */
    public FeeCalculationEngine() {
        this(ObservationRegistry.NOOP);
    }

    /**
     * 带上观测注册表（规格书 §7.2 第 561 行的 {@code ai.fee.calculate}）。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public FeeCalculationEngine(ObservationRegistry observationRegistry) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    /** 未实现条款清单（逐条附锚点与原因）——对外必须如实说明。 */
    public static final List<String> UNIMPLEMENTED_CLAUSES = List.of(
            "核定最低计费吨位（06-第十五条 2 / 07-第十三条 2）：全语料未给出核定方法，无法实现",
            "引航中止按 50% 计收（08-第六条）：库内无「中止」状态字段，不可触发",
            "自备装卸设备按半价（09-第九条 2）：库内无「是否自备设备」字段，不可触发",
            "装、卸各计一次装卸费（09-第八条 2）：fee_record 无作业类型列，本数据集每运单记 1 次",
            "各免收情形（如公务/抢险船舶）：库内无对应船舶属性，不可触发",
            "堆存与仓储费率（20-第十一/十二条）：无对应数据表，不计费");

    /**
     * 一次测算的完整结果（**每一步都可核对**，与 `fee_record.calc_basis` 同构）。
     *
     * @param baseAmount 分档单价算出的基础金额（未施加减免/附加）
     * @param amount     最终金额（= baseAmount × reliefFactor × surchargeFactor，四舍五入到分）
     */
    public record Quote(BigDecimal amount, BigDecimal baseAmount, String formula,
                        BigDecimal unitPrice, String unit, BigDecimal tonnage, String tonnageBasis,
                        int days, String cargoCategory, boolean dangerous,
                        boolean reliefApplied, boolean surchargeApplied,
                        BigDecimal reliefFactor, BigDecimal surchargeFactor,
                        String ruleCode, String bandText, String periodText,
                        LocalDate bizDate, List<String> steps) {

        /** 供回答直接引用的一行式说明（金额口径与依据）。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(tonnage.stripTrailingZeros().toPlainString()).append("（").append(tonnageBasis).append("）");
            sb.append(" × ").append(unitPrice.stripTrailingZeros().toPlainString()).append(' ').append(unit);
            if (days > 1) {
                sb.append(" × ").append(days).append(" 天");
            }
            if (reliefApplied) {
                sb.append(" × 0.70（重点保供）");
            }
            if (surchargeApplied) {
                sb.append(" × 1.30（危险货物加收）");
            }
            sb.append(" = ").append(amount.toPlainString()).append(" 元");
            return sb.toString();
        }
    }

    /**
     * 按规则测算金额（规格书 §7.2 第 561 行的 {@code ai.fee.calculate}）。
     *
     * <p>⚠️ 这里**只记"成功/失败"一个低基数键**（{@code ai.rag.result}），⛔ <b>绝不把金额
     * 写成属性</b>：金额是**高基数业务数据**——它随每一题、每个吨位无界增长，
     * 进 Metrics 会让时间序列爆炸（知识库第 202、531–533 行），进 Trace 也只是把
     * 一个本该在 {@code ToolResult.facts()} / 数据库 oracle 里对账的数抄到日志中。
     * 金额的归宿是**产物与 oracle 对账**（Execution Accuracy），不是 Span 属性。
     *
     * @param rule        命中的费率规则（**必须已按业务日与吨位口径命中**，本方法不再筛档）
     * @param tonnage     计费吨位（口径由 {@code rule.tonnageBasis()} 决定，调用方负责传对）
     * @param days        计费天数（仅停泊费有意义，其余费种忽略）
     * @param dangerous   是否危险货物（由货种推导，**不让模型判断**）
     * @param bizDate     业务发生日（写进结果，便于对账与复现）
     * @return 计算结果；失败时**抛异常**（见类注释"宁可抛，也不要算出一个看起来合理的数"）
     */
    public Quote quote(TariffRuleRow rule, BigDecimal tonnage, int days, boolean dangerous,
                       LocalDate bizDate) {
        Observation observation = AiObservation.start(AiSpans.FEE_CALCULATE, observationRegistry);
        return observation.observe(() -> {
            Quote quote = computeQuote(rule, tonnage, days, dangerous, bizDate);
            // ⛔ 这里**刻意不写** ai.rag.result（C-59，2026-09-22 裁示删除）：
            //    本引擎失败时**抛异常**而不是返回 null，所以那个键的取值**恒为 hit**。
            //    一个只能取一个值的键不是信息、是噪声——它既不可聚合，又会让读者以为
            //    "这里也有空召回的可能"。失败本身已由 observe(...) 记成这个 Span 的
            //    **error 状态**（并带上原始异常），⛔ 不需要再造一个假的两值枚举。
            return quote;
        });
    }

    /**
     * 真正的计算（原 {@code quote} 的实现，逐行未改）。
     *
     * <p>与埋点分成两个方法，是为了让"埋点没有动过任何算术或口径"这件事一眼可核对。
     */
    private Quote computeQuote(TariffRuleRow rule, BigDecimal tonnage, int days, boolean dangerous,
                               LocalDate bizDate) {
        if (rule == null) {
            throw new IllegalArgumentException("费率规则不得为空");
        }
        if (tonnage == null || tonnage.signum() <= 0) {
            throw new IllegalArgumentException("计费吨位必须为正数，实际 " + tonnage);
        }
        int effectiveDays = Math.max(1, days);
        if (!rule.covers(tonnage)) {
            // 档位不匹配属调用方错误：宁可抛，也不要算出一个"看起来合理"的数
            throw new IllegalArgumentException("吨位 " + tonnage + " 不在规则 " + rule.ruleCode()
                    + " 的档位 " + rule.bandText() + " 内");
        }

        BigDecimal base;
        String formula;
        if (UNIT_PER_TON.equals(rule.unit())) {
            base = tonnage.multiply(rule.unitPrice());
            formula = "吨位 × 单价";
        } else if (UNIT_PER_TIME.equals(rule.unit())) {
            base = rule.unitPrice();
            formula = "固定单次费率";
        } else if (UNIT_PER_TON_DAY.equals(rule.unit())) {
            base = tonnage.multiply(rule.unitPrice()).multiply(BigDecimal.valueOf(effectiveDays));
            formula = "吨位 × 单价 × 天数";
        } else {
            throw new IllegalArgumentException("未知计费单位：" + rule.unit()
                    + "（语料 25-第二十七条 只定义 元/吨、元/次、元/吨天 三种）");
        }

        boolean relief = RELIEF_FEE_TYPES.contains(rule.feeType())
                && RELIEF_CATEGORIES.contains(rule.cargoCategory());
        boolean surcharge = SURCHARGE_FEE_TYPES.contains(rule.feeType()) && dangerous;
        BigDecimal reliefFactor = relief ? RELIEF_FACTOR : ONE;
        BigDecimal surchargeFactor = surcharge ? SURCHARGE_FACTOR : ONE;

        // ⚠️ 只在**最后**做一次四舍五入：中间四舍五入会与库侧生成器产生分位差
        BigDecimal amount = base.multiply(reliefFactor).multiply(surchargeFactor)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        List<String> steps = new ArrayList<>();
        steps.add("命中规则 " + rule.ruleCode() + "（" + rule.feeType() + " / " + rule.cargoCategory()
                + " / " + rule.tonnageBasis() + " / 档位 " + rule.bandText() + "）");
        steps.add("单价 " + rule.unitPrice().stripTrailingZeros().toPlainString() + " " + rule.unit()
                + "，生效期 " + rule.periodText());
        steps.add("基础金额 = " + formula + " = " + base.stripTrailingZeros().toPlainString() + " 元");
        if (relief) {
            steps.add("重点保供物资（" + rule.cargoCategory() + "）过闸费按 70% 计收 → ×0.70");
        }
        if (surcharge) {
            steps.add("危险货物按对应费率加收 30% → ×1.30");
        }
        steps.add("最终金额 = 基础金额 × " + reliefFactor.toPlainString() + " × "
                + surchargeFactor.toPlainString() + " = " + amount.toPlainString() + " 元（四舍五入到分）");

        return new Quote(amount, base, formula, rule.unitPrice(), rule.unit(), tonnage,
                rule.tonnageBasis(), effectiveDays, rule.cargoCategory(), dangerous,
                relief, surcharge, reliefFactor, surchargeFactor, rule.ruleCode(),
                rule.bandText(), rule.periodText(), bizDate, List.copyOf(steps));
    }
}