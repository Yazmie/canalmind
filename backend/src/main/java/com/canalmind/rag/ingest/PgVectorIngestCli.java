package com.canalmind.rag.ingest;

import com.canalmind.rag.embed.EmbeddingClient;
import com.canalmind.rag.embed.EmbeddingModelInfo;
import com.canalmind.rag.embed.OllamaEmbeddingClient;
import com.canalmind.rag.embed.VectorCodec;
import com.canalmind.rag.split.Chunk;
import com.canalmind.rag.split.ChunkStructureSplitter;
import com.canalmind.rag.split.ChunkType;
import com.canalmind.rag.split.ChunkingProperties;
import com.canalmind.rag.split.CorpusDocument;
import com.canalmind.rag.split.DocumentParser;
import com.canalmind.rag.split.FixedLengthBaselineSplitter;
import com.canalmind.rag.split.Splitter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PgVector 入库命令行入口：<b>读语料 → 切分 → 调 embedding → 写 rag_chunk</b>
 * （第 2 周第 3 项，规格书第 628 行"Embedding + PgVector 入库"）。
 *
 * <p>用法：
 * <pre>
 *   # 真实入库（默认连 127.0.0.1:55432 的临时集群）
 *   java -Dfile.encoding=UTF-8 -cp &lt;classes:deps&gt; com.canalmind.rag.ingest.PgVectorIngestCli
 *
 *   # 只切分、不调 embedding、不写库（入库前自查）
 *   ... PgVectorIngestCli --dry-run
 *
 *   # 换成固定长度基线（第 4 周消融用；⚠️ 见下面"同一张表不要混两种策略"）
 *   ... PgVectorIngestCli --strategy baseline
 *
 *   ... PgVectorIngestCli --help
 * </pre>
 *
 * <p><b>为什么把这个 CLI 写出来（而不是只留一个 Service 类）</b>：入库是<b>幂等的重活</b>
 * （541 个 chunk、数十次 HTTP、数分钟），它需要的"可复现"是
 * <b>"从日志就能重放这一次运行"</b>：故启动时先打印全部生效配置（含每个默认值是从
 * 环境变量来的还是写死的），结束时打印一份汇总（各类 chunk 数、维度、范数统计、
 * 写入前后行数、耗时）。这样"某次入库是不是和我上次一样"是可核对的，
 * 不需要回头看命令行历史——与 {@code ChunkManifestCli} 同一套思路。
 *
 * <p><b>⚠️ 同一张表不要混两种切分策略</b>：V3 迁移的触发器
 * {@code rag_chunk_profile_guard} 要求每个 chunk 的 {@code strategy/splitter_version}
 * 与其 {@code profile_name} 指向的档案一致。档案名由 embedding 模型三元组构成
 * （{@code ollama:bge-m3:1024}），<b>不含切分策略</b>——所以用基线策略跑一遍，
 * 会把档案的 {@code splitter_strategy} 改成基线，而库里已有的结构化 chunk 仍是旧策略，
 * 于是表里出现两种口径的行（触发器只管写入，不会回头检查既有行）。
 * 这正是"每轮只改少量变量"（知识库《RAG 优化》第 366 行）要防的混批问题，
 * 故用 {@code --strategy baseline} 时本 CLI 会打印警告。
 *
 * <p>参数默认值来源优先级：命令行 &gt; 环境变量 &gt; 写死的本机默认值。
 * 环境变量名与 {@code .env.example} / {@code application.yml} 保持一致
 * （{@code POSTGRES_*} / {@code OLLAMA_*}），避免两处口径不同。
 */
public final class PgVectorIngestCli {

    /** 默认数据库端口：<b>55432</b> 是当前可用的验证集群（见 application.yml 注释与进度看板 §4/§6）。 */
    private static final String DEFAULT_DB_HOST = "127.0.0.1";
    private static final String DEFAULT_DB_PORT = "55432";
    private static final String DEFAULT_DB_NAME = "canalmind";
    private static final String DEFAULT_DB_USER = "postgres";
    private static final String DEFAULT_DB_PASSWORD = "canalmind_test";

    private static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "bge-m3";

    /** bge-m3 实测维度 1024（决策 C-22 / 进度看板 §4）。 */
    private static final int DEFAULT_DIMENSION = 1024;

