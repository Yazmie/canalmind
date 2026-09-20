package com.canalmind.rag.split;

import java.util.List;
import java.util.Map;

/**
 * 切分产物清单：一次切分运行的完整结果。
 *
 * <p>这份清单是<b>「文档检索通道」的第一个可交付产物</b>——
 * 后续 Embedding 入库直接消费它，第 4 周消融实验也以它为输入。
 *
 * <p><b>刻意不含时间戳、不含机器路径、不含随机值</b>：
 * 同样的语料 + 同样的策略必须产出<strong>逐字节相同</strong>的清单。
 * 依据《RAG 优化》第 366 行"每一轮只改变少量变量"——若清单本身不可复现，
 * 两次跑分的差异就可能是噪声而不是改动带来的。
 *
 * @param manifestVersion 清单结构版本
 * @param strategy        切分策略标识
 * @param splitterVersion 切分器版本
 * @param properties      本次运行使用的参数（写进产物，避免"分数对不上参数"）
 * @param statistics      统计（块大小分布等，对应知识库第 179–183 行的 Chunking 校验）
 * @param documents       逐文档的摘要
 * @param baseline        固定长度基线的对照统计；仅结构化模式下填充
 */
public record ChunkManifest(
        String manifestVersion,
        String strategy,
        String splitterVersion,
        Map<String, Object> properties,
        Statistics statistics,
        List<DocumentSummary> documents,
        List<Chunk> chunks,
        Statistics baseline) {

    public static final String CURRENT_MANIFEST_VERSION = "1.0";

    /**
     * 块大小分布统计。
     *
     * <p>字段取自知识库《RAG 文档处理与切分策略》<b>第 181–183 行</b>给出的
     * {@code ChunkingQualityReport}：总数、总字符、均值、最小、最大、标准差，
     * 并要求据此判断"块大小分布是否合理"。故这里逐一实现，不做取舍。
     */
    public record Statistics(
            int chunkCount,
            long totalChars,
            double meanChars,
            int minChars,
            int maxChars,
            double stdDevChars,
            int tableChunkCount,
            int bodyChunkCount,
            int docsWithNoChunk,
            Map<Integer, Integer> sizeHistogram) {

        /** 知识库第 182 行的告警条件：标准差 > 均值的 50% 视为分布不均。 */
        public boolean isSizeDistributionUneven() {
            return meanChars > 0 && stdDevChars > meanChars * 0.5;
        }
    }

    /** 单篇文档的切分摘要，便于定位"哪篇切得异常"。 */
    public record DocumentSummary(
            String docId,
            String docNo,
            String title,
            String version,
            String effectiveFrom,
            String effectiveTo,
            boolean superseded,
            int articleCount,
            int bodyChunkCount,
            int tableChunkCount) {
    }
}
