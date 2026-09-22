package com.canalmind.rag.retrieve;

import com.canalmind.observability.AiSpans;
import com.canalmind.rag.retrieve.LlmReranker.ChunkSnippetProvider;
import com.canalmind.rag.retrieve.LlmReranker.PromptCompleter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 重排器的测试（第 2 周第 5 项，C-27）。
 *
 * <p><b>为什么这组测试必须存在、且必须用桩</b>：本类是链路上<b>唯一依赖外部服务</b>的一环
 * （DeepSeek），而它最要紧的行为恰恰是<b>失败时怎么办</b>——解析失败、超时、网络错、
 * 模型编造 id。这些路径用真实服务<b>无法稳定复现</b>（要制造 5xx、要等 30 秒、要诱导模型胡说），
 * 但它们一旦写错，后果是"没跑成"被读成"rerank 没效果"，从而把第 4 周的消融结论带偏。
 * 所以模型调用被收在一道 {@link PromptCompleter} 缝里，逐条覆盖。
 *
 * <p>桩一律手写（不用 mock 框架）：这里要验的是"降级后<b>候选一条不少、顺序未变</b>"，
 * 用 lambda 直白表达比 mock 的期望式断言更贴近要验的事实。
 */
class LlmRerankerTest {

    private static final String PROBE = "过闸费怎么算";

