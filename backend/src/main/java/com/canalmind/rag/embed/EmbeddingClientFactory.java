package com.canalmind.rag.embed;

/**
 * 按外部配置创建 embedding 客户端。
 *
 * <p><b>为什么要有这一层，而不是在 CLI 里读环境变量</b>：
 * 入库 CLI 与将来的检索链路<b>必须解析出完全相同的模型信息</b>——
 * 如果两处各读一遍环境变量，早晚会出现"入库读的是 A、查询读的是 B"
 * 这种 C-22 配套硬约束要挡的情况。集中到一处，两侧就不可能分叉。
 *
 * <p><b>为什么默认值写死成 bge-m3 + 1024</b>：这是决策 C-22 与向量表
 * {@code vector(1024)} 绑定的取值。默认值不是"随便挑的"，
 * 改动它必须同步改 V3 迁移，属 A 类变更。
 *
 * <p>刻意<b>不</b>做成 Spring {@code @Component}：入库 CLI 需要在<b>没有 Spring 上下文</b>
 * 的情况下运行（`java -cp ... PgVectorIngestCli`），这样才能在只装了 JDK 的机器上补数据。
 */
public final class EmbeddingClientFactory {

    /** 决策 C-22 指定的本地模型。 */
    public static final String DEFAULT_MODEL = "bge-m3";

    /** 决策 C-22 与 V3 的 {@code vector(1024)} 绑定。 */
    public static final int DEFAULT_DIMENSION = 1024;

    public static final String DEFAULT_PROVIDER = "ollama";
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private EmbeddingClientFactory() {
    }

    /**
     * 从环境变量创建（缺省时用 C-22 的默认值）。
     *
     * <p>读取的键：{@code OLLAMA_BASE_URL}、{@code OLLAMA_EMBEDDING_MODEL}、
     * {@code OLLAMA_EMBEDDING_DIMENSION}——与 {@code .env.example} 中已写入的键一致。
     */
    public static EmbeddingClient fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** 便于测试注入环境变量映射。 */
    public static EmbeddingClient fromEnvironment(java.util.Map<String, String> env) {
        String baseUrl = value(env, "OLLAMA_BASE_URL", DEFAULT_BASE_URL);
        String model = value(env, "OLLAMA_EMBEDDING_MODEL", DEFAULT_MODEL);
        int dimension = parseDimension(value(env, "OLLAMA_EMBEDDING_DIMENSION",
                String.valueOf(DEFAULT_DIMENSION)));
        return new OllamaEmbeddingClient(baseUrl, new EmbeddingModelInfo(DEFAULT_PROVIDER, model, dimension));
    }

    /**
     * 解析维度。
     *
     * <p>⚠️ 刻意<b>不</b>在维度非 1024 时静默放行：V3 里向量列是 {@code vector(1024)}，
     * 维度不符时写库会被数据库拦下，但那时 embedding 算力已经白花。
     * 这里提前报错，并说明该值与迁移文件的绑定关系。
     */
    public static int parseDimension(String raw) {
        int d;
        try {
            d = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "OLLAMA_EMBEDDING_DIMENSION 不是整数：" + raw, e);
        }
        if (d != DEFAULT_DIMENSION) {
            throw new IllegalArgumentException(
                    "OLLAMA_EMBEDDING_DIMENSION=" + d + " 与向量表的 vector(" + DEFAULT_DIMENSION
                            + ") 不一致。维度由 embedding 模型决定，且与 V3 迁移"
                            + "（V3__add_rag_chunk_vector_table.sql）绑定——"
                            + "换维度必须同时改迁移并全量重建索引（C-22 配套硬约束）。");
        }
        return d;
    }

    private static String value(java.util.Map<String, String> env, String key, String fallback) {
        String v = env == null ? null : env.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim();
    }
}