    private PgVectorIngestCli() {
    }

    public static void main(String[] args) {
        pinUtf8Stdout();
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            // 失败也要"可核对"：打印异常类型 + 消息，若包装了原因（如 SQLException）一并打印，
            // 否则最有用的一句话会藏在 cause 里。
            System.out.println("入库失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("  底层原因：" + cause.getClass().getName() + ": " + cause.getMessage());
            }
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }

    /**
     * 把标准输出钉成 UTF-8。
     *
     * <p>JDK 18+ 里 {@code System.out} 的编码取自 {@code stdout.encoding}（默认是<b>控制台代码页</b>，
     * 本机为 GBK），而不是 {@code file.encoding}。若重定向到文件/管道，日志会变成 GBK 字节，
     * 用 UTF-8 读时中文全是乱码——而"日志可读"正是本 CLI 存在的意义（见类注释）。
     * 依据：AGENTS.md §7.2「生成产物时，换行/编码必须自己钉死」。
     */
    private static void pinUtf8Stdout() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    /** 全部生效配置（打印用，也便于人工核对"这次到底连了哪儿"）。 */
    private static final class Config {
        Path corpusDir;
        String jdbcUrl;
        String user;
        String password;
        String ollamaUrl;
        String model;
        /** {@code -1} 表示"命令行未指定"，由环境变量兜底；用 0 当哨兵会与"显式传 0"混淆。 */
        int dimension = -1;
        int batchSize = OllamaEmbeddingClient.DEFAULT_BATCH_SIZE;
        String strategy = "structure";
        boolean dryRun;
        boolean help;

        /** 是否由环境变量/命令行显式给出（仅用于打印"默认值来源"，不参与逻辑）。 */
        String jdbcUrlSource = "默认";
    }

