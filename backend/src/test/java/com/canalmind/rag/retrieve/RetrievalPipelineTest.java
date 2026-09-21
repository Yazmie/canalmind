package com.canalmind.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 检索链路的测试（第 2 周第 5 项）：<b>三档 Top-K 的语义</b>、无 rerank 档、降级档、空查询短路。
 *
 * <p>这组测试盯的是"条数在每个阶段到底等于多少"。这类错误<b>全都不会报错</b>：
 * {@code recallTopK} 传错一个变量、{@code contextTopN} 取错一档，
 * 结果都是一份看起来正常的候选列表——只是不再是你以为的那一档，
 * 而第 4 周的消融会把这个差异记到"融合/重排的效果"头上。
 *
 * <p>通道与重排器一律用桩：本类只验<b>编排</b>（谁调谁、每次要多少条、边界怎么取），
 * 连库与调模型分别由通道测试与 {@code LlmRerankerTest} 覆盖。
 */
class RetrievalPipelineTest {

    private static final String PROBE = "过闸费怎么算";

    /** 读产物 JSON 用（B1 的等式断言在产物上做：`hits`/`topK` 都是产物里的键）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 三档 Top-K 语义 ───────────────────────────────────────────────────

    @Test
    @DisplayName("★ 三档 Top-K：recallTopK=30 → 每通道要 30 条；融合 20；重排 10；上下文 5")
    void threeTierTopKSemantics() {
        StubChannel a = new StubChannel("stub-a", 40);
        StubChannel b = new StubChannel("stub-b", 40);
        StubReranker reranker = new StubReranker("llm-rerank", null);
        RetrievalConfig config = new RetrievalConfig(30, 20, 10, 5, 60, true,
                RrfFusion.NAME, reranker.name());

        RetrievalPipeline pipeline = new RetrievalPipeline(List.of(a, b),
                new RrfFusion(60), reranker, config);
        RetrievalResult result = pipeline.retrieve(PROBE);

        // ① 召回：每路只应被"要" recallTopK 条（要多了等于偷偷换了实验条件）
        assertThat(a.requestedTopK).containsExactly(30);
        assertThat(b.requestedTopK).containsExactly(30);
        assertThat(result.trace("recall:stub-a")).hasSize(30);
        assertThat(result.trace(RetrievalPipeline.STAGE_RECALL))
                .as("两个桩通道给的 id 不重叠，故并集 = 30 + 30")
                .hasSize(60);

        // ② 融合：截到 fusionTopK
        assertThat(result.fused()).hasSize(20);
        assertThat(result.trace(RetrievalResult.STAGE_FUSION)).hasSize(20);

        // ③ 重排：输入是整份融合结果，输出保留 rerankTopN
        assertThat(reranker.receivedSizes).containsExactly(20);
        assertThat(reranker.receivedTopN).containsExactly(10);
        assertThat(result.rerankOutcome().candidates()).hasSize(10);
        assertThat(result.trace(RetrievalResult.STAGE_RERANK)).hasSize(10);

        // ④ 上下文：只取 contextTopN 条
        assertThat(result.context()).hasSize(5);
        assertThat(result.trace(RetrievalResult.STAGE_CONTEXT)).hasSize(5);
        assertThat(result.context()).isEqualTo(
                result.rerankOutcome().candidates().subList(0, 5));
    }

    @Test
    @DisplayName("召回不足时各阶段自然变少（不报错、不需要凑数）")
    void fewerCandidatesThanRequested() {
        RetrievalConfig config = new RetrievalConfig(5, 5, 3, 2, 60, true,
                RrfFusion.NAME, "llm-rerank");
        StubChannel a = new StubChannel("stub-a", 2);
        StubReranker reranker = new StubReranker("llm-rerank", null);
        RetrievalResult result = new RetrievalPipeline(List.of(a), new RrfFusion(60), reranker, config)
                .retrieve(PROBE);

        assertThat(a.requestedTopK).containsExactly(5);   // 要 5 条
        assertThat(result.fused()).hasSize(2);            // 只有 2 条
        assertThat(result.context()).hasSize(2);          // 最终也就 2 条
    }

    // ── 无 rerank 档（消融对照）────────────────────────────────────────────

    @Test
    @DisplayName("★ --no-rerank：不调用重排器，上下文直接取融合序的前 contextTopN 条")
    void noRerankTakesFusionOrder() {
        RetrievalConfig config = new RetrievalConfig(30, 20, 10, 5, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);
        StubChannel a = new StubChannel("stub-a", 40);
        StubChannel b = new StubChannel("stub-b", 40);
        StubReranker neverCalled = new StubReranker(NoopReranker.NAME, null);

        RetrievalResult result = new RetrievalPipeline(List.of(a, b), new RrfFusion(60),
                neverCalled, config).retrieve(PROBE);

        assertThat(neverCalled.receivedSizes)
                .as("rerankEnabled=false 时不得调用任何重排器（含 NoopReranker）："
                        + "本档上游是融合结果，一旦被调用 hits 就会被 rerankTopN 削短，"
                        + "产物随即变成「声明 topK=5 却只有 3 条」的静默错数")
                .isEmpty();
        assertThat(result.rerankOutcome().degraded()).isFalse();
        assertThat(result.rerankOutcome().model())
                .as("本档没有模型，不许冒用真实模型名")
                .isEqualTo(RerankOutcome.NO_MODEL);
        assertThat(result.context()).hasSize(5);
        assertThat(ids(result.context()))
                .as("顺序必须与融合序完全一致（取前 5 条）")
                .isEqualTo(ids(result.fused()).subList(0, 5));
        assertThat(result.context().get(0).rerankRank()).isNull();
        assertThat(result.context().get(0).finalScore())
                .as("无重排分时 score 落到融合分")
                .isEqualTo(result.context().get(0).fusedScore());
    }

    // ── 降级档 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 重排降级：仍返回 contextTopN 条、顺序为融合序，且 degraded 与原因都带出来")
    void degradedStillReturnsContextInFusionOrder() {
        RetrievalConfig config = new RetrievalConfig(30, 20, 10, 5, 60, true,
                RrfFusion.NAME, "llm-rerank");
        StubChannel a = new StubChannel("stub-a", 40);
        StubReranker failing = new StubReranker("llm-rerank", "重排模型调用超时（等待上限 30000 ms）");

        RetrievalResult result = new RetrievalPipeline(List.of(a), new RrfFusion(60),
                failing, config).retrieve(PROBE);

        assertThat(result.rerankDegraded()).isTrue();
        assertThat(result.rerankOutcome().degradeReason()).contains("超时");
        assertThat(result.context()).hasSize(5);
        assertThat(ids(result.context())).isEqualTo(ids(result.fused()).subList(0, 5));
        assertThat(result.context().get(0).rerankScore()).isNull();
        assertThat(result.trace(RetrievalResult.STAGE_RERANK)).hasSize(10);
    }

    // ── 空查询短路 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 空查询短路：通道与模型都不被调用，返回空结果（口径与既有两通道一致）")
    void blankQueryShortCircuits() {
        RetrievalConfig config = RetrievalConfig.defaults();
        StubChannel a = new StubChannel("stub-a", 5);
        StubChannel b = new StubChannel("stub-b", 5);
        StubReranker reranker = new StubReranker("llm-rerank", null);
        RetrievalPipeline pipeline = new RetrievalPipeline(List.of(a, b), new RrfFusion(60),
                reranker, config);

        for (String blank : new String[]{null, "", "   ", String.valueOf((char) 9)}) {
            RetrievalResult result = pipeline.retrieve(blank);
            assertThat(result.context()).isEmpty();
            assertThat(result.fused()).isEmpty();
            assertThat(result.rerankOutcome().model()).isEqualTo(RerankOutcome.NO_MODEL);
            assertThat(result.rerankOutcome().degraded()).isFalse();
        }

        assertThat(a.requestedTopK).as("空查询不得触发任何通道调用").isEmpty();
        assertThat(b.requestedTopK).isEmpty();
        assertThat(reranker.receivedSizes).isEmpty();
        assertThat(pipeline.retrieve("  ").stageTrace().keySet())
                .as("短路时阶段键集与正常路径一致（只是全为空），产物 schema 才稳定")
                .containsExactly("recall:stub-a", "recall:stub-b", "recall", "fusion", "rerank",
                        "context");
    }

    @Test
    @DisplayName("单字符查询正常走链路（只有「空」才算空，别把短查询也短路掉）")
    void singleCharQueryIsNotShortCircuited() {
        StubChannel a = new StubChannel("stub-a", 3);
        RetrievalConfig config = new RetrievalConfig(3, 3, 2, 1, 60, true, RrfFusion.NAME, "llm-rerank");
        RetrievalResult result = new RetrievalPipeline(List.of(a), new RrfFusion(60),
                new StubReranker("llm-rerank", null), config).retrieve("费");
        assertThat(a.requestedTopK).containsExactly(3);
        assertThat(result.context()).hasSize(1);
    }

    // ── 单通道档（第 6 项四路对照）─────────────────────────────────────────

    @Test
    @DisplayName("★ 单通道 + 无 rerank：先看对照组的原始返回序（未过链路）")
    void singleChannelBaselineOrder() {
        StubChannel a = new StubChannel("vector", 40);
        List<String> raw = ids(a.search(PROBE, 30));
        assertThat(raw).hasSize(30).startsWith("vector-1", "vector-2", "vector-3");
    }

    @Test
    @DisplayName("★ 单通道 + 无 rerank：上下文 = 该路原序的前 contextTopN 条（融合层不得改序）")
    void singleChannelWithoutRerankKeepsChannelOrder() {
        StubChannel only = new StubChannel("vector", 40);
        StubChannel absent = new StubChannel("bm25", 40);
        RetrievalConfig config = new RetrievalConfig(30, 30, 10, 5, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);

        RetrievalResult result = new RetrievalPipeline(List.of(only), new RrfFusion(60),
                new NoopReranker(), config).retrieve(PROBE);

        // 对照组：该通道自己返回的顺序（不是重算 RRF 分数——那只能证明实现自洽）
        List<String> rawOrder = ids(only.search(PROBE, 30));

        assertThat(ids(result.context()))
                .as("单路 RRF 的分数是 1/(k+rank)，分母随名次严格递增 ⇒ 顺序必须逐位不变。"
                        + "若这里不一致，--channels vector 就不是 VectorQueryCli 的对照组，"
                        + "第 6 项会把「融合的增益」记到别的东西头上")
                .isEqualTo(rawOrder.subList(0, 5));
        assertThat(ids(result.fused())).isEqualTo(rawOrder);
        assertThat(absent.requestedTopK).as("另一路不许被调用").isEmpty();
        assertThat(result.trace("recall:vector")).isEqualTo(rawOrder);
        assertThat(result.stageTrace()).doesNotContainKey("recall:bm25");
    }

    @Test
    @DisplayName("★ 单通道 + 重排：重排的输入就是该路原序（截断到 rerankTopN 后原序仍是前缀）")
    void singleChannelWithRerankFeedsChannelOrderToReranker() {
        StubChannel only = new StubChannel("bm25", 40);
        StubReranker reranker = new StubReranker("llm-rerank", null);
        RetrievalConfig config = new RetrievalConfig(30, 30, 10, 5, 60, true,
                RrfFusion.NAME, reranker.name());

        RetrievalResult result = new RetrievalPipeline(List.of(only), new RrfFusion(60),
                reranker, config).retrieve(PROBE);

        List<String> rawOrder = ids(only.search(PROBE, 30));
        assertThat(reranker.receivedSizes).containsExactly(30);
        assertThat(ids(result.context())).isEqualTo(rawOrder.subList(0, 5));
    }

    @Test
    @DisplayName("★ 两路与单路都不是「直通」：两路时同一 chunk 的分数会累加（证明融合真的在跑）")
    void fusionIsAlwaysInThePath() {
        StubChannel a = new StubChannel("vector", 3);
        StubChannel b = new StubChannel("bm25", 3);
        RetrievalConfig config = new RetrievalConfig(3, 3, 2, 2, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);

        // 两个桩通道的 id 前缀不同 ⇒ 并集 6 条，各自只有一路的分数
        RetrievalResult both = new RetrievalPipeline(List.of(a, b), new RrfFusion(60),
                new NoopReranker(), config).retrieve(PROBE);
        assertThat(both.fused()).hasSize(3);
        assertThat(both.fused().get(0).channelScores()).hasSize(1);
        assertThat(both.trace(RetrievalPipeline.STAGE_RECALL)).hasSize(6);

        // 单路时分数同样是 RRF 的 1/(k+rank)，不是原始分
        RetrievalResult single = new RetrievalPipeline(List.of(a), new RrfFusion(60),
                new NoopReranker(), config).retrieve(PROBE);
        assertThat(single.fused().get(0).fusedScore()).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(single.fused().get(0).channelScores()).containsEntry("vector", 99.0);
    }

    // ── B1 回归（2026-09-21）：关掉重排时下游上限必须取 fusionTopK ────────────

    @Test
    @DisplayName("★ B1①：任意合法配置下，产物每题 hits 条数恒等于顶层 topK（评估脚本依赖的等式）")
    void hitsSizeAlwaysEqualsTopKForEveryLegalConfig() throws Exception {
        // 这份清单就是"合法配置"的抽样：每条都满足 RetrievalConfig 的校验规则
        //   ① rerankTopN <= fusionTopK
        //   ② contextTopN <= (rerankEnabled ? rerankTopN : fusionTopK)
        // 等式本身与具体取值无关——它只要求"声明 topK 是多少，就真给多少条"。
        // 关掉重排那几档正是出过问题的地方（曾按 rerankTopN 截断 ⇒ 声明 30 条实际 10 条），
        // 故清单里刻意让"重排开/关 × 各档条数"的取值错开，而不是只测默认档。
        List<Case> cases = List.of(
                new Case(30, 30, 10, 5, 60, true, null),
                new Case(30, 30, 10, 5, 60, false, null),
                new Case(30, 30, 10, 30, 60, false, null),
                new Case(30, 30, 3, 5, 60, false, null),
                new Case(30, 30, 5, 5, 60, true, null),
                new Case(30, 30, 30, 30, 60, true, null),
                new Case(50, 40, 40, 7, 60, true, null),
                new Case(50, 40, 20, 7, 60, false, null),
                new Case(60, 25, 25, 25, 10, true, null),
                new Case(20, 8, 1, 8, 60, false, null),
                // 降级档也要满足同一条等式：降级只换排序，不改条数
                new Case(30, 30, 10, 5, 60, true, "重排模型调用超时（等待上限 90000 ms）"),
                new Case(30, 30, 20, 6, 60, true, "重排模型输出无法解析为约定 JSON：JSON 语法错"));

        for (Case c : cases) {
            RetrievalConfig config = c.config();
            StubChannel a = new StubChannel("vector", 60);
            StubChannel b = new StubChannel("bm25", 60);
            // 关闭重排时，故意注入一个"一旦被调用就会降级"的桩：
            // 它被调用 ⇒ hits 会被 rerankTopN 削短 ⇒ 等式当场不成立（这正是 B1 的现象）
            String stubName = config.rerankEnabled() ? config.rerankerName() : NoopReranker.NAME;
            StubReranker reranker = new StubReranker(stubName,
                    config.rerankEnabled() ? c.degradeReason() : "关闭重排时重排器不该被调用");
            RetrievalResult result = new RetrievalPipeline(List.of(a, b), new RrfFusion(c.rrfK()),
                    reranker, config).retrieve(PROBE);

            String json = RetrievalResultJson.render(config, List.of("vector", "bm25"),
                    List.of(result));
            JsonNode root = MAPPER.readTree(json);
            int topK = root.get("topK").asInt();
            assertThat(topK).as("顶层 topK 必须是 contextTopN（%s）", c.label())
                    .isEqualTo(config.contextTopN());
            assertThat(root.get("queryCount").asInt()).isEqualTo(1);
            assertThat(root.get("results").get(0).get("hits").size())
                    .as("★ B1 等式：每题 hits 条数必须恒等于顶层 topK（%s）："
                            + "声明 %d 条而实际给别的条数，指标就会在错误的 k 上算，"
                            + "而 eval_retrieval.py 的第二道硬校验会整份拒绝它",
                            c.label(), topK)
                    .isEqualTo(topK);
            assertThat(result.context()).hasSize(config.contextTopN());
            if (!config.rerankEnabled()) {
                assertThat(reranker.receivedSizes)
                        .as("关闭重排时重排器一次都不该被调用（它的那次调用正是 B1 的成因，%s）",
                                c.label())
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("★ B1②：fusionTopK=30 / rerankTopN=3 / contextTopN=5 且关闭重排 → hits 恰为 5 条（改之前是 3）")
    void noRerankWithSmallRerankTopNKeepsContextTopN() throws Exception {
        // 这组取值把"关掉重排反而让上下文变少"顶到最明显的程度：
        // 关掉重排时的上游是融合结果（30 条），rerankTopN=3 在本档**没有任何约束力**
        // （RetrievalConfig 的契约就是 contextTopN ≤ fusionTopK）。
        // 改之前的实现会按 rerankTopN=3 截断 ⇒ 产物声明 topK=5 而每题只有 3 条，
        // 静默、不报错，指标在 3 条上算却标成 @5。
        RetrievalConfig config = new RetrievalConfig(30, 30, 3, 5, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);
        StubChannel a = new StubChannel("vector", 60);
        StubChannel b = new StubChannel("bm25", 60);
        StubReranker unused = new StubReranker(NoopReranker.NAME, "关闭重排时重排器不该被调用");

        RetrievalResult result = new RetrievalPipeline(List.of(a, b), new RrfFusion(60),
                unused, config).retrieve(PROBE);

        assertThat(result.fused()).as("融合后有 30 条可用原料").hasSize(30);
        assertThat(result.context()).as("★ contextTopN=5，不是 rerankTopN=3").hasSize(5);
        assertThat(result.trace(RetrievalResult.STAGE_CONTEXT)).hasSize(5);
        assertThat(ids(result.context())).isEqualTo(ids(result.fused()).subList(0, 5));

        JsonNode root = MAPPER.readTree(RetrievalResultJson.render(config,
                List.of("vector", "bm25"), List.of(result)));
        assertThat(root.get("topK").asInt()).isEqualTo(5);
        assertThat(root.get("results").get(0).get("hits").size())
                .as("★ B1②：产物里每题的 hits 必须恰好 5 条")
                .isEqualTo(5);
        assertThat(root.get("results").get(0).get("rerankAttempts").asInt())
                .as("本档一次模型都没调，故尝试次数为 0")
                .isZero();
        assertThat(unused.receivedSizes).as("关闭重排时不调用注入的重排器").isEmpty();
    }

    @Test
    @DisplayName("★ 无 rerank 档不得被 rerankTopN 截断：contextTopN=30 > rerankTopN=10 时上下文仍是 30 条")
    void noRerankContextIsNotCappedByRerankTopN() {
        // 合法组合：关闭重排时 contextTopN 只受 fusionTopK 约束（RetrievalConfig 的校验就是这么写的）。
        // ⚠️ 2026-09-21 真实运行发现过反例：链路里写死传 rerankTopN，
        // 于是 `--no-rerank --top-k 30` 只产出 10 条，而产物声明 topK=30 ——
        // 声明与实际不符、指标在 10 条上算却标成 @30，全程不报错。
        RetrievalConfig config = new RetrievalConfig(30, 30, 10, 30, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);
        StubChannel a = new StubChannel("vector", 40);
        StubReranker unused = new StubReranker(NoopReranker.NAME, null);

        RetrievalResult result = new RetrievalPipeline(List.of(a), new RrfFusion(60),
                unused, config).retrieve(PROBE);

        assertThat(result.context()).hasSize(30);
        assertThat(result.trace(RetrievalResult.STAGE_CONTEXT)).hasSize(30);
        assertThat(result.trace(RetrievalResult.STAGE_RERANK))
                .as("无 rerank 档的「重排阶段」就是融合结果本身，不该被 rerankTopN 削短")
                .hasSize(30);
        assertThat(ids(result.context())).isEqualTo(ids(result.fused()));
        assertThat(unused.receivedSizes).as("关闭重排时不调用注入的重排器").isEmpty();
    }

    @Test
    @DisplayName("开着重排时 rerankTopN 仍然生效（上限只在该档被解除，不是全局放开）")
    void rerankTopNStillAppliesWhenEnabled() {
        RetrievalConfig config = new RetrievalConfig(30, 30, 10, 5, 60, true,
                RrfFusion.NAME, "llm-rerank");
        StubChannel a = new StubChannel("vector", 40);
        StubReranker reranker = new StubReranker("llm-rerank", null);

        RetrievalResult result = new RetrievalPipeline(List.of(a), new RrfFusion(60),
                reranker, config).retrieve(PROBE);

        assertThat(reranker.receivedTopN).containsExactly(10);
        assertThat(result.rerankOutcome().candidates()).hasSize(10);
        assertThat(result.context()).hasSize(5);
    }

    // ── 构造期校验 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("通道为空 / 融合策略名不一致 / 重排器名不一致 → 构造即失败（产物不许「声称」用了别的策略）")
    void constructorGuards() {
        StubChannel a = new StubChannel("stub-a", 3);

        assertThatThrownBy(() -> new RetrievalPipeline(List.of(), new RrfFusion(),
                new NoopReranker(), RetrievalConfig.defaults().withoutRerank()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个检索通道");

        // 配置说 llm-rerank，实际给 noop → 必须拒绝
        assertThatThrownBy(() -> new RetrievalPipeline(List.of(a), new RrfFusion(),
                new NoopReranker(), RetrievalConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rerankerName")
                .hasMessageContaining("不一致");

        // 关闭重排时不校验重排器名（本档根本不用它）
        assertThat(new RetrievalPipeline(List.of(a), new RrfFusion(), new NoopReranker(),
                RetrievalConfig.defaults().withoutRerank())).isNotNull();
    }

    @Test
    @DisplayName("通道抛异常时整次失败：不降级成单通道（否则会被读成「混合检索没增益」）")
    void channelFailurePropagates() {
        RetrievalChannel broken = new RetrievalChannel() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public List<RetrievalCandidate> search(String query, int topK) {
                throw new RetrievalChannelException("BM25 通道检索失败：档案不匹配",
                        new IllegalStateException("词典指纹不一致"));
            }
        };
        RetrievalConfig config = new RetrievalConfig(5, 5, 2, 1, 60, true, RrfFusion.NAME, "llm-rerank");
        RetrievalPipeline pipeline = new RetrievalPipeline(List.of(broken, new StubChannel("ok", 5)),
                new RrfFusion(60), new StubReranker("llm-rerank", null), config);

        assertThatThrownBy(() -> pipeline.retrieve(PROBE))
                .isInstanceOf(RetrievalChannelException.class)
                .hasMessageContaining("BM25 通道检索失败")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    // ── 桩 ────────────────────────────────────────────────────────────────

    /**
     * B1 回归用的"一档合法配置"。
     *
     * <p>把配置写成数据而不是五六个独立的测试方法：等式校验是<b>逐档重复</b>的，
     * 写成清单后加一档只是加一行，而每档失败时 {@link #label()} 能直接说出是哪一档
     * （题面本身看不出配置，不写清楚就只能靠猜）。
     *
     * @param rerankEnabled 是否开启重排
     * @param degradeReason 桩重排器的降级原因；null = 正常重排
     */
    private record Case(int recallTopK, int fusionTopK, int rerankTopN, int contextTopN,
                        int rrfK, boolean rerankEnabled, String degradeReason) {

        RetrievalConfig config() {
            return new RetrievalConfig(recallTopK, fusionTopK, rerankTopN, contextTopN, rrfK,
                    rerankEnabled, RrfFusion.NAME,
                    rerankEnabled ? "llm-rerank" : NoopReranker.NAME);
        }

        String label() {
            return "recallTopK=" + recallTopK + " fusionTopK=" + fusionTopK
                    + " rerankTopN=" + rerankTopN + " contextTopN=" + contextTopN
                    + " rrfK=" + rrfK + " rerankEnabled=" + rerankEnabled
                    + (degradeReason == null ? "" : "（降级档）");
        }
    }

