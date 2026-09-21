package com.canalmind.rag.tokenize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分词档案字段校验的测试：<b>Java 侧与 V4 迁移的 CHECK 约束必须一致</b>。
 *
 * <p>为什么要专门测：两处校验若不一致，表现是"Java 放行、数据库才拒绝"——
 * 错误出现在事务里、堆栈里，而真正的原因（参数非法）要翻半天。
 * 把校验钉在 record 的紧凑构造器上（与 {@code CorpusDocument} 同一手法），
 * 并在这里逐条断言，才谈得上"两处一致"。
 */
class TokenizerProfileTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("档案名构造：jieba:<词典版本>:rules-<规则版本>:<模式>（V3 的 ollama:bge-m3:1024 同风格）")
    void buildsProfileName() {
        assertThat(TokenizerProfile.buildProfileName("jieba-analysis", "shipping-dict-v1", "v2", "INDEX"))
                .isEqualTo("jieba:shipping-dict-v1:rules-v2:INDEX");
        assertThat(TokenizerProfile.buildProfileName("jieba-analysis", "shipping-dict-v2", "v3", "SEARCH"))
                .isEqualTo("jieba:shipping-dict-v2:rules-v3:SEARCH");
        // 规则版本为空 → 拒绝（否则档案名会退化成无法区分规则的两段式，静默失去可追溯性）
        assertThatThrownBy(() -> TokenizerProfile.buildProfileName("jieba-analysis", "shipping-dict-v1", " ", "INDEX"))
                .hasMessageContaining("rulesVersion");
    }

    @Test
    @DisplayName("非法切分模式 → 拒绝（与 rag_token_profile 的 segment_mode CHECK 一致）")
    void rejectsBadSegmentMode() {
        assertThatThrownBy(() -> profile("INDEX2", SHA, 1.2, 0.75))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segmentMode");
    }

    @Test
    @DisplayName("sha256 形状不对 → 拒绝（否则要到建档案时才被数据库拒绝）")
    void rejectsBadSha() {
        assertThatThrownBy(() -> profile("INDEX", "ABC", 1.2, 0.75))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionarySha256");
    }

    @Test
    @DisplayName("k1 越界（≤0 或 >100）→ 拒绝（与 rag_token_profile_k1_ck 一致）")
    void rejectsBadK1() {
        assertThatThrownBy(() -> profile("INDEX", SHA, 0, 0.75)).hasMessageContaining("bm25K1");
        assertThatThrownBy(() -> profile("INDEX", SHA, 101, 0.75)).hasMessageContaining("bm25K1");
    }

    @Test
    @DisplayName("b 越界（<0 或 >1）→ 拒绝（与 rag_token_profile_b_ck 一致）")
    void rejectsBadB() {
        assertThatThrownBy(() -> profile("INDEX", SHA, 1.2, -0.1)).hasMessageContaining("bm25B");
        assertThatThrownBy(() -> profile("INDEX", SHA, 1.2, 1.01)).hasMessageContaining("bm25B");
    }

    @Test
    @DisplayName("档案名/分词器名为空 → 拒绝")
    void rejectsBlankNames() {
        assertThatThrownBy(() -> new TokenizerProfile("  ", "jieba-analysis", "1.0.2", "INDEX",
                "shipping-dict-v1", SHA, 164, SHA, 50, 1.2, 0.75))
                .hasMessageContaining("profileName");
    }

    @Test
    @DisplayName("摘要含档案名与两侧指纹前缀（供 CLI/日志比对两次运行）")
    void summaryIsInformative() {
        String summary = profile("INDEX", SHA, 1.2, 0.75).summary();
        assertThat(summary)
                .contains("jieba:shipping-dict-v1:INDEX")
                .contains("jieba-analysis")
                .contains("shipping-dict-v1")
                .contains("01234567")
                .contains("k1=1.20")
                .contains("b=0.75");
    }

    private static TokenizerProfile profile(String mode, String sha, double k1, double b) {
        return new TokenizerProfile("jieba:shipping-dict-v1:" + mode, "jieba-analysis", "1.0.2", mode,
                "shipping-dict-v1", sha, 164, SHA, 50, k1, b);
    }
}
