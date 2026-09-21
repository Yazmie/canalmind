package com.canalmind.rag.ingest;

import com.canalmind.rag.bm25.Bm25Params;
import com.canalmind.rag.cli.CliConsole;
import com.canalmind.rag.tokenize.ChineseTokenizer;
import com.canalmind.rag.tokenize.JiebaShippingTokenizer;
import com.canalmind.rag.tokenize.ShippingDictionary;
import com.canalmind.rag.tokenize.StopwordList;
import com.canalmind.rag.tokenize.TokenizerProfile;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 通道入库命令行入口：<b>读 rag_chunk → 分词 → 写档案 + 长度统计 + 倒排表</b>
 * （第 2 周第 4 项，规格书第 629 行"BM25 通道 + 中文分词（自定义航运词典）"）。
 *
 * <p>用法（以 {@code --help} 为准）：
 * <pre>
 *   # 词典自检（不连库）：每条词典词必须能被完整切出
 *   java -Dfile.encoding=UTF-8 -cp &lt;classes:deps&gt; com.canalmind.rag.ingest.Bm25IngestCli --selftest
 *
 *   # 空跑：只读库、分词并报告，不写任何数据
 *   ... Bm25IngestCli --dry-run
 *
 *   # 正式入库
 *   ... Bm25IngestCli
 * </pre>
 *
 * <p><b>为什么默认端口是 5432，而同目录的 {@code PgVectorIngestCli} 是 55432</b>：
 * 55432 是早前用于验证的临时集群（已停），55432 那个默认值写于"正式业务库还没建好"的时候；
 * 现在词元必须与 {@code rag_chunk} 在**同一个库**（外键约束），而 chunk 落在正式库 5432
 * （见 docs/进度看板.md §3.3）。此处不改动已被验收的 PgVectorIngestCli，
 * 只在新入口采用正确默认值，并把差异写在注释里，避免"两个入口默认值不同"被当成 bug。
 *
 * <p>参数默认值来源优先级：命令行 &gt; 环境变量（{@code POSTGRES_*}）&gt; 写死的本机默认值。
 *
 * <p><b>⚠️ 中文不要用 {@code --profile} 之类的命令行参数传</b>：Windows 下 JVM 用系统
 * 代码页（实测 GBK）解码 argv，中文参数会变乱码。本入口因此不接收任何中文参数，
 * 需要检索时用 {@code Bm25QueryCli --query-file}（UTF-8 文件）。
 */
public final class Bm25IngestCli {

    private static final String DEFAULT_DB_HOST = "127.0.0.1";
    /** 正式业务库端口（见类注释：与 PgVectorIngestCli 的 55432 差异是有意的）。 */
    private static final String DEFAULT_DB_PORT = "5432";
    private static final String DEFAULT_DB_NAME = "canalmind";
    private static final String DEFAULT_DB_USER = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "";

    private Bm25IngestCli() {
    }

