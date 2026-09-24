package com.canalmind.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;

import reactor.core.publisher.Flux;

/**
 * 前端页跨域放行（决策 <b>C-70</b>）的**真实 HTTP 断言**。
 *
 * <h2>它守护的是一次真实的联调故障（2026-09-24）</h2>
 * 用户双击打开 {@code frontend/index.html}、切到「后端接口」模式发问：
 * 前端显示 {@code 接口不可用：无法连接 http://127.0.0.1:8080（Failed to fetch）}，
 * 而后端日志显示 {@code SseEmitter.send} 时连接被中止。
 * 根因是**没有 CORS 头**：请求（简单请求、不预检）打到了控制器、也开始推流，
 * 但响应缺 {@code Access-Control-Allow-Origin} ⇒ 浏览器丢弃响应并断开连接。
 *
 * <h2>为什么断言写在这些点上</h2>
 * <ul>
 *   <li><b>预检</b>：改前实测 {@code 403 Invalid CORS request}（Spring 在没有 CORS 配置时的固定反应），
 *       改后必须是 200 且带 ACAO——这条最灵敏；</li>
 *   <li><b>真实 GET 的响应头</b>：这才是浏览器 {@code Failed to fetch} 的直接原因；</li>
 *   <li><b>陌生来源仍 403</b>：证明放行面是**白名单**而不是"无脑通配"——
 *       ⚠️ 这条同时守护一个副作用：本接口没有鉴权、每次问答都烧付费额度，
 *       所以"白名单之外的来源"应当在**进控制器之前**就被拒掉；</li>
 *   <li><b>不带 Origin 时⛔ 不得出现 ACAO 头</b>：证明对 curl / 测试 / 服务端调用方**零影响**，
 *       ⛔ 不是"给所有响应都加了个头"。</li>
 * </ul>
 *
 * <p>用桩 {@code ChatModel}（{@code spring.ai.model.chat=none}）⇒ 本类**零付费调用**，
 * 与 {@code ChatStructuredFrameTest} 同一手法。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.model.chat=none")
class ChatCorsTest {

    /** 与 {@code ChatStructuredFrameTest} 同款的最简桩：够让 SSE 流跑完即可。 */
    private static final List<String> CHUNKS = List.of("按规则库：", "过闸费请以费率表为准。");

    @LocalServerPort
    private int port;

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage(String.join("", CHUNKS)))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.fromIterable(CHUNKS)
                            .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
                }
            };
        }
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 发一个带（或不带）Origin 头的 GET。 */
    private HttpResponse<String> get(String path, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (origin != null) {
            builder.header(HttpHeaders.ORIGIN, origin);
        }
        return client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 发一个 CORS 预检（OPTIONS + Access-Control-Request-Method）。 */
    private HttpResponse<String> preflight(String path, String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> allowOrigin(HttpResponse<String> response) {
        return response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private static final String CHAT = "/api/chat/stream?question=CORS自检&sessionId=cors-1";

    @Test
    @DisplayName("★ 核心：file:// 页面（Origin: null）预检从 403 变为放行")
    void preflightFromFilePageIsAllowed() throws Exception {
        HttpResponse<String> response = preflight("/api/chat/stream", "null");

        assertThat(response.statusCode())
                .as("改前实测 403 Invalid CORS request —— 这条断言就是那次故障的回归护栏")
                .isEqualTo(200);
        assertThat(allowOrigin(response))
                .as("必须回显字符串 \"null\"（file:// 页面的 Origin 就是它，⛔ 不是空值）")
                .contains("null");
    }

    @Test
    @DisplayName("★ 核心：带 Origin 的真实 GET 响应含 ACAO，且 SSE 正文完好")
    void realGetFromFilePageCarriesAllowOrigin() throws Exception {
        HttpResponse<String> response = get(CHAT, "null");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(allowOrigin(response))
                .as("没有这个头，浏览器就会 Failed to fetch 并断开连接（用户实测到的现象）")
                .contains("null");
        assertThat(response.body())
                .as("加了 CORS 头之后协议本体不许受影响")
                .contains("event:meta")
                .contains("event:token")
                .contains("event:done");
    }

    @Test
    @DisplayName("挂在本地静态服务器上的来源（127.0.0.1 / localhost 任意端口）同样放行")
    void localStaticServerOriginsAreAllowed() throws Exception {
        assertThat(allowOrigin(get(CHAT, "http://127.0.0.1:5500")))
                .as("VSCode Live Server 之类")
                .contains("http://127.0.0.1:5500");
        assertThat(allowOrigin(get(CHAT, "http://localhost:3000")))
                .contains("http://localhost:3000");
    }

    @Test
    @DisplayName("⚠️ 白名单之外的来源仍被拒（403），且在进控制器之前就拒")
    void unknownOriginIsRejected() throws Exception {
        HttpResponse<String> response = get(CHAT, "https://evil.example");

        assertThat(response.statusCode())
                .as("本接口无鉴权且每次问答都烧付费额度 ⇒ ⛔ 不能放行任意网站")
                .isEqualTo(403);
        assertThat(allowOrigin(response)).isEmpty();
        assertThat(response.body())
                .as("被拒时不该有任何 SSE 正文漏出去")
                .doesNotContain("event:token");
    }

    @Test
    @DisplayName("不带 Origin 的调用方（curl / 测试 / 服务端）响应里⛔ 没有 CORS 头")
    void nonBrowserCallersSeeNoCorsHeaders() throws Exception {
        HttpResponse<String> response = get(CHAT, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(allowOrigin(response))
                .as("对非浏览器调用方必须零影响，⛔ 不是给所有响应都加头")
                .isEmpty();
    }

    @Test
    @DisplayName("放行范围只到 /api/chat/**：其他端点即使带本机 Origin 也不给 ACAO")
    void otherEndpointsAreNotOpened() throws Exception {
        HttpResponse<String> response = get("/actuator/health", "null");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(allowOrigin(response))
                .as("C-70 的放行面刻意只到聊天接口（见 ChatCorsConfig.MAPPING）")
                .isEmpty();
    }
}
