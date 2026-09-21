package com.canalmind.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索产物 JSON 契约的测试（第 2 周第 5 项）。
 *
 * <p><b>为什么这份格式值得单独一组测试</b>：它的消费者是既有的
 * {@code backend/scripts/eval_retrieval.py}，而该脚本对 schema 是<b>硬校验</b>
 * （缺键直接非 0 退出，不做猜测式容错）。格式类缺陷又几乎全是<b>静默</b>的：
 * 键名改一个字、{@code rank} 从 0 起、换行随平台变、多写一个时间戳，
 * 都不会抛异常——只会让下游少读到一列、或者让产物 diff 永远不为空
 * （AGENTS.md §7.2 的同一类坑）。故这里把契约的每一条都钉住。
 */
class RetrievalResultJsonTest {

    private static final char LF = (char) 10;
    private static final char CR = (char) 13;

    @TempDir
    Path tempDir;

    // ── 必需键（下游脚本的硬校验）──────────────────────────────────────────

    @Test
    @DisplayName("★ 顶层必需键齐全且在最前：channel / topK / queryCount / results")
    void requiredTopLevelKeys() throws Exception {
        JsonNode root = new ObjectMapper().readTree(render());
        assertThat(fieldNames(root)).startsWith(
                "channel", "topK", "queryCount", "results");
        assertThat(RetrievalResultJson.REQUIRED_KEYS)
                .containsExactly("channel", "topK", "queryCount", "results");
        assertThat(root.get("channel").asText()).isEqualTo("rrf+llm-rerank");
        assertThat(root.get("queryCount").asInt()).isEqualTo(1);
        assertThat(root.get("topK").asInt())
                .as("topK 必须是最终上下文条数（= contextTopN）：脚本用它当截断位上界，"
                        + "写错会按错误的 k 去算 Precision@k")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("★ 每条 hit 都有 rank/chunkId/score，且 rank 从 1 连续")
    void requiredHitKeysAndContiguousRank() throws Exception {
        JsonNode hits = new ObjectMapper().readTree(render()).get("results").get(0).get("hits");
        assertThat(hits).hasSize(5);
        for (int i = 0; i < hits.size(); i++) {
            JsonNode hit = hits.get(i);
            assertThat(hit.has("rank")).isTrue();
            assertThat(hit.has("chunkId")).isTrue();
            assertThat(hit.has("score")).isTrue();
            assertThat(hit.get("rank").asInt()).as("rank 必须从 1 起且连续").isEqualTo(i + 1);
            assertThat(hit.get("chunkId").isTextual()).isTrue();
            assertThat(hit.get("score").isNumber()).as("score 必须是数字而不是字符串").isTrue();
        }
    }

    @Test
    @DisplayName("★ score 取最终阶段分数：有重排用 rerankScore，无重排/降级用 fusedScore")
    void scoreIsFinalStageScore() throws Exception {
        JsonNode hits = new ObjectMapper().readTree(render()).get("results").get(0).get("hits");

        JsonNode reranked = hits.get(0);
        assertThat(reranked.get("rerankScore").isNumber()).isTrue();
        assertThat(reranked.get("score").doubleValue())
                .isEqualTo(reranked.get("rerankScore").doubleValue());

        JsonNode notReranked = hits.get(2);
        assertThat(notReranked.get("rerankScore").isNull()).isTrue();
        assertThat(notReranked.get("score").doubleValue())
                .as("没有重排分时 score 落到融合分")
                .isEqualTo(notReranked.get("fusedScore").doubleValue());
    }

    // ── 额外键（消融与排错用）────────────────────────────────────────────

    @Test
    @DisplayName("额外键：fusion/reranker/config/recallChannels/rerankDegraded + 每条 stageTrace")
    void extraKeysForAblation() throws Exception {
        JsonNode root = new ObjectMapper().readTree(render());
        assertThat(root.get("fusion").asText()).isEqualTo("rrf");
        assertThat(root.get("reranker").asText()).isEqualTo("llm-rerank");
        assertThat(root.get("recallChannels")).hasSize(2);
        assertThat(root.get("recallChannels").get(0).asText()).isEqualTo("vector");
        assertThat(root.get("rerankDegraded").asBoolean()).isTrue();
        assertThat(root.get("degradedQueryCount").asInt()).isEqualTo(1);

        JsonNode config = root.get("config");
        assertThat(config.get("recallTopK").asInt()).isEqualTo(30);
        assertThat(config.get("fusionTopK").asInt()).isEqualTo(20);
        assertThat(config.get("rerankTopN").asInt()).isEqualTo(10);
        assertThat(config.get("contextTopN").asInt()).isEqualTo(5);
        assertThat(config.get("rrfK").asInt()).isEqualTo(60);
        assertThat(config.get("rerankEnabled").asBoolean()).isTrue();

        JsonNode result = root.get("results").get(0);
        assertThat(result.get("degraded").asBoolean()).isTrue();
        assertThat(result.get("degradeReason").asText()).contains("超时");
        assertThat(result.get("rerankerModel").asText()).isEqualTo("deepseek-chat");
        assertThat(result.get("fusedCount").asInt()).isEqualTo(20);
        assertThat(fieldNames(result.get("stageTrace")))
                .containsExactly("recall:vector", "recall:bm25", "recall", "fusion", "rerank",
                        "context");
    }

    @Test
    @DisplayName("未降级时 degraded=false 且 degradeReason 为 JSON null（键仍在，值可为空）")
    void notDegradedWritesNullReason() throws Exception {
        RetrievalResult ok = result(RetrievalConfig.defaults(), null);
        String json = RetrievalResultJson.render(RetrievalConfig.defaults(),
                List.of("vector"), List.of(ok));
        JsonNode result = new ObjectMapper().readTree(json).get("results").get(0);
        assertThat(result.get("degraded").asBoolean()).isFalse();
        assertThat(result.has("degradeReason")).isTrue();
        assertThat(result.get("degradeReason").isNull()).isTrue();
    }

    // ── 重试痕迹（B4 配套：重试过就必须看得出来）────────────────────────────

    @Test
    @DisplayName("★ 重试三键：每题 rerankAttempts / previousDegradeReasons，顶层 rerankAttemptsTotal")
    void retryTracesAreInTheArtifact() throws Exception {
        RetrievalConfig config = RetrievalConfig.defaults();   // rerankEnabled=true, contextTopN=5

        // 三题三种形态（覆盖全部组合）：
        //   ① 一次成功         → attempts=1、previous 空
        //   ② 重试后才成功     → degraded=false 但 attempts=3、previous 有 2 条
        //   ③ 重试后仍降级     → degraded=true、attempts=3、previous 有 2 条、最后一次进 degradeReason
        RetrievalResult oneShot = result(config, null, 1, List.of());
        RetrievalResult retriedOk = result(config, null, 3,
                List.of("第一次：重排模型输出无法解析为约定 JSON（模型输出非法 JSON）：JSON 语法错",
                        "第二次：重排模型输出被 token 上限截断（finish_reason=length，completionTokens=16384）"));
        RetrievalResult stillDegraded = result(config,
                "重排模型输出被 token 上限截断（finish_reason=length，completionTokens=16384），"
                        + "截断的输出必然不是完整 JSON",
                3, List.of("第 1 次：JSON 语法错", "第 2 次：JSON 语法错"));

        JsonNode root = new ObjectMapper().readTree(RetrievalResultJson.render(config,
                List.of("vector", "bm25"), List.of(oneShot, retriedOk, stillDegraded)));
        JsonNode results = root.get("results");

        // ① 一次成功
        assertThat(results.get(0).get("rerankAttempts").asInt()).isEqualTo(1);
        assertThat(results.get(0).get("previousDegradeReasons")).isEmpty();
        assertThat(results.get(0).get("degradeReason").isNull()).isTrue();

        // ② 重试后成功：⛔ 不许写成"一次成功"
        assertThat(results.get(1).get("degraded").asBoolean())
                .as("重试后成功仍是 degraded=false（降级=false），但光看这一位分不出重试与否")
                .isFalse();
        assertThat(results.get(1).get("rerankAttempts").asInt())
                .as("★ 读者靠它看出「这次不是一次成功的」")
                .isEqualTo(3);
        assertThat(results.get(1).get("previousDegradeReasons")).hasSize(2);
        assertThat(results.get(1).get("previousDegradeReasons").get(0).asText())
                .as("前几次失败的原文逐字保留")
                .contains("模型输出非法 JSON");
        assertThat(results.get(1).get("previousDegradeReasons").get(1).asText())
                .contains("被 token 上限截断");
        assertThat(results.get(1).get("degradeReason").isNull())
                .as("最终成功，故 degradeReason 为 null（失败原文在 previousDegradeReasons 里）")
                .isTrue();

        // ③ 重试后仍降级
        assertThat(results.get(2).get("degraded").asBoolean()).isTrue();
        assertThat(results.get(2).get("rerankAttempts").asInt()).isEqualTo(3);
        assertThat(results.get(2).get("previousDegradeReasons")).hasSize(2);
        assertThat(results.get(2).get("degradeReason").asText())
                .as("最后一次的原文进 degradeReason，不是被丢掉")
                .contains("被 token 上限截断");

        assertThat(root.get("rerankAttemptsTotal").asInt())
                .as("顶层总尝试次数 = 1 + 3 + 3（配合 queryCount 可看平均试了几回）")
                .isEqualTo(7);
        assertThat(root.get("degradedQueryCount").asInt())
                .as("★ degradedQueryCount 只数「**重试后仍降级**」的条数："
                        + "第 ② 题曾经失败过但重试成功，不算降级")
                .isEqualTo(1);
        assertThat(root.get("rerankDegraded").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★ 逐题判定键：rerankDegraded / degradeKind / rerankRecoveredByRetry，且与顶层计数可交叉核对")
    void perQueryDegradeFlagsCrossCheckWithTopLevelCount() throws Exception {
        RetrievalConfig config = RetrievalConfig.defaults();

        // 四题四种形态（穷尽「逐题四态」表）：
        //   ① 一次成功       ② 重试后才成功    ③ 降级且未重试（空正文）   ④ 降级且重试耗尽
        RetrievalResult oneShot = result(config, null, 1, List.of());
        RetrievalResult retriedOk = result(config, null, 3, List.of("第 1 次坏", "第 2 次坏"));
        RetrievalResult blank = result(config,
                "重排模型返回了空正文（finish_reason=length，completion 4096 tokens）", 1, List.of(),
                RerankOutcome.FailureKind.BLANK_COMPLETION);
        RetrievalResult exhausted = result(config, "重排模型输出被 token 上限截断", 3,
                List.of("第 1 次坏", "第 2 次坏"), RerankOutcome.FailureKind.UNPARSABLE);

        JsonNode root = new ObjectMapper().readTree(RetrievalResultJson.render(config,
                List.of("vector", "bm25"), List.of(oneShot, retriedOk, blank, exhausted)));
        JsonNode results = root.get("results");

        // ① 一次成功
        assertThat(results.get(0).get("rerankDegraded").asBoolean()).isFalse();
        assertThat(results.get(0).get("rerankRecoveredByRetry").asBoolean()).isFalse();
        assertThat(results.get(0).get("rerankAttempts").asInt()).isEqualTo(1);
        assertThat(results.get(0).get("degradeKind").isNull()).isTrue();

        // ② 重试后才成功：⛔ 不许读成「一次跑通」
        assertThat(results.get(1).get("rerankDegraded").asBoolean()).isFalse();
        assertThat(results.get(1).get("rerankRecoveredByRetry").asBoolean())
                .as("★ 这一位就是「失败过但重试成功」的唯一判据（不必再让读者自己拼两个键）")
                .isTrue();
        assertThat(results.get(1).get("rerankAttempts").asInt()).isEqualTo(3);
        assertThat(results.get(1).get("degradeKind").isNull())
                .as("最终成功，故没有「最后一次失败的分类」")
                .isTrue();

        // ③ 降级且未重试：attempts=1 但**不是**一次成功
        assertThat(results.get(2).get("rerankDegraded").asBoolean()).isTrue();
        assertThat(results.get(2).get("rerankRecoveredByRetry").asBoolean()).isFalse();
        assertThat(results.get(2).get("rerankAttempts").asInt()).isEqualTo(1);
        assertThat(results.get(2).get("degradeKind").asText())
                .as("分类是机器可读的「为什么没重试」依据")
                .isEqualTo("BLANK_COMPLETION");

        // ④ 降级且重试耗尽
        assertThat(results.get(3).get("rerankDegraded").asBoolean()).isTrue();
        assertThat(results.get(3).get("rerankAttempts").asInt()).isEqualTo(3);
        assertThat(results.get(3).get("degradeKind").asText()).isEqualTo("UNPARSABLE");

        // ★ 交叉核对一：逐题 degraded 是 rerankDegraded 的同义别名（同源，不许各自计算）
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).get("degraded").asBoolean())
                    .as("第 %d 题的 degraded 与 rerankDegraded 必须同值", i + 1)
                    .isEqualTo(results.get(i).get("rerankDegraded").asBoolean());
        }

        // ★ 交叉核对二：逐题 true 的条数 ≡ 顶层 degradedQueryCount（本题 2 条）
        int counted = 0;
        for (JsonNode r : results) {
            if (r.get("rerankDegraded").asBoolean()) {
                counted++;
            }
        }
        assertThat(counted).isEqualTo(2);
        assertThat(root.get("degradedQueryCount").asInt())
                .as("顶层计数必须等于逐题 true 的条数（重试后成功那题不算降级）")
                .isEqualTo(counted);
        assertThat(root.get("rerankDegraded").asBoolean()).isTrue();
        assertThat(root.get("rerankAttemptsTotal").asInt())
                .as("1 + 3 + 1 + 3 = 8：顶层总数也要与逐题 attempts 对得上")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("无 rerank 档：rerankAttempts 全为 0（本档一次模型都没调），重试键仍在")
    void noRerankTierReportsZeroAttempts() throws Exception {
        RetrievalConfig config = new RetrievalConfig(30, 20, 10, 5, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);
        RetrievalResult one = result(config, null);   // 夹具默认 attempts=0

        JsonNode root = new ObjectMapper().readTree(RetrievalResultJson.render(config,
                List.of("vector"), List.of(one)));

        assertThat(root.get("results").get(0).get("rerankAttempts").asInt())
                .as("0 = 一次都没调模型（本档没有模型），与 degraded=false 并不矛盾")
                .isZero();
        assertThat(root.get("results").get(0).get("previousDegradeReasons")).isEmpty();
        assertThat(root.get("rerankAttemptsTotal").asInt()).isZero();
        assertThat(root.get("rerankDegraded").asBoolean()).isFalse();
        assertThat(root.get("degradedQueryCount").asInt()).isZero();
    }

    // ── 确定性：编码 / 换行 / 无时间戳 ────────────────────────────────────

    @Test
    @DisplayName("★ 无 BOM、CR 字节数 0、无时间戳键、两次渲染字节完全相同")
    void deterministicBytes() throws Exception {
        String first = render();
        String second = render();
        assertThat(second).as("同一输入必须渲染出完全相同的字符串").isEqualTo(first);

        assertThat(first.indexOf(CR)).as("字符串里不得出现 CR（Jackson 默认缩进器是 SYS_LF）")
                .isEqualTo(-1);

        Path out = tempDir.resolve("nested/dir/retrieval.json");
        RetrievalResultJson.write(out, first);
        byte[] bytes = Files.readAllBytes(out);
        assertThat(Files.isRegularFile(out)).as("目录不存在时应自动创建").isTrue();

        assertThat(bytes.length).isGreaterThan(3);
        boolean hasBom = bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
        assertThat(hasBom).as("产物不得以 UTF-8 BOM 开头").isFalse();
        long crCount = 0;
        for (byte b : bytes) {
            if (b == (byte) 0x0D) {
                crCount++;
            }
        }
        assertThat(crCount).as("文件里的 CR 字节数必须是 0（只允许 LF）").isZero();

        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).endsWith(String.valueOf(LF));
        for (String forbidden : List.of("timestamp", "generatedAt", "elapsedMs", "latencyMillis",
                "runAt")) {
            assertThat(text)
                    .as("产物不得含时间戳/耗时键：" + forbidden + "（否则 diff 永远不为空）")
                    .doesNotContain(forbidden);
        }

        // 再写一次，逐字节比较
        Path out2 = tempDir.resolve("nested/dir/retrieval-2.json");
        RetrievalResultJson.write(out2, render());
        assertThat(Files.readAllBytes(out2)).isEqualTo(bytes);
    }

