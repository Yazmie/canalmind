package com.canalmind.rag.bm25;

import com.canalmind.rag.cli.CliConsole;
import com.canalmind.rag.tokenize.ChineseTokenizer;
import com.canalmind.rag.tokenize.JiebaShippingTokenizer;
import com.canalmind.rag.tokenize.TokenizerProfileGuard.TokenizerProfileMismatchException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * BM25 通道检索命令行入口（第 2 周第 4 项）。
 *
 * <p>用途有二：① 人工核对"某条问题到底召回了什么、分数怎么来的"（这是写《评测报告》
 * 检索段与排查 badcase 的主要手段）；② 给第 5 项的 RRF 融合提供可对照的单通道结果。
 *
 * <p>用法：
 * <pre>
 *   # ⚠️ 中文查询一律用 --query-file（UTF-8 文件）。Windows 下 JVM 用 GBK 解码 argv，
 *   #    --query "过闸费怎么算" 会变成乱码，而乱码不会报错，只会"什么都召不回"。
 *   ... Bm25QueryCli --query-file docs/评测报告/probe-q1.txt --top-k 5
 *
 *   # 只看查询被切成了什么（排查"为什么召不回"的第一步）
 *   ... Bm25QueryCli --query-file q1.txt --tokens
 * </pre>
 *
 * <p><b>档案名的确定方式</b>（刻意不猜）：{@code --profile} 未给时列出库里的档案——
 * 只有唯一一个才自动采用；有多个则**报错并要求指定**，而不是挑"第一个"。
 * 这一条抄自 {@code RagChunkRepository.selectProfileName()} 的实测教训：
 * 库里存在历史遗留档案时，"按第一行取"会把一致性校验静默关掉。
 */
public final class Bm25QueryCli {

    private static final String DEFAULT_DB_HOST = "127.0.0.1";
    private static final String DEFAULT_DB_PORT = "5432";
    private static final String DEFAULT_DB_NAME = "canalmind";
    private static final String DEFAULT_DB_USER = "postgres";

    /** 制表符：探针行用它分隔"查询文本"与"该查询的词元"（用计算字符，不用转义字面量）。 */
    private static final char TAB = (char) 9;

    private Bm25QueryCli() {
    }

