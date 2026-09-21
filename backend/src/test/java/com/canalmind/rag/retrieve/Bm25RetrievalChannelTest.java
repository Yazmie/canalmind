package com.canalmind.rag.retrieve;

import com.canalmind.rag.bm25.Bm25Hit;
import com.canalmind.rag.bm25.Bm25QueryOptions;
import com.canalmind.rag.bm25.Bm25Searcher;
import com.canalmind.rag.tokenize.TokenizerProfileGuard.TokenizerProfileMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * BM25 通道适配的测试（第 2 周第 5 项）。
 *
 * <p>与 {@code VectorRetrievalChannelTest} 一一对应：同一个"Hit → 候选"的字段顺序，
 * 两路必须映射到<b>同一组</b>{@link RetrievalCandidate} 分量上，
 * 否则融合层会拿到"两路字段含义不同"的候选，而这种情况<b>不会报错</b>
 * ——只会在第 4 周复盘时表现为"某一路的 article 级别定位莫名失效"。
 *
 * <p>{@code Bm25Hit} 比 {@code VectorHit} 多一个 {@code matchedTerms}，
 * 本测试同时钉住"该字段<b>不</b>进候选"（候选不带它：它是解释性字段，
 * 融合与重排都用不到，带上只会让候选结构分叉）。
 */
class Bm25RetrievalChannelTest {

    @Test
    @DisplayName("★ Hit → 候选：7 个溯源字段逐一对应，score 写进 channelScores，名次从 1 起")
    void mapsHitToCandidate() throws Exception {
        Bm25Searcher searcher = Mockito.mock(Bm25Searcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(
                        new Bm25Hit("c2", "05-计费总则", "计费总则", "TABLE", "第三条", "计费单位",
                                "2026", 31.4, 3),
                        new Bm25Hit("c1", "06-过闸费费率表（2026版）", "过闸费费率表（2026版）",
                                "BODY", "第一条", "目的与依据", "2026", 12.5, 1)));

        List<RetrievalCandidate> out = new Bm25RetrievalChannel(searcher).search("过闸费怎么算", 30);

        assertThat(out).hasSize(2);
        assertThat(Bm25RetrievalChannel.NAME).isEqualTo("bm25");

        RetrievalCandidate first = out.get(0);
        assertThat(first.chunkId()).isEqualTo("c2");
        assertThat(first.docId()).isEqualTo("05-计费总则");
        assertThat(first.docTitle()).isEqualTo("计费总则");
        assertThat(first.chunkType()).isEqualTo("TABLE");
        assertThat(first.articleNo()).isEqualTo("第三条");
        assertThat(first.articleTitle()).isEqualTo("计费单位");
        assertThat(first.version()).isEqualTo("2026");
        assertThat(first.channelScores()).containsOnlyKeys("bm25");
        assertThat(first.channelScores().get("bm25"))
                .as("BM25 原始分原样保留（无上界，故融合只用名次——C-26）")
                .isCloseTo(31.4, within(1e-12));
        assertThat(first.channelRanks()).containsOnlyKeys("bm25");
        assertThat(first.channelRanks().get("bm25")).isEqualTo(1);
        assertThat(first.fusedScore()).isZero();
        assertThat(first.rerankRank()).isNull();
        assertThat(first.rerankScore()).isNull();

        assertThat(out.get(1).channelRanks().get("bm25")).isEqualTo(2);
    }

    @Test
    @DisplayName("matchedTerms 不进候选（它是解释性字段，带上会让两路候选结构分叉）")
    void matchedTermsIsNotPropagated() throws Exception {
        Bm25Searcher searcher = Mockito.mock(Bm25Searcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(new Bm25Hit("c1", "d", "t", "BODY", null, null, "2026",
                        9.9, 7)));

        RetrievalCandidate c = new Bm25RetrievalChannel(searcher).search("q", 1).get(0);
        // 候选只有"两个 Map + 三个数值"这三类附加数据；matchedTerms 不在其中
        assertThat(c.channelScores()).hasSize(1);
        assertThat(c.channelRanks()).hasSize(1);
        assertThat(c.fusedScore()).isZero();
    }

    @Test
    @DisplayName("topK 原样透传，且适配层不额外加过滤条件")
    void passesTopKThrough() throws Exception {
        Bm25Searcher searcher = Mockito.mock(Bm25Searcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any())).thenReturn(List.of());

        new Bm25RetrievalChannel(searcher).search("过闸费怎么算", 12);

        ArgumentCaptor<Bm25QueryOptions> captor = ArgumentCaptor.forClass(Bm25QueryOptions.class);
        Mockito.verify(searcher).search(Mockito.eq("过闸费怎么算"), captor.capture());
        Bm25QueryOptions options = captor.getValue();
        assertThat(options.topK()).isEqualTo(12);
        assertThat(options.docId()).isNull();
        assertThat(options.docCategoryNo()).isNull();
        assertThat(options.chunkType()).isNull();
        assertThat(options.asOf()).isNull();
    }

    @Test
    @DisplayName("查询原样传给 searcher：不 trim、不改写（分词口径由档案守卫负责）")
    void queryIsPassedVerbatim() throws Exception {
        Bm25Searcher searcher = Mockito.mock(Bm25Searcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any())).thenReturn(List.of());

        new Bm25RetrievalChannel(searcher).search("  过闸费 怎么算  ", 5);

        Mockito.verify(searcher).search("  过闸费 怎么算  ", Bm25QueryOptions.topK(5));
    }

    @Test
    @DisplayName("底层受检异常 → 包成 RetrievalChannelException，并保留 cause（档案不匹配要能一眼看到）")
    void wrapsCheckedExceptionKeepingCause() throws Exception {
        Bm25Searcher searcher = Mockito.mock(Bm25Searcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenThrow(new TokenizerProfileMismatchException("词典 sha256 与库内档案不一致"));

        assertThatThrownBy(() -> new Bm25RetrievalChannel(searcher).search("过闸费", 5))
                .isInstanceOf(RetrievalChannelException.class)
                .hasMessageContaining("BM25 通道检索失败")
                .hasMessageContaining("TokenizerProfileMismatchException")
                .hasCauseInstanceOf(TokenizerProfileMismatchException.class);
    }

    @Test
    @DisplayName("空命中与 topK<1 的边界")
    void emptyHitsAndInvalidTopK() throws Exception {
        Bm25Searcher searcher = Mockito.mock(Bm25Searcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any())).thenReturn(List.of());
        assertThat(new Bm25RetrievalChannel(searcher).search("q", 5)).isEmpty();

        assertThatThrownBy(() -> new Bm25RetrievalChannel(searcher).search("q", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }
}
