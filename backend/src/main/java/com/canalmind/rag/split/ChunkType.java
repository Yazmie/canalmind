package com.canalmind.rag.split;

/**
 * Chunk 类型。
 *
 * <p>依据决策 <b>C-21</b> 与 {@code docs/规则语料/README.md} §3.2：
 * 正文与表格<strong>分开抽取</strong>（"表格单独结构化抽取，不与正文混切"），
 * 故两者是不同的 chunk 类型，而不是同一种 chunk 的两种字段。
 */
public enum ChunkType {

    /**
     * 正文 chunk：以「条」为单元。
     *
     * <p>条内的表格会被替换为一行占位标记，指向对应的 {@link #TABLE} chunk——
     * 这样正文既保留了"本条含有一个费率表"这一事实，又不会把表格内容切碎或稀释。
     */
    BODY,

    /**
     * 表格 chunk：140 个管道表各自结构化抽取，保留表头与逐行数据。
     *
     * <p>与正文分离，便于后续按字段/行做精确查询（结构化查询通道），
     * 也避免表格行把正文 chunk 的向量语义带偏。
     */
    TABLE
}
