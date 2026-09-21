package com.canalmind.rag.vector;

import java.time.LocalDate;

/**
 * 向量检索的查询选项（第 2 周第 5 项）。
 *
 * <p><b>本类刻意与 {@code Bm25QueryOptions} 同形同校验</b>（字段名、默认值语义、
 * 紧凑构造器里的两条拒绝规则都一致），理由不是"抄一遍省事"，而是<b>评测口径必须一致</b>：
 * 第 4 周要做"纯向量 vs 纯 BM25 vs 混合"的对照（C-26 与 D3 决策的输入），
 * 若两个通道的过滤条件语义有一处不同（例如 as_of 一个左闭右开、一个左闭右闭），
 * 消融结论就会把"过滤口径差异"误读成"检索能力差异"，而<b>不会有任何报错</b>。
 *
 * <p>所有过滤条件都在<b>打分同一条 SQL 里</b>生效，不是"先取 Top-K 再筛"。
 * 依据是知识库《RAG 优化》第 61–63 行：权限/范围过滤必须在召回前参与查询，
 * 否则会出现"Top-10 里 8 条无权访问，剩下 2 条不能说明只有 2 条相关内容"。
 *
 * <p>⚠️ 与 BM25 通道的一处<b>真实差异</b>（写在类型注释里，免得被当成 bug）：
 * 本通道走 HNSW 近似索引时，过滤条件可能触发"过滤后静默少返回"
 * （见 {@code docs/工程复盘/检索工程/HNSW索引过滤条件静默少返回.md}）；
 * 而不走索引的精确扫描（默认的 cosine 度量）不存在该退化。
 * 该差异由 {@link VectorSearcher} 按度量分别处理，不在本类型上体现。
 *
 * @param topK          返回条数上限，必须 ≥ 1
 * @param docId         限定文档（{@code rag_chunk.doc_id}），null 表示不限
 * @param docCategoryNo 限定主题类编号（01–26），null 表示不限
 * @param chunkType     限定 chunk 类型（BODY / TABLE），null 表示不限
 * @param asOf          业务日期，按<b>左闭右开</b> [effective_from, effective_to) 过滤；
 *                      null 表示不按生效期过滤
 */
public record VectorQueryOptions(
        int topK,
        String docId,
        String docCategoryNo,
        String chunkType,
        LocalDate asOf) {

    public VectorQueryOptions {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1，实际：" + topK);
        }
        if (chunkType != null && !"BODY".equals(chunkType) && !"TABLE".equals(chunkType)) {
            throw new IllegalArgumentException(
                    "chunkType 只能是 BODY 或 TABLE（V3 的 chunk_type CHECK），实际：" + chunkType);
        }
    }

    /** 只给 Top-K，其余过滤全部关闭。 */
    public static VectorQueryOptions topK(int topK) {
        return new VectorQueryOptions(topK, null, null, null, null);
    }

    /** 按业务日期过滤（评测「版本冲突」类题目的关键入口）。 */
    public VectorQueryOptions withAsOf(LocalDate date) {
        return new VectorQueryOptions(topK, docId, docCategoryNo, chunkType, date);
    }

    /** 限定文档。 */
    public VectorQueryOptions withDocId(String newDocId) {
        return new VectorQueryOptions(topK, newDocId, docCategoryNo, chunkType, asOf);
    }

    /** 限定 chunk 类型。 */
    public VectorQueryOptions withChunkType(String newChunkType) {
        return new VectorQueryOptions(topK, docId, docCategoryNo, newChunkType, asOf);
    }
}
