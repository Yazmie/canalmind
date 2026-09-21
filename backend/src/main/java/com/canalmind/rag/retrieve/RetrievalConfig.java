package com.canalmind.rag.retrieve;

import java.util.List;

/**
 * 检索配置：<b>三档 Top-K</b> 与融合/重排开关（第 2 周第 5 项，第 6 项要调的正是它）。
 *
 * <h2>为什么是"三档 + 最终上下文"四个数，而不是一个 topK</h2>
 *
 * <p>一条混合检索链路上有四个不同的"条数"，它们回答的是不同问题：
 * <table border="1">
 *   <caption>四个条数各自的作用</caption>
 *   <tr><th>字段</th><th>含义</th><th>调大它意味着</th></tr>
 *   <tr><td>{@code recallTopK}</td><td>每个通道各召回多少条</td>
 *       <td>给融合更多原料（Recall 的<b>上界</b>由它决定，知识库《RAG 优化》第 178 行：
 *           Rerank 补不回候选池里没有的文档）</td></tr>
 *   <tr><td>{@code fusionTopK}</td><td>融合后保留多少条</td>
 *       <td>决定进入重排的候选池大小，也决定重排请求的长度与成本</td></tr>
 *   <tr><td>{@code rerankTopN}</td><td>送重排后保留多少条</td>
 *       <td>重排结果里留几条（重排的输入是整份融合结果，见
 *           {@link RetrievalPipeline}）</td></tr>
 *   <tr><td>{@code contextTopN}</td><td><b>最终进上下文的条数</b></td>
 *       <td>真正给生成模型看几条——Context Precision 与 token 成本由它决定</td></tr>
 * </table>
 *
 * <p>用一个 topK 代替，等于把"召回多少 / 重排多少 / 喂几条"强行绑成同一个数：
 * 想给融合更多原料就必须同时把上下文撑大、把重排请求变长，
 * 于是**无法区分"是候选池不够"还是"上下文被稀释"**——这正是第 4 周消融要解的问题。
 * 知识库《RAG 优化》第 151 行也要求"三档 Top-K 应在同一评估集上一起调整"，
 * 说明它们是<b>独立可调</b>的旋钮。
 *
 * <h2>校验规则（构造时即失败，不留到运行期）</h2>
 *
 * <p>所有条数都必须 > 0；且：
 * <ol>
 *   <li>{@code rerankTopN <= fusionTopK}：重排的输入只能来自融合结果，
 *       {@code rerankTopN} 配大了这一档就只能空着；</li>
 *   <li>{@code contextTopN <= (rerankEnabled ? rerankTopN : fusionTopK)}：
 *       最终上下文依次取自"重排结果"或"融合结果"，超出上游条数的名额<b>永远填不满</b>。</li>
 * </ol>
 *
 * <p>⚠️ {@code fusionTopK <= 通道数 × recallTopK} <b>刻意不强制</b>：那是"总和"关系，
 * 而融合后的条数取决于两路的重叠程度（并集），重叠多时并集本来就小——
 * 拿总和不等于校验错误，若强行拒绝，正常的重叠场景会被误判为配置非法。
 *
 * <p>这些规则不写在命令行解析里而写在 record 的紧凑构造器里：配置是<b>数据边界</b>，
 * 钉在这里（AGENTS.md §7.2 第 4 条），任何构造路径（CLI / 测试 / 将来的 Spring 装配）
 * 都不可能绕过。
 *
 * @param recallTopK     每个通道召回条数
 * @param fusionTopK     融合后保留条数
 * @param rerankTopN     重排档保留条数（重排**输出**的上限；重排的输入是整份融合结果）。
 *                       ⚠️ 它**只在 {@code rerankEnabled=true} 时起作用**：关闭重排时本值
 *                       不参与任何截断，上游直接是融合结果，故上下文只受 {@code fusionTopK} 约束
 *                       （见 {@link RetrievalPipeline} 的 B1 修正——否则会出「产物声明 topK=30、
 *                       每题实际只有 10 条」这类静默错数）
 * @param contextTopN    最终进上下文的条数
 * @param rrfK           RRF 的 k（C-26：默认 60）
 * @param rerankEnabled  是否启用重排。false 时编排**不调用任何重排器**（连
 *                       {@link NoopReranker} 也不调），直接以融合结果作为该阶段结果；
 *                       命令行仍注入 NoopReranker，只是为了给这一档一个可读的档位名
 * @param fusionName     融合策略名，必须与 {@link RetrievalPipeline} 收到的 {@link FusionStrategy#name()} 一致
 * @param rerankerName   重排器名，同上（与 {@link Reranker#name()} 一致）
 */
