package com.canalmind.rag.bm25;

import com.canalmind.rag.tokenize.JiebaShippingTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BM25 检索层的契约测试（第 2 周第 4 项）。
 *
 * <p><b>覆盖边界（诚实说明）</b>：本项目无 Docker、无测试库（C-12），
 * 故这里只测"不碰数据库就能测"的部分——参数校验、空查询短路、档案名确定规则。
 * <b>打分公式本身的正确性不由本文件保证</b>，而由
 * {@code backend/scripts/verify_bm25_fixture.sql}（手算期望值的夹具测试）
 * 与 {@code backend/scripts/verify_bm25.py}（独立重算 + 真实库）保证。
 * 分工写在这里，免得后来者以为"跑过单测就等于 BM25 是对的"。
 */
class Bm25ContractTest {

    // ── 参数与选项 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("默认参数是 Lucene 的 k1=1.2 / b=0.75")
    void luceneDefaults() {
        assertThat(Bm25Params.luceneDefaults().k1()).isEqualTo(1.2);
        assertThat(Bm25Params.luceneDefaults().b()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("k1/b 越界 → 拒绝（与数据库 CHECK 同口径）")
    void rejectsBadParams() {
        assertThatThrownBy(() -> new Bm25Params(0, 0.75)).hasMessageContaining("k1");
        assertThatThrownBy(() -> new Bm25Params(1.2, -0.01)).hasMessageContaining("b");
    }

    @Test
    @DisplayName("topK < 1 → 拒绝；chunkType 只允许 BODY/TABLE（与 V3 的 CHECK 一致）")
    void rejectsBadOptions() {
        assertThatThrownBy(() -> Bm25QueryOptions.topK(0)).hasMessageContaining("topK");
        assertThatThrownBy(() -> new Bm25QueryOptions(5, null, null, "TABLE2", null))
                .hasMessageContaining("chunkType");
    }

    @Test
    @DisplayName("过滤器可叠加，且不改变 topK（改一处只影响一处）")
    void optionsAreImmutable() {
        Bm25QueryOptions base = Bm25QueryOptions.topK(10).withAsOf(LocalDate.of(2026, 6, 30));
        Bm25QueryOptions narrowed = base.withDocId("PLYH-REG-2026-001");

        assertThat(base.docId()).isNull();
        assertThat(base.asOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(narrowed.docId()).isEqualTo("PLYH-REG-2026-001");
        assertThat(narrowed.asOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(narrowed.topK()).isEqualTo(10);
    }

    // ── 检索器：不碰数据库的部分 ──────────────────────────────────────────

    @Test
    @DisplayName("查询切不出词元（全是停用词）→ 直接返回空，**不查库**（没有可匹配的信号）")
    void emptyTermsShortCircuitWithoutTouchingDatabase() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        Bm25Searcher searcher = new Bm25Searcher(
                jdbc, new JiebaShippingTokenizer(), "jieba:shipping-dict-v1:INDEX");

        assertThat(searcher.search("的的的", Bm25QueryOptions.topK(5))).isEmpty();
        assertThat(searcher.search("，。？！", Bm25QueryOptions.topK(5))).isEmpty();
        assertThat(searcher.search(null, Bm25QueryOptions.topK(5))).isEmpty();
        // 关键断言：短路口径下不该产生任何数据库交互（连档案校验都不必做）
        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("查询分词用的是同一个分词器口径（CLI 的 --tokens 靠它解释\"为什么召不回\"）")
    void queryTokenizationUsesTheSameTokenizer() {
        Bm25Searcher searcher = new Bm25Searcher(
                Mockito.mock(JdbcTemplate.class), new JiebaShippingTokenizer(), "jieba:shipping-dict-v1:INDEX");
        assertThat(searcher.tokenizeQuery("过闸费怎么算")).contains("过闸费");
        assertThat(searcher.tokenizeQuery("的的的")).isEmpty();
    }

    @Test
    @DisplayName("档案名为空 → 构造即报错（避免构造出一个\"查哪个档案都行\"的检索器）")
    void blankProfileNameRejected() {
        assertThatThrownBy(() -> new Bm25Searcher(
                Mockito.mock(JdbcTemplate.class), new JiebaShippingTokenizer(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileName");
    }

    @Test
    @DisplayName("命中摘要含分数、命中词数与溯源字段（评测定位 badcase 靠它）")
    void hitSummaryIsTraceable() {
        Bm25Hit hit = new Bm25Hit("c1", "PLYH-REG-2026-001", "平陆运河过闸费费率表", "BODY",
                "第三条", "计费单位", "V2.0", 3.14159, 2);
        assertThat(hit.summary())
                .contains("3.141590")
                .contains("命中2词")
                .contains("PLYH-REG-2026-001")
                .contains("第三条")
                .contains("c1");
    }
}
