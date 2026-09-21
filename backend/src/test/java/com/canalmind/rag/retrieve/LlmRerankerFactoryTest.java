package com.canalmind.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重排装配侧「实验条件」的测试（2026-09-21，对应 ① 调大并放开 maxTokens、
 * ② 把单次等待上限 30 s 调大到 90 s 并放开为环境变量）。
 *
 * <p><b>为什么这些值值得单独一组测试</b>：它们是"第四路能不能跑起来"的开关，
 * 而它们出事的方式<b>全都是静默的</b>——
 * <ul>
 *   <li>漏设 / 被别的默认值盖掉 ⇒ 表现是"重排老是降级"，代码一行都不报错；</li>
 *   <li>环境变量写错（{@code 16k}、{@code 8192.0}、{@code 0}、{@code 120s}）
 *       而实现静默退回默认值 ⇒ 用户以为"我已经调到 16384 / 120 s 了"，
 *       产物里却仍是旧值，而产物<b>看不出</b>这一点；</li>
 *   <li>{@code maxTokens} 的 {@code 0} 最坏：模型一个字都写不出来，
 *       症状与"额度太小"一模一样；{@code timeout} 的 {@code 0} 同样最坏：
 *       每次调用都立刻超时降级，症状与"重排没效果"一模一样。</li>
 * </ul>
 * 故这里既钉默认值，也钉"非法取值必须大声报错"。
 */
class LlmRerankerFactoryTest {

    @Test
    @DisplayName("★ 重排额度默认 8192（原 4096 在 rerankTopN=30 档下是确定性降级）")
    void defaultMaxTokens() {
        assertThat(LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS)
                .as("实测依据：3/3 试点题 finish_reason=LENGTH 且 completion=4096=maxTokens，"
                        + "推理 token 与正文共享预算 ⇒ 4096 写不出正文")
                .isEqualTo(8192);
        assertThat(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV)
                .as("环境变量名是交付约定，别改名（用户按它调额度）")
                .isEqualTo("CANALMIND_RERANK_MAX_TOKENS");

        assertThat(LlmRerankerFactory.resolveMaxTokens(Map.of()))
                .isEqualTo(LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS);
        assertThat(LlmRerankerFactory.resolveMaxTokens(null))
                .as("env 为 null 视为空（自检与测试会传 null）")
                .isEqualTo(LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS);
        assertThat(LlmRerankerFactory.resolveMaxTokens(
                Map.of(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV, "   ")))
                .as("空白等同于未设置（与工厂里其它环境变量同一口径）")
                .isEqualTo(LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS);
    }

