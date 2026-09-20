package com.canalmind.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

import reactor.core.publisher.Flux;

/**
 * 流式问答接口的端到端测试：**真实启动 Tomcat，走真实 HTTP 连接**。
 *
 * <p><b>为什么值得写</b>：单元测试只能证明"转义函数是对的"，
 * 证明不了"SSE 分帧在真实 HTTP 上是完整的"。而本项目的核心风险恰恰是后者——
 * 一旦转义没生效，事件边界错位只会在真实连接上暴露。
 *
 * <p><b>为什么不依赖 DeepSeek</b>：本机无 API Key，且测试不应依赖外部服务
 * （不稳定、慢、要花钱）。这里用 {@link StubChatModel} 替换模型，
 * 只验证**框架接线与 SSE 协议行为**。两者必须分开验证，
 * 否则失败时无法判断是接线错、协议错还是网络错。
 *
 * <p><b>如何关掉 DeepSeek 自动配置（这里踩过坑，记下来）</b>：
 * 正确的开关是 <b>{@code spring.ai.model.chat=none}</b>，
 * 因为该自动配置上的条件注解实测为：
 * <pre>
 *   @ConditionalOnProperty(name = "spring.ai.model.chat",
 *                          havingValue = "deepseek", matchIfMissing = true)
 * </pre>
 * ⚠️ 而 {@code spring.ai.deepseek.chat.enabled=false} **不起作用**——
 * 它虽然在构件的配置元数据里存在（默认 true），但并不是该类生效条件的开关。
 * 这一点最初被我写错过，实测报 "DeepSeek API key must be set" 才纠正过来。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.model.chat=none")
class ChatStreamControllerTest {

    @LocalServerPort
    private int port;

    @TestConfiguration
    static class StubChatModelConfig {
        /**
         * 桩模型。返回的分片刻意含换行、代码块与反斜杠路径——
         * 正是会让 SSE 事件边界错位的三类内容。
         * 分片切在换行符边界上（{@code "说明：\n"} 与 {@code "\n"}），
         * 这是最容易暴露转义缺陷的切法。
         */
        @Bean
        @Primary
        ChatModel stubChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage(String.join("", chunks())))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.fromIterable(chunks())
                            .map(t -> new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
                }

                private List<String> chunks() {
                    return List.of(
                            "运费说明：\n",
                            "\n",
                            "1. 过闸费按吨位分档\n",
                            "2. 港杂费按票计收\n",
                            "路径示例：C:\\new\\path\n",
                            "以上仅供参考。");
                }
            };
        }
    }

    /** 读完整条 SSE 流，返回原始行。 */
    private List<String> readSse(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<Stream<String>> response =
                client.send(request, HttpResponse.BodyHandlers.ofLines());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("SSE 响应头")
                .startsWith("text/event-stream");

        List<String> lines = new ArrayList<>();
        boolean[] doneSeen = {false};
        try (Stream<String> body = response.body()) {
            // 流理论上是无限的（真实模型会持续产 token），不能直接 toList 收集。
            // 判停点必须落在 done 事件**之后的那条 data 行**上——
            // 若只停在 "event:done"，就会漏掉它的 data 载荷，
            // 导致 "data 行数 == event 行数" 这条断言假失败（这个坑实际踩过）。
            body.takeWhile(line -> {
                lines.add(line);
                if (doneSeen[0] && line.startsWith("data:")) {
                    return false;
                }
                if (line.equals("event:done")) {
                    doneSeen[0] = true;
                }
                return true;
            }).forEach(line -> { });
        }
        return lines;
    }

    @Test
    @DisplayName("SSE 流返回 meta / token / done 三类事件")
    void emitsExpectedEventTypes() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=过闸费怎么算&sessionId=it-1");
        String joined = String.join("\n", lines);

        assertThat(joined).contains("event:meta");
        assertThat(joined).contains("event:token");
        assertThat(joined).contains("event:done");
    }

    @Test
    @DisplayName("★ 核心断言：转义生效，协议中没有游离行（事件边界未错位）")
    void noStrayLinesOnWire() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=测试转义&sessionId=it-2");

        // SSE 规范里，每个非空行都必须以字段名开头。
        // 若转义失效，含换行的分片会把 data: 字段劈开，
        // 于是出现"不以 data:/event:/id:/retry: 开头的非空行"。
        List<String> stray = lines.stream()
                .filter(l -> !l.isEmpty())
                .filter(l -> !l.startsWith("data:"))
                .filter(l -> !l.startsWith("event:"))
                .filter(l -> !l.startsWith("id:"))
                .filter(l -> !l.startsWith("retry:"))
                .toList();

        assertThat(stray)
                .as("存在游离行说明换行转义失效，SSE 事件边界已错位")
                .isEmpty();
    }

    @Test
    @DisplayName("data 行中的换行是字面量 \\n，而非真实换行")
    void dataLinesCarryEscapedNewlines() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=测试转义&sessionId=it-3");

        List<String> dataLines = lines.stream().filter(l -> l.startsWith("data:")).toList();
        assertThat(dataLines).isNotEmpty();

        // 真实换行已被转义，因此字面量 \n 应当出现
        assertThat(String.join("\n", dataLines))
                .as("data 行中应含字面量 \\n")
                .contains("\\n");

        // 且 data 行里不得出现真实换行（它们本就不可能是多行，这里作双保险）
        dataLines.forEach(l -> assertThat(l).doesNotContain("\n").doesNotContain("\r"));
    }

    @Test
    @DisplayName("data 行数应等于 event 行数（事件与载荷一一对应）")
    void dataAndEventCountsMatch() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=计数&sessionId=it-4");

        long dataCount = lines.stream().filter(l -> l.startsWith("data:")).count();
        long eventCount = lines.stream().filter(l -> l.startsWith("event:")).count();

        assertThat(dataCount).isEqualTo(eventCount);
    }
}
