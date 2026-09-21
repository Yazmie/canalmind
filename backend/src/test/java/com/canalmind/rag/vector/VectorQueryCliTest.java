package com.canalmind.rag.vector;

import com.canalmind.rag.vector.VectorSearcher.Metric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量检索 CLI 的契约测试（第 2 周第 5 项）。
 *
 * <p>命令行入口通常"没人测"，但它的两处恰恰最容易出事：
 * <ol>
 *   <li><b>默认值</b>：默认 Top-K 若与 BM25 通道不一致，两通道对照会凭空差一截，
 *       而这种差异<b>不会报错</b>；</li>
 *   <li><b>{@code --query-file} 的读法</b>：中文查询只能从 UTF-8 文件来（argv 会被
 *       Windows 代码页改坏），一旦读法有偏差（丢掉行、带上 BOM、把注释当查询），
 *       症状是"某条查询莫名召不回正确内容"——而乱码/BOM 都<b>不会报错</b>。</li>
 * </ol>
 *
 * <p>测试不连库、不连 Ollama：只测参数解析、探针文件读法与选项校验。
 */
class VectorQueryCliTest {

    private static final char LF = (char) 10;
    private static final char TAB = (char) 9;
    private static final char BOM = (char) 0xFEFF;

    // ── 默认值与参数解析 ──────────────────────────────────────────────────

    @Test
    @DisplayName("默认 Top-K 是 30（与 rag_bm25_search 默认值对齐，两通道对照口径一致）")
    void defaultTopKIsThirty() {
        VectorQueryCli.Config cfg = VectorQueryCli.parseArgs(new String[0]);
        assertThat(VectorQueryCli.DEFAULT_TOP_K).isEqualTo(30);
        assertThat(cfg.topK).isEqualTo(30);
        assertThat(cfg.options.topK()).isEqualTo(30);
    }

    @Test
    @DisplayName("默认度量是 cosine（精确扫描的评测基线），不是走索引的 ip")
    void defaultMetricIsCosine() {
        VectorQueryCli.Config cfg = VectorQueryCli.parseArgs(new String[0]);
        assertThat(cfg.metric).isEqualTo(Metric.COSINE);
        assertThat(cfg.metric.usesHnswIndex()).isFalse();
    }

    @Test
    @DisplayName("默认不写产物、不自检、不过滤（选项全开会让「基线」变成另一条链路）")
    void defaultsAreAllOff() {
        VectorQueryCli.Config cfg = VectorQueryCli.parseArgs(new String[0]);
        assertThat(cfg.out).isNull();
        assertThat(cfg.selftest).isFalse();
        assertThat(cfg.docId).isNull();
        assertThat(cfg.category).isNull();
        assertThat(cfg.chunkType).isNull();
        assertThat(cfg.asOf).isNull();
        assertThat(cfg.profileName).isNull();
    }

    @Test
    @DisplayName("过滤条件与产物路径解析正确，并原样进入 VectorQueryOptions")
    void parsesFiltersAndOut() {
        VectorQueryCli.Config cfg = VectorQueryCli.parseArgs(new String[]{
                "--top-k", "5",
                "--doc-id", "06-过闸费费率表（2026版）",
                "--category", "06",
                "--chunk-type", "TABLE",
                "--as-of", "2026-06-30",
                "--metric", "ip",
                "--out", "target/probe.json"});

        assertThat(cfg.topK).isEqualTo(5);
        assertThat(cfg.docId).isEqualTo("06-过闸费费率表（2026版）");
        assertThat(cfg.category).isEqualTo("06");
        assertThat(cfg.chunkType).isEqualTo("TABLE");
        assertThat(cfg.asOf).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(cfg.metric).isEqualTo(Metric.INNER_PRODUCT);
        assertThat(cfg.out.getFileName().toString()).isEqualTo("probe.json");

        VectorQueryOptions options = cfg.options;
        assertThat(options.topK()).isEqualTo(5);
        assertThat(options.docId()).isEqualTo("06-过闸费费率表（2026版）");
        assertThat(options.docCategoryNo()).isEqualTo("06");
        assertThat(options.chunkType()).isEqualTo("TABLE");
        assertThat(options.asOf()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("URL 组装：--url 覆盖整条；host/port/db 分开写也拼得对")
    void urlAssembly() {
        assertThat(VectorQueryCli.parseArgs(new String[]{"--port", "55432", "--db", "x"}).url())
                .isEqualTo("jdbc:postgresql://127.0.0.1:55432/x");
        assertThat(VectorQueryCli.parseArgs(
                new String[]{"--url", "jdbc:postgresql://h:1/d"}).url())
                .isEqualTo("jdbc:postgresql://h:1/d");
    }

    @Test
    @DisplayName("--query-file / --out / --selftest 解析")
    void parsesPathsAndSelftest() {
        VectorQueryCli.Config cfg = VectorQueryCli.parseArgs(new String[]{
                "--query-file", "probes.txt", "--out", "out.json", "--selftest"});
        assertThat(cfg.queryFile.getFileName().toString()).isEqualTo("probes.txt");
        assertThat(cfg.out.getFileName().toString()).isEqualTo("out.json");
        assertThat(cfg.selftest).isTrue();
    }

    @Test
    @DisplayName("--help 短路（不再校验其余参数）")
    void helpShortCircuits() {
        assertThat(VectorQueryCli.parseArgs(new String[]{"--help"}).help).isTrue();
        assertThat(VectorQueryCli.parseArgs(new String[]{"-h"}).help).isTrue();
    }

    // ── 参数非法：在解析阶段就失败（不等到连库、调完 embedding）──────────

    @Test
    @DisplayName("--top-k 非正数或非整数 → 解析阶段即报错")
    void rejectsBadTopK() {
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--top-k", "0"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--top-k", "abc"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--top-k");
    }

    @Test
    @DisplayName("--chunk-type 只允许 BODY / TABLE（与库里 CHECK 同口径）")
    void rejectsBadChunkType() {
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--chunk-type", "TABLE2"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkType");
        assertThat(VectorQueryCli.parseArgs(new String[]{"--chunk-type", "BODY"}).chunkType)
                .isEqualTo("BODY");
    }

    @Test
    @DisplayName("--as-of 格式错 → 报错并点出该用哪种格式（不猜日期）")
    void rejectsBadAsOf() {
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--as-of", "2026/06/30"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--as-of")
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("--metric 只认 cosine / ip")
    void rejectsBadMetric() {
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--metric", "l2"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cosine 或 ip");
    }

