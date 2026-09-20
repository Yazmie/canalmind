package com.canalmind.rag.ingest;

import com.canalmind.rag.embed.EmbeddingModelInfo;
import com.canalmind.rag.embed.VectorCodec;
import com.canalmind.rag.split.Chunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code rag_chunk} / {@code rag_index_profile} 的写入口（PgVector 入库，第 2 周第 3 项）。
 *
 * <p><b>为什么直接用 JdbcTemplate 而不是 MyBatis-Plus</b>：本次要写的是
 * {@code text[]}、{@code jsonb}、{@code vector(1024)} 三种非标量列，且整批写入必须
 * 在一条 {@code INSERT ... ON CONFLICT} 里完成（幂等重跑）。JdbcTemplate 的
 * {@code BatchPreparedStatementSetter} 允许我们在 setter 内拿到
 * {@link java.sql.Connection} 来构造 {@code Array}——这是 {@code text[]} 唯一可靠的绑定方式。
 * MyBatis 的 TypeHandler 路线要在 XML/注解里另写三类 handler，对这一次性入库不划算
 * （结构化查询通道那一步再评估，见规格书 §0.3）。
 *
 * <p><b>两种装配方式</b>：既能被 Spring 注入（{@code new RagChunkRepository(dataSource)}），
 * 也能在命令行里独立使用（{@link PgVectorIngestCli} 用
 * {@code DriverManagerDataSource} 包一层 {@code DriverManager.getConnection}）。
 * 之所以不写成 {@code @Repository} 组件：入库是<b>离线批处理</b>，不该被应用启动时扫描成 bean
 * ——那样每次启动应用都会凭空多一个依赖数据库的 bean，而检索通道本周还没接进主链路。
 *
 * <p><b>本类刻意承担的两项"入库前断言"</b>（都属于"错了不会报错、只会静默出错"的高危点，
 * 见 {@code docs/工程复盘/工程方法/转义与写入层的静默损坏.md} 同一类问题）：
 * <ol>
 *   <li><b>向量维度必须等于档案维度</b>：{@code embedding} 是 {@code vector(1024)}，
 *       长度不符时数据库会报错——但那时 embedding 算力已经白花，而且错误信息指向
 *       "vector 字面量语法"而不是"模型换了"；</li>
 *   <li><b>向量必须已 L2 归一化</b>：HNSW 索引建在 {@code vector_ip_ops}（内积）上，
 *       查询用 {@code <#>}。<b>只有归一化向量上内积才等价于余弦</b>；若某条向量未归一化，
 *       它会因为"模长大"而被内积排序系统性排到前面，<b>不报错</b>，只是排序被长度污染
 *       （依据：V3 迁移文件第 25–31 行、知识库《RAG 向量索引算法和向量数据库》第 157 行）。</li>
 * </ol>
 */
public class RagChunkRepository {

    /**
     * 单次 {@code batchUpdate} 的行数上限。
     *
     * <p>541 行 × 每行约 1 万个字符的 vector 字面量 ≈ 6 MB 参数，
     * 一次提交并不会爆内存，但切片后单批失败的影响面更小、日志也更好读。
     * 这个值只影响<b>分批发送</b>，不影响事务语义之外的任何结果（幂等 upsert）。
     */
    private static final int WRITE_BATCH_SIZE = 250;

    private static final String UPSERT_PROFILE_SQL = """
            INSERT INTO rag_index_profile (
                profile_name, embedding_provider, embedding_model, embedding_dimension,
                distance_metric, index_type, splitter_strategy, splitter_version, chunker_note
            ) VALUES (?, ?, ?, ?, 'inner_product', 'hnsw', ?, ?, ?)
            ON CONFLICT (profile_name) DO UPDATE SET
                embedding_provider = EXCLUDED.embedding_provider,
                embedding_model    = EXCLUDED.embedding_model,
                embedding_dimension = EXCLUDED.embedding_dimension,
                distance_metric    = EXCLUDED.distance_metric,
                index_type         = EXCLUDED.index_type,
                splitter_strategy  = EXCLUDED.splitter_strategy,
                splitter_version   = EXCLUDED.splitter_version,
                chunker_note       = EXCLUDED.chunker_note
            """;

    /**
     * 全列 upsert。
     *
     * <p>几个刻意的写法：
     * <ul>
     *   <li>{@code ?::date} / {@code ?::jsonb} / {@code ?::vector}——显式转型，不依赖 JDBC
     *       的类型推断。参数按 VARCHAR 绑定（{@code setString}），实测 PG 18.4 下
     *       {@code varchar::vector}、{@code varchar::jsonb}、{@code varchar::date} 三种转换均可用；</li>
     *   <li>{@code chapter_path} / {@code table_headers} 用 {@code createArrayOf} 绑定，
     *       <b>不拼 {@code '{...}'} 字面量</b>——表头可能含逗号与引号，拼字符串必然踩转义坑；</li>
     *   <li>{@code embedded_at = now()}：向量与写入时刻一起落库，便于事后判断"这批向量是什么时候产的"；</li>
     *   <li>{@code created_at} / {@code updated_at} 交给列默认值与触发器，
     *       但 <b>DO UPDATE 也要显式 {@code updated_at = now()}</b>——触发器虽然会兜底，
     *       可依赖"另一个对象会替我兜住"正是评审时最容易漏掉的一类假设。</li>
     * </ul>
     */
    private static final String UPSERT_CHUNK_SQL = """
            INSERT INTO rag_chunk (
                chunk_id, chunk_type, doc_id, doc_title, doc_no, version,
                effective_from, effective_to, supersedes, doc_category_no,
                chapter_path, article_no, article_title, part_index, part_count,
                table_headers, table_rows, strategy, splitter_version,
                content, char_count, source_lines,
                embedding, profile_name, embedded_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?::date, ?::date, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?::jsonb, ?, ?,
                ?, ?, ?,
                ?::vector, ?, now()
            )
            ON CONFLICT (chunk_id) DO UPDATE SET
                chunk_type        = EXCLUDED.chunk_type,
                doc_id            = EXCLUDED.doc_id,
                doc_title         = EXCLUDED.doc_title,
                doc_no            = EXCLUDED.doc_no,
                version           = EXCLUDED.version,
                effective_from    = EXCLUDED.effective_from,
                effective_to      = EXCLUDED.effective_to,
                supersedes        = EXCLUDED.supersedes,
                doc_category_no   = EXCLUDED.doc_category_no,
                chapter_path      = EXCLUDED.chapter_path,
                article_no        = EXCLUDED.article_no,
                article_title     = EXCLUDED.article_title,
                part_index        = EXCLUDED.part_index,
                part_count        = EXCLUDED.part_count,
                table_headers     = EXCLUDED.table_headers,
                table_rows        = EXCLUDED.table_rows,
                strategy          = EXCLUDED.strategy,
                splitter_version  = EXCLUDED.splitter_version,
                content           = EXCLUDED.content,
                char_count        = EXCLUDED.char_count,
                source_lines      = EXCLUDED.source_lines,
                embedding         = EXCLUDED.embedding,
                profile_name      = EXCLUDED.profile_name,
                embedded_at       = now(),
                updated_at        = now()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 由 {@link #upsertProfile} 记住的维度，供 {@link #upsertChunks} 断言用。
     *
     * <p>刻意<b>不</b>把 {@code EmbeddingModelInfo} 放在构造器上：本类的构造器要能被
     * Spring 直接注入（只有 DataSource 可注入），而"这批向量属于哪个档案"是
     * <b>调用序列</b>里的事实，不是装配时的事实。
     */
    private volatile Integer rememberedDimension;

    /** Spring 装配入口。 */
    public RagChunkRepository(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    /** 便于测试直接注入已配置的 JdbcTemplate。 */
    public RagChunkRepository(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate 不能为 null");
        }
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入/更新索引档案（幂等）。
     *
     * <p>{@code distance_metric='inner_product'} 与 {@code index_type='hnsw'} 是<b>写死</b>的，
     * 不是省事：它们必须与 V3 迁移里建的索引操作符类（{@code vector_ip_ops}）
     * 及查询运算符（{@code <#>}）三者一致，任何一处改漏都会静默返回错误排序
     * （V3 文件第 29–31 行）。所以不给调用方留"改一个"的入口。
     *
     * @param info              embedding 模型身份（三者编进 profile_name）
     * @param splitterStrategy  切分策略，如 {@code structure-article-v1}
     * @param splitterVersion   切分器版本
     * @param note              人可读备注（可为 null）
     */
    public void upsertProfile(EmbeddingModelInfo info, String splitterStrategy,
                              String splitterVersion, String note) {
        if (info == null) {
            throw new IllegalArgumentException("EmbeddingModelInfo 不能为 null");
        }
        jdbcTemplate.update(UPSERT_PROFILE_SQL,
                info.profileName(), info.provider(), info.model(), info.dimension(),
                splitterStrategy, splitterVersion, note);
        this.rememberedDimension = info.dimension();
    }

    /**
     * 批量 upsert chunk 及其向量。
     *
     * @param chunks      chunk 列表（与 {@code vectors} 一一对应、顺序一致）
     * @param vectors     向量列表，长度必须等于 {@code chunks.size()}
     * @param profileName 所属档案（必须已存在于 {@code rag_index_profile}，
     *                    否则数据库的外键 + 触发器会拒绝——这是刻意的：
     *                    "有向量却不知道是谁产的"在库层面就不允许）
     * @return 实际写入/更新的行数
     * @throws IllegalStateException 条数不符、维度不符、或向量未 L2 归一化
     */
    public int upsertChunks(List<Chunk> chunks, List<float[]> vectors, String profileName) {
        if (chunks == null || vectors == null) {
            throw new IllegalStateException("chunks 与 vectors 都不能为 null");
        }
        if (chunks.size() != vectors.size()) {
            throw new IllegalStateException(
                    "chunk 条数 " + chunks.size() + " 与向量条数 " + vectors.size() + " 不一致；"
                            + "按位对齐会静默错位，故拒绝写入");
        }
        if (chunks.isEmpty()) {
            return 0;
        }

        // ⚠️ 整批预检**先于任何写入**：维度与归一化只要有一条不合格就整批拒绝，
        //    不会留下"部分行有向量、部分行没有"的半成品状态。
        Integer dimension = resolveDimension(profileName);
        requireDimensions(chunks, vectors, dimension, "档案 " + profileName);
        for (int i = 0; i < chunks.size(); i++) {
            requireL2Normalized(chunks.get(i), vectors.get(i));
        }

        int total = 0;
        for (int from = 0; from < chunks.size(); from += WRITE_BATCH_SIZE) {
            int to = Math.min(from + WRITE_BATCH_SIZE, chunks.size());
            total += upsertSlice(chunks.subList(from, to), vectors.subList(from, to), profileName);
        }
        return total;
    }

    /**
     * 解析"该档案声明的向量维度"。
     *
     * <p>优先用 {@link #upsertProfile} 刚记住的值；没记住（例如进程重启后直接补写）
     * 就回查数据库——库里的 {@code rag_index_profile} 才是权威事实，
     * 而不是调用方记得传对参数。
     *
     * @return 维度；档案不存在时返回 {@code null}（此时外键与触发器会拒绝写入，
     *         不需要在这里再造一个错误信息）
     */
    private Integer resolveDimension(String profileName) {
        Integer remembered = rememberedDimension;
        if (remembered != null) {
            return remembered;
        }
        List<Integer> dims = jdbcTemplate.queryForList(
                "SELECT embedding_dimension FROM rag_index_profile WHERE profile_name = ?",
                Integer.class, profileName);
        return dims.isEmpty() ? null : dims.get(0);
    }

    /**
     * 写入一个切片。
     *
     * <p>⚠️ {@code Array} 对象由驱动持有、需显式 {@code free()}；这里在 setter 内创建，
     * 再在 {@code batchUpdate} 返回后统一释放——<b>不能在 setter 里就地释放</b>，
     * 因为批量执行时驱动要等到 {@code executeBatch} 才真正读取参数。
     */
    private int upsertSlice(List<Chunk> chunks, List<float[]> vectors, String profileName) {
        List<Array> allocated = new ArrayList<>(chunks.size() * 2);
        try {
            int[] counts = jdbcTemplate.batchUpdate(UPSERT_CHUNK_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Chunk c = chunks.get(i);
                    float[] v = vectors.get(i);
                    bind(ps, c, v, profileName, allocated);
                }

                @Override
                public int getBatchSize() {
                    return chunks.size();
                }
            });
            int sum = 0;
            for (int n : counts) {
                sum += Math.max(n, 0);
            }
            return sum;
        } finally {
            for (Array a : allocated) {
                try {
                    a.free();
                } catch (SQLException ignored) {
                    // 释放失败不影响已写入的数据；此处刻意不抛，避免掩盖主异常
                }
            }
        }
    }

    /**
     * 绑定一行。
     *
     * <p>占位符顺序与 {@link #UPSERT_CHUNK_SQL} 严格一致。
     * <b>这里不再做维度/归一化断言</b>：那两项已由 {@link #upsertChunks} 在写入前整批预检过；
     * 放在 setter 里会变成"边写边查"，失败时前面的行已经写进去了。
     * 此处只拦"根本写不进去"的输入（缺 chunk_id）。
     */
    private void bind(PreparedStatement ps, Chunk c, float[] v, String profileName,
                      List<Array> allocated) throws SQLException {
        if (c.getChunkId() == null || c.getChunkId().isBlank()) {
            throw new IllegalStateException("chunk 缺少 chunk_id，无法写入（chunk_id 是主键，也是与切分器对账的凭据）");
        }

        int i = 1;
        ps.setString(i++, c.getChunkId());
        ps.setString(i++, c.getChunkType() == null ? null : c.getChunkType().name());
        ps.setString(i++, c.getDocId());
        ps.setString(i++, c.getDocTitle());
        ps.setString(i++, c.getDocNo());
        ps.setString(i++, c.getVersion());
        // effective_from/to 是 DATE；ISO 字符串经 ::date 转换，null 直接落 NULL
        ps.setString(i++, c.getEffectiveFrom());
        ps.setString(i++, c.getEffectiveTo());
        ps.setString(i++, c.getSupersedes());
        ps.setString(i++, c.getDocCategoryNo());
        // chapter_path 是 NOT NULL text[]：null/空一律写成空数组 '{}'，语义与 DDL 默认值一致
        ps.setArray(i++, textArray(ps, allocated,
                c.getChapterPath() == null ? List.of() : c.getChapterPath()));
        ps.setString(i++, c.getArticleNo());
        ps.setString(i++, c.getArticleTitle());
        ps.setInt(i++, c.getPartIndex());
        ps.setInt(i++, c.getPartCount());
        // table_headers 可空：非表格 chunk 就是 NULL。
        // 用 Types.OTHER（= Oid.UNSPECIFIED）发送**无类型 NULL**，由 INSERT 的列类型推断；
        // 若用 setNull(Types.ARRAY) 会以 anyarray 伪类型发出，反而可能无法定型。
        if (c.getTableHeaders() == null) {
            ps.setNull(i++, Types.OTHER);
        } else {
            ps.setArray(i++, textArray(ps, allocated, c.getTableHeaders()));
        }
        // table_rows 是 jsonb（二维数组）。null 同样走无类型 NULL。
        if (c.getTableRows() == null) {
            ps.setNull(i++, Types.OTHER);
        } else {
            ps.setString(i++, toJson(c));
        }
        ps.setString(i++, c.getStrategy());
        ps.setString(i++, c.getSplitterVersion());
        ps.setString(i++, c.getContent());
        ps.setInt(i++, c.getCharCount());
        ps.setString(i++, c.getSourceLines());
        // 向量：手写 pgvector 文本字面量 + ::vector 显式转型
        //（本机缓冲仓库没有 pgvector 的 Java 客户端，见 VectorCodec 类注释）
        ps.setString(i++, VectorCodec.toPgVectorLiteral(v));
        ps.setString(i, profileName);
    }

    private Array textArray(PreparedStatement ps, List<Array> allocated, List<String> values)
            throws SQLException {
        Array arr = ps.getConnection().createArrayOf("text", values.toArray());
        allocated.add(arr);
        return arr;
    }

    /**
     * 表格数据 → JSONB。
     *
     * <p>用 Jackson 而不是手拼字符串：单元格里可能有引号、反斜杠、全角逗号，
     * 手拼必然在某个语料上踩转义坑，而"转义错了"在本项目已经被证明是
     * <b>不报错、只悄悄写错数据</b>的一类问题。
     */
    private String toJson(Chunk c) {
        try {
            return mapper.writeValueAsString(c.getTableRows());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "chunk " + c.getChunkId() + " 的 table_rows 无法序列化为 JSON", e);
        }
    }

    /**
     * 入库前的两项硬断言之一：向量必须已 L2 归一化。
     *
     * <p>为什么这里<b>报错而不是自动归一化</b>：自动归一化看起来很体贴，
     * 但它会把"模型换了、输出分布变了"这一事实<b>擦掉</b>——
     * 而那正是 C-22 配套硬约束要我们注意的信号。宁可让入库停在这里，
     * 由人确认"是 bge-m3 换了版本"还是"配错了模型"。
     */
    private void requireL2Normalized(Chunk c, float[] v) {
        if (v == null) {
            throw new IllegalStateException(
                    "chunk " + c.getChunkId() + " 没有对应向量（vectors 里出现 null）；"
                            + "有 chunk 无向量会被 rag_chunk_profile_pair_ck 拒绝，故在此提前失败");
        }
        double norm = VectorCodec.norm(v);
        if (!VectorCodec.isL2Normalized(v, NORM_TOLERANCE)) {
            throw new IllegalStateException(
                    "chunk " + c.getChunkId() + " 的向量 L2 范数为 " + norm + "，偏离 1.0 超过 "
                            + NORM_TOLERANCE + "。HNSW 索引使用 vector_ip_ops（内积 <#>），"
                            + "只有 L2 归一化向量上内积才等价于余弦相似度；未归一化的向量会因模长偏大"
                            + "被系统性排前，且**不会报错**。请确认 embedding 模型是否为已归一化的 bge-m3。");
        }
    }

    /**
     * 入库前的两项硬断言之二：维度必须等于档案声明维度。
     *
     * <p>放在 {@link #upsertChunks} 最前面<b>整批预先检查</b>，而不是在 setter 里边写边查：
     * 预检失败时一行都不会写入，语义是干净的"整批拒绝"。
     *
     * <p>档案不存在（{@code dimension == null}）时<b>不报错</b>：那种情况下外键
     * {@code rag_chunk_profile_name_fkey} 与触发器 {@code rag_chunk_profile_guard}
     * 会给出更贴近原因的错误信息，这里再拦一道只会把真正的原因盖住。
     */
    private void requireDimensions(List<Chunk> chunks, List<float[]> vectors, Integer dimension,
                                   String what) {
        if (dimension == null) {
            return;
        }
        for (int i = 0; i < vectors.size(); i++) {
            float[] v = vectors.get(i);
            int len = v == null ? -1 : v.length;
            if (len != dimension) {
                String id = i < chunks.size() ? chunks.get(i).getChunkId() : ("# " + i);
                throw new IllegalStateException(
                        "chunk " + id + " 的向量维度为 " + len + "，但" + what
                                + " 声明的维度为 " + dimension
                                + "。向量列是 vector(1024)，维度不符说明 embedding 模型与索引档案不一致；"
                                + "按 C-22 配套硬约束必须全量重建索引，不得混批写入。");
            }
        }
    }

    /**
     * 供调用方（{@link PgVectorIngestCli}）在写入前做一次显式维度校验。
     *
     * <p>与 {@link #upsertChunks} 内部的预检<b>同源</b>（都走 {@link #requireDimensions}），
     * 区别只在错误信息里点名的是 {@code EmbeddingModelInfo} 而非库里的档案名——
     * 命令行场景下"我配的模型是哪个"比"库里那行档案"更直观。
     *
     * @throws IllegalStateException 任一条向量长度不等于 {@code info.dimension()}
     */
    public void assertDimensions(List<Chunk> chunks, List<float[]> vectors, EmbeddingModelInfo info) {
        if (info == null) {
            throw new IllegalStateException("EmbeddingModelInfo 不能为 null，无法校验向量维度");
        }
        requireDimensions(chunks, vectors, info.dimension(), "配置的模型 " + info.profileName());
    }

    /** L2 范数容差。实测 bge-m3 偏差约 1e-6，取 1e-3 留两个数量级余量（同 {@link VectorCodec}）。 */
    private static final double NORM_TOLERANCE = 1e-3;

    /** {@code rag_chunk} 现有行数。 */
    public Long countChunks() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM rag_chunk", Long.class);
    }

    /**
     * 批量写入后收集统计信息。
     *
     * <p><b>⚠️ 为什么必须显式调用（这不是可选优化）</b>——本项目实测暴露的缺陷：
     * 541 行入库后 <b>从未 ANALYZE</b>，{@code pg_class} 里 {@code reltuples = -1}、
     * {@code relpages = 0}，于是<b>规划器是在没有统计信息的情况下决定执行计划</b>的。
     *
     * <p>后果是<b>静默的</b>：查询照常返回正确结果，但 {@code EXPLAIN} 给出的计划与代价
     * <b>不代表数据真实分布</b>，据此得出的"为什么没走索引"结论会完全失真
     * （我在排查 HNSW 是否被使用时就被这一点误导过一轮）。
     *
     * <p>一句话：<b>"表里有数据"不等于"规划器知道表里有数据"。</b>
     * 批量写入后不 ANALYZE，等于让优化器盲选计划；而这一步<b>不报错</b>，
     * 只会在你读 {@code EXPLAIN} 时骗你。
     *
     * <p>用 {@code ANALYZE} 而非 {@code VACUUM ANALYZE}：只需统计信息，
     * 不必顺带回收空间（且在事务中 {@code VACUUM} 也不允许执行）。
     */
    public void analyzeChunkTable() {
        jdbcTemplate.execute("ANALYZE rag_chunk");
    }

    /**
     * 读取"当前索引里的向量是由哪个档案（哪个模型）产出的"。
     *
     * <p><b>⚠️ 为什么不是简单取 {@code rag_index_profile} 的第一行</b>——
     * 本机实测踩到：档案表里存在一条 <b>0 行 chunk 引用</b>的历史档案
     * （{@code bge-m3-structure-v1}，创建于 17:47，早于本次入库），
     * 而本次入库用的是 {@code ollama:bge-m3:1024}。若按"第一行"返回，
     * 这个方法的返回值就是<b>错的</b>——而它的用途正是 C-22 的模型一致性校验，
     * 返回错的档案名等于把校验悄悄关掉。故：
     * <ol>
     *   <li>优先取 {@code rag_chunk} <b>实际引用</b>的档案（库里的既成事实）；</li>
     *   <li>若出现多个不同档案（半个索引是 A 模型、半个是 B 模型），
     *       <b>抛异常而不是挑一个</b>——这正是必须全量重建的场景；</li>
     *   <li>库里还没有向量数据时，才退回档案表；此时若有多条未被引用的档案，
     *       同样抛异常而不是猜。</li>
     * </ol>
     *
     * @return 档案名；两张表都没有记录时返回 {@code null}
     * @throws IllegalStateException 存在多个互不相同的档案，无法给出唯一口径
     */
    public String selectProfileName() {
        List<String> used = jdbcTemplate.queryForList(
                "SELECT DISTINCT profile_name FROM rag_chunk"
                        + " WHERE profile_name IS NOT NULL ORDER BY profile_name",
                String.class);
        if (used.size() > 1) {
            throw new IllegalStateException(
                    "rag_chunk 里同时存在多个索引档案 " + used + "：说明索引由不同模型/切分策略混批产出，"
                            + "查询侧无法确定该用哪个模型编码。按 C-22 配套硬约束，必须全量重建索引。");
        }
        if (used.size() == 1) {
            return used.get(0);
        }
        List<String> all = jdbcTemplate.queryForList(
                "SELECT profile_name FROM rag_index_profile ORDER BY profile_name", String.class);
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() > 1) {
            throw new IllegalStateException(
                    "rag_chunk 里还没有向量，但 rag_index_profile 里有 " + all.size()
                            + " 条档案 " + all + "，无法判断该用哪一条；"
                            + "请先完成一次入库（写入的 chunk 会指明实际使用的档案）。");
        }
        return all.get(0);
    }

    /**
     * 为"带过滤条件的向量检索"配置本会话的 HNSW 参数。
     *
     * <p><b>⚠️ 必须与后续查询在同一个连接/会话上执行</b>——这是本方法存在的全部理由，
     * 也是它<b>不能</b>被写成"启动时执行一次"的原因：
     * <ul>
     *   <li>{@code hnsw.iterative_scan} / {@code hnsw.max_scan_tuples} 是<b>会话级 GUC</b>，
     *       不是索引属性（V3 文件第 43–44 行）；</li>
     *   <li>这两个参数<b>只在已加载 {@code vector} 扩展的会话里存在</b>——
     *       在一个没执行过任何 vector 相关语句的新会话里 {@code SET} 看似成功、
     *       {@code SHOW} 却报 {@code unrecognized configuration parameter}
     *       （本机实测，见 {@code docs/进度看板.md} §4 坑 3）；</li>
     *   <li>用连接池时，{@code SET} 只作用于<b>借出的那一条连接</b>。
     *       若先 {@code SET} 再归还连接、随后又借一条新连接去查询，
     *       参数会丢失且<b>不报错</b>，查询会安静地少返回结果——
     *       正是 {@code docs/工程复盘/检索工程/HNSW索引过滤条件静默少返回.md} 记录的现象。</li>
     * </ul>
     *
     * <p>为什么需要它：HNSW 是近似索引，{@code LIMIT} 在索引扫描阶段就被满足，
     * 带 {@code WHERE} 过滤时可能只取回 {@code ef_search} 个近邻、过滤后返回行数
     * <b>少于 Top-K 且不报错</b>（知识库《RAG 向量索引算法和向量数据库》第 175–184 行）。
     * {@code iterative_scan = relaxed_order} 让 pgvector 继续扫描直到凑够 Top-K，
     * {@code max_scan_tuples} 给这次"多扫一点"设上限，避免成本失控
     * （pgvector 0.8.0+ 特性，须显式开启，默认关闭）。
     *
     * <p>用 {@code Statement} 批量执行而非两条独立 {@code execute()}：
     * 参数之间不允许出现"只设了一条"的中间态。
     *
     * <p><b>⚠️ 为什么前后各多一步（预热 + 读回校验）——这是刻意的，别删</b>：
     * PostgreSQL 对<b>带点号的自定义参数</b>是"来者不拒"的：在扩展尚未加载的会话里执行
     * {@code SET hnsw.iterative_scan = relaxed_order} 会<b>成功返回</b>，
     * 但它只是登记成一个占位符，pgvector 真正的 GUC <b>并没有被改</b>。
     * 结果是：命令没报错、查询照跑、只是<b>安静地少返回结果</b>——
     * 与"忘了 SET"完全同样的症状，却更难查（本机实测，见 docs/进度看板.md §4 坑 3）。
     * 故这里：① 先用一次真实的 vector 表达式把扩展库加载进本会话；
     * ② {@code SET} 之后用 {@code current_setting} 读回；
     * ③ 读到的值不对就<b>抛异常</b>，把"静默失效"变成"启动即失败"。
     */
    public void configureSessionForFilteredSearch() {
        jdbcTemplate.execute((ConnectionCallback<Void>) conn -> {
            try (Statement st = conn.createStatement()) {
                // ① 预热：向量类型的输入函数在 vector.so 里，用一次即触发 _PG_init，
                //     hnsw.* 这两个 GUC 才会真正存在。
                try (ResultSet rs = st.executeQuery("SELECT '[0]'::vector")) {
                    rs.next();
                }
                // ② 两条 SET 一次批量发出，避免"只生效一条"的中间态
                st.addBatch("SET hnsw.iterative_scan = relaxed_order");
                st.addBatch("SET hnsw.max_scan_tuples = 20000");
                st.executeBatch();
                // ③ 读回校验：SET 返回成功 ≠ 生效（见上）
                String mode;
                String maxTuples;
                try (ResultSet rs = st.executeQuery(
                        "SELECT current_setting('hnsw.iterative_scan'),"
                                + " current_setting('hnsw.max_scan_tuples')")) {
                    if (!rs.next()) {
                        throw new IllegalStateException("读取 hnsw 会话参数失败：查询没有返回行");
                    }
                    mode = rs.getString(1);
                    maxTuples = rs.getString(2);
                }
                if (!"relaxed_order".equals(mode) || !"20000".equals(maxTuples)) {
                    throw new IllegalStateException(
                            "HNSW 会话参数未真正生效：hnsw.iterative_scan=" + mode
                                    + "，hnsw.max_scan_tuples=" + maxTuples
                                    + "。若 SET 被当成了自定义占位参数，说明本会话尚未加载 vector 扩展；"
                                    + "此状态下带过滤的向量检索会**静默少返回结果**，故拒绝继续。");
                }
            }
            return null;
        });
    }
}
