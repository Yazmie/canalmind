package com.canalmind.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RRF 融合的测试（第 2 周第 5 项，C-26）。
 *
 * <p><b>为什么期望值必须手算</b>：如果断言写成 {@code 1.0/(60+1) + 1.0/(60+3)}，
 * 那只是把实现里的公式抄了一遍——实现错了断言也会"通过"，
 * 评测出来的一切增益都建立在同一个错公式上。故本测试把
 * <b>名次、k、以及每条分数的小数</b>都写死在注释里，期望值用<b>字面量</b>断言。
 *
 * <p>手算过程（两路各 3 条，k = 60）：
 *
 * <pre>
 *   向量路 V（名次 1..3）: c1  c2  c3
 *   BM25 路 B（名次 1..3）: c2  c4  c1
 *
 *   倒数分母：1/61 = 0.016393442622950820
 *             1/62 = 0.016129032258064516
 *             1/63 = 0.015873015873015873
 *
 *   c1：V 第 1 名 + B 第 3 名 = 1/61 + 1/63 = 0.032266458495966693
 *   c2：V 第 2 名 + B 第 1 名 = 1/62 + 1/61 = 0.032522474881015336
 *   c3：V 第 3 名              = 1/63      = 0.015873015873015873
 *   c4：B 第 2 名              = 1/62      = 0.016129032258064516
 *
 *   降序：c2 (0.032522474881015336) &gt; c1 (0.032266458495966693)
 *        &gt; c4 (0.016129032258064516) &gt; c3 (0.015873015873015873)
 * </pre>
 *
 * <p>容差取 1e-12：手算小数只写到第 18 位，而累加顺序会影响末位比特，
 * 用位级相等断言反而会把"浮点累加顺序"这种无关变量变成测试脆弱点。
 */
class RrfFusionTest {

    private static final double K60_RANK1 = 1.0 / 61;
    private static final double K60_RANK2 = 1.0 / 62;
    private static final double K60_RANK3 = 1.0 / 63;

    // ── 手算自证 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 手算自证：k=60、两路各 3 条，逐条分数与降序结果与纸面计算一致")
    void handComputedScoresAndOrder() {
        List<RetrievalCandidate> vector = List.of(
                hit("c1", VectorRetrievalChannel.NAME, 0.90, 1),
                hit("c2", VectorRetrievalChannel.NAME, 0.80, 2),
                hit("c3", VectorRetrievalChannel.NAME, 0.70, 3));
        List<RetrievalCandidate> bm25 = List.of(
                hit("c2", Bm25RetrievalChannel.NAME, 31.4, 1),
                hit("c4", Bm25RetrievalChannel.NAME, 21.0, 2),
                hit("c1", Bm25RetrievalChannel.NAME, 12.5, 3));

        List<RetrievalCandidate> fused = new RrfFusion(60).fuse(List.of(vector, bm25), 10);

        assertThat(ids(fused)).containsExactly("c2", "c1", "c4", "c3");

        assertThat(score(fused, "c2"))
                .as("c2 = 1/62（V 第 2）+ 1/61（B 第 1）= 0.032522474881015336")
                .isCloseTo(0.032522474881015336, within(1e-12));
        assertThat(score(fused, "c1"))
                .as("c1 = 1/61（V 第 1）+ 1/63（B 第 3）= 0.032266458495966693")
                .isCloseTo(0.032266458495966693, within(1e-12));
        assertThat(score(fused, "c4"))
                .as("c4 = 1/62（B 第 2）= 0.016129032258064516")
                .isCloseTo(0.016129032258064516, within(1e-12));
        assertThat(score(fused, "c3"))
                .as("c3 = 1/63（V 第 3）= 0.015873015873015873")
                .isCloseTo(0.015873015873015873, within(1e-12));

        // k 的默认值就是 C-26 的 60（不是随手取的）
        assertThat(new RrfFusion().k()).isEqualTo(60);
        assertThat(RrfFusion.DEFAULT_K).isEqualTo(60);
        assertThat(new RrfFusion().name()).isEqualTo("rrf");
    }