    @Test
    @DisplayName("未知参数与缺取值 → 报错并提示 --help（顺带挡住「命令行传中文」这个坑）")
    void rejectsUnknownAndMissingValue() {
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--tokens"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知参数")
                .hasMessageContaining("--help");
        assertThatThrownBy(() -> VectorQueryCli.parseArgs(new String[]{"--top-k"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少取值");
    }

    // ── --query-file 的读法（中文必须能读，且不被 BOM/注释/空行污染）─────

    @Test
    @DisplayName("探针文件按行读：中文原样、空行与 # 注释跳过、TAB 之后忽略")
    void readsProbeFileLineByLine() throws IOException {
        Path file = writeProbeFile("""
                过闸费怎么算

                # 这行是注释
                船舶通过能力是多少？
                危险货物可以夜航吗？%sprobe: 词元证据（本入口忽略）
                """.formatted(String.valueOf(TAB)));

        VectorQueryCli.Config cfg = VectorQueryCli.parseArgs(
                new String[]{"--query-file", file.toString()});

        assertThat(cfg.readQueries()).containsExactly(
                "过闸费怎么算",
                "船舶通过能力是多少？",
                "危险货物可以夜航吗？");
    }

    @Test
    @DisplayName("⚠️ UTF-8 BOM 会被剥掉（否则第一条查询带着一个看不见的字符去编码）")
    void stripsUtf8BomFromFirstQuery() throws IOException {
        Path file = writeWithBom("过闸费怎么算" + LF + "船舶通过能力是多少？" + LF);

        List<String> queries = VectorQueryCli.parseArgs(
                new String[]{"--query-file", file.toString()}).readQueries();

        assertThat(queries).containsExactly("过闸费怎么算", "船舶通过能力是多少？");
    }

    @Test
    @DisplayName("全文只有注释与空行 → 空列表（由 run 报「查询为空」，而不是发一条空查询）")
    void commentsOnlyYieldsNoQueries() throws IOException {
        Path file = writeProbeFile("# 只有注释" + LF + LF + "   " + LF);
        assertThat(VectorQueryCli.parseArgs(new String[]{"--query-file", file.toString()})
                .readQueries()).isEmpty();
    }

    @Test
    @DisplayName("--query 直接给一条；同时给文件时**以文件为准**（argv 里的中文注定是乱码）")
    void queryFileWinsOverQuery() throws IOException {
        Path file = writeProbeFile("过闸费怎么算" + LF);

        VectorQueryCli.Config onlyQuery = VectorQueryCli.parseArgs(new String[]{"--query", "x"});
        assertThat(onlyQuery.readQueries()).containsExactly("x");

        VectorQueryCli.Config both = VectorQueryCli.parseArgs(
                new String[]{"--query", "乱码占位", "--query-file", file.toString()});
        assertThat(both.readQueries()).containsExactly("过闸费怎么算");
    }

    @Test
    @DisplayName("不给任何查询来源 → 空列表（run 会给出「请用 --query-file」的提示）")
    void noQuerySourceYieldsEmpty() throws IOException {
        assertThat(VectorQueryCli.parseArgs(new String[]{}).readQueries()).isEmpty();
        assertThat(VectorQueryCli.parseArgs(new String[]{"--query", "   "}).readQueries()).isEmpty();
    }

    @Test
    @DisplayName("自检探针是源码常量（中文不经 argv），且非空")
    void selftestProbeIsInternalConstant() {
        assertThat(VectorQueryCli.SELFTEST_PROBE).isNotBlank();
        assertThat(VectorQueryCli.SELFTEST_PROBE).contains("过闸费");
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    @TempDir
    Path tempDir;

    /** 按 UTF-8 写探针文件（显式写字节，避免平台的默认编码与换行介入）。 */
    private Path writeProbeFile(String content) throws IOException {
        Path file = tempDir.resolve("probes.txt");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** 写一个带 UTF-8 BOM 的文件（模拟记事本/某些编辑器保存的形态）。 */
    private Path writeWithBom(String content) throws IOException {
        Path file = tempDir.resolve("probes-bom.txt");
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        Files.write(file, bytes);
        assertThat(BOM).isEqualTo((char) 0xFEFF);   // 夹具自证：上面写的确实是 BOM
        return file;
    }
}
