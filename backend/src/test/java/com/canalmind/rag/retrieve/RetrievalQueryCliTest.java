package com.canalmind.rag.retrieve;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 混合检索 CLI 的契约测试（第 2 周第 5 项）。
 *
 * <p>三处最容易出事、且<b>都不会报错</b>的地方：
 * <ol>
 *   <li><b>默认值与既有两个单通道 CLI 不一致</b>（默认 Top-K 不同 → 消融凭空差一截）；</li>
 *   <li><b>{@code --query-file} 的读法</b>（丢行、带 BOM、把注释当查询 →
 *       "某条查询莫名召不回"，而乱码/BOM 都不报错）；</li>
 *   <li><b>缺 {@code DEEPSEEK_API_KEY} 时静默降级</b>（C-27 明确禁止：
 *       产物会被读成"rerank 没效果"，而实际是"没跑成"）。</li>
 * </ol>
 *
 * <p>测试不连库、不调模型：只测参数解析、探针文件读法、配置校验与 key 要求的开关。
 */
class RetrievalQueryCliTest {

    private static final char LF = (char) 10;
    private static final char TAB = (char) 9;
    private static final char BOM = (char) 0xFEFF;

    @TempDir
    Path tempDir;

    // ── 默认值 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 默认四档：recall 30 / fusion 30 / rerankTopN 10 / top-k 5，且重排默认开启")
    void defaults() {
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(new String[0], Map.of());
        assertThat(cfg.recallTopK).isEqualTo(30);
        assertThat(cfg.fusionTopK).isEqualTo(30);
        assertThat(cfg.rerankTopN).isEqualTo(10);
        assertThat(cfg.contextTopN).isEqualTo(5);
        assertThat(cfg.rrfK).isEqualTo(60);
        assertThat(cfg.rerankEnabled).isTrue();
        assertThat(cfg.rerankRetry)
                .as("重试次数默认 2（= 1 次首发 + 2 次额外尝试）：它决定「降级率」这个观测量")
                .isEqualTo(2);
        assertThat(LlmReranker.DEFAULT_MAX_RETRIES).isEqualTo(2);
        assertThat(cfg.fusionCliName).isEqualTo(RrfFusion.NAME);
        assertThat(cfg.out).isNull();
        assertThat(cfg.selftest).isFalse();
        assertThat(cfg.help).isFalse();
        assertThat(cfg.profile).isNull();
        assertThat(cfg.tokenProfile).isNull();
        assertThat(cfg.queryFile).isNull();
        assertThat(cfg.query).isNull();
    }