    /** 内存通道：返回 {@code available} 条互不相同的候选，并记下每次被要了多少条。 */
    private static final class StubChannel implements RetrievalChannel {

        private final String name;
        private final int available;
        private final List<Integer> requestedTopK = new ArrayList<>();

        StubChannel(String name, int available) {
            this.name = name;
            this.available = available;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<RetrievalCandidate> search(String query, int topK) {
            requestedTopK.add(topK);
            int n = Math.min(available, topK);
            List<RetrievalCandidate> out = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                // 分数随名次递减（原值不影响 RRF，它只用名次）
                out.add(RetrievalCandidate.fromHit(name, 100.0 - i, i,
                        name + "-" + i, "doc-" + i, "文档 " + i, "BODY", "第" + i + "条",
                        "标题 " + i, "2026"));
            }
            return List.copyOf(out);
        }
    }

    /** 内存重排器：默认原样返回（截断 topN）；给了 {@code degradeReason} 就返回降级结果。 */
    private static final class StubReranker implements Reranker {

        private final String name;
        private final String degradeReason;
        private final List<Integer> receivedSizes = new ArrayList<>();
        private final List<Integer> receivedTopN = new ArrayList<>();

        StubReranker(String name, String degradeReason) {
            this.name = name;
            this.degradeReason = degradeReason;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public RerankOutcome rerank(String query, List<RetrievalCandidate> candidates, int topN) {
            receivedSizes.add(candidates.size());
            receivedTopN.add(topN);
            List<RetrievalCandidate> out = candidates.size() <= topN
                    ? candidates : List.copyOf(candidates.subList(0, topN));
            if (degradeReason != null) {
                return RerankOutcome.degraded(out, degradeReason, 12L, name);
            }
            List<RetrievalCandidate> ranked = new ArrayList<>(out.size());
            for (int i = 0; i < out.size(); i++) {
                ranked.add(out.get(i).withRerank(i + 1, 1.0 - i * 0.01));
            }
            return RerankOutcome.ok(List.copyOf(ranked), 7L, name);
        }
    }

    private static List<String> ids(List<RetrievalCandidate> list) {
        return list.stream().map(RetrievalCandidate::chunkId).toList();
    }
}
