package com.canalmind.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-51：Prompt 版本化的零付费契约测试。
 *
 * <p>本类最重要的职责是**钉住 v1**——L3/L5/L6 的既有出分产物就是 v1 的产物，
 * 一旦 v1 的文字被改动，那些数字的出处就断了，而**没有任何东西会因此报错**。
 * 所以这里把 v1 的全文逐字写进断言：谁改了它，谁就会看到测试变红。
 */
class PromptVersionsTest {

    /** ⚠️ v1 的全文快照：**这是基线判据**，改动它等于宣布既有出分产物作废。 */
    private static final String V1_SNAPSHOT = "你是平陆运河航运物流业务助手，服务于内河航运物流场景。\n"
            + "\n"
            + "回答要求：\n"
            + "1. 只依据业务数据库查询结果与规则文档作答，不得编造。\n"
            + "2. 涉及费用、时限、流程等关键信息时，明确标注依据来源。\n"
            + "3. 检索不到依据时，直接说明无法回答，并建议转人工，不要猜测。\n";

    @Test
    @DisplayName("⛔ v1 必须与冻结快照逐字一致——否则既有出分产物的出处就断了")
    void v1IsFrozen() {
        assertEquals(V1_SNAPSHOT, PromptVersions.v1(),
                "v1 被改动了！L3/L5/L6 的既有产物是它的产物，改动即作废基线（C-51）");
    }

    @Test
    @DisplayName("v2 必须**从 v1 派生**：以 v1 原文开头，只追加不改写")
    void v2DerivesFromV1() {
        String v2 = PromptVersions.v2();
        assertTrue(v2.startsWith(PromptVersions.v1()),
                "v2 必须以 v1 原文开头（派生而非另抄一份）——否则 v1/v2 的差异无法 diff");
        assertTrue(v2.length() > PromptVersions.v1().length());

        // v1 的三条既有条款必须**原样还在**（⛔ 不许被 v2 悄悄改写掉）
        assertTrue(v2.contains("1. 只依据业务数据库查询结果与规则文档作答，不得编造。"));
        assertTrue(v2.contains("3. 检索不到依据时，直接说明无法回答，并建议转人工，不要猜测。"));

        // v2 追加的是**可执行的机械要求**，不是又一句笼统话
        assertTrue(v2.contains("逐条引用"), "v2 的核心就是「逐条」");
        assertTrue(v2.contains("不得合并引用"), "不许用一句「依据相关规定」概括");
        assertTrue(v2.contains("本项无依据"), "无依据要明说，而不是给个结论");
        assertFalse(v2.contains("\\n"), "⛔ 不得残留转义字面量");
    }

    @Test
    @DisplayName("未设置环境变量 ⇒ v1（既有行为零变化）")
    void defaultsToV1() {
        assertEquals(PromptVersions.v1(), PromptVersions.fromEnv(null));
        assertEquals(PromptVersions.v1(), PromptVersions.fromEnv(Map.of()));
        assertEquals(PromptVersions.v1(), PromptVersions.fromEnv(Map.of(PromptVersions.ENV_KEY, "   ")));
        assertEquals(PromptVersions.V1, PromptVersions.versionName(Map.of()));
    }

    @Test
    @DisplayName("显式指定 v2 能取到 v2")
    void selectsV2() {
        assertEquals(PromptVersions.v2(), PromptVersions.fromEnv(Map.of(PromptVersions.ENV_KEY, "v2")));
        assertEquals(PromptVersions.V2, PromptVersions.versionName(Map.of(PromptVersions.ENV_KEY, "v2")));
    }

    @Test
    @DisplayName("⛔「只改 L5」必须是机械保证：选了 v2，非 L5 的层也**不可能**拿到 v2")
    void v2IsScopedToL5() {
        Map<String, String> v2sel = Map.of(PromptVersions.ENV_KEY, "v2");

        // 目标层拿到 v2
        assertEquals(PromptVersions.v2(), PromptVersions.forLayer("L5", v2sel));
        assertEquals(PromptVersions.V2, PromptVersions.versionNameForLayer("L5", v2sel));

        // ⚠️ 其余层**必须原样是 v1** —— 否则 L3/L6 的既有产物会因这次实验失去出处
        for (String other : new String[] {"L1", "L2", "L3", "L4", "L6", "l5", "L5 ", null}) {
            assertEquals(PromptVersions.v1(), PromptVersions.forLayer(other, v2sel),
                    "层「" + other + "」不得拿到 v2——本轮口径是只改 L5");
            assertEquals(PromptVersions.V1, PromptVersions.versionNameForLayer(other, v2sel));
        }

        // 未选 v2 时，**任何层**（含 L5）都是 v1
        for (String any : new String[] {"L1", "L3", "L5", "L6"}) {
            assertEquals(PromptVersions.v1(), PromptVersions.forLayer(any, Map.of()),
                    "未选 v2 时不该有任何层被改动");
            assertEquals(PromptVersions.V1, PromptVersions.versionNameForLayer(any, Map.of()));
        }
    }

    @Test
    @DisplayName("⛔ 版本名拼错必须抛异常，不得静默回退成 v1")
    void unknownVersionThrows() {
        // 静默回退会让"v2 的结论"其实是 v1 的结果——正是本项目反复出现的「合法但错」
        assertThrows(IllegalArgumentException.class,
                () -> PromptVersions.fromEnv(Map.of(PromptVersions.ENV_KEY, "v3")));
        assertThrows(IllegalArgumentException.class,
                () -> PromptVersions.byName("V2 "));   // 大小写敏感：只认小写规范名
        assertThrows(IllegalArgumentException.class, () -> PromptVersions.byName(null));
    }
}