    @Test
    @DisplayName("空 results 也是合法产物（queryCount=0），结构不缺键")
    void emptyResultsStillValid() throws Exception {
        String json = RetrievalResultJson.render(RetrievalConfig.defaults(), List.of("vector"),
                List.of());
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("queryCount").asInt()).isZero();
        assertThat(root.get("results").size()).isZero();
        assertThat(fieldNames(root)).startsWith("channel", "topK", "queryCount", "results");
        assertThat(root.get("rerankDegraded").asBoolean()).isFalse();
        assertThat(root.get("degradedQueryCount").asInt()).isZero();
    }

    @Test
    @DisplayName("无 rerank 档：channel 如实反映通道集（单路 vector 就写 vector，两路才写 rrf），reranker=none")
    void noRerankShapesArtifact() throws Exception {
        RetrievalConfig config = new RetrievalConfig(30, 20, 10, 5, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);
        RetrievalResult one = result(config, null);

        String single = RetrievalResultJson.render(config, List.of("vector"), List.of(one));
        JsonNode root = new ObjectMapper().readTree(single);
        assertThat(root.get("channel").asText())
                .as("单通道档必须写通道名：写死 rrf 会让 vector 档与 bm25 档的产物无法区分")
                .isEqualTo("vector");
        assertThat(root.get("reranker").asText()).isEqualTo(RerankOutcome.NO_MODEL);
        assertThat(root.get("rerankDegraded").asBoolean()).isFalse();
        JsonNode hit = root.get("results").get(0).get("hits").get(0);
        assertThat(hit.get("rerankRank").isNull()).isTrue();
        assertThat(hit.get("rerankScore").isNull()).isTrue();

        // 两路（无 rerank）→ rrf；单路 bm25 → bm25
        assertThat(new ObjectMapper().readTree(RetrievalResultJson.render(config,
                List.of("vector", "bm25"), List.of(one))).get("channel").asText()).isEqualTo("rrf");
        assertThat(new ObjectMapper().readTree(RetrievalResultJson.render(config,
                List.of("bm25"), List.of(one))).get("channel").asText()).isEqualTo("bm25");
    }

    @Test
    @DisplayName("★ 四路对照的 channel 取值：vector / bm25 / rrf，带重排时各加 +llm-rerank 后缀")
    void channelReflectsFourthWayAbalation() throws Exception {
        RetrievalConfig noRerank = RetrievalConfig.defaults().withoutRerank();
        RetrievalResult one = result(noRerank, null);

        assertThat(channelOf(noRerank, List.of("vector"), one)).isEqualTo("vector");
        assertThat(channelOf(noRerank, List.of("bm25"), one)).isEqualTo("bm25");
        assertThat(channelOf(noRerank, List.of("vector", "bm25"), one)).isEqualTo("rrf");

        RetrievalConfig withRerank = RetrievalConfig.defaults();
        RetrievalResult ranked = result(withRerank, null);
        assertThat(channelOf(withRerank, List.of("vector"), ranked)).isEqualTo("vector+llm-rerank");
        assertThat(channelOf(withRerank, List.of("bm25"), ranked)).isEqualTo("bm25+llm-rerank");
        assertThat(channelOf(withRerank, List.of("vector", "bm25"), ranked))
                .isEqualTo("rrf+llm-rerank");
    }

    private static String channelOf(RetrievalConfig config, List<String> channels,
                                    RetrievalResult result) throws Exception {
        return new ObjectMapper().readTree(RetrievalResultJson.render(config, channels,
                List.of(result))).get("channel").asText();
    }

    @Test
    @DisplayName("★ 不补齐条数：上游只给 2 条就写 2 条，同时能自查出「没填满 topK」的题")
    void doesNotPadShortResultsAndFlagsThem() throws Exception {
        RetrievalConfig config = RetrievalConfig.defaults();   // contextTopN = 5
        RetrievalResult shortOne = shortResult(config, 2);     // 只有 2 条 → 没填满
        RetrievalResult full = result(config, null);           // 夹具给 5 条 → 正好填满

        String json = RetrievalResultJson.render(config, List.of("vector"),
                List.of(shortOne, full));
        JsonNode results = new ObjectMapper().readTree(json).get("results");

        assertThat(results.get(0).get("hits").size())
                .as("产物不得为了迎合校验而补齐候选：补出来的命中是伪造的")
                .isEqualTo(2);
        assertThat(results.get(1).get("hits").size()).isEqualTo(5);

        assertThat(RetrievalResultJson.queriesNotFillingContext(config,
                List.of(shortOne, full)))
                .as("只有第 1 条没填满，必须能被指名道姓地报出来（且不误报填满的那条）")
                .containsExactly("过闸费怎么算(2条)");

        // 填满 contextTopN 时不报（避免"每次运行都警告"，警告会被无视）
        assertThat(RetrievalResultJson.queriesNotFillingContext(config,
                List.of(shortResult(config, 5)))).isEmpty();
        assertThat(RetrievalResultJson.queriesNotFillingContext(config, List.of())).isEmpty();
        assertThat(RetrievalResultJson.queriesNotFillingContext(config, null)).isEmpty();
    }

    // ── 下游契约解析（逐条对应 eval_retrieval.py 的硬校验）─────────────────

    @Test
    @DisplayName("★ 契约解析：产物必须能被「4 个顶层键 + 每条 hit 的 rank/chunkId/score」的解析器吃下去")
    void parsesWithTheDownstreamContract() throws Exception {
        // 这里的每一条断言都对应 backend/scripts/eval_retrieval.py 的一处硬校验：
        //   load_hits() 第 97-99 行  顶层键 channel/topK/queryCount/results 缺一即非 0 退出
        //   load_hits() 第 100-102 行 queryCount 必须等于 results 条数
        //   load_hits() 第 104-105 行 results 元素必须有 query/hits
        //   load_hits() 第 106-109 行 每条 hit 必须有 rank/chunkId/score
        //   load_hits() 第 110-111 行 rank 必须逐位等于序号（从 1 起、连续）
        //   main() 第 308-309 行      max(k) 不得超过 topK
        //   main() 第 318-320 行      每题条数必须**恰好**等于 topK
        // ⛔ 解析器刻意写得"笨"（硬校验、不容错、不猜测），与 Python 侧一致：
        //    宽容的解析器会把"键名写错一半"的产物悄悄读成另一件事。
        assertContractSatisfied(render());

        // 三档/单通道/空结果三种形态都要能满足同一份契约
        RetrievalConfig config = RetrievalConfig.defaults();
        assertContractSatisfied(RetrievalResultJson.render(config, List.of("vector"),
                List.of(result(config, null))));
        assertContractSatisfied(RetrievalResultJson.render(config, List.of("bm25"),
                List.of(result(config, null))));
        assertContractSatisfied(RetrievalResultJson.render(config, List.of(),
                List.of()));
    }

    /**
     * 模拟 {@code eval_retrieval.py} 的 {@code load_hits()}：契约不符就抛。
     *
     * <p>真实脚本的跑法另见交付回报（用 40 条真实题面的产物真跑过，退出码 0）；
     * 这里做成单测是为了让契约<b>每次构建都被检查一次</b>，
     * 而不是等到"某天要出评测报告时才发现产物读不进去"。
     */
    private static void assertContractSatisfied(String json) throws Exception {
        JsonNode root = new ObjectMapper().readTree(json);
        for (String key : RetrievalResultJson.REQUIRED_KEYS) {
            assertThat(root.has(key)).as("顶层缺必需键 " + key).isTrue();
        }
        assertThat(root.get("results").isArray()).isTrue();
        assertThat(root.get("queryCount").asInt())
                .as("queryCount 必须等于 results 条数（脚本第 100 行）")
                .isEqualTo(root.get("results").size());
        int topK = root.get("topK").asInt();
        assertThat(topK).as("topK 必须为正（脚本拿它当 k 的上界）").isGreaterThan(0);
        for (JsonNode r : root.get("results")) {
            assertThat(r.has("query")).as("results 元素缺 query（脚本第 104 行）").isTrue();
            assertThat(r.get("hits").isArray()).as("results 元素缺 hits").isTrue();
            assertThat(r.get("hits").size())
                    .as("每题条数必须恰好等于 topK（脚本第 318-320 行）")
                    .isEqualTo(topK);
            int i = 1;
            for (JsonNode hit : r.get("hits")) {
                for (String key : RetrievalResultJson.REQUIRED_HIT_KEYS) {
                    assertThat(hit.has(key)).as("命中项缺字段 " + key + "（脚本第 106-109 行）").isTrue();
                }
                assertThat(hit.get("chunkId").asText()).isNotEmpty();
                assertThat(hit.get("score").isNumber()).as("score 必须是数字").isTrue();
                assertThat(hit.get("rank").asInt())
                        .as("rank 必须逐位等于 " + i + "（脚本第 110 行）")
                        .isEqualTo(i);
                i++;
            }
        }
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 只有 {@code hitCount} 条上下文的结果（用于验证"不补齐"与填写满自查）。 */
    private static RetrievalResult shortResult(RetrievalConfig config, int hitCount) {
        List<RetrievalCandidate> fused = new ArrayList<>();
        for (int i = 1; i <= hitCount; i++) {
            fused.add(RetrievalCandidate.of("chunk-" + i).withFusedScore(1.0 / (60 + i)));
        }
        return new RetrievalResult("过闸费怎么算(" + hitCount + "条)", List.copyOf(fused),
                List.copyOf(fused),
                RerankOutcome.ok(List.copyOf(fused), 1L, "deepseek-chat"),
                Map.of("context", fused.stream().map(RetrievalCandidate::chunkId).toList()));
    }

    /**
     * 结果夹具：contextTopN=5，所以上下文恰好 5 条（下游脚本要求每题条数**恰好**等于 topK）。
     * 其中第 1 条正常重排、第 2 条分低、第 3 条模型没给分（名次有、分数为 null）、
     * 第 4/5 条正常——三种取值都覆盖到。
     */
    private static String render() {
        RetrievalConfig config = new RetrievalConfig(30, 20, 10, 5, 60, true,
                RrfFusion.NAME, LlmReranker.NAME);
        RetrievalResult result = result(config, "重排模型调用超时（等待上限 30000 ms）");
        return RetrievalResultJson.render(config, List.of("vector", "bm25"), List.of(result));
    }

    private static RetrievalResult result(RetrievalConfig config, String degradeReason) {
        return result(config, degradeReason, 0, List.of());
    }

    /**
     * 同上，但带上<b>重试痕迹</b>（{@code attempts} / {@code previousDegradeReasons}）。
     *
     * @param attempts  本次共尝试几次（0 = 没调过模型）
     * @param previous  前几次失败的原文（长度 ≤ attempts）
     */
    private static RetrievalResult result(RetrievalConfig config, String degradeReason,
                                          int attempts, List<String> previous) {
        // 既有夹具不给分类 ⇒ 记作「未标注」（与 RerankOutcome 的旧工厂一致）
        return result(config, degradeReason, attempts, previous,
                RerankOutcome.FailureKind.UNSPECIFIED);
    }

    /**
     * 同上，但把<b>最后一次失败的分类</b>也给出（逐题 {@code degradeKind} 的取值来源）。
     *
     * @param kind 未降级时被 {@link RerankOutcome} 归一成 NONE，故这里传什么都无所谓
     */
    private static RetrievalResult result(RetrievalConfig config, String degradeReason,
                                          int attempts, List<String> previous,
                                          RerankOutcome.FailureKind kind) {
        List<RetrievalCandidate> fused = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            fused.add(RetrievalCandidate.of("chunk-" + String.format("%02d", i))
                    .withChannel("vector", 0.9 - i * 0.01, i)
                    .withChannel("bm25", 30.0 - i, i + 1)
                    .withFusedScore(1.0 / (60 + i)));
        }

        // 无 rerank 档：候选不带重排字段（与 NoopReranker 的行为一致——它不写 rerankRank/Score）
        List<RetrievalCandidate> ranked = new ArrayList<>();
        if (config.rerankEnabled()) {
            ranked.add(fused.get(0).withRerank(1, 0.87));
            ranked.add(fused.get(1).withRerank(2, 0.42));
            ranked.add(fused.get(2).withRerank(3, null));   // 模型漏了它：名次有、分数为 null
            ranked.add(fused.get(3).withRerank(4, 0.11));
            ranked.add(fused.get(4).withRerank(5, 0.05));
        } else {
            ranked.addAll(fused.subList(0, 5));
        }
        List<RetrievalCandidate> context = ranked.size() <= config.contextTopN()
                ? List.copyOf(ranked) : List.copyOf(ranked.subList(0, config.contextTopN()));

        RerankOutcome outcome = degradeReason == null
                ? RerankOutcome.ok(List.copyOf(ranked), 1234L, "deepseek-chat", attempts, previous)
                : RerankOutcome.degraded(List.copyOf(ranked), degradeReason, 1234L, "deepseek-chat",
                        attempts, previous, kind);

        List<String> ids = fused.stream().map(RetrievalCandidate::chunkId).toList();
        Map<String, List<String>> trace = new LinkedHashMap<>();
        trace.put("recall:vector", ids.subList(0, 3));
        trace.put("recall:bm25", ids.subList(1, 4));
        trace.put("recall", ids.subList(0, 4));
        trace.put("fusion", ids);
        trace.put("rerank", List.of(fused.get(0).chunkId(), fused.get(1).chunkId(),
                fused.get(2).chunkId()));
        trace.put("context", context.stream().map(RetrievalCandidate::chunkId).toList());
        return new RetrievalResult("过闸费怎么算", context, fused, outcome, trace);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }
}
