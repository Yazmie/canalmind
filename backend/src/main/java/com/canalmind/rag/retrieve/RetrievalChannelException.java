package com.canalmind.rag.retrieve;

/**
 * 通道检索失败（第 2 周第 5 项）。
 *
 * <p><b>为什么要有这个包装类型</b>：两个底层 searcher 抛的是<b>受检</b>异常
 * （{@code EmbeddingException} / {@code EmbeddingModelMismatchException} /
 * {@code TokenizerProfileMismatchException}），而 {@link RetrievalChannel} 的签名刻意保持干净
 * （见其类注释）。包成非受检是<b>为了签名</b>，不是为了"可以不处理"。
 *
 * <p>⚠️ 因此有两条不可违反的用法：
 * <ol>
 *   <li><b>必须保留 cause</b>（构造器强制传入）；底层原因（"档案不匹配"、"连不上 Ollama"）
 *       才是第一眼要看的信息，只留一句"检索失败"等于把最有用的信息丢掉；</li>
 *   <li>⛔ {@link RetrievalPipeline} <b>不得捕获它去降级成单通道</b>。一次"实际只跑了一个通道"
 *       的评测若静默通过，会被读成"混合检索相比单通道没有增益"——
 *       这正是 C-27 要求"缺 key 必须报错、不许静默降级"的同一个理由，
 *       只是换到了召回侧。</li>
 * </ol>
 */
public class RetrievalChannelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetrievalChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
