package com.canalmind.rag.retrieve;

import com.canalmind.rag.bm25.Bm25Searcher;
import com.canalmind.rag.cli.CliConsole;
import com.canalmind.rag.embed.EmbeddingClient;
import com.canalmind.rag.embed.EmbeddingClientFactory;
import com.canalmind.rag.embed.EmbeddingModelGuard.EmbeddingModelMismatchException;
import com.canalmind.rag.ingest.RagChunkRepository;
import com.canalmind.rag.retrieve.LlmReranker.ChunkSnippetProvider;
import com.canalmind.rag.tokenize.JiebaShippingTokenizer;
import com.canalmind.rag.tokenize.TokenizerProfileGuard.TokenizerProfileMismatchException;
import com.canalmind.rag.vector.VectorSearcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 混合检索（RRF 融合 + LLM Rerank）命令行入口（第 2 周第 5 项，C-26 / C-27）。
 *
 * <p>用途有三：① 人工核对"某条问题在混合链路下召回了什么、各阶段怎么变的"
 * （写《评测报告》检索段与排查 badcase 的主要手段）；
 * ② 用 {@code --out} 产出确定性 JSON，作为<b>评测基线资产</b>
 * （无时间戳、UTF-8 无 BOM、仅 LF，可字节级 diff），并直接喂给
 * {@code backend/scripts/eval_retrieval.py}；③ 作为第 4/6 周消融与三档 Top-K 调参的抓手
 * ——那是本入口存在的主要理由，故四个条数都是显式参数。
 *
 * <p>用法（以 {@code --help} 为准）：
 * <pre>
 *   # ⚠️ 中文查询一律用 --query-file（UTF-8 文件）。Windows 下 JVM 用 GBK 解码 argv，
 *   #    --query "过闸费怎么算" 会变成乱码，而乱码不会报错，只会"什么都召不回"。
 *   ... RetrievalQueryCli --query-file probes.txt --top-k 5 --out docs/评测报告/rrf-rerank.json
 *
 *   # 消融的「无 rerank」档（**必须显式**，见下）
 *   ... RetrievalQueryCli --query-file probes.txt --no-rerank --out …/rrf-only.json
 *
 *   # 调三档 Top-K
 *   ... RetrievalQueryCli --query-file probes.txt --recall-top-k 50 --fusion-top-k 40 --rerank-top-n 20 --top-k 8
 *
 *   # 不连库、不调模型的自检（换机器后先跑这个）
 *   ... RetrievalQueryCli --selftest
 *
 *   # 第 6 项四路对照（单通道两档；产物 channel 字段会如实写成 vector / bm25）
 *   ... RetrievalQueryCli --query-file probes.txt --channels vector --no-rerank --out …/vector-only.json
 *   ... RetrievalQueryCli --query-file probes.txt --channels bm25   --no-rerank --out …/bm25-only.json
 * </pre>
 *
 * <h2>⚠️ {@code --channels}：单路时融合层仍然走 RRF，而"单路 RRF 序 = 该路原序"</h2>
 *
 * <p>只指定一路时，<b>刻意不换成"直通"实现</b>：链路里仍然是
 * {@code 召回 → RrfFusion →（Noop 或 LLM）重排 → 取前 contextTopN}，
 * 只是融合的输入只有一份名次表。这样做是为了让四路对照**只有一个变量**
 * ——"跑了几路"——而不是"跑了几路 + 换了条链路"。
 *
 * <p>单路时 RRF 的分数是 {@code 1/(k + rank)}：分母随名次严格递增，
 * 故<b>单路输入的名次序与 RRF 后的顺序逐位相同</b>（同分决胜规则不会改变它，
 * 因为同一路内的名次本就互不相同）。于是：
 * <ul>
 *   <li>{@code --channels vector --no-rerank --top-k N} 的前 N 条，
 *       应当与 {@code VectorQueryCli --top-k N} 的<b>同题排名完全一致</b>；</li>
 *   <li>同理 {@code --channels bm25} 与 {@code Bm25QueryCli} 一致。</li>
 * </ul>
 * <b>这一点有测试守着</b>（{@code RetrievalPipelineTest} 断言单路序逐位不变、
 * 且截断只是取前缀；{@code RetrievalQueryCliTest} 断言另一路的通道<b>根本不被构造</b>），
 * 因为"融合层在单路时悄悄改了顺序"是<b>不会报错的</b>：
 * 产物照样生成、指标照样算出来，只是那不再是单通道基线的数字，
 * 于是第 6 项会把"融合的增益"记到别的东西头上。
 *
 * <p>另外，单路时产物顶层的 {@code channel} 写<b>通道名</b>（{@code vector} / {@code bm25}）
 * 而不是 {@code rrf}——两路才写 {@code rrf}。这条要如实：下游靠它区分产物来源，
 * {@code vector} 与 {@code bm25} 两档的指标长得像而含义完全不同（见
 * {@code docs/工程复盘/检索工程/度量口径没进产物}）。
 *
 * <h2>⚠️ 缺 {@code DEEPSEEK_API_KEY} 时报错，不静默降级（C-27 配套硬约束）</h2>
 *
 * <p>{@code --no-rerank} 时<b>不要求</b>该 key；开启重排但环境里没有 key 时
 * <b>直接抛错</b>（消息里给出三条出路）。理由见 {@link LlmRerankerFactory#requireApiKey}：
 * 自动关掉重排会让"没跑成"与"rerank 没效果"在产物上无法区分。
 *
 * <h2>环境变量的两套口径（刻意不同，别"顺手统一"）</h2>
 *
 * <ul>
 *   <li>{@code POSTGRES_*} / {@code OLLAMA_*}：<b>只读进程环境变量</b>
 *       （与 {@link CliConsole#envOr} 同语义；本类自带一份同名解析只是为了把环境映射
 *       做成可注入的字段，好让测试不依赖真实环境），与 {@code VectorQueryCli} / {@code Bm25QueryCli}
 *       <b>逐字一致</b>。刻意<b>不</b>读 {@code .env}：本仓库根 {@code .env} 里的
 *       {@code POSTGRES_PORT} 与两个既有 CLI 常用的本地验证集群端口并不相同，
 *       若这里读 {@code .env}，"同一份探针在单通道与混合链路下连的可能是两个库"，
 *       而消融结论会因此悄悄失真。<b>比较必须在同一个库上做</b>；</li>
 *   <li>{@code DEEPSEEK_*}：<b>读 {@code .env}</b>（{@code ./.env} 与 {@code ../.env}，
 *       进程环境变量优先）。理由：仓库的既有约定就是把 key 放在 {@code .env}
 *       （{@code application.yml} 用 {@code spring.config.import} 显式导入，
 *       AGENTS.md §7.2 记过这个坑），而独立 {@code main} 没有 Spring，
 *       {@code System.getenv()} <b>看不到</b> {@code .env}。不读它，表现就是
 *       "密钥明明填在 .env 里，CLI 却报没有 key"。</li>
 * </ul>
 *
 * <h2>⚠️ stdout 上的「重排尝试」行不再把「没重试」说成「一次成功」（2026-09-21 修）</h2>
 *
 * <p>旧文案是 {@code attempts > 1 ? 重试过… : （一次成功）}，于是<b>降级</b>的题只要只试了一次，
 * 就会打印「1 次（一次成功）」，而紧接着下一行就是降级警告——同一题上自相矛盾。
 * 现在只有 {@code degraded=false} 才允许出现「成功」二字；降级时一律如实写
 * 「未重试：<失败分类>——<为什么没重试>」或「N 次（均失败，已保留融合原序…）」，
 * 判定逻辑收在 {@link #attemptLine}（有单测逐字钉住，见 {@code RetrievalQueryCliTest}）。
 *
 * <h2>退出码</h2>
 *
 * <p>0 = 跑完并写出产物（<b>即使发生过重排降级</b>——降级是本次运行的结论之一，
 * 由 stdout 的醒目提示与产物里的逐题 {@code rerankDegraded} / {@code degradeKind} /
 * {@code rerankRecoveredByRetry} 表达）；
 * 1 = 失败（参数非法、缺 key、连库失败、通道异常等）。
 * ⚠️ 刻意不把"降级"当失败：否则使用者会去改退出码判断而不是看降级原因；
 * 反过来，因为降级<b>会</b>打印提示并写进产物，也不会被误读成"rerank 没效果"。
 */
public final class RetrievalQueryCli {

    private static final String DEFAULT_DB_HOST = "127.0.0.1";
    private static final String DEFAULT_DB_PORT = "5432";
    private static final String DEFAULT_DB_NAME = "canalmind";
    private static final String DEFAULT_DB_USER = "postgres";

    /** 制表符：探针行用它分隔"查询文本"与"该行的附加信息"（用计算字符，不用转义字面量）。 */
    private static final char TAB = (char) 9;

    /**
     * UTF-8 BOM（U+FEFF）。用计算字符而不是 unicode 转义：转义会被 javac 在<b>词法阶段</b>
     * 替换掉，连注释里都会变成真字符，给后续编辑留下看不见的坑（同 {@code VectorQueryCli}）。
     */
    private static final char BOM = (char) 0xFEFF;

    /** 自检探针（源码常量：中文经 argv 会被控制台代码页改坏，自检本身不该引入这个变量）。 */
    static final String SELFTEST_PROBE = "自检探针：过闸费怎么算";

    /** 支持的融合策略（当前只有 RRF，见 {@link FusionStrategy}——归一化加权是第 4 周的可选项）。 */
    static final List<String> SUPPORTED_FUSION = List.of(RrfFusion.NAME);

    /**
     * 合法的通道名（第 6 项四路对照用）。
     *
     * <p>取值即"通道的查询侧实现"：{@link VectorRetrievalChannel#NAME} 与
     * {@link Bm25RetrievalChannel#NAME}。⛔ 不接受别名（{@code vec} / {@code bm25s} 之类）：
     * 别名只会让"我到底跑了哪一路"多一个出错的地方，而产物里的 {@code channel} 字段
     * 是要被下游用来区分消融档的。
     */
    static final List<String> SUPPORTED_CHANNELS =
            List.of(VectorRetrievalChannel.NAME, Bm25RetrievalChannel.NAME);

    /** 默认通道集（两路 RRF 融合）。 */
    static final String DEFAULT_CHANNELS = VectorRetrievalChannel.NAME + "," + Bm25RetrievalChannel.NAME;

    /** 一次性校验/解析 --channels 的结果：既是参数校验，也给测试与自检一个可复用的入口。 */
    record ChannelSpec(List<String> channels) {

        ChannelSpec {
            channels = List.copyOf(channels);
        }

        /** 是否只跑了一路（此时产物 {@code channel} 写通道名而不是融合策略名）。 */
        boolean single() {
            return channels.size() == 1;
        }

        String label(RetrievalConfig config) {
            return config.labelFor(channels);
        }
    }

    private RetrievalQueryCli() {
    }

    public static void main(String[] args) {
        System.exit(mainCode(args));
    }

    /**
     * 跑一次并给出**退出码**（不调用 {@code System.exit}，故测试能直接断言退出码）。
     *
     * <p>退出码口径：
     * <ul>
     *   <li>{@code 0} 跑完（即使发生过重排降级，产物里有 {@code rerankDegraded}）；</li>
     *   <li>{@code 2} <b>命令行参数错误</b>——用法问题，改参数即可，不该让人去查数据库；</li>
     *   <li>{@code 1} <b>运行期失败</b>（连库 / embedding / 通道 / 模型失败）。</li>
     * </ul>
     *
     * <p>参数错误刻意<b>只打一行中文提示</b>、且<b>不打印异常类名</b>：
     * 冒一个 {@code IllegalArgumentException} 到顶只会让人以为程序崩了，
     * 而它的真实含义是「参数写错了」——这类「错误形态不统一」正是
     * {@code unknownOptions} 那条测试想拦住的东西。
     */
    static int mainCode(String[] args) {
        CliConsole.pinUtf8Stdout();
        try {
            return run(args);
        } catch (UsageError e) {
            // 一行，带「参数错误：」前缀，脚本可按前缀分流
            System.out.println("参数错误：" + e.getMessage());
            return 2;
        } catch (Throwable t) {
            // 失败也要"可核对"：打印异常类型 + 消息 + 底层原因。
            // 通道异常包装了受检异常（档案不匹配、连不上 Ollama…），
            // 最有用的那句话通常藏在 cause 里（与其余 CLI 一致）。
            System.out.println("混合检索失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
            Throwable cause = t.getCause();
            if (cause != null) {
                System.out.println("  底层原因：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            return 1;
        }
    }

    /**
     * 命令行**参数错误**：与「运行期失败」分开的一类。
     *
     * <p>继承 {@link IllegalArgumentException} 是刻意的——既有断言
     * （{@code isInstanceOf(IllegalArgumentException.class)}）不必改；
     * 而 {@link #mainCode} 按<b>更具体的类型先捕获</b>，于是这一类被统一收敛成
     * 「一行中文提示 + 退出码 2」。其余 {@code IllegalArgumentException}
     * （例如运行期内部契约被违反）仍按运行期失败处理（退出码 1）。
     *
     * <p>⛔ 新增参数校验时<b>一律</b>用它，别再裸抛 {@code IllegalArgumentException}：
     * 混用会让"参数错"和"运行期错"在用户眼里变成同一句话。
     */
    static final class UsageError extends IllegalArgumentException {

        UsageError(String message) {
            super(message);
        }
    }

    /**
     * 跑一次检索。
     *
     * <p>两个受检异常（索引侧模型不匹配、分词档案不匹配）<b>直接抛出、不在这里包装</b>：
     * 它们是"库里的既成事实与本地配置不一致"，必须拒绝执行（见两个 searcher 的守卫），
     * 而 {@link #main} 会打印异常类型、消息与底层原因——包装成"检索失败"会把
     * 最有用的那句话（哪一个档案、哪里不一致）压到 cause 里。
     */
    static int run(String[] args) throws IOException,
            EmbeddingModelMismatchException, TokenizerProfileMismatchException {
        Config cfg = parseArgs(args);
        if (cfg.help) {
            printUsage();
            return 0;
        }
        if (cfg.selftest) {
            return runSelfTest(cfg);
        }

        List<String> queries = cfg.readQueries();
        if (queries.isEmpty()) {
            // 归入参数错误（退出码 2）："没给查询"是用法问题，不是运行期故障
            throw new UsageError(
                    "查询为空：请用 --query-file <UTF-8 文件>（推荐）或 --query <文本>");
        }

        // 缺 key 在**连库之前**就报错：避免先花一次 embedding 与一次连库的等待，再告诉用户没 key
        requireRerankKey(cfg.rerankEnabled, cfg.deepSeekEnv());

        DriverManagerDataSource ds = new DriverManagerDataSource(cfg.url(), cfg.user, cfg.password);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // ── 两路通道：**按需构造**（--channels 只给一路时，另一路根本不建）────────
        // 建一路的代价不只是内存：向量路要 embedding 客户端 + 索引档案核对，
        // BM25 路要分词器 + 词典指纹核对，两者的守卫**都会直接抛错**。
        // 若不按需构造，"只想跑 vector"会因为库里 BM25 档案缺失而失败——
        // 而那一侧本次根本用不到（见 requestedChannels 的注释）。
        boolean wantVector = cfg.channels.contains(VectorRetrievalChannel.NAME);
        boolean wantBm25 = cfg.channels.contains(Bm25RetrievalChannel.NAME);

        // 档案名只在需要时才去库里探；探不到就报错（不猜、不降级）
        String vectorProfile = null;
        if (wantVector) {
            vectorProfile = cfg.profile != null ? cfg.profile
                    : new RagChunkRepository(jdbc).selectProfileName();
            if (vectorProfile == null) {
                throw new IllegalStateException(
                        "库里没有任何向量索引档案（rag_index_profile 与 rag_chunk 都没有记录）。"
                                + "请先运行 PgVectorIngestCli 完成「切分 + embedding + 入库」。");
            }
        }
        String tokenProfile = null;
        if (wantBm25) {
            tokenProfile = cfg.tokenProfile != null ? cfg.tokenProfile : resolveSingleTokenProfile(jdbc);
        }
        final String vp = vectorProfile;
        final String tp = tokenProfile;

        List<RetrievalChannel> channels = requestedChannels(cfg.channels,
                () -> {
                    VectorSearcher searcher = new VectorSearcher(jdbc, cfg.embeddingClient(), vp);
                    // 先把两侧档案核对一遍：不一致时**拒绝查询**，不降级（searcher 自带守卫）
                    searcher.ensureProfileMatches();
                    return new VectorRetrievalChannel(searcher);
                },
                () -> {
                    Bm25Searcher searcher = new Bm25Searcher(jdbc,
                            new JiebaShippingTokenizer(cfg.segmentMode), tp);
                    searcher.ensureProfileMatches();
                    return new Bm25RetrievalChannel(searcher);
                });

        // ── 组装链路：融合策略 → 重排器 → 配置（三者的名字必须自洽）──────
        FusionStrategy fusion = cfg.fusion();
        Reranker reranker;
        if (cfg.rerankEnabled) {
            // 重试上限随命令行传入（--rerank-retry，默认 2）：它决定"降级率"这个观测量，
            // 埋在常量里会让两次跑出的降级率不可比
            //
            // --dump-rerank：只把**真正发出去的那次调用**旁听下来（提示词全文/原始应答/旁证）。
            // ⚠️ 它被做成"装配点套一层装饰"而不是重排器内部另开一条路：
            //    否则落盘的就不是真实证据，而"看不到真实证据"正是这次排查卡住的原因。
            // ⚠️ 构造 RerankDump 会**立刻建目录并清空文件**：路径不可写要在花钱之前报出来。
            reranker = LlmRerankerFactory.fromEnvironment(
                    cfg.deepSeekEnv(), jdbcSnippetProvider(jdbc), cfg.rerankRetry,
                    cfg.dumpRerank == null
                            ? java.util.function.UnaryOperator.identity()
                            : completer -> new RerankDump(cfg.dumpRerank, completer, dumpHeader(cfg)));
        } else {
            // 关掉重排时**不构造** LLM 重排器（否则会要求 key，与 --no-rerank 的语义矛盾）。
            // ⚠️ 这个 NoopReranker 只是"本档没有重排器"的占位：RetrievalPipeline 在
            //    rerankEnabled=false 时**根本不调用它**（也不再借它做 rerankTopN 截断，见 B1）。
            reranker = new NoopReranker();
        }
        RetrievalConfig retrievalConfig = cfg.retrievalConfig(fusion, reranker);

        // 单路时**仍然走 RrfFusion**：四路对照要只改"跑了几路"这一个变量（见类注释）
        RetrievalPipeline pipeline = new RetrievalPipeline(channels, fusion, reranker, retrievalConfig);

        printBanner(cfg, pipeline, vp, tp, queries.size());

        List<RetrievalResult> results = new ArrayList<>(queries.size());
        int degradedCount = 0;
        int retriedCount = 0;
        int retriedOkCount = 0;
        for (int qi = 0; qi < queries.size(); qi++) {
            String query = queries.get(qi);
            System.out.println();
            System.out.println("── 查询 " + (qi + 1) + "/" + queries.size() + " ──");
            System.out.println("查询        ：" + query);
            long t0 = System.currentTimeMillis();
            RetrievalResult result = pipeline.retrieve(query);
            long ms = System.currentTimeMillis() - t0;
            results.add(result);
            printResult(result, retrievalConfig, ms, cfg.rerankRetry);
            // ⚠️ 只数"最终仍降级"的（= 重试后仍降级）。重试后才成功的单独数：
            //    把它算进降级数会让人以为降级率没被重试改善，而完全不计又会掩盖链路不稳。
            if (result.rerankDegraded()) {
                degradedCount++;
            }
            if (result.rerankOutcome().retried()) {
                retriedCount++;
                if (!result.rerankDegraded()) {
                    retriedOkCount++;
                }
            }
        }

        if (cfg.out != null) {
            String json = RetrievalResultJson.render(
                    retrievalConfig, pipeline.channelNames(), results);
            RetrievalResultJson.write(cfg.out, json);
            System.out.println();
            System.out.println("已写出 JSON：" + cfg.out.toAbsolutePath());
            System.out.println("字节数      ：" + Files.size(cfg.out.toAbsolutePath())
                    + "（UTF-8 无 BOM + 仅 LF，无时间戳 → 可字节级复现）");

            // 写盘后立刻自查下游的第二道硬校验（每题条数必须恰好 = topK）。
            // 放在写盘之后：产物本身是有价值的排错证据（能看出是哪几题没填满），
            // 删掉它只会让人更难看懂问题；但必须大声说出来，否则用户拿到的是一份
            // "能生成、一算就 FAIL"的基线，而 FAIL 信息只印截断后的题面。
            List<String> shortQueries = RetrievalResultJson.queriesNotFillingContext(
                    retrievalConfig, results);
            if (!shortQueries.isEmpty()) {
                System.out.println();
                System.out.println("⚠️⚠️ 有 " + shortQueries.size() + "/" + results.size()
                        + " 条查询没填满上下文（少于 contextTopN=" + retrievalConfig.contextTopN()
                        + " 条）：");
                for (String q : shortQueries) {
                    System.out.println("      · " + q);
                }
                System.out.println("      ⇒ eval_retrieval.py 会**拒绝**这一份产物"
                        + "（它硬校验「每题条数 == topK」，因为指标是按实际条数算的，"
                        + "声明多于实际会得到看起来正常但偏低的数字）。");
                System.out.println("      ⇒ 常见原因：库里可检索的块太少（少于 "
                        + retrievalConfig.contextTopN() + " 条）。请先确认入库与档案，"
                        + "不要靠改评测脚本绕过。");
            }
        }
        if (degradedCount > 0) {
            System.out.println();
            System.out.println("⚠️⚠️ 本次有 " + degradedCount + "/" + results.size()
                    + " 条查询**重试后仍降级**：这些查询的排序是融合原序，"
                    + "不是重排结果。产物里已标顶层 rerankDegraded=true、degradedQueryCount="
                    + degradedCount + "，每条另有 rerankDegraded / degradeKind / degradeReason"
                    + "（rerankDegraded 是逐题版的同一位，两者可交叉核对）。"
                    + "⛔ 不要把这次结果读成「rerank 没有效果」——那是「没跑成」。");
        }
        if (retriedCount > 0) {
            System.out.println();
            System.out.println("ℹ️ 本次有 " + retriedCount + "/" + results.size()
                    + " 条查询**重试过**（rerankAttempts>1）：其中 " + retriedOkCount + " 条"
                    + "是重试后成功（产物里 rerankRecoveredByRetry=true 的那几条）、"
                    + "其余 " + (retriedCount - retriedOkCount) + " 条重试后仍降级。"
                    + "⚠️ 重试过 ≠ 一次成功：产物里带 rerankAttempts 与 previousDegradeReasons，"
                    + "把它们读成「链路一次跑通」会掩盖这条链路的偶发不稳定。");
        }
        return 0;
    }

    /**
     * 「重排尝试」那一行的取值（2026-09-21 修正自相矛盾的文案）。
     *
     * <p><b>要修的毛病</b>：旧实现写
     * {@code attempts > 1 ? 重试过… : （一次成功）}——于是 {@code attempts=1} 的降级
     * 会打出「1 次（一次成功）」，紧接着下一行却是降级警告。
     * 根因是把"没重试"读成了"成了"，而 {@code attempts=1} 有三种完全不同的含义：
     * <ol>
     *   <li>真的<b>一次就成了</b>（{@code degraded=false}）；</li>
     *   <li><b>这一档压根不重试</b>（空正文 / 超时 / 鉴权失败 / 候选超上限…）——
     *       此时必须说清是哪一档、以及<b>为什么没重试</b>；</li>
     *   <li><b>重试被关掉了</b>（{@code --rerank-retry 0}）——这与第 2 种要分开：
     *       前者是配置决定，后者是失败性质决定。</li>
     * </ol>
     * <p>⛔ 只有真的产出了可用重排结果（{@code degraded=false}）才允许出现「成功」二字；
     * 降级时一律如实说"未重试 / 均失败 + 已保留融合原序"。
     *
     * @param retryBudget 本次的重试上限（{@code --rerank-retry}，0 = 关闭重试）
     */
    static String attemptLine(RerankOutcome outcome, int retryBudget) {
        int n = outcome.attempts();
        RerankOutcome.FailureKind kind = outcome.failureKind();
        if (n == 0) {
            // 一次都没调模型：可能是关掉重排 / 空输入，也可能是"调模型之前就被拒"的那几档
            return outcome.degraded()
                    ? "0 次（未调用模型：" + kind.label() + "——" + kind.retryNote() + "）"
                    : "0 次（未调用模型：空输入，或本档关闭了重排）";
        }
        if (!outcome.degraded()) {
            return n == 1
                    ? "1 次（一次成功）"
                    : n + " 次（⚠️ 重试过：前 " + (n - 1) + " 次失败，本次成功——"
                            + "⛔ 这不是「一次成功」：链路这一刻不稳，只是被重试兜住了）";
        }
        if (n == 1) {
            // 降级且只试了一次：必须交代"为什么没重试"，否则读者只会看到一句没有下文的次数
            return retryBudget == 0
                    ? "1 次（未重试：--rerank-retry 0，本次已关闭重试；失败原因见下一行）"
                    : "1 次（未重试：" + kind.label() + "——" + kind.retryNote() + "）";
        }
        return n + " 次（均失败，已保留融合原序：重试 " + (n - 1) + " 次仍降级，"
                + "最后一次的失败分类 " + kind + "）";
    }

    private static void printResult(RetrievalResult result, RetrievalConfig config, long ms,
                                    int retryBudget) {
        RerankOutcome outcome = result.rerankOutcome();
        System.out.println("阶段条数    ：召回并集 " + result.trace(RetrievalPipeline.STAGE_RECALL).size()
                + " → 融合 " + result.fused().size()
                + " → 重排 " + outcome.candidates().size()
                + " → 上下文 " + result.context().size()
                + "（请求：recallTopK=" + config.recallTopK() + " fusionTopK=" + config.fusionTopK()
                + " rerankTopN=" + config.rerankTopN() + " contextTopN=" + config.contextTopN() + "）");
        System.out.println("耗时        ：" + ms + " ms（其中重排 " + outcome.latencyMillis()
                + " ms，模型 " + outcome.model() + "）");
        System.out.println("重排尝试    ：" + attemptLine(outcome, retryBudget));
        if (outcome.degraded()) {
            System.out.println("⚠️ 重排降级  ：" + outcome.degradeReason());
        }
        for (String previous : outcome.previousDegradeReasons()) {
            System.out.println("   ↳ 前一次降级原因：" + previous);
        }
        int rank = 1;
        for (RetrievalCandidate c : result.context()) {
            System.out.println(String.format(Locale.ROOT, "%2d. %s", rank++, c.summary()));
        }
        if (result.context().isEmpty()) {
            System.out.println("（无候选：两路都没召回任何 chunk）");
        }
    }

    private static void printBanner(Config cfg, RetrievalPipeline pipeline, String vectorProfile,
                                    String tokenProfile, int queryCount) {
        RetrievalConfig rc = pipeline.config();
        boolean multi = pipeline.channelNames().size() > 1;
        System.out.println("=== 混合检索（第 2 周第 5 项：RRF 融合 + LLM Rerank）===");
        System.out.println("通道        ：" + pipeline.channelNames()
                + (multi ? "" : "（⚠️ 单通道档：产物 channel 字段会写通道名而不是 rrf；"
                        + "单路时 RRF 序 = 该路原序，即与单通道 CLI 同题排名一致）"));
        System.out.println("             每路召回 " + rc.recallTopK() + " 条；两个 searcher 的过滤条件均为「不限」，"
                + "与单通道 CLI 的基线口径一致");
        if (vectorProfile != null) {
            System.out.println("索引档案    ：" + vectorProfile);
        }
        if (tokenProfile != null) {
            System.out.println("分词档案    ：" + tokenProfile);
        }
        if (!multi) {
            System.out.println("             另一路本次**未构造**（--channels " + cfg.channels + "）");
        }
        System.out.println("融合策略    ：" + rc.fusionName() + "（score(d) = Σ 1/(k + rank)，k=" + rc.rrfK()
                + "；只用名次、不归一化分数——C-26）");
        System.out.println("重排        ：" + (rc.rerankEnabled()
                ? rc.rerankerName() + "（模型 " + cfg.rerankerModelHint()
                        + "，maxTokens=" + cfg.rerankMaxTokens()
                        + "（" + LlmRerankerFactory.RERANK_MAX_TOKENS_ENV + " 可覆盖，默认 "
                        + LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS + "；"
                        + "推理 token 与正文共享这个预算），重试上限 " + cfg.rerankRetry
                        + " 次：只在「输出无法解析 / 被截断」上重试，"
                        + "鉴权/网络/超时/空正文不重试）"
                : "关闭（NoopReranker 占位，= 消融的「无 rerank」对照档；"
                        + "本档**不调用**重排器，也不按 rerankTopN 截断上下文——见 B1）"));
        if (rc.rerankEnabled()) {
            // 超时与 maxTokens 同属「这一路能不能跑起来」的实验条件，故同样一眼可见地打印。
            // ⚠️ 只在开着重排时打印：它取自环境变量，而 --no-rerank 档不该因为一个
            //    与本次无关的非法取值而报错（那个档根本不构造重排器）。
            System.out.println("重排超时    ：timeout=" + cfg.rerankTimeoutSeconds() + "s（"
                    + LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV + " 可覆盖，默认 "
                    + LlmRerankerFactory.DEFAULT_RERANK_TIMEOUT_SECONDS
                    + "s；超时即降级，且这一档不重试。"
                    + "实测 40 题单题重排耗时均值 29.4 s，原 30 s 正好压在均值上，故已调大）");
            // 诊断落盘：**开启时必须显式可见**。写了一个没人找得到的文件，
            // 下次排查又会退化成"靠猜"——那正是这个开关要解决的问题。
            System.out.println("诊断落盘    ：" + (cfg.dumpRerank == null
                    ? "未开启（--dump-rerank <路径> 或 " + RerankDump.ENV_NAME
                            + " 可开启：把每次重排的提示词全文 / 原始应答 / finishReason / token 用量落盘）"
                    : cfg.dumpRerank + "（每次重排调用追加一段；该文件是**证据**，不是评测基线）"));
        }
        // ⛔ 不许静默空转：本档关闭重排 ⇒ 一次模型都不会调 ⇒ 那个文件里不会出现任何东西。
        //    不写这句的话，用户会以为"诊断开着只是这次没降级"，而真相是"这份证据根本不会产生"。
        if (!rc.rerankEnabled() && cfg.dumpRerank != null) {
            System.out.println("诊断落盘    ：--dump-rerank " + cfg.dumpRerank
                    + " 已给出，但本档**关闭重排**、一次模型都不调 ⇒ 该文件本轮不会有内容"
                    + "（开关只在开启重排时生效）");
        }
        System.out.println("产物标识    ：channel=" + rc.labelFor(pipeline.channelNames())
                + "，topK=" + rc.contextTopN() + "（topK = 最终上下文条数，"
                + "eval_retrieval.py 用它当截断位上界）");
        System.out.println("查询条数    ：" + queryCount
                + (queryCount > 1 ? "（探针文件：每行一个查询，# 开头为注释）" : ""));
    }

    // ── 自检（不连库、不调模型）──────────────────────────────────────────

    /**
     * 自检：<b>不连库、不调模型</b>，只验"链路的接线与产物契约"。
     *
     * <p>检查五项，全部是"错了不会报错、只会在产物里表现为『没效果』"的地方：
     * <ol>
     *   <li>四个条数与 k 的取值、以及配置校验真的会拒绝非法组合；</li>
     *   <li>{@code channel} 标签与三档条数（下游脚本按 {@code topK} 当截断位）；</li>
     *   <li>融合→重排→上下文这条编排能用<b>内存桩通道</b>跑通，且各阶段条数符合预期；</li>
     *   <li>渲染出的 JSON 含四个必需键，且每条命中都有 rank/chunkId/score、
     *       rank 从 1 连续（这三条是 {@code eval_retrieval.py} 的硬校验）；</li>
     *   <li>重排名前后的自洽校验确实会拦下"名字与实现不一致"的产物。</li>
     * </ol>
     * <p>刻意<b>不</b>调模型：自检的用途是"换机器/换配置后先确认链路形状"，
     * 而真实模型调用是另一件事（有成本、有网络依赖），混在一起会让
     * "链路接错了"与"key/网络有问题"两个原因无法区分。
     */
    static int runSelfTest(Config cfg) {
        System.out.println("=== 混合检索自检（不连库、不调模型）===");
        List<String> failures = new ArrayList<>();

        // 只用来取"会写进产物的通道标识"的那个名字：自检**不调用**任何模型，
        // 故这里的 completer / snippet 都是不会被执行到的桩（返回空串）。
        Reranker displayReranker = cfg.rerankEnabled
                ? new LlmReranker(prompt -> "", ChunkSnippetProvider.NONE, cfg.rerankerModelHint())
                : new NoopReranker();
        RetrievalConfig base = cfg.retrievalConfig(cfg.fusion(), displayReranker);
        System.out.println("四档条数    ：recallTopK=" + base.recallTopK()
                + " fusionTopK=" + base.fusionTopK()
                + " rerankTopN=" + base.rerankTopN()
                + " contextTopN=" + base.contextTopN() + " rrfK=" + base.rrfK());
        System.out.println("通道标识    ：" + base.labelFor(cfg.channels)
                + "（rerankEnabled=" + base.rerankEnabled() + "，--channels " + cfg.channels + "）");
        System.out.println("重排重试    ：--rerank-retry " + cfg.rerankRetry
                + "（0 = 不重试；只在「输出无法解析 / 被 token 上限截断」上重试，"
                + "鉴权 / 网络 / 超时 / 空正文不重试）");
        // 重排额度与重试次数同属"这一路能不能跑起来"的实验条件，
        // 故自检也报它；⚠️ 非法取值在这里变成一条失败项而不是抛出去——
        // 自检的用途是"给出清单"，半路抛异常会让其它检查项一个都看不到。
        try {
            System.out.println("重排额度    ：maxTokens=" + cfg.rerankMaxTokens()
                    + "（" + LlmRerankerFactory.RERANK_MAX_TOKENS_ENV + " 可覆盖，默认 "
                    + LlmRerankerFactory.DEFAULT_RERANK_MAX_TOKENS
                    + "；推理 token 与正文共享这个预算，故提示词越长 / 候选越多越容易写不出来）");
        } catch (IllegalArgumentException e) {
            failures.add("重排 maxTokens 配置非法：" + firstLine(e.getMessage()));
        }
        // 超时同样是"这一路能不能跑起来"的实验条件（实测 30 s 正好压在均值 29.4 s 上），
        // 故自检也报它；非法取值同样变成一条失败项而不是抛出去（同上一段的理由）。
        try {
            System.out.println("重排超时    ：timeout=" + cfg.rerankTimeoutSeconds() + "s（"
                    + LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV + " 可覆盖，默认 "
                    + LlmRerankerFactory.DEFAULT_RERANK_TIMEOUT_SECONDS
                    + "s；超时即降级，且这一档不重试。"
                    + "实测 40 题单题重排耗时均值 29.4 s，原 30 s 正好压在均值上，故已调大）");
        } catch (IllegalArgumentException e) {
            failures.add("重排超时配置非法：" + firstLine(e.getMessage()));
        }
        // 诊断开关也进自检清单：它的存在感必须比"记得加参数"强——
        // 真实的截断排查就是卡在"看不到那次的提示词与原始应答"上。
        System.out.println("诊断落盘    ：" + (cfg.dumpRerank == null
                ? "未开启（--dump-rerank <路径> 或 " + RerankDump.ENV_NAME + " 可开启）"
                : cfg.dumpRerank + "（重排调用会追加：提示词全文 / 原始应答 / finishReason"
                        + " / promptTokens / completionTokens / reasoningTokens）"));

        // ⑴b --channels 的合法值、以及"单路 → 通道标识写通道名"这条溯源要求
        try {
            parseChannelSpec("vec");
            failures.add("未知通道名竟然没有报错（会让用户以为跑了对照，实际跑的是默认两路）");
        } catch (IllegalArgumentException e) {
            System.out.println("通道校验    ：未知通道被拒 → " + firstLine(e.getMessage()));
        }
        try {
            parseChannelSpec("vector,vector");
            failures.add("重复通道竟然没有报错（RRF 会把同一路当两份证据，分数被默默放大）");
        } catch (IllegalArgumentException e) {
            System.out.println("重复通道    ：被拒 → " + firstLine(e.getMessage()));
        }
        for (String one : SUPPORTED_CHANNELS) {
            String label = parseChannelSpec(one).label(base.withoutRerank());
            if (!one.equals(label)) {
                failures.add("单通道 " + one + " 的产物 channel 应为 " + one + "，实际 " + label);
            }
        }
        System.out.println("单路标识    ：--channels vector → "
                + parseChannelSpec("vector").label(base.withoutRerank())
                + "；--channels bm25 → " + parseChannelSpec("bm25").label(base.withoutRerank())
                + "；两路 → " + base.labelFor(List.of(VectorRetrievalChannel.NAME,
                Bm25RetrievalChannel.NAME)));

        // ⑴ 配置校验：非法组合必须被拒（拒绝规则写在 RetrievalConfig 一处，这里只验它活着）
        try {
            new RetrievalConfig(30, 10, 20, 5, 60, true, RrfFusion.NAME, NoopReranker.NAME);
            failures.add("非法组合 rerankTopN>fusionTopK 竟然通过了校验");
        } catch (IllegalArgumentException e) {
            System.out.println("校验示例    ：rerankTopN>fusionTopK 被拒 → " + firstLine(e.getMessage()));
        }

        // ⑵ 用内存桩通道跑通编排（不连库）
        List<RetrievalCandidate> channelA = new ArrayList<>();
        List<RetrievalCandidate> channelB = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            channelA.add(RetrievalCandidate.of(String.format(Locale.ROOT, "a%02d", i))
                    .withChannel("stub-a", 1.0 / i, i));
        }
        for (int i = 1; i <= 40; i++) {
            channelB.add(RetrievalCandidate.of(String.format(Locale.ROOT, "b%02d", i))
                    .withChannel("stub-b", 10.0 - i, i));
        }
        RetrievalChannel stubA = new StubChannel("stub-a", channelA);
        RetrievalChannel stubB = new StubChannel("stub-b", channelB);
        RetrievalConfig selftestConfig = new RetrievalConfig(30, 20, 10, 5, RrfFusion.DEFAULT_K,
                false, RrfFusion.NAME, NoopReranker.NAME);
        RetrievalPipeline pipeline = new RetrievalPipeline(
                List.of(stubA, stubB), new RrfFusion(selftestConfig.rrfK()),
                new NoopReranker(), selftestConfig);
        RetrievalResult result = pipeline.retrieve(SELFTEST_PROBE);
        System.out.println("阶段条数    ：召回并集 " + result.trace(RetrievalPipeline.STAGE_RECALL).size()
                + "（两路各 " + selftestConfig.recallTopK() + "，桩通道各给 40）"
                + " → 融合 " + result.fused().size()
                + " → 上下文 " + result.context().size());
        if (result.fused().size() != selftestConfig.fusionTopK()) {
            failures.add("融合后条数 " + result.fused().size()
                    + " 不等于 fusionTopK=" + selftestConfig.fusionTopK());
        }
        if (result.context().size() != selftestConfig.contextTopN()) {
            failures.add("上下文条数 " + result.context().size()
                    + " 不等于 contextTopN=" + selftestConfig.contextTopN());
        }

        // ⑵b 单通道：融合层不得改变该路顺序（否则第 6 项会把"融合的增益"记到别处）
        RetrievalPipeline single = new RetrievalPipeline(List.of(stubA),
                new RrfFusion(selftestConfig.rrfK()), new NoopReranker(), selftestConfig);
        RetrievalResult singleResult = single.retrieve(SELFTEST_PROBE);
        List<String> singleIds = singleResult.context().stream()
                .map(RetrievalCandidate::chunkId).toList();
        List<String> expectedIds = channelA.subList(0, selftestConfig.contextTopN()).stream()
                .map(RetrievalCandidate::chunkId).toList();
        System.out.println("单路对照    ：--channels stub-a 的前 " + selftestConfig.contextTopN()
                + " 条 = " + singleIds + "，与该路原序前 "
                + selftestConfig.contextTopN() + " 条一致：" + singleIds.equals(expectedIds));
        if (!singleIds.equals(expectedIds)) {
            failures.add("单通道时 RRF 改变了该路顺序：期望 " + expectedIds + "，实际 " + singleIds);
        }

        // ⑶ 产物契约
        String json = RetrievalResultJson.render(selftestConfig, pipeline.channelNames(),
                List.of(result));
        for (String key : RetrievalResultJson.REQUIRED_KEYS) {
            if (!json.contains("\"" + key + "\"")) {
                failures.add("产物缺少必需键：" + key);
            }
        }
        // ⑶b 重试痕迹的三个键必须在（B4 配套）：它们缺失时"重试过"就看不出来，
        //     而那是"重试把链路不稳定洗成链路正常"的入口
        for (String key : List.of("rerankAttempts", "previousDegradeReasons",
                "rerankAttemptsTotal")) {
            if (!json.contains("\"" + key + "\"")) {
                failures.add("产物缺少重试痕迹键：" + key);
            }
        }
        // ⑶b′ 逐题的三个判定键必须在（2026-09-21 补）：降级位与"重试后才成功"必须一眼看得出
        //     ⚠️ rerankDegraded 顶层与逐题同名，故这里只查它"至少出现两次"，
        //        另两个键（degradeKind / rerankRecoveredByRetry）只有逐题才有
        for (String key : List.of("degradeKind", "rerankRecoveredByRetry")) {
            if (!json.contains("\"" + key + "\"")) {
                failures.add("产物缺少逐题判定键：" + key);
            }
        }
        if (countOf(json, "\"rerankDegraded\"") < 2) {
            failures.add("产物里 rerankDegraded 只出现了 " + countOf(json, "\"rerankDegraded\"")
                    + " 次：顶层与逐题都该有（只读顶层时「哪几题降级」要另外猜）");
        }
        if (json.indexOf((char) 13) >= 0) {
            failures.add("产物里出现了 CR 字节（Jackson 默认缩进器是 SYS_LF，两个缩进器都要显式设 LF）");
        }
        System.out.println("产物自检    ：必需键 " + RetrievalResultJson.REQUIRED_KEYS
                + " 齐全；重试痕迹键 rerankAttempts / previousDegradeReasons /"
                + " rerankAttemptsTotal 齐全；逐题判定键 rerankDegraded（顶层+逐题）/"
                + " degradeKind / rerankRecoveredByRetry 齐全；无 CR；channel="
                + selftestConfig.labelFor(pipeline.channelNames()));

        // ⑶c B1：关闭重排时上下文**不得**被 rerankTopN 截断（声明 == 实际）
        //     用一组合法但"contextTopN > rerankTopN"的配置：曾在这里静默少给几条
        RetrievalConfig noRerankWide = new RetrievalConfig(40, 40, 3, 30, RrfFusion.DEFAULT_K,
                false, RrfFusion.NAME, NoopReranker.NAME);
        RetrievalResult wide = new RetrievalPipeline(List.of(stubA, stubB),
                new RrfFusion(noRerankWide.rrfK()), new NoopReranker(), noRerankWide)
                .retrieve(SELFTEST_PROBE);
        System.out.println("B1 自检     ：--no-rerank 且 rerankTopN=3 / contextTopN=30 时上下文 = "
                + wide.context().size() + " 条（应恒等于 contextTopN=30，"
                + "且等于产物顶层 topK）");
        if (wide.context().size() != noRerankWide.contextTopN()) {
            failures.add("关闭重排时上下文只有 " + wide.context().size() + " 条，"
                    + "不等于 contextTopN=" + noRerankWide.contextTopN()
                    + "（被 rerankTopN=" + noRerankWide.rerankTopN() + " 截断了？"
                    + "表现为「关掉重排反而让上下文变少」，且产物声明的 topK 与实际不符）");
        }

        // ⑷ 名字自洽校验必须活着（否则产物会"声称"用了某个策略而实际不是）
        try {
            new RetrievalPipeline(List.of(stubA), new RrfFusion(),
                    new NoopReranker(), RetrievalConfig.defaults());
            failures.add("融合策略名与配置不一致时竟然没有报错（产物会声称用了别的策略）");
        } catch (IllegalArgumentException e) {
            // RetrievalConfig.defaults() 的 fusionName 是 rrf，与 NoopReranker 无关；
            // 这里触发的是 rerankerName（llm-rerank）与 NoopReranker（noop）不一致
            System.out.println("名字校验    ：不一致被拒 → " + firstLine(e.getMessage()));
        }

        // ⑸ 环境：只报"有没有"，⛔ 不打印 key 本身
        Map<String, String> env = cfg.deepSeekEnv();
        boolean hasKey = env.get(LlmRerankerFactory.API_KEY_ENV) != null
                && !env.get(LlmRerankerFactory.API_KEY_ENV).isBlank();
        System.out.println("重排环境    ：" + LlmRerankerFactory.API_KEY_ENV
                + (hasKey ? " 已就位（不回显取值）" : " 缺失")
                + "，base-url=" + env.getOrDefault("DEEPSEEK_BASE_URL", LlmRerankerFactory.DEFAULT_BASE_URL)
                + "，model=" + env.getOrDefault("DEEPSEEK_CHAT_MODEL", LlmRerankerFactory.DEFAULT_MODEL));
        try {
            requireRerankKey(true, Map.of());
            failures.add("开启重排且没有 key 时竟然没有报错（会造成静默降级）");
        } catch (IllegalStateException e) {
            System.out.println("缺 key 校验 ：开启重排时报错 → " + firstLine(e.getMessage()));
        }
        requireRerankKey(false, Map.of());   // 关闭重排时必须放行

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("自检结论    ：✅ 编排与产物契约正确（未连库、未调模型、未校验档案一致性）");
            return 0;
        }
        System.out.println("自检结论    ：❌ 失败 " + failures.size() + " 项：");
        for (String f : failures) {
            System.out.println("   - " + f);
        }
        return 1;
    }

    /** 只取消息的第一行，避免把长消息刷满屏。 */
    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int cut = message.indexOf(CliConsole.LF);
        return cut < 0 ? message : message.substring(0, cut) + " …";
    }

    /**
     * 子串出现次数（自检用）。
     *
     * <p>存在的理由：{@code rerankDegraded} 顶层与逐题<b>同名</b>，而"键在不在"这种
     * {@code contains} 判断对它是无效的——顶层那一个就足以让检查通过，
     * 于是逐题漏写也测不出来（这正是本项目最怕的"假 PASS"）。
     * 数次数才能把"两处都有"钉住。
     */
    static int countOf(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** 自检用的内存通道。 */
    private record StubChannel(String name, List<RetrievalCandidate> hits) implements RetrievalChannel {

        @Override
        public List<RetrievalCandidate> search(String query, int topK) {
            return List.copyOf(hits.size() <= topK ? hits : hits.subList(0, topK));
        }
    }

    // ── 装配 ──────────────────────────────────────────────────────────────

    /**
     * 取消重排时的 key 要求逻辑（供测试与自检直接调用）。
     *
     * @throws IllegalStateException 开启重排但缺 key
     */
    static void requireRerankKey(boolean rerankEnabled, Map<String, String> deepSeekEnv) {
        if (!rerankEnabled) {
            return;   // ⚠️ 关闭时**不**要求 key：这正是 --no-rerank 能跑通的前提
        }
        LlmRerankerFactory.requireApiKey(deepSeekEnv);
    }

    /**
     * 让重排能拿到候选正文（{@code rag_chunk.content}）。
     *
     * <p>为什么按需查库而不是先把正文塞进候选：候选是召回/融合的产物，
     * 会在 stageTrace 与产物里被反复记录，带上正文会让内存与日志都膨胀；
     * 而正文只在"送进提示词"这一刻需要（见 {@link ChunkSnippetProvider}）。
     *
     * <p>同一次运行内 chunk 正文不会变，故按 chunk_id 缓存：一次评测（40 题 × 最多 30 候选）
     * 会产生上千次查询，缓存把重复往返去掉；缓存只在进程内、不落盘，
     * 且<b>不会</b>跨进程复用（否则"改了语料却还在用旧正文"，而重排结果不会报错）。
     */
    static ChunkSnippetProvider jdbcSnippetProvider(JdbcTemplate jdbc) {
        Map<String, String> cache = new HashMap<>();
        return chunkId -> cache.computeIfAbsent(chunkId, id -> {
            List<String> rows = jdbc.queryForList(
                    "SELECT content FROM rag_chunk WHERE chunk_id = ?", String.class, id);
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    /**
     * 未指定 {@code --token-profile} 时确定分词档案名：唯一才自动采用，多个直接报错。
     *
     * <p>与 {@code Bm25QueryCli.resolveSingleProfile()} <b>同一条规则</b>
     * （该方法为包内可见、本入口在另一个包，故按同规则重写而不是复制一份判断逻辑的位置）。
     * 刻意不取"第一个"：档案不同意味着词典/模式/参数不同，猜错会让分数失去可比性，
     * 而查询本身不会报错。
     */
    static String resolveSingleTokenProfile(JdbcTemplate jdbc) {
        List<String> names = jdbc.queryForList(
                "SELECT profile_name FROM rag_token_profile ORDER BY profile_name", String.class);
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "库里没有任何 BM25 分词档案。请先运行 Bm25IngestCli 建档案并灌词元。");
        }
        if (names.size() > 1) {
            throw new IllegalStateException(
                    "库里有 " + names.size() + " 个 BM25 档案 " + names + "，无法确定用哪个，"
                            + "请显式指定 --token-profile。");
        }
        return names.get(0);
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

        /** 向量索引档案（{@code rag_index_profile}）；null = 取 {@code rag_chunk} 实际引用的那个。 */
        String profile;
        /** BM25 分词档案（{@code rag_token_profile}）；null = 库里唯一才自动采用。 */
        String tokenProfile;
        String segmentMode = JiebaShippingTokenizer.DEFAULT_SEGMENT_MODE;

        String query;
        Path queryFile;
        Path out;

        int recallTopK = RetrievalConfig.defaults().recallTopK();
        int fusionTopK = RetrievalConfig.defaults().fusionTopK();
        int rerankTopN = RetrievalConfig.defaults().rerankTopN();
        int contextTopN = RetrievalConfig.defaults().contextTopN();
        int rrfK = RetrievalConfig.defaults().rrfK();
        boolean rerankEnabled = true;

        /**
         * 重排的<b>重试次数上限</b>（不含首发），默认 {@link LlmReranker#DEFAULT_MAX_RETRIES} = 2。
         *
         * <p>只在"输出无法解析 / 被 token 上限截断"上重试；鉴权（缺 key、401）、网络、
         * 超时、空正文一律<b>不</b>重试（理由见 {@link LlmReranker} 的逐条口径表）。
         *
         * <p>⚠️ 它是<b>实验条件</b>之一，不是纯实现细节：这个数直接决定"降级率"这个观测量
         * （实测降级率 ~25% 时，不重试会有 ~10/40 条落到融合原序兜底，
         * 指标就同时混了"重排效果"与"兜底效果"、不可解读）。
         * 故必须能从命令行调，并随产物可追溯（{@code rerankAttempts} /
         * {@code rerankAttemptsTotal}）。
         */
        int rerankRetry = LlmReranker.DEFAULT_MAX_RETRIES;

        String fusionCliName = RrfFusion.NAME;

        /**
         * 参与检索的通道（第 6 项四路对照）。默认两路。
         *
         * <p>存的是<b>已校验</b>的通道名（{@link #parseChannelSpec} 的产物），
         * 故这里不会是未知名字；名字与实现类的 {@code name()} 必须一致，
         * 否则 {@link RetrievalResultJson} 的 {@code recallChannels} 与阶段 trace 的键
         * 会和实际通道对不上（而不报错）。
         */
        List<String> channels = List.of(VectorRetrievalChannel.NAME, Bm25RetrievalChannel.NAME);

        /** Ollama 地址/模型覆盖（默认取 $OLLAMA_*，见 {@link #embeddingClient()}）。 */
        String ollamaUrl;
        String ollamaModel;

        /**
         * 重排**诊断落盘**路径（{@code --dump-rerank <路径>}，环境变量
         * {@link RerankDump#ENV_NAME} 兜底）；null = 不落盘。
         *
         * <p>它只影响"是否把证据写下来"，<b>不改变额度/超时/重试</b>这三样实验条件——
         * 关掉它，跑出来的排序与产物必须完全一样（否则它就不是诊断开关，
         * 而是偷偷改行为的第二个变量）。
         */
        Path dumpRerank;

        boolean selftest;
        boolean help;

        /** 进程环境变量（测试可注入；默认 {@link System#getenv()}）。 */
        Map<String, String> processEnv = System.getenv();

        /** DeepSeek 侧的 .env 候选路径（顺序即优先级，进程环境变量仍最高）。 */
        List<Path> dotEnvFiles = defaultDotEnvFiles();

        String url() {
            return rawUrl != null ? rawUrl : "jdbc:postgresql://" + host + ":" + port + "/" + db;
        }

        /** 与 {@code application.yml} 的两个 import 路径一致（工作目录随启动方式变化）。 */
        static List<Path> defaultDotEnvFiles() {
            return List.of(Path.of(".env"), Path.of("..", ".env"));
        }

        /**
         * DeepSeek 侧环境：{@code .env} + 进程环境变量（后者优先）。
         *
         * <p>只把 <b>DeepSeek 相关</b>的键并进来，不用它去解析 {@code POSTGRES_*}/{@code OLLAMA_*}
         * ——理由见类注释「环境变量的两套口径」。
         */
        Map<String, String> deepSeekEnv() {
            return LlmRerankerFactory.environmentWithDotEnv(dotEnvFiles, processEnv);
        }

        /**
         * 本次会用的重排模型名（仅用于 banner 打印"我们打算用哪个模型"）。
         *
         * <p>与 {@link LlmRerankerFactory} 读的是同一批键，故打印值与实际调用值不会分叉；
         * 真值仍以装配出来的 {@link LlmReranker#modelName()} 为准（它会写进产物）。
         */
        String rerankerModelHint() {
            String v = deepSeekEnv().get("DEEPSEEK_CHAT_MODEL");
            return (v == null || v.isBlank()) ? LlmRerankerFactory.DEFAULT_MODEL : v.strip();
        }

        /**
         * 本次重排用的<b>输出额度</b>（{@link LlmRerankerFactory#RERANK_MAX_TOKENS_ENV}，
         * 默认 {@link LlmRerankerFactory#DEFAULT_RERANK_MAX_TOKENS}）。
         *
         * <p>存在的唯一理由是<b>让 banner 打印它</b>：额度是"这一路能不能跑起来"的实验条件
         * （实测 4096 在 30 候选档下确定性降级），而它来自 {@code .env} / 环境变量——
         * 若 banner 不打印，用户无法确认"我设的 16384 到底生效了没有"，
         * 只能从"这次降级率"去反推，而那是<b>事后、且混着模型波动</b>的证据。
         *
         * <p>⚠️ 与真正生效的值<b>同源</b>（都走 {@code resolveMaxTokens}），故不会分叉；
         * 非法取值在装配处就已报错，不存在"打印了一个没生效的数"的情况。
         */
        int rerankMaxTokens() {
            return LlmRerankerFactory.resolveMaxTokens(deepSeekEnv());
        }

        /**
         * 本次重排用的<b>单次等待上限（秒）</b>
         * （{@link LlmRerankerFactory#RERANK_TIMEOUT_SECONDS_ENV}，
         * 默认 {@link LlmRerankerFactory#DEFAULT_RERANK_TIMEOUT_SECONDS}）。
         *
         * <p>与 {@link #rerankMaxTokens()} 同一个理由<b>让 banner 打印它</b>：
         * 等待上限也是"这一路能不能跑起来"的实验条件——实测 40 题单题重排耗时
         * 最小 21.6 / 均值 29.4 / 最大 43.5 s，原值 30 s <b>正好压在均值上</b>，
         * 被它判成超时降级的调用，在产物里与"重排没效果"完全一样。
         * 若 banner 不打印，用户无法确认"我设的 120 到底生效了没有"。
         *
         * <p>⚠️ 与真正生效的值<b>同源</b>：装配处
         * {@link LlmRerankerFactory#fromEnvironment} 用同一个 {@code resolveTimeoutSeconds}
         * 的结果做 {@code withTimeout}，故"打印的值"与"实际等待的值"不会分叉；
         * 非法取值在装配处就已报错，不存在"打印了一个没生效的数"的情况。
         */
        int rerankTimeoutSeconds() {
            return LlmRerankerFactory.resolveTimeoutSeconds(deepSeekEnv());
        }

        /**
         * 按 {@link EmbeddingClientFactory} 的同一套键构造查询侧客户端（与 {@code VectorQueryCli} 一致）。
         *
         * <p>命令行覆盖通过"临时环境变量映射"注入，而不是另写一条构造分支：
         * 这样默认值与校验规则只有工厂一处，也顺带继承它对维度的强校验。
         */
        EmbeddingClient embeddingClient() {
            Map<String, String> env = new HashMap<>(processEnv);
            if (ollamaUrl != null) {
                env.put("OLLAMA_BASE_URL", ollamaUrl);
            }
            if (ollamaModel != null) {
                env.put("OLLAMA_EMBEDDING_MODEL", ollamaModel);
            }
            return EmbeddingClientFactory.fromEnvironment(env);
        }

        /** 融合策略实例：命令行取值只认 {@link #SUPPORTED_FUSION}。 */
        FusionStrategy fusion() {
            if (RrfFusion.NAME.equals(fusionCliName)) {
                return new RrfFusion(rrfK);
            }
            throw new UsageError(
                    "未知融合策略「" + fusionCliName + "」：当前只支持 " + SUPPORTED_FUSION
                            + "（归一化加权是 C-26 留给第 4 周的可选第二实现，本项不实现）");
        }

        /**
         * 构造配置。
         *
         * <p>四个条数与两个名字都必须与<b>实际使用的实例</b>一致（{@link RetrievalPipeline} 会再校验一次），
         * 否则产物会声称用了某个策略而实际不是。
         */
        RetrievalConfig retrievalConfig(FusionStrategy fusion, Reranker reranker) {
            return new RetrievalConfig(recallTopK, fusionTopK, rerankTopN, contextTopN, rrfK,
                    rerankEnabled, fusion.name(), reranker.name());
        }

        /**
         * 读出查询列表。
         *
         * <p>{@code --query-file} 的约定与 {@code VectorQueryCli} / {@code Bm25QueryCli}
         * <b>完全一致</b>（同一份探针文件要能喂三个入口）：UTF-8；每行一个查询；
         * {@code #} 开头为注释；空行忽略；剥掉行首 BOM；
         * 行内 TAB 之后的部分本入口忽略（留给下游脚本记附加信息）。
         *
         * <p>两个来源都给时<b>以文件为准</b>：命令行里那句中文注定是乱码，
         * 与其把乱码当查询发出去，不如让文件赢。
         */
        List<String> readQueries() throws IOException {
            if (queryFile == null) {
                return (query == null || query.isBlank()) ? List.of() : List.of(query);
            }
            List<String> queries = new ArrayList<>();
            for (String line : Files.readAllLines(queryFile, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                // 只会在第一行出现 BOM，但逐行判：写成"仅首行"是多余的状态，
                // 而漏掉它会让第一条查询带着一个看不见的字符去编码与分词
                if (!trimmed.isEmpty() && trimmed.charAt(0) == BOM) {
                    trimmed = trimmed.substring(1).strip();
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int tab = trimmed.indexOf(TAB);
                queries.add(tab < 0 ? trimmed : trimmed.substring(0, tab).strip());
            }
            return queries;
        }
    }

    static Config parseArgs(String[] args) {
        return parseArgs(args, System.getenv());
    }

    /** 便于测试注入进程环境变量（不改全局状态）。 */
    static Config parseArgs(String[] args, Map<String, String> processEnv) {
        Config cfg = new Config();
        cfg.processEnv = processEnv == null ? Map.of() : processEnv;
        cfg.host = envOr(cfg, "POSTGRES_HOST", DEFAULT_DB_HOST);
        cfg.port = envOr(cfg, "POSTGRES_PORT", DEFAULT_DB_PORT);
        cfg.db = envOr(cfg, "POSTGRES_DB", DEFAULT_DB_NAME);
        cfg.user = envOr(cfg, "POSTGRES_USER", DEFAULT_DB_USER);
        cfg.password = envOr(cfg, "POSTGRES_PASSWORD", "");
        // --dump-rerank 也可由环境变量开启（命令行优先：下面 switch 会覆盖这里）。
        // 任何路径问题都必须在**连库/花钱之前**以参数错误（退出码 2）报出来。
        String dumpEnv = cfg.processEnv.get(RerankDump.ENV_NAME);
        if (dumpEnv != null && !dumpEnv.isBlank()) {
            cfg.dumpRerank = parsePathArg(RerankDump.ENV_NAME, dumpEnv);
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--url" -> cfg.rawUrl = next(args, ++i, arg);
                case "--host" -> cfg.host = next(args, ++i, arg);
                case "--port" -> cfg.port = next(args, ++i, arg);
                case "--db" -> cfg.db = next(args, ++i, arg);
                case "--user" -> cfg.user = next(args, ++i, arg);
                case "--password" -> cfg.password = next(args, ++i, arg);
                case "--profile" -> cfg.profile = next(args, ++i, arg);
                case "--token-profile" -> cfg.tokenProfile = next(args, ++i, arg);
                case "--segment-mode" -> cfg.segmentMode = next(args, ++i, arg);
                case "--query" -> cfg.query = next(args, ++i, arg);
                case "--query-file" -> cfg.queryFile = parsePathArg(arg, next(args, ++i, arg));
                case "--out" -> cfg.out = parsePathArg(arg, next(args, ++i, arg));
                case "--recall-top-k" -> cfg.recallTopK = parseIntArg(arg, next(args, ++i, arg));
                case "--fusion-top-k" -> cfg.fusionTopK = parseIntArg(arg, next(args, ++i, arg));
                case "--rerank-top-n" -> cfg.rerankTopN = parseIntArg(arg, next(args, ++i, arg));
                case "--top-k" -> cfg.contextTopN = parseIntArg(arg, next(args, ++i, arg));
                case "--rrf-k" -> cfg.rrfK = parseIntArg(arg, next(args, ++i, arg));
                case "--rerank-retry" -> cfg.rerankRetry = parseRetryArg(next(args, ++i, arg));
                case "--dump-rerank" -> cfg.dumpRerank = parsePathArg(arg, next(args, ++i, arg));
                case "--no-rerank" -> cfg.rerankEnabled = false;
                case "--fusion" -> cfg.fusionCliName = next(args, ++i, arg);
                case "--channels" -> cfg.channels = parseChannelSpec(next(args, ++i, arg)).channels();
                case "--ollama-url" -> cfg.ollamaUrl = next(args, ++i, arg);
                case "--model" -> cfg.ollamaModel = next(args, ++i, arg);
                case "--selftest" -> cfg.selftest = true;
                case "--help", "-h" -> {
                    cfg.help = true;
                    return cfg;
                }
                default -> throw new UsageError(
                        "未知参数：" + arg + "（用 --help 看用法。⚠️ 不要用命令行传中文："
                                + "Windows 会用代码页解码 argv，中文查询请写进 UTF-8 文件用 --query-file）");
            }
        }
        // 在解析末尾**提前触发**配置校验：非法组合（如 contextTopN 大于 rerankTopN）
        // 会在连库、调 embedding、调模型之前失败，而那时用户往往已经等了几十秒。
        // 校验规则只写在 RetrievalConfig 一处，这里不复制一份判断。
        //
        // ⚠️ 必须转成 UsageError：RetrievalConfig 抛的是 IllegalArgumentException，
        //    若不转换，同样的"参数写错了"会走到运行期那一档（退出码 1 + 打印异常类名），
        //    与 --channels 之类参数错误的形态就不一致了。
        try {
            cfg.retrievalConfig(cfg.fusion(), new NoopReranker());
        } catch (IllegalArgumentException e) {
            throw new UsageError(e.getMessage());
        }
        return cfg;
    }

    /**
     * 解析并校验 {@code --channels}（第 6 项四路对照）。
     *
     * <p>四类输入一律<b>报错</b>，不静默处理：
     * <ol>
     *   <li><b>未知通道名</b>（如 {@code --channels vec}）——静默忽略会让用户以为跑了对照，
     *       实际跑的是默认两路，而产物照样生成、指标照样算出来；</li>
     *   <li><b>空元素</b>（如 {@code vector,} 或 {@code ,}）——多半是想写两路写漏了；</li>
     *   <li><b>重复通道</b>（如 {@code vector,vector}）——这是最危险的一类：
     *       RRF 会把同一个通道当成两份独立证据，该路每条候选的分数被<b>悄悄放大一倍</b>，
     *       排序看起来仍然"正常"；</li>
     *   <li><b>空取值</b>（{@code --channels ""}）。</li>
     * </ol>
     *
     * <p>取值规则：<b>逗号分隔、允许空格、大小写不敏感</b>，且<b>顺序即融合输入顺序</b>
     * （{@code bm25,vector} 与 {@code vector,bm25} 的 {@code recallChannels} 会跟着变，
     * 故顺序原样保留、不做排序）。大小写不敏感是为了挡「{@code --channels VECTOR}
     * 被判成未知通道」这类纯手误——它看着像通道名有别名，实际只是大小写。
     */
    static ChannelSpec parseChannelSpec(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new UsageError(
                    "--channels 不能为空：合法值为 " + SUPPORTED_CHANNELS
                            + "，逗号分隔（默认 " + DEFAULT_CHANNELS + "）");
        }
        List<String> channels = new ArrayList<>();
        for (String piece : raw.split(",", -1)) {
            String written = piece.strip();
            if (written.isEmpty()) {
                throw new UsageError(
                        "--channels 里有空元素（原始取值「" + raw + "」）：合法值为 " + SUPPORTED_CHANNELS
                                + "，逗号分隔（默认 " + DEFAULT_CHANNELS + "）");
            }
            // 归一成小写再比对：SUPPORTED_CHANNELS 是小写常量，而用户写 VECTOR 时意图毫无歧义
            String name = written.toLowerCase(Locale.ROOT);
            if (!SUPPORTED_CHANNELS.contains(name)) {
                throw new UsageError(
                        "未知通道「" + written + "」：--channels 只支持 " + SUPPORTED_CHANNELS
                                + "（逗号分隔、允许空格、大小写不敏感；默认 " + DEFAULT_CHANNELS
                                + "）。⚠️ 不会静默忽略：那样产物照样生成、指标照样算出来，"
                                + "而你以为跑的是对照档");
            }
            if (channels.contains(name)) {
                throw new UsageError(
                        "通道重复：「" + written + "」在 --channels 里出现了两次。"
                                + "同一个通道跑两遍会让 RRF 把它当成两路独立证据，"
                                + "该路的每条候选分数被默默放大，而排序看起来仍然正常。");
            }
            channels.add(name);
        }
        return new ChannelSpec(channels);
    }

    /**
     * 只构造<b>被请求</b>的通道（第 6 项四路对照；也是"单通道结果应与单通道 CLI 一致"的前提）。
     *
     * <p>用工厂而不是直接传实例：单通道时要做到"另一路<b>根本不被构造</b>"。
     * 构造一路是有副作用的——向量路要建 embedding 客户端、连库、核对索引档案；
     * BM25 路要建分词器、读档案、核对词典指纹。<b>两者的守卫都会直接抛错</b>，
     * 于是"只想跑 vector"却因为 BM25 档案不匹配而失败，是很坏的体验：
     * 用户明明没用到那一侧。故这里按需构造。
     */
    static List<RetrievalChannel> requestedChannels(List<String> channels,
                                                    ChannelSupplier vector,
                                                    ChannelSupplier bm25)
            throws EmbeddingModelMismatchException, TokenizerProfileMismatchException {
        ChannelSpec spec = new ChannelSpec(channels);
        List<RetrievalChannel> out = new ArrayList<>(spec.channels().size());
        for (String name : spec.channels()) {
            if (VectorRetrievalChannel.NAME.equals(name)) {
                out.add(vector.get());
            } else {
                out.add(bm25.get());
            }
        }
        return List.copyOf(out);
    }

    /** 通道工厂：允许抛受检异常（索引/分词档案不匹配时必须拒绝执行，不降级）。 */
    @FunctionalInterface
    interface ChannelSupplier {
        RetrievalChannel get() throws EmbeddingModelMismatchException,
                TokenizerProfileMismatchException;
    }

    /**
     * {@code --dump-rerank} 的文件头：把"这次调用是在什么条件下发生的"写下来。
     *
     * <p>没有这几行，事后拿到一份提示词全文也判不出该调哪个旋钮——
     * 因为"该调大额度"与"该缩短输出契约/候选数"的结论依赖于
     * {@code maxTokens}、{@code fusionTopK}、{@code rerankTopN}、模型名这几个量。
     * 这些量与真正装配进重排器的值同源（{@code cfg}），故不会与实际调用分叉。
     */
    static String dumpHeader(Config cfg) {
        String LF = String.valueOf((char) 10);
        Map<String, String> env = cfg.deepSeekEnv();
        return new StringBuilder()
                .append("模型              : ").append(cfg.rerankerModelHint())
                .append("  （DEEPSEEK_CHAT_MODEL）").append(LF)
                .append("base-url          : ")
                .append(env.getOrDefault("DEEPSEEK_BASE_URL", LlmRerankerFactory.DEFAULT_BASE_URL)).append(LF)
                .append("maxTokens（输出额度）: ").append(cfg.rerankMaxTokens())
                .append("  （").append(LlmRerankerFactory.RERANK_MAX_TOKENS_ENV).append(" 当前有效值；")
                .append("⚠️ 推理 token 与正文共享这个预算）").append(LF)
                .append("超时(秒)          : ").append(cfg.rerankTimeoutSeconds())
                .append("  （").append(LlmRerankerFactory.RERANK_TIMEOUT_SECONDS_ENV).append("）").append(LF)
                .append("重试上限(不含首发) : ").append(cfg.rerankRetry).append(LF)
                .append("四个条数          : recallTopK=").append(cfg.recallTopK)
                .append("  fusionTopK=").append(cfg.fusionTopK)
                .append("  rerankTopN=").append(cfg.rerankTopN)
                .append("  contextTopN=").append(cfg.contextTopN)
                .append("  rrfK=").append(cfg.rrfK).append(LF)
                .append("通道              : ").append(String.join(",", cfg.channels)).append(LF)
                .toString();
    }

    private static String envOr(Config cfg, String name, String fallback) {        String value = cfg.processEnv.get(name);
        return (value == null || value.isBlank()) ? fallback : value.strip();
    }

    private static int parseIntArg(String arg, String raw) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new UsageError(arg + " 需要整数，实际：" + raw);
        }
    }

    /**
     * 解析路径类参数（{@code --out} / {@code --query-file} / {@code --dump-rerank}）。
     *
     * <p>为什么不能直接 {@code Path.of(...)}：非法路径会抛 {@link InvalidPathException}
     * （一个运行期异常），于是"参数写错了"会以退出码 1 + 异常类名的面貌出现，
     * 与本项目"参数错误 = 一行中文 + 退出码 2"的口径不一致——而参数错误本该一眼可改。
     */
    private static Path parsePathArg(String arg, String raw) {
        try {
            return Path.of(raw.strip());
        } catch (InvalidPathException e) {
            throw new UsageError(arg + " 的路径非法：「" + raw + "」（" + e.getMessage() + "）");
        }
    }

    /**
     * 解析 {@code --rerank-retry}：必须是 {@code >= 0} 的整数。
     *
     * <p>⛔ 不接受负数：负数的重试次数会让"最多再试 n 次"这个终止条件变得不可读，
     * 而它<b>不会报错</b>——只会让重试静默失效（或反过来变成无限循环）。
     * {@code 0} 是合法取值（= 不重试，回到"每次只发一次请求"的旧行为）。
     */
    private static int parseRetryArg(String raw) {
        int n = parseIntArg("--rerank-retry", raw);
        if (n < 0) {
            throw new UsageError(
                    "--rerank-retry 必须 >= 0（0 = 不重试，只发一次请求），实际：" + n
                            + "。⚠️ 负数不会报错的写法会让重试静默失效，故在解析处直接拒绝。");
        }
        return n;
    }

    private static String next(String[] args, int i, String arg) {
        if (i >= args.length) {
            throw new UsageError("参数 " + arg + " 缺少取值");
        }
        return args[i];
    }

    /**
     * 打印用法。
     *
     * <p>包内可见（而不是 private）是为了让测试能把输出抓下来逐条断言：
     * 帮助文案是本入口唯一的"契约说明"，而选项一旦漏写进帮助，
     * 用户就只能靠读源码或猜——这与"配置项必须能被发现"是同一件事。
     */
    static void printUsage() {
        System.out.println("""
                混合检索（RRF 融合 + LLM Rerank）——第 2 周第 5 项（C-26 / C-27）

                用法：
                  ... com.canalmind.rag.retrieve.RetrievalQueryCli --query-file <utf8文件> [选项]

                查询来源（二选一，同时给出时以文件为准）：
                  --query-file <path>    UTF-8 文件：每行一个查询，# 开头为注释，空行忽略；
                                         允许写成 <查询>TAB<附加信息>，TAB 之后本入口忽略
                                         （与 VectorQueryCli / Bm25QueryCli 同一份探针文件可用）
                  --query <text>         直接给一条文本（⚠️ 中文经 Windows argv 会被 GBK 解码成乱码）

                三档 Top-K（第 6 项要调的正是这四个数）：
                  --recall-top-k <n>     每通道召回条数（默认 30）
                  --fusion-top-k <n>     融合后保留条数（默认 30）
                  --rerank-top-n <n>     送重排后保留条数（默认 10；重排的输入是整份融合结果）
                  --top-k <n>            最终进上下文的条数（默认 5；**产物 JSON 的 topK 就是它**）

                融合与重排：
                  --fusion <name>        融合策略（默认 rrf；归一化加权是第 4 周的可选项，本项不实现）
                  --rrf-k <n>            RRF 的 k（默认 60，C-26）
                  --channels <a,b>       只跑指定通道，逗号分隔（默认 vector,bm25）。合法值只有
                                         vector / bm25；第 6 项四路对照用它跑单通道两档
                                         ⚠️ 允许空格、**大小写不敏感**（vector,VECTOR 等价），
                                         顺序即融合输入顺序（recallChannels 按你给的顺序写）
                                         ⚠️ 单路时**仍然走 RRF**（只改"跑了几路"这一个变量）：
                                         单路 RRF 序 = 该路原序，故 --channels vector --no-rerank
                                         的同题排名应与 VectorQueryCli 逐位一致
                                         ⚠️ 未被指定的那一侧**不会被构造**（不连库、不查档案）
                                         ⛔ 未知通道名 / 空元素 / 重复通道一律报错，不静默忽略
                                            （这三类都会让产物照样生成、指标照样算出来）
                  --no-rerank            关闭重排 = 消融的「无 rerank」对照档（此档不要求 DEEPSEEK_API_KEY）
                                         ⚠️ 开启重排时必须有 DEEPSEEK_API_KEY（读 ./.env 与 ../.env），
                                            缺失即报错；**不会**静默降级
                                         ⚠️ 本档**不按 rerank-top-n 截断上下文**：最终上下文直接取
                                            融合结果的前 --top-k 条（B1 修正：否则会出现
                                            「关掉重排反而让上下文变少」，且产物声明的 topK 与实际条数不符）
                  --rerank-retry <n>     重排的重试次数上限（默认 2，即最多 1 次首发 + 2 次额外尝试；
                                         0 = 不重试）。**只对**「输出无法解析为约定 JSON / 被 token 上限
                                         截断」重试；鉴权（缺 key、401）、网络、超时、空正文一律不重试
                                         （重试会把真故障掩盖成「试几次就好了」）。
                                         ⚠️ 重试过必须看得出来：产物每题带 rerankAttempts（本次共试几次）
                                         与 previousDegradeReasons（前几次失败原文，最后一次进
                                         degradeReason），顶层带 rerankAttemptsTotal；
                                         degradedQueryCount 只数「**重试后仍降级**」的条数
                                         ⚠️ attempts=1 不等于「一次成功」：它也可能是「这一档不重试」
                                         （空正文 / 超时 / 鉴权失败…），还可能是 --rerank-retry 0；
                                         stdout 与产物里的 degradeKind 会写清是哪一档、为何没重试
                  --dump-rerank <路径>   重排**诊断落盘**：把每次重排调用追加进该文件——
                                         实际发出的**提示词全文**、**原始应答全文**、finishReason、
                                         promptTokens / completionTokens / reasoningTokens、耗时(ms)
                                         为什么需要它：真实降级时产物里只有一句原因，而
                                         「额度被思考吃光」与「模型不听话」的处置相反，
                                         分不出就会去改错的那个旋钮（实测卡在这里过）
                                         ⚠️ **路径语法**非法 ⇒ 参数错误（退出码 2）；
                                         **写不进去**（如路径是个目录）⇒ 在**调模型之前**立刻
                                         失败（退出码 1，实测报「诊断落盘失败」）——
                                         不会跑完一整批才告诉你证据没留下
                                         文件在启动时清空重建（一次运行 = 一份证据）
                                         ⚠️ 它**不改变**额度/超时/重试这三样实验条件：
                                         开与不开，排序与产物必须一致
                                         ⛔ 它是**证据**不是评测基线（含耗时，不保证逐字节可复现）
                                         也可用 CANALMIND_DUMP_RERANK=<路径>（命令行优先）

                重排额度（⛔ 只影响重排这一条链路，不动主对话那条链路的 max-tokens）：
                  CANALMIND_RERANK_MAX_TOKENS
                                         重排输出的 maxTokens（默认 8192）。⚠️ 推理 token 与正文
                                         **共享**同一个预算，故提示词越长 / 候选越多越容易写不出正文：
                                         实测 rerankTopN=30 时额度 4096 ⇒ 提示词 8.7～9.6k 字符、
                                         finish_reason=LENGTH 且 completion=4096=maxTokens，
                                         3/3 全部降级（第四路根本跑不起来）
                                         非正整数（0 / -1 / 16k 之类）**一律报错**，不静默退回默认值：
                                         静默退回会让人以为额度已按预期生效
                                         ⚠️ 上限不是成本：max_tokens 只是截断线，按实际用量计费

                重排超时（⛔ 同样只影响重排这一条链路，不动主对话那条链路）：
                  CANALMIND_RERANK_TIMEOUT_SECONDS
                                          单次重排调用的等待上限，单位**秒**（默认 90）。
                                          实测 rerankTopN=30 档（30 条候选、提示词 8.7～9.6k 字符）
                                          40 题真实链路：单题重排耗时 最小 21.6 s / 均值 29.4 s /
                                          最大 43.5 s，且有一次**成功**的耗时 29.875 s
                                          ⇒ 原值 30 s **正好压在均值上**，会把大量本来能成功的
                                          调用判成「超时降级」，而产物看起来与「重排没效果」
                                          完全一样（都是降级 + 保留融合原序），故已调大到 90 s
                                          非正整数（0 / -5 / abc / 120s 之类）**一律报错**，
                                          不静默退回默认值：静默退回会让人以为超时已按预期生效
                                          ⚠️ 超时**不重试**（重试要再等一整个上限，40 题整批会被拖长）

                数据库与模型：
                  --profile <name>       向量索引档案（默认取 rag_chunk 实际引用的那个）
                  --token-profile <name> BM25 分词档案（默认：库里唯一才自动采用）
                  --segment-mode <m>     查询侧切分模式，必须与档案一致（默认 INDEX）
                  --ollama-url <url>     Ollama 地址（默认 $OLLAMA_BASE_URL 或 http://localhost:11434）
                  --model <name>         embedding 模型（默认 $OLLAMA_EMBEDDING_MODEL 或 bge-m3）
                  --url/--host/--port/--db/--user/--password   数据库连接（默认读 $POSTGRES_*）

                产物与自检：
                  --out <path>           写结果 JSON（UTF-8 无 BOM + 仅 LF、无时间戳，可字节级复现；
                                         键 channel/topK/queryCount/results 与 eval_retrieval.py 兼容）
                                         ⚠️ 若某题条数少于 --top-k，本入口会大声提示：
                                         eval_retrieval.py 硬校验「每题条数 == topK」，
                                         而产物刻意不补齐（补出来的候选是伪造的）
                  --selftest             只检查编排与产物契约，不连库、不调模型
                  --help, -h             显示帮助

                ⚠️ 评估约定（产物只含「最终进上下文的那几条」，务必按此读产物）：
                  • 产物的 hits 就是**最终上下文**（每题条数 = --top-k），顶层 topK 也是它
                    —— 它不是「召回了几条」、也不是「融合后剩几条」，那两档在 stageTrace 里
                  • 因此**要评估到 @k，就必须把 --top-k 设为 ≥ k**：
                    拿 5 条产物去算 HitRate@30，只会得到与 @5 相同的数（这是个**静默错数**）
                  • 该错数不会发生：eval_retrieval.py 有硬失败守卫（k > topK 直接 [FAIL]）。
                    ⛔ 不要去掉那个守卫，也不要用「自动补足条数」去绕过它——
                    补出来的命中是伪造的，产物会变成一份好看的假数据
                  • 第 6 项看排序请用「宽上下文评估档」（如 --top-k 30）；
                    ⚠️ 那**不代表**生产 context_top_n = 30，生产取值由饱和点决定

                  退出码：0 = 跑完（即使发生过重排降级，产物里会标 rerankDegraded）；
                          2 = **参数错误**（只打一行中文提示、不打印异常类名、不连库）；
                          1 = 运行期失败（连库 / embedding / 通道 / 模型）
                """);
    }
}
