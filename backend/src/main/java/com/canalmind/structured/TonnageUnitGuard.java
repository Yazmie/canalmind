package com.canalmind.structured;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计费吨位的**单位一致性守卫**（决策 D24 → 缺口③：「有票机制砂 5000 **米**」）。
 *
 * <p>背景：结构化层是**契约化受控工具集**，参数只有"数"没有"单位"——
 * 一旦模型把长度当成吨位塞进 {@code tonnage}，本层会**照数算出一个确定金额且零提示**
 * （实测：`5000` ⇒ `20000.00 元`）。这类错误比答不出更危险，所以要在执行前拦下并**追问**。
 *
 * <h2>⚠️ 判定规则刻意取得很窄（⛔ 不用"问句里含「米」就追问"这种脆弱正则）</h2>
 *
 * <p>只有**同时**满足下面三条才判为"单位错"：
 * <ol>
 *   <li>问句里存在「**数字 + 长度单位**」（{@code 5000 米} / {@code 3.5 公里} …）；
 *       ⛔ 面积/体积单位（{@code 平方米}/{@code 立方米}）不算——它们不是长度；</li>
 *   <li>那个数字**恰好等于**本次调用传进来的 {@code tonnage}
 *       （即"模型很可能是把这个数抄成了吨位"）；</li>
 *   <li>问句里**没有**同值的「数字 + 吨」（{@code 5000 吨}）——
 *       若用户本来就说过"5000 吨"，那 {@code tonnage} 有正当来源，
 *       同一句话里另提某个长度（如"吃水 5 米"）**不构成单位错**。</li>
 * </ol>
 *
 * <p>第 3 条是关键的一条：它把"同一句话里既有吨位又有尺度"这种**正常提问**排除掉。
 * 反例（必须**不**触发）：{@code 吃水 5 米的船装 5 吨货}（有"5 吨"⇒ 不判）、
 * {@code 船长 200 米、载货 3000 吨}（数字不等⇒ 不判）。
 *
 * <h2>⚠️ 诚实声明（残留风险，已在交付报告里逐条写明）</h2>
 * <ul>
 *   <li>本守卫依赖**原始问句**。当前链路（{@code QuestionPipeline.doRun} → {@code structured.ask(question)}
 *       、{@code L4RunCli}）传进来的确实是用户原话/多轮拼接文本，故可用；
 *       ⚠️ 若将来某处改成传"改写后的检索用问句"，数字与单位可能被丢掉 ⇒ 本守卫会**静默失效**（变成不触发，
 *       而不是误判）。这一失效方向是**故意选的**：宁可漏判也不误判。</li>
 *   <li>它**不能**判断"模型是猜的还是抄的"；它只判断"这个数与问句里的一个长度值相同且没有同值的吨位"，
 *       属于**证据不足时的追问**，而不是"已确认用户说错单位"。</li>
 * </ul>
 */
public final class TonnageUnitGuard {

    /**
     * 长度单位（**长的排前面**，否则 {@code 厘米} 会被 {@code 米} 抢先匹配成"…厘 米"）。
     * ⛔ 不收 {@code 平方米}/{@code 立方米}：它们不是长度（正则在数字后紧跟单位处匹配，
     * {@code 5000 立方米} 因此天然匹配不上）。
     */
    public static final List<String> LENGTH_UNITS = List.of("厘米", "毫米", "千米", "公里", "海里", "公尺", "米");

    /** 吨（重量）单位——用于"用户本来就说了吨位"这一豁免条件。 */
    public static final List<String> WEIGHT_UNITS = List.of("吨", "公斤", "千克");

    private static final Pattern LENGTH_MENTION = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(" + String.join("|", LENGTH_UNITS) + ")");

    private static final Pattern WEIGHT_MENTION = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(" + String.join("|", WEIGHT_UNITS) + ")");

    private TonnageUnitGuard() {
    }

    /**
     * 若问句表明本次 {@code tonnage} 很可能来自一个**长度**值，返回一句可直接当追问话术的说明；
     * 否则返回 {@code null}（= 未发现问题，⛔ 不表示"已确认单位正确"）。
     *
     * @param question 用户原始问句（可为 {@code null}，此时一律不判）
     * @param tonnage  本次调用传进来的计费吨位（可为 {@code null}）
     */
    public static String explainIfLengthUnitMistake(String question, BigDecimal tonnage) {
        if (question == null || question.isBlank() || tonnage == null) {
            return null;
        }
        if (hasSameValueWeightMention(question, tonnage)) {
            // 用户本来就说了同值的"…吨" ⇒ 吨位有正当来源，不判（详见类注释第 3 条）
            return null;
        }
        Matcher m = LENGTH_MENTION.matcher(question);
        while (m.find()) {
            BigDecimal value;
            try {
                value = new BigDecimal(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (value.compareTo(tonnage) != 0) {
                continue;
            }
            return "问题里的「" + m.group(1) + " " + m.group(2) + "」是**长度**单位，"
                    + "而计费吨位要的是**吨**（过闸费/港杂费/装卸费按载货吨位，引航费/停泊费按船舶总吨位，"
                    + "见语料 25-第十二条）——不能把 " + plain(tonnage) + " 这个长度值直接当成 "
                    + plain(tonnage) + " 吨来计算";
        }
        return null;
    }

    /** 问句里是否出现了**与该吨位同值**的重量单位表述（{@code 5000 吨}）。 */
    private static boolean hasSameValueWeightMention(String question, BigDecimal tonnage) {
        Matcher m = WEIGHT_MENTION.matcher(question);
        while (m.find()) {
            try {
                if (new BigDecimal(m.group(1)).compareTo(tonnage) == 0) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // 数字格式异常 ⇒ 视作"没提到"，继续找
            }
        }
        return false;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
