package com.canalmind.rag.tokenize;

import java.util.List;
import java.util.Map;

/**
 * 中文分词器抽象（第 2 周第 4 项）。
 *
 * <p>抽成接口而不是直接用 jieba 的具体类，是为了让两件事可替换：
 * <ol>
 *   <li><b>消融对比</b>——第 4 周要比较"分词粒度/词典"对召回的影响，
 *       换实现不该动检索器（知识库《RAG 优化》第 366 行"每轮只改少量变量"）；</li>
 *   <li><b>测试替身</b>——BM25 检索器的单测需要一个可预测的分词器，
 *       否则断言会被真实分词结果绑架（改一个词典词就红一片）。</li>
 * </ol>
 *
 * <p><b>索引与查询必须用同一个分词器实例的口径</b>：这正是 V3
 * （C-22 配套硬约束：索引与查询必须同一 embedding 模型）在关键词通道上的对应约束。
 * 本项目的做法是由 {@link #profile} 产出档案指纹、写进库里，
 * 查询侧比对指纹不符就**拒绝查询**（见 {@code Bm25Searcher}），而不是降级。
 */
public interface ChineseTokenizer {

    /** 分词器名（如 {@code jieba-analysis}），进档案。 */
    String name();

    /** 分词器版本，进档案；必须与真实依赖版本一致（有单测守护）。 */
    String version();

    /** 切分模式名（如 {@code INDEX} / {@code SEARCH}），进档案。 */
    String segmentMode();

    /**
     * 分词：返回**保留重复与顺序**的词元列表。
     *
     * <p>词元一律已归一化：小写、词内无空白、已剔除停用词与纯标点。
     * 同一输入必须给出**逐位相同**的输出（可复现是评测基线的前提）。
     */
    List<String> tokenize(String text);

    /**
     * 词频统计：词元 → 出现次数。
     *
     * <p>返回**按词元排序**的映射（TreeMap）：入库顺序、CLI 输出、两次运行的 diff
     * 都因此可比。用 HashMap 会让"同一份语料两次入库"的日志顺序随机变化，
     * 从而无法用 diff 判断差异来自语料还是来自环境。
     */
    Map<String, Integer> termFrequencies(String text);

    /** 产出档案（含当前词典/停用词指纹与 BM25 参数）。 */
    TokenizerProfile profile(double bm25K1, double bm25B);
}
