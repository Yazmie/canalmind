package com.canalmind.rag.vector;

import com.canalmind.rag.vector.VectorResultJson.QueryHits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量检索 JSON 契约的测试（第 2 周第 5 项）。
 *
 * <p><b>为什么给"产物格式"专门写一组测试</b>：这份 JSON 有下游脚本要解析，
 * 而格式类缺陷几乎全是<b>静默</b>的——键名改一个字、rank 从 0 开始、换行随平台变，
 * 都不会抛异常，只会让下游少读到一列或 diff 永远不为空。
 * 本项目在"生成产物时换行/编码必须自己钉死"上已经踩过（AGENTS.md §7.2），
 * 故这里把三件事都钉住：<b>键名与键序</b>、<b>rank 从 1 起</b>、
 * <b>UTF-8 无 BOM + 仅 LF + 无时间戳</b>。
 */
class VectorResultJsonTest {

    private static final char LF = (char) 10;
    private static final char CR = (char) 13;

    @TempDir
    Path tempDir;

    private static VectorHit hit(String chunkId, String chunkType, String articleNo, double score) {
        return new VectorHit(chunkId, "06-过闸费费率表（2026版）", "过闸费费率表（2026版）",
                chunkType, articleNo, "目的与依据", "2026", score);
    }

    private static String sampleJson() {
        List<QueryHits> results = new ArrayList<>();
        results.add(new QueryHits("过闸费怎么算", List.of(
                hit("3c58adb940ca3987", "BODY", "第一条", 0.812345),
                hit("aaaaaaaaaaaaaaaa", "TABLE", null, 0.5))));
        return VectorResultJson.render("ollama:bge-m3:1024", 30, results);
    }

    // ── 键名与键序：契约的硬要求 ──────────────────────────────────────────

