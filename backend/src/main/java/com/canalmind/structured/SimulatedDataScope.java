package com.canalmind.structured;

import java.math.BigDecimal;

/**
 * **仿真业务数据的量级边界**（决策 D24 → 缺口④：5 万吨必须提示"远超仿真数据量级"）。
 *
 * <p>本类只回答一个问题：<b>这个计费吨位还在本项目仿真数据的量级之内吗？</b>
 * ⛔ 它**不**判断费率对不对、也**不**参与算钱——量级之外的请求由服务层判 {@code DEGRADED}，
 * 意思是"这一路不给你确定金额"，而不是"你的问题不合法"。
 *
 * <h2>⚠️ 两个边界值**全部来自数据与文档**，⛔ 没有一个是拍脑袋的魔数</h2>
 *
 * <table>
 *   <tr><th>常量</th><th>值</th><th>出处（可核对）</th></tr>
 *   <tr>
 *     <td>{@link #CARGO_WEIGHT_CEILING_TON}</td><td>5000 吨</td>
 *     <td>{@code docs/数据模型-ER图.md} §2.1（「同理过闸费/港杂费的 <b>[5000, ∞) 档</b>也未触发
 *         （<b>运单货重恒 < 5,000 吨</b>）」）＋ 种子数据生成器
 *         {@code backend/scripts/generate_seed_data.py} 的货重分布
 *         {@code weight = uniform(200,1000) / uniform(1000,3000) / uniform(3000,5000)}（恒 < 5000）
 *         ＋ 数据集 {@code docs/评测报告/golden-set-v3.json} 的 {@code L6-007} anchor/rubric
 *         （「5 万吨<b>远超</b>本项目仿真业务数据量级（运单货重恒小于 5000 吨），该量级属口径外」）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #GROSS_TONNAGE_CEILING_TON}</td><td>3017 吨</td>
 *     <td>{@code docs/数据模型-ER图.md} §2.1（「本项目<b>船队载重吨 800–5,500 ⇒ 总吨位 501–3,017</b>，
 *         因此引航费/停泊费的 <b>[5000, 10000) 与 [10000, ∞) 两档没有任何仿真数据触发</b>」）；
 *         交叉核对：{@code generate_seed_data.py} 的 {@code VESSEL_DWT_RANGE} 上界 5500
 *         × {@code GROSS_TON_FACTOR} 0.55 = 3025（与该文档口径同量级）</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ {@link #FAR_EXCEEDS_FACTOR}（=10）是"本层对『远超』的可复核定义"，⛔ 不是数据里的常数</h2>
 *
 * <p>为什么不能把边界直接定在 5000 吨：5000 吨是**数据覆盖面的边界**，不是**合法性的边界**。
 * 费率表本身有 {@code [5000, ∞)} 档（末档不封顶，语料 25-第二十九条），数据集里
 * {@code L1-003/005/014/016} 就是拿 <b>6000 吨</b>问费率与金额、{@code L5-017} 更是拿
 * <b>正好 5000 吨</b>问"算哪一档"（期望正常作答）——把边界定在 5000 会把这些**合法题**一并判掉。
 *
 * <p>而 {@code L6-007} 的用词是「**远超**」而不是「超出」⇒ 本层把"量级差到**一个数量级**
 * （≥ 上界的 10 倍）"定义为口径外：{@code 5000 × 10 = 50000} 吨（正好是 5 万吨）、
 * {@code 3017 × 10 = 30170} 吨。⚠️ 这个倍数是**本类的定义选择**，故：
 * ①写成具名常量以便复核与调整；②判定结果里**如实写出边界与倍数**，
 * 让读的人一眼看出这是"量级提示"而不是"规则拒绝"。
 */
public final class SimulatedDataScope {

    /** 载货吨位（货重）口径的仿真数据上界：**运单货重恒 < 5000 吨**。出处见类注释表格。 */
    public static final BigDecimal CARGO_WEIGHT_CEILING_TON = new BigDecimal("5000");