    @Test
    @DisplayName("★ 环境变量可覆盖额度：16384 / 32768 / 1 都接受，两侧空白容忍")
    void envOverridesMaxTokens() {
        assertThat(resolve("16384"))
                .as("更宽的一档：本机网关实测接受该值（历史实测 40 题 0/40 降级）")
                .isEqualTo(16384);
        assertThat(resolve(" 32768 ")).isEqualTo(32768);
        assertThat(resolve("1")).as("下界是 1（正整数），刻意不设上限：上限不是成本").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 非法取值一律报错，⛔ 不静默用默认值、更不静默当 0")
    void illegalValuesFailLoudly() {
        // 非整数：静默退回默认值的后果是"用户以为调过了"——产物里看不出来
        for (String bad : List.of("16k", "8192.0", "八千", "8 192", "abc")) {
            assertThatThrownBy(() -> resolve(bad))
                    .as("取值「%s」必须报错（静默退回会让人以为额度已按预期生效）", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV)
                    .hasMessageContaining(bad)
                    .hasMessageContaining("必须是正整数")
                    .hasMessageContaining("静默");
        }
        // <= 0：0 会让模型一个字都写不出来，而症状与"额度太小"无法区分
        for (String bad : List.of("0", "-1", "-4096")) {
            assertThatThrownBy(() -> resolve(bad))
                    .as("取值「%s」必须报错（0 的症状是「每次重排都降为空正文」）", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV)
                    .hasMessageContaining(">= 1");
        }
    }

    @Test
    @DisplayName("★ 装配时就把非法额度拦下：fromEnvironment 在发任何请求之前报错（不花冤枉钱）")
    void fromEnvironmentRejectsIllegalMaxTokensBeforeAnyCall() {
        Map<String, String> env = Map.of(
                LlmRerankerFactory.API_KEY_ENV, "sk-stub",
                LlmRerankerFactory.RERANK_MAX_TOKENS_ENV, "0");

        assertThatThrownBy(() -> LlmRerankerFactory.fromEnvironment(
                env, LlmReranker.ChunkSnippetProvider.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV);

        // 合法取值时装配成功，且额度落在模型默认选项上（下面那条用例验证"真的落进去了"）
        assertThat(LlmRerankerFactory.fromEnvironment(Map.of(
                LlmRerankerFactory.API_KEY_ENV, "sk-stub",
                LlmRerankerFactory.RERANK_MAX_TOKENS_ENV, "16384"),
                LlmReranker.ChunkSnippetProvider.NONE).modelName())
                .isEqualTo(LlmRerankerFactory.DEFAULT_MODEL);
    }

    @Test
    @DisplayName("★ 额度真的落进模型默认选项（漏设不会报错，只会一直降级）")
    void maxTokensReachesTheModelOptions() {
        ChatOptions defaults = LlmRerankerFactory.deepSeekChatModel("sk-stub",
                LlmRerankerFactory.DEFAULT_BASE_URL, LlmRerankerFactory.DEFAULT_MODEL,
                LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS).getDefaultOptions();

        assertThat(defaults.getMaxTokens())
                .as("装配处漏掉 maxTokens 时不会有任何报错，只会在真实链路里一直降级——故必须钉住")
                .isEqualTo(8192);
        assertThat(defaults.getModel()).isEqualTo(LlmRerankerFactory.DEFAULT_MODEL);
        assertThat(defaults.getTemperature())
                .as("重排是排序任务：温度钉死为 0，保证同一提示词稳定复现")
                .isEqualTo(0.0);
        assertThat(LlmRerankerFactory.deepSeekChatModel("sk-stub",
                LlmRerankerFactory.DEFAULT_BASE_URL, LlmRerankerFactory.DEFAULT_MODEL, 16384)
                .getDefaultOptions().getMaxTokens())
                .as("覆盖值同样要落进 options（否则改了环境变量等于没改）")
                .isEqualTo(16384);
    }

    private static int resolve(String raw) {
        return LlmRerankerFactory.resolveMaxTokens(
                Map.of(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV, raw));
    }

    // ── ② 单次等待上限（2026-09-21：30 s → 90 s，并放开为环境变量）─────────────
    //
    // 为什么它同样值得一组测试：等待上限出事的方式**全都是静默的**——
    // 调小了 ⇒ 大量本来能成功的调用被判成超时降级，而"降级 + 保留融合原序"
    // 在产物里与"重排本来就没效果"**完全一样**；环境变量写错而实现静默退回默认值
    // ⇒ 用户以为"我已经调到 120 s 了"，实际还是 90 s，产物里看不出来。

    @Test
    @DisplayName("★ 重排超时默认 90 s（原 30 s 正好压在实测均值 29.4 s 上）")
    void defaultTimeoutSeconds() {
        assertThat(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV)
                .as("环境变量名是交付约定，别改名（用户按它调超时）")
                .isEqualTo("CANALMIND_RERANK_TIMEOUT_SECONDS");
        assertThat(LlmRerankerFactory.DEFAULT_RERANK_TIMEOUT_SECONDS)
                .as("实测依据：rerankTopN=30 档 40 题真实链路，单题重排耗时 "
                        + "最小 21.6 / 均值 29.4 / 最大 43.5 s，且有一次成功的 29.875 s"
                        + "（离 30 s 只差 0.1 s）⇒ 30 s 该调大")
                .isEqualTo(90);
        assertThat(LlmReranker.DEFAULT_TIMEOUT.toSeconds())
                .as("工厂缺省值由 LlmReranker.DEFAULT_TIMEOUT 换算而来：两处各写一遍必然分叉，"
                        + "而分叉的表现是「banner 打印 90 s、实际等待 30 s」，不会报错")
                .isEqualTo(LlmRerankerFactory.DEFAULT_RERANK_TIMEOUT_SECONDS);

        assertThat(LlmRerankerFactory.resolveTimeoutSeconds(Map.of()))
                .isEqualTo(LlmRerankerFactory.DEFAULT_RERANK_TIMEOUT_SECONDS);
        assertThat(LlmRerankerFactory.resolveTimeoutSeconds(null))
                .as("env 为 null 视为空（自检与测试会传 null）")
                .isEqualTo(90);
        assertThat(LlmRerankerFactory.resolveTimeoutSeconds(
                Map.of(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV, "   ")))
                .as("空白等同于未设置（与 maxTokens 同一口径）")
                .isEqualTo(90);
    }

    @Test
    @DisplayName("★ 环境变量可覆盖超时：120 / 300 / 1 都接受，两侧空白容忍")
    void envOverridesTimeoutSeconds() {
        assertThat(resolveTimeout("120"))
                .as("用户可按需更宽（例如晚间网关更慢），不必改代码重编译")
                .isEqualTo(120);
        assertThat(resolveTimeout(" 300 ")).isEqualTo(300);
        assertThat(resolveTimeout("1")).as("下界是 1 秒（正整数）").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 非法超时一律报错（abc / 0 / -5），⛔ 不静默退回默认值")
    void illegalTimeoutValuesFailLoudly() {
        // 非整数：静默退回默认值的后果是"用户以为调过了"——产物里看不出来
        for (String bad : List.of("abc", "90s", "90.0", "2m", "1 20")) {
            assertThatThrownBy(() -> resolveTimeout(bad))
                    .as("取值「%s」必须报错（静默退回会让人以为超时已按预期生效）", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV)
                    .hasMessageContaining(bad)
                    .hasMessageContaining("必须是正整数")
                    .hasMessageContaining("静默");
        }
        // <= 0：上限为 0 时每次都立刻超时降级，而症状与"重排没效果"无法区分
        for (String bad : List.of("0", "-5", "-90")) {
            assertThatThrownBy(() -> resolveTimeout(bad))
                    .as("取值「%s」必须报错（上限为 0 会让每次调用立刻超时降级）", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV)
                    .hasMessageContaining(bad)
                    .hasMessageContaining(">= 1");
        }
    }

    @Test
    @DisplayName("★ 生效值：不设 = 90 s、设 120 = 120 s，且非法值在发任何请求之前就被拦下")
    void timeoutReachesTheReranker() {
        // 构造只装配模型对象，**不发任何网络请求**（与上面 maxTokens 那条用例同一做法）
        assertThat(LlmRerankerFactory.fromEnvironment(
                Map.of(LlmRerankerFactory.API_KEY_ENV, "sk-stub"),
                LlmReranker.ChunkSnippetProvider.NONE).timeoutMillis())
                .as("未设置环境变量时就是缺省值 90 s")
                .isEqualTo(90_000L);
        assertThat(LlmRerankerFactory.fromEnvironment(Map.of(
                LlmRerankerFactory.API_KEY_ENV, "sk-stub",
                LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV, "120"),
                LlmReranker.ChunkSnippetProvider.NONE).timeoutMillis())
                .as("装配处漏掉 withTimeout 时没有任何报错，只会在真实链路里一直超时降级——故必须钉住")
                .isEqualTo(120_000L);

        // 非法取值：在装配处（= 发任何请求之前）就报错，不花冤枉钱
        assertThatThrownBy(() -> LlmRerankerFactory.fromEnvironment(Map.of(
                LlmRerankerFactory.API_KEY_ENV, "sk-stub",
                LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV, "0"),
                LlmReranker.ChunkSnippetProvider.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV);
    }

    private static int resolveTimeout(String raw) {
        return LlmRerankerFactory.resolveTimeoutSeconds(
                Map.of(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV, raw));
    }
}