    static int run(String[] args) throws IOException {
        long startedAt = System.currentTimeMillis();
        Config cfg = parseArgs(args);
        if (cfg.help) {
            printUsage();
            return 0;
        }

        List<Path> files = DocumentParser.listCorpusFiles(cfg.corpusDir);
        ChunkingProperties props = ChunkingProperties.defaults();
        Splitter splitter = "baseline".equals(cfg.strategy)
                ? new FixedLengthBaselineSplitter(props)
                : new ChunkStructureSplitter(props);
        EmbeddingModelInfo info = new EmbeddingModelInfo("ollama", cfg.model, cfg.dimension);

        printConfig(cfg, splitter, info, files.size());

        // ── 切分（两种策略都要先切，dry-run 也走这一步）────────────────────
        List<Chunk> chunks = new ArrayList<>();
        for (Path f : files) {
            CorpusDocument doc = DocumentParser.load(f);
            chunks.addAll(splitter.split(doc));
        }
        long bodyCount = countByType(chunks, ChunkType.BODY);
        long tableCount = countByType(chunks, ChunkType.TABLE);

        System.out.println("已切分：" + files.size() + " 篇文档 → " + chunks.size()
                + " 个 chunk（BODY " + bodyCount + " / TABLE " + tableCount + "）");

        int batches = (chunks.size() + cfg.batchSize - 1) / cfg.batchSize;
        if (cfg.dryRun) {
            System.out.println();
            System.out.println("==== dry-run（不调 embedding、不写库）====");
            System.out.println("将向 " + cfg.ollamaUrl + " 发起 " + batches + " 次 /api/embed 请求"
                    + "（每批 " + cfg.batchSize + " 条，共 " + chunks.size() + " 条）");
            System.out.println("将 upsert 档案：" + info.profileName()
                    + "（" + splitter.strategyName() + " " + splitter.version() + "）");
            System.out.println("将 upsert chunk：" + chunks.size() + " 行 → " + cfg.jdbcUrl);
            System.out.println("耗时 " + (System.currentTimeMillis() - startedAt) + " ms");
            return 0;
        }

        // ── 连库（独立运行，绕开 Spring 容器）──────────────────────────────
        // DriverManagerDataSource 的 getConnection() 就是
        // DriverManager.getConnection(url, username, password) ——
        // 用它只为把"一个 DataSource"这个 JdbcTemplate 需要的形状补齐，
        // 这里没有任何连接池（一次性批处理，池只会掩盖"连接没关"这类问题）。
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(cfg.jdbcUrl);
        dataSource.setUsername(cfg.user);
        dataSource.setPassword(cfg.password);
        RagChunkRepository repo = new RagChunkRepository(dataSource);

        Long rowsBefore = repo.countChunks();
        System.out.println("库内现有 chunk 行数：" + rowsBefore);

        // ── 调 embedding ───────────────────────────────────────────────────
        EmbeddingClient client = new OllamaEmbeddingClient(cfg.ollamaUrl, info, cfg.batchSize);
        List<float[]> vectors = new ArrayList<>(chunks.size());
        long embedStart = System.currentTimeMillis();
        System.out.println("开始向量化：" + chunks.size() + " 条，分 " + batches + " 批（每批 "
                + cfg.batchSize + " 条）");
        for (int b = 0; b < batches; b++) {
            int from = b * cfg.batchSize;
            int to = Math.min(from + cfg.batchSize, chunks.size());
            List<String> texts = new ArrayList<>(to - from);
            for (Chunk c : chunks.subList(from, to)) {
                texts.add(c.getContent());
            }
            // 每次只交一批：客户端内部也是按 batchSize 切，这里切一样大，
            // 于是"一次调用 = 一次 HTTP 请求"，进度行与请求数一一对应、可核对。
            vectors.addAll(client.embedAll(texts));
            System.out.println("[" + (b + 1) + "/" + batches + "] 已向量化 " + vectors.size() + " 条");
        }
        long embedMs = System.currentTimeMillis() - embedStart;

        // ── 入库前的两道断言（维度 + L2 归一化）────────────────────────────
        // 显式调一次，好让"配置的模型"出现在错误信息里（库内部还会按档案再校验一次）。
        repo.assertDimensions(chunks, vectors, info);

        // ── 写库 ───────────────────────────────────────────────────────────
        repo.upsertProfile(info, splitter.strategyName(), splitter.version(), noteFor(cfg, chunks));
        int written = repo.upsertChunks(chunks, vectors, info.profileName());
        Long rowsAfter = repo.countChunks();

        // ⚠️ 写完立刻收集统计信息：否则规划器在"无统计"状态下做计划选择，
        //    查询结果仍正确，但 EXPLAIN 的计划与代价会失真、误导索引排查。
        //    "表里有数据" ≠ "规划器知道表里有数据"。
        repo.analyzeChunkTable();

        // ── 汇总 ───────────────────────────────────────────────────────────
        double normSum = 0.0;
        double maxDeviation = 0.0;
        for (float[] v : vectors) {
            double n = VectorCodec.norm(v);
            normSum += n;
            maxDeviation = Math.max(maxDeviation, Math.abs(n - 1.0));
        }

        System.out.println();
        System.out.println("==== 入库汇总 ====");
        System.out.println("chunk 总数：" + chunks.size()
                + "（BODY " + bodyCount + " / TABLE " + tableCount + "）");
        System.out.println("向量维度：" + info.dimension() + "（档案 " + info.profileName() + "）");
        System.out.println("L2 范数：均值 " + fmt(normSum / Math.max(vectors.size(), 1))
                + "，与 1.0 的最大偏差 " + fmt(maxDeviation) + "（容差 1e-3）");
        System.out.println("写入行数（upsert 影响行）：" + written);
        System.out.println("库内 chunk 行数：" + rowsBefore + " → " + rowsAfter);
        System.out.println("耗时：embedding " + embedMs + " ms，整体 "
                + (System.currentTimeMillis() - startedAt) + " ms");
        return 0;
    }

    /**
     * 档案备注：写清"这批向量是怎么产出的"，让事后核查不必去翻日志。
     *
     * <p>这也是决策 C-22 配套硬约束"模型信息写入元数据"的一部分——
     * {@code profile_name} 只承载模型三元组，切分器与语料的形状差异要靠备注说明。
     */
    private static String noteFor(Config cfg, List<Chunk> chunks) {
        return "PgVectorIngestCli 入库：策略 " + cfg.strategy
                + "，批 " + cfg.batchSize
                + "，语料 " + cfg.corpusDir.getFileName()
                + "，共 " + chunks.size() + " 个 chunk";
    }

