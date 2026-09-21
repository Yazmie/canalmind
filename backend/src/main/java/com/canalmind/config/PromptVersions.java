package com.canalmind.config;

import java.util.Map;

/**
 * 系统提示词的**版本化入口**（C-51）。
 *
 * <p>**为什么要单独一个类**：Prompt 版本是"每轮只改少量变量"里的一个变量，
 * 而要能把指标差异**归因**给它，就必须满足两条：
 * <ol>
 *   <li>**v1 被冻结**——它已被 L3/L5/L6 的既有出分产物引用，改动即作废基线；</li>
 *   <li>**v2 从 v1 派生，而不是另抄一份**——抄一份就会出现"两处写同一件事"，
 *       而本项目已经吃过"两份来源同一事实、改了一处另一处没改"的亏。</li>
 * </ol>
 * 故这里 v2 = **v1 原文 + 一段追加块**，⛔ 不改写 v1 的任何既有条款。
 * 这样"v2 只多了什么"是**可 diff 的**，v1/v2 的差异才读得出来。
 *
 * <p>⚠️ **v2 的定位（⛔ 对外不得含混）**：v2 瞄准的是 **L5（依据与金额）** 这一层——
 * L5 当前严格通过率仅 **0.1500**，是六层里最低的一层，而"逐条引用依据"正是它的判分要点。
 * 但 v2 改的是**共享的系统提示词**，因此：**只重跑 L5 才能把差异归因给 Prompt**；
 * ⛔ **不得据此宣称 v2 对 L3/L6 也有效**——那两层没有重跑，没有证据。
 */
public final class PromptVersions {

    /** 选择版本的进程环境变量；缺省 / 空值 / 未识别值一律走 {@link #V1}。 */
    public static final String ENV_KEY = "CANALMIND_SYSTEM_PROMPT_VERSION";

    public static final String V1 = "v1";
    public static final String V2 = "v2";

    private static final String LF = String.valueOf((char) 10);

    private PromptVersions() {
    }

    /** v1：**冻结的**第 1 周占位版——⛔ 只读，不要在别处重写这段文字。 */
    public static String v1() {
        return ChatClientConfig.SYSTEM_PROMPT;
    }

    /**
     * v2：v1 + 「逐条引用依据」的强化块。
     *
     * <p>⚠️ v1 的第 2 条已经说了"明确标注依据来源"，但实测 L5 只有 0.1500 ⇒
     * 说明那句话**不够可执行**。v2 不再说"标注来源"，而是要求**可核对的机械形式**：
     * 每条结论后面跟一条依据、无依据就明说"无依据"、⛔ 禁止把推测写成结论。
     */
    public static String v2() {
        return v1() + LF
                + "依据引用（v2 强化，⛔ 覆盖上面第 2 条的笼统要求）：" + LF
                + "1. **逐条引用**：凡是给出的结论、金额、时限、条件，**每一条**后面都必须紧跟" + LF
                + "   它的依据（来自规则文档的，写明条款/文件编号；来自数据库查询的，写明字段与快照日期）。" + LF
                + "2. **⛔ 不得合并引用**：不许用一句「依据相关规定」概括多条结论——那等于没有依据。" + LF
                + "3. **无依据必须明说**：若某个结论在检索结果与数据库里都找不到依据，" + LF
                + "   ⛔ 不要给出该结论，改为**明确写出「本项无依据」**。" + LF
                + "4. **⛔ 禁止把推测写成结论**：凡属于推断、估算或「通常做法」，" + LF
                + "   必须显式标注为推断，并说明推断所依赖的前提。" + LF
                + "5. 金额一律以数据库查询结果为准，⛔ 不得由模型自行计算或估算。";
    }

    /**
     * 按版本名取提示词；未知版本**抛异常**。
     *
     * <p>⛔ **不静默回退**：写成"认不出就用 v1"会让一次拼错的版本名悄悄跑成 v1，
     * 于是"v2 的结论"其实是 v1 的结果——本项目反复出现的**合法但错**。
     * 缺省由 {@link #fromEnv(Map)} 负责（那也是显式的、有测试钉住的默认值）。
     */
    public static String byName(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Prompt 版本不得为 null（缺省请用 fromEnv）");
        }
        return switch (version.trim()) {
            case V1 -> v1();
            case V2 -> v2();
            default -> throw new IllegalArgumentException(
                    "未知的 Prompt 版本「" + version + "」：只认 " + V1 + " / " + V2);
        };
    }

    /** 从环境变量取版本；未设置或为空白 ⇒ **v1**（保持既有行为不变）。 */
    public static String fromEnv(Map<String, String> env) {
        String raw = env == null ? null : env.get(ENV_KEY);
        if (raw == null || raw.isBlank()) {
            return v1();
        }
        // ⚠️ 显式设置了就**必须**认得出来：⛔ 不静默回退到 v1
        return byName(raw);
    }

    /** 版本的规范名（写进产物，便于溯源）。 */
    public static String versionName(Map<String, String> env) {
        String raw = env == null ? null : env.get(ENV_KEY);
        if (raw == null || raw.isBlank()) {
            return V1;
        }
        byName(raw);
        return raw.trim();
    }
}