    // ── ① 正常路径 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("正常：按模型给的 score 降序重排，写进 rerankRank/rerankScore，degraded=false")
    void normalRanking() {
        List<RetrievalCandidate> in = candidates("a", "b", "c");
        LlmReranker reranker = reranker(ok("""
                {"ranking":[{"chunk_id":"c","score":0.95,"reason":"最相关"},
                            {"chunk_id":"a","score":0.55,"reason":"次之"},
                            {"chunk_id":"b","score":0.10,"reason":"弱"}]}"""));

        RerankOutcome out = reranker.rerank(PROBE, in, 10);

        assertThat(out.degraded()).isFalse();
        assertThat(out.degradeReason()).isNull();
        assertThat(out.model()).isEqualTo("stub-model");
        assertThat(out.latencyMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(ids(out.candidates())).containsExactly("c", "a", "b");
        assertThat(out.candidates().get(0).rerankRank()).isEqualTo(1);
        assertThat(out.candidates().get(0).rerankScore()).isEqualTo(0.95);
        assertThat(out.candidates().get(1).rerankRank()).isEqualTo(2);
        assertThat(out.candidates().get(2).rerankRank()).isEqualTo(3);
        assertThat(out.candidates().get(2).rerankScore()).isEqualTo(0.10);
        // 重排不改元数据与通道数据（那是融合阶段的产物，重排只加名次与分数）
        assertThat(out.candidates().get(0).docId()).isEqualTo("doc-c");
        assertThat(out.candidates().get(0).channelRanks()).containsEntry("stub", 3);
    }

    @Test
    @DisplayName("正常：topN 截断到 min(topN, 输入数)，且 rank 从 1 连续")
    void truncatesToTopN() {
        LlmReranker reranker = reranker(ok(
                "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9},{\"chunk_id\":\"b\",\"score\":0.8},"
                        + "{\"chunk_id\":\"c\",\"score\":0.7}]}"));
        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b", "c"), 2);
        assertThat(ids(out.candidates())).containsExactly("a", "b");
        assertThat(out.candidates()).extracting(RetrievalCandidate::rerankRank).containsExactly(1, 2);
    }

    @Test
    @DisplayName("正常：markdown 代码围栏与前后解释文字被容忍（从第一个 { 到最后一个 } 截取）")
    void toleratesCodeFence() {
        String fenced = "好的，结果如下：" + LF
                + "```json" + LF
                + "{\"ranking\":[{\"chunk_id\":\"b\",\"score\":0.9},{\"chunk_id\":\"a\",\"score\":0.2}]}" + LF
                + "```" + LF
                + "完成。";
        LlmReranker reranker = reranker(ok(fenced));
        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b"), 10);
        assertThat(out.degraded()).isFalse();
        assertThat(ids(out.candidates())).containsExactly("b", "a");
    }

    @Test
    @DisplayName("正常：模型漏掉的候选按输入原序追加在末尾，rerankScore 为 null（不是 0 分）")
    void omittedCandidatesAppendedInOriginalOrder() {
        List<RetrievalCandidate> in = candidates("a", "b", "c", "d");
        LlmReranker reranker = reranker(ok(
                "{\"ranking\":[{\"chunk_id\":\"d\",\"score\":0.9},{\"chunk_id\":\"b\",\"score\":0.5}]}"));
        RerankOutcome out = reranker.rerank(PROBE, in, 10);

        assertThat(out.degraded()).isFalse();
        assertThat(ids(out.candidates()))
                .as("先按分数排有分者，再按输入原序追加没被模型提到的 a、c")
                .containsExactly("d", "b", "a", "c");
        assertThat(out.candidates().get(2).rerankScore()).isNull();
        assertThat(out.candidates().get(2).rerankRank()).isEqualTo(3);
    }

    @Test
    @DisplayName("正常：模型给重复 chunk_id 只取首次；缺 score 的条目排在末尾")
    void duplicateAndMissingScore() {
        List<RetrievalCandidate> in = candidates("a", "b", "c");
        LlmReranker reranker = reranker(ok("""
                {"ranking":[{"chunk_id":"a","score":0.9},
                            {"chunk_id":"a","score":0.1},
                            {"chunk_id":"b"},
                            {"chunk_id":"c","score":"0.4"}]}"""));

        RerankOutcome out = reranker.rerank(PROBE, in, 10);
        assertThat(out.degraded()).isFalse();
        assertThat(ids(out.candidates()))
                .as("有分者按分数降序（a=0.9 在 c=0.4 之前），无分的 b 排在其后；"
                        + "重复的 a 只取首次那一个 0.9")
                .containsExactly("a", "c", "b");
        assertThat(out.candidates().get(0).rerankScore()).isEqualTo(0.9);
        assertThat(out.candidates().get(1).rerankScore())
                .as("数字字符串也接受（模型偶尔给引号），否则这条会落进「没给分」一档")
                .isEqualTo(0.4);
        assertThat(out.candidates().get(2).rerankScore()).isNull();
    }

    // ── ② JSON 语法错 → 降级保原序 ────────────────────────────────────────

    @Test
    @DisplayName("JSON 语法错 → degraded=true、保留输入原序、候选一条不少、不带重排字段")
    void brokenJsonDegrades() {
        LlmReranker reranker = reranker(ok("{\"ranking\":[{\"chunk_id\":\"a\" \"score\":0.9,]}"));
        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b", "c"), 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("无法解析").contains("保留融合原序");
        assertThat(ids(out.candidates())).containsExactly("a", "b", "c");
        assertThat(out.candidates().get(0).rerankRank())
                .as("降级时不得假装重排跑过：名次与分数都留 null")
                .isNull();
        assertThat(out.candidates().get(0).rerankScore()).isNull();
    }

    @Test
    @DisplayName("输出是空文本 / 没有 ranking 数组 / 条目缺 chunk_id → 一律降级并写清原因")
    void unsatisfiableShapesDegrade() {
        assertThat(reason(ok("   "))).contains("空文本");
        assertThat(reason(ok("{\"result\":[]}"))).contains("缺少 ranking 数组");
        assertThat(reason(ok("{\"ranking\":[{\"score\":0.9}]}"))).contains("缺少 chunk_id");
        assertThat(reason(ok("这里根本没有 JSON"))).contains("找不到 JSON 对象边界");
        // 上面每条都必须真的降级
        for (String raw : List.of("   ", "{\"result\":[]}", "{\"ranking\":[{\"score\":0.9}]}",
                "这里根本没有 JSON")) {
            RerankOutcome out = reranker(ok(raw)).rerank(PROBE, candidates("a", "b"), 10);
            assertThat(out.degraded()).as("raw=" + raw).isTrue();
            assertThat(out.degradeReason()).isNotBlank();
            assertThat(ids(out.candidates())).containsExactly("a", "b");
        }
    }

    // ── ③ 未知 chunk_id ───────────────────────────────────────────────────

    @Test
    @DisplayName("★ 模型返回未知 chunk_id → 整次降级、保留原序，绝不按编造的 id 造候选")
    void unknownChunkIdDegrades() {
        LlmReranker reranker = reranker(ok(
                "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9},{\"chunk_id\":\"ghost\",\"score\":0.99}]}"));
        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b"), 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason())
                .contains("候选集之外的 chunk_id")
                .contains("ghost")
                .contains("整次降级");
        assertThat(ids(out.candidates()))
                .as("候选集合必须与输入完全一致：既不丢，也不凭空多出 ghost")
                .containsExactly("a", "b");
    }

    // ── ④ 候选超上限 → 拒绝并降级 ─────────────────────────────────────────

    @Test
    @DisplayName("★ 候选数超过上限 → 拒绝调用模型（模型一次都没被调）并降级")
    void tooManyCandidatesDegradesWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        LlmReranker reranker = new LlmReranker(prompt -> {
            calls.incrementAndGet();
            return "{\"ranking\":[]}";
        }, snippets("正文"), "stub-model", Duration.ofSeconds(5), 3, 100);

        List<RetrievalCandidate> in = candidates("a", "b", "c", "d");
        RerankOutcome out = reranker.rerank(PROBE, in, 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("超过上限").contains("3").contains("融合原序");
        assertThat(calls.get()).as("超上限时不许调用模型：截断后再调用会让排序只在部分候选里进行")
                .isZero();
        assertThat(out.latencyMillis()).isZero();
        assertThat(ids(out.candidates())).containsExactly("a", "b", "c", "d");
    }

    // ── ⑤ 超时 / 网络异常 → 降级 ──────────────────────────────────────────

    @Test
    @DisplayName("★ 调用超时 → 降级并保留原序，原因里写明等待上限与「只是放弃等待」")
    void timeoutDegrades() {
        LlmReranker reranker = new LlmReranker(prompt -> {
            Thread.sleep(5_000);
            return "{\"ranking\":[]}";
        }, snippets("正文"), "stub-model", Duration.ofMillis(150),
                LlmReranker.DEFAULT_MAX_CANDIDATES, LlmReranker.DEFAULT_SNIPPET_CHARS);

        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b"), 10);
        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("超时").contains("150 ms").contains("放弃等待");
        assertThat(ids(out.candidates())).containsExactly("a", "b");
    }

    @Test
    @DisplayName("★ 网络/服务端异常 → 降级并保留原序，原因里带底层异常类型与消息")
    void networkFailureDegrades() {
        LlmReranker reranker = reranker(prompt -> {
            throw new java.net.ConnectException("Connection refused: api.deepseek.com");
        });
        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b"), 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason())
                .contains("调用失败")
                .contains("ConnectException")
                .contains("Connection refused");
        assertThat(ids(out.candidates())).containsExactly("a", "b");
    }

    @Test
    @DisplayName("取候选正文失败 / 正文全空 → 降级（只看标题的重排收益不可信）")
    void snippetFailuresDegrade() {
        List<RetrievalCandidate> in = candidates("a", "b");

        LlmReranker throwing = new LlmReranker(prompt -> "{\"ranking\":[]}",
                chunkId -> {
                    throw new IllegalStateException("连不上数据库");
                }, "stub-model");
        RerankOutcome out = throwing.rerank(PROBE, in, 10);
        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("取候选正文片段失败").contains("连不上数据库");

        LlmReranker empty = new LlmReranker(prompt -> "{\"ranking\":[]}",
                ChunkSnippetProvider.NONE, "stub-model");
        RerankOutcome out2 = empty.rerank(PROBE, in, 10);
        assertThat(out2.degraded()).isTrue();
        assertThat(out2.degradeReason()).contains("正文片段全部为空").contains("rag_chunk.content");
        assertThat(ids(out2.candidates())).containsExactly("a", "b");
    }

    // ── ⑥ 空输入与提示词形状 ──────────────────────────────────────────────

    @Test
    @DisplayName("空候选：不调模型、不降级，返回空结果（没东西可排不是失败）")
    void emptyCandidatesIsNotDegradation() {
        AtomicInteger calls = new AtomicInteger();
        LlmReranker reranker = new LlmReranker(prompt -> {
            calls.incrementAndGet();
            return "";
        }, snippets("正文"), "stub-model");

        RerankOutcome out = reranker.rerank(PROBE, List.of(), 10);
        assertThat(out.degraded()).isFalse();
        assertThat(out.candidates()).isEmpty();
        assertThat(out.latencyMillis()).isZero();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("提示词：只含 chunk_id/溯源/正文片段，正文按上限截断并折掉换行、加省略号")
    void promptShapeAndSnippetTruncation() {
        List<String> captured = new ArrayList<>();
        LlmReranker reranker = new LlmReranker(prompt -> {
            captured.add(prompt);
            return "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9}]}";
        }, chunkId -> "正文第一行" + LF + "正文第二行" + LF + "X".repeat(50), "stub-model",
                Duration.ofSeconds(5), LlmReranker.DEFAULT_MAX_CANDIDATES, 11);

        reranker.rerank("过闸费" + LF + "怎么算", candidates("a", "b"), 10);

        assertThat(captured).hasSize(1);
        String prompt = captured.get(0);
        assertThat(prompt)
                .contains("chunk_id")
                .contains("\"a\"")
                .contains("score")
                .contains("只能使用上面出现过的 chunk_id");
        assertThat(prompt)
                .as("换行被折成空格：提示词里不该出现正文带来的空白行")
                .doesNotContain("正文第一行" + LF + "正文第二行");
        assertThat(prompt)
                .as("超过 11 字符的片段被截断并以省略号收尾（省略号让模型知道正文被截断）")
                .contains("正文第一行 正文第二行" + ELLIPSIS);
        assertThat(prompt)
                .as("查询里的换行同样折掉，避免提示词结构被查询文本破坏")
                .doesNotContain("过闸费" + LF + "怎么算");
    }

    @Test
    @DisplayName("解析辅助：非法 score（非数字文本/布尔/对象/null）一律当「没给分」，不是 0 分")
    void nonFiniteScoresAreTreatedAsMissing() {
        List<LlmReranker.RankedEntry> entries = LlmReranker.parseRankingJson(
                "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":\"高\"},"
                        + "{\"chunk_id\":\"b\",\"score\":null},"
                        + "{\"chunk_id\":\"c\",\"score\":true},"
                        + "{\"chunk_id\":\"d\",\"score\":{\"v\":1}}]}");
        assertThat(entries).hasSize(4);
        assertThat(entries).extracting(LlmReranker.RankedEntry::score).containsOnlyNulls();
    }

    @Test
    @DisplayName("构造参数非法：缺 modelName / timeout 非正 / maxCandidates 与 snippetChars < 1")
    void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new LlmReranker(p -> "", snippets("x"), " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelName");
        assertThatThrownBy(() -> new LlmReranker(p -> "", snippets("x"), "m", Duration.ZERO, 50, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeout");
        assertThatThrownBy(() -> new LlmReranker(p -> "", snippets("x"), "m", Duration.ofSeconds(1), 0, 10))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxCandidates");
        assertThatThrownBy(() -> new LlmReranker(p -> "", snippets("x"), "m", Duration.ofSeconds(1), 50, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("snippetChars");
        assertThatThrownBy(() -> new LlmReranker(p -> "", snippets("x"), "m").rerank(PROBE,
                candidates("a"), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("topN");
        assertThat(LlmReranker.NAME).isEqualTo("llm-rerank");
    }

    @Test
    @DisplayName("★ 空正文（BlankCompletionException）单独成一档，且把 finishReason 与 token 用量带出来")
    void blankCompletionIsReportedWithEvidence() {
        LlmReranker reranker = new LlmReranker(prompt -> {
            throw new LlmReranker.BlankCompletionException(
                    "finish_reason=length，prompt 9091 tokens / completion 4096 tokens，"
                            + "提示词 14387 字符");
        }, snippets("片段"), "deepseek-v4-flash");
        List<RetrievalCandidate> in = candidates("c1", "c2", "c3");

        RerankOutcome outcome = reranker.rerank(PROBE, in, 2);

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.degradeReason())
                .as("必须与「调用失败」区分开：一个要调额度，一个要查网络")
                .contains("返回了空正文")
                .contains("finish_reason=length")
                .contains("completion 4096 tokens")
                .contains("14387 字符")
                .contains("maxTokens");
        assertThat(ids(outcome.candidates()))
                .as("空正文不是「没有候选」，仍要保留融合原序的前 topN 条")
                .containsExactly("c1", "c2");
        assertThat(outcome.candidates().get(0).rerankScore()).isNull();
        assertThat(outcome.candidates().get(0).rerankRank()).isNull();
    }

    @Test
    @DisplayName("空正文走的是「空正文」这一档，不会被报成「调用失败」")
    void blankCompletionIsNotReportedAsCallFailure() {
        LlmReranker reranker = new LlmReranker(prompt -> {
            throw new LlmReranker.BlankCompletionException("finish_reason=length");
        }, snippets("片段"), "m");

        RerankOutcome outcome = reranker.rerank(PROBE, candidates("a", "b"), 2);

        assertThat(outcome.degradeReason()).doesNotContain("网络/鉴权/服务端错误");
    }

    @Test
    @DisplayName("★ 测试也会绊住：真实 completer 必须把空正文抛成 BlankCompletionException（工厂契约）")
    void blankCompletionExceptionIsTheContractForCompleters() {
        // 这条守的是"以后改回链式 ChatClient 调用"的回归：
        // client.prompt().user(p).call().content() 拿不到 finishReason/usage，
        // 于是空正文只能落回"解析失败"，旁证就丢了（2026-09-21 踩过，多花一次诊断调用）。
        assertThat(new LlmReranker.BlankCompletionException("finish_reason=length"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("length");
        // ⚠️ 等待上限**故意只断言"没被静默改小/改没"**，不断言它"够大"：
        //    实测 40 题真实语料的重排耗时 21.6～43.5 s（均值 29.4 s），原值 30 s 正好压在均值上，
        //    且本轮实测到一次**成功**的耗时 29.875 s（离 30 s 只差 0.1 s）——
        //    依据与建议见 LlmReranker.DEFAULT_TIMEOUT 的 javadoc。
        //    2026-09-21 用户依该证据把它从 30 s 改到 90 s，故这里同步钉住 90：
        //    谁要再改它，必须同时改这条断言并写清依据——不许悄悄改。
        assertThat(LlmReranker.DEFAULT_TIMEOUT.toSeconds())
                .as("当前取值为 90 s（原 30 s 正好压在实测均值上）；改动必须同步本断言与 javadoc 依据")
                .isEqualTo(90);
    }

    // ── ②b 截断 vs 非法 JSON：两种病，两句话（B4）────────────────────────────

    @Test
    @DisplayName("★ B4：finish_reason=length 时点名「被 token 上限截断」并带上 token 数，不与「非法 JSON」混用")
    void truncatedOutputIsReportedAsTokenLimitNotAsInvalidJson() {
        // 截断的输出必然解析不出完整 JSON：它走到的是"解析失败"这条路，
        // 但**病因完全不同**（该调 maxTokens，不是改提示词），故必须分开措辞。
        LlmReranker reranker = reranker(withEvidence(
                "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9},{\"chunk_id\":\"b\",\"sc",
                "length", 9091, 16384)).withMaxRetries(0);

        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b", "c"), 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason())
                .as("截断这一档必须点名原因并带 token 数：只有一句「被截断了」等于没说，"
                        + "completionTokens 才是「该把它调多大」的唯一量化依据")
                .contains("重排模型输出被 token 上限截断")
                .contains("finish_reason=length")
                .contains("promptTokens=9091")
                .contains("completionTokens=16384")
                .as("⛔ 不许与「模型输出非法 JSON」共用一句话：两者处置相反")
                .doesNotContain("模型输出非法 JSON");
        assertThat(ids(out.candidates()))
                .as("降级一律保持融合原序（这条语义不许因本次改动而变）")
                .containsExactly("a", "b", "c");
        assertThat(out.candidates().get(0).rerankRank()).isNull();
        assertThat(out.candidates().get(0).rerankScore()).isNull();
    }

    @Test
    @DisplayName("★ B4：finish_reason 不是截断（或拿不到）时仍写「模型输出非法 JSON」")
    void nonTruncatedParseFailureStillSaysInvalidJson() {
        // finish_reason=stop 但 JSON 坏了 → 提示词/服从性问题，与截断不是一回事
        assertThat(reason(withEvidence("{ 坏 ", "stop", 10, 20)))
                .contains("模型输出非法 JSON")
                .doesNotContain("被 token 上限截断");

        // 只实现了 complete() 的桩（拿不到 finish_reason）→ 退化成"非法 JSON"这一档，
        // ⛔ 绝不能猜成"被截断"（猜错的代价是让人去调一个不该调的旋钮）
        assertThat(reason(prompt -> "{ 坏 "))
                .contains("模型输出非法 JSON")
                .doesNotContain("被 token 上限截断");
    }

    @Test
    @DisplayName("finish_reason 的等价截断写法都认（length / MAX_TOKENS / max_output_tokens），其余不认")
    void truncationSynonyms() {
        assertThat(LlmReranker.isTruncated("length")).isTrue();
        assertThat(LlmReranker.isTruncated("LENGTH")).isTrue();
        assertThat(LlmReranker.isTruncated(" length ")).isTrue();
        assertThat(LlmReranker.isTruncated("max_tokens")).isTrue();
        assertThat(LlmReranker.isTruncated("MAX_TOKENS")).isTrue();
        assertThat(LlmReranker.isTruncated("max_output_tokens")).isTrue();
        assertThat(LlmReranker.isTruncated("token_limit")).isTrue();
        // ⛔ 宽判断（contains("length") 之类）会把将来的新取值静默误判成截断，故逐值比对
        assertThat(LlmReranker.isTruncated("stop")).isFalse();
        assertThat(LlmReranker.isTruncated("content_filter")).isFalse();
        assertThat(LlmReranker.isTruncated("tool_calls")).isFalse();
        assertThat(LlmReranker.isTruncated(null)).isFalse();
        assertThat(LlmReranker.isTruncated("  ")).isFalse();
    }

    // ── ②c 有界重试（B4 配套）──────────────────────────────────────────────

    @Test
    @DisplayName("★ 重试上限默认 2（1 次首发 + 2 次额外尝试）；0 = 不重试")
    void retryBudgetDefaults() {
        assertThat(LlmReranker.DEFAULT_MAX_RETRIES).isEqualTo(2);
        assertThat(reranker(prompt -> "{ 坏 ").maxRetries()).isEqualTo(2);
        assertThat(reranker(prompt -> "{ 坏 ").withMaxRetries(0).maxRetries()).isZero();
        assertThatThrownBy(() -> reranker(prompt -> "{ 坏 ").withMaxRetries(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }

    @Test
    @DisplayName("★ 首次解析失败、第二次成功 → degraded=false，但 attempts=2 且前一次原因留在 previousDegradeReasons")
    void retrySucceedsOnSecondAttemptAndTheArtifactShowsIt() {
        AtomicInteger calls = new AtomicInteger();
        // 实测形状：同一查询 4 次真实调用里 1 次 JSON 语法错、3 次成功（降级率 ~25%）
        PromptCompleter flaky = prompt -> {
            int n = calls.incrementAndGet();
            return n == 1
                    ? "{\"ranking\":[{\"chunk_id\":\"a\" \"score\":0.9,]}"
                    : "{\"ranking\":[{\"chunk_id\":\"b\",\"score\":0.9},"
                            + "{\"chunk_id\":\"a\",\"score\":0.2}]}";
        };

        RerankOutcome out = reranker(flaky).rerank(PROBE, candidates("a", "b"), 10);

        assertThat(calls.get()).as("首次失败 → 重试一次成功，故共 2 次调用").isEqualTo(2);
        assertThat(out.degraded()).isFalse();
        assertThat(out.degradeReason()).isNull();
        assertThat(ids(out.candidates())).containsExactly("b", "a");
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(out.retried()).isTrue();
        assertThat(out.previousDegradeReasons())
                .as("⛔ 重试后成功不许写成「一次成功」：前一次的失败原文必须留在产物里")
                .hasSize(1);
        assertThat(out.previousDegradeReasons().get(0)).contains("无法解析").contains("保留融合原序");
    }

    @Test
    @DisplayName("★ 重试耗尽（1+2=3 次仍失败）→ degraded=true、attempts=3、前 2 次原因按序留档、最后一次进 degradeReason")
    void retryExhaustedKeepsLastReasonAndListsTheEarlierOnes() {
        AtomicInteger calls = new AtomicInteger();
        PromptCompleter alwaysBroken = prompt -> {
            calls.incrementAndGet();
            return "{ 坏 ";
        };

        RerankOutcome out = reranker(alwaysBroken).rerank(PROBE, candidates("a", "b"), 10);

        assertThat(calls.get()).as("1 次首发 + 2 次额外尝试 = 3 次").isEqualTo(3);
        assertThat(out.degraded()).isTrue();
        assertThat(out.attempts()).isEqualTo(3);
        assertThat(out.previousDegradeReasons()).hasSize(2);
        assertThat(out.degradeReason())
                .as("最后一次的原文进 degradeReason（而不是被丢掉）")
                .contains("模型输出非法 JSON");
        assertThat(out.previousDegradeReasons())
                .as("前几次的原文逐字保留（产物里要能看出「试了 3 次、每次都坏在哪」）")
                .allSatisfy(r -> assertThat(r).contains("模型输出非法 JSON"));
        assertThat(ids(out.candidates())).containsExactly("a", "b");
    }

    @Test
    @DisplayName("★ --rerank-retry 0 → 只发一次请求，attempts=1、previousDegradeReasons 为空")
    void zeroRetriesSendsExactlyOneRequest() {
        AtomicInteger calls = new AtomicInteger();
        RerankOutcome out = reranker(prompt -> {
            calls.incrementAndGet();
            return "{ 坏 ";
        }).withMaxRetries(0).rerank(PROBE, candidates("a", "b"), 10);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(out.attempts()).isEqualTo(1);
        assertThat(out.retried()).isFalse();
        assertThat(out.previousDegradeReasons()).isEmpty();
        assertThat(out.degraded()).isTrue();
    }

    @Test
    @DisplayName("★「被截断」也算可重试原因：第二次给完整输出即成功，且前一次的截断原因留档")
    void truncatedOutputIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        PromptCompleter truncatingOnce = new PromptCompleter() {

            @Override
            public String complete(String prompt) {
                return "{\"ranking\":[{\"chunk_id\":\"a\",\"sc";
            }

            @Override
            public LlmReranker.Completion completeWithEvidence(String prompt) {
                return calls.incrementAndGet() == 1
                        ? new LlmReranker.Completion("{\"ranking\":[{\"chunk_id\":\"a\",\"sc", "length", 9091, 16384)
                        : new LlmReranker.Completion("{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9}]}",
                                "stop", 9091, 42);
            }
        };

        RerankOutcome out = reranker(truncatingOnce).rerank(PROBE, candidates("a", "b"), 10);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(out.degraded()).isFalse();
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(out.previousDegradeReasons()).hasSize(1);
        assertThat(out.previousDegradeReasons().get(0))
                .as("重试的动机也要留档：读者要能看出「上次是被截断」")
                .contains("被 token 上限截断")
                .contains("completionTokens=16384");
    }

    @Test
    @DisplayName("★ 超时 / 网络 / 鉴权一律**不**重试（重试会把真故障掩盖成「试几次就好了」）")
    void timeoutNetworkAndAuthErrorsAreNeverRetried() {
        // ① 超时：即使把重试上限放到 3，也必须只发 1 次
        AtomicInteger slowCalls = new AtomicInteger();
        LlmReranker slow = new LlmReranker(prompt -> {
            slowCalls.incrementAndGet();
            Thread.sleep(5_000);
            return "{\"ranking\":[]}";
        }, snippets("正文"), "stub-model", Duration.ofMillis(120),
                LlmReranker.DEFAULT_MAX_CANDIDATES, LlmReranker.DEFAULT_SNIPPET_CHARS)
                .withMaxRetries(3);
        RerankOutcome timedOut = slow.rerank(PROBE, candidates("a", "b"), 10);
        assertThat(timedOut.degraded()).isTrue();
        assertThat(timedOut.degradeReason()).contains("超时");
        assertThat(slowCalls.get()).as("超时不重试：重试要再等一整个上限").isEqualTo(1);
        assertThat(timedOut.attempts()).isEqualTo(1);
        assertThat(timedOut.previousDegradeReasons()).isEmpty();

        // ② 网络不可达
        AtomicInteger netCalls = new AtomicInteger();
        RerankOutcome net = reranker(prompt -> {
            netCalls.incrementAndGet();
            throw new java.net.ConnectException("Connection refused: api.deepseek.com");
        }).withMaxRetries(3).rerank(PROBE, candidates("a", "b"), 10);
        assertThat(net.degraded()).isTrue();
        assertThat(net.degradeReason()).contains("ConnectException").contains("不重试");
        assertThat(netCalls.get()).as("网络错不重试：要查网络，不是再试一次").isEqualTo(1);
        assertThat(net.attempts()).isEqualTo(1);

        // ③ 鉴权（401 / 缺 key）：最不能重试的一类——重试会把它洗成"只是慢/抖动"
        AtomicInteger authCalls = new AtomicInteger();
        RerankOutcome auth = reranker(prompt -> {
            authCalls.incrementAndGet();
            throw new IllegalStateException("401 Unauthorized: invalid api key");
        }).withMaxRetries(3).rerank(PROBE, candidates("a", "b"), 10);
        assertThat(auth.degraded()).isTrue();
        assertThat(auth.degradeReason()).contains("401 Unauthorized").contains("不重试");
        assertThat(authCalls.get()).as("鉴权失败不重试（与「缺 key 不得静默降级」同一条纪律）")
                .isEqualTo(1);
        assertThat(auth.attempts()).isEqualTo(1);

        // ④ 空正文：确定性失败，重试只会再花一次钱
        AtomicInteger blankCalls = new AtomicInteger();
        RerankOutcome blank = reranker(prompt -> {
            blankCalls.incrementAndGet();
            throw new LlmReranker.BlankCompletionException("finish_reason=length");
        }).withMaxRetries(3).rerank(PROBE, candidates("a", "b"), 10);
        assertThat(blank.degradeReason()).contains("返回了空正文").contains("不重试");
        assertThat(blankCalls.get()).isEqualTo(1);
        assertThat(blank.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 调模型之前就决定的那几档（候选超上限等）attempts=0：一次模型都没调")
    void preflightFailuresNeverCallTheModelNorRetry() {
        AtomicInteger calls = new AtomicInteger();
        LlmReranker reranker = new LlmReranker(prompt -> {
            calls.incrementAndGet();
            return "{\"ranking\":[]}";
        }, snippets("正文"), "stub-model", Duration.ofSeconds(5), 3, 100).withMaxRetries(3);

        RerankOutcome out = reranker.rerank(PROBE, candidates("a", "b", "c", "d"), 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("超过上限");
        assertThat(calls.get()).as("超上限时不许调用模型").isZero();
        assertThat(out.attempts())
                .as("attempts=0 与「拒绝调用模型」是同一件事的两种说法，不矛盾")
                .isZero();
        assertThat(out.previousDegradeReasons()).isEmpty();
    }

    @Test
    @DisplayName("★ 模型编造 chunk_id 属于「已解析成功」：整次降级但**不**重试")
    void unknownChunkIdDegradesWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        RerankOutcome out = reranker(prompt -> {
            calls.incrementAndGet();
            return "{\"ranking\":[{\"chunk_id\":\"ghost\",\"score\":0.99}]}";
        }).withMaxRetries(3).rerank(PROBE, candidates("a", "b"), 10);

        assertThat(out.degraded()).isTrue();
        assertThat(out.degradeReason()).contains("候选集之外的 chunk_id");
        assertThat(calls.get())
                .as("本次刻意只把「无法解析 / 被截断」列为可重试，不擅自扩大到服从性问题")
                .isEqualTo(1);
        assertThat(out.attempts()).isEqualTo(1);
    }

    // ── ②d 失败分类（2026-09-21）：attempts=1 不等于「一次成功」────────────────

    @Test
    @DisplayName("★ 分类表：只有「输出无法解析/被截断」可重试；每档都有标签与「为什么（不）重试」")
    void failureKindTable() {
        assertThat(List.of(RerankOutcome.FailureKind.values())
                .stream().filter(RerankOutcome.FailureKind::retryable).toList())
                .as("重试口径刻意只放宽到「无法解析 / 被截断」一类，不擅自扩大")
                .containsExactly(RerankOutcome.FailureKind.UNPARSABLE);
        for (RerankOutcome.FailureKind kind : RerankOutcome.FailureKind.values()) {
            assertThat(kind.label()).as("%s 的短标签", kind).isNotBlank();
            assertThat(kind.retryNote())
                    .as("%s 必须说清「为什么（不）重试」——stdout 靠它交代没重试的原因", kind)
                    .isNotBlank();
            assertThat(kind.beforeModelCall() && kind.afterModelCall())
                    .as("%s 不可能同时在调模型之前与之后", kind)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("★ ① 试点形状：同为 finish_reason=LENGTH，空正文只试 1 次、非空截断却试满 3 次")
    void blankBodyAndTruncatedBodyDifferInAttemptsEvenWithTheSameFinishReason() {
        // 题 1/2 的形状（2026-09-21 真实试点）：调通了但正文为空，
        // finish_reason=length 且 completion=4096=maxTokens
        AtomicInteger blankCalls = new AtomicInteger();
        RerankOutcome blank = reranker(prompt -> {
            blankCalls.incrementAndGet();
            throw new LlmReranker.BlankCompletionException(
                    "finish_reason=length，prompt 5250 tokens / completion 4096 tokens，"
                            + "提示词 8710 字符");
        }).withMaxRetries(2).rerank(PROBE, candidates("a", "b"), 10);

        assertThat(blankCalls.get())
                .as("空正文不重试：实测它是确定性失败，再来一次只会再花一次钱")
                .isEqualTo(1);
        assertThat(blank.attempts()).isEqualTo(1);
        assertThat(blank.degraded()).isTrue();
        assertThat(blank.failureKind()).isEqualTo(RerankOutcome.FailureKind.BLANK_COMPLETION);
        assertThat(blank.failureKind().retryable()).isFalse();
        assertThat(blank.failureKind().label()).isEqualTo("空正文");
        assertThat(blank.previousDegradeReasons()).isEmpty();

        // 题 3 的形状：**有正文但被截断**⇒解析失败，走的是唯一可重试的那条分支
        AtomicInteger truncatedCalls = new AtomicInteger();
        PromptCompleter truncatedCompleter = new PromptCompleter() {

            @Override
            public String complete(String prompt) {
                return TRUNCATED_JSON;
            }

            @Override
            public LlmReranker.Completion completeWithEvidence(String prompt) {
                truncatedCalls.incrementAndGet();
                return new LlmReranker.Completion(TRUNCATED_JSON, "length", 5562, 4096);
            }
        };
        RerankOutcome truncated = reranker(truncatedCompleter).withMaxRetries(2)
                .rerank(PROBE, candidates("a", "b"), 10);

        assertThat(truncatedCalls.get())
                .as("1 次首发 + 2 次重试 = 3 次：截断属于可重试档，故试满上限")
                .isEqualTo(3);
        assertThat(truncated.attempts()).isEqualTo(3);
        assertThat(truncated.degraded()).isTrue();
        assertThat(truncated.failureKind()).isEqualTo(RerankOutcome.FailureKind.UNPARSABLE);
        assertThat(truncated.failureKind().retryable()).isTrue();
        assertThat(truncated.previousDegradeReasons())
                .as("前两次的失败原文逐字留档：它们是「为什么试了 3 次」的唯一证据")
                .hasSize(2)
                .allSatisfy(r -> assertThat(r).contains("被 token 上限截断"));

        // ★ 这一条才是本次要钉住的事实：两者**旁证长得一样**（同为 LENGTH + 4096），
        //   于是只看 token 数会把「空正文（1 次）」与「非空截断（3 次）」读成同一个原因，
        //   而它们走的是两条完全不同的分支、处置也不同（前者调额度，后者换样本即可）
        assertThat(blank.degradeReason())
                .as("空正文这一档带的是 token 旁证")
                .contains("finish_reason=length")
                .contains("4096");
        assertThat(truncated.degradeReason())
                .as("截断这一档带的旁证与上一条**看起来一样**（这正是当时误判为「同因」的原因）")
                .contains("finish_reason=length")
                .contains("completionTokens=4096")
                .contains("被 token 上限截断");
        assertThat(blank.failureKind()).isNotEqualTo(truncated.failureKind());
        // 两条都必须保留融合原序（降级语义不因分类而变）
        assertThat(ids(blank.candidates())).containsExactly("a", "b");
        assertThat(ids(truncated.candidates())).containsExactly("a", "b");
    }

    @Test
    @DisplayName("★ 各条失败路径都带上正确的分类（stdout 靠它交代「为什么没重试」）")
    void everyFailurePathCarriesItsKind() {
        // 调模型之前就决定的几档
        assertThat(new LlmReranker(prompt -> "{\"ranking\":[]}", snippets("正文"), "m",
                Duration.ofSeconds(5), 3, 100).withMaxRetries(3)
                .rerank(PROBE, candidates("a", "b", "c", "d"), 10).failureKind())
                .isEqualTo(RerankOutcome.FailureKind.TOO_MANY_CANDIDATES);
        assertThat(reranker(prompt -> "{\"ranking\":[]}")
                .rerank(PROBE, candidates("a"), 10).failureKind())
                .as("正常路径：没有失败")
                .isEqualTo(RerankOutcome.FailureKind.NONE);

        LlmReranker emptySnippets = new LlmReranker(prompt -> "{\"ranking\":[]}",
                ChunkSnippetProvider.NONE, "m");
        RerankOutcome noSnippet = emptySnippets.rerank(PROBE, candidates("a", "b"), 10);
        assertThat(noSnippet.failureKind()).isEqualTo(RerankOutcome.FailureKind.EMPTY_SNIPPETS);
        assertThat(noSnippet.failureKind().beforeModelCall()).isTrue();
        assertThat(noSnippet.failureKind().retryable()).isFalse();

        LlmReranker throwingSnippets = new LlmReranker(prompt -> "{\"ranking\":[]}",
                chunkId -> {
                    throw new IllegalStateException("连不上数据库");
                }, "m");
        assertThat(throwingSnippets.rerank(PROBE, candidates("a", "b"), 10).failureKind())
                .isEqualTo(RerankOutcome.FailureKind.SNIPPET_FAILED);

        // 调模型之后失败的几档
        LlmReranker slow = new LlmReranker(prompt -> {
            Thread.sleep(5_000);
            return "{\"ranking\":[]}";
        }, snippets("正文"), "m", Duration.ofMillis(120),
                LlmReranker.DEFAULT_MAX_CANDIDATES, LlmReranker.DEFAULT_SNIPPET_CHARS);
        assertThat(slow.rerank(PROBE, candidates("a", "b"), 10).failureKind())
                .isEqualTo(RerankOutcome.FailureKind.TIMEOUT);
        assertThat(reranker(prompt -> {
            throw new java.net.ConnectException("Connection refused");
        }).rerank(PROBE, candidates("a", "b"), 10).failureKind())
                .as("网络 / 鉴权 / 5xx 同一档：处置都是「去看真故障」，不是再试一次")
                .isEqualTo(RerankOutcome.FailureKind.CALL_FAILED);
        assertThat(reranker(ok("{\"ranking\":[{\"chunk_id\":\"ghost\",\"score\":0.9}]}"))
                .rerank(PROBE, candidates("a", "b"), 10).failureKind())
                .isEqualTo(RerankOutcome.FailureKind.UNKNOWN_CHUNK_ID);
        assertThat(reranker(ok("{ 坏 ")).rerank(PROBE, candidates("a", "b"), 10).failureKind())
                .as("finish_reason 拿不到时仍算「解析失败」——但**不许**猜成「被截断」")
                .isEqualTo(RerankOutcome.FailureKind.UNPARSABLE);
    }

    @Test
    @DisplayName("★ 被中断也是一档独立分类，且中断位必须恢复（否则上层看不到中断）")
    void interruptedIsClassifiedAndTheFlagIsRestored() {
        RerankOutcome out;
        Thread.currentThread().interrupt();
        try {
            out = reranker(ok("{\"ranking\":[]}")).rerank(PROBE, candidates("a", "b"), 10);
            assertThat(Thread.interrupted())
                    .as("中断位必须恢复（本断言同时清掉它，免得影响后续用例）")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(out.degraded()).isTrue();
        assertThat(out.failureKind()).isEqualTo(RerankOutcome.FailureKind.INTERRUPTED);
        assertThat(out.attempts()).as("中断不重试：调用方要求停").isEqualTo(1);
    }

    @Test
    @DisplayName("★ 降级时把机器可读的 FailureKind 写进 ai.degrade.reason（否则「降级」与「重排没效果」长得一样）")
    void degradeReasonIsRecordedOnTheSpan() {
        Capturing capturing = new Capturing();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(capturing);

        RerankOutcome degraded = tracingReranker(ok(TRUNCATED_JSON), registry)
                .rerank(PROBE, candidates("a", "b"), 10);

        assertThat(degraded.degraded()).as("前提：这次调用真的降级了").isTrue();
        assertThat(degraded.failureKind()).isEqualTo(RerankOutcome.FailureKind.UNPARSABLE);
        assertThat(capturing.attrsOf(AiSpans.RAG_RERANK))
                .as("降级原因必须写进 Span，且取 FailureKind 的**枚举名**——"
                        + "RerankOutcome 自己写着「⛔ 不许让读者去猜 degradeReason 的措辞、"
                        + "更不许用字符串匹配来判断」，所以这里断言的是枚举名本身，不是中文措辞")
                .containsEntry(AiSpans.KEY_DEGRADE_REASON, "UNPARSABLE");
    }

    @Test
    @DisplayName("成功路径上**不写** ai.degrade.reason（若成功也写 NONE，键恒存在就没法用「键在不在」判断降级）")
    void successPathDoesNotRecordDegradeReason() {
        Capturing capturing = new Capturing();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(capturing);

        RerankOutcome succeeded = tracingReranker(
                ok("{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9}]}"), registry)
                .rerank(PROBE, candidates("a", "b"), 10);

        assertThat(succeeded.degraded()).as("前提：这次调用没有降级").isFalse();
        assertThat(capturing.attrsOf(AiSpans.RAG_RERANK))
                .as("成功路径不写降级原因（C-59 口径：只在真的降级时写；"
                        + "同 KEY_RAG_FALLBACK 的做法）")
                .doesNotContainKey(AiSpans.KEY_DEGRADE_REASON);
    }

    /** 带观测注册表的重排器（7 参构造器；超时/候选上限/片段长度给够，不干扰本次要测的降级分类）。 */
    private static LlmReranker tracingReranker(PromptCompleter completer,
                                                ObservationRegistry registry) {
        return new LlmReranker(completer, snippets("正文"), "stub-model",
                Duration.ofSeconds(30), 50, 2000, registry);
    }

    /** 只收属性、不做判断的 {@link ObservationHandler}。 */
    private static final class Capturing implements ObservationHandler<Observation.Context> {

        private final Map<String, Map<String, String>> byName = new LinkedHashMap<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        /**
         * ⚠️ 属性只能在 {@code onStop} 收：{@code observe(...)} 体内的属性是**启动之后**
         * 才写的，在 {@code onStart} 快照只会得到一份空属性表（那样断言会静默失效）。
         */
        @Override
        public void onStop(Observation.Context context) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                attrs.put(kv.getKey(), kv.getValue());
            }
            for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                attrs.put(kv.getKey(), kv.getValue());
            }
            byName.put(context.getName(), attrs);
        }

        Map<String, String> attrsOf(String spanName) {
            Map<String, String> got = byName.get(spanName);
            assertThat(got)
                    .as("Span「" + spanName + "」应当出现过："
                            + "没出现说明埋点没生效或被短路，后面的属性断言会因此失去意义")
                    .isNotNull();
            return got;
        }
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static final char LF_CHAR = (char) 10;
    private static final String LF = String.valueOf(LF_CHAR);
    private static final String ELLIPSIS = String.valueOf((char) 0x2026);

    /**
     * 「有正文、但被 token 上限切在半路」的形状（2026-09-21 真实试点题 3 的原文形状）。
     *
     * <p>它与"空正文"最容易混：两者 {@code finish_reason} 都是 {@code length}、
     * {@code completion} 都等于 {@code maxTokens}，差别只在<b>正文是不是一个字都没有</b>，
     * 而这一字之差决定它们走两条不同的分支（是否可重试）。
     */
    private static final String TRUNCATED_JSON =
            "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9},{\"chunk_id\":\"b\",\"sc";

    private static PromptCompleter ok(String raw) {
        return prompt -> raw;
    }

    /**
     * 带<b>旁证</b>的桩：模拟真实应答里的 {@code finish_reason} 与 token 用量。
     *
     * <p>为什么要单独一个夹具：{@link PromptCompleter#complete} 只给得回正文，
     * 而"被 token 上限截断"与"吐了非法 JSON"<b>只能靠 finish_reason 分辨</b>。
     * 用 lambda 建不出带旁证的桩（默认实现退化成"只有正文"），故这里用匿名类覆写
     * {@link PromptCompleter#completeWithEvidence}——这也顺带钉住了该默认方法的存在方式。
     */
    private static PromptCompleter withEvidence(String raw, String finishReason,
                                                Integer promptTokens, Integer completionTokens) {
        return new PromptCompleter() {

            @Override
            public String complete(String prompt) {
                return raw;
            }

            @Override
            public LlmReranker.Completion completeWithEvidence(String prompt) {
                return new LlmReranker.Completion(raw, finishReason, promptTokens, completionTokens);
            }
        };
    }

    private static ChunkSnippetProvider snippets(String text) {
        return chunkId -> text + "（" + chunkId + "）";
    }

    private static LlmReranker reranker(PromptCompleter completer) {
        return new LlmReranker(completer, snippets("正文"), "stub-model");
    }

    /** 走一次降级路径并取回原因，用于紧凑地断言多条"形状不合法"的输入。 */
    private static String reason(PromptCompleter completer) {
        return reranker(completer).rerank(PROBE, candidates("a", "b"), 10).degradeReason();
    }

    private static List<RetrievalCandidate> candidates(String... chunkIds) {
        List<RetrievalCandidate> out = new ArrayList<>();
        int rank = 0;
        for (String id : chunkIds) {
            out.add(RetrievalCandidate.fromHit("stub", 1.0 - rank, ++rank,
                    id, "doc-" + id, "文档 " + id, "BODY", "第" + rank + "条", "标题 " + id, "2026"));
        }
        return out;
    }

    private static List<String> ids(List<RetrievalCandidate> list) {
        return list.stream().map(RetrievalCandidate::chunkId).toList();
    }

    // ── 复核补充（2026-09-21）：推理 token 必须成为旁证的一部分 ──────────────

    @Test
    @DisplayName("★ 推理 token 从厂商原生 usage 取出：Map / JsonNode 都认，取不到返 null（不是 0）")
    void reasoningTokensComeFromNativeUsage() throws Exception {
        // 为什么值得单测：本项目模型是推理模型（实测 completion 1456 里 reasoning 875），
        // 思考 token 与正文共享 maxTokens 预算。取不到时若写成 0，就会被读成「没有思考」。
        assertThat(LlmReranker.reasoningTokensOf(new DefaultUsage(9213, 8192, 17405,
                Map.of("completion_tokens_details", Map.of("reasoning_tokens", 7100)))))
                .as("OpenAI / DeepSeek 系把推理用量放在 completion_tokens_details 里")
                .isEqualTo(7100);
        assertThat(LlmReranker.reasoningTokensOf(new DefaultUsage(1, 2, 3,
                Map.of("reasoning_tokens", 875))))
                .as("也有网关直接放在顶层，两种都要认")
                .isEqualTo(875);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(LlmReranker.reasoningTokensOf(new DefaultUsage(1, 2, 3,
                mapper.readTree("{\"completion_tokens_details\":{\"reasoning_tokens\":1234}}"))))
                .as("原生对象是 JsonNode 的形态也要认（形态不受本项目控制）")
                .isEqualTo(1234);
        assertThat(LlmReranker.reasoningTokensOf(new DefaultUsage(1, 2, 3, null)))
                .as("取不到就是 null：⛔ 不写 0——0 会被读成「没有思考」，真相是「这一层没告诉我们」")
                .isNull();
        assertThat(LlmReranker.reasoningTokensOf(new DefaultUsage(1, 2, 3, Map.of()))).isNull();
        assertThat(LlmReranker.reasoningTokensOf(null)).isNull();
    }

    @Test
    @DisplayName("★ 截断文案带 reasoningTokens：有值写数字，取不到写「未知」")
    void truncationReasonCarriesReasoningTokens() {
        LlmReranker.Completion truncated = new LlmReranker.Completion(
                "{\"ranking\":[{\"chunk_id\":\"a\"", "length", 9213, 8192, 7100);
        assertThat(LlmReranker.unparsableReason(truncated,
                new IllegalArgumentException("Unexpected end-of-input")))
                .contains("重排模型输出被 token 上限截断")
                .contains("finish_reason=length")
                .contains("completionTokens=8192")
                .as("推理 token 与正文共享 maxTokens：没有它分不清「额度不够」与「额度全被思考吃光」")
                .contains("reasoningTokens=7100");
        assertThat(LlmReranker.unparsableReason(
                new LlmReranker.Completion("x", "length", 1, 2), new IllegalArgumentException("坏")))
                .as("拿不到推理用量时写「未知」：⛔ 不写 0")
                .contains("reasoningTokens=未知");
    }

    @Test
    @DisplayName("★ 全链路：被截断时产物里的降级原因必须点名截断，且带推理 token")
    void degradedReasonOnTruncatedOutputMentionsTruncation() {
        String msg = reason(withEvidenceAndReasoning(
                "{\"ranking\":[{\"chunk_id\":\"a\",\"score\":0.9,\"reason\":\"写到一半", "length",
                9213, 8192, 7100));
        assertThat(msg)
                .as("这一句就是产物里的 degradeReason：必须让人一眼看出该调 maxTokens")
                .contains("被 token 上限截断")
                .contains("reasoningTokens=7100")
                .as("截断与「模型不听话」必须分得开：后者才是改提示词")
                .doesNotContain("模型输出非法 JSON");
    }

    /** 带旁证 + 推理 token 的桩（5 参 Completion）；必须匿名类，lambda 建不出带旁证的桩。 */
    private static PromptCompleter withEvidenceAndReasoning(String raw, String finishReason,
                                                            Integer promptTokens,
                                                            Integer completionTokens,
                                                            Integer reasoningTokens) {
        return new PromptCompleter() {

            @Override
            public String complete(String prompt) {
                return raw;
            }

            @Override
            public LlmReranker.Completion completeWithEvidence(String prompt) {
                return new LlmReranker.Completion(raw, finishReason, promptTokens, completionTokens,
                        reasoningTokens);
            }
        };
    }
}
