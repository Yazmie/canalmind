package com.canalmind.structured;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code tariff_rule} 的一行（费率规则）。
 *
 * <p>⚠️ {@code maxTon == null} 表示**不封顶末档**（语料 `25-第二十九条`），
 * {@code effectiveTo == null} 表示长期有效——两者都**不等于 0 或"无值"**，
 * 故用 {@link BigDecimal} / {@link LocalDate} 的可空形式而不是哨兵数字。
 *
 * @param tonnageBasis 计费吨位口径：{@code 载货吨位} 或 {@code 船舶总吨位}（决策 C-31）
 */
public record TariffRuleRow(long id, String ruleCode, String feeType, String cargoCategory,
                            String tonnageBasis, BigDecimal minTon, BigDecimal maxTon,
                            BigDecimal unitPrice, String unit,
                            LocalDate effectiveFrom, LocalDate effectiveTo) {

    /** 语料 `25-第十二条` 的两套吨位口径。 */
    public static final String BASIS_CARGO_WEIGHT = "载货吨位";
    public static final String BASIS_GROSS_TONNAGE = "船舶总吨位";

    /** 引航费/停泊费与货类无关，落库为哨兵值 `不区分`（决策 C-31）。 */
    public static final String NO_CATEGORY = "不区分";

    public TariffRuleRow {
        if (id <= 0 || ruleCode == null || feeType == null || unitPrice == null || unit == null) {
            throw new IllegalArgumentException("费率规则行字段不完整：" + ruleCode);
        }
        if (tonnageBasis == null || tonnageBasis.isBlank()) {
            throw new IllegalArgumentException("费率规则缺 tonnage_basis（C-31 起为必填）：" + ruleCode);
        }
    }

    /** 档位区间是否包含该吨位：**左闭右开** [minTon, maxTon)（语料 `25-第二十九条`）。 */
    public boolean covers(BigDecimal tonnage) {
        if (tonnage == null || tonnage.signum() <= 0) {
            return false;
        }
        if (tonnage.compareTo(minTon) < 0) {
            return false;
        }
        return maxTon == null || tonnage.compareTo(maxTon) < 0;
    }

    /** 档位文本（给回答用），如 {@code [500, 2000)}、{@code [5000, ∞)}。 */
    public String bandText() {
        return "[" + plain(minTon) + ", " + (maxTon == null ? "∞" : plain(maxTon)) + ")";
    }

    /** 生效期文本，如 {@code 2026-09-16 ~ 2027-01-01（不含）}。 */
    public String periodText() {
        return effectiveFrom + " ~ " + (effectiveTo == null ? "长期有效" : effectiveTo + "（终止日不含）");
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
