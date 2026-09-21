package com.canalmind.rag.vector;

import com.canalmind.rag.embed.EmbeddingClient;
import com.canalmind.rag.embed.EmbeddingModelGuard.EmbeddingModelMismatchException;
import com.canalmind.rag.embed.EmbeddingModelInfo;
import com.canalmind.rag.embed.VectorCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量检索层的契约测试（第 2 周第 5 项）。
 *
 * <p><b>覆盖边界（诚实说明）</b>：本项目无 Docker、无测试库（C-12），
 * 故这里测的是"不碰真实数据库与 Ollama 就能测"的部分——
 * SQL 形状（含 as_of 左闭右开与 {@code embedding IS NOT NULL}）、参数绑定顺序与次数、
 * 结果映射、top-k 边界、身份守卫的拒绝路径。
 *
 * <p><b>不由本文件保证的</b>：① 真实 pgvector 的距离计算与 HNSW 计划是否命中索引——
 * 由交付说明里的 {@code EXPLAIN (ANALYZE)} 实测记录与 CLI 端到端跑批守住；
 * ② 档案表的真实内容——由库里唯一那条 {@code ollama:bge-m3:1024} 与
 * {@code EmbeddingModelGuardTest} 守住。分工写在这里，免得后来者以为
 * "跑过单测就等于向量检索是对的"（与 {@code Bm25ContractTest} 同样的声明）。
 */
class VectorSearcherTest {

    private static final String PROFILE = "ollama:bge-m3:1024";

    // ── SQL 形状：与 BM25 通道口径对齐 + 只取有向量的行 ────────────────────

    @Test
    @DisplayName("余弦口径 SQL：过滤与排序同体，ORDER BY 算子升序 + LIMIT 走占位符")
    void cosineSqlShape() {
        assertThat(VectorSearcher.SEARCH_SQL_COSINE)
                .contains("1 - (c.embedding <=> ?::vector) AS score")
                .contains("ORDER BY c.embedding <=> ?::vector ASC, c.chunk_id")
                .contains("LIMIT ?");
    }

    @Test
    @DisplayName("⚠️ 只检索 embedding 非空的行（V3 允许「有 chunk 无向量」的行存在）")
    void onlyRowsWithEmbedding() {
        assertThat(VectorSearcher.SEARCH_SQL_COSINE).contains("c.embedding IS NOT NULL");
    }

    @Test
    @DisplayName("as_of 是左闭右开：effective_from <= as_of 且 effective_to > as_of（不是 >=）")
    void asOfIsLeftClosedRightOpen() {
        assertThat(VectorSearcher.SEARCH_SQL_COSINE)
                .contains("c.effective_from IS NULL OR c.effective_from <= ?::date")
                .contains("c.effective_to IS NULL OR c.effective_to > ?::date");
        // 反向断言：写成 >= 会把「到 2026-12-31 失效」的版本在 2026-12-31 当天也算成生效，
        // 而它不会报错——只会让"版本冲突"类题目的答案多出一条过期依据。
        assertThat(VectorSearcher.SEARCH_SQL_COSINE)
                .doesNotContain("c.effective_to >= ?::date")
                .doesNotContain("c.effective_from >= ?::date");
    }

    @Test
    @DisplayName("三条相等过滤与 rag_bm25_search 完全同形（不过滤就放行，不做 coalesce 短路）")
    void equalityFiltersMirrorBm25() {
        assertThat(VectorSearcher.SEARCH_SQL_COSINE)
                .contains("(?::text IS NULL OR c.doc_id = ?::text)")
                .contains("(?::text IS NULL OR c.doc_category_no = ?::text)")
                .contains("(?::text IS NULL OR c.chunk_type = ?::text)");
        // coalesce 写法在可空列（doc_category_no）上会把 NULL 行静默丢掉，故显式断言不用它
        assertThat(VectorSearcher.SEARCH_SQL_COSINE).doesNotContain("coalesce");
    }

    @Test
    @DisplayName("占位符个数与参数绑定顺序一致（改 SQL 忘了改绑定会在这里红）")
    void placeholderCountMatchesBinding() {
        long placeholders = VectorSearcher.SEARCH_SQL_COSINE.chars().filter(c -> c == '?').count();
        assertThat(placeholders).isEqualTo(VectorSearcher.PLACEHOLDER_COUNT);
    }

