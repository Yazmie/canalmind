package com.canalmind.rag.embed;

/**
 * 查询侧的 embedding 模型版本校验闸门（决策 <b>C-22 配套硬约束</b>的落地）。
 *
 * <p><b>它挡的是什么</b>：索引由模型 A 产出、查询用模型 B 编码——此时检索<b>不会报错</b>，
 * 会正常返回一批结果，只是相似度分数<b>全是错的</b>。知识库《RAG 知识库文档如何更新》
 * <b>第 33 行</b>称之为"整个排序基础已经坏了"；<b>第 39 行</b>点名
 * 「本地模型与 API 模型混用」是生产最易忽视的坑之一（"测试用本地、上线用 API，召回率直接腰斩"）。
 *
 * <p>本项目<b>恰好</b>选了本地 Ollama 模型（C-22），所以这个闸门对我们是高相关项，
 * 不是"抄一条最佳实践"。
 *
 * <p><b>为什么做成一个独立的小类而不是散在检索代码里</b>：校验必须在<b>每一条</b>
 * 查询路径上都执行。散着写，早晚有一条新加的检索入口忘记校验——
 * 而忘记校验不会有任何提示。集中成一个对象，新入口只要拿到它就自动受约束。
 */
public final class EmbeddingModelGuard {

    private final EmbeddingModelInfo indexed;
    private final EmbeddingModelInfo querying;

    /**
     * @param indexed  索引侧模型信息（来自 {@code rag_index_profile}，即数据库里的<b>事实</b>）
     * @param querying 查询侧模型信息（来自当前配置）
     */
    public EmbeddingModelGuard(EmbeddingModelInfo indexed, EmbeddingModelInfo querying) {
        this.indexed = indexed;
        this.querying = querying;
    }

    /** 是否一致（一致才允许检索）。 */
    public boolean isConsistent() {
        return indexed != null && indexed.isCompatibleWith(querying);
    }

    /**
     * 一致性校验；不一致则抛异常拒绝查询。
     *
     * <p>刻意<b>拒绝</b>而不是"警告后继续"：继续下去会得到一份看起来正常、
     * 实际排序全错的结果，这种结果比报错危险得多。
     */
    public void verify() throws EmbeddingModelMismatchException {
        if (indexed == null) {
            throw new EmbeddingModelMismatchException(
                    "索引侧模型信息缺失（rag_index_profile 无记录或未建立索引档案）。"
                            + "无法确认向量由哪个模型产出，按 C-22 配套硬约束拒绝查询。");
        }
        if (!isConsistent()) {
            throw new EmbeddingModelMismatchException(querying.describeMismatch(indexed));
        }
    }

    /** 模型不一致时抛出。属受检异常：调用方必须显式处理，不能默默吞掉。 */
    public static class EmbeddingModelMismatchException extends Exception {
        public EmbeddingModelMismatchException(String message) {
            super(message);
        }
    }
}
