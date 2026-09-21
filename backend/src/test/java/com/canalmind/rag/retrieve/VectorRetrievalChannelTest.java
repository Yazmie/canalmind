package com.canalmind.rag.retrieve;

import com.canalmind.rag.embed.EmbeddingClient.EmbeddingException;
import com.canalmind.rag.vector.VectorHit;
import com.canalmind.rag.vector.VectorQueryOptions;
import com.canalmind.rag.vector.VectorSearcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 向量通道适配的测试（第 2 周第 5 项）。
 *
 * <p>本类只验<b>形状转换</b>（Hit → 候选）与名次口径。它值得单独测，是因为
 * 字段错位、名次从 0 起、分数不归一化这类问题<b>都不会报错</b>：
 * 候选看上去完整、下游也照常算出指标，只是指标不再是"这一路"的指标。
 * 融合层与评测脚本都建立在这份映射上，故这里逐字段钉住。
 *
 * <p>用 Mockito 桩掉 {@link VectorSearcher}：向量通道的检索正确性由
 * {@code VectorSearcherTest} 覆盖（它连库）；本测试不该依赖数据库与 Ollama。
 */
class VectorRetrievalChannelTest {

    @Test
    @DisplayName("★ Hit → 候选：7 个溯源字段逐一对应，score 写进 channelScores，名次从 1 起")
    void mapsHitToCandidate() throws Exception {
        VectorSearcher searcher = Mockito.mock(VectorSearcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(
                        new VectorHit("c1", "06-过闸费费率表（2026版）", "过闸费费率表（2026版）",
                                "BODY", "第一条", "目的与依据", "2026", 0.812345),
                        new VectorHit("c2", "05-计费总则", "计费总则", "TABLE", null, null, "2026",
                                0.5)));

        List<RetrievalCandidate> out = new VectorRetrievalChannel(searcher).search("过闸费怎么算", 30);

        assertThat(out).hasSize(2);
        assertThat(VectorRetrievalChannel.NAME).isEqualTo("vector");

        RetrievalCandidate first = out.get(0);
        assertThat(first.chunkId()).isEqualTo("c1");
        assertThat(first.docId()).isEqualTo("06-过闸费费率表（2026版）");
        assertThat(first.docTitle()).isEqualTo("过闸费费率表（2026版）");
        assertThat(first.chunkType()).isEqualTo("BODY");
        assertThat(first.articleNo()).isEqualTo("第一条");
        assertThat(first.articleTitle()).isEqualTo("目的与依据");
        assertThat(first.version()).isEqualTo("2026");
        assertThat(first.channelScores()).containsOnlyKeys("vector");
        assertThat(first.channelScores().get("vector")).isCloseTo(0.812345, within(1e-12));
        assertThat(first.channelRanks()).containsOnlyKeys("vector");
        assertThat(first.channelRanks().get("vector"))
                .as("名次 = 返回顺序，且从 1 起（从 0 起会让整列偏一位）")
                .isEqualTo(1);
        assertThat(first.fusedScore()).isZero();
        assertThat(first.rerankRank()).isNull();
        assertThat(first.rerankScore()).isNull();

        RetrievalCandidate second = out.get(1);
        assertThat(second.channelRanks().get("vector"))
                .as("第二条的名次是 2（名次就是返回顺序）")
                .isEqualTo(2);
        assertThat(second.articleNo()).as("文档级 chunk 的条号可以是 null").isNull();
        assertThat(second.articleTitle()).isNull();
    }

    @Test
    @DisplayName("分数不做归一化：原样放进 channelScores（C-26 要求融合只用名次、不改写原始分）")
    void scoreIsNotNormalized() throws Exception {
        VectorSearcher searcher = Mockito.mock(VectorSearcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenReturn(List.of(
                        new VectorHit("c1", "d", "t", "BODY", null, null, "2026", -0.25),
                        new VectorHit("c2", "d", "t", "BODY", null, null, "2026", 1.0)));

        List<RetrievalCandidate> out = new VectorRetrievalChannel(searcher).search("q", 2);
        assertThat(out.get(0).channelScores().get("vector")).isEqualTo(-0.25);
        assertThat(out.get(1).channelScores().get("vector")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("topK 原样透传给 VectorQueryOptions（接口口径不得被适配层悄悄改掉）")
    void passesTopKThrough() throws Exception {
        VectorSearcher searcher = Mockito.mock(VectorSearcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any())).thenReturn(List.of());

        new VectorRetrievalChannel(searcher).search("过闸费", 17);

        ArgumentCaptor<VectorQueryOptions> captor = ArgumentCaptor.forClass(VectorQueryOptions.class);
        Mockito.verify(searcher).search(Mockito.eq("过闸费"), captor.capture());
        VectorQueryOptions options = captor.getValue();
        assertThat(options.topK()).isEqualTo(17);
        assertThat(options.docId()).as("适配层不额外加过滤条件（否则与单通道基线不可比）").isNull();
        assertThat(options.docCategoryNo()).isNull();
        assertThat(options.chunkType()).isNull();
        assertThat(options.asOf()).isNull();
    }

    @Test
    @DisplayName("空命中：返回空列表，不报错（query 切不出东西/库里没数据都走这条路）")
    void emptyHits() throws Exception {
        VectorSearcher searcher = Mockito.mock(VectorSearcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any())).thenReturn(List.of());
        assertThat(new VectorRetrievalChannel(searcher).search("q", 5)).isEmpty();
    }

    @Test
    @DisplayName("底层受检异常 → 包成 RetrievalChannelException，且**保留 cause**（档案不匹配要能一眼看到）")
    void wrapsCheckedExceptionKeepingCause() throws Exception {
        VectorSearcher searcher = Mockito.mock(VectorSearcher.class);
        Mockito.when(searcher.search(Mockito.anyString(), Mockito.any()))
                .thenThrow(new EmbeddingException("连不上 Ollama"));

        assertThatThrownBy(() -> new VectorRetrievalChannel(searcher).search("过闸费", 5))
                .isInstanceOf(RetrievalChannelException.class)
                .hasMessageContaining("向量通道检索失败")
                .hasMessageContaining("EmbeddingException")
                .hasCauseInstanceOf(EmbeddingException.class);
    }

    @Test
    @DisplayName("topK < 1 → 拒绝（不让「条数为 0」变成一份空结果）")
    void rejectsNonPositiveTopK() {
        assertThatThrownBy(() -> new VectorRetrievalChannel(Mockito.mock(VectorSearcher.class))
                .search("q", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }
}