    private static long countByType(List<Chunk> chunks, ChunkType type) {
        return chunks.stream().filter(c -> c.getChunkType() == type).count();
    }

    /** 固定小数位 + Locale.ROOT：避免某些 locale 用逗号做小数点，让日志无法直接复制进 SQL。 */
    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.6f", d);
    }

    private static Config parseArgs(String[] args) {
        Config cfg = new Config();
        String jdbcUrl = null;
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            switch (key) {
                case "--corpus" -> cfg.corpusDir = Path.of(value(args, ++i, key));
                case "--url" -> jdbcUrl = value(args, ++i, key);
                case "--user" -> cfg.user = value(args, ++i, key);
                case "--password" -> cfg.password = value(args, ++i, key);
                case "--ollama-url" -> cfg.ollamaUrl = value(args, ++i, key);
                case "--model" -> cfg.model = value(args, ++i, key);
                case "--batch-size" -> cfg.batchSize = intValue(args, ++i, key);
                case "--dimension" -> cfg.dimension = intValue(args, ++i, key);
                case "--strategy" -> cfg.strategy = value(args, ++i, key).toLowerCase(Locale.ROOT);
                case "--dry-run" -> cfg.dryRun = true;
                case "--help", "-h" -> {
                    cfg.help = true;
                    return cfg;
                }
                default -> throw new IllegalArgumentException("未知参数: " + key);
            }
        }

        if (!"structure".equals(cfg.strategy) && !"baseline".equals(cfg.strategy)) {
            throw new IllegalArgumentException("--strategy 只支持 structure|baseline，实际 " + cfg.strategy);
        }

        // 命令行 > 环境变量 > 默认值。URL 由主机/端口/库名拼出，
        // 但允许 --url 直接覆盖（便于连别的集群）。
        String host = envOr("POSTGRES_HOST", DEFAULT_DB_HOST);
        String port = envOr("POSTGRES_PORT", DEFAULT_DB_PORT);
        String db = envOr("POSTGRES_DB", DEFAULT_DB_NAME);
        if (jdbcUrl != null) {
            cfg.jdbcUrl = jdbcUrl;
            cfg.jdbcUrlSource = "命令行 --url";
        } else {
            String fromEnv = System.getenv("POSTGRES_URL");
            if (fromEnv != null && !fromEnv.isBlank()) {
                cfg.jdbcUrl = fromEnv;
                cfg.jdbcUrlSource = "环境变量 POSTGRES_URL";
            } else {
                cfg.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                cfg.jdbcUrlSource = "POSTGRES_HOST/PORT/DB 或默认值";
            }
        }
        if (cfg.user == null) {
            cfg.user = envOr("POSTGRES_USER", DEFAULT_DB_USER);
        }
        if (cfg.password == null) {
            cfg.password = envOr("POSTGRES_PASSWORD", DEFAULT_DB_PASSWORD);
        }
        if (cfg.ollamaUrl == null) {
            cfg.ollamaUrl = envOr("OLLAMA_BASE_URL", DEFAULT_OLLAMA_URL);
        }
        if (cfg.model == null) {
            cfg.model = envOr("OLLAMA_EMBEDDING_MODEL", DEFAULT_MODEL);
        }
        // 维度不从模型名猜：模型换维度就换数字，写死猜测会在换模型时静默错维度。
        cfg.dimension = cfg.dimension != -1 ? cfg.dimension
                : parseDimension(envOr("OLLAMA_EMBEDDING_DIMENSION", String.valueOf(DEFAULT_DIMENSION)));

        // 取值校验放在"环境变量兜底之后"：否则用户不传 --dimension 时，
        // 哨兵值 -1 会被当成"用户传了非法值"而误报。
        if (cfg.batchSize < 1) {
            throw new IllegalArgumentException("--batch-size 必须 >= 1，实际 " + cfg.batchSize);
        }
        if (cfg.dimension < 1) {
            throw new IllegalArgumentException("--dimension 必须 >= 1，实际 " + cfg.dimension);
        }

        if (cfg.corpusDir == null) {
            cfg.corpusDir = locateCorpusDir();
        }
        if (cfg.corpusDir == null || !Files.isDirectory(cfg.corpusDir)) {
            throw new IllegalArgumentException(
                    "找不到语料目录，请用 --corpus 指定（当前工作目录向上 5 层内没有 docs/规则语料）");
        }
        return cfg;
    }

    private static int parseDimension(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "OLLAMA_EMBEDDING_DIMENSION 不是整数: " + raw + "。"
                            + "该值必须与向量列 vector(N) 及模型实际维度一致，故不猜默认值之外的值");
        }
    }

    private static String value(String[] args, int i, String key) {
        if (i >= args.length) {
            throw new IllegalArgumentException("参数 " + key + " 缺少取值");
        }
        return args[i];
    }

    private static int intValue(String[] args, int i, String key) {
        String raw = value(args, i, key);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + key + " 需要整数，实际 " + raw);
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    /**
     * 从当前工作目录向上查找 {@code docs/规则语料}。
     *
     * <p>与 {@code ChunkManifestCli} 同一套查找约定：不写死相对层级，
     * 因为"工作目录随启动方式变化"（Maven 从 {@code backend/} 跑、IDE 可能从仓库根跑），
     * 这一点本项目在 {@code .env} 上已经踩过（AGENTS.md §7.2）。
     */
    static Path locateCorpusDir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cur != null; i++) {
            Path candidate = cur.resolve("docs").resolve("规则语料");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static void printConfig(Config cfg, Splitter splitter, EmbeddingModelInfo info, int fileCount) {
        System.out.println("==== PgVectorIngestCli 配置（从本段日志即可重放本次运行）====");
        System.out.println("语料目录：" + cfg.corpusDir.toAbsolutePath() + "（" + fileCount + " 篇）");
        System.out.println("JDBC：" + cfg.jdbcUrl + "（来源：" + cfg.jdbcUrlSource + "）");
        System.out.println("数据库用户：" + cfg.user + "，口令："
                + (cfg.password == null || cfg.password.isEmpty() ? "（空）" : "已设置（不打印）"));
        System.out.println("Ollama：" + cfg.ollamaUrl + "，模型 " + cfg.model + "，维度 " + cfg.dimension);
        System.out.println("批次大小：" + cfg.batchSize);
        System.out.println("切分策略：" + cfg.strategy + "（" + splitter.strategyName()
                + " v" + splitter.version() + "）");
        System.out.println("模式：" + (cfg.dryRun ? "dry-run（不调 embedding、不写库）" : "实写"));
        System.out.println("档案名：" + info.profileName());
        if ("baseline".equals(cfg.strategy)) {
            System.out.println("⚠️ 警告：基线策略会把档案的 splitter_strategy 改成 "
                    + splitter.strategyName() + "，而库中既有结构化 chunk 仍是 "
                    + ChunkStructureSplitter.STRATEGY
                    + "；两者混在一张表里会让检索无法区分口径。"
                    + "做消融对比请写进独立档案/独立表，不要复用同一张 rag_chunk。");
        }
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("""
                用法: PgVectorIngestCli [选项]

                  --corpus <dir>       语料目录（默认向上查找 docs/规则语料）
                  --url <jdbc-url>     JDBC URL（默认由 POSTGRES_HOST/PORT/DB 拼出：
                                       jdbc:postgresql://127.0.0.1:55432/canalmind）
                  --user <u>           数据库用户（默认 $POSTGRES_USER 或 postgres）
                  --password <p>       数据库口令（默认 $POSTGRES_PASSWORD 或 canalmind_test）
                  --ollama-url <url>   Ollama 地址（默认 $OLLAMA_BASE_URL 或 http://localhost:11434）
                  --model <name>       embedding 模型（默认 $OLLAMA_EMBEDDING_MODEL 或 bge-m3）
                  --dimension <n>      向量维度（默认 $OLLAMA_EMBEDDING_DIMENSION 或 1024）
                  --batch-size <n>     每批向量化条数（默认 16）
                  --strategy <s>       structure（默认，C-21 主链路）| baseline（固定长度基线）
                  --dry-run            只切分并报告将要做什么，不调 embedding、不写库
                  --help, -h           显示帮助

                退出码：0 成功；1 失败（失败信息中会打印异常类型与底层原因）
                """);
    }
}
