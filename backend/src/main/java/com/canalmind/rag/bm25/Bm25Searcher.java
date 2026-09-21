package com.canalmind.rag.bm25;

import com.canalmind.rag.tokenize.ChineseTokenizer;
import com.canalmind.rag.tokenize.TokenizerProfile;
import com.canalmind.rag.tokenize.TokenizerProfileGuard;
import com.canalmind.rag.tokenize.TokenizerProfileGuard.TokenizerProfileMismatchException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * BM25 检索器（第 2 周第 4 项）：<b>查询分词 → 一条 SQL 打分 → 带溯源字段的命中</b>。
 *
 * <h2>本类不实现 BM25 公式</h2>
 *
 * <p>公式的唯一实现在 PostgreSQL 的 {@code rag_bm25_search}（V4 迁移，决策 C-25）。
 * 这里只做三件事：① 用**同一个分词器**把查询切成词元；② 传参调函数；
 * ③ 把结果映射成 {@link Bm25Hit}。
 *
 * <p>为什么刻意不在 Java 里再写一份：两份实现必然漂移，而"分数不一致"这种缺陷
 * 不会报错，只会让离线评测与线上排序对不上（本项目在"跨来源口径冲突"上已有教训，
 * 见 {@code docs/工程复盘/数据一致性/}）。SQL 侧的正确性由
 * {@code backend/scripts/verify_bm25_fixture.sql} 用**手算期望值**守住。
 *
 * <h2>索引与查询必须同一口径（V3 的 C-22 约束在关键词通道上的对应物）</h2>
 *
 * <p>{@link #ensureProfileMatches()} 会把库里的档案指纹与本地分词器比对
 * （分词器名/版本、切分模式、**词典 sha256**、停用词 sha256），不符即**拒绝查询**、
 * 不降级。原因：查询侧用新词典切词、库里词元是旧词典产的，
 * 结果不会报错——只会"部分词永远召不回"，这是最难查的一类问题。
 *
 * <p>校验结果**按实例缓存一次**是安全的：档案身份字段不可就地修改
 * （{@code rag_assert_token_profile} 会拒绝），要换身份只能新建档案名，
 * 而档案名就是本实例的 {@code profileName}。故一次校验在实例生命周期内恒真。
 */
public class Bm25Searcher {

    /**
     * 打分 SQL。显式写 {@code ORDER BY score DESC, chunk_id}：
     * 函数内部已经这样排（V4），这里再排一次是**显式契约**——
     * 否则将来函数改了排序，"并列名次"的稳定性就会悄悄变化。
     */
    static final String SEARCH_SQL = """
            SELECT chunk_id, doc_id, doc_title, chunk_type, article_no,
                   article_title, version, score, matched_terms
              FROM rag_bm25_search(?::text, ?::text[], ?::int, ?::text, ?::text, ?::text, ?::date)
             ORDER BY score DESC, chunk_id
            """;

    private static final String PROFILE_SQL = """
            SELECT profile_name, tokenizer_name, tokenizer_version, segment_mode,
                   dictionary_version, dictionary_sha256, dictionary_terms,
                   stopwords_sha256, stopwords_count, bm25_k1, bm25_b
              FROM rag_token_profile
             WHERE profile_name = ?
            """;

    private final JdbcTemplate jdbc;
    private final ChineseTokenizer tokenizer;
    private final String profileName;

    private volatile boolean profileVerified;

    public Bm25Searcher(JdbcTemplate jdbc, ChineseTokenizer tokenizer, String profileName) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName 不得为空");
        }
        this.profileName = profileName;
    }

    /** 查询词的分词结果（供 CLI/评测输出"为什么这么切"的现场证据）。 */
    public List<String> tokenizeQuery(String query) {
        return tokenizer.tokenize(query);
    }

    /** 档案名。 */
    public String profileName() {
        return profileName;
    }

    /**
     * 检索。
     *
     * <p>查询词切完为空（例如整句都是停用词/标点）时**直接返回空列表、不查库**：
     * 这不是异常，是"没有可匹配的信号"。数据库侧同样把空词表当 0 行处理
     * （V4 里已断言），两处口径一致。
     *
     * @throws TokenizerProfileMismatchException 索引侧档案与查询侧分词口径不一致
     */
    public List<Bm25Hit> search(String query, Bm25QueryOptions options)
            throws TokenizerProfileMismatchException {
        Objects.requireNonNull(options, "options 不得为空");
        List<String> terms = tokenizer.tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        ensureProfileMatches();
        // 去重后传参：SQL 内部也做了 DISTINCT，这里先去重可减少参数体积（长查询更明显）
        List<String> distinctTerms = terms.stream().distinct().toList();

        List<Bm25Hit> hits = jdbc.execute((ConnectionCallback<List<Bm25Hit>>) con -> {
            try (PreparedStatement ps = con.prepareStatement(SEARCH_SQL)) {
                ps.setString(1, profileName);
                ps.setArray(2, con.createArrayOf("text", distinctTerms.toArray(new String[0])));
                ps.setInt(3, options.topK());
                ps.setString(4, options.docId());
                ps.setString(5, options.docCategoryNo());
                ps.setString(6, options.chunkType());
                if (options.asOf() == null) {
                    ps.setNull(7, Types.DATE);
                } else {
                    ps.setObject(7, options.asOf(), Types.DATE);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Bm25Hit> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(new Bm25Hit(
                                rs.getString("chunk_id"),
                                rs.getString("doc_id"),
                                rs.getString("doc_title"),
                                rs.getString("chunk_type"),
                                rs.getString("article_no"),
                                rs.getString("article_title"),
                                rs.getString("version"),
                                rs.getDouble("score"),
                                rs.getInt("matched_terms")));
                    }
                    return list;
                }
            }
        });
        return hits == null ? List.of() : hits;
    }

    /** 从库里读档案（k1/b 以库为准——打分用的是库里的值，不是本地的默认值）。 */
    public TokenizerProfile profileFromDb() {
        try {
            return jdbc.queryForObject(PROFILE_SQL, (rs, rowNum) -> new TokenizerProfile(
                    rs.getString("profile_name"),
                    rs.getString("tokenizer_name"),
                    rs.getString("tokenizer_version"),
                    rs.getString("segment_mode"),
                    rs.getString("dictionary_version"),
                    rs.getString("dictionary_sha256"),
                    rs.getInt("dictionary_terms"),
                    rs.getString("stopwords_sha256"),
                    rs.getInt("stopwords_count"),
                    rs.getDouble("bm25_k1"),
                    rs.getDouble("bm25_b")), profileName);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                    "BM25 档案 " + profileName + " 不存在。请先运行 "
                            + "Bm25IngestCli 建档案并灌词元（它会把词典/停用词指纹一并写入）。", e);
        }
    }

    /**
     * 档案指纹与本地分词器一致性校验（首次调用时执行一次，之后走缓存）。
     *
     * @throws TokenizerProfileMismatchException 档案缺失或指纹不符
     */
    public void ensureProfileMatches() throws TokenizerProfileMismatchException {
        if (profileVerified) {
            return;
        }
        synchronized (this) {
            if (profileVerified) {
                return;
            }
            TokenizerProfile db = profileFromDb();
            // 用库里的 k1/b 构造本地档案：这两项以库为准（打分用的是库里的值），
            // 比对的是分词口径（分词器/模式/词典与停用词指纹），见 TokenizerProfileGuard
            TokenizerProfile local = tokenizer.profile(db.bm25K1(), db.bm25B());
            new TokenizerProfileGuard(db, local).verify();
            profileVerified = true;
        }
    }
}