    public static void main(String[] args) {
        CliConsole.pinUtf8Stdout();
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            // 失败也要"可核对"：打印异常类型 + 消息 + 底层原因（与 PgVectorIngestCli 一致）
            System.out.println("入库失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("  底层原因：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) {
        Config cfg = parseArgs(args);
        if (cfg.help) {
            printUsage();
            return 0;
        }

        ChineseTokenizer tokenizer = new JiebaShippingTokenizer(cfg.segmentMode);
        TokenizerProfile profile = cfg.profileName == null
                ? tokenizer.profile(cfg.k1, cfg.b)
                : new TokenizerProfile(cfg.profileName, tokenizer.name(), tokenizer.version(),
                        tokenizer.segmentMode(), ShippingDictionary.VERSION, ShippingDictionary.sha256(),
                        ShippingDictionary.termCount(), StopwordList.sha256(), StopwordList.count(),
                        cfg.k1, cfg.b);

        printConfig(cfg, profile);

        if (cfg.selftest) {
            return runSelfTest(tokenizer) ? 0 : 1;
        }

        long t0 = System.currentTimeMillis();
        DriverManagerDataSource ds = new DriverManagerDataSource(cfg.url(), cfg.user, cfg.password);
        RagTokenRepository repo = new RagTokenRepository(ds);

        // ── 读 + 分词 ──────────────────────────────────────────────────────
        Map<String, String> contents = repo.readChunkContents();
        if (contents.isEmpty()) {
            throw new IllegalStateException(
                    "rag_chunk 里没有数据：BM25 词元必须与 chunk 一一对应。"
                            + "请先跑 PgVectorIngestCli（它会读语料、切分并写入 rag_chunk）。");
        }

        Map<String, Integer> docLengths = new LinkedHashMap<>();
        List<RagTokenRepository.TermRow> termRows = new ArrayList<>();
        List<String> unindexable = new ArrayList<>();
        List<String> unexplained = new ArrayList<>();
        long totalTokens = 0;
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            Map<String, Integer> freqs = tokenizer.termFrequencies(entry.getValue());
            int dl = 0;
            for (Map.Entry<String, Integer> f : freqs.entrySet()) {
                termRows.add(new RagTokenRepository.TermRow(entry.getKey(), f.getKey(), f.getValue()));
                dl += f.getValue();
            }
            if (dl == 0) {
                // 切不出词元 = 在 BM25 通道里**永远召不回**。这里分两种情况处理，
                // 因为它们的含义完全不同（详见 classifyEmptyChunk）：
                //   ① 正文只剩表格占位标记的退化 chunk（周 2 第 3 项切分产物的已知产物）
                //      → 它本来就是纯指针，语义内容在对应的 TABLE chunk 里，不算缺陷；
                //   ② 有实质文字却切不出词元 → 分词器/词典有 bug，必须停下。
                String reason = classifyEmptyChunk(entry.getValue());
                if (reason == null) {
                    unexplained.add(entry.getKey() + "（正文预览：" + preview(entry.getValue()) + "）");
                } else {
                    unindexable.add(entry.getKey() + "（" + reason + "）");
                }
                continue;   // 无词元的 chunk 不进 BM25 语料（否则会以 dl=0 拉低 avgdl）
            }
            docLengths.put(entry.getKey(), dl);
            totalTokens += dl;
        }

        System.out.println("分词完成：读取 chunk " + contents.size()
                + " ｜ 进入 BM25 语料 " + docLengths.size()
                + " ｜ 词元总数 " + totalTokens
                + " ｜ 倒排行数 " + termRows.size()
                + " ｜ 耗时 " + (System.currentTimeMillis() - t0) + " ms");

        if (!unindexable.isEmpty()) {
            System.out.println("无词元的 chunk（已排除，不影响正确性，但要能解释）：" + unindexable.size() + " 个");
            for (String u : unindexable.subList(0, Math.min(10, unindexable.size()))) {
                System.out.println("     - " + u);
            }
        }

        // 有实质文字却切不出词元 → 这是真正的缺陷（分词器/词典失效），必须停住。
        // 本项目的一贯取向：宁可在入库时红，也不要在检索时静悄悄漏。
        if (!unexplained.isEmpty()) {
            throw new IllegalStateException(
                    "有 " + unexplained.size() + " 个 chunk 含文字却切不出任何词元"
                            + "（说明分词器或词典失效，它们将永远召不回）："
                            + unexplained.subList(0, Math.min(5, unexplained.size())));
        }

        if (cfg.dryRun) {
            System.out.println("--dry-run：不写库。上面即为将要写入的规模。");
            System.out.println("  档案：" + profile.summary());
            return 0;
        }

        // ── 写 ────────────────────────────────────────────────────────────
        // ⚠️ 先校验档案身份、再删旧数据：若顺序反过来，一次身份不符的入库会先把
        // 库里可用的词元清空，异常信息却只说"身份变了"，现场极具误导性。
        long before = repo.countTermRows(profile.profileName());
        String action = repo.assertProfile(profile, docLengths.size());
        System.out.println("档案 " + profile.profileName() + "：" + action
                + "（写入前倒排行数 " + before + "，本次语料 " + docLengths.size() + " 个 chunk）");

        repo.replaceTokens(profile.profileName(), docLengths, termRows);
        long after = repo.countTermRows(profile.profileName());
        System.out.println("写入完成：倒排行数 " + before + " → " + after);

        RagTokenRepository.AnalyzeEvidence evidence = repo.analyzeTokenTables();
        System.out.println("ANALYZE 完成：rag_chunk_term reltuples=" + evidence.termRelTuples()
                + "，rag_chunk_token_stat reltuples=" + evidence.statRelTuples());

        long mismatches = repo.countTermCountMismatches(profile.profileName());
        if (mismatches != 0) {
            throw new IllegalStateException(
                    "不变式被破坏：有 " + mismatches + " 个 chunk 的 term_count ≠ SUM(term_freq)。"
                            + "该不变式跨行、CHECK 约束表达不了（见 V4 迁移注释），"
                            + "但它坏了就意味着 BM25 的 dl/avgdl 用的是错的值——必须修，不能继续。");
        }
        System.out.println("不变式复核：term_count = SUM(term_freq) 全部一致（0 处不符）");

        RagTokenRepository.TokenStats stats = repo.stats(profile.profileName());
        System.out.println("档案统计：" + stats.summary());
        System.out.println("高频词元 Top-" + cfg.topTerms + "（df = 出现在多少个 chunk 里）：");
        for (Map<String, Object> row : repo.topTerms(profile.profileName(), cfg.topTerms)) {
            System.out.println("  " + row.get("term") + "  df=" + row.get("df") + "  tf=" + row.get("tf"));
        }
        System.out.println("总耗时 " + (System.currentTimeMillis() - t0) + " ms");
        return 0;
    }

