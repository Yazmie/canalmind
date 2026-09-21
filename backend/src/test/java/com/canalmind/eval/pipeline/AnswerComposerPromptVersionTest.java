package com.canalmind.eval.pipeline;

import com.canalmind.config.PromptVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-51：**评测侧**系统提示词的 v1 / v2 组合契约（零付费）。
 *
 * <p>⚠️ 本类的存在理由：第 13 轮我把 v1 找错了地方（冻的是 Web 侧的
 * `ChatClientConfig.SYSTEM_PROMPT`，而 L5 作答读的是本类的 `systemPrompt()`）。
 * 所以这里**专门钉住"评测侧"这一段**，并用断言证明 v2 的追加块**确实进了同一段文字**——
 * 而不是只看"跑成功了"。详见 DECISIONS 的「**C-51 v2 目标更正**」。
 */
class AnswerComposerPromptVersionTest {

    @Test
    @DisplayName("⛔ 不启用 v2 时，提示词必须与既有一字不差")
    void v1IsUnchangedWhenV2Off() {
        assertEquals(AnswerComposer.systemPrompt(), AnswerComposer.systemPrompt(false),
                "v1 变了！L3/L5/L6 的既有出分产物就是它的产物，改动即作废基线");
    }

    @Test
    @DisplayName("v2 = v1 原文 + 追加块；且追加块与 Web 侧**共用同一份文字**")
    void v2AppendsSharedBlock() {
        String v1 = AnswerComposer.systemPrompt();
        String v2 = AnswerComposer.systemPrompt(true);

        assertTrue(v2.startsWith(v1), "v2 必须以 v1 原文开头（⛔ 不是另写一段）");
        assertTrue(v2.length() > v1.length(), "v2 必须比 v1 长");
        assertTrue(v2.endsWith(PromptVersions.citationBlock()),
                "追加块必须与 Web 侧共用 PromptVersions.citationBlock()，⛔ 不许本类另抄一份");

        // v1 的三条既有硬约束必须**原样还在**（⛔ 不得被 v2 悄悄改写）
        assertTrue(v2.contains(AnswerComposer.NO_FABRICATION_RULE));
        assertTrue(v2.contains(AnswerComposer.STATUS_RULE));
        assertTrue(v2.contains(AnswerComposer.SEGMENT_RULE));

        // v2 追加的必须是**可执行的机械要求**
        assertTrue(v2.contains("逐条引用"));
        assertTrue(v2.contains("不得合并引用"));
        assertTrue(v2.contains("本项无依据"));
        assertFalse(v2.contains("\\n"), "⛔ 不得残留转义字面量（真实换行要用计算字符）");
    }

    @Test
    @DisplayName("⛔ 追加块只在 v2 时为真时出现，v1 里绝不能有它")
    void blockIsAbsentFromV1() {
        assertFalse(AnswerComposer.systemPrompt().contains("逐条引用"),
                "v1 里出现 v2 的追加块，说明版本判断失效了");
    }
}
