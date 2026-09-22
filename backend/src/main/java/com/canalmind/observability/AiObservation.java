package com.canalmind.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 业务 Span 的埋点小工具：<b>把规格书 §7.3 的基数纪律变成会抛异常的检查</b>。
 *
 * <p><b>为什么不是"一个薄封装"，而是一道闸门</b>：知识库《AI 可观测性与 Trace…》第 202 行
 * 给的是<b>纪律</b>（"低基数键会进入 Metrics 和 Trace，高基数键只进入 Trace"），
 * 第 451 行给的是<b>禁令</b>（"不要把原始查询和文档正文直接设为默认 Tag"），
 * 第 531–533 行把它列为"常见错误：把用户输入放进指标标签"。
 * <b>但纪律写在文档里是不会生效的</b>——本项目已有实证：一条写入文档的约定
 * 同一天被人（我）连踩两次（`docs/工程复盘/面试速查卡.md` 的 psql 条目）。
 * 所以这里把三条纪律各做成一个<b>运行期断言</b>，违反即抛异常、并附上"为什么不行 + 该怎么办"。
 *
 * <h2>三道闸门</h2>
 * <ol>
 *   <li><b>键必须在白名单里</b>（{@link AiSpans#LOW_CARDINALITY_KEYS} /
 *       {@link AiSpans#HIGH_CARDINALITY_KEYS}）——防止有人在低基数位上新建一个键，
 *       在<b>别处</b>悄悄把它的取值变成开放的。</li>
 *   <li><b>低基数取值必须是短的封闭枚举</b>（见 {@link #LOW_CARDINALITY_VALUE}）——
 *       这条最关键：它让"把中文问题原文塞进低基数键"<b>在结构上不可能</b>，
 *       因为中文、空格、问号、超长文本都会被正则拒绝。</li>
 *   <li><b>高基数取值不得含换行且不得超长</b>（见 {@link #MAX_HIGH_CARDINALITY_LENGTH}）——
 *       文档正文与 Prompt 原文都带换行，所以这条挡住了最常见的"顺手把正文挂上去"。</li>
 * </ol>
 *
 * <p>✅ <b>可被证伪</b>：{@code AiObservationTest} 对三道闸门<b>各准备了反例</b>
 * （中文问题原文、带换行的文档片段、未登记的键），断言它们<b>真的会被拒</b>——
 * 而不是断言"我写了一个 if"。
 *
 * <p>⚠️ <b>如实记一笔局限</b>：闸门只能挡住"经由本类写入的属性"。
 * 若某处直接调 {@code Observation.lowCardinalityKeyValue(...)} 绕过本类，闸门不会被触发。
 * 故配套约定：<b>业务埋点一律经由本类写属性</b>；这条由 {@code AiObservationTest}
 * 的源码扫描断言守护（见该类 {@code 没有绕过闸门的裸调用}）。
 */
public final class AiObservation {

    private AiObservation() {
    }

    /** 低基数取值的允许形状：短、无空格、无中文、无标点——即"封闭枚举"。 */
    private static final Pattern LOW_CARDINALITY_VALUE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    /**
     * 高基数取值的长度上限。
     *
     * <p>取 512 是个工程折中：文档 id、模型响应 id、UUID、JSON 摘要都远小于它，
     * 而"一段正文"通常远大于它。⛔ 它的作用不是"允许到 512 字"，而是<b>把正文挡在外面</b>。
     */
    public static final int MAX_HIGH_CARDINALITY_LENGTH = 512;

    /**
     * 新起一个 Span（未启动）。
     *
     * <p>别名 {@link Observation#createNotStarted(String, ObservationRegistry)}，存在的理由是
     * 让"Span 名只能来自 {@link AiSpans}"在调用点一眼可见。
     *
     * <p>⚠️⚠️ <b>拿到之后必须用 {@code observe(...)} / {@code observeChecked(...)} 包住业务，
     * ⛔ 不要手工 {@code start()}/{@code stop()}</b>——本机制已实测（2026-09-22，
     * 见 {@code docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md}）：
     * <table border="1">
     *   <caption>父指针的建立条件（实测）</caption>
     *   <tr><th>写法</th><th>子 Span 的父指针</th></tr>
     *   <tr><td>外层只 {@code start()} / {@code stop()}</td><td>⛔ {@code null}（整棵树是平的）</td></tr>
     *   <tr><td>外层 {@code observe(...)}（或 {@code openScope()}）</td><td>✅ 外层的 Span 名</td></tr>
     * </table>
     * 原因是「当前观测」只在<b>打开 scope 之后</b>才存在，而父指针是<b>子 Span 启动时</b>从它取的。
     * ⛔ 这个错误<b>不报错、Span 也都在、名字也对</b>——只是调用链结构静默变成平的，
     * 于是"哪一步导致了哪一步"永远查不出来。
     *
     * @param name     必须是 {@link AiSpans} 里的常量；⛔ 不要就地拼字符串
     *                 （知识库第 91、105 行：名字必须稳定、低基数）
     * @param registry 由调用方注入；无观测需求时传 {@link ObservationRegistry#NOOP}
     */
    public static Observation start(String name, ObservationRegistry registry) {
        Objects.requireNonNull(name, "Span 名不能为 null");
        Objects.requireNonNull(registry, "ObservationRegistry 不能为 null；不需要埋点时请显式传 ObservationRegistry.NOOP");
        return Observation.createNotStarted(name, registry);
    }

    /**
     * 写一个<b>低基数</b>属性（进 Metrics + Trace）。
     *
     * @throws IllegalArgumentException 键不在白名单、取值为空、或取值不像封闭枚举
     */
    public static Observation lowCardinality(Observation observation, String key, String value) {
        Objects.requireNonNull(observation, "observation 不能为 null");
        if (!AiSpans.LOW_CARDINALITY_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "低基数键「" + key + "」未登记。规格书 §7.3（第 570 行）只允许 "
                            + AiSpans.LOW_CARDINALITY_KEYS + " 进 Metrics。"
                            + "高基数的取值进 Metrics 会让时间序列随用户输入无界增长"
                            + "（知识库《AI 可观测性与 Trace…》第 202、531–533 行）。"
                            + "⇒ 应改用 highCardinality(observation, key, value)；"
                            + "若确实要新增低基数键，须先加进 AiSpans.LOW_CARDINALITY_KEYS 并说明它的取值为何封闭。");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("低基数键「" + key + "」的取值不能为空（空值会让聚合结果看起来像另一个枚举值）");
        }
        if (!LOW_CARDINALITY_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "低基数键「" + key + "」的取值「" + abbreviate(value) + "」不像封闭枚举。"
                            + "低基数取值只允许 [A-Za-z0-9_.:-] 且不超过 64 字符，"
                            + "这是为了<b>让中文问题原文在结构上无法进 Metrics</b>"
                            + "（知识库第 451、531–533 行明令禁止把用户输入当标签）。"
                            + "⇒ 内容类信息请改传哈希或受控枚举，或改用 highCardinality(...)。");
        }
        return observation.lowCardinalityKeyValue(key, value);
    }

    /**
     * 写一个<b>高基数</b>属性（只进 Trace）。
     *
     * @throws IllegalArgumentException 键不在白名单、取值为空、含换行、或超长
     */
    public static Observation highCardinality(Observation observation, String key, String value) {
        Objects.requireNonNull(observation, "observation 不能为 null");
        if (!AiSpans.HIGH_CARDINALITY_KEYS.contains(key)) {
            throw new IllegalArgumentException(
                    "高基数键「" + key + "」未登记。规格书 §7.3（第 571 行）登记的是 "
                            + AiSpans.HIGH_CARDINALITY_KEYS + "。"
                            + "⛔ 特别是<b>原始问题、文档正文、Prompt/Completion 原文不得作为属性</b>"
                            + "（知识库第 200、451 行：它们不只高基数，还可能很长并含敏感信息）——"
                            + "需要留痕时请传哈希。");
        }
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("高基数键「" + key + "」的取值不能为空");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "高基数键「" + key + "」的取值含换行 ⇒ 这几乎一定是正文（文档片段 / Prompt 原文），"
                            + "而它们<b>不允许作为属性</b>（知识库第 200、451 行）。"
                            + "⇒ 请改传哈希、id 或受控摘要。");
        }
        if (value.length() > MAX_HIGH_CARDINALITY_LENGTH) {
            throw new IllegalArgumentException(
                    "高基数键「" + key + "」的取值长度 " + value.length() + " 超过上限 "
                            + MAX_HIGH_CARDINALITY_LENGTH + " ⇒ 这几乎一定是正文而非标识符"
                            + "（知识库第 200、451 行）。");
        }
        return observation.highCardinalityKeyValue(key, value);
    }

    /**
     * 记录"第几次尝试"（知识库第 55、269 行）。
     *
     * <p><b>为什么重试必须各建一个 Span 并带上它</b>：知识库第 55 行原文<b>"每次重试都新建
     * Span 并用 {@code attempt} 标明次数"</b>，否则"第一次超时和第二次成功会混在同一条记录里"。
     * 本项目的抽取器有 {@code DEFAULT_MAX_RETRIES} 次带错误反馈的重试，正是这个场景。
     *
     * @param attempt 从 <b>1</b> 起（第 0 次容易与"未开始"混淆）
     */
    public static Observation attempt(Observation observation, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须从 1 起（收到 " + attempt
                    + "）；用 0 会和「未开始」混淆（知识库第 55 行）");
        }
        return highCardinality(observation, AiSpans.KEY_ATTEMPT, Integer.toString(attempt));
    }

    /**
     * 检索结果枚举（{@code hit} / {@code empty}）——规格书 §7.3 第 570 行点名的低基数键。
     *
     * <p>知识库第 448、451 行的示例正是这个用法：<b>{@code ai.rag.result} 只有两种取值，
     * 所以可以参与指标聚合；而召回<b>条数</b>只放到 Trace</b>。
     */
    public static Observation ragResult(Observation observation, boolean empty) {
        return lowCardinality(observation, AiSpans.KEY_RAG_RESULT,
                empty ? AiSpans.VALUE_RESULT_EMPTY : AiSpans.VALUE_RESULT_HIT);
    }

    /** 异常消息里截断长取值，避免把正文原样回显到日志（否则"挡在属性外、漏进了日志"）。 */
    private static String abbreviate(String value) {
        return value.length() <= 24 ? value : value.substring(0, 24) + "…（共 " + value.length() + " 字符）";
    }
}
