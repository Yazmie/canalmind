package com.canalmind.chat.frame;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Flux;

import com.canalmind.chat.ChatFrameSource;

/**
 * <b>真实装配器</b>{@link ChatFrameAssembler} 的装载与失败软着陆测试。
 *
 * <h2>为什么必须单独有这一个测试（它补的是一个真实缺口）</h2>
 * {@code ChatStructuredFrameTest} 用的是**桩帧源**，证明的是"帧在线上不破坏协议"；
 * 但它<b>从未装载过真实的装配器 Bean</b>。于是下面这两件事一直没人验证：
 * <ol>
 *   <li><b>开关打开时上下文到底能不能起来</b>：真实装配器要注入
 *       {@code JdbcTemplate} / {@code ChatModel} / {@code Environment} /
 *       {@code ObservationRegistry}，并把连接数据库与 Ollama 的动作**推迟到第一次提问**。
 *       若构造器里不小心碰了 I/O，那么在没有 PG / 没有 Ollama 的机器上
 *       应用会**启动即失败**——而且是"打开开关才失败"，最难在开发机发现。
 *       本测试在 {@code canalmind.chat.structured-frame=on} 下启动，
 *       断言上下文成功装载 + 装配器 Bean 存在且类型正确。</li>
 *   <li><b>依赖不可用时是否真的软着陆</b>：类注释承诺"帧装配失败只让帧缺席，
 *       ⛔ 绝不让问答变成 error 事件"。这条承诺必须被断言，否则它只是句注释。</li>
 * </ol>
 *
 * <h2>为什么不依赖 PG / Ollama 是否在跑（本测试的确定性来源）</h2>
 * 两种环境下断言都成立，故测试**不因本机环境而红**：
 * <ul>
 *   <li>库与 Ollama 可达 ⇒ 可能出一帧（也可能是 DEGRADED，因为桩模型答不出合法 JSON）——
 *       只要出帧，就断言它是**单行合法 JSON**；</li>
 *   <li>不可达 ⇒ 没有帧 —— 断言正文链路与改动前完全一致（meta/token/done，且**无 error**）。</li>
 * </ul>
 * ⚠️ 两条路都**零付费**：模型侧是桩 {@code ChatModel}，检索是本地 Ollama 向量（不出网）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.chat=none",
                "canalmind.chat.structured-frame=on"
        })
class ChatFrameAssemblerWiringTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    /** 真实装配器的开关属性值，本测试刻意显式写一遍（与源码常量对拍）。 */
    private static final String ENABLED_BY = ChatFrameAssembler.ENABLED_PROPERTY + "=on";

    @TestConfiguration
    static class StubModelConfig {
        @Bean
        @Primary
        ChatModel stubChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(
                            new Generation(new AssistantMessage("这不是合法 JSON，抽取层应当自行降级"))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(new ChatResponse(List.of(
                            new Generation(new AssistantMessage("按规则语料，过闸费按吨位分档计收。")))));
                }
            };
        }
    }

    @Test
    @DisplayName("★ 开关打开时上下文正常装载，且真实装配器 Bean 就位")
    void contextLoadsWithRealAssembler() {
        assertThat(ENABLED_BY).isEqualTo("canalmind.chat.structured-frame=on");

        // 若真实装配器在构造期就尝试连库/连模型，本断言会在"没有 PG / 没有 Ollama"的机器上失败。
        assertThat(context.getBeanNamesForType(ChatFrameSource.class))
                .as("开关打开后应恰好有一个帧源 Bean（真实装配器）")
                .hasSize(1);
        assertThat(context.getBean(ChatFrameSource.class))
                .as("必须是真实装配器，⛔ 不是 ChatFrameSource.NONE")
                .isInstanceOf(ChatFrameAssembler.class);
    }

    @Test
    @DisplayName("★ 依赖不可用时软着陆：问答照常完成，⛔ 不出现 error 事件")
    void softLandsWhenDependenciesUnavailable() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=建材3200吨过闸费多少&sessionId=asm-1");
        String joined = String.join(String.valueOf((char) 10), lines);

        assertThat(joined).contains("event:meta");
        assertThat(joined).contains("event:token");
        assertThat(joined).contains("event:done");
        // 这条是本测试的核心：整条链路里**不得**出现 error 事件。
        // 帧是增强信息，它的失败绝不能把问答本身变成失败。
        assertThat(joined)
                .as("帧装配失败必须软着陆（只少一帧），⛔ 不得转成 error 事件")
                .doesNotContain("event:error");

        // 若这一帧确实出来了（本机 PG/Ollama 可用时），它必须满足"单行 JSON"这条协议约定。
        long frames = lines.stream().filter("event:structured"::equals).count();
        assertThat(frames).isLessThanOrEqualTo(1);
        if (frames == 1) {
            String data = payloadOf(lines, "structured");
            assertThat(data)
                    .as("帧载荷必须是单行 JSON（出现换行即破坏 SSE 事件边界）")
                    .startsWith("{").endsWith("}");
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(data);
        }
    }

    @Test
    @DisplayName("协议仍然干净：无游离行，事件与载荷一一对应（开关打开也不变）")
    void wireStaysCleanWhenEnabled() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=转义与分帧&sessionId=asm-2");

        List<String> stray = lines.stream()
                .filter(l -> !l.isEmpty())
                .filter(l -> !l.startsWith("data:"))
                .filter(l -> !l.startsWith("event:"))
                .filter(l -> !l.startsWith("id:"))
                .filter(l -> !l.startsWith("retry:"))
                .toList();
        assertThat(stray).isEmpty();

        long dataCount = lines.stream().filter(l -> l.startsWith("data:")).count();
        long eventCount = lines.stream().filter(l -> l.startsWith("event:")).count();
        assertThat(dataCount).isEqualTo(eventCount);
    }

    private String payloadOf(List<String> lines, String eventName) {
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).equals("event:" + eventName)) {
                String dataLine = lines.get(i + 1);
                assertThat(dataLine).startsWith("data:");
                return dataLine.substring("data:".length()).trim();
            }
        }
        throw new AssertionError("没有找到 " + eventName + " 事件");
    }

    /** 读完整条 SSE 流，返回原始行（与既有两个 SSE 测试同一手法）。 */
    private List<String> readSse(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60))
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
            }).forEach(line -> { });
        }
        return lines;
    }
}
