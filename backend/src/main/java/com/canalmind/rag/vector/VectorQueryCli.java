package com.canalmind.rag.vector;

import com.canalmind.rag.cli.CliConsole;
import com.canalmind.rag.embed.EmbeddingClient;
import com.canalmind.rag.embed.EmbeddingClient.EmbeddingException;
import com.canalmind.rag.embed.EmbeddingClientFactory;
import com.canalmind.rag.embed.EmbeddingModelGuard.EmbeddingModelMismatchException;
import com.canalmind.rag.embed.EmbeddingModelInfo;
import com.canalmind.rag.embed.VectorCodec;
import com.canalmind.rag.ingest.RagChunkRepository;
import com.canalmind.rag.vector.VectorResultJson.QueryHits;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 向量通道检索命令行入口（第 2 周第 5 项）。
 *
 * <p>用途有三：① 人工核对"某条问题在纯向量通道下召回了什么、相似度是多少"
 * （写《评测报告》检索段与排查 badcase 的主要手段）；
 * ② 给第 4 周的消融（纯向量 / 纯 BM25 / 混合 + RRF）提供<b>可对照的单通道结果</b>——
 * 它是"纯向量检索"这一档的抓手；③ 用 {@code --out} 产出确定性 JSON，
 * 作为评测基线资产（无时间戳、UTF-8 无 BOM、仅 LF，可字节级 diff）。
 *
 * <p>用法（以 {@code --help} 为准）：
 * <pre>
 *   # ⚠️ 中文查询一律用 --query-file（UTF-8 文件）。Windows 下 JVM 用 GBK 解码 argv，
 *   #    --query "过闸费怎么算" 会变成乱码，而乱码不会报错，只会"什么都召不回"。
 *   ... VectorQueryCli --query-file docs/评测报告/vector-probes.txt --top-k 5
 *
 *   # 评测基线：写 JSON 产物（stdout 同时打人类可读摘要）
 *   ... VectorQueryCli --query-file probes.txt --top-k 30 --out docs/评测报告/vector-baseline.json
 *
 *   # 只检查 embed 客户端可用（不连库）
 *   ... VectorQueryCli --selftest
 * </pre>
 *
 * <p><b>档案名的确定方式</b>（刻意不猜）：{@code --profile} 未给时，
 * 复用 {@link RagChunkRepository#selectProfileName()}——它取 {@code rag_chunk}
 * <b>实际引用</b>的档案（库里的既成事实），而不是 {@code rag_index_profile} 的第一行。
 * 本机实测过那条教训：档案表里存在一条 0 行 chunk 引用的历史档案，
 * 按"第一行取"会把 C-22 的模型一致性校验<b>静默关掉</b>。
 *
 * <p><b>为什么查询侧模型身份走 {@link EmbeddingClientFactory}</b>：
 * 入库 CLI 与查询 CLI 必须解析出<b>完全相同</b>的模型三元组（C-22 配套硬约束）。
 * 两处各读一遍环境变量，早晚出现"入库读的是 A、查询读的是 B"——
 * 而这种不一致不会报错，只会让相似度全错。集中到工厂一处，两侧就不可能分叉。
 *
 * <p>⚠️ <b>不引入</b>任何"用 {@code psql} 子进程去核对"的做法：本机实测（见
 * {@code backend/scripts/verify_bm25.py} 的注释）中文经命令行参数会被控制台代码页改坏，
 * 且子进程读取口令需要额外的控制台/环境变量处理。本入口一律走 JDBC，中文只从 UTF-8 文件来。
 */
public final class VectorQueryCli {

    private static final String DEFAULT_DB_HOST = "127.0.0.1";
    private static final String DEFAULT_DB_PORT = "5432";
    private static final String DEFAULT_DB_NAME = "canalmind";
    private static final String DEFAULT_DB_USER = "postgres";

    /** 默认 Top-K：与 {@code rag_bm25_search} 的默认值（30）对齐，两通道对照时口径一致。 */
    static final int DEFAULT_TOP_K = 30;

    /** 制表符：探针行用它分隔"查询文本"与"该行的附加信息"（用计算字符，不用转义字面量）。 */
    private static final char TAB = (char) 9;

    /**
     * UTF-8 BOM（U+FEFF）。
     *
     * <p>用计算字符而不是 unicode 转义：escape 会被 javac 在<b>词法阶段</b>就替换掉，
     * 写在注释里都会变成真字符，给后续编辑留下一个看不见的坑。
     *
     * <p>为什么要处理它：探针文件若由 Windows 记事本或某些编辑器存成"UTF-8 带 BOM"，
     * 第一行开头的 BOM 会跟着查询文本一起进 embedding——BOM 是最不可见的字符，
     * 结果是"第一条查询莫名召不回"，而且完全看不出原因。
     */
    private static final char BOM = (char) 0xFEFF;

    /**
     * 自检探针文本。
     *
     * <p>刻意写成源码常量而不是命令行参数：中文经 argv 会被控制台代码页改坏
     * （同一个坑，在全项目反复记了三处），自检本身不该引入这个变量。
     */
    static final String SELFTEST_PROBE = "自检探针：过闸费怎么算";

    /** 仅换行：把多行 SQL 压成一行打印时用（用计算字符，不用转义字面量）。 */
    private static final String LF = String.valueOf((char) 10);

    private VectorQueryCli() {
    }

    public static void main(String[] args) {
        CliConsole.pinUtf8Stdout();
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            // 失败也要"可核对"：打印异常类型 + 消息；若包装了原因（如 SQLException）一并打印，
            // 否则最有用的一句话会藏在 cause 里（与其余 CLI 一致）。
            System.out.println("向量检索失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("  底层原因：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            code = 1;
        }
        System.exit(code);
    }

    static int run(String[] args) throws EmbeddingException, EmbeddingModelMismatchException, IOException {
        Config cfg = parseArgs(args);
        if (cfg.help) {
            printUsage();
            return 0;
        }

        EmbeddingClient client = cfg.embeddingClient();

        if (cfg.selftest) {
            return runSelfTest(cfg, client);
        }

        List<String> queries = cfg.readQueries();
        if (queries.isEmpty()) {
            throw new IllegalArgumentException(
                    "查询为空：请用 --query-file <UTF-8 文件>（推荐）或 --query <文本>");
        }

        DriverManagerDataSource ds = new DriverManagerDataSource(cfg.url(), cfg.user, cfg.password);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String profileName = cfg.profileName != null
                ? cfg.profileName
                : new RagChunkRepository(jdbc).selectProfileName();
        if (profileName == null) {
            throw new IllegalStateException(
                    "库里没有任何向量索引档案（rag_index_profile 与 rag_chunk 都没有记录）。"
                            + "请先运行 PgVectorIngestCli 完成「切分 + embedding + 入库」。");
        }

        VectorSearcher searcher = new VectorSearcher(jdbc, client, profileName, cfg.metric);
        // 先把档案身份核对一遍：库里的模型/维度与当前查询侧不一致时**拒绝查询**，不降级
        searcher.ensureProfileMatches();

        printBanner(cfg, searcher, queries.size());

        VectorQueryOptions options = cfg.options;
        List<QueryHits> results = new ArrayList<>(queries.size());
        for (int qi = 0; qi < queries.size(); qi++) {
            String query = queries.get(qi);
            System.out.println();
            System.out.println("── 查询 " + (qi + 1) + "/" + queries.size() + " ──");
            System.out.println("查询        ：" + query);
            long t0 = System.currentTimeMillis();
            List<VectorHit> hits = searcher.search(query, options);
            long ms = System.currentTimeMillis() - t0;
            System.out.println("命中 " + hits.size() + " 条（请求 Top-" + options.topK()
                    + "），耗时 " + ms + " ms"
                    + (hits.size() < options.topK()
                            ? "  ← 少于请求条数：候选集只有这么多（不是被截断，见过滤条件）" : ""));
            int rank = 1;
            for (VectorHit hit : hits) {
                System.out.println(String.format(Locale.ROOT, "%2d. %s", rank++, hit.summary()));
            }
            results.add(new QueryHits(query, hits));
        }

        if (cfg.out != null) {
            String json = VectorResultJson.render(searcher.profileName(), options.topK(), results);
            VectorResultJson.write(cfg.out, json);
            System.out.println();
            System.out.println("已写出 JSON：" + cfg.out.toAbsolutePath());
            System.out.println("字节数      ：" + Files.size(cfg.out.toAbsolutePath())
                    + "（UTF-8 无 BOM + 仅 LF，无时间戳 → 可字节级复现）");
        }
        return 0;
    }

    private static void printBanner(Config cfg, VectorSearcher searcher, int queryCount) {
        VectorSearcher.ProfileMetadata db = searcher.profileFromDb();
        System.out.println("=== 向量检索（第 2 周第 5 项，纯向量通道）===");
        System.out.println("索引档案    ：" + (db == null ? "(不存在)" : db.summary()));
        System.out.println("查询侧模型  ：" + searcher.queryModelInfo().profileName()
                + "（来自 $OLLAMA_* 或默认值；与档案一致才允许查询）");
        System.out.println("距离度量    ：" + cfg.metric.cliName() + "（" + cfg.metric.operator() + "）"
                + "，score = " + (cfg.metric == VectorSearcher.Metric.COSINE
                        ? "1 - (embedding <=> q)"
                        : "-(embedding <#> q)")
                + " = 余弦相似度，越大越相关");
        if (cfg.metric.usesHnswIndex()) {
            System.out.println("              走 HNSW 索引（vector_ip_ops）：结果为**近似** KNN；"
                    + "已确认本会话 hnsw.iterative_scan 生效，避免过滤后静默少返回");
        } else {
            System.out.println("              ⚠️ 该算子与 HNSW 的 vector_ip_ops 不匹配 → 走**精确扫描**："
                    + "过滤后不会少返回（评测基线要的正是这个）；"
                    + "需要走索引的证据请用 --metric ip");
        }
        System.out.println("过滤条件    ：topK=" + cfg.topK
                + " docId=" + orAll(cfg.docId) + " category=" + orAll(cfg.category)
                + " chunkType=" + orAll(cfg.chunkType) + " asOf=" + orAll(cfg.asOf)
                + "（asOf 按左闭右开 [effective_from, effective_to) 过滤）");
        System.out.println("查询条数    ：" + queryCount
                + (queryCount > 1 ? "（探针文件：每行一个查询，# 开头为注释）" : ""));
    }

    private static String orAll(Object v) {
        return v == null ? "(不过滤)" : String.valueOf(v);
    }

    /**
     * 自检：<b>不连库</b>，只验"查询侧这一半"能不能用。
     *
     * <p>检查四项，全部是"错了不会报错、只会在检索时表现为召回变差"的地方：
     * <ol>
     *   <li>配置解析出的模型身份与维度（打出来，供人工核对与库里的档案对齐）；</li>
     *   <li>embed 客户端真的可用：发一条探针文本，要求返回 <b>1024 维</b>且<b>已 L2 归一化</b>
     *       （未归一化会让内积口径的排序按模长偏斜）；</li>
     *   <li>将要执行的 SQL 形状（含算子、as_of 左闭右开、{@code embedding IS NOT NULL}）；</li>
     *   <li>选项校验：topK/chunkType 的拒绝规则真的生效。</li>
     * </ol>
     * <p>刻意<b>不</b>连库：这一步的用途是"换机器/换模型后先确认网络与配置"，
     * 而库端的一致性校验属于 {@code ensureProfileMatches()} 的职责，混在一起会让
     * "Ollama 没起来"与"档案不匹配"两个原因无法区分。
     */
    static int runSelfTest(Config cfg, EmbeddingClient client) {
        System.out.println("=== 向量检索自检（不连库）===");
        EmbeddingModelInfo info = client.modelInfo();
        System.out.println("查询侧模型  ：" + info.profileName()
                + "（provider=" + info.provider() + " model=" + info.model()
                + " dim=" + info.dimension() + "）");
        System.out.println("探针文本    ：" + SELFTEST_PROBE);

        List<String> failures = new ArrayList<>();
        try {
            float[] v = client.embedOne(SELFTEST_PROBE);
            if (v == null || v.length != info.dimension()) {
                failures.add("embedding 返回维度 " + (v == null ? -1 : v.length)
                        + "，应为 " + info.dimension());
            } else {
                double norm = VectorCodec.norm(v);
                System.out.println("探针向量    ：" + v.length + " 维，L2 范数 "
                        + String.format(Locale.ROOT, "%.6f", norm));
                if (!VectorCodec.isL2Normalized(v, 1e-3)) {
                    failures.add("探针向量 L2 范数 " + norm + " 偏离 1.0 超过 1e-3："
                            + "内积口径（--metric ip）的排序会按模长偏斜且不报错");
                }
            }
        } catch (Exception e) {
            failures.add("调用 embedding 失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage());
        }

        String sql = VectorSearcher.searchSql(cfg.metric);
        for (String required : List.of("c.embedding " + cfg.metric.operator() + " ?::vector",
                "c.embedding IS NOT NULL",
                "c.effective_from <= ?::date",
                "c.effective_to > ?::date",
                "LIMIT ?")) {
            if (!sql.contains(required)) {
                failures.add("生成的 SQL 缺少片段：" + required);
            }
        }
        System.out.println("检索 SQL    ：" + sql.strip().replace(LF, " ⏎ "));

        if (cfg.options.topK() != cfg.topK) {
            failures.add("选项 topK 与配置不一致");
        }
        System.out.println("选项校验    ：topK=" + cfg.options.topK() + " 通过"
                + (cfg.chunkType == null ? "" : "，chunkType=" + cfg.chunkType));

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("自检结论    ：✅ embed 客户端可用、向量已归一化、SQL 形状正确（库端一致性未校验）");
            return 0;
        }
        System.out.println("自检结论    ：❌ 失败 " + failures.size() + " 项：");
        for (String f : failures) {
            System.out.println("   - " + f);
        }
        return 1;
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
        String profileName;
        String query;
        Path queryFile;
        int topK = DEFAULT_TOP_K;
        String docId;
        String category;
        String chunkType;
        LocalDate asOf;
        Path out;
        VectorSearcher.Metric metric = VectorSearcher.Metric.COSINE;
        /** Ollama 地址/模型覆盖（默认取 $OLLAMA_*，见 {@link #embeddingClient()}）。 */
        String ollamaUrl;
        String ollamaModel;
        boolean selftest;
        boolean help;

        /**
         * 解析后的选项对象。
         *
         * <p>在 {@link #parseArgs} 末尾构造一次，把「参数非法」（topK ≤ 0、chunkType 非 BODY/TABLE）
         * <b>提前到解析阶段</b>失败：否则要等到连库、调完 embedding 才报错，
         * 而那时用户已经等了一次网络往返。
         */
        VectorQueryOptions options;

        String url() {
            return rawUrl != null ? rawUrl : "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }

        /**
         * 按 {@link EmbeddingClientFactory} 的同一套键构造查询侧客户端。
         *
         * <p>命令行覆盖通过"临时环境变量映射"注入，而不是另写一条构造分支——
         * 这样"默认值/校验规则"只有工厂一处（换模型、改维度校验都只改那里），
         * 也顺带继承了工厂对维度的强校验（必须等于向量列的 1024）。
         */
        EmbeddingClient embeddingClient() {
            Map<String, String> env = new HashMap<>(System.getenv());
            if (ollamaUrl != null) {
                env.put("OLLAMA_BASE_URL", ollamaUrl);
            }
            if (ollamaModel != null) {
                env.put("OLLAMA_EMBEDDING_MODEL", ollamaModel);
            }
            return EmbeddingClientFactory.fromEnvironment(env);
        }

        /**
         * 读出查询列表。
         *
         * <p>{@code --query-file} 的约定（与 {@code Bm25QueryCli} <b>完全一致</b>，
         * 好让同一份探针文件能同时喂两个通道做对照）：UTF-8；<b>每行一个查询</b>；
         * {@code #} 开头的行是注释；空行忽略。
         *
         * <p>允许探针行写成 {@code <查询文本> TAB <该行的附加信息>}：制表符之后的部分
         * <b>本入口忽略</b>，留给下游脚本（例如标注期望 chunk_id 的评测集）使用。
         * 一份文件同时供多个工具读，就不存在"探针文件与脚本输入不同步"的问题。
         *
         * <p>两个 {@code --query-file} / {@code --query} 都给了时以 <b>文件为准</b>：
         * 命令行里那句中文注定是乱码（Windows GBK 解码 argv），
         * 与其把乱码当查询发出去，不如让文件赢。
         */
        List<String> readQueries() throws IOException {
            if (queryFile == null) {
                return (query == null || query.isBlank()) ? List.of() : List.of(query);
            }
            List<String> queries = new ArrayList<>();
            for (String line : Files.readAllLines(queryFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                // 只会在第一行出现 BOM，但这里逐行判：写成"仅首行"是多余的状态，
                // 而漏掉它会让第一条查询带着一个看不见的字符去编码。
                if (!trimmed.isEmpty() && trimmed.charAt(0) == BOM) {
                    trimmed = trimmed.substring(1).trim();
                }
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
                case "--profile" -> cfg.profileName = next(args, ++i, arg);
                case "--query" -> cfg.query = next(args, ++i, arg);
                case "--query-file" -> cfg.queryFile = Path.of(next(args, ++i, arg));
                case "--out" -> cfg.out = Path.of(next(args, ++i, arg));
                case "--top-k" -> cfg.topK = parseTopK(next(args, ++i, arg));
                case "--doc-id" -> cfg.docId = next(args, ++i, arg);
                case "--category" -> cfg.category = next(args, ++i, arg);
                case "--chunk-type" -> cfg.chunkType = next(args, ++i, arg);
                case "--as-of" -> cfg.asOf = parseAsOf(next(args, ++i, arg));
                case "--metric" -> cfg.metric = VectorSearcher.Metric.fromCli(next(args, ++i, arg));
                case "--ollama-url" -> cfg.ollamaUrl = next(args, ++i, arg);
                case "--model" -> cfg.ollamaModel = next(args, ++i, arg);
                case "--selftest" -> cfg.selftest = true;
                case "--help", "-h" -> {
                    cfg.help = true;
                    return cfg;
                }
                default -> throw new IllegalArgumentException(
                        "未知参数：" + arg + "（用 --help 看用法。⚠️ 不要用命令行传中文："
                                + "Windows 会用代码页解码 argv，中文查询请写进 UTF-8 文件用 --query-file）");
            }
        }
        // 校验放在解析末尾：topK/chunkType 的拒绝规则只写在 VectorQueryOptions 里一处，
        // 这里只是"提前触发"，不复制一份判断。
        cfg.options = new VectorQueryOptions(
                cfg.topK, cfg.docId, cfg.category, cfg.chunkType, cfg.asOf);
        return cfg;
    }

    private static int parseTopK(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--top-k 需要整数，实际：" + raw);
        }
    }

    private static LocalDate parseAsOf(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "--as-of 需要 yyyy-MM-dd 格式的日期，实际：" + raw
                            + "（该值按左闭右开 [effective_from, effective_to) 过滤；"
                            + "格式不明时不做猜测——把日期解析成另一天不会报错，只会让过滤结果悄悄错）");
        }
    }

    private static String next(String[] args, int i, String arg) {
        if (i >= args.length) {
            throw new IllegalArgumentException("参数 " + arg + " 缺少取值");
        }
        return args[i];
    }

    private static void printUsage() {
        System.out.println("""
                向量通道检索（第 2 周第 5 项）——纯向量检索的评测基线抓手

                用法：
                  ... com.canalmind.rag.vector.VectorQueryCli --query-file <utf8文件> [选项]

                查询来源（二选一，同时给出时**以文件为准**）：
                  --query-file <path>    UTF-8 文件：每行一个查询，# 开头为注释，空行忽略；
                                         允许写成 <查询>TAB<附加信息>，TAB 之后本入口忽略
                                         （探针文件即评测资产，可同时喂 BM25 与向量两个通道）
                  --query <text>         直接给一条文本（⚠️ 中文经 Windows argv 会被 GBK 解码成乱码）

                选项：
                  --top-k <n>            返回条数（默认 30，与 rag_bm25_search 默认值对齐）
                  --metric <m>           cosine（默认，<=>，精确扫描，评测基线）| ip（<#>，走 HNSW，近似）
                  --profile <name>       索引档案名（默认取 rag_chunk 实际引用的那个）
                  --doc-id <id>          限定文档
                  --category <no>        限定主题类编号（01–26）
                  --chunk-type <t>       限定 BODY / TABLE
                  --as-of <yyyy-MM-dd>   按生效期左闭右开 [from, to) 过滤
                  --out <path>           写结果 JSON（UTF-8 无 BOM + 仅 LF、无时间戳，可字节级复现）
                  --selftest             只检查 embed 客户端可用与 SQL 形状，不连库
                  --ollama-url <url>     Ollama 地址（默认 $OLLAMA_BASE_URL 或 http://localhost:11434）
                  --model <name>         embedding 模型（默认 $OLLAMA_EMBEDDING_MODEL 或 bge-m3）
                  --url/--host/--port/--db/--user/--password   数据库连接（默认读 $POSTGRES_*）
                  --help, -h             显示帮助

                JSON 契约（键名大小写敏感；rank 从 1 开始；score 为余弦相似度，越大越相关）：
                  channel=vector  profileName  topK  queryCount  results[{query,hits[...]}]

                退出码：0 成功；1 失败（失败信息含异常类型与底层原因）
                """);
    }
}
