package com.canalmind.rag.bm25;

/**
 * BM25 检索的一条命中（第 2 周第 4 项）。
 *
 * <p>返回**溯源字段**（doc_id / doc_title / article_no / article_title）而不只是
 * chunk_id：知识库《RAG 优化》第 48–58 行要求每个 chunk 保留可回溯到原文的元数据；
 * 第 4 周的 Context Recall 评测也要靠 article 级别定位"正确证据是否被召回"，
 * 否则只能人工看 chunk 内容。
 *
 * @param chunkId      chunk 标识（与 {@code rag_chunk.chunk_id} 一致，内容派生、可对账）
 * @param docId        来源文档
 * @param docTitle     文档标题
 * @param chunkType    BODY / TABLE
 * @param articleNo    条号（如"第三条"）
 * @param articleTitle 条标题
 * @param version      文档版本（如 V2.0）
 * @param score        BM25 分数（越大越相关；**只在同一查询内可比**，跨查询不可比）
 * @param matchedTerms 命中的不同查询词个数（用于解释"为什么是这条"）
 */
public record Bm25Hit(
        String chunkId,
        String docId,
        String docTitle,
        String chunkType,
        String articleNo,
        String articleTitle,
        String version,
        double score,
        int matchedTerms) {

    /** 供 CLI/日志的一行摘要。 */
    public String summary() {
        return String.format("%.6f  命中%d词  %s %s【%s】  %s",
                score, matchedTerms, docId, articleNo == null ? "" : articleNo,
                articleTitle == null ? "" : articleTitle, chunkId);
    }
}
