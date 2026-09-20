package com.canalmind.rag.embed;

import java.io.IOException;
import java.util.List;

/**
 * Embedding 客户端契约。
 *
 * <p><b>为什么做成接口</b>：与 {@code Splitter} 同样的理由——
 * 本项目第 4 周要做消融（C-21 已把固定长度基线列为对照物），
 * 而 embedding 是检索链路上<b>最贵、最可能被替换</b>的一环（换模型、换提供方）。
 * 有了接口，测试可以注入桩实现（不依赖 Ollama 是否在跑），
 * 消融也能只换实现而不动入库与检索代码。
 *
 * <p><b>批量而不是逐条</b>：Ollama 的 {@code /api/embed} 支持一次传多条文本，
 * 实测（2 条文本）返回的向量与逐条调用<b>逐维完全相同</b>，
 * 但省掉了每条一次 HTTP 往返。541 个 chunk 逐条调用会产生 541 次往返。
 */
public interface EmbeddingClient {

    /**
     * 批量把文本转成向量。
     *
     * @param texts 待向量化文本，非空
     * @return 与 {@code texts} <b>等长且顺序一一对应</b>的向量列表
     * @throws EmbeddingException 网络失败、模型不存在、返回条数不符等
     */
    List<float[]> embedAll(List<String> texts) throws EmbeddingException;

    /** 单条便捷方法。默认实现走批量，保证两者行为绝不分叉。 */
    default float[] embedOne(String text) throws EmbeddingException {
        List<float[]> v = embedAll(List.of(text));
        if (v.size() != 1) {
            throw new EmbeddingException("单条 embedding 返回 " + v.size() + " 个向量，预期 1 个");
        }
        return v.get(0);
    }

    /** 本客户端实际使用的模型信息，用于写入索引档案并与查询侧校验（C-22）。 */
    EmbeddingModelInfo modelInfo();

    /**
     * embedding 调用失败。
     *
     * <p>做成受检异常：入库是"写数据"的操作，调用方<b>必须</b>显式决定
     * 是重试、跳过还是中止——不允许静默丢掉一段向量。
     */
    class EmbeddingException extends IOException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