public record RetrievalConfig(
        int recallTopK,
        int fusionTopK,
        int rerankTopN,
        int contextTopN,
        int rrfK,
        boolean rerankEnabled,
        String fusionName,
        String rerankerName) {

    public RetrievalConfig {
        requirePositive("recallTopK", recallTopK);
        requirePositive("fusionTopK", fusionTopK);
        requirePositive("rerankTopN", rerankTopN);
        requirePositive("contextTopN", contextTopN);
        requirePositive("rrfK", rrfK);
        if (fusionName == null || fusionName.isBlank()) {
            throw new IllegalArgumentException(
                    "fusionName 不得为空：它会写进评测产物的 channel 字段，"
                            + "空名字会让两次消融的产物无法区分（融合策略是 C-26 的消融变量之一）");
        }
        if (rerankerName == null || rerankerName.isBlank()) {
            throw new IllegalArgumentException(
                    "rerankerName 不得为空：它会写进评测产物的 channel 字段，"
                            + "空名字会让「有 rerank」与「无 rerank」两档产物无法区分");
        }
        if (rerankTopN > fusionTopK) {
            throw new IllegalArgumentException(
                    "rerankTopN(" + rerankTopN + ") 不得大于 fusionTopK(" + fusionTopK + ")："
                            + "重排的输入只能来自融合结果（融合后才有 fusionTopK 条），"
                            + "配大了这一档就会长期不满——表现为「重排后条数莫名少几条」，而不是报错。");
        }
        int upstream = rerankEnabled ? rerankTopN : fusionTopK;
        String upstreamName = rerankEnabled ? "rerankTopN" : "fusionTopK";
        if (contextTopN > upstream) {
            throw new IllegalArgumentException(
                    "contextTopN(" + contextTopN + ") 不得大于 " + upstreamName + "(" + upstream
                            + ")：rerankEnabled=" + rerankEnabled + " 时最终上下文取自"
                            + (rerankEnabled ? "重排结果" : "融合结果") + "，"
                            + "超出上游条数的名额永远填不满——表现为「上下文莫名只有几条」，而不是报错。"
                            + "若确实想要更多上下文，请同时把 " + upstreamName + " 调大"
                            + "（或打开 rerankEnabled，两者是独立旋钮，见类型注释的三档说明）。");
        }
    }

    /**
     * 第 2 周第 5 项的默认配置。
     *
     * <p>取值与理由：
     * <ul>
     *   <li>{@code recallTopK = 30} —— 与既有两个 CLI 的默认 Top-K（30）对齐，
     *       保证"单通道基线"与"融合输入"用同一个召回深度，两边的数字才可比；</li>
     *   <li>{@code fusionTopK = 30} —— 不额外截断。融合本身就是一次排序，
     *       在没有证据说明"融合的前 30 条之外还有有用的东西"之前，
     *       多截一次只会让第 4 周多一个待解释的变量；</li>
     *   <li>{@code rerankTopN = 10} —— 重排的成本与提示词长度成正比，
     *       而重排的收益集中在头部（把正确的那条从第 7 提到第 1），
     *       留 10 条足够覆盖"进上下文"的前几名；</li>
     *   <li>{@code contextTopN = 5} —— 本项目语料是条款级短文本，
     *       5 条约等于"一条主依据 + 几条相关条款"，再多会稀释提示词；</li>
     *   <li>{@code rrfK = 60} —— C-26 原文（经典默认值）；</li>
     *   <li>{@code rerankEnabled = true} —— C-27 的主实现，
     *       但"无 rerank"档必须用 {@code --no-rerank} 显式选择（见 {@link LlmRerankerFactory}）。</li>
     * </ul>
     */
    public static RetrievalConfig defaults() {
        return new RetrievalConfig(30, 30, 10, 5, RrfFusion.DEFAULT_K, true,
                RrfFusion.NAME, LlmReranker.NAME);
    }

    /** 关掉重排（= 消融的「无 rerank」档），其余不变。会重新走一遍校验。 */
    public RetrievalConfig withoutRerank() {
        return new RetrievalConfig(recallTopK, fusionTopK, rerankTopN, contextTopN, rrfK, false,
                fusionName, rerankerName);
    }

    /** 打开重排，其余不变。 */
    public RetrievalConfig withRerank() {
        return new RetrievalConfig(recallTopK, fusionTopK, rerankTopN, contextTopN, rrfK, true,
                fusionName, rerankerName);
    }

    /** 改最终上下文条数（第 6 项调参的主要入口），其余不变。 */
    public RetrievalConfig withContextTopN(int newContextTopN) {
        return new RetrievalConfig(recallTopK, fusionTopK, rerankTopN, newContextTopN, rrfK,
                rerankEnabled, fusionName, rerankerName);
    }

    /** 改召回条数，其余不变。 */
    public RetrievalConfig withRecallTopK(int newRecallTopK) {
        return new RetrievalConfig(newRecallTopK, fusionTopK, rerankTopN, contextTopN, rrfK,
                rerankEnabled, fusionName, rerankerName);
    }

    /**
     * 写进产物 {@code channel} 字段的通道标识。
     *
     * <p>取值形态：无 rerank 时是 {@code rrf}，启用时是 {@code rrf+llm-rerank}
     * ——让"这一份产物是哪个消融档"从文件名之外也能读出来。
     *
     * <p>⚠️ <b>降级不会改变这个字符串</b>（这是刻意的）：降级是"本次没跑成"，
     * 不是"配置换了"。它由产物里独立的 {@code rerankDegraded}（顶层 + 逐题）/ {@code degraded} /
     * {@code degradeKind} / {@code degradedQueryCount} 几个键表达，并由 CLI 在 stdout 打醒目提示。
     * 把降级塞进 channel 名字里会让字符串变得无法穷举，反而不好用。
     *
     * <p>⚠️ 本方法<b>假设跑了两路</b>（它就是 {@code rrf} 这个取值的来源）。
     * 单通道运行时必须改用 {@link #labelFor(List)}：那种情况下 {@code rrf} 是<b>假话</b>
     * ——单路也会走 RRF，但"哪一路"才是这份产物唯一的身份信息。
     */
    public String channelLabel() {
        return rerankEnabled ? fusionName + "+" + rerankerName : fusionName;
    }

    /**
     * 按<b>实际跑的通道</b>生成写进产物 {@code channel} 字段的标识（第 6 项四路对照用）。
     *
     * <p>规则：
     * <ul>
     *   <li>两路 → 用融合策略名（{@code rrf}）；单路 → 用<b>通道名</b>（{@code vector} / {@code bm25}），
     *       因为"到底只跑了哪一路"比"用了哪种融合"更值得被记住；</li>
     *   <li>启用重排时再追加 {@code +<重排器名>}（{@code vector+llm-rerank} 等），
     *       于是从 {@code channel} 一个字段就能同时读出<b>通道集</b>与<b>是否有重排</b>；
     *   </li>
     *   <li>降级同样不改它（理由同 {@link #channelLabel()}）。</li>
     * </ul>
     *
     * <p>⚠️ 为什么不直接写死：本项目已经吃过"产物里缺『用了什么口径』字段"的亏
     * （见 {@code docs/工程复盘/检索工程/度量口径没进产物}）——两份产物无法区分，
     * 只能靠文件名或人的记忆。四路对照尤其危险：{@code vector} 与 {@code bm25} 的
     * 指标长得像、含义完全不同，产物里认错一次，第 4 周的消融结论就整个反过来。
     *
     * @param channelNames 本次实际参与的通道名（{@link RetrievalPipeline#channelNames()}）
     */
    public String labelFor(List<String> channelNames) {
        String base = (channelNames != null && channelNames.size() == 1)
                ? channelNames.get(0)
                : fusionName;
        return rerankEnabled ? base + "+" + rerankerName : base;
    }

    private static void requirePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(
                    name + " 必须 >= 1，实际：" + value
                            + "（条数为 0 或负数会让对应阶段静默返回空结果——"
                            + "表现为「检索什么都没召回」，而不是报错）");
        }
    }
}
