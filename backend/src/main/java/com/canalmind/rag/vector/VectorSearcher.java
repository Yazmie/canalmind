package com.canalmind.rag.vector;

import com.canalmind.rag.embed.EmbeddingClient;
import com.canalmind.rag.embed.EmbeddingClient.EmbeddingException;
import com.canalmind.rag.embed.EmbeddingModelGuard;
import com.canalmind.rag.embed.EmbeddingModelGuard.EmbeddingModelMismatchException;
import com.canalmind.rag.embed.EmbeddingModelInfo;
import com.canalmind.rag.embed.VectorCodec;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 向量检索器（第 2 周第 5 项，规格书第 628 行"Embedding + PgVector 入库"的<b>查询侧</b>）。
 *
 * <h2>本类不实现相似度计算</h2>
 *
 * <p>距离由 PostgreSQL 的 pgvector 算子算出（{@code <=>} / {@code <#>}），
 * 排序与截断也都在同一条 SQL 内完成。这里只做四件事：
 * ① 调 {@link EmbeddingClient} 把查询文本编码成 1024 维向量；
 * ② 拼一条<b>过滤与排序同体</b>的 SQL；③ 传参；④ 把结果映射成 {@link VectorHit}。
 *
 * <p>为什么不在 Java 里取回全部向量自己算余弦：541 行 × 1024 维已经要把
 * 2164 KB 的浮点搬进 JVM，再排序——既慢又让"排序口径"多出一份实现。
 * 两份实现必然漂移，而"分数不一致"这种缺陷不会报错，只会让离线评测与线上排序对不上
 * （与 {@code Bm25Searcher} 把公式唯一实现留在 SQL 侧是同一个理由）。
 *
 * <h2>过滤条件与 BM25 通道<b>逐字对齐</b></h2>
 *
 * <p>三条相等过滤（doc_id / doc_category_no / chunk_type）与生效期区间都照抄
 * {@code V4__add_bm25_token_index.sql} 的 {@code rag_bm25_search}：
 * {@code as_of} 是<b>左闭右开</b> [effective_from, effective_to)，
 * 依据 V1 迁移第 256 行的 {@code daterange(...,'[)')}。
 * 另加一条本通道独有的必要条件：<b>只检索 {@code embedding IS NOT NULL} 的行</b>
 * ——V3 的 {@code rag_chunk_profile_pair_ck} 允许"有 chunk 无向量"的行存在
 * （切分产物与向量产物是两次独立运行），那类行参与距离计算只会得到 NULL，
 * 排序位置无意义却仍占掉 LIMIT 名额。
 *
 * <h2>两种距离度量，以及为什么默认是 cosine</h2>
 *
 * <table border="1">
 *   <caption>度量选择</caption>
 *   <tr><th>度量</th><th>SQL</th><th>能否走 HNSW</th><th>结果性质</th></tr>
 *   <tr><td>{@code cosine}（默认）</td><td>{@code 1 - (embedding <=> q)} / {@code <=>}</td>
 *       <td>否（索引建在 {@code vector_ip_ops} 上，算子类不匹配）</td><td>精确 KNN，无近似误差</td></tr>
 *   <tr><td>{@code ip}</td><td>{@code -(embedding <#> q)} / {@code <#>}</td>
 *       <td>是（与 V3 的索引算子类一致）</td><td>近似 KNN（HNSW）</td></tr>
 * </table>
 *
 * <p>两者在<b>本语料上排序完全等价</b>：bge-m3 输出已 L2 归一化，
 * 归一化向量上内积 = 余弦相似度（V3 迁移文件头第 25–31 行）。
 * 差异只在"是否命中 HNSW"与"是否含近似误差"，因此
 * {@code --metric} 这个开关本质上不是"换个相似度口径"，而是
 * <b>「精确扫描的评测基线」与「走索引的线上路径」二选一</b>——
 * 第 4 周做 HNSW 召回率损失复核时正好需要这两条都能跑。
 *
 * <p>⚠️ 如实记录一处与既有决策的张力：V3 的 {@code COMMENT ON COLUMN rag_chunk.embedding}
 * 写的是「查询用 {@code <#>}」，C-23 也要求「检查执行计划确认索引用上了」。
 * 本类默认走 {@code <=>} 因而<b>不走 HNSW</b>（541 行顺序扫描为毫秒级，且换来精确结果）；
 * 需要"索引真的被用上"的证据时用 {@code --metric ip}，
 * 两边的 {@code EXPLAIN (ANALYZE)} 结论记在交付说明里，不靠注释自证。
 *
 * <h2>身份守卫：复用 {@link EmbeddingModelGuard}，不另造一套</h2>
 *
 * <p>{@link #ensureProfileMatches()} 从 {@code rag_index_profile} 读出<b>库里的既成事实</b>
 * （embedding_provider / embedding_model / embedding_dimension），
 * 与查询侧 {@link EmbeddingClient#modelInfo()} 交给 {@link EmbeddingModelGuard} 比对，
 * 不一致即<b>拒绝查询</b>、不降级。理由见守卫自身的注释（索引由 A 模型产出、查询用 B 编码时，
 * 检索不报错、只是相似度分数全错）。
 *
 * <p>校验结果按实例缓存一次：本类只服务于一次命令行运行（短生命周期），
 * 而 {@code rag_index_profile} 的身份字段在本项目里由
 * {@code RagChunkRepository.upsertProfile} 以 upsert 覆盖写——
 * 也就是说<b>长驻进程里这个缓存是会过期的</b>。这一点与
 * {@code Bm25Searcher} 不同（那边有 {@code rag_assert_token_profile} 守卫，身份字段不可就地改），
 * 故此处如实记下：若将来把本类接进长驻的检索链路，应改为每次查询校验或加缓存失效，
 * <b>不要沿用这里的"校验一次"</b>。
 */
public class VectorSearcher {

    /** 余弦距离算子（pgvector）。{@code <=>} 返回距离（越小越相似），故 score 要取 1 减。 */
    public static final String COSINE_OPERATOR = "<=>";

    /** 内积算子（pgvector）。返回<b>负</b>内积，故 score 要取一元负号。 */
    public static final String INNER_PRODUCT_OPERATOR = "<#>";

    /** 历史遗留：与 {@code rag_index_profile.distance_metric} 的取值对齐（V3 的 CHECK）。 */
    private static final String PROFILE_METRIC_INNER_PRODUCT = "inner_product";

    /** L2 范数容差（与 {@code RagChunkRepository.NORM_TOLERANCE} 同值同理由）。 */
    private static final double NORM_TOLERANCE = 1e-3;

    /**
     * SQL 模板：两处 {@code %s} 分别是「打分表达式」与「排序算子」。
     *
     * <p>刻意用模板而不是抄两份 SQL：两种度量<b>只应差在算子</b>，
     * 抄两份之后任何一次过滤条件的修改都只可能改到其中一份，
     * 而"两个度量口径不同"不会报错、只会让消融对比失真。
     *
     * <p>几个刻意的写法：
     * <ul>
     *   <li>{@code c.embedding IS NOT NULL} —— 见类注释；</li>
     *   <li>每个过滤值绑定<b>两次</b>（{@code ?::text IS NULL OR c.doc_id = ?::text}）：
     *       前半段是"不过滤就放行"的短路，后半段才是比较。不用
     *       {@code coalesce(?::text, c.doc_id) = c.doc_id} 这种"省一个占位符"的写法——
     *       当列本身可空时（{@code doc_category_no} 可空），
     *       参数为 NULL 会让等式变成 {@code NULL = NULL} → 结果为 NULL → 该行被<b>静默丢掉</b>；</li>
     *   <li>{@code ORDER BY ... , c.chunk_id}：算子相等（如同分）时用主键兜住顺序，
     *       否则同一份数据两次查询的"并列名次"可能互换，产物就无法字节级复现；</li>
     *   <li>{@code LIMIT ?} 也走占位符：Top-K 是<b>参数</b>而不是拼字符串进来的，
     *       避免把外部输入拼进 SQL。</li>
     * </ul>
     */
    static final String SEARCH_SQL_TEMPLATE = """
            SELECT c.chunk_id, c.doc_id, c.doc_title, c.chunk_type,
                   c.article_no, c.article_title, c.version,
                   %s AS score
              FROM rag_chunk c
             WHERE c.embedding IS NOT NULL
               AND (?::text IS NULL OR c.doc_id = ?::text)
               AND (?::text IS NULL OR c.doc_category_no = ?::text)
               AND (?::text IS NULL OR c.chunk_type = ?::text)
               AND (?::date IS NULL
                    OR ((c.effective_from IS NULL OR c.effective_from <= ?::date)
                        AND (c.effective_to IS NULL OR c.effective_to > ?::date)))
             ORDER BY c.embedding %s ?::vector ASC, c.chunk_id
             LIMIT ?
            """;

    /** 默认度量的 SQL（精确扫描，评测基线用）。 */
    static final String SEARCH_SQL_COSINE = searchSql(Metric.COSINE);

    /** 走 HNSW 索引的度量所用 SQL。 */
    static final String SEARCH_SQL_INNER_PRODUCT = searchSql(Metric.INNER_PRODUCT);

    /**
     * 索引档案读取 SQL。
     *
     * <p>读的是 {@code rag_index_profile} 的<b>身份与度量字段</b>（模型三元组 + 度量 + 索引类型）。
     * 切分策略字段一并读出只为在 CLI 里打印成"这批向量是怎么来的"，不参与校验
     * （切分口径不影响向量相似度，混批风险由 V3 的触发器 {@code rag_chunk_profile_guard} 兜住）。
     */
    static final String PROFILE_SQL = """
            SELECT profile_name, embedding_provider, embedding_model, embedding_dimension,
                   distance_metric, index_type, splitter_strategy, splitter_version
              FROM rag_index_profile
             WHERE profile_name = ?
            """;

    /** 占位符个数：1（打分表达式里的查询向量）+ 2×3（三条文本过滤）+ 3（as_of）+ 1（排序里的查询向量）+ 1（LIMIT）。 */
    static final int PLACEHOLDER_COUNT = 12;

    /** 距离度量。{@code cliName} 是命令行取值，{@code operator} 是 pgvector 算子。 */
    public enum Metric {

        /**
         * 余弦距离（默认）。score = {@code 1 - (embedding <=> q)} = 余弦相似度。
         *
         * <p>与 HNSW 的 {@code vector_ip_ops} 索引算子类不匹配 → <b>不走索引</b>（精确扫描）。
         */
        COSINE("cosine", COSINE_OPERATOR, "1 - (c.embedding %s ?::vector)"),

        /**
         * 内积（{@code <#>} 返回负内积）。score = {@code -(embedding <#> q)} = 余弦相似度
         * （仅当向量已 L2 归一化时成立，本项目入库时已断言）。
         *
         * <p>与 V3 建的 HNSW 索引算子类一致 → 可走索引，此时是<b>近似</b> KNN。
         */
        INNER_PRODUCT("ip", INNER_PRODUCT_OPERATOR, "-(c.embedding %s ?::vector)");

        private final String cliName;
        private final String operator;
        private final String scoreTemplate;

        Metric(String cliName, String operator, String scoreTemplate) {
            this.cliName = cliName;
            this.operator = operator;
            this.scoreTemplate = scoreTemplate;
        }

        /** 命令行取值。 */
        public String cliName() {
            return cliName;
        }

        /** pgvector 算子。 */
        public String operator() {
            return operator;
        }

        /** 打分表达式（把算子填进模板）。 */
        String scoreExpression() {
            return scoreTemplate.formatted(operator);
        }

        /** 该度量是否可能命中 V3 建的 HNSW 索引。 */
        public boolean usesHnswIndex() {
            return this == INNER_PRODUCT;
        }

        /**
         * 解析命令行取值。
         *
         * <p>刻意只认 {@code cosine} / {@code ip} 两个字面值，不认 {@code <=>} / {@code <#>}
         * 也不做大小写模糊匹配：这个值决定"结果是精确的还是近似的"，
         * 猜错方向不会报错，只会让评测基线悄悄变成另一条链路。
         */
        public static Metric fromCli(String raw) {
            for (Metric m : values()) {
                if (m.cliName.equals(raw)) {
                    return m;
                }
            }
            throw new IllegalArgumentException(
                    "未知距离度量「" + raw + "」：只支持 cosine 或 ip"
                            + "（cosine = 精确扫描的评测基线；ip = 与 HNSW 索引算子类一致，结果为近似）");
        }
    }

    private final JdbcTemplate jdbc;
    private final EmbeddingClient client;
    private final String profileName;
    private final Metric metric;

    /** 档案校验结果缓存，见类注释「长驻进程里会过期」那一段。 */
    private volatile boolean profileVerified;

    /** 默认度量（cosine，精确扫描）。 */
    public VectorSearcher(JdbcTemplate jdbc, EmbeddingClient client, String profileName) {
        this(jdbc, client, profileName, Metric.COSINE);
    }

    public VectorSearcher(JdbcTemplate jdbc, EmbeddingClient client, String profileName, Metric metric) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.client = Objects.requireNonNull(client, "client");
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName 不得为空");
        }
        this.profileName = profileName;
        this.metric = Objects.requireNonNull(metric, "metric");
    }

    /** 档案名（= 库里的索引身份，见 V3 的 {@code rag_index_profile}）。 */
    public String profileName() {
        return profileName;
    }

    /** 本次使用的距离度量。 */
    public Metric metric() {
        return metric;
    }

    /** 查询侧模型身份（来自当前配置，用来与库里的档案比对）。 */
    public EmbeddingModelInfo queryModelInfo() {
        return client.modelInfo();
    }

    /** 生成某个度量对应的检索 SQL（供 CLI 打印"将要执行的 SQL"，也便于测试直接断言）。 */
    static String searchSql(Metric metric) {
        return SEARCH_SQL_TEMPLATE.formatted(metric.scoreExpression(), metric.operator());
    }

    /**
     * 本次实际会执行的 SQL。
     *
     * <p>用枚举穷举 switch 返回上面两个<b>常量</b>（而不是重新格式化一次模板）：
     * 这样"测试断言的那条 SQL"与"真正执行的 SQL"是同一个对象，
     * 不会出现"测试断言了一份、运行用了另一份"这种自证式的假覆盖。
     */
    public String sql() {
        return switch (metric) {
            case COSINE -> SEARCH_SQL_COSINE;
            case INNER_PRODUCT -> SEARCH_SQL_INNER_PRODUCT;
        };
    }

    /**
     * 从库里读索引档案元数据（{@code rag_index_profile} 的既成事实）。
     *
     * @return 档案元数据；档案不存在时返回 {@code null}
     *         （由 {@link #ensureProfileMatches()} 转成"拒绝查询"，不在这里编错误信息）
     */
    public ProfileMetadata profileFromDb() {
        List<ProfileMetadata> rows = jdbc.query(PROFILE_SQL, (rs, rowNum) -> new ProfileMetadata(
                rs.getString("profile_name"),
                new EmbeddingModelInfo(
                        rs.getString("embedding_provider"),
                        rs.getString("embedding_model"),
                        rs.getInt("embedding_dimension")),
                rs.getString("distance_metric"),
                rs.getString("index_type"),
                rs.getString("splitter_strategy"),
                rs.getString("splitter_version")),
                // 显式传 Object[] 而不是让编译器展开 varargs：
                // 占位符只有一个，靠数组把"参数个数"这件事写死，避免将来加参数时静默错位。
                new Object[]{profileName});
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 索引档案身份校验（首次调用时执行一次，之后走缓存）。
     *
     * @throws EmbeddingModelMismatchException 档案缺失、模型三元组不一致，或（内积口径下）
     *         档案声明的度量不是 {@code inner_product}
     */
    public void ensureProfileMatches() throws EmbeddingModelMismatchException {
        if (profileVerified) {
            return;
        }
        synchronized (this) {
            if (profileVerified) {
                return;
            }
            ProfileMetadata db = profileFromDb();
            EmbeddingModelInfo indexed = db == null ? null : db.model();
            try {
                // 复用既有闸门：库里的档案是"事实"，本地配置是"意图"，两者比对由守卫负责。
                new EmbeddingModelGuard(indexed, client.modelInfo()).verify();
            } catch (EmbeddingModelMismatchException e) {
                // 加上档案名再抛：命令行场景下"是哪个档案"比"不匹配"更值得第一眼看到。
                throw new EmbeddingModelMismatchException(
                        "索引档案「" + profileName + "」不可用于本次查询，拒绝查询（不降级）：" + e.getMessage());
            }
            if (metric.usesHnswIndex() && !PROFILE_METRIC_INNER_PRODUCT.equals(db.distanceMetric())) {
                // 内积口径的分数只在"索引也是按内积建的"这个前提下才有意义。
                // 这不是洁癖：换个算子类而忘记同步索引，排序会静默变错（V3 文件头第 29–31 行）。
                throw new EmbeddingModelMismatchException(
                        "索引档案「" + profileName + "」声明的 distance_metric 是「" + db.distanceMetric()
                                + "」，而本次要求用内积（" + INNER_PRODUCT_OPERATOR + "）排序。"
                                + "两者必须一致（V3 的 HNSW 索引建在 vector_ip_ops 上）；"
                                + "口径不一致时排序会静默变错，故拒绝查询。"
                                + "若只想按余弦相似度检索，用 --metric cosine（归一化向量上排名等价）。");
            }
            profileVerified = true;
        }
    }

    /**
     * 检索。
     *
     * <p>查询文本为空（null / 空白）时<b>直接返回空列表、不调 embedding、不查库</b>：
     * 这不是异常，而是"没有可匹配的信号"。口径与
     * {@code Bm25Searcher} 的"切不出词元就不查库"一致——
     * 两通道对"空输入"的处理若不同，消融对比会在这一格上出现假差异。
     *
     * @throws EmbeddingException              调 embedding 失败（网络/模型/维度）
     * @throws EmbeddingModelMismatchException 索引侧档案与查询侧不一致
     */
    public List<VectorHit> search(String query, VectorQueryOptions options)
            throws EmbeddingException, EmbeddingModelMismatchException {
        Objects.requireNonNull(options, "options 不得为空");
        if (query == null || query.isBlank()) {
            return List.of();
        }
        ensureProfileMatches();

        // ⚠️ 查询文本原样送去编码，不加任何指令前缀：入库侧编码的是 chunk 正文原文，
        //    查询侧若加「query:」之类前缀（bge-m3 不需要），两侧就变成两种编码约定，
        //    症状是"召回莫名其妙变差"且不报错（对照 C-22 的同类问题）。
        float[] queryVector = client.embedOne(query);
        requireUsableQueryVector(queryVector);

        // 同一个字面量用两次（打分表达式与排序），故只编码一次：
        // 两次分别编码会因 float 打印/解析产生"看起来一样"的两个字符串，
        // 而排序与打分用的必须是同一个向量，否则分数与名次会互相矛盾。
        String literal = VectorCodec.toPgVectorLiteral(queryVector);

        List<VectorHit> hits = jdbc.execute((ConnectionCallback<List<VectorHit>>) con -> {
            // 会话级参数必须在**同一条连接**上准备：HNSW 的迭代扫描参数是会话级 GUC，
            // 而 DriverManagerDataSource 每次操作都给新连接（见 configureSession 的注释）。
            configureSession(con);
            try (PreparedStatement ps = con.prepareStatement(sql())) {
                int i = 1;
                ps.setString(i++, literal);
                i = bindFilters(ps, i, options);
                ps.setString(i++, literal);
                ps.setInt(i, options.topK());
                try (ResultSet rs = ps.executeQuery()) {
                    List<VectorHit> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(new VectorHit(
                                rs.getString("chunk_id"),
                                rs.getString("doc_id"),
                                rs.getString("doc_title"),
                                rs.getString("chunk_type"),
                                rs.getString("article_no"),
                                rs.getString("article_title"),
                                rs.getString("version"),
                                rs.getDouble("score")));
                    }
                    return list;
                }
            }
        });
        return hits == null ? List.of() : hits;
    }

    /**
     * 绑定三条文本过滤（每个值绑两次，见 {@link #SEARCH_SQL_TEMPLATE} 的注释）。
     *
     * @return 下一个可用的参数下标
     */
    private static int bindFilters(PreparedStatement ps, int from, VectorQueryOptions o)
            throws SQLException {
        int i = from;
        i = bindTwice(ps, i, o.docId());
        i = bindTwice(ps, i, o.docCategoryNo());
        i = bindTwice(ps, i, o.chunkType());
        return bindAsOf(ps, i, o.asOf());
    }

    private static int bindTwice(PreparedStatement ps, int i, String value) throws SQLException {
        ps.setString(i++, value);
        ps.setString(i++, value);
        return i;
    }

    /**
     * 绑定 as_of（SQL 里有三处占位符：一处判空、两处区间比较）。
     *
     * <p>NULL 必须用 {@code setNull(Types.DATE)} 而不是 {@code setString(i, null)}：
     * 前者把"这是 DATE 类型的空值"告诉驱动，后者的无类型 NULL 让 PG 在
     * {@code ?::date IS NULL} 上仍需推断类型——虽然显式转型能救回来，
     * 但等到报错时错误信息指向的是"参数类型无法确定"，与真正的原因（没传日期）相隔很远。
     */
    private static int bindAsOf(PreparedStatement ps, int i, LocalDate asOf) throws SQLException {
        for (int k = 0; k < 3; k++) {
            if (asOf == null) {
                ps.setNull(i++, Types.DATE);
            } else {
                ps.setObject(i++, asOf, Types.DATE);
            }
        }
        return i;
    }

    /**
     * 查询向量可用性断言。
     *
     * <p>两项都是"错了不会报错、只会悄悄算错"的高危点（本项目一贯重点关注的一类）：
     * <ol>
     *   <li><b>维度</b>必须等于当前配置的维度。维度不符时数据库会在 {@code ?::vector}
     *       处报"字面量语法错误"——错误信息完全不指向真正的原因（模型换了）；</li>
     *   <li><b>内积口径下必须已 L2 归一化</b>：{@code <#>} 的排序在未归一化向量上会按模长偏斜
     *       （长向量系统性排前），而这不是语义相似度，且<b>不报错</b>。
     *       余弦口径（{@code <=>}）内部会做归一化，故不受影响，不在此断言范围内。</li>
     * </ol>
     */
    private void requireUsableQueryVector(float[] v) {
        int expected = client.modelInfo().dimension();
        if (v == null || v.length != expected) {
            int actual = v == null ? -1 : v.length;
            throw new IllegalStateException(
                    "查询向量维度为 " + actual + "，但当前模型 " + client.modelInfo().profileName()
                            + " 声明的维度是 " + expected + "。向量列是 vector(" + expected + ")，"
                            + "维度不符说明查询侧模型与索引不一致；按 C-22 配套硬约束必须全量重建索引，"
                            + "不得用另一维度的模型查询。");
        }
        if (metric.usesHnswIndex() && !VectorCodec.isL2Normalized(v, NORM_TOLERANCE)) {
            double norm = VectorCodec.norm(v);
            throw new IllegalStateException(
                    "查询向量 L2 范数为 " + norm + "，偏离 1.0 超过 " + NORM_TOLERANCE
                            + "，而本次用的是内积（" + INNER_PRODUCT_OPERATOR + "）。"
                            + "内积排序只在向量已归一化时才等价于余弦相似度；未归一化会让排序按模长偏斜，"
                            + "且不报错。请确认 embedding 模型是否仍为已归一化的 bge-m3，"
                            + "或改用 --metric cosine（该口径内部会归一化）。");
        }
    }

    /**
     * 准备本会话的 HNSW 参数（仅内积口径调用）。
     *
     * <p><b>为什么必须在 {@link ConnectionCallback} 内、与查询同一条连接上做</b>：
     * {@code hnsw.iterative_scan} 是<b>会话级</b> GUC，不是索引属性；
     * 而 CLI 用的 {@code DriverManagerDataSource} 每次操作都开新连接，
     * "启动时 SET 一次"只会作用在一条随即被丢弃的连接上——参数丢失且<b>不报错</b>，
     * 查询会安静地少返回结果（{@code docs/工程复盘/检索工程/会话级参数与连接池.md}）。
     *
     * <p>为什么需要它：HNSW 近似索引下 {@code LIMIT} 在索引扫描阶段就被满足，
     * 带 {@code WHERE} 过滤时可能只取回部分近邻、过滤后返回行数少于 Top-K 且不报错
     * （实测 3000 行表上 LIMIT 10 只回 2 行，见 V3 文件头）。
     * {@code iterative_scan = relaxed_order} 让它继续扫到凑够 Top-K。
     *
     * <p>三处刻意写法（照抄 {@code RagChunkRepository.configureSessionForFilteredSearch} 的教训）：
     * ① 先用一次真实 vector 表达式把扩展加载进本会话，{@code hnsw.*} 两个 GUC 才会存在；
     * ② {@code SET} 之后用 {@code current_setting} <b>读回</b>——PG 对带点号的自定义参数"来者不拒"，
     * 未注册时 {@code SET} 会成功返回却什么也没改；
     * ③ 读到的值不对就<b>抛异常</b>，把"静默少返回"变成"启动即失败"。
     *
     * <p>库里已有 {@code ALTER DATABASE canalmind SET hnsw.iterative_scan = relaxed_order}
     * （V3 §4），所以正常情况下这里只是读回确认，不会真的执行 SET。
     */
    private void configureSession(Connection con) throws SQLException {
        if (!metric.usesHnswIndex()) {
            // cosine 不走 HNSW（算子类不匹配）→ 精确扫描 → 不存在"过滤后少返回"，无需该参数。
            return;
        }
        try (Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT '[0]'::vector")) {
                rs.next();
            }
            String mode;
            String maxScanTuples;
            try (ResultSet rs = st.executeQuery(
                    "SELECT current_setting('hnsw.iterative_scan', true),"
                            + " current_setting('hnsw.max_scan_tuples', true)")) {
                if (!rs.next()) {
                    throw new IllegalStateException("读取 hnsw 会话参数失败：查询没有返回行");
                }
                mode = rs.getString(1);
                maxScanTuples = rs.getString(2);
            }
            if (!"relaxed_order".equals(mode)) {
                st.execute("SET hnsw.iterative_scan = relaxed_order");
                try (ResultSet rs = st.executeQuery(
                        "SELECT current_setting('hnsw.iterative_scan', true)")) {
                    rs.next();
                    mode = rs.getString(1);
                }
                if (!"relaxed_order".equals(mode)) {
                    throw new IllegalStateException(
                            "HNSW 会话参数未真正生效：hnsw.iterative_scan=" + mode
                                    + "（max_scan_tuples=" + maxScanTuples + "）。"
                                    + "若 SET 被当成了自定义占位参数，说明本会话尚未加载 vector 扩展；"
                                    + "此状态下带过滤的向量检索会**静默少返回结果**，故拒绝继续。"
                                    + "可改用 --metric cosine（不走索引、精确扫描）绕开该风险。");
                }
            }
        }
    }

    /**
     * 索引档案元数据（库里的既成事实）。
     *
     * @param profileName      档案名（= {@code ollama:bge-m3:1024} 这类三元组字符串）
     * @param model            embedding 模型身份（provider / model / dimension）
     * @param distanceMetric   档案声明的距离度量（V3 的 CHECK：inner_product / cosine / l2）
     * @param indexType        档案声明的索引类型（hnsw / ivfflat / none）
     * @param splitterStrategy 切分策略（仅用于打印"这批向量是怎么来的"）
     * @param splitterVersion  切分器版本（同上）
     */
    public record ProfileMetadata(
            String profileName,
            EmbeddingModelInfo model,
            String distanceMetric,
            String indexType,
            String splitterStrategy,
            String splitterVersion) {

        /** 供 CLI/日志的一行摘要。 */
        public String summary() {
            return profileName + "（provider=" + model.provider()
                    + " model=" + model.model()
                    + " dim=" + model.dimension()
                    + " metric=" + distanceMetric
                    + " index=" + indexType
                    + " splitter=" + splitterStrategy + " " + splitterVersion + "）";
        }
    }
}