    /** 船舶总吨位口径的仿真数据上界：**船队总吨位实测 501–3,017**。出处见类注释表格。 */
    public static final BigDecimal GROSS_TONNAGE_CEILING_TON = new BigDecimal("3017");

    /**
     * 「远超」的判定倍数：上界的 **10 倍**（一个数量级）。
     *
     * <p>⚠️ 本值是**定义**而非数据常数（理由见类注释），刻意做成具名常量：要调整只需改这一处，
     * 且判定文案里会把倍数一并说出来（⛔ 不写"神秘阈值"）。
     */
    public static final int FAR_EXCEEDS_FACTOR = 10;

    /** 载货吨位口径的"口径外"起点：{@code 5000 × 10 = 50000} 吨（即 5 万吨）。 */
    public static final BigDecimal CARGO_WEIGHT_OUT_OF_SCOPE_TON =
            CARGO_WEIGHT_CEILING_TON.multiply(BigDecimal.valueOf(FAR_EXCEEDS_FACTOR));

    /** 船舶总吨位口径的"口径外"起点：{@code 3017 × 10 = 30170} 吨。 */
    public static final BigDecimal GROSS_TONNAGE_OUT_OF_SCOPE_TON =
            GROSS_TONNAGE_CEILING_TON.multiply(BigDecimal.valueOf(FAR_EXCEEDS_FACTOR));

    private SimulatedDataScope() {
    }

    /**
     * 判定该吨位是否已超出仿真数据量级；超了就给一段**可核对**的说明，没超返回 {@code null}。
     *
     * <p>⚠️ 只认两种计费吨位口径（{@link TariffRuleRow#BASIS_CARGO_WEIGHT} /
     * {@link TariffRuleRow#BASIS_GROSS_TONNAGE}）——⛔ 口径不明时**一律不判**：
     * 拿货重的边界去卡船舶总吨位（两者量级本就不同，501–3,017 vs <5,000）会误判。
     *
     * @param tonnageBasis 计费吨位口径（由费种推导，见 {@code 25-第十二条}）
     * @param tonnage      计费吨位（吨）
     */
    public static String explainIfOutOfScope(String tonnageBasis, BigDecimal tonnage) {
        if (tonnage == null || tonnageBasis == null) {
            return null;
        }
        BigDecimal ceiling;
        BigDecimal outOfScope;
        String dataNote;
        if (TariffRuleRow.BASIS_CARGO_WEIGHT.equals(tonnageBasis)) {
            ceiling = CARGO_WEIGHT_CEILING_TON;
            outOfScope = CARGO_WEIGHT_OUT_OF_SCOPE_TON;
            dataNote = "本项目仿真运单的货重恒 < " + plain(ceiling) + " 吨（过闸费/港杂费的 ["
                    + plain(ceiling) + ", ∞) 档没有被任何仿真数据触发）";
        } else if (TariffRuleRow.BASIS_GROSS_TONNAGE.equals(tonnageBasis)) {
            ceiling = GROSS_TONNAGE_CEILING_TON;
            outOfScope = GROSS_TONNAGE_OUT_OF_SCOPE_TON;
            dataNote = "本项目仿真船队的总吨位为 501–" + plain(ceiling)
                    + " 吨（引航费/停泊费的 [5000, 10000) 与 [10000, ∞) 档没有被任何仿真数据触发）";
        } else {
            return null;
        }
        if (tonnage.compareTo(outOfScope) < 0) {
            return null;
        }
        return "计费吨位 " + plain(tonnage) + " 吨（口径：" + tonnageBasis + "）**远超仿真业务数据量级**："
                + dataNote + "；本层把「≥ 数据上界 " + plain(ceiling) + " 吨的 " + FAR_EXCEEDS_FACTOR
                + " 倍（" + plain(outOfScope) + " 吨）」判为口径外，故**不给确定金额**"
                + "（可给出费率档位说明，但不得当作真实业务报价）";
    }

    /** 去掉无意义的尾随零（5000 而不是 5000.00），避免文案里出现"精确到分"的假象。 */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
