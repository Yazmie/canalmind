package com.canalmind.rag.retrieve;

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
 * 混合检索结果的 JSON 契约（第 2 周第 5 项）。
 *
 * <h2>⚠️ 兼容性：这份产物的消费者是既有评测脚本</h2>
 *
 * <p>{@code backend/scripts/eval_retrieval.py} 的 {@code load_hits()} <b>按 schema 校验</b>，
 * 缺键即非 0 退出（不做猜测式容错）。故以下键是契约，<b>一个都不能少、大小写不能变</b>：
 *
 * <pre>
 * {
 *   "channel": "rrf+llm-rerank",
 *   "topK": 5,
 *   "queryCount": 2,
 *   "results": [
 *     { "query": "过闸费怎么算",
 *       "hits": [ {"rank": 1, "chunkId": "…", "score": 0.87, "docId": "…", …} ] }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code rank} 从 <b>1</b> 开始且<b>连续</b>（脚本会逐位断言 {@code h["rank"] == i}）；</li>
 *   <li>{@code score} 取<b>最终阶段</b>的分数：有重排分用 {@code rerankScore}，
 *       否则用 {@code fusedScore}（{@link RetrievalCandidate#finalScore()}）。
 *       降级与无 rerank 两档里 {@code rerankScore} 为 null，故自动落到融合分——
 *       这正是不给降级候选编造"名次分"的另一个好处；</li>
 *   <li>{@code queryCount} 必须等于 {@code results} 条数（脚本断言）；</li>
 *   <li><b>{@code topK} 记的是"最终上下文的请求条数"（= {@link RetrievalConfig#contextTopN()}）</b>，
 *       因为脚本用它当截断位上界（{@code if max(ks) > top_k: FAIL}），
 *       而 {@code hits} 的长度就是这一档。若这里写 {@code recallTopK}/{@code fusionTopK}，
 *       脚本会以为"请求了 30 条却只回 5 条"并按 k=30 去算 Precision@30——
 *       数字看着正常但口径已经错了。三档的完整取值另由顶层 {@code config} 键携带。</li>
 * </ul>
 *
 * <h2>⚠️⚠️ 第二道硬校验：每题条数必须**恰好等于** {@code topK}（2026-09-21 实测发现）</h2>
 *
 * <p>脚本除上界之外还有一条<b>等式</b>校验（{@code eval_retrieval.py} 第 318–327 行）：
 * {@code min(hits 条数) == max(hits 条数) == topK}。少一条就整份产物被判失败：
 * 因为指标是在"实际条数"上算的，声明 5 条而实际只有 3 条会得到
 * <b>看起来正常但偏低</b>的数字——正是本项目反复出过的"静默错数"族。
 *
 * <p>本类<b>不填数、不补齐</b>：{@code hits} 照实写（上游给了几条就是几条）。
 * 于是当某次运行没能把上下文填满（库里候选不足 {@code contextTopN}）时，
 * 产物会被脚本<b>明确拒绝</b>——这是刻意的：与其产出一份能被算分但口径已被削掉的基线，
 * 不如在评测入口就失败。调用方应在写盘前用
 * {@link #queriesNotFillingContext(RetrievalConfig, List)} 自查并<b>大声提示</b>
 * （{@code RetrievalQueryCli --out} 就是这么做的）。
 *
 * <p>⚠️ 空查询那条<b>不会</b>从本入口产生：{@code readQueries()} 会滤掉空行，
 * 所以正常基线里每题的条数应当恒等于 {@code topK}。
 *
 * <h2>额外键（允许，且刻意保留）</h2>
 *
 * <p>为了第 4/6 项的消融与排错，另加：顶层 {@code fusion} / {@code reranker} /
 * {@code recallChannels} / {@code config} / {@code rerankDegraded} / {@code degradedQueryCount} /
 * {@code rerankAttemptsTotal}；
 * 每条结果 {@code degraded} / {@code rerankDegraded} / {@code degradeKind} /
 * {@code rerankRecoveredByRetry} / {@code degradeReason} / {@code rerankAttempts} /
 * {@code previousDegradeReasons} / {@code rerankerModel} / {@code stageTrace}；
 * 每条命中 {@code fusedScore} / {@code rerankRank} / {@code rerankScore} /
 * {@code channelScores} / {@code channelRanks} 与全部溯源字段。
 *
 * <p>⚠️ <b>降级不改变 {@code channel} 字符串</b>（见 {@link RetrievalConfig#labelFor(List)}），
 * 而是由 {@code rerankDegraded}（整批是否最终仍有降级）、{@code degradedQueryCount}
 * 与每条结果的 {@code degraded}/{@code degradeReason} 三处表达。
 * 这样"没跑成"在产物里必定留下痕迹，不会被读成"rerank 没效果"。
 *
 * <h2>⚠️⚠️ 重试的三处痕迹（2026-09-21 补，B4 配套）：① 重试口径 ②`degradedQueryCount` 的准确含义</h2>
 *
 * <p>重排链路会<b>偶发降级</b>（实测 ~25%），故 {@link LlmReranker} 加了有界重试。
 * 一旦有了重试，"一次就成功"与"重试两次才成功"是两种不同的事实，产物必须能分辨，
 * 否则重试就把"链路不稳"洗成了"链路正常"。三处痕迹：
 * <ol>
 *   <li>每题 {@code rerankAttempts}：本次向模型发起了几次调用
 *       （{@code 0} = 一次都没调：关掉重排 / 空输入 / 候选超上限被拒）；</li>
 *   <li>每题 {@code previousDegradeReasons}：前几次失败的原文（按顺序）。
 *       <b>它的存在本身就是"重试过"的证据</b>——{@code degraded=false} 且
 *       该键非空 = "重试后才成功"，⛔ 不要读成一次成功。
 *       最后一次失败的原因仍在原有 {@code degradeReason}（最终成功时为 null）；</li>
 *   <li>顶层 {@code rerankAttemptsTotal}：本次所有查询的尝试次数之和
 *       （配合 {@code queryCount} 即可看出平均每次查询试了几回）。</li>
 * </ol>
 *
 * <p>⚠️ <b>{@code degradedQueryCount} 的准确含义是"重试后仍降级的条数"</b>
 * （{@code rerankDegraded} 同理是"整批是否仍有至少一条最终降级"）。
 * 它<b>不</b>统计"曾经失败过一次但重试成功"的条目——那种情况 {@code degraded=false}
 * 而 {@code rerankAttempts>1}。两者刻意分开统计：把重试成功的也算进降级数，
 * 会让人以为降级率没被重试改善；而把它们算成"完全没降级"又会掩盖链路不稳。
 *
 * <h2>⚠️⚠️ 逐题三态：{@code rerankDegraded} / {@code rerankRecoveredByRetry} / {@code degradeKind}</h2>
 *
 * <p>背景（2026-09-21 实测）：按 {@code rerankDegraded} 去读<b>逐题</b>状态时读到的是
 * <b>None</b>——原来这个键只在顶层，逐题那一位叫 {@code degraded}（名字不一致），
 * 于是"哪几题降级"只能再拿顶层计数去猜。本次补齐逐题判定键，语义<b>逐字</b>如下：
 *
 * <ul>
 *   <li>{@code rerankDegraded}（布尔，逐题）：<b>本题是否最终仍降级</b>（= 重试之后仍然降级）。
 *       与顶层 {@code rerankDegraded}、以及既有逐题键 {@code degraded} <b>同值同源</b>
 *       （都取自 {@code RetrievalResult.rerankDegraded()}）；
 *       {@code degraded} 是既有键，为兼容既有读法而保留，⛔ 两者不得各自计算——</li>
 *   <li>{@code degradeKind}（字符串或 null）：本题<b>最后一次失败</b>的分类，
 *       取值即 {@link RerankOutcome.FailureKind} 的枚举名
 *       （如 {@code BLANK_COMPLETION} = 空正文、{@code UNPARSABLE} = 输出无法解析或被截断、
 *       {@code TOO_MANY_CANDIDATES} = 候选超上限）；未降级时为 JSON {@code null}。
 *       ⚠️ 它同时回答了"为什么没重试"（{@code UNPARSABLE} 是全场唯一可重试的一档，
 *       其余取值都不重试），故排查时<b>先看这一个键</b>，不必去猜 {@code degradeReason} 的措辞；</li>
 *   <li>{@code rerankRecoveredByRetry}（布尔，逐题）：<b>本题是否"失败过、但重试后成功"</b>
 *       （= {@code !rerankDegraded && rerankAttempts > 1}）。
 *       ⛔ 它 <b>true</b> 时说明链路那一刻不稳、只是被重试兜住了，
 *       不许读成"链路一次跑通"。判定由 {@link RerankOutcome#recoveredByRetry()} 给出，
 *       本类不自己拼这个条件。</li>
 * </ul>
 *
 * <p><b>交叉核对规则</b>（三条，任一条不成立就说明产物自相矛盾，应当先查实现而不是信数字）：
 * <ol>
 *   <li>顶层 {@code degradedQueryCount} ≡ 逐题 {@code rerankDegraded=true} 的条数
 *       （顶层 {@code rerankDegraded} ≡ 该计数 &gt; 0）；</li>
 *   <li>{@code rerankRecoveredByRetry=true} 的题必有 {@code rerankDegraded=false}、
 *       {@code rerankAttempts>1}，且 {@code previousDegradeReasons} 非空
 *       （长度 = {@code rerankAttempts - 1}）；</li>
 *   <li>逐题四态<b>互斥且穷尽</b>：
 *       <table border="1">
 *         <caption>逐题四态（把这些键合起来读）</caption>
 *         <tr><th>rerankDegraded</th><th>rerankRecoveredByRetry</th>
 *             <th>rerankAttempts</th><th>读作</th></tr>
 *         <tr><td>true</td><td>false</td><td>1</td>
 *             <td>降级且<b>未重试</b>（这一档不重试，或 --rerank-retry 0）——
 *                 看 {@code degradeKind} 定处置</td></tr>
 *         <tr><td>true</td><td>false</td><td>&gt;1</td>
 *             <td>降级且<b>重试耗尽</b>（前几次原因在 {@code previousDegradeReasons}）</td></tr>
 *         <tr><td>false</td><td>true</td><td>&gt;1</td>
 *             <td><b>重试后才成功</b>（⛔ 不是一次成功）</td></tr>
 *         <tr><td>false</td><td>false</td><td>1</td><td>一次成功</td></tr>
 *         <tr><td>false</td><td>false</td><td>0</td>
 *             <td>压根没调模型（关掉重排 / 空输入 / 候选超上限被拒）</td></tr>
 *       </table>
 *   </li>
 * </ol>
 *
 * <h2>编码与换行：自己钉死，不跟随平台</h2>
 *
 * <p>照 {@code VectorResultJson} 的做法（AGENTS.md §7.2）：Jackson 的
 * {@code DefaultPrettyPrinter} 默认缩进器是 {@code DefaultIndenter.SYS_LF}
 * （= {@code System.lineSeparator()}），Windows 上返回的字符串里<b>就已经含 CR+LF</b>，
 * 故 {@code indentObjectsWith} 与 {@code indentArraysWith} <b>两个都要</b>设成 LF 缩进器
 * （只设一个不够，实测对象级换行仍是 CRLF）。写盘用
 * {@code Files.write(path, bytes)} 而不是 {@code writeString}，把换行与编码的决定权留在自己手里。
 *
 * <p><b>产物里没有任何时间戳</b>：重排延迟只打 stdout（{@link RerankOutcome#latencyMillis()}），
 * 不进 JSON——它每次都不同，进产物会让 diff 永远不为空。
 */
public final class RetrievalResultJson {

    /** 产物换行：用计算字符而非转义字面量（AGENTS.md §7.2 第 1 条）。 */
    private static final String LF = String.valueOf((char) 10);

    /** 契约必需键（下游 {@code eval_retrieval.py} 按此校验）。 */
    public static final List<String> REQUIRED_KEYS = List.of("channel", "topK", "queryCount", "results");

    /** 每条命中的必需键。 */
    public static final List<String> REQUIRED_HIT_KEYS = List.of("rank", "chunkId", "score");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private RetrievalResultJson() {
    }

    /**
     * 自查：哪些查询的实际条数<b>没填满</b> {@code contextTopN}。
     *
     * <p>存在的唯一理由是"下游的第二道硬校验"（见类注释）：脚本要求每题条数恰好等于
     * {@code topK}，而本类刻意<b>不补齐</b>条数。故调用方写盘前应当先问一次，
     * 若结果非空就<b>大声提示</b>——否则用户会拿到一份"能生成但一算就 FAIL"的产物，
     * 而 FAIL 信息里只有截断后的题面，定位起来很绕。
     *
     * @return 实际条数 ≠ {@code contextTopN} 的查询原文（按输入顺序；空表表示全部填满）
     */
    public static List<String> queriesNotFillingContext(RetrievalConfig config,
                                                        List<RetrievalResult> results) {
        Objects.requireNonNull(config, "config");
        List<String> out = new ArrayList<>();
        if (results == null) {
            return List.of();
        }
        for (RetrievalResult r : results) {
            if (r.context().size() != config.contextTopN()) {
                out.add(r.query());
            }
        }
        return List.copyOf(out);
    }

    /**
     * 渲染成 JSON 文本（含末尾一个 LF）。
     *
     * @param config         本次配置（提供 {@code channel} 标签、四档条数、k 与开关）
     * @param recallChannels 参与的通道名（按构造顺序），写进 {@code recallChannels} 键
     * @param results        每个查询一段
     */
    public static String render(RetrievalConfig config, List<String> recallChannels,
                                List<RetrievalResult> results) {
        Objects.requireNonNull(config, "config");
        List<String> channelNames = recallChannels == null ? List.of() : List.copyOf(recallChannels);
        List<RetrievalResult> list = results == null ? List.of() : results;

        Map<String, Object> root = new LinkedHashMap<>();
        // 前四个键就是契约里的四个必需键，顺序与 VectorResultJson 一致（channel/topK/queryCount/results）
        // ⚠️ 用 labelFor(实际通道) 而不是 channelLabel()：单通道跑（第 6 项四路对照）时
        // channelLabel() 会写死成 rrf，产物就再也分不清它是 vector 档还是 bm25 档——
        // 而这两档的指标长得像、含义完全不同。
        root.put("channel", config.labelFor(channelNames));
        root.put("topK", config.contextTopN());
        root.put("queryCount", list.size());
        root.put("results", resultNodes(list));

        Map<String, Object> configNode = new LinkedHashMap<>();
        configNode.put("recallTopK", config.recallTopK());
        configNode.put("fusionTopK", config.fusionTopK());
        configNode.put("rerankTopN", config.rerankTopN());
        configNode.put("contextTopN", config.contextTopN());
        configNode.put("rrfK", config.rrfK());
        configNode.put("rerankEnabled", config.rerankEnabled());

        root.put("fusion", config.fusionName());
        root.put("reranker", config.rerankEnabled() ? config.rerankerName() : RerankOutcome.NO_MODEL);
        root.put("recallChannels", channelNames);
        root.put("config", configNode);
        int degradedCount = 0;
        int attemptsTotal = 0;
        for (RetrievalResult r : list) {
            // ⚠️ 只数"最终仍降级"的：degraded=false 但 attempts>1（重试后成功）不进来，
            //    那是另一件事，由 rerankAttempts / previousDegradeReasons 表达
            if (r.rerankDegraded()) {
                degradedCount++;
            }
            attemptsTotal += r.rerankOutcome().attempts();
        }
        root.put("rerankDegraded", degradedCount > 0);
        root.put("degradedQueryCount", degradedCount);
        root.put("rerankAttemptsTotal", attemptsTotal);

        try {
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            DefaultIndenter lfIndenter = new DefaultIndenter("  ", LF);
            printer.indentObjectsWith(lfIndenter);
            printer.indentArraysWith(lfIndenter);
            return MAPPER.writer(printer).writeValueAsString(root) + LF;
        } catch (IOException e) {
            // 写内存字符串理论上不会 IO 失败；真失败说明 mapper 配置有问题，不能静默吞掉
            throw new IllegalStateException("检索结果无法序列化为 JSON", e);
        }
    }

    /**
     * 写文件：UTF-8 无 BOM + 仅 LF。目录不存在时自动创建（{@code --out docs/评测报告/xxx.json} 的首跑）。
     */
    public static void write(Path out, String json) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(json, "json");
        Path absolute = out.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(absolute, json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Object> resultNodes(List<RetrievalResult> results) {
        List<Object> nodes = new ArrayList<>(results.size());
        for (RetrievalResult r : results) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("query", r.query());
            List<RetrievalCandidate> hits = r.context();
            List<Object> hitNodes = new ArrayList<>(hits.size());
            int rank = 1;   // 契约：rank 从 1 开始且连续
            for (RetrievalCandidate c : hits) {
                hitNodes.add(hitNode(rank++, c));
            }
            node.put("hits", hitNodes);
            // ⚠️ 逐题降级位有两个键、只有一处取值：
            //    `degraded` 是既有键（既有读法/脚本已按它读），`rerankDegraded` 是 2026-09-21 补的
            //    ——它与顶层同名，因为"按 rerankDegraded 读逐题"是最自然的读法，
            //    而当时逐题只有 degraded，于是读到 None。两者都由
            //    RetrievalResult.rerankDegraded() 给出，⛔ 不许各自计算（同事实两个写法必然分叉）。
            node.put("degraded", r.rerankDegraded());
            node.put("rerankDegraded", r.rerankDegraded());
            // 重试后成功的判定交给 RerankOutcome 一处给出（本类不自己拼 !degraded && attempts>1）
            node.put("rerankRecoveredByRetry", r.rerankOutcome().recoveredByRetry());
            // 失败分类（枚举名；未降级为 null）。它是"为什么没重试 / 该改哪个旋钮"的机器可读依据，
            // 取值与语义见 RerankOutcome.FailureKind
            node.put("degradeKind", r.rerankOutcome().degraded()
                    ? r.rerankOutcome().failureKind().name() : null);
            // 未降级时写 JSON null（与 VectorResultJson 里 articleNo 的处理一致：
            // 键必须在，值可以为 null——下游据此区分"键缺失"与"本次没有原因"）
            node.put("degradeReason", r.rerankOutcome().degradeReason());
            // ⚠️ 这两个键是"重试过"的原始证据：rerankDegraded=false 且 rerankAttempts>1
            //    （或 previousDegradeReasons 非空）读作"重试后才成功"，⛔ 不是一次成功。
            //    键名由本次交付逐字约定，别改名。
            //    ⚠️ 2026-09-21：该组合另有一个显式布尔 rerankRecoveredByRetry（见上），
            //    因为"要读者自己拼两个键"这件事已经错过一次（读到 None）。
            node.put("rerankAttempts", r.rerankOutcome().attempts());
            node.put("previousDegradeReasons", r.rerankOutcome().previousDegradeReasons());
            node.put("rerankerModel", r.rerankOutcome().model());
            node.put("fusedCount", r.fused().size());
            node.put("stageTrace", traceNode(r));
            nodes.add(node);
        }
        return nodes;
    }

    private static Map<String, Object> traceNode(RetrievalResult r) {
        Map<String, Object> trace = new LinkedHashMap<>();
        r.stageTrace().forEach((k, v) -> trace.put(k, v));
        return trace;
    }

    private static Map<String, Object> hitNode(int rank, RetrievalCandidate c) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("rank", rank);
        node.put("chunkId", c.chunkId());
        node.put("score", c.finalScore());
        node.put("docId", c.docId());
        node.put("docTitle", c.docTitle());
        node.put("chunkType", c.chunkType());
        node.put("articleNo", c.articleNo());
        node.put("articleTitle", c.articleTitle());
        node.put("version", c.version());
        node.put("fusedScore", c.fusedScore());
        node.put("rerankRank", c.rerankRank());
        node.put("rerankScore", c.rerankScore());
        node.put("channelScores", c.channelScores());
        node.put("channelRanks", c.channelRanks());
        return node;
    }
}
