package com.canalmind.rag.embed;

/**
 * pgvector 字面量编解码与向量范数校验。
 *
 * <p><b>为什么要自己写</b>：本文档成文时本地 Maven 仓库（离线可用）里
 * <b>没有</b> pgvector 的 Java 客户端（查过 {@code com/pgvector}、{@code org/pgvector}、
 * {@code io/github/pgvector} 均不存在），而本项目构建<b>必须离线</b>（见 AGENTS.md §7.1）。
 * 故按 pgvector 的文本格式自行编码。
 *
 * <p>格式（pgvector 官方输入格式）：{@code [1.5,-2,3]}——方括号、逗号分隔、无空格。
 * 用 {@code ?::vector} 显式转型传入，比依赖 JDBC 的类型推断更稳。
 *
 * <p><b>为什么必须校验有限性</b>：{@code NaN} 与 {@code Infinity} 一旦写进向量列，
 * pgvector <b>可以接受它们</b>，而距离计算会污染成 NaN——
 * 那么这条记录<b>永远不会被检索到</b>，且不会报任何错。
 * 这与本项目已经踩过的"静默失效"是同一类问题（见
 * {@code docs/工程复盘/工程方法/转义与写入层的静默损坏.md}），
 * 所以在编码阶段就拒绝，而不是等检索结果莫名变少再去查。
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    /**
     * 把向量编码成 pgvector 文本字面量。
     *
     * @param vector 向量分量
     * @return 形如 {@code [0.1,-0.2,0.3]} 的字面量
     * @throws IllegalArgumentException 向量为空、含非有限值（NaN/Infinity）
     */
    public static String toPgVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量为空，无法编码为 pgvector 字面量");
        }
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            float f = vector[i];
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new IllegalArgumentException(
                        "向量第 " + i + " 个分量为非法值 " + f
                                + "。NaN/Infinity 会被 pgvector 接受，但会让距离计算变成 NaN，"
                                + "导致该行永远检索不到且不报错，故在此拒绝。");
            }
            if (i > 0) {
                sb.append(',');
            }
            // Float.toString 保证输出可被 Float.parseFloat 精确还原（往返一致），
            // 而 BigDecimal 会写出更长且不必要的表示。
            sb.append(f);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 计算 L2 范数。
     *
     * <p><b>为什么关心范数</b>：本项目的 HNSW 索引用 {@code vector_ip_ops}（内积），
     * 查询用 {@code <#>}。内积排序<b>只在向量已 L2 归一化时才等价于余弦排序</b>。
     * 实测 bge-m3 输出范数为 1.000001（已归一化），所以该等价成立、且内积计算更快。
     * 但若将来更换模型而输出未归一化，内积排序会<b>静默地按向量长度偏斜</b>——
     * 长向量更容易排在前面，而这不是语义相似度。故入库时校验范数。
     */
    public static double norm(float[] vector) {
        double sum = 0.0;
        for (float f : vector) {
            sum += (double) f * f;
        }
        return Math.sqrt(sum);
    }

    /**
     * 范数是否足够接近 1（判定"已 L2 归一化"）。
     *
     * @param tolerance 容差。实测 bge-m3 偏差约 1e-6，取 1e-3 是留两个数量级余量，
     *                  既能确认归一化，又不会因浮点累加误差误报
     */
    public static boolean isL2Normalized(float[] vector, double tolerance) {
        return Math.abs(norm(vector) - 1.0) <= tolerance;
    }
}
