package com.canalmind.rag.embed;

/**
 * Embedding 模型配置。
 *
 * <p><b>为什么把"模型身份"做成一个不可变 record</b>：决策 <b>C-22 配套硬约束</b>要求
 * "索引与查询必须同一 embedding 模型及版本；模型信息写入 chunk 元数据，查询时校验版本，
 * 不匹配则拒绝查询"。把 provider/model/dimension 绑成一个值对象后，
 * <strong>校验就是在比较两个对象是否相等</strong>——而不是在若干散落字段上各写一遍 if。
 *
 * <p>依据（知识库《RAG 知识库文档如何更新：增量更新、版本控制、去重与全量重建》）：
 * <ul>
 *   <li><b>第 29 行</b>：<i>"索引时用的 Embedding 模型，必须和查询时用的模型一致"</i></li>
 *   <li><b>第 33 行</b>：维度相同但模型不同时，相似度分数<b>不可比</b>，召回结果不能信
 *       （<i>"整个排序基础已经坏了"</i>）</li>
 *   <li><b>第 39 行</b>：点名「本地模型与 API 模型混用」是生产最易忽视的坑之一；
 *       本项目恰好选了本地 Ollama 模型，故该约束对我们尤其相关</li>
 *   <li><b>第 41 行</b>：做法就是"把模型信息写入元数据并逐次查询校验"</li>
 * </ul>
 *
 * @param provider  提供方标识，如 {@code ollama}
 * @param model     模型名，如 {@code bge-m3}
 * @param dimension 向量维度。<b>必须与向量列 {@code vector(N)} 一致</b>——
 *                  bge-m3 实测为 1024（见 {@code docs/进度看板.md} §4）
 */
public record EmbeddingModelInfo(String provider, String model, int dimension) {

    /**
     * 档案名：写入 {@code rag_chunk.profile_name}，也用作 {@code rag_index_profile} 的主键。
     *
     * <p>形如 {@code ollama:bge-m3:1024}——刻意把三个可能不一致的维度都编进名字里，
     * 这样从档案名就能看出"是谁产的向量"，不必再查表。
     */
    public String profileName() {
        return provider + ":" + model + ":" + dimension;
    }

    /**
     * 是否与另一份模型信息兼容（可用于同一索引）。
     *
     * <p>维度不同直接不可比；维度相同但<b>模型或提供方不同</b>同样不可比——
     * 后者正是 C-22 配套硬约束要挡的场景（知识库第 33 行："整个排序基础已经坏了"）。
     * 故这里要求三者<b>全部相等</b>。
     */
    public boolean isCompatibleWith(EmbeddingModelInfo other) {
        if (other == null) {
            return false;
        }
        return provider.equals(other.provider)
                && model.equals(other.model)
                && dimension == other.dimension;
    }

    /**
     * 生成"不匹配"的诊断文本。
     *
     * <p>刻意写得啰嗦：这个异常一旦抛出，说明有人要拿 A 模型的向量去比 B 模型的查询向量，
     * 结果会<b>静默地全是错的</b>（不报错、相似度分数照常返回）。
     * 所以错误信息必须直接说清"该怎么做"，而不是只说"不匹配"。
     */
    public String describeMismatch(EmbeddingModelInfo indexed) {
        StringBuilder sb = new StringBuilder();
        sb.append("embedding 模型不匹配：索引由 ").append(indexed.profileName())
                .append(" 产出，但当前查询用的是 ").append(profileName()).append("。");
        if (indexed.dimension() != dimension) {
            sb.append("维度不同（").append(indexed.dimension()).append(" vs ").append(dimension)
                    .append("），实际上无法放进同一索引。");
        } else {
            sb.append("维度虽相同，但模型不同——相似度分数不可比，召回结果不可信。");
        }
        sb.append("处理办法：更换模型必须**全量重建索引**，不能增量替换（C-22 配套硬约束）。");
        return sb.toString();
    }
}
