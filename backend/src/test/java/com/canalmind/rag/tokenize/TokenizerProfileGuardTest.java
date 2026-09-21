package com.canalmind.rag.tokenize;

import com.canalmind.rag.tokenize.TokenizerProfileGuard.TokenizerProfileMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分词档案一致性闸门的测试——这是 {@code EmbeddingModelGuardTest} 的关键词通道版本。
 *
 * <p>共同前提：词典/模式不一致时检索<b>不报错</b>，只会"部分词永远召不回"。
 * 所以闸门必须<b>拒绝</b>查询，而不是警告后继续。
 */
class TokenizerProfileGuardTest {

    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static TokenizerProfile profile(String dictSha, String stopSha, String mode, String version) {
        return new TokenizerProfile("jieba:shipping-dict-v1:" + mode, "jieba-analysis", version, mode,
                "shipping-dict-v1", dictSha, 164, stopSha, 50, 1.2, 0.75);
    }

    @Test
    @DisplayName("两侧完全一致 → 放行")
    void consistentPasses() {
        TokenizerProfileGuard guard = new TokenizerProfileGuard(
                profile(SHA_A, SHA_B, "INDEX", "1.0.2"), profile(SHA_A, SHA_B, "INDEX", "1.0.2"));
        assertThat(guard.isConsistent()).isTrue();
        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⚠️ 词典 sha256 不同 → 拒绝，并点明是词典变了")
    void dictionaryChangeRejected() {
        TokenizerProfileGuard guard = new TokenizerProfileGuard(
                profile(SHA_A, SHA_B, "INDEX", "1.0.2"), profile(SHA_B, SHA_B, "INDEX", "1.0.2"));
        assertThat(guard.isConsistent()).isFalse();
        assertThatThrownBy(guard::verify)
                .isInstanceOf(TokenizerProfileMismatchException.class)
                .hasMessageContaining("拒绝查询")
                .hasMessageContaining("词典 sha256")
                .hasMessageContaining("全量重灌");
    }

    @Test
    @DisplayName("切分模式不同（INDEX vs SEARCH）→ 拒绝：词元集合本来就不同")
    void segmentModeChangeRejected() {
        TokenizerProfileGuard guard = new TokenizerProfileGuard(
                profile(SHA_A, SHA_B, "INDEX", "1.0.2"), profile(SHA_A, SHA_B, "SEARCH", "1.0.2"));
        assertThatThrownBy(guard::verify)
                .isInstanceOf(TokenizerProfileMismatchException.class)
                .hasMessageContaining("切分模式");
    }

    @Test
    @DisplayName("停用词表变了 → 拒绝（否则跑分差异无法归因到具体版本）")
    void stopwordsChangeRejected() {
        TokenizerProfileGuard guard = new TokenizerProfileGuard(
                profile(SHA_A, SHA_B, "INDEX", "1.0.2"), profile(SHA_A, SHA_A, "INDEX", "1.0.2"));
        assertThatThrownBy(guard::verify)
                .isInstanceOf(TokenizerProfileMismatchException.class)
                .hasMessageContaining("停用词表 sha256");
    }

    @Test
    @DisplayName("分词器版本不同 → 拒绝（升级依赖同样会改变切分结果）")
    void tokenizerVersionChangeRejected() {
        TokenizerProfileGuard guard = new TokenizerProfileGuard(
                profile(SHA_A, SHA_B, "INDEX", "1.0.2"), profile(SHA_A, SHA_B, "INDEX", "1.0.3"));
        assertThatThrownBy(guard::verify)
                .isInstanceOf(TokenizerProfileMismatchException.class)
                .hasMessageContaining("分词器版本");
    }

    @Test
    @DisplayName("库侧档案缺失 → 拒绝，并指明先建档案")
    void missingIndexProfileRejected() {
        TokenizerProfileGuard guard = new TokenizerProfileGuard(
                null, profile(SHA_A, SHA_B, "INDEX", "1.0.2"));
        assertThat(guard.isConsistent()).isFalse();
        assertThatThrownBy(guard::verify)
                .isInstanceOf(TokenizerProfileMismatchException.class)
                .hasMessageContaining("档案缺失")
                .hasMessageContaining("Bm25IngestCli");
    }

    @Test
    @DisplayName("真实档案 & 当前分词器 → 一致（把\"纸上规则\"接到真实资源上验一次）")
    void realProfileMatchesRealTokenizer() {
        JiebaShippingTokenizer tokenizer = new JiebaShippingTokenizer();
        TokenizerProfile fromIndex = tokenizer.profile(1.2, 0.75);
        TokenizerProfile fromQuery = new JiebaShippingTokenizer().profile(1.2, 0.75);
        assertThat(new TokenizerProfileGuard(fromIndex, fromQuery).isConsistent()).isTrue();
    }
}
