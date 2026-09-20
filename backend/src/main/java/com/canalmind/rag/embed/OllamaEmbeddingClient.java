package com.canalmind.rag.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过 Ollama 的 {@code /api/embed} 调用本地 bge-m3 的 embedding 客户端。
 *
 * <p><b>为什么直接写 HTTP 而不是用 Spring AI 的 Ollama 模型</b>（决策 C-22 虽指定经
 * {@code spring-ai-starter-model-ollama} 接入）：
 * <ol>
 *   <li>Spring AI 1.1.2 的 Ollama embedding 抽象是"一条文本一次调用"，
 *       而我们要 541 个 chunk 批量入库——逐条会产生 541 次 HTTP 往返；</li>
 *   <li>Ollama 的<b>原生</b>批量端点 {@code /api/embed} 支持一次传多条，
 *       实测返回与逐条调用逐维一致（本项目已实测确认）；</li>
 *   <li>本客户端只有一个依赖（JDK {@link HttpClient} + Jackson），
 *       便于用桩服务器做确定性测试——而"换模型/换提供方"正是第 4 周消融要碰的地方。</li>
 * </ol>
 * 若将来改为云 embedding API，只需新增一个 {@link EmbeddingClient} 实现。
 *
 * <p><b>实测契约</b>（2026-09-21，本机 Ollama 0.11+ / bge-m3:latest）：
 * <pre>
 *   POST /api/embed   {"model":"bge-m3","input":["文本1","文本2"]}
 *   → 200 {"model":..., "embeddings":[[1024 个数],[1024 个数]], "total_duration":...}
 *   → 404（模型不存在）响应体为空，故错误信息里必须带上模型名
 * </pre>
 *
 * <p><b>刻意做的两处强校验</b>（都是"静默出错"的高危点）：
 * <ol>
 *   <li><b>返回条数必须与请求条数相等</b>——若 Ollama 少返回一条而我们按位对齐，
 *       后续所有向量都会<b>错位</b>，且不会报错，只是召回结果全错；</li>
 *   <li><b>每个向量维度必须等于配置维度</b>——维度不符时写库会被 pgvector 拦住，
 *       但那时已经白花了 embedding 算力；不如在客户端就报错，且错误信息更贴近原因。</li>
 * </ol>
 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    /** 默认批量大小。小批量是为了限制单次请求体的体积与超时风险，而非模型限制。 */
    public static final int DEFAULT_BATCH_SIZE = 16;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 单次请求超时。
     *
     * <p>刻意的取值理由：实测 2 条短文本约 6.8 秒（含一次模型加载/预热），
     * 批量 16 条最长文本（528 字）时耗时更长。设为 120 秒是留足余量，
     * 同时避免"卡死却不报错"——<b>有超时上限的错误远好过无限等待</b>。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MILLIS = 1000L;

    private final String baseUrl;
    private final EmbeddingModelInfo modelInfo;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final int batchSize;

    public OllamaEmbeddingClient(String baseUrl, EmbeddingModelInfo modelInfo) {
        this(baseUrl, modelInfo, DEFAULT_BATCH_SIZE);
    }

    public OllamaEmbeddingClient(String baseUrl, EmbeddingModelInfo modelInfo, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 必须 >= 1，实际 " + batchSize);
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelInfo = modelInfo;
        this.batchSize = batchSize;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public EmbeddingModelInfo modelInfo() {
        return modelInfo;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) throws EmbeddingException {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            out.addAll(embedBatch(texts.subList(i, end)));
        }
        return out;
    }

    /**
     * 调用一个批次。
     *
     * <p>重试策略：只对<b>可能自愈</b>的失败重试——网络 IO 异常、
     * 5xx（服务端临时故障）、429（限流）。对 4xx 中除 429 以外的状态<b>立即失败</b>：
     * 模型名写错、请求体不合法这类问题重试 100 次也不会好，重试只会掩盖真实原因。
     */
    private List<float[]> embedBatch(List<String> batch) throws EmbeddingException {
        String body = buildRequestBody(batch);
        URI uri = URI.create(baseUrl + "/api/embed");

        EmbeddingException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<byte[]> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();

                if (status == 200) {
                    return parseEmbeddings(response.body(), batch.size());
                }

                String detail = new String(response.body(), StandardCharsets.UTF_8).trim();
                String message = "Ollama /api/embed 返回 HTTP " + status
                        + "（模型 " + modelInfo.model() + "，本批 " + batch.size() + " 条）"
                        + (detail.isEmpty() ? "，响应体为空" : "：" + truncate(detail));

                boolean retryable = status >= 500 || status == 429;
                if (!retryable) {
                    throw new EmbeddingException(message
                            + "。该错误重试无意义（多为模型名错误或请求不合法），已直接失败。");
                }
                last = new EmbeddingException(message);
            } catch (EmbeddingException e) {
                // ⚠️ 这个 catch 必须排在 catch (IOException) **之前**，且必须原样抛出。
                //    原因：EmbeddingException 继承 IOException，而本方法内"不该重试"的两类错误
                //    都是以 EmbeddingException 抛出的——
                //      ① HTTP 4xx（除 429）的立即失败；
                //      ② 响应契约不符（条数不一致 / 维度不符 / 响应非 JSON）。
                //    若没有这个分支，它们会被下面的 catch (IOException) 当成"网络抖动"吞掉，
                //    于是重试 3 次、每个批次最多白花 3 秒退避，最后只报"连续失败 3 次"，
                //    真正的原因（模型名写错？返回条数少了一条？）被埋进 cause 里。
                //    实测证据：OllamaEmbeddingClientTest 断言消息里含「条数/维度/合法 JSON/
                //    重试无意义」，未加本分支时只看到「连续失败 3 次」；
                //    且 404 用例的请求数从 1 变成 3——即"不重试"的承诺当时是失效的。
                throw e;
            } catch (IOException e) {
                // ConnectException / HttpTimeoutException 等都归到这里
                last = new EmbeddingException(
                        "调用 Ollama embedding 失败（模型 " + modelInfo.model() + "，第 " + attempt + " 次）："
                                + e.getClass().getSimpleName() + " " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("调用 Ollama embedding 被中断", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw new EmbeddingException(
                "调用 Ollama embedding 连续失败 " + MAX_ATTEMPTS + " 次，已放弃（模型 "
                        + modelInfo.model() + "）", last);
    }

    private static void sleepBackoff(int attempt) throws EmbeddingException {
        try {
            Thread.sleep(RETRY_BASE_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("重试等待被中断", e);
        }
    }

    /**
     * 构造请求体。
     *
     * <p>用 Jackson 生成 JSON 而<b>不是手工拼字符串</b>：chunk 正文里含有换行、
     * 中文标点、竖线（表格）等字符，手工拼接极易漏转义——
     * 而"漏转义"正是本项目刚吃过大亏的一类**静默错误**
     * （见 {@code docs/工程复盘/工程方法/转义与写入层的静默损坏.md}）。
     * 让库负责转义，我们不去数反斜杠。
     */
    private String buildRequestBody(List<String> batch) throws EmbeddingException {
        try {
            var node = mapper.createObjectNode();
            node.put("model", modelInfo.model());
            var arr = node.putArray("input");
            for (String t : batch) {
                arr.add(t == null ? "" : t);
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new EmbeddingException("构造 embedding 请求体失败", e);
        }
    }

    /** 解析响应，并做强校验（条数、维度）。 */
    private List<float[]> parseEmbeddings(byte[] body, int expectedCount) throws EmbeddingException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new EmbeddingException(
                    "Ollama /api/embed 响应不是合法 JSON（模型 " + modelInfo.model() + "）", e);
        }

        JsonNode embeddings = root.get("embeddings");
        if (embeddings == null || !embeddings.isArray()) {
            throw new EmbeddingException(
                    "Ollama /api/embed 响应缺少 embeddings 数组（模型 " + modelInfo.model()
                            + "）。若 Ollama 版本较老，可能只提供 /api/embeddings（单条）。响应片段："
                            + truncate(root.toString()));
        }

        // ⚠️ 强校验 1：条数必须相等。少一条就会导致后续向量整体错位，
        //    而错位不会抛异常、只会让召回结果静默全错。
        if (embeddings.size() != expectedCount) {
            throw new EmbeddingException(
                    "Ollama 返回的向量条数 " + embeddings.size() + " 与请求文本条数 " + expectedCount
                            + " 不一致（模型 " + modelInfo.model()
                            + "）。按位对齐会静默错位，故直接判为失败。");
        }

        List<float[]> result = new ArrayList<>(expectedCount);
        for (int i = 0; i < embeddings.size(); i++) {
            JsonNode vec = embeddings.get(i);
            if (!vec.isArray()) {
                throw new EmbeddingException("第 " + i + " 个 embedding 不是数组");
            }
            // ⚠️ 强校验 2：维度必须等于配置维度。
            //    维度不符最终会被 pgvector 的 vector(1024) 拦住，但那时算力已经白花；
            //    在客户端报错能把原因说得更直接。
            if (vec.size() != modelInfo.dimension()) {
                throw new EmbeddingException(
                        "第 " + i + " 个 embedding 维度为 " + vec.size() + "，但配置期望 "
                                + modelInfo.dimension() + "（模型 " + modelInfo.model()
                                + "）。维度由模型决定，检查 OLLAMA_EMBEDDING_DIMENSION 与所用模型是否一致。");
            }
            float[] v = new float[vec.size()];
            for (int d = 0; d < vec.size(); d++) {
                v[d] = (float) vec.get(d).asDouble();
            }
            result.add(v);
        }
        return result;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