    @Test
    @DisplayName("数据库默认值走 $POSTGRES_*（与既有 CLI 同一套约定）")
    void databaseDefaultsFromEnv() {
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(new String[0], Map.of(
                "POSTGRES_HOST", "10.0.0.5",
                "POSTGRES_PORT", "55432",
                "POSTGRES_DB", "canalmind_test",
                "POSTGRES_USER", "canalmind",
                "POSTGRES_PASSWORD", "secret"));
        assertThat(cfg.url()).isEqualTo("jdbc:postgresql://10.0.0.5:55432/canalmind_test");
        assertThat(cfg.user).isEqualTo("canalmind");
        assertThat(cfg.password).isEqualTo("secret");

        RetrievalQueryCli.Config fallback = RetrievalQueryCli.parseArgs(new String[0], Map.of());
        assertThat(fallback.url()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/canalmind");
        assertThat(fallback.user).isEqualTo("postgres");
        assertThat(fallback.password).isEmpty();
    }

    @Test
    @DisplayName("--url 优先级最高；参数解析覆盖 env 与默认值")
    void parsesAllOptions() {
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(new String[]{
                "--url", "jdbc:postgresql://127.0.0.1:55432/canalmind",
                "--recall-top-k", "50",
                "--fusion-top-k", "40",
                "--rerank-top-n", "20",
                "--top-k", "8",
                "--rrf-k", "30",
                "--fusion", "rrf",
                "--profile", "ollama:bge-m3:1024",
                "--token-profile", "jieba:shipping-dict-v1:rules-v2:INDEX",
                "--segment-mode", "INDEX",
                "--ollama-url", "http://127.0.0.1:11434",
                "--model", "bge-m3",
                "--query-file", "probes.txt",
                "--out", "target/out.json"}, Map.of());

        assertThat(cfg.url()).isEqualTo("jdbc:postgresql://127.0.0.1:55432/canalmind");
        assertThat(cfg.recallTopK).isEqualTo(50);
        assertThat(cfg.fusionTopK).isEqualTo(40);
        assertThat(cfg.rerankTopN).isEqualTo(20);
        assertThat(cfg.contextTopN).isEqualTo(8);
        assertThat(cfg.rrfK).isEqualTo(30);
        assertThat(cfg.profile).isEqualTo("ollama:bge-m3:1024");
        assertThat(cfg.tokenProfile).isEqualTo("jieba:shipping-dict-v1:rules-v2:INDEX");
        assertThat(cfg.ollamaUrl).isEqualTo("http://127.0.0.1:11434");
        assertThat(cfg.ollamaModel).isEqualTo("bge-m3");
        assertThat(cfg.queryFile.getFileName().toString()).isEqualTo("probes.txt");
        assertThat(cfg.out.getFileName().toString()).isEqualTo("out.json");
        assertThat(cfg.retrievalConfig(cfg.fusion(), new NoopReranker()).contextTopN()).isEqualTo(8);
    }

    @Test
    @DisplayName("--no-rerank：关闭重排，配置标签变成 rrf，rerankerName 变成 noop")
    void noRerankFlag() {
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(
                new String[]{"--no-rerank"}, Map.of());
        assertThat(cfg.rerankEnabled).isFalse();
        RetrievalConfig rc = cfg.retrievalConfig(cfg.fusion(), new NoopReranker());
        assertThat(rc.rerankEnabled()).isFalse();
        assertThat(rc.channelLabel()).isEqualTo("rrf");
        assertThat(rc.rerankerName()).isEqualTo(NoopReranker.NAME);
    }

    // ── --rerank-retry（B4 配套的有界重试）────────────────────────────────

    @Test
    @DisplayName("★ --rerank-retry：默认 2；0 = 不重试（合法）；负数 / 非整数 / 缺取值一律解析阶段报错")
    void rerankRetryOption() {
        assertThat(RetrievalQueryCli.parseArgs(new String[]{"--rerank-retry", "3"}, Map.of())
                .rerankRetry).isEqualTo(3);
        assertThat(RetrievalQueryCli.parseArgs(new String[]{"--rerank-retry", "0"}, Map.of())
                .rerankRetry)
                .as("0 合法：等于回到「每次只发一次请求」的旧行为，是对照实验需要的档")
                .isZero();
        assertThat(RetrievalQueryCli.parseArgs(new String[]{"--rerank-retry", " 2 "}, Map.of())
                .rerankRetry)
                .as("两侧空白要能容忍（与既有整数参数一致）")
                .isEqualTo(2);

        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--rerank-retry", "-1"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--rerank-retry")
                .hasMessageContaining(">= 0");
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--rerank-retry", "两次"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--rerank-retry")
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--rerank-retry"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少取值");
    }

    @Test
    @DisplayName("--help 里必须写明 --rerank-retry 的默认值与「只在可重试原因上重试」")
    void helpDocumentsRerankRetry() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        String usage;
        try {
            System.setOut(new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
            RetrievalQueryCli.printUsage();
        } finally {
            System.setOut(original);
        }
        usage = buffer.toString(StandardCharsets.UTF_8);

        assertThat(usage)
                .as("帮助里不写默认值，用户就只能靠试")
                .contains("--rerank-retry <n>")
                .contains("默认 2")
                .contains("rerankAttempts")
                .contains("previousDegradeReasons")
                .contains("rerankAttemptsTotal")
                .contains("重试后仍降级");
        assertThat(usage)
                .as("⛔ 必须写明哪些原因不重试：否则用户会以为鉴权失败也会被重试兜住")
                .contains("超时、空正文一律不重试");
    }

    // ── 参数非法 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ --top-k 超过 --rerank-top-n → 解析阶段就报错（连库之前失败），消息点名两者")
    void topKExceedingRerankTopNFailsAtParse() {
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--top-k", "20"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextTopN(20)")
                .hasMessageContaining("rerankTopN(10)");
    }

    @Test
    @DisplayName("条数为 0 / 非整数 → 报错")
    void invalidNumbers() {
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(new String[]{"--top-k", "0"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextTopN");
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--recall-top-k", "三十"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--recall-top-k")
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(new String[]{"--rrf-k"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少取值");
    }

    @Test
    @DisplayName("未知参数 / 未知融合策略 → 报错并说明支持范围（归一化加权本项不实现）")
    void unknownOptions() {
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(new String[]{"--topk", "5"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知参数");
        // 融合策略在解析阶段就校验：连库、算 embedding 之前失败，不浪费一次真实调用
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--fusion", "weighted"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weighted")
                .hasMessageContaining("本项不实现");
    }

    // ── --channels（第 6 项四路对照）──────────────────────────────────────

    @Test
    @DisplayName("默认通道集是 vector,bm25；合法单通道被接受且顺序保持")
    void channelSpecDefaultsAndValues() {
        assertThat(RetrievalQueryCli.parseArgs(new String[0], Map.of()).channels)
                .containsExactly("vector", "bm25");
        assertThat(RetrievalQueryCli.DEFAULT_CHANNELS).isEqualTo("vector,bm25");
        assertThat(RetrievalQueryCli.SUPPORTED_CHANNELS).containsExactly("vector", "bm25");

        assertThat(RetrievalQueryCli.parseChannelSpec("vector").channels()).containsExactly("vector");
        assertThat(RetrievalQueryCli.parseChannelSpec("bm25").channels()).containsExactly("bm25");
        assertThat(RetrievalQueryCli.parseChannelSpec("bm25,vector").channels())
                .as("顺序按用户给的原样保留（产物里的 recallChannels 会跟着变）")
                .containsExactly("bm25", "vector");
        assertThat(RetrievalQueryCli.parseChannelSpec(" vector , bm25 ").channels())
                .as("两侧空白要能容忍（逗号后带空格是常见写法）")
                .containsExactly("vector", "bm25");
        assertThat(RetrievalQueryCli.parseChannelSpec("vector").single()).isTrue();
        assertThat(RetrievalQueryCli.parseChannelSpec("vector,bm25").single()).isFalse();

        assertThat(RetrievalQueryCli.parseArgs(
                new String[]{"--channels", "bm25"}, Map.of()).channels)
                .containsExactly("bm25");
    }

    @Test
    @DisplayName("★ --channels 非法值一律报错并列出合法值：未知 / 空元素 / 重复 / 空取值")
    void channelSpecRejectsBadValues() {
        assertThatThrownBy(() -> RetrievalQueryCli.parseChannelSpec("vec"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知通道")
                .hasMessageContaining("vec")
                .hasMessageContaining("vector")
                .hasMessageContaining("bm25");

        assertThatThrownBy(() -> RetrievalQueryCli.parseChannelSpec("vector,"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空元素")
                .hasMessageContaining("vector");

        assertThatThrownBy(() -> RetrievalQueryCli.parseChannelSpec(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--channels 不能为空")
                .hasMessageContaining("vector,bm25");

        assertThatThrownBy(() -> RetrievalQueryCli.parseChannelSpec("vector,vector"))
                .as("重复通道最危险：RRF 会把同一路当两份独立证据，分数被默默放大")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("通道重复")
                .hasMessageContaining("放大");

        // 经 parseArgs 走一遍：参数校验必须在连库之前失败
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--channels", "vector,vec"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知通道");
    }

    @Test
    @DisplayName("★ 单通道时另一路**根本不被构造**（供应商不被调用），两路时各构造一次")
    void singleChannelDoesNotConstructTheOther() throws Exception {
        AtomicInteger vectorBuilt = new AtomicInteger();
        AtomicInteger bm25Built = new AtomicInteger();
        RetrievalQueryCli.ChannelSupplier vector = () -> {
            vectorBuilt.incrementAndGet();
            return new FakeChannel("vector");
        };
        RetrievalQueryCli.ChannelSupplier bm25 = () -> {
            bm25Built.incrementAndGet();
            return new FakeChannel("bm25");
        };

        List<RetrievalChannel> onlyVector = RetrievalQueryCli.requestedChannels(
                List.of("vector"), vector, bm25);
        assertThat(onlyVector).extracting(RetrievalChannel::name).containsExactly("vector");
        assertThat(vectorBuilt.get()).isEqualTo(1);
        assertThat(bm25Built.get())
                .as("BM25 侧要建分词器、读档案、核对词典指纹，其守卫会直接抛错；"
                        + "本次用不到它就不该被构造")
                .isZero();

        List<RetrievalChannel> onlyBm25 = RetrievalQueryCli.requestedChannels(
                List.of("bm25"), vector, bm25);
        assertThat(onlyBm25).extracting(RetrievalChannel::name).containsExactly("bm25");
        assertThat(vectorBuilt.get()).as("向量侧同理：不该为了跑 BM25 去连 embedding").isEqualTo(1);
        assertThat(bm25Built.get()).isEqualTo(1);

        List<RetrievalChannel> both = RetrievalQueryCli.requestedChannels(
                List.of("vector", "bm25"), vector, bm25);
        assertThat(both).extracting(RetrievalChannel::name).containsExactly("vector", "bm25");
        assertThat(vectorBuilt.get()).isEqualTo(2);
        assertThat(bm25Built.get()).isEqualTo(2);

        assertThatThrownBy(() -> RetrievalQueryCli.requestedChannels(
                List.of("vector"), () -> {
                    throw new RuntimeException("档案不匹配：不该被捕获，必须整次失败");
                }, bm25))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("档案不匹配");
    }

    @Test
    @DisplayName("★ 单通道 → 产物 channel 写通道名；两路 → 写 rrf；带重排则追加后缀")
    void channelLabelReflectsActualChannels() {
        RetrievalConfig noRerank = RetrievalConfig.defaults().withoutRerank();
        RetrievalConfig withRerank = RetrievalConfig.defaults();

        assertThat(noRerank.labelFor(List.of("vector"))).isEqualTo("vector");
        assertThat(noRerank.labelFor(List.of("bm25"))).isEqualTo("bm25");
        assertThat(noRerank.labelFor(List.of("vector", "bm25"))).isEqualTo("rrf");

        assertThat(withRerank.labelFor(List.of("vector"))).isEqualTo("vector+llm-rerank");
        assertThat(withRerank.labelFor(List.of("bm25"))).isEqualTo("bm25+llm-rerank");
        assertThat(withRerank.labelFor(List.of("vector", "bm25"))).isEqualTo("rrf+llm-rerank");

        assertThat(noRerank.labelFor(List.of()))
                .as("空通道集退回融合策略名（不该抛异常，产物仍要能写出来）")
                .isEqualTo("rrf");

        // channelLabel() 是"两路"的简写形式，两者在默认档下必须一致
        assertThat(withRerank.channelLabel()).isEqualTo(withRerank.labelFor(List.of("vector", "bm25")));
    }

    @Test
    @DisplayName("★ 单通道 + 无 rerank：产物 channel 与阶段 trace 的键都只有那一路")
    void singleChannelArtifactShape() throws Exception {
        RetrievalConfig config = new RetrievalConfig(30, 30, 10, 5, 60, false,
                RrfFusion.NAME, NoopReranker.NAME);
        RetrievalChannel only = new FakeChannel("bm25");
        RetrievalPipeline pipeline = new RetrievalPipeline(List.of(only), new RrfFusion(60),
                new NoopReranker(), config);
        RetrievalResult result = pipeline.retrieve("过闸费怎么算");

        String json = RetrievalResultJson.render(config, pipeline.channelNames(), List.of(result));
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("channel").asText()).isEqualTo("bm25");
        assertThat(root.get("recallChannels")).hasSize(1);
        assertThat(root.get("recallChannels").get(0).asText()).isEqualTo("bm25");
        assertThat(fieldNames(root.get("results").get(0).get("stageTrace")))
                .as("单通道时不该出现另一路的阶段键（它会让人以为跑了两路）")
                .containsExactly("recall:bm25", "recall", "fusion", "rerank", "context");
    }

    // ── 探针文件读法 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("★ --query-file：剥 BOM、跳过 # 注释与空行、TAB 之后的部分忽略")
    void readsQueryFile() throws IOException {
        Path file = writeProbeFile("# 这是注释" + LF
                + "过闸费怎么算" + LF
                + LF
                + "   " + LF
                + "船舶报港需要哪些材料" + TAB + "附加信息" + LF
                + "  # 前面有空格的注释" + LF);

        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(
                new String[]{"--query-file", file.toString()}, Map.of());
        assertThat(cfg.readQueries())
                .containsExactly("过闸费怎么算", "船舶报港需要哪些材料");
    }

    @Test
    @DisplayName("带 UTF-8 BOM 的查询文件：首条查询不能被 BOM 污染（否则第一条永远召不回）")
    void stripsBomFromQueryFile() throws IOException {
        Path file = writeWithBom("过闸费怎么算" + LF + "船舶报港" + LF);
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(
                new String[]{"--query-file", file.toString()}, Map.of());
        assertThat(cfg.readQueries()).containsExactly("过闸费怎么算", "船舶报港");
        assertThat(cfg.readQueries().get(0).charAt(0)).isNotEqualTo(BOM);
    }

    @Test
    @DisplayName("--query 与 --query-file 同时给出时以文件为准（命令行里的中文注定是乱码）")
    void fileWinsOverInlineQuery() throws IOException {
        Path file = writeProbeFile("文件里的查询" + LF);
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(
                new String[]{"--query", "argv里的乱码", "--query-file", file.toString()}, Map.of());
        assertThat(cfg.readQueries()).containsExactly("文件里的查询");
    }

    @Test
    @DisplayName("只有 --query / 两者都没有 → 行为明确（空则交给上层报错）")
    void inlineQueryOnly() throws IOException {
        assertThat(RetrievalQueryCli.parseArgs(new String[]{"--query", "x"}, Map.of()).readQueries())
                .containsExactly("x");
        assertThat(RetrievalQueryCli.parseArgs(new String[0], Map.of()).readQueries()).isEmpty();
        assertThat(RetrievalQueryCli.parseArgs(new String[]{"--query", "  "}, Map.of())
                .readQueries()).isEmpty();
    }

    // ── key 要求（C-27 硬约束）────────────────────────────────────────────

    @Test
    @DisplayName("★ --no-rerank 时不要求 DEEPSEEK_API_KEY；开启重排但缺 key 时**报错**（不静默降级）")
    void apiKeyRequirement() {
        RetrievalQueryCli.requireRerankKey(false, Map.of());   // 不抛 = 通过

        assertThatThrownBy(() -> RetrievalQueryCli.requireRerankKey(true, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LlmRerankerFactory.API_KEY_ENV)
                .hasMessageContaining("不静默降级")
                .hasMessageContaining("--no-rerank");

        // 空白 key 等同缺失
        assertThatThrownBy(() -> RetrievalQueryCli.requireRerankKey(true,
                Map.of(LlmRerankerFactory.API_KEY_ENV, "   ")))
                .isInstanceOf(IllegalStateException.class);

        // 有 key 时放行
        RetrievalQueryCli.requireRerankKey(true, Map.of(LlmRerankerFactory.API_KEY_ENV, "sk-stub"));
    }

    @Test
    @DisplayName("DeepSeek 侧配置读 .env（进程环境变量优先），而 POSTGRES_* 不读 .env")
    void deepSeekEnvComesFromDotEnv() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, ("# 注释" + LF
                + "DEEPSEEK_API_KEY=from-dotenv" + LF
                + "DEEPSEEK_CHAT_MODEL=" + "from-dotenv-model" + LF
                + "POSTGRES_PORT=9999" + LF).getBytes(StandardCharsets.UTF_8));

        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(new String[0],
                Map.of("DEEPSEEK_API_KEY", "from-process-env"));
        cfg.dotEnvFiles = List.of(envFile);

        assertThat(cfg.deepSeekEnv().get("DEEPSEEK_API_KEY"))
                .as("进程环境变量优先（与 Spring 的外部化配置顺序一致）")
                .isEqualTo("from-process-env");
        assertThat(cfg.deepSeekEnv().get("DEEPSEEK_CHAT_MODEL")).isEqualTo("from-dotenv-model");
        assertThat(cfg.rerankerModelHint()).isEqualTo("from-dotenv-model");
        assertThat(cfg.url())
                .as(".env 里的 POSTGRES_PORT 不得影响连接串：否则与两个既有 CLI 连的不是同一个库")
                .isEqualTo("jdbc:postgresql://127.0.0.1:5432/canalmind");
    }

    @Test
    @DisplayName(".env 解析：跳过注释与无等号行、去空白、容忍成对引号、剥 BOM")
    void parsesDotEnvFile() throws IOException {
        Path envFile = tempDir.resolve("custom.env");
        Files.write(envFile, ("DEEPSEEK_API_KEY=\"quoted-key\"" + LF
                + "DEEPSEEK_BASE_URL='https://example.test/v1'" + LF
                + "  稀疏键  =  值带空格  " + LF
                + "没有等号的行" + LF
                + "# DEEPSEEK_CHAT_MODEL=被注释掉" + LF).getBytes(StandardCharsets.UTF_8));

        Map<String, String> parsed = LlmRerankerFactory.readDotEnv(envFile);
        assertThat(parsed)
                .containsEntry("DEEPSEEK_API_KEY", "quoted-key")
                .containsEntry("DEEPSEEK_BASE_URL", "https://example.test/v1")
                .containsEntry("稀疏键", "值带空格")
                .doesNotContainKey("DEEPSEEK_CHAT_MODEL")
                .hasSize(3);
    }

    @Test
    @DisplayName("--selftest 不连库、不调模型也能跑通（返回 0，说明编排与产物契约自洽）")
    void selftestPasses() {
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(new String[]{"--selftest"}, Map.of());
        assertThat(cfg.selftest).isTrue();
        assertThat(RetrievalQueryCli.runSelfTest(cfg)).isZero();
    }

    @Test
    @DisplayName("--help 短路：不校验后续参数（帮助永远能看）")
    void helpShortCircuits() {
        RetrievalQueryCli.Config cfg = RetrievalQueryCli.parseArgs(new String[]{"--help"}, Map.of());
        assertThat(cfg.help).isTrue();
    }

    // ── 「重排尝试」行：不许再把「没重试」说成「一次成功」（2026-09-21 修）────────

    @Test
    @DisplayName("★ 降级时一个字都不许提「成功」；attempts=1 必须交代「为什么没重试」")
    void attemptLineNeverClaimsSuccessWhenDegraded() {
        List<RetrievalCandidate> two = List.of(RetrievalCandidate.of("a"), RetrievalCandidate.of("b"));

        // ① 真的产出了可用重排结果 —— 只有这一种才允许说「成功」
        assertThat(RetrievalQueryCli.attemptLine(
                RerankOutcome.ok(two, 5L, "m", 1, List.of()), 2))
                .isEqualTo("1 次（一次成功）");

        // ② 空正文：只试了一次，但那是「这一档不重试」，不是成功
        RerankOutcome blank = RerankOutcome.degraded(two, "重排模型返回了空正文（…）", 5000L, "m",
                1, List.of(), RerankOutcome.FailureKind.BLANK_COMPLETION);
        assertThat(RetrievalQueryCli.attemptLine(blank, 2))
                .as("★ 本次要修的自相矛盾：次数、是否成功、为什么没重试，三件事必须各说各的")
                .isEqualTo("1 次（未重试：空正文——空正文是确定性失败：同一提示词再来一次只会再花一次钱）");

        // ③ 同一个「1 次」，若因为 --rerank-retry 0，措辞必须不同：
        //    前者是失败性质决定的，后者是配置决定的——混成一句会让人去改错旋钮
        assertThat(RetrievalQueryCli.attemptLine(blank, 0))
                .isEqualTo("1 次（未重试：--rerank-retry 0，本次已关闭重试；失败原因见下一行）");

        // ④ 调模型之前就被拒的那几档：0 次，且写明为什么（未调用模型 ≠ 失败在模型上）
        RerankOutcome tooMany = RerankOutcome.degraded(two, "候选数 40 超过上限 3（…）", 0L, "m",
                RerankOutcome.FailureKind.TOO_MANY_CANDIDATES);
        assertThat(RetrievalQueryCli.attemptLine(tooMany, 2))
                .isEqualTo("0 次（未调用模型：候选超上限——候选数在调模型之前就已超上限，重试结果相同）");

        // ⑤ 重试耗尽仍降级
        RerankOutcome exhausted = RerankOutcome.degraded(two, "输出被 token 上限截断（…）", 40000L,
                "m", 3, List.of("第 1 次：截断", "第 2 次：截断"),
                RerankOutcome.FailureKind.UNPARSABLE);
        assertThat(RetrievalQueryCli.attemptLine(exhausted, 2))
                .isEqualTo("3 次（均失败，已保留融合原序：重试 2 次仍降级，"
                        + "最后一次的失败分类 UNPARSABLE）");

        // ⑥ 一次都没调模型且没降级（关掉重排 / 空输入）
        assertThat(RetrievalQueryCli.attemptLine(
                RerankOutcome.ok(two, 0L, RerankOutcome.NO_MODEL), 2))
                .isEqualTo("0 次（未调用模型：空输入，或本档关闭了重排）");

        // ⑦ 重试后才成功：可以说成功，但必须同时说明「不是一次成功」
        String retriedOk = RetrievalQueryCli.attemptLine(
                RerankOutcome.ok(two, 5L, "m", 3, List.of("第 1 次坏", "第 2 次坏")), 2);
        assertThat(retriedOk)
                .contains("重试过")
                .contains("本次成功")
                .contains("这不是「一次成功」")
                .contains("被重试兜住了");

        // ⑧ 核心不变量：降级的三条一律不提「成功」
        for (RerankOutcome degraded : List.of(blank, tooMany, exhausted)) {
            assertThat(RetrievalQueryCli.attemptLine(degraded, 2))
                    .as("⛔ 降级时出现「成功」二字就是本次要消灭的那种自相矛盾")
                    .doesNotContain("成功");
        }
    }

    @Test
    @DisplayName("★ 重排额度：默认 8192、环境变量可覆盖、非法取值在配置读取处就报错")
    void rerankMaxTokensComesFromEnv() {
        RetrievalQueryCli.Config defaults = RetrievalQueryCli.parseArgs(new String[0], Map.of());
        defaults.dotEnvFiles = List.of();   // 隔离：不读工作目录下可能存在的 .env
        assertThat(defaults.rerankMaxTokens()).isEqualTo(8192);

        RetrievalQueryCli.Config overridden = RetrievalQueryCli.parseArgs(new String[0],
                Map.of(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV, "16384"));
        overridden.dotEnvFiles = List.of();
        assertThat(overridden.rerankMaxTokens()).isEqualTo(16384);

        RetrievalQueryCli.Config bad = RetrievalQueryCli.parseArgs(new String[0],
                Map.of(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV, "16k"));
        bad.dotEnvFiles = List.of();
        assertThatThrownBy(bad::rerankMaxTokens)
                .as("非法额度不许静默退回默认值：那会让人以为「我已经调到 16k 了」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV);
    }

    @Test
    @DisplayName("★ 重排超时：默认 90 s、环境变量可覆盖、非法取值在配置读取处就报错")
    void rerankTimeoutComesFromEnv() {
        RetrievalQueryCli.Config defaults = RetrievalQueryCli.parseArgs(new String[0], Map.of());
        defaults.dotEnvFiles = List.of();   // 隔离：不读工作目录下可能存在的 .env
        assertThat(defaults.rerankTimeoutSeconds()).isEqualTo(90);

        RetrievalQueryCli.Config overridden = RetrievalQueryCli.parseArgs(new String[0],
                Map.of(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV, "120"));
        overridden.dotEnvFiles = List.of();
        assertThat(overridden.rerankTimeoutSeconds())
                .as("banner 打印的就是这个值：设了必须生效，否则「一眼确认」是假的")
                .isEqualTo(120);

        RetrievalQueryCli.Config bad = RetrievalQueryCli.parseArgs(new String[0],
                Map.of(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV, "-5"));
        bad.dotEnvFiles = List.of();
        assertThatThrownBy(bad::rerankTimeoutSeconds)
                .as("非法超时不许静默退回默认值：那会让人以为「我已经调到 120 了」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV)
                .hasMessageContaining("-5");
    }

    @Test
    @DisplayName("--help 里必须写明重排超时这个环境变量（配置项不可发现＝不可用）")
    void helpDocumentsRerankTimeout() {
        String usage = capturedUsage();
        assertThat(usage)
                .contains(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV)
                .contains("单次重排调用的等待上限")
                .contains("29.875 s")
                .contains("一律报错");
        assertThat(usage)
                .as("⛔ 必须写明它只动重排这一条链路：否则有人会顺手改主对话的等待上限")
                .contains("只影响重排");
        // 既有的额度段不许被本次改动挤掉
        assertThat(usage).contains(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV);
    }

    @Test
    @DisplayName("--help 里必须写明重排额度这个环境变量（配置项不可发现＝不可用）")
    void helpDocumentsRerankMaxTokens() {
        String usage = capturedUsage();
        assertThat(usage)
                .contains(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV)
                .contains("8192")
                .contains("推理 token 与正文")
                .contains("一律报错");
        assertThat(usage)
                .as("⛔ 必须写明它只动重排这一条链路：否则有人会顺手改主对话的 max-tokens")
                .contains("不动主对话");
        // 既有的重试段不许被本次改动挤掉
        assertThat(usage).contains("超时、空正文一律不重试").contains("attempts=1 不等于「一次成功」");
    }

    /** 抓下 {@code --help} 的全文（与既有 {@code helpDocumentsRerankRetry} 同做法）。 */
    private static String capturedUsage() {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        try {
            System.setOut(new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
            RetrievalQueryCli.printUsage();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 内存通道：返回 40 条与通道名绑定的候选（够单通道档填满 contextTopN）。 */
    private record FakeChannel(String name) implements RetrievalChannel {

        @Override
        public List<RetrievalCandidate> search(String query, int topK) {
            int n = Math.min(40, topK);
            List<RetrievalCandidate> out = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                out.add(RetrievalCandidate.fromHit(name, 40.0 - i, i, name + "-chunk-" + i,
                        "doc-" + i, "文档 " + i, "BODY", "第" + i + "条", "标题 " + i, "v1"));
            }
            return List.copyOf(out);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    private Path writeProbeFile(String content) throws IOException {
        Path file = tempDir.resolve("probes.txt");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

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

    // ── 复核补充：大小写不敏感 + 参数错误形态统一（2026-09-21）────────────────

    @Test
    @DisplayName("★ --channels 大小写不敏感、容忍空格：VECTOR / Bm25 与 vector / bm25 等价")
    void channelSpecIsCaseInsensitiveAndToleratesSpaces() {
        // 为什么值得测：`--channels VECTOR` 被判成"未知通道"时，用户会以为通道名有别名，
        // 而真相只是大小写——错误信息指向了错误的地方。
        assertThat(RetrievalQueryCli.parseChannelSpec("VECTOR").channels()).containsExactly("vector");
        assertThat(RetrievalQueryCli.parseChannelSpec("Bm25").channels()).containsExactly("bm25");
        assertThat(RetrievalQueryCli.parseChannelSpec("VECTOR, Bm25").channels())
                .as("归一成小写后写进产物，recallChannels 才不会被大小写污染")
                .containsExactly("vector", "bm25");
        assertThat(RetrievalQueryCli.parseChannelSpec("  BM25  ").channels()).containsExactly("bm25");
        assertThat(RetrievalQueryCli.parseArgs(new String[]{"--channels", "Vector,Bm25"}, Map.of()).channels)
                .containsExactly("vector", "bm25");
        // 顺序仍按用户给的原样（大小写归一不等于排序）
        assertThat(RetrievalQueryCli.parseChannelSpec("BM25,vEcToR").channels())
                .containsExactly("bm25", "vector");
    }

    @Test
    @DisplayName("★ 所有参数错误同一种形态：UsageError（不是裸 IllegalArgumentException）")
    void everyParameterErrorHasTheSameShape() {
        // 复核指出的问题：参数错误曾以 `混合检索失败：IllegalArgumentException: ...` 的面目出现，
        // 让人以为程序崩了。现在统一成 UsageError ⇒ mainCode 收敛为「一行中文 + 退出码 2」。
        // 这条测试逐个钉住每一类参数错误，避免以后新增校验时又裸抛 IAE。
        List<java.util.function.Supplier<?>> usageErrors = List.of(
                () -> RetrievalQueryCli.parseChannelSpec("vec"),          // 未知通道
                () -> RetrievalQueryCli.parseChannelSpec("vector,"),      // 空元素
                () -> RetrievalQueryCli.parseChannelSpec(""),             // 空取值
                () -> RetrievalQueryCli.parseChannelSpec("vector,vector"), // 重复通道
                () -> RetrievalQueryCli.parseArgs(new String[]{"--nope"}, Map.of()),   // 未知参数
                () -> RetrievalQueryCli.parseArgs(new String[]{"--channels"}, Map.of()), // 缺取值
                () -> RetrievalQueryCli.parseArgs(new String[]{"--top-k", "abc"}, Map.of()), // 非整数
                () -> RetrievalQueryCli.parseArgs(new String[]{"--rerank-retry", "-1"}, Map.of()), // 负数
                () -> RetrievalQueryCli.parseArgs(new String[]{"--top-k", "20"}, Map.of())); // 配置组合非法
        for (java.util.function.Supplier<?> bad : usageErrors) {
            assertThatThrownBy(bad::get)
                    .as("参数错误必须是 UsageError（它继承 IllegalArgumentException，"
                            + "故既有断言不受影响，而 mainCode 能按更具体的类型先捕获）")
                    .isInstanceOf(RetrievalQueryCli.UsageError.class)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("IllegalArgumentException");
        }
    }

    @Test
    @DisplayName("★ 参数错误的退出码是 2（运行期失败才是 1），且只打一行提示")
    void usageErrorsExitWithCodeTwo() {
        // mainCode 不调用 System.exit，故能直接断言退出码；参数错误在连库之前就返回，
        // 所以这条测试不会碰数据库、也不会调模型。
        assertThat(RetrievalQueryCli.mainCode(new String[]{"--channels", "vec"}))
                .as("非法通道：退出码 2（用法问题），不是 1（运行期失败）")
                .isEqualTo(2);
        assertThat(RetrievalQueryCli.mainCode(new String[]{"--nope"}))
                .as("未知参数同样是 2")
                .isEqualTo(2);
        assertThat(RetrievalQueryCli.mainCode(new String[]{"--channels", "vector,vector"}))
                .as("重复通道同样是 2")
                .isEqualTo(2);
    }

    // ── 复核补充（2026-09-21）：--dump-rerank 诊断开关 ──────────────────────

    @Test
    @DisplayName("★ --dump-rerank：命令行优先于环境变量；缺取值/非法路径都归参数错误（退出码 2）")
    void dumpRerankSwitchIsParsedWithEnvFallback() {
        // 这个开关的用途是"真实降级时留证"。它的解析规则必须与其它参数同口径：
        // 环境变量兜底、命令行优先、错误一律是 UsageError（一行中文 + 退出码 2）。
        assertThat(RetrievalQueryCli.parseArgs(
                new String[]{"--dump-rerank", "target/x/dump.txt"}, Map.of()).dumpRerank)
                .isEqualTo(Path.of("target/x/dump.txt"));
        assertThat(RetrievalQueryCli.parseArgs(
                new String[]{}, Map.of(RerankDump.ENV_NAME, "a/b.txt")).dumpRerank)
                .as("环境变量兜底（").isEqualTo(Path.of("a/b.txt"));
        assertThat(RetrievalQueryCli.parseArgs(
                new String[]{"--dump-rerank", "cli.txt"},
                Map.of(RerankDump.ENV_NAME, "env.txt")).dumpRerank)
                .as("命令行优先于环境变量").isEqualTo(Path.of("cli.txt"));
        assertThat(RetrievalQueryCli.parseArgs(new String[]{}, Map.of()).dumpRerank)
                .as("不给开关就不落盘：默认档行为不许被诊断能力改变").isNull();

        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--dump-rerank"}, Map.of()))
                .as("缺取值").isInstanceOf(RetrievalQueryCli.UsageError.class);
        assertThatThrownBy(() -> RetrievalQueryCli.parseArgs(
                new String[]{"--dump-rerank", "bad\u0000path"}, Map.of()))
                .as("路径非法也要归参数错误（路径错的写法不许冒成运行期异常）")
                .isInstanceOf(RetrievalQueryCli.UsageError.class)
                .hasMessageContaining("路径非法");
        assertThat(RetrievalQueryCli.mainCode(new String[]{"--dump-rerank"}))
                .as("退出码 2：参数错误，不连库、不花钱").isEqualTo(2);
    }
}
