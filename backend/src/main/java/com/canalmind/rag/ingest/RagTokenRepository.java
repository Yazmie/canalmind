package com.canalmind.rag.ingest;

import com.canalmind.rag.tokenize.TokenizerProfile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code rag_token_profile} / {@code rag_chunk_term} / {@code rag_chunk_token_stat}
 * 的写入口（BM25 通道入库，第 2 周第 4 项）。
 *
 * <p>与 {@link RagChunkRepository} 同一套约定：既能被 Spring 注入，也能在命令行独立使用；
 * 刻意不写成 {@code @Repository} 组件——入库是离线批处理，不该在应用启动时多出一个
 * 依赖数据库的 bean（检索通道本周还没接进主链路）。
 *
 * <p><b>本类承担的三项"错了不报错、只静默出错"的防护</b>：
 * <ol>
 *   <li><b>档案身份先校验、再删旧数据</b>：{@code rag_assert_token_profile} 在
 *       身份变更时抛异常。顺序很关键——若先删后校验，一次失败的入库会把库里
 *       <b>可用的词元清空</b>，而异常信息只说"身份变了"，现场会非常误导。</li>
 *   <li><b>长度统计与倒排表必须同时写入**同一事务**</b>：{@code rag_chunk_term}
 *       的外键指向 {@code rag_chunk_token_stat}，缺失统计行会让 dl/avgdl 失真。
 *       外键能挡住"没有统计行的词元"，但挡不住"只写了一半就被中断"，
 *       故整批包在一个事务里。</li>
 *   <li><b>不变式「term_count = SUM(term_freq)」显式复核</b>：
 *       该不变式跨行，CHECK 约束表达不了（V4 迁移里已注明）。
 *       这里提供 {@link #countTermCountMismatches(String)}，
 *       由入库 CLI 调用并**在不为 0 时报错**，把它从"文档里的约定"变成"每次入库都验的事实"。</li>
 * </ol>
 */
public class RagTokenRepository {

    /** 倒排表单批行数：实测 541 个 chunk 约 6~7 万行词元，分批发送使日志与失败面更小。 */
    private static final int TERM_BATCH_SIZE = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public RagTokenRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** 倒排表的一行（入库前的内存表示）。 */
    public record TermRow(String chunkId, String term, int termFreq) {
    }

    /** 档案规模统计（口径来自 {@code rag_token_stats}，与《评测报告》共用）。 */
    public record TokenStats(
            long chunkCount,
            long distinctTerms,
            long totalTokens,
            double avgDocLength,
            int minDocLength,
            int maxDocLength) {

        String summary() {
            return String.format("chunk %d ｜ 词表 %d ｜ 词元总数 %d ｜ dl 均值 %.2f（min %d / max %d）",
                    chunkCount, distinctTerms, totalTokens, avgDocLength, minDocLength, maxDocLength);
        }
    }

    /** {@code ANALYZE} 之后的证据（证明"规划器知道表里有数据"）。 */
    public record AnalyzeEvidence(long termRelTuples, long statRelTuples) {
    }

    /**
     * 建档案或校验身份；返回 {@code created} / {@code unchanged}。
     *
     * @param indexedChunkCount 本次**实际进入倒排表**的 chunk 数，只作为**审计快照**存进
     *                          {@code chunk_count_at_ingest}（不参与打分；N/avgdl 一律查询时现算）。
     *                          ⚠️ 它与 {@code count(*) FROM rag_chunk} 可能不相等：
     *                          实测有 7 个 BODY chunk 的正文只剩表格占位标记、切不出词元，
     *                          它们不进 BM25 语料（否则会以 dl=0 拉低 avgdl）。
     */
    public String assertProfile(TokenizerProfile profile, int indexedChunkCount) {
        String result = jdbcTemplate.queryForObject("""
                SELECT rag_assert_token_profile(
                    ?::text, ?::text, ?::text, ?::text, ?::text, ?::text, ?::int,
                    ?::text, ?::int, ?::double precision, ?::double precision, ?::int, ?::text)
                """, String.class,
                profile.profileName(), profile.tokenizerName(), profile.tokenizerVersion(),
                profile.segmentMode(), profile.dictionaryVersion(), profile.dictionarySha256(),
                profile.dictionaryTerms(), profile.stopwordsSha256(), profile.stopwordsCount(),
                profile.bm25K1(), profile.bm25B(), indexedChunkCount, buildNote(profile));
        return result == null ? "unknown" : result;
    }

    private static String buildNote(TokenizerProfile profile) {
        return "由 Bm25IngestCli 写入；词典 " + profile.dictionaryVersion()
                + "（" + profile.dictionaryTerms() + " 词）；分词器 " + profile.tokenizerName()
                + " " + profile.tokenizerVersion() + "；模式 " + profile.segmentMode();
    }

    /**
     * 读出全部 chunk 的正文（{@code chunk_id → content}），按 chunk_id 排序。
     *
     * <p>用 {@code LinkedHashMap} 保序：入库顺序因此确定，两次运行的日志可以直接 diff。
     *
     * <p>⚠️ 只取 {@code content}，**不加** {@code article_title}/{@code chapter_path}。
     * 理由：向量通道（V3）编码的也是 {@code content}，两个通道的输入必须一致，
     * 否则第 4 周的"纯向量 vs 混合"消融会把"通道差异"和"输入差异"混在一起。
     * "把标题拼进检索文本"已登记为消融候选变量（见《评测报告》检索段）。
     */
    public Map<String, String> readChunkContents() {
        Map<String, String> contents = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT chunk_id, content FROM rag_chunk ORDER BY chunk_id", rs -> {
            contents.put(rs.getString("chunk_id"), rs.getString("content"));
        });
        return contents;
    }

    /** 当前档案在倒排表里的行数。 */
    public long countTermRows(String profileName) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_chunk_term WHERE profile_name = ?", Long.class, profileName);
        return n == null ? 0L : n;
    }

    /** 当前档案在长度统计表里的行数。 */
    public long countStatRows(String profileName) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_chunk_token_stat WHERE profile_name = ?", Long.class, profileName);
        return n == null ? 0L : n;
    }

    /**
     * 原子替换一个档案的全部词元：删旧 → 写长度统计 → 写倒排表（同一事务）。
     *
     * <p>顺序不可交换：{@code rag_chunk_term} 的外键指向 {@code rag_chunk_token_stat}，
     * 先删统计行会被外键挡住（这是好事——挡住的正是"统计与词元不同步"）。
     */
    public void replaceTokens(String profileName, Map<String, Integer> docLengths, List<TermRow> termRows) {
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM rag_chunk_term WHERE profile_name = ?", profileName);
            jdbcTemplate.update("DELETE FROM rag_chunk_token_stat WHERE profile_name = ?", profileName);

            List<Map.Entry<String, Integer>> stats = new ArrayList<>(docLengths.entrySet());
            jdbcTemplate.batchUpdate(
                    "INSERT INTO rag_chunk_token_stat (profile_name, chunk_id, term_count) VALUES (?, ?, ?)",
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            ps.setString(1, profileName);
                            ps.setString(2, stats.get(i).getKey());
                            ps.setInt(3, stats.get(i).getValue());
                        }

                        @Override
                        public int getBatchSize() {
                            return stats.size();
                        }
                    });

            for (int from = 0; from < termRows.size(); from += TERM_BATCH_SIZE) {
                int to = Math.min(from + TERM_BATCH_SIZE, termRows.size());
                List<TermRow> slice = termRows.subList(from, to);
                jdbcTemplate.batchUpdate(
                        "INSERT INTO rag_chunk_term (profile_name, chunk_id, term, term_freq) VALUES (?, ?, ?, ?)",
                        new BatchPreparedStatementSetter() {
                            @Override
                            public void setValues(PreparedStatement ps, int i) throws SQLException {
                                TermRow row = slice.get(i);
                                ps.setString(1, profileName);
                                ps.setString(2, row.chunkId());
                                ps.setString(3, row.term());
                                ps.setInt(4, row.termFreq());
                            }

                            @Override
                            public int getBatchSize() {
                                return slice.size();
                            }
                        });
            }
        });
    }

    /**
     * 复核不变式 {@code rag_chunk_token_stat.term_count = SUM(rag_chunk_term.term_freq)}。
     *
     * @return 不一致的 chunk 数（期望 0）
     */
    public long countTermCountMismatches(String profileName) {
        Long n = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM rag_chunk_token_stat s
                  LEFT JOIN (
                        SELECT chunk_id, sum(term_freq) AS total
                          FROM rag_chunk_term
                         WHERE profile_name = ?
                         GROUP BY chunk_id
                  ) t ON t.chunk_id = s.chunk_id
                 WHERE s.profile_name = ?
                   AND coalesce(t.total, 0) <> s.term_count
                """, Long.class, profileName, profileName);
        return n == null ? 0L : n;
    }

    /**
     * 批量写入后收集统计信息（与 {@link RagChunkRepository#analyzeChunkTable()} 同一理由：
     * <b>"表里有数据"不等于"规划器知道表里有数据"</b>，不 ANALYZE 会让 EXPLAIN 骗人）。
     *
     * <p>返回 {@code reltuples} 作为**证据**而不是"执行过就完事"：
     * 本项目实测过 {@code reltuples = -1} 这种"看起来正常、其实没有统计信息"的状态。
     */
    public AnalyzeEvidence analyzeTokenTables() {
        jdbcTemplate.execute("ANALYZE rag_chunk_term");
        jdbcTemplate.execute("ANALYZE rag_chunk_token_stat");
        return new AnalyzeEvidence(relTuples("rag_chunk_term"), relTuples("rag_chunk_token_stat"));
    }

    private long relTuples(String table) {
        Long v = jdbcTemplate.queryForObject(
                "SELECT reltuples::bigint FROM pg_class WHERE oid = ?::regclass", Long.class, table);
        return v == null ? -1L : v;
    }

    /** 档案规模统计（走 {@code rag_token_stats}，保证与 psql、评测脚本同口径）。 */
    public TokenStats stats(String profileName) {
        return jdbcTemplate.queryForObject("""
                SELECT chunk_count, distinct_terms, total_tokens,
                       avg_doc_length, min_doc_length, max_doc_length
                  FROM rag_token_stats(?::text)
                """, (rs, rowNum) -> new TokenStats(
                rs.getLong("chunk_count"),
                rs.getLong("distinct_terms"),
                rs.getLong("total_tokens"),
                rs.getDouble("avg_doc_length"),
                rs.getInt("min_doc_length"),
                rs.getInt("max_doc_length")), profileName);
    }

    /** 出现频次最高的 N 个词元（仅用于入库汇总与人工看语料特征）。 */
    public List<Map<String, Object>> topTerms(String profileName, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT term, count(*) AS df, sum(term_freq) AS tf
                  FROM rag_chunk_term
                 WHERE profile_name = ?
                 GROUP BY term
                 ORDER BY df DESC, term
                 LIMIT ?
                """, profileName, limit);
    }
}