    public static void main(String[] args) {
        CliConsole.pinUtf8Stdout();
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            System.out.println("检索失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("  底层原因：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws IOException, TokenizerProfileMismatchException {
        Config cfg = parseArgs(args);
        if (cfg.help) {
            printUsage();
            return 0;
        }
        List<String> queries = cfg.readQueries();
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("查询为空：请用 --query-file <utf-8 文件>（推荐）或 --query <文本>");
        }

        DriverManagerDataSource ds = new DriverManagerDataSource(cfg.url(), cfg.user, cfg.password);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String profileName = cfg.profileName != null ? cfg.profileName : resolveSingleProfile(jdbc);

        ChineseTokenizer tokenizer = new JiebaShippingTokenizer(cfg.segmentMode);
        Bm25Searcher searcher = new Bm25Searcher(jdbc, tokenizer, profileName);
        // 先把档案指纹核对一遍并打印：查询侧与索引侧口径不一致时**拒绝查询**，不降级
        searcher.ensureProfileMatches();

        System.out.println("=== BM25 检索（第 2 周第 4 项）===");
        System.out.println("档案        ：" + searcher.profileFromDb().summary());
        System.out.println("查询条数    ：" + queries.size()
                + (queries.size() > 1 ? "（探针文件：每行一个查询，# 开头为注释）" : ""));

        Bm25QueryOptions options = new Bm25QueryOptions(
                cfg.topK, cfg.docId, cfg.category, cfg.chunkType, cfg.asOf);
        for (int qi = 0; qi < queries.size(); qi++) {
            String query = queries.get(qi);
            List<String> terms = searcher.tokenizeQuery(query);
            System.out.println();
            System.out.println("── 查询 " + (qi + 1) + "/" + queries.size() + " ──");
            System.out.println("查询        ：" + query.trim());
            System.out.println("查询词元    ：" + terms + "（共 " + terms.size() + " 个"
                    + (terms.isEmpty() ? " → 切不出词元，直接返回空结果（不查库）" : "") + "）");
            if (cfg.tokensOnly) {
                continue;
            }
            System.out.println("过滤条件    ：topK=" + cfg.topK
                    + " docId=" + orAll(cfg.docId) + " category=" + orAll(cfg.category)
                    + " chunkType=" + orAll(cfg.chunkType) + " asOf=" + orAll(cfg.asOf));
            long t0 = System.currentTimeMillis();
            List<Bm25Hit> hits = searcher.search(query, options);
            System.out.println("命中 " + hits.size() + " 条，耗时 " + (System.currentTimeMillis() - t0) + " ms"
                    + "（分数只在同一查询内可比）");
            int rank = 1;
            for (Bm25Hit hit : hits) {
                System.out.println(String.format("%2d. %s", rank++, hit.summary()));
            }
        }
        return 0;
    }

    private static String orAll(Object v) {
        return v == null ? "(不过滤)" : String.valueOf(v);
    }

    /**
     * 未指定 {@code --profile} 时确定档案名：唯一才自动采用，多个直接报错。
     */
    static String resolveSingleProfile(JdbcTemplate jdbc) {
        List<String> names = jdbc.queryForList(
                "SELECT profile_name FROM rag_token_profile ORDER BY profile_name", String.class);
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "库里没有任何 BM25 分词档案。请先运行 Bm25IngestCli 建档案并灌词元。");
        }
        if (names.size() > 1) {
            throw new IllegalStateException(
                    "库里有 " + names.size() + " 个 BM25 档案 " + names + "，无法确定用哪个，"
                            + "请显式指定 --profile。"
                            + "（刻意不取「第一个」：档案不同意味着词典/模式/参数不同，"
                            + "猜错会让分数失去可比性，而查询本身不会报错。）");
        }
        return names.get(0);
    }

    /** 配置。 */
    static final class Config {
        String rawUrl;
        String host;
        String port;
        String db;
        String user;
        String password;
        String segmentMode = JiebaShippingTokenizer.DEFAULT_SEGMENT_MODE;
        String profileName;
        String query;
        Path queryFile;
        int topK = 10;
        String docId;
        String category;
        String chunkType;
        LocalDate asOf;
        boolean tokensOnly;
        boolean help;

        String url() {
            return rawUrl != null ? rawUrl : "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }

        /**
         * 读出查询列表。
         *
         * <p>{@code --query-file} 的约定：UTF-8；**每行一个查询**；{@code #} 开头的行是注释；
         * 空行忽略。这样"探针文件"本身就能进 git 当评测资产
         * （{@code docs/评测报告/bm25-probes.txt}），而不是散落成多个临时文件。
         *
         * <p>允许探针行写成 {@code <查询文本> TAB <词元1 词元2 …>}：制表符之后的部分
         * <b>本入口忽略</b>，它是给 {@code backend/scripts/verify_bm25.py} 做"独立重算"用的。
         * 一份文件同时供两个工具读，就不存在"探针文件与重算输入不同步"的问题。
         */
        List<String> readQueries() throws IOException {
            if (queryFile == null) {
                return (query == null || query.isBlank()) ? List.of() : List.of(query);
            }
            List<String> queries = new ArrayList<>();
            for (String line : Files.readAllLines(queryFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int tab = trimmed.indexOf(TAB);
                queries.add(tab < 0 ? trimmed : trimmed.substring(0, tab).trim());
            }
            return queries;
        }
    }

    static Config parseArgs(String[] args) {
        Config cfg = new Config();
        cfg.host = CliConsole.envOr("POSTGRES_HOST", DEFAULT_DB_HOST);
        cfg.port = CliConsole.envOr("POSTGRES_PORT", DEFAULT_DB_PORT);
        cfg.db = CliConsole.envOr("POSTGRES_DB", DEFAULT_DB_NAME);
        cfg.user = CliConsole.envOr("POSTGRES_USER", DEFAULT_DB_USER);
        cfg.password = CliConsole.envOr("POSTGRES_PASSWORD", "");

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--url" -> cfg.rawUrl = next(args, ++i, arg);
                case "--host" -> cfg.host = next(args, ++i, arg);
                case "--port" -> cfg.port = next(args, ++i, arg);
                case "--db" -> cfg.db = next(args, ++i, arg);
                case "--user" -> cfg.user = next(args, ++i, arg);
                case "--password" -> cfg.password = next(args, ++i, arg);
                case "--segment-mode" -> cfg.segmentMode = next(args, ++i, arg);
                case "--profile" -> cfg.profileName = next(args, ++i, arg);
                case "--query" -> cfg.query = next(args, ++i, arg);
                case "--query-file" -> cfg.queryFile = Path.of(next(args, ++i, arg));
                case "--top-k" -> cfg.topK = Integer.parseInt(next(args, ++i, arg));
                case "--doc-id" -> cfg.docId = next(args, ++i, arg);
                case "--category" -> cfg.category = next(args, ++i, arg);
                case "--chunk-type" -> cfg.chunkType = next(args, ++i, arg);
                case "--as-of" -> cfg.asOf = LocalDate.parse(next(args, ++i, arg));
                case "--tokens" -> cfg.tokensOnly = true;
                case "--help", "-h" -> cfg.help = true;
                default -> throw new IllegalArgumentException("未知参数：" + arg + "（用 --help 看用法）");
            }
        }
        return cfg;
    }

    private static String next(String[] args, int i, String arg) {
        if (i >= args.length) {
            throw new IllegalArgumentException("参数 " + arg + " 缺少取值");
        }
        return args[i];
    }

    private static void printUsage() {
        System.out.println("""
                BM25 通道检索（第 2 周第 4 项）

                用法：
                  ... com.canalmind.rag.bm25.Bm25QueryCli --query-file <utf8文件> [选项]

                查询来源（二选一）：
                  --query-file <path>    UTF-8 文件：**每行一个查询**，# 开头为注释，空行忽略
                                         （探针文件即评测资产，见 docs/评测报告/bm25-probes.txt）
                  --query <text>         直接给一条文本（⚠️ 中文经 Windows argv 会被 GBK 解码成乱码）

                选项：
                  --top-k <n>            返回条数（默认 10）
                  --profile <name>       分词档案名（库里只有一个档案时可省略）
                  --segment-mode <m>     查询侧切分模式，必须与档案一致（默认 INDEX）
                  --doc-id <id>          限定文档
                  --category <no>        限定主题类编号（01–26）
                  --chunk-type <t>       限定 BODY / TABLE
                  --as-of <yyyy-MM-dd>   按生效期左闭右开 [from, to) 过滤
                  --tokens               只打印查询分词结果，不检索
                  --url/--host/--port/--db/--user/--password   数据库连接（默认读 $POSTGRES_*）
                  --help, -h             显示帮助

                退出码：0 成功；1 失败
                """);
    }
}
