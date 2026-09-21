package com.canalmind.rag.ingest;

import com.canalmind.rag.tokenize.JiebaShippingTokenizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入库 CLI 的契约测试（第 2 周第 4 项）。
 *
 * <p>命令行入口通常"没人测"，但它的默认值恰恰是最容易出错的地方：
 * 本项目的两个入库入口默认端口不同（{@code PgVectorIngestCli} 是 55432 临时集群，
 * 本入口是 5432 正式业务库），这种差异一旦被"顺手改齐"就会连到错误的库——
 * 而且**不会报错**（另一个库也存在）。故用测试把默认值钉住。
 */
class Bm25IngestCliTest {

    @Test
    @DisplayName("⚠️ 默认端口是正式业务库 5432，不是 PgVectorIngestCli 的 55432")
    void defaultPortIsProductionDatabase() {
        Assumptions.assumeTrue(System.getenv("POSTGRES_PORT") == null,
                "本机设置了 POSTGRES_PORT，跳过默认值断言（环境变量优先是设计行为）");
        assertThat(Bm25IngestCli.parseArgs(new String[0]).port).isEqualTo("5432");
    }

    @Test
    @DisplayName("默认参数：INDEX 模式 + Lucene 的 k1/b + 不 dry-run")
    void defaultsAreDocumentedValues() {
        Bm25IngestCli.Config cfg = Bm25IngestCli.parseArgs(new String[0]);
        assertThat(cfg.segmentMode).isEqualTo(JiebaShippingTokenizer.MODE_INDEX);
        assertThat(cfg.k1).isEqualTo(1.2);
        assertThat(cfg.b).isEqualTo(0.75);
        assertThat(cfg.dryRun).isFalse();
        assertThat(cfg.selftest).isFalse();
        assertThat(cfg.profileName).isNull();   // 未指定时由本地分词器推导
    }

    @Test
    @DisplayName("--url 覆盖整条 JDBC URL；host/port 分开写也拼得对")
    void urlAssembly() {
        assertThat(Bm25IngestCli.parseArgs(new String[]{"--port", "55432", "--db", "x"}).url())
                .isEqualTo("jdbc:postgresql://127.0.0.1:55432/x");
        assertThat(Bm25IngestCli.parseArgs(new String[]{"--url", "jdbc:postgresql://h:1/d"}).url())
                .isEqualTo("jdbc:postgresql://h:1/d");
    }

    @Test
    @DisplayName("--dry-run / --segment-mode / --k1 解析正确")
    void optionParsing() {
        Bm25IngestCli.Config cfg = Bm25IngestCli.parseArgs(
                new String[]{"--dry-run", "--segment-mode", "SEARCH", "--k1", "1.5", "--b", "0.5"});
        assertThat(cfg.dryRun).isTrue();
        assertThat(cfg.segmentMode).isEqualTo("SEARCH");
        assertThat(cfg.k1).isEqualTo(1.5);
        assertThat(cfg.b).isEqualTo(0.5);
    }

    @Test
    @DisplayName("未知参数 → 报错并提示用 --help（顺带挡住\"用命令行传中文\"这个坑）")
    void unknownArgumentRejected() {
        assertThatThrownBy(() -> Bm25IngestCli.parseArgs(new String[]{"--tokenize", "过闸费"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知参数")
                .hasMessageContaining("--help");
        assertThatThrownBy(() -> Bm25IngestCli.parseArgs(new String[]{"--port"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少取值");
    }

    @Test
    @DisplayName("词典自检通过（每条词典词都被完整切出）")
    void selfTestPasses() {
        assertThat(Bm25IngestCli.runSelfTest(new JiebaShippingTokenizer())).isTrue();
    }
}
