package com.canalmind.rag.split;

import com.canalmind.rag.split.ChunkManifest.Statistics;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 切分器命令行入口。
 *
 * <p>用法（两种策略分别产出清单，供第 4 周消融对比）：
 * <pre>
 *   # 结构化切分（C-21 主链路）
 *   java -cp &lt;classes:deps&gt; com.canalmind.rag.split.ChunkManifestCli --out docs/评测报告/chunks-structure.json
 *
 *   # 固定长度基线（C-21 要求的对照物，缺它则消融无意义）
 *   java -cp &lt;classes:deps&gt; com.canalmind.rag.split.ChunkManifestCli --strategy baseline --out docs/评测报告/chunks-baseline.json
 *
 *   # 参数与说明
 *   ... ChunkManifestCli --help
 * </pre>
 *
 * <p>参数默认值指向仓库内的语料目录，路径按"从工作目录向上找 docs/规则语料"解析——
 * 因为 Maven 从 {@code backend/} 运行、而 IDE 可能从仓库根运行，
 * 与 {@code application.yml} 里 {@code .env} 双路径导入是同一类问题。
 */
public final class ChunkManifestCli {

    /** 产物换行：用计算字符而非转义字面量（理由见 AGENTS.md §7.2「Java 换行/转义写法纪律」）。 */
    private static final String LF = String.valueOf((char) 10);

    private ChunkManifestCli() {
    }

    public static void main(String[] args) throws IOException {
        Path corpusDir = null;
        Path out = null;
        String strategy = "structure";
        boolean json = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus" -> corpusDir = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--strategy" -> strategy = args[++i].toLowerCase(Locale.ROOT);
                case "--no-json" -> json = false;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    printUsage();
                    System.exit(2);
                }
            }
        }

        if (corpusDir == null) {
            corpusDir = locateCorpusDir();
        }
        if (corpusDir == null || !Files.isDirectory(corpusDir)) {
            System.err.println("找不到语料目录，请用 --corpus 指定");
            System.exit(2);
            return;
        }

        ChunkingProperties props = ChunkingProperties.defaults();
        Splitter splitter = "baseline".equals(strategy)
                ? new FixedLengthBaselineSplitter(props)
                : new ChunkStructureSplitter(props);

        List<Path> files = DocumentParser.listCorpusFiles(corpusDir);
        List<CorpusDocument> docs = new ArrayList<>();
        for (Path f : files) {
            docs.add(DocumentParser.load(f));
        }

        List<List<Chunk>> perDoc = new ArrayList<>();
        for (CorpusDocument d : docs) {
            perDoc.add(splitter.split(d));
        }

        // 基线统计：无论当前跑哪种策略都一并算出，便于在同一份报告里对照
        List<Chunk> baselineChunks = new ArrayList<>();
        Splitter baseline = new FixedLengthBaselineSplitter(props);
        for (CorpusDocument d : docs) {
            baselineChunks.addAll(baseline.split(d));
        }
        Statistics baselineStats = ManifestReporter.statistics(baselineChunks, 0);

        ChunkManifest manifest = ManifestReporter.build(
                splitter.strategyName(), splitter.version(), props, perDoc, docs, baselineStats);

        System.out.println(ManifestReporter.render(manifest));

        if (json && out != null) {
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            // ⚠️ 必须显式指定换行，**不能用默认的 pretty printer**。
            // 实测（WriteProbe3）：Jackson 2.19 的 DefaultPrettyPrinter 默认缩进器用的是
            // `DefaultIndenter.SYS_LF`（= System.lineSeparator()），在 Windows 上就是 CRLF，
            // 于是 writeValueAsString 产出的**字符串里本身就含 <CR><LF>**
            // ——问题不在 Files.writeString，而在序列化阶段。
            // 后果：`.gitattributes` 声明 `*.json eol=lf`，而产物是 CRLF，
            // 「每跑一次就生成一份换行不同的产物」，污染 diff、也让评测基线不稳定。
            // 这里把对象缩进与数组缩进**都**钉成 LF（只改一个不够，见 WriteProbe3 实测）。
            // 依据：AGENTS.md §8.5 编码约定（无 BOM + LF）。
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            DefaultIndenter lfIndenter = new DefaultIndenter("  ", LF);
            printer.indentObjectsWith(lfIndenter);
            printer.indentArraysWith(lfIndenter);
            // 依赖 getter 而非字段，避免 private 字段反射的额外可见性配置
            String jsonText = mapper.writer(printer).writeValueAsString(manifest);
            // 用字节写出而非 Files.writeString：把换行的最终决定权留在我们手里。
            Files.write(out, jsonText.getBytes(StandardCharsets.UTF_8));
            System.out.println("已写出清单: " + out.toAbsolutePath());
            System.out.println("清单字节数: " + Files.size(out));
        }
    }

    /**
     * 从当前工作目录向上查找 {@code docs/规则语料}。
     *
     * <p>同样的问题本项目在 {@code .env} 上踩过一次（见 {@code AGENTS.md} §7.2）：
     * "工作目录随启动方式变化"。故这里不写死相对层级，而是向上探测。
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

    private static void printUsage() {
        System.out.println("""
                用法: ChunkManifestCli [选项]
                  --corpus <dir>      语料目录（默认自动向上查找 docs/规则语料）
                  --out <file.json>   写出 chunk 清单 JSON
                  --strategy <s>      structure（默认，C-21 主链路）| baseline（固定长度基线）
                  --no-json           只打印报告，不写文件
                  --help, -h          显示帮助
                """);
    }

    /** 供测试调用：不经过 main，直接产出清单。 */
    public static ChunkManifest runStructure(Path corpusDir, ChunkingProperties props) {
        return run(corpusDir, props, new ChunkStructureSplitter(props));
    }

    static ChunkManifest run(Path corpusDir, ChunkingProperties props, Splitter splitter) {
        List<Path> files = DocumentParser.listCorpusFiles(corpusDir);
        List<CorpusDocument> docs = new ArrayList<>();
        List<List<Chunk>> perDoc = new ArrayList<>();
        try {
            for (Path f : files) {
                CorpusDocument d = DocumentParser.load(f);
                docs.add(d);
                perDoc.add(splitter.split(d));
            }
        } catch (UncheckedIOException e) {
            throw e;
        }
        List<Chunk> baselineChunks = new ArrayList<>();
        Splitter baseline = new FixedLengthBaselineSplitter(props);
        for (CorpusDocument d : docs) {
            baselineChunks.addAll(baseline.split(d));
        }
        return ManifestReporter.build(splitter.strategyName(), splitter.version(), props,
                perDoc, docs, ManifestReporter.statistics(baselineChunks, 0));
    }
}
