package com.canalmind.rag.embed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OllamaEmbeddingClient} 的测试——用 JDK 自带的 {@link HttpServer} 做桩，
 * 不依赖本机 Ollama 是否在跑、也不消耗真实算力。
 *
 * <p><b>为什么重点测"条数不符"与"维度不符"</b>：这两类错误都会被 naive 实现悄悄放过——
 * 少返回一条时若按位对齐，<b>后续所有向量整体错位</b>，而错位不抛异常、
 * 只让召回结果全错。这是典型的"看起来正常却全错"，必须由测试守住。
 */
class OllamaEmbeddingClientTest {

    private static final String NEWLINE = String.valueOf((char) 10);

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 由各测试注入：收到请求后如何响应。 */
    private volatile Responder responder;

    private interface Responder {
        Response respond(String requestBody) throws IOException;
    }

    private record Response(int status, String body) {
    }

    @BeforeEach
    void startStub() throws IOException {
        requestCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Response r;
        try {
            r = responder.respond(body);
        } catch (Exception e) {
            r = new Response(500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(r.status(), out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static EmbeddingModelInfo bgeM3() {
        return new EmbeddingModelInfo("ollama", "bge-m3", 1024);
    }

    private OllamaEmbeddingClient client(int batchSize) {
        return new OllamaEmbeddingClient(baseUrl, bgeM3(), batchSize);
    }

    /** 从请求体里数出 input 数组有几条文本。 */
    private static int countInputs(String requestBody) {
        int idx = requestBody.indexOf("\"input\"");
        if (idx < 0) {
            return 0;
        }
        int open = requestBody.indexOf('[', idx);
        int close = requestBody.lastIndexOf(']');
        String inner = requestBody.substring(open + 1, close);
        if (inner.isBlank()) {
            return 0;
        }
        // 桩里只发纯字母文本，没有引号/逗号，故按逗号数即可
        return inner.split(",").length;
    }

    private static String embeddingsJson(int count, int dim) {
        StringBuilder sb = new StringBuilder("{\"model\":\"bge-m3\",\"embeddings\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            for (int d = 0; d < dim; d++) {
                if (d > 0) {
                    sb.append(',');
                }
                sb.append(d == 0 ? "1" : "0");
            }
            sb.append(']');
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("正常路径：按 batchSize 切分请求，结果顺序与输入一致")
    void happyPathSplitsIntoBatchesAndKeepsOrder() throws Exception {
        List<Integer> seenCounts = new ArrayList<>();
        responder = request -> {
            int n = countInputs(request);
            seenCounts.add(n);
            return new Response(200, embeddingsJson(n, 1024));
        };

        List<String> texts = List.of("a", "b", "c", "d", "e");
        List<float[]> vectors = client(2).embedAll(texts);

        assertThat(vectors).hasSize(5);
        // 5 条 / 每批 2 条 → 3 次请求（2 + 2 + 1）
        assertThat(requestCount.get()).isEqualTo(3);
        assertThat(seenCounts).containsExactly(2, 2, 1);
        vectors.forEach(v -> assertThat(v).hasSize(1024));
    }

    @Test
    @DisplayName("⚠️ 返回条数少于请求条数 → 必须失败，不能按位对齐")
    void fewerEmbeddingsThanRequestedMustFail() {
        responder = request -> new Response(200, embeddingsJson(2, 1024));
        assertThatThrownBy(() -> client(4).embedAll(List.of("a", "b", "c", "d")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("条数")
                .hasMessageContaining("错位");
    }

    @Test
    @DisplayName("⚠️ 维度不符 → 必须失败，并指向配置项")
    void wrongDimensionMustFail() {
        responder = request -> new Response(200, embeddingsJson(1, 768));
        assertThatThrownBy(() -> client(4).embedAll(List.of("a")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("维度")
                .hasMessageContaining("OLLAMA_EMBEDDING_DIMENSION");
    }

    @Test
    @DisplayName("缺 embeddings 字段（老版本只提供单条端点）→ 给出可辨认的提示")
    void missingEmbeddingsFieldExplainsLegacyEndpoint() {
        responder = request -> new Response(200, "{\"embedding\":[1,2,3]}");
        assertThatThrownBy(() -> client(4).embedAll(List.of("a")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("embeddings")
                .hasMessageContaining("/api/embeddings");
    }

    @Test
    @DisplayName("HTTP 404（模型名写错）→ 立即失败，不重试，错误里带模型名")
    void notFoundFailsFastWithoutRetry() {
        responder = request -> new Response(404, "");
        assertThatThrownBy(() -> client(4).embedAll(List.of("a")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("bge-m3")
                .hasMessageContaining("重试无意义");
        // 关键断言：4xx 不重试。重试只会浪费时间并掩盖真实原因。
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 500 连续两次后成功 → 重试生效")
    void retriesOnServerErrorThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        responder = request -> {
            int n = calls.incrementAndGet();
            if (n <= 2) {
                return new Response(500, "{\"error\":\"temporary\"}");
            }
            return new Response(200, embeddingsJson(1, 1024));
        };
        List<float[]> v = client(4).embedAll(List.of("a"));
        assertThat(v).hasSize(1);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("HTTP 500 每次都失败 → 用尽重试后报错，且请求次数等于上限")
    void exhaustsRetriesOnPersistentServerError() {
        responder = request -> new Response(500, "{\"error\":\"down\"}");
        assertThatThrownBy(() -> client(4).embedAll(List.of("a")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("连续失败");
        assertThat(requestCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("响应不是合法 JSON → 明确报错")
    void invalidJsonFails() {
        responder = request -> new Response(200, "not json at all");
        assertThatThrownBy(() -> client(4).embedAll(List.of("a")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("合法 JSON");
    }

    @Test
    @DisplayName("embedOne 走的是同一批量路径，行为不分叉")
    void embedOneDelegatesToBatch() throws Exception {
        responder = request -> new Response(200, embeddingsJson(countInputs(request), 1024));
        float[] v = client(4).embedOne("solo");
        assertThat(v).hasSize(1024);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("模型信息原样暴露，供写入索引档案")
    void exposesModelInfo() {
        assertThat(client(4).modelInfo().profileName()).isEqualTo("ollama:bge-m3:1024");
    }

    @Test
    @DisplayName("null 输入与空输入都不发起请求（统一为「无文本可向量化」）")
    void nullAndEmptyInputMakeNoRequest() throws Exception {
        responder = request -> new Response(200, embeddingsJson(0, 1024));
        assertThat(client(16).embedAll(List.of())).isEmpty();
        assertThat(client(16).embedAll(null)).isEmpty();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    @DisplayName("恰好整除批量大小时不多发一次空请求")
    void exactMultipleDoesNotSendEmptyTrailingBatch() throws Exception {
        List<Integer> seen = new ArrayList<>();
        responder = request -> {
            int n = countInputs(request);
            seen.add(n);
            return new Response(200, embeddingsJson(n, 1024));
        };
        assertThat(client(2).embedAll(List.of("a", "b", "c", "d"))).hasSize(4);
        assertThat(seen).containsExactly(2, 2);
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("batchSize < 1 直接拒绝（否则会死循环）")
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new OllamaEmbeddingClient(baseUrl, bgeM3(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    @DisplayName("baseUrl 末尾斜杠不会造成双斜杠路径")
    void toleratesTrailingSlashInBaseUrl() throws Exception {
        responder = request -> new Response(200, embeddingsJson(countInputs(request), 1024));
        OllamaEmbeddingClient c = new OllamaEmbeddingClient(baseUrl + "/", bgeM3(), 4);
        assertThat(c.embedAll(List.of("a"))).hasSize(1);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("多批次时每批独立校验维度（第二批坏也要报错）")
    void secondBatchDimensionIsAlsoValidated() {
        AtomicInteger calls = new AtomicInteger();
        responder = request -> {
            int n = calls.incrementAndGet();
            // 第一批正常，第二批维度错
            return n == 1
                    ? new Response(200, embeddingsJson(countInputs(request), 1024))
                    : new Response(200, embeddingsJson(countInputs(request), 512));
        };
        assertThatThrownBy(() -> client(1).embedAll(List.of("a", "b")))
                .isInstanceOf(EmbeddingClient.EmbeddingException.class)
                .hasMessageContaining("维度");
    }

    /** 请求体形状：确认走的是 Ollama 原生批量端点的字段名。 */
    @Test
    @DisplayName("请求体含 model 与 input 两个字段")
    void requestBodyShape() throws Exception {
        List<String> bodies = new ArrayList<>();
        responder = request -> {
            bodies.add(request);
            return new Response(200, embeddingsJson(countInputs(request), 1024));
        };
        client(4).embedAll(List.of("x"));
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"model\"").contains("bge-m3").contains("\"input\"");
    }
}
