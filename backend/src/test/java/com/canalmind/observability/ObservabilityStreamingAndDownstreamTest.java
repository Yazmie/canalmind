package com.canalmind.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库《AI 可观测性与 Trace…》第 465–472 行场景 <b>⑤ 流式模型响应</b> 与
 * <b>⑥ 通过 HTTP 调用下游</b>的集成测试（规格书第 661 行那半句）。
 *
 * <p>另六个场景（①②③④⑦⑧）在同包的 {@code ObservabilityEightScenariosTest}（离线）。
 *
 * <h2>为什么这个类必须起一个本地桩服务器</h2>
 * <p>⑤⑥ 要验证的正是<b>真实发出 HTTP 请求</b>时观测链路长什么样：
 * 「流式响应有没有被观测到」与「跨进程调用有没有把上下文带过去」这两件事，
 * <b>靠读代码判断不了</b>。桩服务器用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer}，
 * 不引入任何新依赖，也⛔ 不调用真实 DeepSeek 中转（零付费）。
 *
 * <h2>⚠️ 本类如实断言的两条现状（⛔ 不为好看而改断言）</h2>
 * <ol>
 *   <li><b>SSE 路径没有任何 §7.2 业务 Span</b>：{@code ChatStreamController} 直接走
 *       {@code ChatClient.stream()}，<b>不经过 {@code QuestionPipeline}</b>，
 *       所以根 Span / 检索段 / 生成段在那条链路上<b>一个都不会产生</b>。
 *       这不是"漏了埋点"，而是这条链路的现状；本类把它<b>断言下来</b>，
 *       将来谁把它接上观测，这条断言就会变红——届时应改成断言"接了哪些节点"。
 *       ⚠️ <b>实测</b>：该链路上注册表确实看见了 5 条<b>框架层</b>观测
 *       （{@code http.server.requests} / {@code http.client.requests} /
 *       {@code gen_ai.client.operation} / {@code spring.ai.chat.client} /
 *       {@code spring.ai.advisor}），其中 {@code gen_ai.client.operation} 是 OTel GenAI 约定名，
 *       也是这条链路上唯一可能带模型名与用量信息的地方（属性已落盘备查）。</li>
 *   <li><b>没有 {@code traceparent}、也没有 traceId</b>：实测类路径上没有
 *       {@code micrometer-tracing}、没有 {@code opentelemetry}（只有 micrometer-observation），
 *       故知识库第 480 行的「跨线程和跨服务以后 {@code traceId} 是否保持一致」在本项目
 *       <b>无法断言</b>（不存在这个量）。本类改为断言<b>「桩服务器确实收到了这次跨进程调用」</b>
 *       + <b>「请求头里没有 traceparent」</b>——后者是现状证据，
 *       一旦将来接入 tracing 就会变红，提醒复核 README 的已知限制。
 *       ⚠️ 另有一处实测差异：出站请求的 {@code Accept} 是通配值（{@code *} 加斜杠再加 {@code *}），
 *       <b>不是</b> {@code text/event-stream}（流式靠响应头 Content-Type 判定）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityStreamingAndDownstreamTest {

    private static final String STUB_KEY = "stub-key-for-test";
    private static final String MODEL = "deepseek-chat";

    /** 分片内容刻意含换行（与 {@code DeepSeekRequestShapeTest} 同源，用于同时验证转义链路）。 */
    private static final List<String> DELTAS = List.of(
            "过闸费按吨位分档：\n",
            "1. 0-1000 吨\n",
            "2. 1000-3000 吨\n");

    private static final List<String> capturedPaths = new CopyOnWriteArrayList<>();
    private static final List<String> capturedAuth = new CopyOnWriteArrayList<>();
    private static final List<String> capturedAccept = new CopyOnWriteArrayList<>();
    private static final List<String> capturedTraceparent = new CopyOnWriteArrayList<>();

    private static HttpServer stub;
    private static int stubPort;

    @LocalServerPort
    private int appPort;

    @Autowired
    private ObservationRegistry applicationRegistry;

    @BeforeEach
    void clearCaptured() {
        capturedPaths.clear();
        capturedAuth.clear();
        capturedAccept.clear();
        capturedTraceparent.clear();
    }

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
        stub.createContext("/chat/completions", ObservabilityStreamingAndDownstreamTest::handle);
        stub.setExecutor(null);
        stub.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stub.stop(0)));
    }

    /** 按 OpenAI 兼容协议返回 SSE 流，并逐个 flush（让"流式"可被观测）；同时记下请求头。 */
    private static void handle(HttpExchange exchange) throws IOException {
        capturedPaths.add(exchange.getRequestURI().getPath());
        capturedAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        capturedAccept.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
        // ⚠️ 这就是"跨服务上下文传播"要看的那个头：没有它，下游无法把两段 Trace 拼起来
        capturedTraceparent.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
        exchange.getRequestBody().readAllBytes();

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream os = exchange.getResponseBody()) {
            for (String delta : DELTAS) {
                String chunk = "{\"id\":\"stub-1\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1700000000,\"model\":\"" + MODEL + "\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                        + toJsonString(delta) + "},\"finish_reason\":null}]}";
                os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
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
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ==================== 场景 ⑤：流式模型响应 ====================

    @Test
    @DisplayName("场景⑤：流式响应真的逐片到达，但 SSE 链路上没有任何 §7.2 业务 Span（如实断言现状）")
    void scenario5StreamingArrivesButTheSsePathEmitsNoBusinessSpan() throws Exception {
        CapturingHandler handler = new CapturingHandler();
        applicationRegistry.observationConfig().observationHandler(handler);

        String body = readStream("闸室水深不足时怎么办？");

        // ① 先证明"流式真的发生了"：分片数必须是多片（整体缓冲只会有一片）
        long tokenEvents = body.lines().filter(l -> l.equals("event:token")).count();
        assertThat(tokenEvents)
                .as("SSE 必须真的逐片下发（这是「流式」的事实证据）。只有 1 片说明被整体缓冲了，"
                        + "那「流式模型响应」这个场景根本没被覆盖。收到的事件：" + body)
                .isGreaterThan(1);
        assertThat(body).as("三段桩分片的内容必须都能看到").contains("0-1000 吨").contains("1000-3000 吨");

        // ② 跨进程调用确实发生了
        assertThat(capturedPaths)
                .as("桩服务器必须真的收到过这次下游调用（⛔ 否则本场景什么都没验证到）")
                .isNotEmpty();
        assertThat(capturedPaths).allMatch(p -> p.equals("/chat/completions"));

        // ③ ⚠️ 如实断言现状：这条链路上没有任何 §7.2 业务 Span
        Set<String> businessSpans = new HashSet<>(handler.names());
        businessSpans.retainAll(AiSpans.ALL);

        // 把"注册表到底看见了什么"落盘：⛔ 否则"没有业务 Span"会因为"注册表压根什么都没收到"而假绿
        writeEvidence("sse-observed-spans.txt",
                "本次 SSE 调用期间注册表观测到的全部 Span 名（共 " + handler.names().size() + " 条）："
                        + String.valueOf((char) 10)
                        + (handler.names().isEmpty() ? "（一条都没有）" : String.join(String.valueOf((char) 10), handler.names()))
                        + String.valueOf((char) 10)
                        + "其中属于 §7.2 登记名的：" + businessSpans + String.valueOf((char) 10));

        // ④ 知识库第 479 行的「Token、模型版本是否能够查到」：SSE 链路上能查到的只有**框架层**
        assertThat(handler.names())
                .as("实测：Spring AI 自己会产出 gen_ai.client.operation（OTel GenAI 约定名）。"
                        + "它不属于 §7.2 的 16 个业务名，但它是这条链路上唯一可能带模型名/用量信息的地方；"
                        + "本次它的全部属性已落盘，供证据文档逐条引用。"
                        + "⛔ 本断言**不主张**它替代业务 Span——只是把现状钉住。本次全部："
                        + handler.names())
                .contains("gen_ai.client.operation");
        writeEvidence("sse-observed-span-attrs.txt", renderAttrEvidence(handler));

        assertThat(businessSpans)
                .as("⚠️ 本条断言的是**现状**：ChatStreamController 直接走 ChatClient.stream()，"
                        + "不经过 QuestionPipeline ⇒ 根/检索/生成三段业务 Span 在 SSE 链路上一个都不会产生。"
                        + "「注册表实际看见了什么」已落盘到 target/eval/observability-8-scenarios/"
                        + "sse-observed-spans.txt（⛔ 否则本条会因为注册表什么都没收到而假绿）。"
                        + "若本条变红，说明有人把 SSE 链路接上了观测——那是好事，"
                        + "请把本条改成断言「接了哪些节点」，并同步 README 的已知限制。"
                        + "本次观测到的全部 Span 名：" + handler.names())
                .isEmpty();
    }

    // ==================== 场景 ⑥：通过 HTTP 调用下游 ====================

    @Test
    @DisplayName("场景⑥：跨进程调用确实发生，但请求头里没有 traceparent（本项目无 tracing 桥，如实断言）")
    void scenario6DownstreamCallHappensButNoTraceContextIsPropagated() throws Exception {
        readStream("过闸费怎么算？");

        assertThat(capturedPaths)
                .as("场景 ⑥ 的前提：必须真的发生了跨进程 HTTP 调用")
                .isNotEmpty();
        assertThat(capturedAuth)
                .as("下游收到的是桩密钥（⛔ 本测试绝不接触真实中转）")
                .allMatch(h -> h.equals("Bearer " + STUB_KEY));
        assertThat(capturedAccept)
                .as("⚠️ 实测：本项目出站请求的 Accept 是 */*，**不是** text/event-stream"
                        + "（流式靠响应头的 Content-Type 判定，见 DeepSeekRequestShapeTest 的同名观测）。"
                        + "如实记录：下游若严格按 Accept 协商，这种写法拿不到 SSE。"
                        + "若将来改成显式声明 Accept: text/event-stream，本条会变红——届时应改成断言它")
                .allMatch(h -> h.equals("*/*"));

        assertThat(capturedTraceparent)
                .as("⚠️ 本条断言的是**现状**：实测类路径上没有 micrometer-tracing / opentelemetry"
                        + "（只有 micrometer-observation）⇒ 本项目不存在 traceId，"
                        + "HTTP 出站也就不会带 traceparent。"
                        + "于是知识库第 480 行的「跨服务以后 traceId 是否一致」在本项目**无法断言**——"
                        + "不是没做对，而是没有这个量。"
                        + "若本条变红，说明 tracing 桥已接入，应改为断言两端 traceId 一致，"
                        + "并同步 README / 规格书第 661 行那半句的状态")
                .allMatch(h -> h.equals("null") || h.isBlank());
    }

    // ==================== 工具 ====================

    /** 打一次流式接口，返回完整 SSE 文本（用 JDK HttpClient，与既有测试同法）。 */    private String readStream(String question) throws Exception {
        String path = "/api/chat/stream?question=" + URLEncoder.encode(question, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + appPort + path))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("流式接口应当返回 200；收到 " + response.body()).isEqualTo(200);
        return response.body();
    }

    /**
     * 把证据落盘到 {@code target/eval/observability-8-scenarios/}（gitignored）。
     *
     * <p>⛔ 换行自己钉死（{@code String.valueOf((char) 10)}，见 AGENTS §7.2）、无 BOM、UTF-8：
     * 产物要能跨平台字节可比。
     */
    private static void writeEvidence(String fileName, String content) throws IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("target", "eval", "observability-8-scenarios");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve(fileName),
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** 捕获 Span 名与属性（属性用于回答知识库第 479 行「Token、模型版本能否查到」）。 */
    private static final class CapturingHandler implements ObservationHandler<Observation.Context> {

        private static final String LF = String.valueOf((char) 10);

        private final List<String> spans = new ArrayList<>();
        private final Map<String, Map<String, String>> attributes = new LinkedHashMap<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            spans.add(context.getName());
            Map<String, String> attrs = new LinkedHashMap<>();
            for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                attrs.put(kv.getKey(), kv.getValue());
            }
            for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                attrs.put(kv.getKey(), kv.getValue());
            }
            attributes.put(context.getName(), attrs);
        }

        List<String> names() {
            return List.copyOf(spans);
        }

        Map<String, Map<String, String>> attributes() {
            return Map.copyOf(attributes);
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Map<String, String>> e : attributes.entrySet()) {
                sb.append(e.getKey()).append(" -> ").append(e.getValue()).append(LF);
            }
            return sb.toString();
        }
    }

    private static String renderAttrEvidence(CapturingHandler handler) {
        return "本次 SSE 调用期间各 Span 的属性（框架层观测；⛔ 没有 §7.2 业务 Span）："
                + String.valueOf((char) 10) + handler.render();
    }
}
