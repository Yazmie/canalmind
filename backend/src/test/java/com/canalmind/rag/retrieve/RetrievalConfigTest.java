package com.canalmind.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索配置的测试（第 2 周第 5 项）。
 *
 * <p>为什么给一个"参数对象"写一组测试：三档 Top-K 的校验规则一旦失效，
 * 症状不是报错而是<b>某档长期不满</b>（例如 {@code contextTopN} 配得比 {@code rerankTopN} 大，
 * 就永远只有 {@code rerankTopN} 条进上下文）。这类错会在第 6 项调参时
 * 把"改这个参数没用"误读成"这个变量不影响指标"——于是调参结论整个作废。
 * 故每条规则各写一个用例，并断言错误消息里<b>点名</b>了冲突的两个参数。
 */
class RetrievalConfigTest {

    // ── 默认值 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ defaults()：recallTopK=30, fusionTopK=30, rerankTopN=10, contextTopN=5, rrfK=60, 重排开启")
    void defaultsMatchSpec() {
        RetrievalConfig d = RetrievalConfig.defaults();
        assertThat(d.recallTopK()).isEqualTo(30);
        assertThat(d.fusionTopK()).isEqualTo(30);
        assertThat(d.rerankTopN()).isEqualTo(10);
        assertThat(d.contextTopN()).isEqualTo(5);
        assertThat(d.rrfK()).isEqualTo(60);
        assertThat(d.rerankEnabled()).isTrue();
        assertThat(d.fusionName()).isEqualTo(RrfFusion.NAME);
        assertThat(d.rerankerName()).isEqualTo(LlmReranker.NAME);
    }

    @Test
    @DisplayName("通道标识：开启重排是 rrf+llm-rerank，关闭是 rrf（产物按它区分消融档）")
    void channelLabel() {
        assertThat(RetrievalConfig.defaults().channelLabel()).isEqualTo("rrf+llm-rerank");
        assertThat(RetrievalConfig.defaults().withoutRerank().channelLabel()).isEqualTo("rrf");
    }

    @Test
    @DisplayName("with* 方法返回新实例并重新校验（不是绕过校验的后门）")
    void withMethodsRevalidate() {
        RetrievalConfig d = RetrievalConfig.defaults();
        assertThat(d.withContextTopN(10).contextTopN()).isEqualTo(10);
        assertThat(d.withContextTopN(10)).isNotSameAs(d);
        assertThat(d.withRecallTopK(50).recallTopK()).isEqualTo(50);
        assertThat(d.withRerank().rerankEnabled()).isTrue();
        assertThat(d.withoutRerank().rerankEnabled()).isFalse();

        // 20 > rerankTopN(10) → 必须仍然报错（若 with 方法漏了校验，这里会静默通过）
        assertThatThrownBy(() -> d.withContextTopN(20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextTopN(20)")
                .hasMessageContaining("rerankTopN(10)");
    }

    // ── 逐条非法组合 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("recallTopK = 0 → 报错并点名参数")
    void recallTopKMustBePositive() {
        assertThatThrownBy(() -> new RetrievalConfig(0, 30, 10, 5, 60, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recallTopK")
                .hasMessageContaining(">= 1");
    }

    @Test
    @DisplayName("fusionTopK = 0 → 报错并点名参数")
    void fusionTopKMustBePositive() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 0, 10, 5, 60, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fusionTopK");
    }

    @Test
    @DisplayName("rerankTopN = 0 → 报错并点名参数")
    void rerankTopNMustBePositive() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 30, 0, 5, 60, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rerankTopN");
    }

    @Test
    @DisplayName("contextTopN = 0 → 报错并点名参数")
    void contextTopNMustBePositive() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 30, 10, 0, 60, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextTopN");
    }

    @Test
    @DisplayName("rrfK = 0 → 报错并点名参数（k 是分母里的常数，不许为 0）")
    void rrfKMustBePositive() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 30, 10, 5, 0, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rrfK");
    }

    @Test
    @DisplayName("★ rerankTopN > fusionTopK → 报错，消息点名两者（重排池不可能比融合结果大）")
    void rerankTopNCannotExceedFusionTopK() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 10, 20, 5, 60, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rerankTopN(20)")
                .hasMessageContaining("fusionTopK(10)");
    }

    @Test
    @DisplayName("★ 开启重排时 contextTopN > rerankTopN → 报错，消息点名两者")
    void contextTopNCannotExceedRerankTopN() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 30, 10, 11, 60, true, "rrf", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextTopN(11)")
                .hasMessageContaining("rerankTopN(10)");
    }

    @Test
    @DisplayName("★ 关闭重排时上下文取自融合序，故上限换成 fusionTopK（消息里也要说清楚）")
    void contextTopNCannotExceedFusionTopKWhenRerankOff() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 10, 10, 11, 60, false, "rrf", "noop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextTopN(11)")
                .hasMessageContaining("fusionTopK(10)")
                .hasMessageContaining("rerankEnabled=false");
    }

    @Test
    @DisplayName("fusionName / rerankerName 不得为空（它们要写进产物，空名字让两档无法区分）")
    void namesMustNotBeBlank() {
        assertThatThrownBy(() -> new RetrievalConfig(30, 30, 10, 5, 60, true, "  ", "llm-rerank"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fusionName");
        assertThatThrownBy(() -> new RetrievalConfig(30, 30, 10, 5, 60, true, "rrf", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rerankerName");
    }

    @Test
    @DisplayName("fusionTopK 与通道数无关：融合是并集，不校验「总和不等于通道数×召回数」")
    void fusionTopKIsNotBoundedByChannelCount() {
        // 单通道、fusionTopK 远大于 recallTopK —— 合法（融合后条数只是被截断，不是错误）
        RetrievalConfig cfg = new RetrievalConfig(3, 100, 10, 5, 60, true, "rrf", "llm-rerank");
        assertThat(cfg.fusionTopK()).isEqualTo(100);
    }
}
