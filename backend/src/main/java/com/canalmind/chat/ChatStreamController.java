package com.canalmind.chat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 流式问答接口（SSE）。
 *
 * <p>事件约定（**服务端与前端必须固定同一语义**）：
 * <ul>
 *   <li>{@code event: meta} —— 会话元信息，前端据此准备渲染；</li>
 *   <li>{@code event: structured} —— **结构化帧**（单行 JSON）：路由结论、
 *       结构化通道的五态结果、引用来源。**有才发，没有就不发**；</li>
 *   <li>{@code event: token} —— 增量正文，**已按 {@link SseTextCodec} 转义换行**；</li>
 *   <li>{@code event: done} —— 正常结束，携带分片数与耗时；</li>
 *   <li>{@code event: error} —— 异常结束，携带可展示的提示。</li>
 * </ul>
 *
 * <p><b>关于 {@code structured} 帧的两条约定</b>：
 * <ol>
 *   <li><b>一次发完，⛔ 不做增量</b>：依据知识库《大模型 API 调用工程实践》
 *       第 448–450 行——"不要一边收到 delta 一边 {@code JSON.parse()} 完整对象"，
 *       故整帧在正文开始**之前**一次性下发。前端因此可以先渲染结果卡片、
 *       再逐字显示正文（本项目前端**刻意不用打字机效果**，见前端页面说明）；</li>
 *   <li><b>失败不影响问答</b>：帧是增强信息。装配失败（库/Ollama 未就绪、模型不可用）
 *       只会让这一帧缺席，**绝不会**把正常问答变成 {@code error} 事件。
 *       ⛔ 也不要用"空帧"表达失败——没有该事件就是"没有"，前端据此少一个分支。</li>
 * </ol>
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
    /** 结构化帧来源；容器里没有实现时为 {@link ChatFrameSource#NONE}（= 不发该事件）。 */
    private final ChatFrameSource frameSource;

    /**
     * ⚠️ <b>既有签名，保持不变</b>：等价于"没有帧源"，
     * 出站事件集合与本类引入 structured 帧之前**逐字节一致**。
     * 保留它的目的是让既有测试与任何直接构造本类的代码继续原样工作。
     */
    public ChatStreamController(ChatClient chatClient) {
        this(chatClient, ChatFrameSource.NONE);
    }

    /**
     * Spring 使用的构造器。
     *
     * <p>⚠️ 用 {@link ObjectProvider} 而不是直接注入 {@code ChatFrameSource}：
     * 帧源是**可选**的（默认配置下容器里就没有），直接注入会让应用在
     * 未开启该功能时启动失败——那是把"可选增强"做成了"必需依赖"。
     */
    @Autowired
    public ChatStreamController(ChatClient chatClient, ObjectProvider<ChatFrameSource> frameSource) {
        this(chatClient, frameSource.getIfAvailable(() -> ChatFrameSource.NONE));
    }

    private ChatStreamController(ChatClient chatClient, ChatFrameSource frameSource) {
        this.chatClient = chatClient;
        this.frameSource = frameSource;
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

        // ── 结构化帧 ──
        // ⚠️ 帧的装配是**阻塞**的（要查库、要调模型），故显式挪到 boundedElastic 上执行：
        //    ⛔ 不让阻塞调用跑在事件循环/请求线程上的响应式链里。
        // ⚠️ 帧装配的异常在这里**吞掉**（onErrorReturn 空 ⇒ 不发该事件）：
        //    主链路的失败语义必须与改动前一致，不能因为"锦上添花"多出一种 error 路径。
        Flux<ServerSentEvent<String>> structured = Flux.defer(() ->
                Mono.fromCallable(() -> frameSource.frameFor(question, sid))
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnError(ex -> log.warn("结构化帧获取失败，已跳过 session={}", sid, ex))
                        .onErrorReturn(Optional.<String>empty())
                        .flatMapMany(frame -> frame
                                .map(json -> Flux.just(sse("structured", json)))
                                .orElseGet(Flux::empty)));

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
        return Flux.concat(meta, structured, body, done)
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
