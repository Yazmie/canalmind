package com.canalmind.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import com.canalmind.chat.frame.ChatFrameCodec;
import com.canalmind.chat.frame.FrameCitation;
import com.canalmind.intent.RouteDecision;
import com.canalmind.structured.ToolResult;

/**
 * {@code event: structured} 帧的**真实 HTTP 端到端测试**。
 *
 * <p><b>为什么必须走真实连接</b>：单元测试（{@link com.canalmind.chat.frame.ChatFrameCodecTest}）
 * 能证明"编码出来是单行的"，证明不了"这帧混进 SSE 流之后事件边界还完整"。
 * 而本项目在 SSE 上踩过的坑恰恰全在后者。
 *
 * <p><b>为什么用桩帧源而不是真装配器</b>：真正的 {@code ChatFrameAssembler} 要连
 * PG + Ollama 并会调用付费模型，测试不该依赖这些（慢、不稳、要花钱）。
 * 这里塞一个返回**真实编码产物**的帧源——载荷用
 * {@link ChatFrameCodec} 生成（含换行与反斜杠的最难缠正文），
 * 于是"编码器 + 传输"两段被接在一起验证，
 * 而"数据从哪来"这一段由 {@code ChatFrameCodecTest} 与装配器各自的测试覆盖。
 *
 * <p><b>零改动既有测试的证明</b>：本类**不动** {@code ChatStreamControllerTest} 一个字，
 * 它仍在不带帧源的上下文里跑（默认配置下容器里就没有帧源），
 * 四条既有协议断言因此保持原意。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.model.chat=none")
class ChatStructuredFrameTest {

    /** 真实换行 / 反斜杠一律用计算字符，⛔ 不用转义字面量（AGENTS.md §7.2）。 */
    private static final String LF = String.valueOf((char) 10);
    private static final String BACKSLASH = String.valueOf((char) 92);

    /** 与前端 mock 演示同一个业务事实：建材 3200 吨落在 [2000,5000) 档，单价 3.30 元/吨。 */
    private static final String AMOUNT = "10560.00";

    @LocalServerPort
    private int port;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestConfiguration
    static class StubConfig {

        /** 与 {@code ChatStreamControllerTest} 同款：分片含换行与反斜杠路径。 */
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
                    return List.of("按 2026 版费率表：", LF, "建材 3200 吨适用 [2000,5000) 档。");
                }
            };
        }

        /**
         * 桩帧源：载荷由**真实的** {@link ChatFrameCodec} 生成，
         * 正文里故意塞进换行与反斜杠——若编码或分帧有缺陷，本类会立刻变红。
         */
        @Bean
        ChatFrameSource stubFrameSource() {
            return (question, sessionId) -> {
                RouteDecision route = new RouteDecision(
                        RouteDecision.CHANNEL_STRUCTURED,
                        List.of(new RouteDecision.RouteStep(RouteDecision.CHANNEL_STRUCTURED,
                                question, "过闸费多少")),
                        "R1-数值诉求-结构化",
                        "命中数值诉求「多少」⇒ 该数值应由数据库算出，走受控工具集",
                        false, "", 1.0);
                ToolResult tool = ToolResult.ok("quote_fee",
                        List.of("费种", "货类", "计费吨位", "档位", "单价", "单位", "金额(元)", "规则码"),
                        List.of(List.of("过闸费", "建材", "3200", "[2000,5000)", "3.30", "元/吨",
                                AMOUNT, "PLYH-FL-2026-006-第四条")),
                        List.of("过闸费金额 = " + AMOUNT + " 元",
                                "计算过程：3200（载货吨位） × 3.30 元/吨 = " + AMOUNT + " 元" + LF
                                        + "路径示例：C:" + BACKSLASH + "new" + BACKSLASH + "path",
                                "依据规则码 PLYH-FL-2026-006-第四条"),
                        List.of("工具=quote_fee"));
                // ⚠️ 摘录走 ChatFrameCodec.excerpt —— 与真实装配器（ChatFrameAssembler.citations）
                //    同一条路径。⛔ 不要在测试桩里手写摘录文本：那会让"摘录确实被折成一行"
                //    这条断言变成在测测试自己（首次写这个桩时就踩过：桩里漏调 excerpt，
                //    断言因此变红，而生产代码是对的）。
                FrameCitation citation = new FrameCitation("chunk-0001",
                        "过闸费费率表（2026版）", "PLYH-FL-2026-006", "第四条",
                        "【建材类过闸费费率】", List.of("第二章 费率标准"), 1, 1,
                        ChatFrameCodec.excerpt(
                                "建材类货物过闸费费率如下：" + LF + "[2000,5000) 档 3.30 元/吨"));
                return Optional.of(ChatFrameCodec.encode(sessionId, route, tool, List.of(citation)));
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

    /** 取某个事件名之后那条 data 行的载荷。 */
    private static String payloadOf(List<String> lines, String eventName) {
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).equals("event:" + eventName)) {
                String dataLine = lines.get(i + 1);
                assertThat(dataLine).startsWith("data:");
                return dataLine.substring("data:".length()).trim();
            }
        }
        throw new AssertionError("没有找到 " + eventName + " 事件，实际事件行：" + lines.stream()
                .filter(l -> l.startsWith("event:")).toList());
    }

    @Test
    @DisplayName("★ 帧上线后事件顺序为 meta → structured → token → done")
    void emitsStructuredFrameInOrder() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=建材3200吨过闸费多少&sessionId=fr-1");
        List<String> events = lines.stream()
                .filter(l -> l.startsWith("event:"))
                .map(l -> l.substring("event:".length()))
                .toList();

        assertThat(events).startsWith("meta", "structured");
        assertThat(events).endsWith("done");
        assertThat(events).contains("token");
        assertThat(events.stream().filter("structured"::equals))
                .as("结构化帧每轮只发一次（⛔ 不做增量分片）")
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 核心：帧混进流之后协议仍然干净（无游离行、事件与载荷一一对应）")
    void wireStaysCleanWithFrame() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=转义与分帧&sessionId=fr-2");

        List<String> stray = lines.stream()
                .filter(l -> !l.isEmpty())
                .filter(l -> !l.startsWith("data:"))
                .filter(l -> !l.startsWith("event:"))
                .filter(l -> !l.startsWith("id:"))
                .filter(l -> !l.startsWith("retry:"))
                .toList();
        assertThat(stray)
                .as("帧载荷里若含裸换行，事件边界就会错位并出现游离行")
                .isEmpty();

        long dataCount = lines.stream().filter(l -> l.startsWith("data:")).count();
        long eventCount = lines.stream().filter(l -> l.startsWith("event:")).count();
        assertThat(dataCount).as("事件与载荷必须一一对应").isEqualTo(eventCount);
    }

    @Test
    @DisplayName("帧载荷可被前端按约定解析：金额是字符串、引用带文档名与条款号")
    void framePayloadMatchesFrontendContract() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=建材3200吨过闸费多少&sessionId=fr-3");
        JsonNode root = MAPPER.readTree(payloadOf(lines, "structured"));

        assertThat(root.path("v").asInt()).isEqualTo(ChatFrameCodec.VERSION);
        assertThat(root.path("sessionId").asText()).isEqualTo("fr-3");
        assertThat(root.path("route").path("channel").asText()).isEqualTo("STRUCTURED");
        assertThat(root.path("tool").path("status").asText()).isEqualTo("OK");

        JsonNode columns = root.path("tool").path("columns");
        int amountCol = -1;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).asText().contains("金额")) {
                amountCol = i;
            }
        }
        assertThat(amountCol).as("前端靠列名找金额列，合同必须保留「金额(元)」").isNotNegative();
        assertThat(root.path("tool").path("rows").get(0).get(amountCol).asText()).isEqualTo(AMOUNT);

        JsonNode citation = root.path("citations").get(0);
        assertThat(citation.path("docTitle").asText()).isEqualTo("过闸费费率表（2026版）");
        assertThat(citation.path("articleNo").asText()).isEqualTo("第四条");
        assertThat(citation.path("chapterPath").get(0).asText()).isEqualTo("第二章 费率标准");
    }

    @Test
    @DisplayName("★ 帧里的换行与反斜杠跨过 HTTP 之后仍原样可还原")
    void newlineAndBackslashSurviveTheWire() throws Exception {
        List<String> lines = readSse("/api/chat/stream?question=转义守恒&sessionId=fr-4");
        JsonNode root = MAPPER.readTree(payloadOf(lines, "structured"));

        String fact = root.path("tool").path("facts").get(1).asText();
        assertThat(fact)
                .as("正文里的真实换行必须还原出来，⛔ 不能被折成空格或字面量")
                .contains(LF);
        assertThat(fact)
                .as("反斜杠路径必须原样（这正是 SseTextCodec 额外转义反斜杠要挡的损坏）")
                .contains("C:" + BACKSLASH + "new" + BACKSLASH + "path");

        String excerpt = root.path("citations").get(0).path("excerpt").asText();
        assertThat(excerpt)
                .as("摘录是唯一**刻意**折掉换行的字段（见 ChatFrameCodec.excerpt）")
                .doesNotContain(LF)
                .contains("[2000,5000) 档 3.30 元/吨");
    }
}