    @Test
    @DisplayName("k 真的进了公式：k=1 时单路分数退化为 1/2、1/3（而不是被当成常量忽略）")
    void kIsActuallyUsed() {
        List<RetrievalCandidate> only = List.of(
                hit("a", "ch", 1.0, 1), hit("b", "ch", 0.5, 2));
        List<RetrievalCandidate> fused = new RrfFusion(1).fuse(List.of(only), 10);
        assertThat(score(fused, "a")).isCloseTo(0.5, within(1e-12));
        assertThat(score(fused, "b")).isCloseTo(1.0 / 3, within(1e-12));
    }

    // ── 并集合并 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("并集：同一 chunk 出现在两路时合并成一条，channelScores/channelRanks 是两路的并集")
    void unionMergesChannelData() {
        List<RetrievalCandidate> vector = List.of(
                hit("c1", VectorRetrievalChannel.NAME, 0.90, 1),
                hit("c2", VectorRetrievalChannel.NAME, 0.80, 2));
        List<RetrievalCandidate> bm25 = List.of(
                hit("c2", Bm25RetrievalChannel.NAME, 31.4, 1),
                hit("c1", Bm25RetrievalChannel.NAME, 12.5, 2));

        List<RetrievalCandidate> fused = new RrfFusion(60).fuse(List.of(vector, bm25), 10);

        assertThat(fused).hasSize(2);
        RetrievalCandidate c1 = byId(fused, "c1");
        assertThat(c1.channelScores())
                .containsEntry(VectorRetrievalChannel.NAME, 0.90)
                .containsEntry(Bm25RetrievalChannel.NAME, 12.5);
        assertThat(c1.channelRanks())
                .containsEntry(VectorRetrievalChannel.NAME, 1)
                .containsEntry(Bm25RetrievalChannel.NAME, 2);
        // 键序 = 首次出现顺序（先 V 后 B）→ 产物 JSON 的键序才可复现
        assertThat(c1.channelScores().keySet())
                .containsExactly(VectorRetrievalChannel.NAME, Bm25RetrievalChannel.NAME);

        // 元数据保留先出现的那一路（两条来自同一行 rag_chunk，正常逐字段相同）
        assertThat(c1.docId()).isEqualTo("doc-c1");
    }

    @Test
    @DisplayName("同一路内重复出现同一 chunk：只计首个位置的名次，重复项不再加分")
    void duplicateWithinOneListCountedOnce() {
        List<RetrievalCandidate> list = List.of(
                hit("a", "ch", 9.0, 1),
                hit("a", "ch", 8.0, 2),   // 重复：不该让 a 得两份分
                hit("b", "ch", 7.0, 3));
        List<RetrievalCandidate> fused = new RrfFusion(60).fuse(List.of(list), 10);

        assertThat(ids(fused)).containsExactly("a", "b");
        assertThat(score(fused, "a"))
                .as("a 只按第 1 名计一次 = 1/61")
                .isCloseTo(K60_RANK1, within(1e-12));
        assertThat(score(fused, "b"))
                .as("b 的位置名次是 3（第 2 位被重复项占着），故 = 1/63")
                .isCloseTo(K60_RANK3, within(1e-12));
        assertThat(byId(fused, "a").channelRank("ch")).isEqualTo(1);
    }

    // ── 决胜规则 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 同分按 chunkId 升序决胜（构造必然同分：两路各只有一条、名次都是 1）")
    void tieIsBrokenByChunkIdAscending() {
        // zzz 先出现、aaa 后出现，两者分数完全相同（都是 1/61）
        List<RetrievalCandidate> first = List.of(hit("zzz", VectorRetrievalChannel.NAME, 0.9, 1));
        List<RetrievalCandidate> second = List.of(hit("aaa", Bm25RetrievalChannel.NAME, 9.9, 1));

        List<RetrievalCandidate> fused = new RrfFusion(60).fuse(List.of(first, second), 10);

        assertThat(score(fused, "zzz")).isCloseTo(K60_RANK1, within(1e-12));
        assertThat(score(fused, "aaa")).isCloseTo(K60_RANK1, within(1e-12));
        assertThat(ids(fused))
                .as("同分必须按 chunkId 升序：否则两次运行的并列顺序可能互换，产物无法 diff")
                .containsExactly("aaa", "zzz");

        // 再跑一次，顺序必须一模一样（确定性）
        assertThat(ids(new RrfFusion(60).fuse(List.of(first, second), 10)))
                .containsExactly("aaa", "zzz");
    }