    /**
     * 判断"零词元 chunk"能否被解释。
     *
     * @return {@code null} 表示**无法解释**（含实质文字却切不出词元 → 视为缺陷）；
     *         否则返回可读的原因
     */
    static String classifyEmptyChunk(String content) {
        String leftover = JiebaShippingTokenizer.stripTableMarkers(content).trim();
        if (leftover.isEmpty()) {
            return "正文只有表格占位标记（切分退化产物，语义内容在对应的 TABLE chunk 里）";
        }
        // 有字母/数字/汉字残留却切不出词元 → 分词器或词典失效，这是缺陷
        for (int i = 0; i < leftover.length(); i++) {
            if (Character.isLetterOrDigit(leftover.charAt(i))) {
                return null;
            }
        }
        return "正文只有标点/符号（无词可切）";
    }

    private static String preview(String content) {
        String stripped = JiebaShippingTokenizer.stripTableMarkers(content).trim();
        return stripped.length() <= 40 ? stripped : stripped.substring(0, 40) + "…";
    }

    /**
     * 词典自检：**每条词典词都必须被完整切出来**。
     *
     * <p>为什么这条断言是词典正确性的关键：jieba-analysis 的词频若退回默认值
     * （写成三列格式时的静默失效，见 {@link ShippingDictionary}），该词就会被
     * 切碎——"切碎了"不等于报错，只等于词典形同不存在。本断言是**唯一**能发现它的东西。
     *
     * <p>断言写的是"词元列表里**包含**该词"而不是"只有一个词元"：
     * INDEX 模式会额外输出"本身也在词典里"的子词（这是本模式的既定行为），
     * 例如"危险货物"会同时产出"危险/货物"。要求"只有一个词元"会把正常行为判成失败。
     */
    static boolean runSelfTest(ChineseTokenizer tokenizer) {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String word : ShippingDictionary.words()) {
            checked++;
            if (StopwordList.contains(word)) {
                failures.add(word + " → 同时被列进停用词表，永远不会被输出（配置冲突）");
                continue;
            }
            List<String> tokens = tokenizer.tokenize(word);
            if (!tokens.contains(word)) {
                failures.add(word + " → 切成了 " + tokens);
            }
        }
        System.out.println("词典自检：" + ShippingDictionary.termCount() + " 词，实检 " + checked
                + "，失败 " + failures.size());
        if (failures.isEmpty()) {
            System.out.println("  ✅ 每条词典词都能被完整切出（词频生效，词典没有静默失效）");
            return true;
        }
        System.out.println("  ❌ 以下词典词未被完整切出（词典很可能没生效）：");
        for (String f : failures.subList(0, Math.min(20, failures.size()))) {
            System.out.println("     - " + f);
        }
        return false;
    }

    // ── 参数 ──────────────────────────────────────────────────────────────

    /** 配置。 */
    static final class Config {
        /** 整条 JDBC URL（给了它与 host/port/db 二选一，避免两套写法互相打架）。 */
        String rawUrl;
        String host;
        String port;
        String db;
        String user;
        String password;
        String segmentMode = JiebaShippingTokenizer.DEFAULT_SEGMENT_MODE;
        String profileName;
        double k1 = Bm25Params.LUCENE_K1;
        double b = Bm25Params.LUCENE_B;
        int topTerms = 10;
        boolean dryRun;
        boolean selftest;
        boolean help;

        String url() {
            return rawUrl != null ? rawUrl : "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }
    }

    static Config parseArgs(String[] args) {
        Config cfg = new Config();
        cfg.host = CliConsole.envOr("POSTGRES_HOST", DEFAULT_DB_HOST);
        cfg.port = CliConsole.envOr("POSTGRES_PORT", DEFAULT_DB_PORT);
        cfg.db = CliConsole.envOr("POSTGRES_DB", DEFAULT_DB_NAME);
        cfg.user = CliConsole.envOr("POSTGRES_USER", DEFAULT_DB_USER);
        cfg.password = CliConsole.envOr("POSTGRES_PASSWORD", DEFAULT_DB_PASSWORD);

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
                case "--k1" -> cfg.k1 = Double.parseDouble(next(args, ++i, arg));
                case "--b" -> cfg.b = Double.parseDouble(next(args, ++i, arg));
                case "--top-terms" -> cfg.topTerms = Integer.parseInt(next(args, ++i, arg));
                case "--dry-run" -> cfg.dryRun = true;
                case "--selftest" -> cfg.selftest = true;
                case "--help", "-h" -> cfg.help = true;
                default -> throw new IllegalArgumentException(
                        "未知参数：" + arg + "（用 --help 看用法。⚠️ 不要用命令行传中文，"
                                + "Windows 会用 GBK 解码 argv 导致乱码）");
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

    private static void printConfig(Config cfg, TokenizerProfile profile) {
        System.out.println("=== BM25 通道入库（第 2 周第 4 项）===");
        System.out.println("数据库      ：" + cfg.url()
                + "（用户 " + cfg.user + "，口令 " + (cfg.password.isEmpty() ? "空" : "已设置（不打印）") + "）");
        System.out.println("分词档案    ：" + profile.summary());
        System.out.println("词典文件    ：" + ShippingDictionary.RESOURCE_PATH
                + "（sha256 " + ShippingDictionary.sha256() + "）");
        System.out.println("停用词文件  ：" + StopwordList.RESOURCE_PATH
                + "（sha256 " + StopwordList.sha256() + "）");
        System.out.println("模式        ：" + (cfg.dryRun ? "dry-run（不写库）" : "正式入库")
                + (cfg.selftest ? " ｜ 只做词典自检" : ""));
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("""
                BM25 通道入库（第 2 周第 4 项）——读 rag_chunk，分词后写档案 + 长度统计 + 倒排表

                用法：
                  ... com.canalmind.rag.ingest.Bm25IngestCli [选项]

                选项：
                  --url <jdbc-url>       整条 JDBC URL（覆盖 host/port/db）
                  --host <h>             数据库主机（默认 $POSTGRES_HOST 或 127.0.0.1）
                  --port <p>             数据库端口（默认 $POSTGRES_PORT 或 5432，正式业务库）
                  --db <name>            数据库名（默认 $POSTGRES_DB 或 canalmind）
                  --user <u>             数据库用户（默认 $POSTGRES_USER 或 postgres）
                  --password <p>         数据库口令（默认 $POSTGRES_PASSWORD 或空）
                  --segment-mode <m>     INDEX（默认）| SEARCH，对应 jieba 的 SegMode
                  --profile <name>       覆盖档案名（默认 jieba:<词典版本>:<模式>）
                  --k1 <v>               BM25 k1（默认 1.2 = Lucene 默认）
                  --b <v>                BM25 b（默认 0.75 = Lucene 默认）
                  --top-terms <n>        汇总里打印的高频词元条数（默认 10）
                  --selftest             只做词典自检（每条词典词必须被完整切出），不连库
                  --dry-run              只读库 + 分词 + 报告，不写任何数据
                  --help, -h             显示帮助

                退出码：0 成功；1 失败（失败信息含异常类型与底层原因）
                """);
    }
}
