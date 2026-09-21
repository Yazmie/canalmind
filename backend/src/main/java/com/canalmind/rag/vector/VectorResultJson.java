package com.canalmind.rag.vector;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向量检索结果的 JSON 契约（第 2 周第 5 项）。
 *
 * <p><b>为什么单独一个类型而不是写在 CLI 里</b>（对照 {@code ChunkManifestCli} 的直接内联写法）：
 * 这份 JSON 是<b>接口</b>而不是日志——第 4 周的评测脚本、两通道消融与 RRF 融合都要解析它。
 * 接口需要能被独立测试（键名、rank 起点、换行、编码），而 CLI 的 {@code main} 不好测。
 * 用 {@code --out} 写文件的那段逻辑一旦绕过测试，最典型的坏结果是
 * "键名大小写变了、下游脚本静默少读到一列"。
 *
 * <h2>契约（下游脚本按此解析，键名大小写敏感）</h2>
 *
 * <pre>
 * {
 *   "channel": "vector",
 *   "profileName": "ollama:bge-m3:1024",
 *   "topK": 30,
 *   "queryCount": 3,
 *   "results": [
 *     { "query": "过闸费怎么算",
 *       "hits": [ {"rank": 1, "chunkId": "…", "docId": "…", "docTitle": "…",
 *                  "chunkType": "BODY", "articleNo": "第一条", "articleTitle": "…",
 *                  "version": "2026", "score": 0.812345} ] }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code rank} 从 <b>1</b> 开始（不是 0）；</li>
 *   <li>{@code score} 是 double（余弦相似度，越大越相关，见 {@link VectorHit#score()}）；</li>
 *   <li>{@code articleNo} / {@code articleTitle} 可能是 {@code null}（文档级 chunk 没有条号）；</li>
 *   <li><b>产物里没有任何时间戳</b>：为了字节级可复现（两次相同输入的运行 diff 必须为空），
 *       运行耗时只打 stdout，不进 JSON。</li>
 * </ul>
 *
 * <h2>编码与换行：自己钉死，不跟随平台</h2>
 *
 * <p>两个坑本项目都真实踩过（AGENTS.md §7.2）：
 * <ol>
 *   <li>Jackson 的 {@code DefaultPrettyPrinter} 默认缩进器是
 *       {@code DefaultIndenter.SYS_LF}（= {@code System.lineSeparator()}），
 *       在 Windows 上 {@code writeValueAsString} 返回的<b>字符串里就已经含 CR+LF</b>，
 *       于是 {@code *.json eol=lf} 的约定被悄悄破坏、"每跑一次换行就变一次"。
 *       故显式把对象缩进与数组缩进<b>都</b>设为 LF（只设一个不够）；</li>
 *   <li>写文件用 {@code Files.write(path, bytes)} 而不是 {@code Files.writeString}：
 *       把换行与编码的最终决定权留在自己手里。
 *       {@code getBytes(UTF_8)} 永远不写 BOM（BOM 只会由显式拼接产生）。</li>
 * </ol>
 */
public final class VectorResultJson {

    /** 通道标识：写进 JSON 的 {@code channel} 字段，也是与 BM25 通道对比时的键。 */
    public static final String CHANNEL = "vector";

    /** 产物换行：用计算字符而非转义字面量（AGENTS.md §7.2 第 1 条）。 */
    private static final String LF = String.valueOf((char) 10);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private VectorResultJson() {
    }

    /**
     * 一条查询的结果。
     *
     * @param query 查询文本（探针文件里那一行；下游脚本靠它把结果对回题目）
     * @param hits  命中列表，顺序即名次（rank 由位置推出，不依赖数据库返回顺序之外的任何东西）
     */
    public record QueryHits(String query, List<VectorHit> hits) {

        public QueryHits {
            hits = List.copyOf(hits == null ? List.of() : hits);
        }
    }

    /**
     * 渲染成 JSON 文本（含末尾一个 LF）。
     *
     * @param profileName 索引档案名（如 {@code ollama:bge-m3:1024}），不得为空白
     * @param topK        本次请求的 Top-K（是<b>请求值</b>而不是实际命中数：
     *                    下游靠它区分「就没那么多条」与「参数配小了」）
     * @param results     每个查询一段
     */
    public static String render(String profileName, int topK, List<QueryHits> results) {
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException(
                    "profileName 不得为空：它是 JSON 契约里声明「这批向量由谁产出」的字段，"
                            + "缺了它下游无法判断结果出自哪次索引");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
        List<QueryHits> list = results == null ? List.of() : results;

        // 用 LinkedHashMap 而不是 record/DTO：键序就是契约里的书写序，
        // 任何人改字段顺序都会在 JSON 里直接可见（而不是"靠 Jackson 的字母序碰巧对"）。
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("channel", CHANNEL);
        root.put("profileName", profileName);
        root.put("topK", topK);
        root.put("queryCount", list.size());

        List<Object> resultNodes = new ArrayList<>(list.size());
        for (QueryHits q : list) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("query", q.query());
            List<Object> hits = new ArrayList<>(q.hits().size());
            int rank = 1;   // 契约：rank 从 1 开始
            for (VectorHit hit : q.hits()) {
                hits.add(hitNode(rank++, hit));
            }
            node.put("hits", hits);
            resultNodes.add(node);
        }
        root.put("results", resultNodes);

        try {
            // 缩进器显式指定 LF：默认的 SYS_LF 在 Windows 上会产出 CRLF（见类注释）。
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            DefaultIndenter lfIndenter = new DefaultIndenter("  ", LF);
            printer.indentObjectsWith(lfIndenter);
            printer.indentArraysWith(lfIndenter);
            return MAPPER.writer(printer).writeValueAsString(root) + LF;
        } catch (IOException e) {
            // 写内存字符串理论上不会 IO 失败；真失败说明 mapper 配置有问题，不能静默吞掉
            throw new IllegalStateException("向量检索结果无法序列化为 JSON", e);
        }
    }

    /**
     * 写文件：UTF-8 无 BOM + 仅 LF。
     *
     * <p>目标目录不存在时自动创建（{@code --out docs/评测报告/xxx.json} 这类路径的首跑）。
     */
    public static void write(Path out, String json) throws IOException {
        Objects.requireNonNull(out, "out 不得为空");
        Objects.requireNonNull(json, "json 不得为空");
        Path absolute = out.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(absolute, json.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> hitNode(int rank, VectorHit hit) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rank", rank);
        node.put("chunkId", hit.chunkId());
        node.put("docId", hit.docId());
        node.put("docTitle", hit.docTitle());
        node.put("chunkType", hit.chunkType());
        node.put("articleNo", hit.articleNo());
        node.put("articleTitle", hit.articleTitle());
        node.put("version", hit.version());
        node.put("score", hit.score());
        return node;
    }
}
