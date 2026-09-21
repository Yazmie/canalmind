package com.canalmind.rag.vector;

import java.util.Locale;

/**
 * 向量检索的一条命中（第 2 周第 5 项，纯向量通道）。
 *
 * <p>字段与 {@code com.canalmind.rag.bm25.Bm25Hit} <b>逐字段对齐</b>（只去掉 BM25 专属的
 * {@code matchedTerms}，加上本通道的 {@code score} 口径说明）。对齐是刻意的：
 * 第 4 周要做两通道消融与 RRF 融合（C-26），两个通道的命中形状一致，
 * 融合层与评测脚本才不需要为"向量通道"再写一套字段映射——
 * 而字段映射一旦分叉，最典型的症状是"其中一个通道的 article 级别定位悄悄失效"。
 *
 * <p>返回<b>溯源字段</b>（doc_id / doc_title / article_no / article_title）而不只是
 * chunk_id 的理由与 BM25 侧相同（知识库《RAG 优化》第 48–58 行要求 chunk 保留可回溯原文的元数据）。
 *
 * @param chunkId      chunk 标识（与 {@code rag_chunk.chunk_id} 一致，内容派生、可对账）
 * @param docId        来源文档
 * @param docTitle     文档标题
 * @param chunkType    BODY / TABLE
 * @param articleNo    条号（如「第三条」）
 * @param articleTitle 条标题
 * @param version      文档版本（如 V1.0）
 * @param score        <b>余弦相似度</b>（cosine similarity），取值约 [-1, 1]，<b>越大越相关</b>。
 *                     定义为 {@code 1 - (embedding <=> q)}：{@code <=>} 返回的是
 *                     <b>余弦距离</b>（越小越相似），取 1 减之后方向反过来，成为"越大越好"。
 *                     因为 bge-m3 输出已 L2 归一化（范数实测 1.000001），
 *                     内积 = 余弦相似度，故本字段在数值上等于
 *                     {@code -(embedding <#> q)}（pgvector 的内积运算符返回负内积）。
 *                     ⚠️ 与 BM25 分数不同，本字段<b>有界</b>，因此跨查询粗略可比；
 *                     但"哪条更相关"仍然只在<b>同一查询内</b>有意义（不同查询的分数分布不同），
 *                     排序结论请只在同一查询内比较。
 */
public record VectorHit(
        String chunkId,
        String docId,
        String docTitle,
        String chunkType,
        String articleNo,
        String articleTitle,
        String version,
        double score) {

    /**
     * 供 CLI/日志的一行摘要。
     *
     * <p>小数位用 {@link Locale#ROOT} 固定：某些 locale 的小数点是逗号，
     * 会让日志里的分数无法直接复制进 SQL / 脚本（与 {@code PgVectorIngestCli.fmt} 同一理由）。
     */
    public String summary() {
        return String.format(Locale.ROOT, "%.6f  %s  %s %s【%s】  %s",
                score,
                chunkType == null ? "" : chunkType,
                docId,
                articleNo == null ? "" : articleNo,
                articleTitle == null ? "" : articleTitle,
                chunkId);
    }
}
