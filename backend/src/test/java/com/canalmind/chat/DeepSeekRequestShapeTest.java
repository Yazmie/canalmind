package com.canalmind.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 用**本地桩服务器**验证 DeepSeek 调用链路（不发真实请求、不需要外网）。
 *
 * <p><b>为什么必须写这个测试</b>：真实 API Key 与联网调用无法在开发环境验证，
 * 但"接线是否正确"与"能否联网"是**两个独立的失败原因**，必须分开验证。
 * 本测试覆盖前者：把 {@code spring.ai.deepseek.base-url} 指向本地桩，
 * 于是能断言**真正发出去的 HTTP 请求长什么样**——请求路径对不对、
 * 鉴权头带没带上、请求体里 {@code stream} 是否为真、模型名对不对。
 *
 * <p>更关键的是，它能回答一个**靠读文档判断不了**的问题：
 * 本项目类路径上**只有 {@code spring-webflux}，没有 Netty / HttpClient /
 * OkHttp**（实测 110 个构件，HTTP 客户端相关只有 reactor-core 与 spring-webflux）。
 * 而 Spring AI 的流式调用走 {@code WebClient}——没有传输层实现时，
 * 要么构造即失败，要么退化成"缓冲整个响应"，**流式就名存实亡**。
 * 该问题只在真实发出请求时才暴露，所以必须有这个测试。
 *
 * <p>桩服务器用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}，
 * 不引入任何新依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeepSeekRequestShapeTest {

    private static final String STUB_KEY = "stub-key-for-test";
    private static final String MODEL = "deepseek-chat";

    // ---- 桩服务器捕获的请求 ----
    private static final List<String> capturedPaths = new CopyOnWriteArrayList<>();
    private static final List<String> capturedAuth = new CopyOnWriteArrayList<>();
    private static final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    private static final List<String> capturedAccept = new CopyOnWriteArrayList<>();

    private static HttpServer stub;
    private static int stubPort;

    /** 分片内容刻意含换行，用来同时验证转义链路。 */
    private static final List<String> DELTAS = List.of(
            "过闸费按吨位分档：\n",
            "\n",
            "1. 0-1000 吨\n",
            "2. 1000-3000 吨\n");

    @LocalServerPort
    private int appPort;

    /**
     * 捕获用的 list 是 static（桩服务器也是 static），因此**必须逐用例清空**。
     * 否则用例执行顺序会互相污染——实测踩过：断言取 {@code get(0)} 时拿到的是
     * 另一个用例发的请求体，导致"问题文本对不上"的假失败。
     */
    @BeforeEach
    void clearCaptured() {
        capturedPaths.clear();
        capturedAuth.clear();
        capturedBodies.clear();
        capturedAccept.clear();
    }

    /**
     * 桩服务器必须在 Spring 上下文创建**之前**就绪，否则 base-url 拿不到端口。
     * {@code @DynamicPropertySource} 正是在上下文创建期求值，
     * 所以在这里启动（而不是 {@code @BeforeAll}——扩展回调早于它，
     * 会导致上下文先于桩服务器建立，端口为 0）。
     */
    @DynamicPropertySource
    static void deepSeekProperties(DynamicPropertyRegistry registry) throws IOException {
        ensureStubStarted();
        registry.add("spring.ai.deepseek.base-url", () -> "http://127.0.0.1:" + stubPort);
        registry.add("spring.ai.deepseek.api-key", () -> STUB_KEY);
        registry.add("spring.ai.deepseek.chat.options.model", () -> MODEL);
    }

    private static synchronized void ensureStubStarted() throws IOException {
        if (stub != null) {
            return;
        }
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/chat/completions", DeepSeekRequestShapeTest::handleChatCompletions);
        stub.setExecutor(null);
        stub.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stub.stop(0)));
    }

    /** 按 OpenAI 兼容协议返回 SSE 流，并逐个 flush，让"流式"可被观测。 */
    private static void handleChatCompletions(HttpExchange exchange) throws IOException {
        capturedPaths.add(exchange.getRequestURI().getPath());
        capturedAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        capturedAccept.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
        capturedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream os = exchange.getResponseBody()) {
            int i = 0;
            for (String delta : DELTAS) {
                String chunk = "{\"id\":\"stub-1\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                        + toJsonString(delta) + "},\"finish_reason\":null}]}";
                os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                i++;
            }
            String last = "{\"id\":\"stub-1\",\"object\":\"chat.completion.chunk\","
                    + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
            os.write(("data: " + last + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    private static String toJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 调本项目自己的 SSE 接口，走完整链路：我们的接口 → Spring AI → 桩 DeepSeek。 */
    private List<String> callOurStreamEndpoint(String question) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + appPort + "/api/chat/stream?question="
                        + URLEncoder.encode(question, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Stream<String>> response =
                client.send(request, HttpResponse.BodyHandlers.ofLines());
        assertThat(response.statusCode()).isEqualTo(200);

        List<String> lines = new ArrayList<>();
        boolean[] doneSeen = {false};
        try (Stream<String> body = response.body()) {
            body.takeWhile(line -> {
                lines.add(line);
                if (doneSeen[0] && line.startsWith("data:")) {
                    return false;
                }
                if (line.equals("event:done")) {
                    doneSeen[0] = true;
                }
                return true;
            }).forEach(l -> { });
        }
        return lines;
    }

    @Test
    @DisplayName("★ 请求发到了 /chat/completions，且带上了 Bearer 鉴权头")
    void sendsRequestToCorrectPathWithAuth() throws Exception {
        callOurStreamEndpoint("过闸费怎么算");

        assertThat(capturedPaths).as("上游请求路径").isNotEmpty();
        assertThat(capturedPaths.get(0)).isEqualTo("/chat/completions");
        assertThat(capturedAuth.get(0))
                .as("鉴权头必须为 Bearer <api-key>，否则真实调用会 401")
                .isEqualTo("Bearer " + STUB_KEY);
    }

    @Test
    @DisplayName("★ 请求体包含 stream=true、模型名与用户问题")
    void requestBodyCarriesStreamAndModel() throws Exception {
        callOurStreamEndpoint("过闸费怎么算");

        assertThat(capturedBodies).isNotEmpty();
        String body = capturedBodies.get(0);
        assertThat(body).as("流式请求必须带 stream:true").contains("\"stream\":true");
        assertThat(body).as("模型名须来自配置").contains(MODEL);
        assertThat(body).as("用户问题须出现在 messages 中").contains("过闸费怎么算");
    }

    @Test
    @DisplayName("★ 桩的 SSE 增量被解析为多个 token 事件（流式确实生效，未被整体缓冲）")
    void streamingIsIncremental() throws Exception {
        List<String> lines = callOurStreamEndpoint("过闸费怎么算");

        List<String> tokenEvents = lines.stream().filter(l -> l.equals("event:token")).toList();
        assertThat(tokenEvents)
                .as("桩发了 %d 个增量，应产生对应数量的 token 事件；"
                        + "若只有 1 个，说明传输层把响应整体缓冲了，流式名存实亡",
                        DELTAS.size())
                .hasSize(DELTAS.size());
    }

    @Test
    @DisplayName("桩返回的换行仍被无损转义（整链路贯通）")
    void escapingStillHoldsThroughRealModelPath() throws Exception {
        List<String> lines = callOurStreamEndpoint("过闸费怎么算");

        List<String> stray = lines.stream()
                .filter(l -> !l.isEmpty())
                .filter(l -> !l.startsWith("data:"))
                .filter(l -> !l.startsWith("event:"))
                .toList();
        assertThat(stray).as("存在游离行说明事件边界错位").isEmpty();

        String data = String.join("\n", lines.stream().filter(l -> l.startsWith("data:")).toList());
        assertThat(data).as("换行应被转义为字面量").contains("\\n");
    }

    @Test
    @DisplayName("系统提示词与温度随请求下发（配置真生效，用的是我们的值而非框架默认）")
    void sendsSystemPromptAndTemperatureFromConfig() throws Exception {
        callOurStreamEndpoint("过闸费怎么算");

        String body = capturedBodies.get(capturedBodies.size() - 1);
        assertThat(body)
                .as("系统提示词来自 ChatClientConfig，必须出现在 messages 里")
                .contains("平陆运河航运物流业务助手");
        assertThat(body)
                .as("temperature 来自 application.yml，配了就必须生效")
                .contains("\"temperature\":0.1");
    }

    @Test
    @DisplayName("观测记录：上游未声明 Accept: text/event-stream（属正常，非缺陷）")
    void documentsAcceptHeaderBehaviour() throws Exception {
        callOutStreamEndpointSafely();

        // 实测 Spring AI 发的是 Accept: */*，流式由**请求体**的 stream:true 决定，
        // 而不是靠 Accept 协商。因此这里不断言具体值，只把事实记下来——
        // 若将来排障时看到 */* 而怀疑流式没开，请看本用例。
        assertThat(capturedAccept)
                .as("Accept 头应被记录（其值由框架决定，本项目不依赖它）")
                .isNotEmpty();
    }

    private void callOutStreamEndpointSafely() throws Exception {
        callOurStreamEndpoint("测试");
    }
}