    @Test
    @DisplayName("内积口径 SQL：与 V3 的 vector_ip_ops 索引算子同类，score 取负号")
    void innerProductSqlShape() {
        assertThat(VectorSearcher.SEARCH_SQL_INNER_PRODUCT)
                .contains("-(c.embedding <#> ?::vector) AS score")
                .contains("ORDER BY c.embedding <#> ?::vector ASC, c.chunk_id")
                .contains("c.embedding IS NOT NULL");
    }

    @Test
    @DisplayName("两种 SQL 只有算子与打分表达式不同（同一模板，不会各自漂移）")
    void twoSqlVariantsDifferOnlyInMetric() {
        assertThat(VectorSearcher.SEARCH_SQL_COSINE.replace("<=>", "OP").replace("1 - (", "S("))
                .isEqualTo(VectorSearcher.SEARCH_SQL_INNER_PRODUCT.replace("<#>", "OP").replace("-(", "S("));
    }

    @Test
    @DisplayName("metric → SQL 走的是枚举穷举（测试断言的那条就是真正执行的那条）")
    void sqlFollowsMetric() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        EmbeddingClient client = new StubEmbeddingClient(bgeM3(), unitVector(1024));
        assertThat(new VectorSearcher(jdbc, client, PROFILE).sql())
                .isEqualTo(VectorSearcher.SEARCH_SQL_COSINE);
        assertThat(new VectorSearcher(jdbc, client, PROFILE, VectorSearcher.Metric.INNER_PRODUCT).sql())
                .isEqualTo(VectorSearcher.SEARCH_SQL_INNER_PRODUCT);
    }

    @Test
    @DisplayName("度量只认 cosine / ip，且默认是精确扫描的 cosine")
    void metricParsing() {
        assertThat(VectorSearcher.Metric.fromCli("cosine")).isEqualTo(VectorSearcher.Metric.COSINE);
        assertThat(VectorSearcher.Metric.fromCli("ip")).isEqualTo(VectorSearcher.Metric.INNER_PRODUCT);
        assertThat(VectorSearcher.Metric.COSINE.usesHnswIndex()).isFalse();
        assertThat(VectorSearcher.Metric.INNER_PRODUCT.usesHnswIndex()).isTrue();
        assertThatThrownBy(() -> VectorSearcher.Metric.fromCli("COSINE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cosine 或 ip");
    }

    // ── top-k 边界 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("topK < 1 → 拒绝（与 Bm25QueryOptions 同口径）")
    void rejectsBadTopK() {
        assertThatThrownBy(() -> VectorQueryOptions.topK(0)).hasMessageContaining("topK");
        assertThatThrownBy(() -> VectorQueryOptions.topK(-3)).hasMessageContaining("topK");
        assertThat(VectorQueryOptions.topK(1).topK()).isEqualTo(1);
    }

    @Test
    @DisplayName("chunkType 只允许 BODY/TABLE（与 V3 的 CHECK 一致）")
    void rejectsBadChunkType() {
        assertThatThrownBy(() -> new VectorQueryOptions(5, null, null, "TABLE2", null))
                .hasMessageContaining("chunkType");
        assertThat(VectorQueryOptions.topK(5).withChunkType("TABLE").chunkType()).isEqualTo("TABLE");
    }

    @Test
    @DisplayName("过滤器可叠加，且不改变 topK（改一处只影响一处）")
    void optionsAreImmutable() {
        VectorQueryOptions base = VectorQueryOptions.topK(10).withAsOf(LocalDate.of(2026, 6, 30));
        VectorQueryOptions narrowed = base.withDocId("06-过闸费费率表（2026版）");

        assertThat(base.docId()).isNull();
        assertThat(base.asOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(narrowed.docId()).isEqualTo("06-过闸费费率表（2026版）");
        assertThat(narrowed.asOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(narrowed.topK()).isEqualTo(10);
    }

    // ── 空查询短路：不调 embedding、不查库 ────────────────────────────────

    @Test
    @DisplayName("空查询 → 直接返回空，既不调 embedding 也不查库（与 BM25 的空词元同口径）")
    void blankQueryShortCircuits() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        StubEmbeddingClient client = new StubEmbeddingClient(bgeM3(), unitVector(1024));
        VectorSearcher searcher = new VectorSearcher(jdbc, client, PROFILE);

        assertThat(searcher.search(null, VectorQueryOptions.topK(5))).isEmpty();
        assertThat(searcher.search("   ", VectorQueryOptions.topK(5))).isEmpty();
        assertThat(client.calls).isZero();
        Mockito.verifyNoInteractions(jdbc);
    }

    // ── 真实执行路径：参数绑定与结果映射（用桩 Connection/PS/RS）──────────

    @Test
    @DisplayName("参数绑定：查询向量绑两次（打分 + 排序），三条过滤各绑两次，as_of 绑三次")
    void bindsVectorTwiceAndFiltersInOrder() throws Exception {
        float[] vector = unitVector(1024);
        String literal = VectorCodec.toPgVectorLiteral(vector);
        LocalDate asOf = LocalDate.of(2026, 6, 30);

        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        StubEmbeddingClient client = new StubEmbeddingClient(bgeM3(), vector);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc, client);
        Connection con = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(con.prepareStatement(ArgumentMatchers.anyString())).thenReturn(ps);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(false);
        stubExecute(jdbc, con);

        VectorQueryOptions options = new VectorQueryOptions(30, "d1", "06", "BODY", asOf);
        assertThat(searcher.search("过闸费怎么算", options)).isEmpty();

        // 执行的 SQL 必须就是上面被断言过形状的那条常量
        Mockito.verify(con).prepareStatement(VectorSearcher.SEARCH_SQL_COSINE);
        Mockito.verify(ps).setString(1, literal);
        Mockito.verify(ps).setString(2, "d1");
        Mockito.verify(ps).setString(3, "d1");
        Mockito.verify(ps).setString(4, "06");
        Mockito.verify(ps).setString(5, "06");
        Mockito.verify(ps).setString(6, "BODY");
        Mockito.verify(ps).setString(7, "BODY");
        Mockito.verify(ps).setObject(8, asOf, Types.DATE);
        Mockito.verify(ps).setObject(9, asOf, Types.DATE);
        Mockito.verify(ps).setObject(10, asOf, Types.DATE);
        Mockito.verify(ps).setString(11, literal);
        Mockito.verify(ps).setInt(12, 30);
        // 一次查询只编码一次：两次分别编码会得到"看起来一样"的两个向量，
        // 而打分与排序用的必须是同一个，否则分数与名次会互相矛盾
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("不过滤时三条文本过滤绑 NULL、as_of 绑 DATE 类型的 NULL（不是无类型 NULL）")
    void nullFiltersBindTypedNulls() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc, new StubEmbeddingClient(bgeM3(), unitVector(1024)));
        Connection con = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(con.prepareStatement(ArgumentMatchers.anyString())).thenReturn(ps);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(false);
        stubExecute(jdbc, con);

        searcher.search("过闸费怎么算", VectorQueryOptions.topK(3));

        Mockito.verify(ps, Mockito.times(6)).setString(Mockito.anyInt(), ArgumentMatchers.isNull());
        Mockito.verify(ps, Mockito.times(3)).setNull(Mockito.anyInt(), ArgumentMatchers.eq(Types.DATE));
        Mockito.verify(ps).setInt(12, 3);
    }

    @Test
    @DisplayName("结果映射：溯源字段与 score 逐列落到 VectorHit")
    void mapsRowsToHits() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc, new StubEmbeddingClient(bgeM3(), unitVector(1024)));
        Connection con = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(con.prepareStatement(ArgumentMatchers.anyString())).thenReturn(ps);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(true, true, false);
        Mockito.when(rs.getString("chunk_id")).thenReturn("3c58adb940ca3987", "aaaaaaaaaaaaaaaa");
        Mockito.when(rs.getString("doc_id")).thenReturn("06-过闸费费率表（2026版）", "05-计费总则");
        Mockito.when(rs.getString("doc_title")).thenReturn("过闸费费率表（2026版）", "计费总则");
        Mockito.when(rs.getString("chunk_type")).thenReturn("BODY", "TABLE");
        Mockito.when(rs.getString("article_no")).thenReturn("第一条", "第三条");
        Mockito.when(rs.getString("article_title")).thenReturn("目的与依据", "计费单位");
        Mockito.when(rs.getString("version")).thenReturn("2026", "V1.0");
        Mockito.when(rs.getDouble("score")).thenReturn(0.812345, 0.5);
        stubExecute(jdbc, con);

        List<VectorHit> hits = searcher.search("过闸费怎么算", VectorQueryOptions.topK(2));

        assertThat(hits).hasSize(2);
        VectorHit first = hits.get(0);
        assertThat(first.chunkId()).isEqualTo("3c58adb940ca3987");
        assertThat(first.docId()).isEqualTo("06-过闸费费率表（2026版）");
        assertThat(first.chunkType()).isEqualTo("BODY");
        assertThat(first.articleNo()).isEqualTo("第一条");
        assertThat(first.version()).isEqualTo("2026");
        assertThat(first.score()).isEqualTo(0.812345);
        assertThat(first.summary()).contains("0.812345").contains("3c58adb940ca3987").contains("第一条");
        assertThat(hits.get(1).chunkType()).isEqualTo("TABLE");
    }

    // ── 身份守卫：不一致就拒绝，不降级 ────────────────────────────────────

    @Test
    @DisplayName("⚠️ 库里档案模型与当前查询模型不一致 → 拒绝查询（不静默降级）")
    void rejectsWhenModelMismatches() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = Mockito.spy(new VectorSearcher(
                jdbc, new StubEmbeddingClient(bgeM3(), unitVector(1024)), PROFILE));
        Mockito.doReturn(profile("ollama", "bge-m3-v2", 1024, "inner_product"))
                .when(searcher).profileFromDb();

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining(PROFILE)
                .hasMessageContaining("拒绝查询")
                .hasMessageContaining("全量重建索引");
        // 拒绝发生在查库之前：模型不一致时不该再跑一条注定算错的 SQL
        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("⚠️ 库里的维度与当前不同 → 拒绝（维度不同实际上放不进同一索引）")
    void rejectsWhenDimensionMismatches() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = Mockito.spy(new VectorSearcher(
                jdbc, new StubEmbeddingClient(bgeM3(), unitVector(1024)), PROFILE));
        Mockito.doReturn(profile("ollama", "bge-m3", 768, "inner_product"))
                .when(searcher).profileFromDb();

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining("维度不同");
    }

    @Test
    @DisplayName("⚠️ 档案不存在（rag_index_profile 无记录）→ 拒绝查询并说明下一步")
    void rejectsWhenProfileMissing() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = Mockito.spy(new VectorSearcher(
                jdbc, new StubEmbeddingClient(bgeM3(), unitVector(1024)), PROFILE));
        Mockito.doReturn(null).when(searcher).profileFromDb();

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining(PROFILE)
                .hasMessageContaining("索引侧模型信息缺失");
    }

    @Test
    @DisplayName("内积口径额外要求档案声明的度量也是 inner_product（否则排序会静默变错）")
    void rejectsInnerProductWhenProfileMetricDiffers() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = Mockito.spy(new VectorSearcher(
                jdbc, new StubEmbeddingClient(bgeM3(), unitVector(1024)), PROFILE,
                VectorSearcher.Metric.INNER_PRODUCT));
        Mockito.doReturn(profile("ollama", "bge-m3", 1024, "cosine")).when(searcher).profileFromDb();

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining("distance_metric")
                .hasMessageContaining("--metric cosine");
    }

    @Test
    @DisplayName("余弦口径不额外要求档案度量（归一化向量上排名等价，只说清就好）")
    void cosineDoesNotRequireProfileMetric() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unitVector(1024)));
        Mockito.when(jdbc.execute(Mockito.<ConnectionCallback<List<VectorHit>>>any()))
                .thenReturn(List.of());

        assertThat(searcher.search("过闸费怎么算", VectorQueryOptions.topK(5))).isEmpty();
    }

    @Test
    @DisplayName("档案校验只做一次（同一实例内缓存）——长驻进程需另行处理，见类注释")
    void profileCheckedOncePerInstance() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unitVector(1024)));
        Mockito.when(jdbc.execute(Mockito.<ConnectionCallback<List<VectorHit>>>any()))
                .thenReturn(List.of());

        searcher.search("过闸费怎么算", VectorQueryOptions.topK(5));
        searcher.search("船舶通过能力", VectorQueryOptions.topK(5));

        Mockito.verify(searcher, Mockito.times(1)).profileFromDb();
    }

    @Test
    @DisplayName("档案名空白 → 构造即报错（避免造出「查哪个档案都行」的检索器）")
    void blankProfileRejected() {
        assertThatThrownBy(() -> new VectorSearcher(
                Mockito.mock(JdbcTemplate.class),
                new StubEmbeddingClient(bgeM3(), unitVector(1024)), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profileName");
    }

    // ── 查询向量可用性：内积口径要求已归一化 ──────────────────────────────

    @Test
    @DisplayName("⚠️ 内积口径下查询向量未归一化 → 拒绝（否则排序按模长偏斜且不报错）")
    void innerProductRejectsUnnormalizedQueryVector() throws Exception {
        float[] unnormalized = unitVector(1024);
        unnormalized[0] = 7.5f;   // 范数 != 1
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = Mockito.spy(new VectorSearcher(jdbc,
                new StubEmbeddingClient(bgeM3(), unnormalized), PROFILE,
                VectorSearcher.Metric.INNER_PRODUCT));
        Mockito.doReturn(profile("ollama", "bge-m3", 1024, "inner_product")).when(searcher).profileFromDb();

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("范数")
                .hasMessageContaining("--metric cosine");
        // 拒绝发生在查库之前（不让一次注定算错的距离计算进到数据库）
        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("余弦口径不因未归一化而拒绝（<=> 内部会归一化）")
    void cosineAcceptsUnnormalizedQueryVector() throws Exception {
        float[] unnormalized = unitVector(1024);
        unnormalized[0] = 7.5f;
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unnormalized));
        Mockito.when(jdbc.execute(Mockito.<ConnectionCallback<List<VectorHit>>>any()))
                .thenReturn(List.of());

        assertThat(searcher.search("过闸费怎么算", VectorQueryOptions.topK(5))).isEmpty();
    }

    @Test
    @DisplayName("查询向量维度与配置不符 → 拒绝，并指出「必须全量重建索引」")
    void rejectsWrongQueryVectorDimension() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unitVector(512)));

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度")
                .hasMessageContaining("全量重建索引");
    }

    // ── 内积口径的会话参数守卫（与查询同一条连接）────────────────────────

    @Test
    @DisplayName("内积口径：库级已设 relaxed_order → 只读回确认，不再 SET")
    void innerProductConfirmsSessionSettingWithoutSet() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unitVector(1024)), VectorSearcher.Metric.INNER_PRODUCT);
        Connection con = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet hitRs = Mockito.mock(ResultSet.class);
        Statement st = Mockito.mock(Statement.class);
        Mockito.when(con.createStatement()).thenReturn(st);
        Mockito.when(con.prepareStatement(ArgumentMatchers.anyString())).thenReturn(ps);
        Mockito.when(ps.executeQuery()).thenReturn(hitRs);
        Mockito.when(hitRs.next()).thenReturn(false);
        // 预热查询（加载 vector 扩展）
        ResultSet warmRs = Mockito.mock(ResultSet.class);
        Mockito.when(warmRs.next()).thenReturn(true);
        Mockito.when(st.executeQuery(ArgumentMatchers.contains("::vector"))).thenReturn(warmRs);
        // 读回会话参数
        ResultSet settingRs = Mockito.mock(ResultSet.class);
        Mockito.when(settingRs.next()).thenReturn(true);
        Mockito.when(settingRs.getString(1)).thenReturn("relaxed_order");
        Mockito.when(settingRs.getString(2)).thenReturn("20000");
        Mockito.when(st.executeQuery(ArgumentMatchers.contains("current_setting"))).thenReturn(settingRs);
        stubExecute(jdbc, con);

        searcher.search("过闸费怎么算", VectorQueryOptions.topK(5));

        Mockito.verify(st, Mockito.never()).execute(ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("⚠️ 内积口径：会话参数没生效就抛异常（把「静默少返回」变成「启动即失败」）")
    void innerProductFailsWhenSessionSettingIneffective() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unitVector(1024)), VectorSearcher.Metric.INNER_PRODUCT);
        Connection con = Mockito.mock(Connection.class);
        Statement st = Mockito.mock(Statement.class);
        Mockito.when(con.createStatement()).thenReturn(st);
        ResultSet warmRs = Mockito.mock(ResultSet.class);
        Mockito.when(warmRs.next()).thenReturn(true);
        Mockito.when(st.executeQuery(ArgumentMatchers.contains("::vector"))).thenReturn(warmRs);
        // SET 前后读回都是 off（模拟「SET 被当成未注册的点号参数吞掉」的现象）
        ResultSet offRs = Mockito.mock(ResultSet.class);
        Mockito.when(offRs.next()).thenReturn(true);
        Mockito.when(offRs.getString(1)).thenReturn("off");
        Mockito.when(offRs.getString(2)).thenReturn("20000");
        Mockito.when(st.executeQuery(ArgumentMatchers.contains("current_setting"))).thenReturn(offRs);
        stubExecute(jdbc, con);

        assertThatThrownBy(() -> searcher.search("过闸费怎么算", VectorQueryOptions.topK(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hnsw.iterative_scan")
                .hasMessageContaining("静默少返回");
        Mockito.verify(st).execute("SET hnsw.iterative_scan = relaxed_order");
    }

    @Test
    @DisplayName("余弦口径不碰 hnsw 会话参数（不走索引，不存在过滤后少返回）")
    void cosineDoesNotTouchHnswSession() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        VectorSearcher searcher = searcherWithStubbedProfile(jdbc,
                new StubEmbeddingClient(bgeM3(), unitVector(1024)));
        Connection con = Mockito.mock(Connection.class);
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(con.prepareStatement(ArgumentMatchers.anyString())).thenReturn(ps);
        Mockito.when(ps.executeQuery()).thenReturn(rs);
        Mockito.when(rs.next()).thenReturn(false);
        stubExecute(jdbc, con);

        searcher.search("过闸费怎么算", VectorQueryOptions.topK(5));

        Mockito.verify(con, Mockito.never()).createStatement();
    }

    // ── 桩与夹具 ──────────────────────────────────────────────────────────

    private static EmbeddingModelInfo bgeM3() {
        return new EmbeddingModelInfo("ollama", "bge-m3", 1024);
    }

    /** 单位向量：首维 1.0，其余 0 —— L2 范数恰为 1。 */
    private static float[] unitVector(int dimension) {
        float[] v = new float[dimension];
        v[0] = 1.0f;
        return v;
    }

    private static VectorSearcher.ProfileMetadata profile(
            String provider, String model, int dimension, String distanceMetric) {
        return new VectorSearcher.ProfileMetadata(PROFILE,
                new EmbeddingModelInfo(provider, model, dimension),
                distanceMetric, "hnsw", "structure-article-v1", "1.0.0");
    }

    /** 造一个"档案校验已通过"的检索器（用 spy 替换读库那一步）。 */
    private static VectorSearcher searcherWithStubbedProfile(JdbcTemplate jdbc, EmbeddingClient client) {
        return searcherWithStubbedProfile(jdbc, client, VectorSearcher.Metric.COSINE);
    }

    private static VectorSearcher searcherWithStubbedProfile(
            JdbcTemplate jdbc, EmbeddingClient client, VectorSearcher.Metric metric) {
        VectorSearcher searcher = Mockito.spy(new VectorSearcher(jdbc, client, PROFILE, metric));
        Mockito.doReturn(profile("ollama", "bge-m3", 1024, "inner_product")).when(searcher).profileFromDb();
        return searcher;
    }

    /** 让 JdbcTemplate.execute(ConnectionCallback) 真的执行回调（否则它是 mock，回调永远不跑）。 */
    private static void stubExecute(JdbcTemplate jdbc, Connection con) {
        Mockito.when(jdbc.execute(Mockito.<ConnectionCallback<List<VectorHit>>>any()))
                .thenAnswer(invocation -> invocation
                        .<ConnectionCallback<List<VectorHit>>>getArgument(0)
                        .doInConnection(con));
    }

    /** 桩 embedding 客户端：不依赖 Ollama，同时记录被调用次数（用于断言"空查询不编码"）。 */
    private static final class StubEmbeddingClient implements EmbeddingClient {

        private final EmbeddingModelInfo info;
        private final float[] vector;
        private int calls;

        StubEmbeddingClient(EmbeddingModelInfo info, float[] vector) {
            this.info = info;
            this.vector = vector;
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            calls++;
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < (texts == null ? 0 : texts.size()); i++) {
                out.add(vector.clone());
            }
            return out;
        }

        @Override
        public EmbeddingModelInfo modelInfo() {
            return info;
        }
    }
}
