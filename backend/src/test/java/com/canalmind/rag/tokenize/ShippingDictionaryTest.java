package com.canalmind.rag.tokenize;

import com.huaban.analysis.jieba.WordDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 航运词典加载与格式校验的测试（第 2 周第 4 项）。
 *
 * <p>这些用例守的是一条**没有报错信号的失败路径**：jieba-analysis 的
 * {@code loadUserDict} 对"三列格式"不报错、对"IOException"也不抛出，
 * 两种情况下词典都会<b>静默失效</b>，症状只是"某些词被切碎、召回变低"。
 * 所以这里既测"合法输入能加载"，也逐条测"非法输入会停下"。
 */
class ShippingDictionaryTest {

    /** ⛔ 用计算字符表达换行，不用转义字面量（AGENTS.md §7.2 第 1、2 条）。 */
    private static final String LF = String.valueOf((char) 10);

    @Test
    @DisplayName("合法格式：恰好两列「词 词频」→ 解析成功")
    void parsesTwoColumnLines() {
        Map<String, Integer> freqs = ShippingDictionary.parse("过闸费 100000" + LF + "船闸 100000" + LF);
        assertThat(freqs).containsExactly(
                Map.entry("过闸费", 100000),
                Map.entry("船闸", 100000));
    }

    @Test
    @DisplayName("⚠️ 三列（标准 jieba 的「词 词频 tag」）→ 报错，而不是静默用默认词频 3.0")
    void rejectsThreeColumnLines() {
        // 这是本类最重要的一条：jieba 只在 tokens.length == 2 时取词频，
        // 三列会退回 3.0，log(3/60101878) = -16.81，比基础词典任何词都低 → 词被切碎。
        assertThatThrownBy(() -> ShippingDictionary.parse("过闸费 100000 n" + LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("恰好两列")
                .hasMessageContaining("3.0")
                .hasMessageContaining("build_shipping_dictionary.py");
    }

    @Test
    @DisplayName("⚠️ 注释行 → 报错（jieba 会解析成「词=#」并在 Double.valueOf 抛未捕获异常）")
    void rejectsCommentLines() {
        assertThatThrownBy(() -> ShippingDictionary.parse("# 港口" + LF + "过闸费 100000" + LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注释行");
    }

    @Test
    @DisplayName("中间的空行 → 报错（jieba 会 freqs.put(null, ...)）")
    void rejectsBlankLineInMiddle() {
        assertThatThrownBy(() -> ShippingDictionary.parse("过闸费 100000" + LF + LF + "船闸 100000" + LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空行");
    }

    @Test
    @DisplayName("重复词 → 报错（否则词频取决于是哪一行先被读到）")
    void rejectsDuplicateWords() {
        assertThatThrownBy(() -> ShippingDictionary.parse("过闸费 100000" + LF + "过闸费 500" + LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复词");
    }

    @Test
    @DisplayName("词频非正整数 → 报错")
    void rejectsBadFrequency() {
        assertThatThrownBy(() -> ShippingDictionary.parse("过闸费 0" + LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("词频");
        assertThatThrownBy(() -> ShippingDictionary.parse("过闸费 高" + LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是整数");
    }

    @Test
    @DisplayName("只有结尾换行是允许的（文件以 LF 结尾）")
    void allowsSingleTrailingNewline() {
        assertThat(ShippingDictionary.parse("过闸费 100000" + LF)).hasSize(1);
        assertThat(ShippingDictionary.parse("过闸费 100000")).hasSize(1);
    }

    @Test
    @DisplayName("空词典 → 报错（空词典会让 BM25 通道静默退化成\"什么都召不回\"）")
    void rejectsEmptyDictionary() {
        assertThatThrownBy(() -> ShippingDictionary.parse(LF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空");
    }

    @Test
    @DisplayName("资源指纹：sha256 是 64 位小写十六进制，且与文件字节一致")
    void resourceIsFingerprinted() throws Exception {
        assertThat(ShippingDictionary.sha256()).matches("^[0-9a-f]{64}$");
        assertThat(ShippingDictionary.VERSION).isEqualTo("shipping-dict-v1");

        // 独立重算一遍：指纹必须来自**文件字节**，而不是某个写死的常量
        byte[] bytes = getClass().getResourceAsStream(ShippingDictionary.RESOURCE_PATH).readAllBytes();
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
        }
        assertThat(ShippingDictionary.sha256()).isEqualTo(hex.toString());
    }

    @Test
    @DisplayName("词典覆盖业务术语：港口/货种/船型/费种/项目自建术语都在（来自权威来源）")
    void coversBusinessTerms() {
        assertThat(ShippingDictionary.words())
                .contains("南宁港六景作业区", "平陆运河", "马道枢纽船闸", "危险货物专用闸次",
                        "过闸费", "干散货船", "标准箱", "费率")
                .hasSizeGreaterThan(100);
    }

    @Test
    @DisplayName("⚠️ 加载后核对：每个词条都真的进了 jieba 词表（loadUserDict 会吞掉 IOException）")
    void everyWordReachedJiebaTrie() {
        WordDictionary dictionary = WordDictionary.getInstance();
        assertThat(ShippingDictionary.words()).allSatisfy(
                word -> assertThat(dictionary.containsWord(word))
                        .as("词典词 %s 应已进入 jieba 词表（否则说明 loadUserDict 静默失败了）", word)
                        .isTrue());
    }
}
