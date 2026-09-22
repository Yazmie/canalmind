package com.canalmind.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-56 的**两档模型**配置测试：小档（路由/改写/抽取）与强档（最终生成）怎么解析。
 *
 * <p>⚠️ 这里只测"配置解析与一键回退"——**它证明不了"CLI 真的把强档传给了作答器"**。
 * 那一环的证据是 `LayerRunCli` 的 `smoke` 子命令（真跑 1~2 次付费调用），
 * 见 {@code docs/进度看板.md} §0.4.7；⛔ 别把本测试当成"接线已验证"。
 *
 * <p>零付费：本类**不发起任何网络调用**（只解析环境变量、构造客户端对象）。
 */
class AnswerModelTierTest {

    @Test
    @DisplayName("强档默认 deepseek-v4-pro；显式给值就听显式的")
    void resolvesStrongModel() {
        assertEquals("deepseek-v4-pro",
                LlmRerankerFactory.answerModel(Map.of()));

        Map<String, String> env = new HashMap<>();
        env.put(LlmRerankerFactory.ANSWER_MODEL_ENV, "some-other-strong-model");
        assertEquals("some-other-strong-model", LlmRerankerFactory.answerModel(env));
        assertEquals("some-other-strong-model",
                LlmRerankerFactory.answerModel(env), "同一个 env 反复问要得到同一个答案");
    }

    @Test
    @DisplayName("一键回退：强档置空 ⇒ 回落成小档（DEEPSEEK_CHAT_MODEL），⛔ 不改代码")
    void blankStrongModelFallsBackToSmallTier() {
        Map<String, String> env = new HashMap<>();
        env.put(LlmRerankerFactory.ANSWER_MODEL_ENV, "");
        env.put("DEEPSEEK_CHAT_MODEL", "deepseek-v4-flash");

        assertEquals("deepseek-v4-flash", LlmRerankerFactory.answerModel(env));

        env.put(LlmRerankerFactory.ANSWER_MODEL_ENV, "   ");
        assertEquals("deepseek-v4-flash", LlmRerankerFactory.answerModel(env),
                "只有空白也算置空 —— 否则配置文件里多一个空格就会静默用错模型");
    }

    @Test
    @DisplayName("两种「没给强档」要分清：键不存在 ⇒ 默认强档；键存在但空白 ⇒ 回退小档")
    void absentKeyVersusExplicitlyBlank() {
        Map<String, String> env = new HashMap<>();
        env.put("DEEPSEEK_CHAT_MODEL", "small-tier-model");
        assertEquals("deepseek-v4-pro", LlmRerankerFactory.answerModel(env),
                "⛔ 键**不存在**不等于回退：配置漏了也要按裁示用强档");

        env.put(LlmRerankerFactory.ANSWER_MODEL_ENV, "");
        assertEquals("small-tier-model", LlmRerankerFactory.answerModel(env),
                "键存在但显式置空 = 运维的一键回退开关");
    }

    @Test
    @DisplayName("缺 API key ⇒ 构造强档客户端**立刻**报错（⛔ 不静默退成 null）")
    void missingApiKeyFailsFast() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LlmRerankerFactory.answerModelFromEnvironment(Map.of(), 512));
        assertTrue(thrown.getMessage().toLowerCase().contains("key"),
                "报错信息要指向 key，实际：" + thrown.getMessage());
    }

    @Test
    @DisplayName("给了 key ⇒ 能构造出强档客户端，且构造过程不发网络请求")
    void buildsStrongClientWithoutNetwork() {
        Map<String, String> env = new HashMap<>();
        env.put("DEEPSEEK_API_KEY", "sk-stub-not-used");
        env.put("DEEPSEEK_BASE_URL", "http://127.0.0.1:1/v1");

        assertNotNull(LlmRerankerFactory.answerModelFromEnvironment(env, 512));
    }
}