    @Test
    @DisplayName("顶层键名与顺序严格等于契约（多一个键都可能让严格解析器失败）")
    void topLevelKeysMatchContract() throws Exception {
        JsonNode root = new ObjectMapper().readTree(sampleJson());
        assertThat(fieldNames(root)).containsExactly(
                "channel", "profileName", "topK", "queryCount", "results");
        assertThat(root.get("channel").asText()).isEqualTo("vector");
        assertThat(root.get("profileName").asText()).isEqualTo("ollama:bge-m3:1024");
        assertThat(root.get("topK").asInt()).isEqualTo(30);
        assertThat(root.get("queryCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("hits 的键名与顺序严格等于契约，score 是 JSON 数字（不是字符串）")
    void hitKeysMatchContract() throws Exception {
        JsonNode hit = new ObjectMapper().readTree(sampleJson())
                .get("results").get(0).get("hits").get(0);
        assertThat(fieldNames(hit)).containsExactly(
                "rank", "chunkId", "docId", "docTitle", "chunkType",
                "articleNo", "articleTitle", "version", "score");
        assertThat(hit.get("rank").isNumber()).isTrue();
        assertThat(hit.get("score").isNumber()).isTrue();
        assertThat(hit.get("score").doubleValue()).isEqualTo(0.812345);
        assertThat(hit.get("chunkId").asText()).isEqualTo("3c58adb940ca3987");
        assertThat(hit.get("chunkType").asText()).isEqualTo("BODY");
        assertThat(hit.get("articleNo").asText()).isEqualTo("第一条");
        assertThat(hit.get("version").asText()).isEqualTo("2026");
    }

    @Test
    @DisplayName("rank 从 1 开始、逐条递增（下游靠它对齐名次，从 0 开始会让整列偏一位）")
    void rankStartsAtOne() throws Exception {
        JsonNode hits = new ObjectMapper().readTree(sampleJson())
                .get("results").get(0).get("hits");
        assertThat(hits.get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(hits.get(1).get("rank").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("没有条号的 chunk（文档级）→ articleNo 为 JSON null，而不是空串或漏键")
    void nullArticleIsNullNotNullMissing() throws Exception {
        JsonNode hit = new ObjectMapper().readTree(sampleJson())
                .get("results").get(0).get("hits").get(1);
        assertThat(hit.has("articleNo")).isTrue();
        assertThat(hit.get("articleNo").isNull()).isTrue();
    }

    @Test
    @DisplayName("多查询：queryCount 等于 results 条数，每个查询各自的 hits 独立")
    void multipleQueries() throws Exception {
        List<QueryHits> results = List.of(
                new QueryHits("过闸费怎么算", List.of(hit("c1", "BODY", "第一条", 0.9))),
                new QueryHits("船舶通过能力是多少？", List.of()));
        JsonNode root = new ObjectMapper().readTree(
                VectorResultJson.render("ollama:bge-m3:1024", 5, results));

        assertThat(root.get("queryCount").asInt()).isEqualTo(2);
        assertThat(root.get("results").get(0).get("query").asText()).isEqualTo("过闸费怎么算");
        assertThat(root.get("results").get(1).get("query").asText()).isEqualTo("船舶通过能力是多少？");
        assertThat(root.get("results").get(1).get("hits").size()).isZero();
        assertThat(root.get("topK").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("topK 记的是**请求值**（下游靠它区分「候选只有这么多」与「参数配小了」）")
    void topKIsRequestedNotHitCount() throws Exception {
        String json = VectorResultJson.render("ollama:bge-m3:1024", 30,
                List.of(new QueryHits("过闸费怎么算", List.of(hit("c1", "BODY", "第一条", 0.9)))));
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("topK").asInt()).isEqualTo(30);
        assertThat(root.get("results").get(0).get("hits").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("参数非法：profileName 空白 / topK < 1 → 拒绝（不能产出违约的产物）")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> VectorResultJson.render("  ", 5, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileName");
        assertThatThrownBy(() -> VectorResultJson.render("ollama:bge-m3:1024", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    // ── 字节级：编码、换行、可复现 ────────────────────────────────────────

    @Test
    @DisplayName("⚠️ 仅 LF、无 CR（Jackson 默认缩进器是 SYS_LF，Windows 上会产出 CRLF）")
    void usesLfOnly() {
        String json = sampleJson();
        assertThat(json.indexOf(CR)).isEqualTo(-1);
        assertThat(json.indexOf(LF)).isGreaterThan(0);
    }

    @Test
    @DisplayName("同一份输入渲染两次 → 完全相同的字符串（产物里没有任何时间戳/随机量）")
    void renderingIsDeterministic() throws Exception {
        assertThat(sampleJson()).isEqualTo(sampleJson());

        Path out = tempDir.resolve("a.json");
        VectorResultJson.write(out, sampleJson());
        byte[] first = Files.readAllBytes(out);
        VectorResultJson.write(out, sampleJson());
        assertThat(Files.readAllBytes(out)).isEqualTo(first);

        // 契约键里没有时间戳类字段：用"顶层键就是这五个"来保证（多一个键都会在这里红）
        JsonNode root = new ObjectMapper().readTree(new String(first, StandardCharsets.UTF_8));
        assertThat(fieldNames(root)).containsExactly(
                "channel", "profileName", "topK", "queryCount", "results");
    }

    @Test
    @DisplayName("写文件：UTF-8 无 BOM，中文可原样读回")
    void writesUtf8WithoutBom() throws IOException {
        Path out = tempDir.resolve("nested/dir/result.json");
        VectorResultJson.write(out, sampleJson());

        byte[] bytes = Files.readAllBytes(out);
        assertThat(bytes.length).isGreaterThan(3);
        assertThat(bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF)
                .as("产物不得带 UTF-8 BOM")
                .isFalse();

        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).contains("过闸费怎么算").contains("第一条");
        assertThat(text.indexOf(CR)).isEqualTo(-1);
        // 嵌套目录会被自动创建（--out docs/评测报告/xxx.json 的首跑）
        assertThat(Files.isRegularFile(out)).isTrue();
    }

    @Test
    @DisplayName("空 results 也是合法产物（queryCount=0），不会写出一份结构不同的文件")
    void emptyResultsIsStillValid() throws Exception {
        String json = VectorResultJson.render("ollama:bge-m3:1024", 30, List.of());
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("queryCount").asInt()).isZero();
        assertThat(root.get("results").size()).isZero();
        assertThat(fieldNames(root)).containsExactly(
                "channel", "profileName", "topK", "queryCount", "results");
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
