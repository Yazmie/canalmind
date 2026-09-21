package com.canalmind.rag.bm25;

import java.time.LocalDate;

/**
 * BM25 检索的查询选项（第 2 周第 4 项）。
 *
 * <p>所有过滤条件都要求**在打分同一条 SQL 里生效**，不是"先取 Top-K 再筛"。
 * 依据是知识库《RAG 优化》第 61–63 行：权限/范围过滤必须在召回前参与查询，
 * 否则会出现"Top-10 里 8 条无权访问，剩下 2 条不能说明只有 2 条相关内容"。
 * ⚠️ 向量通道因 HNSW 的近似特性存在"过滤后静默少返回"的退化
 * （见 {@code docs/工程复盘/检索工程/HNSW索引过滤条件静默少返回.md}）；
 * BM25 通道是**精确**打分，不存在该退化——这也是它值得单独存在的一条理由。
 *
 * @param topK          返回条数上限，必须 ≥ 1
 * @param docId         限定文档（{@code rag_chunk.doc_id}），null 表示不限
 * @param docCategoryNo 限定主题类编号（01–26），null 表示不限
 * @param chunkType     限定 chunk 类型（BODY / TABLE），null 表示不限
 * @param asOf          业务日期，按**左闭右开** [effective_from, effective_to) 过滤；
 *                      null 表示不按生效期过滤
 */
public record Bm25QueryOptions(
        int topK,
        String docId,
        String docCategoryNo,
        String chunkType,
        LocalDate asOf) {

    public Bm25QueryOptions {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
        if (chunkType != null && !"BODY".equals(chunkType) && !"TABLE".equals(chunkType)) {
            throw new IllegalArgumentException(
                    "chunkType 只能是 BODY 或 TABLE（V3 的 chunk_type CHECK），实际：" + chunkType);
        }
    }

    /** 只给 Top-K，其余过滤全部关闭。 */
    public static Bm25QueryOptions topK(int topK) {
        return new Bm25QueryOptions(topK, null, null, null, null);
    }

    /** 按业务日期过滤（评测"版本冲突"类题目的关键入口）。 */
    public Bm25QueryOptions withAsOf(LocalDate date) {
        return new Bm25QueryOptions(topK, docId, docCategoryNo, chunkType, date);
    }

    /** 限定文档。 */
    public Bm25QueryOptions withDocId(String newDocId) {
        return new Bm25QueryOptions(topK, newDocId, docCategoryNo, chunkType, asOf);
    }
}
