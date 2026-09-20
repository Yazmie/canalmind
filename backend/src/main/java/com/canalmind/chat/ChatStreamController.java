package com.canalmind.chat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Flux;

/**
 * 流式问答接口（SSE）。
 *
 * <p>事件约定（**服务端与前端必须固定同一语义**）：
 * <ul>
 *   <li>{@code event: meta} —— 会话元信息，前端据此准备渲染；</li>
 *   <li>{@code event: token} —— 增量正文，**已按 {@link SseTextCodec} 转义换行**；</li>
 *   <li>{@code event: done} —— 正常结束，携带分片数与耗时；</li>
 *   <li>{@code event: error} —— 异常结束，携带可展示的提示。</li>
 * </ul>
 *
 * <p><b>依赖注记</b>：本类只依赖 {@code spring-web} 的
 * {@link ServerSentEvent} 与 reactor 的 {@link Flux}，**不需要 WebFlux**。
 * 依据：知识库《大模型 API 调用工程实践》第 106 行——
 * WebMVC + Flux 只是出口用了响应式类型，底层仍是 Servlet 容器。
 */
@RestController
@RequestMapping("/api/chat")
@Validated   // 使 @RequestParam 上的 @NotBlank 生效（Spring 方法级校验）
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    /** 单次生成的硬上限，防止个别请求长期占用连接（知识库第 106 行"按长请求治理"）。 */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(120);

    private final ChatClient chatClient;

    public ChatStreamController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 流式问答。
     *
     * @param question  用户问题，不能为空
     * @param sessionId 会话标识，用于多轮上下文；缺省按匿名处理
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam @NotBlank String question,
            @RequestParam(required = false) String sessionId) {

        String sid = (sessionId == null || sessionId.isBlank()) ? "anonymous" : sessionId;
        long startNanos = System.nanoTime();
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicBoolean settled = new AtomicBoolean(false);

        log.debug("SSE 开始 session={} question={}", sid, question);

        Flux<ServerSentEvent<String>> meta = Flux.just(
                sse("meta", "{\"sessionId\":\"" + sid + "\"}"));

        Flux<ServerSentEvent<String>> body = chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .map(SseTextCodec::escape)
                .map(chunk -> {
                    chunkCount.incrementAndGet();
                    return sse("token", chunk);
                })
                // 知识库第 144 行：用户关页面 / 点停止 / 切会话都应触发取消。
                // 这里在三种终止路径上各留一个观测点，第 3 周接入 Trace 时
                // 分别补 ai.llm.generate 的 Span 状态属性。
                .doOnCancel(() -> log.info("SSE 被客户端取消 session={} 已发送 {} 片",
                        sid, chunkCount.get()))
                .doFinally(signal -> {
                    if (settled.compareAndSet(false, true)) {
                        log.info("SSE 结束 session={} signal={} 分片={} 耗时={}ms",
                                sid, signal, chunkCount.get(),
                                (System.nanoTime() - startNanos) / 1_000_000);
                    }
                });

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> Flux.just(
                sse("done", "{\"chunks\":" + chunkCount.get()
                        + ",\"elapsedMs\":" + ((System.nanoTime() - startNanos) / 1_000_000) + "}")));

        // 上游任何异常都转成一个 error 事件，而不是让连接裸断——
        // 裸断时前端无法区分"正常结束"与"崩了"。
        return Flux.concat(meta, body, done)
                .onErrorResume(ex -> {
                    log.warn("SSE 会话异常 session={}", sid, ex);
                    return Flux.just(sse("error",
                            "{\"message\":\"生成失败，请稍后重试\",\"code\":\"GENERATION_FAILED\"}"));
                })
                .timeout(STREAM_TIMEOUT);
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder()
                .event(event)
                .data(data)
                .build();
    }
}