    // ── 截断与边界 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("topK 截断：只保留前 topK 条，且截断后仍按分数降序")
    void topKTruncates() {
        List<RetrievalCandidate> vector = List.of(
                hit("c1", VectorRetrievalChannel.NAME, 0.9, 1),
                hit("c2", VectorRetrievalChannel.NAME, 0.8, 2),
                hit("c3", VectorRetrievalChannel.NAME, 0.7, 3));
        List<RetrievalCandidate> bm25 = List.of(
                hit("c2", Bm25RetrievalChannel.NAME, 31.4, 1),
                hit("c4", Bm25RetrievalChannel.NAME, 21.0, 2),
                hit("c1", Bm25RetrievalChannel.NAME, 12.5, 3));

        assertThat(ids(new RrfFusion(60).fuse(List.of(vector, bm25), 2)))
                .containsExactly("c2", "c1");
        assertThat(new RrfFusion(60).fuse(List.of(vector, bm25), 10)).hasSize(4);
    }

    @Test
    @DisplayName("空输入：null / 空表 / 只含空路 / 空候选元素，一律返回空列表而不抛异常")
    void emptyInputs() {
        RrfFusion rrf = new RrfFusion(60);
        assertThat(rrf.fuse(null, 10)).isEmpty();
        assertThat(rrf.fuse(List.of(), 10)).isEmpty();
        assertThat(rrf.fuse(List.of(List.of(), List.of()), 10)).isEmpty();
        assertThat(rrf.fuse(nullList(), 10)).isEmpty();

        List<RetrievalCandidate> withNullElement = new ArrayList<>();
        withNullElement.add(null);
        assertThat(rrf.fuse(List.of(withNullElement), 10)).isEmpty();
    }

    @Test
    @DisplayName("单路输入：分数就是 1/(k+rank)，顺序保持不变")
    void singleListKeepsOrder() {
        List<RetrievalCandidate> only = List.of(
                hit("a", VectorRetrievalChannel.NAME, 0.9, 1),
                hit("b", VectorRetrievalChannel.NAME, 0.8, 2),
                hit("c", VectorRetrievalChannel.NAME, 0.7, 3));
        List<RetrievalCandidate> fused = new RrfFusion(60).fuse(List.of(only), 10);

        assertThat(ids(fused)).containsExactly("a", "b", "c");
        assertThat(score(fused, "a")).isCloseTo(K60_RANK1, within(1e-12));
        assertThat(score(fused, "b")).isCloseTo(K60_RANK2, within(1e-12));
        assertThat(score(fused, "c")).isCloseTo(K60_RANK3, within(1e-12));
    }

    @Test
    @DisplayName("返回列表不可变、元素不可变：融合结果不会被后续 put 悄悄改掉")
    void resultIsImmutable() {
        List<RetrievalCandidate> fused = new RrfFusion(60)
                .fuse(List.of(List.of(hit("a", "ch", 1.0, 1))), 10);
        assertThatThrownBy(() -> fused.add(RetrievalCandidate.of("b")))
                .isInstanceOf(UnsupportedOperationException.class);
        RetrievalCandidate c = fused.get(0);
        assertThatThrownBy(() -> c.channelScores().put("x", 1.0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("参数非法：k < 1、topK < 1 → 拒绝（不产出无意义的分数）")
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new RrfFusion(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rrfK");
        assertThatThrownBy(() -> new RrfFusion(60).fuse(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static RetrievalCandidate hit(String chunkId, String channel, double score, int rank) {
        return RetrievalCandidate.fromHit(channel, score, rank,
                chunkId, "doc-" + chunkId, "文档 " + chunkId, "BODY",
                "第" + rank + "条", "标题 " + chunkId, "2026");
    }

    private static List<List<RetrievalCandidate>> nullList() {
        List<List<RetrievalCandidate>> lists = new ArrayList<>();
        lists.add(null);
        return lists;
    }

    private static List<String> ids(List<RetrievalCandidate> list) {
        return list.stream().map(RetrievalCandidate::chunkId).toList();
    }

    private static double score(List<RetrievalCandidate> list, String chunkId) {
        return byId(list, chunkId).fusedScore();
    }

    private static RetrievalCandidate byId(List<RetrievalCandidate> list, String chunkId) {
        return list.stream().filter(c -> c.chunkId().equals(chunkId)).findFirst()
                .orElseThrow(() -> new AssertionError("融合结果里没有 " + chunkId));
    }
}
