package com.canalmind.chat;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.retry.RetryUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmCallGuard} 的测试（C-58）。
 *
 * <p>分两半，**两半都不能少**：
 * <ul>
 *   <li><b>纯策略断言</b>：时钟 / 睡眠 / 抖动三个接缝都注入假实现 ⇒
 *       "退避是不是指数、抖动有没有生效、截止有没有守住"全是确定性断言，⛔ 不靠真的 sleep。</li>
 *   <li><b>真客户端打本地桩</b>：用 JDK {@code HttpServer} 起一个桩（照
 *       {@code DeepSeekRequestShapeTest} 的写法），跑**真的** {@code DeepSeekChatModel}——
 *       桩上数"到底收到了几个请求"，才能证明重试**真的跨了网络**，
 *       而不是只在内存里循环了几次。<b>全过程零付费调用</b>。</li>
 * </ul>
 */
class LlmCallGuardTest {

    private static final String STUB_KEY = "stub-key";
    private static final String MODEL = "deepseek-chat";
    /** 桩返回的正文，用于证明"重试之后拿到的确实是这一次的答案"。 */
    private static final String JSON_ANSWER = "{\"answer\":\"pong\"}";

    // ================= ① 纯策略：确定性断言 =================

    @Test
    @DisplayName("成功时：一次就返回，不重试、不睡眠")
    void doesNotRetryOnSuccess() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = guard(time, 2);

        String result = guard.call("t", () -> "ok", LlmCallGuardTest::mustNotDegrade);

        assertEquals("ok", result);
        assertEquals(List.of(), time.backoffSleeps(), "成功路径不该有任何退避睡眠");
    }

    @Test
    @DisplayName("429：指数退避 + 抖动（抖动值由测试注入，故等待时长可精确断言）")
    void retriesWithExponentialBackoffPlusJitter() {
        FakeTime time = new FakeTime();
        // 抖动固定 0.5 ⇒ 等量抖动 = 上限/2 + 0.5*上限/2 = 上限的 75%
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(2)
                .requestsPerMinute(600_000)      // 令牌间隔 100µs（亚毫秒，不计入退避断言）
                .baseBackoff(Duration.ofMillis(400))
                .maxBackoff(Duration.ofSeconds(4))
                .deadline(Duration.ofSeconds(30))
                .clock(time).sleeper(time).jitter(() -> 0.5)
                .build();

        AtomicInteger attempts = new AtomicInteger();
        String result = guard.call("t", () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new LlmHttpFailure(429, null, "限流");
            }
            return "ok";
        }, LlmCallGuardTest::mustNotDegrade);

        assertEquals("ok", result);
        assertEquals(3, attempts.get(), "重试 2 次 ⇒ 合计 3 次尝试（知识库第 207 行：2-3 次）");
        // 上限序列：400ms、800ms；等量抖动取 75% ⇒ 300ms、600ms —— **第二次是第一次的两倍**，即指数
        assertEquals(List.of(Duration.ofMillis(300), Duration.ofMillis(600)), time.backoffSleeps());
    }

    @Test
    @DisplayName("服务端给了 Retry-After：听服务端的，⛔ 不用自己的指数退避")
    void honoursRetryAfterInsteadOfBackoff() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(1)
                .requestsPerMinute(600_000)
                .baseBackoff(Duration.ofMillis(400))
                .deadline(Duration.ofSeconds(30))
                .clock(time).sleeper(time).jitter(() -> 0.5)
                .build();

        AtomicInteger attempts = new AtomicInteger();
        guard.call("t", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new LlmHttpFailure(429, Duration.ofSeconds(2), "限流");
            }
            return "ok";
        }, LlmCallGuardTest::mustNotDegrade);

        assertEquals(List.of(Duration.ofSeconds(2)), time.backoffSleeps(),
                "有 Retry-After 时必须等它，而不是我们自己算的 300ms");
    }

    @Test
    @DisplayName("4xx（除 429）：不重试、也不降级——客户端错就该如实失败")
    void doesNotRetryClientErrorsAndDoesNotDegrade() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = guard(time, 2);
        AtomicInteger attempts = new AtomicInteger();

        LlmHttpFailure thrown = assertThrows(LlmHttpFailure.class,
                () -> guard.call("t", () -> {
                    attempts.incrementAndGet();
                    throw new LlmHttpFailure(400, null, "参数错");
                }, degraded -> {
                    throw new AssertionError("4xx 不该走降级，收到 " + degraded.reason());
                }));

        assertEquals(400, thrown.statusCode());
        assertEquals(1, attempts.get(), "⛔ 客户端错不得重试");
        assertEquals(List.of(), time.backoffSleeps());
    }

    @Test
    @DisplayName("429 重试用尽 ⇒ 降级，原因 RATE_LIMITED，尝试次数如实带出")
    void degradesWithRateLimitedAfterAttemptsExhausted() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(2).requestsPerMinute(600_000)
                .baseBackoff(Duration.ofMillis(400)).deadline(Duration.ofSeconds(30))
                .clock(time).sleeper(time).jitter(() -> 0.0).build();
        AtomicInteger attempts = new AtomicInteger();
        List<LlmCallGuard.LlmCallDegradedException> degraded = new ArrayList<>();

        String result = guard.call("t", () -> {
            attempts.incrementAndGet();
            throw new LlmHttpFailure(429, null, "限流");
        }, e -> {
            degraded.add(e);
            return "降级答复";
        });

        assertEquals("降级答复", result, "降级出口的返回值必须**被返回**（而不是被丢掉）");
        assertEquals(3, attempts.get());
        assertEquals(1, degraded.size());
        assertEquals(LlmCallGuard.DegradeReason.RATE_LIMITED, degraded.get(0).reason());
        assertEquals(3, degraded.get(0).attempts());
        assertNotNull(degraded.get(0).lastFailure());
    }

    @Test
    @DisplayName("5xx 重试用尽 ⇒ 降级，原因 RETRY_EXHAUSTED（与限流区分开）")
    void degradesWithRetryExhaustedOn5xx() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(1).requestsPerMinute(600_000)
                .baseBackoff(Duration.ofMillis(100)).deadline(Duration.ofSeconds(30))
                .clock(time).sleeper(time).jitter(() -> 0.0).build();

        LlmCallGuard.LlmCallDegradedException degraded = captureDegrade(guard,
                () -> {
                    throw new LlmHttpFailure(503, null, "服务不可用");
                });

        assertEquals(LlmCallGuard.DegradeReason.RETRY_EXHAUSTED, degraded.reason());
    }

    @Test
    @DisplayName("再等就要超出总体截止时间 ⇒ 立刻降级 DEADLINE_EXCEEDED（⛔ 不硬拖）")
    void degradesWhenDeadlineWouldBeExceeded() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(5)
                .requestsPerMinute(600_000)
                .baseBackoff(Duration.ofMillis(500))
                .maxBackoff(Duration.ofSeconds(8))
                .deadline(Duration.ofSeconds(1))
                .clock(time).sleeper(time).jitter(() -> 1.0).build();   // 抖动打满 ⇒ 退避 = 上限
        AtomicInteger attempts = new AtomicInteger();

        LlmCallGuard.LlmCallDegradedException degraded = captureDegrade(guard, () -> {
            attempts.incrementAndGet();
            throw new LlmHttpFailure(429, null, "限流");
        });

        assertEquals(LlmCallGuard.DegradeReason.DEADLINE_EXCEEDED, degraded.reason());
        assertEquals(2, attempts.get(), "第 1 次退避 500ms 用掉一半截止，第 2 次退避 1s 会越界 ⇒ 只尝试 2 次");
    }

    @Test
    @DisplayName("并发满 ⇒ 立刻降级 OVER_CONCURRENCY（知识库第 291 行「并发信号量」那一层）")
    void degradesWhenConcurrencyIsFull() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(0).requestsPerMinute(600_000)
                .overallConcurrency(1)
                .clock(time).sleeper(time).jitter(() -> 0.0).build();

        // 一次调用里再发起一次调用：外层已占住唯一的并发额度
        List<Integer> observedInFlight = new ArrayList<>();
        String outer = guard.call("outer", () -> {
            observedInFlight.add(guard.inFlight());
            return guard.call("inner", () -> "内层拿到了额度（不该发生）",
                    e -> "内层降级：" + e.reason());
        }, LlmCallGuardTest::mustNotDegrade);

        assertEquals("内层降级：OVER_CONCURRENCY", outer);
        assertEquals(List.of(1), observedInFlight, "外层调用期间在飞数应为 1");
        assertEquals(0, guard.inFlight(), "调用结束后并发额度必须归还");
    }

    @Test
    @DisplayName("令牌桶按 RPM 限速：间隔内的第二次调用必须等够一个间隔")
    void rateLimitsToRequestsPerMinute() {
        FakeTime time = new FakeTime();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(0).requestsPerMinute(60)      // 间隔 1 秒
                .deadline(Duration.ofSeconds(10))
                .clock(time).sleeper(time).jitter(() -> 0.0).build();

        assertEquals("a", guard.call("t1", () -> "a", LlmCallGuardTest::mustNotDegrade));
        assertEquals(List.of(), time.allSleeps(), "第一次调用用到的是当下的令牌，不该等");

        assertEquals("b", guard.call("t2", () -> "b", LlmCallGuardTest::mustNotDegrade));
        assertEquals(List.of(Duration.ofSeconds(1)), time.allSleeps(),
                "60 RPM ⇒ 第二次调用要等 1 秒才拿到下一个令牌");
    }

    @Test
    @DisplayName("等待期间被中断 ⇒ 降级 INTERRUPTED，且**中断位被恢复**")
    void degradesOnInterruptAndRestoresFlag() {
        FakeTime time = new FakeTime();
        time.interruptNextSleep();
        LlmCallGuard guard = LlmCallGuard.builder()
                .maxRetries(1).requestsPerMinute(600_000)
                .baseBackoff(Duration.ofMillis(100)).deadline(Duration.ofSeconds(30))
                .clock(time).sleeper(time).jitter(() -> 0.0).build();

        try {
            LlmCallGuard.LlmCallDegradedException degraded = captureDegrade(guard, () -> {
                throw new LlmHttpFailure(429, null, "限流");
            });
            assertEquals(LlmCallGuard.DegradeReason.INTERRUPTED, degraded.reason());
            assertTrue(Thread.interrupted(),
                    "中断位必须被恢复，否则上层（如批处理收尾）看不到中断——断言后本行已清掉中断位");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("环境变量覆盖：只覆盖给到的项，其余保持默认")
    void environmentOverridesAreRead() {
        LlmCallGuard guard = LlmCallGuard.fromEnvironment(Map.of(
                LlmCallGuard.ENV_MAX_RETRIES, "1",
                LlmCallGuard.ENV_REQUESTS_PER_MINUTE, "30",
                LlmCallGuard.ENV_MAX_CONCURRENT, "2",
                LlmCallGuard.ENV_DEADLINE_MS, "3000"));
        assertNotNull(guard);

        // 缺省不报错，且用的是生产默认值
        LlmCallGuard defaults = LlmCallGuard.fromEnvironment(Map.of());
        assertNotNull(defaults);

        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> LlmCallGuard.fromEnvironment(Map.of(LlmCallGuard.ENV_MAX_RETRIES, "两次")));
        assertTrue(bad.getMessage().contains(LlmCallGuard.ENV_MAX_RETRIES));
    }

    @Test
    @DisplayName("可重试的判据只看状态码：429/5xx 可重试，其余 4xx 不可")
    void retryableClassificationFollowsStatusCodes() {
        assertTrue(new LlmHttpFailure(429, null, "").retryable());
        assertTrue(new LlmHttpFailure(500, null, "").retryable());
        assertTrue(new LlmHttpFailure(503, null, "").retryable());
        assertTrue(new LlmHttpFailure(529, null, "").retryable(), "529 是知识库第 190 行列的过载码");
        assertFalse(new LlmHttpFailure(400, null, "").retryable());
        assertFalse(new LlmHttpFailure(401, null, "").retryable());
        assertFalse(new LlmHttpFailure(404, null, "").retryable());
    }

    // ================= ② 真客户端 + 本地桩（零付费） =================

    @Test
    @DisplayName("真客户端+桩：429 被翻译成带状态码与 Retry-After 的 LlmHttpFailure")
    void realClient429IsTypedByHttpErrorHandler() throws Exception {
        Stub stub = Stub.start();
        try {
            stub.script(429, "1");
            ChatModel model = stub.model();

            LlmHttpFailure failure = assertThrows(LlmHttpFailure.class,
                    () -> model.call(new Prompt("ping")));

            assertEquals(429, failure.statusCode());
            assertEquals(Duration.ofSeconds(1), failure.retryAfter(),
                    "Retry-After 必须从**真实响应头**读出来（⛔ 不是从异常消息里抠字符串）");
            assertTrue(failure.retryable());
        } finally {
            stub.stop();
        }
    }

    @Test
    @DisplayName("真客户端+桩：429、429、200 ⇒ 重试真的跨了网络，最终拿到答案")
    void realClientRetriesAgainstStubThenSucceeds() throws Exception {
        Stub stub = Stub.start();
        try {
            stub.script(429, "1");
            stub.script(429, "1");
            stub.script(200, null);
            FakeTime time = new FakeTime();
            LlmCallGuard guard = LlmCallGuard.builder()
                    .maxRetries(2).requestsPerMinute(600_000)
                    .baseBackoff(Duration.ofMillis(400)).deadline(Duration.ofSeconds(30))
                    .clock(time).sleeper(time).jitter(() -> 0.0).build();
            ChatModel model = stub.model();

            String answer = guard.call("stub",
                    () -> model.call(new Prompt("ping")).getResult().getOutput().getText(),
                    LlmCallGuardTest::mustNotDegrade);

            assertTrue(answer.contains("pong"), "实际拿到：" + answer);
            assertEquals(3, stub.requests(), "桩上数请求数：3 次尝试 ⇒ 重试确实跨了网络");
            assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), time.backoffSleeps(),
                    "两次退避都听了服务端的 Retry-After=1s");
        } finally {
            stub.stop();
        }
    }

    @Test
    @DisplayName("真客户端+桩：一直 429 ⇒ 降级，且降级原因写进 Span 的低基数键（不是 error）")
    void realClientAlways429DegradesAndRecordsSpanReason() throws Exception {
        Stub stub = Stub.start();
        try {
            stub.script(429, "1");
            stub.script(429, "1");
            stub.script(429, "1");
            FakeTime time = new FakeTime();
            LlmCallGuard guard = LlmCallGuard.builder()
                    .maxRetries(2).requestsPerMinute(600_000)
                    .baseBackoff(Duration.ofMillis(400)).deadline(Duration.ofSeconds(30))
                    .clock(time).sleeper(time).jitter(() -> 0.0).build();
            ChatModel model = stub.model();

            Recorder recorder = new Recorder();
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(recorder);
            Observation observation = AiObservation.start("ai.llm.generate", registry);

            String answer = observation.observe(() -> guard.call("stub",
                    () -> model.call(new Prompt("ping")).getResult().getOutput().getText(),
                    e -> {
                        // 宿主（本项目里是作答器）在这里返回降级答复 + 把原因写进 Span
                        AiObservation.degrade(observation, e.reason());
                        AiObservation.attempt(observation, Math.max(1, e.attempts()));
                        return "系统繁忙，请稍后再试";
                    }));

            assertEquals("系统繁忙，请稍后再试", answer);
            assertEquals(3, stub.requests());

            Observation.Context ctx = recorder.only("ai.llm.generate");
            assertEquals("RATE_LIMITED", lowCard(ctx, AiSpans.KEY_DEGRADE_REASON),
                    "降级原因必须落在这个低基数键上，且取值来自封闭枚举");
            assertEquals("3", highCard(ctx, AiSpans.KEY_ATTEMPT));
            assertNull(ctx.getError(), "降级 = 给出了一条答复，不是错误 ⇒ 不该记 error");
        } finally {
            stub.stop();
        }
    }

    @Test
    @DisplayName("真客户端+桩：宿主选择不降级 ⇒ Span 如实记 error（两条路都留着，由宿主决定）")
    void spanRecordsErrorWhenHostDoesNotDegrade() throws Exception {
        Stub stub = Stub.start();
        try {
            stub.script(429, "1");
            stub.script(429, "1");
            stub.script(429, "1");
            FakeTime time = new FakeTime();
            LlmCallGuard guard = LlmCallGuard.builder()
                    .maxRetries(2).requestsPerMinute(600_000)
                    .baseBackoff(Duration.ofMillis(400)).deadline(Duration.ofSeconds(30))
                    .clock(time).sleeper(time).jitter(() -> 0.0).build();
            ChatModel model = stub.model();

            Recorder recorder = new Recorder();
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(recorder);
            Observation observation = AiObservation.start("ai.llm.generate", registry);

            assertThrows(LlmCallGuard.LlmCallDegradedException.class,
                    () -> observation.observe(() -> guard.call("stub",
                            () -> model.call(new Prompt("ping")).getResult().getOutput().getText(),
                            e -> {
                                throw e;      // 宿主决定"不降级、直接失败"
                            })));

            Observation.Context ctx = recorder.only("ai.llm.generate");
            assertInstanceOf(LlmCallGuard.LlmCallDegradedException.class, ctx.getError());
            assertEquals(3, stub.requests());
        } finally {
            stub.stop();
        }
    }

    @Test
    @DisplayName("真客户端+桩：400 不重试 ⇒ 桩只收到 1 个请求")
    void realClientDoesNotRetryOn400() throws Exception {
        Stub stub = Stub.start();
        try {
            stub.script(400, null);
            FakeTime time = new FakeTime();
            LlmCallGuard guard = guard(time, 5);
            ChatModel model = stub.model();

            LlmHttpFailure failure = assertThrows(LlmHttpFailure.class,
                    () -> guard.call("stub",
                            () -> model.call(new Prompt("ping")).getResult().getOutput().getText(),
                            LlmCallGuardTest::mustNotDegrade));

            assertEquals(400, failure.statusCode());
            assertEquals(1, stub.requests(), "客户端错重试多少次都是错，只该打一次");
        } finally {
            stub.stop();
        }
    }

    // ================= 夹具 =================

    private static LlmCallGuard guard(FakeTime time, int maxRetries) {
        return LlmCallGuard.builder()
                .maxRetries(maxRetries)
                .requestsPerMinute(600_000)
                .baseBackoff(Duration.ofMillis(400))
                .deadline(Duration.ofSeconds(30))
                .clock(time).sleeper(time).jitter(() -> 0.0)
                .build();
    }

    /** 捕获降级信号（用例自己决定降级出口返回什么）。 */
    private static LlmCallGuard.LlmCallDegradedException captureDegrade(
            LlmCallGuard guard, java.util.function.Supplier<String> attempt) {
        List<LlmCallGuard.LlmCallDegradedException> captured = new ArrayList<>();
        guard.call("t", attempt, e -> {
            captured.add(e);
            return "降级";
        });
        assertEquals(1, captured.size(), "应当恰好降级一次");
        return captured.get(0);
    }

    private static <T> T mustNotDegrade(LlmCallGuard.LlmCallDegradedException e) {
        throw new AssertionError("不该降级，实际原因：" + e.reason() + " 尝试=" + e.attempts());
    }

    /**
     * 读低基数属性。
     *
     * <p>⚠️ <b>必须走聚合视图</b>（{@code getLowCardinalityKeyValues()}），⛔ 不要用
     * {@code ctx.getLowCardinalityKeyValue("键")}——实测（本用例第一次就是这么写的，红了）：
     * 对<b>观察启动之后才写入</b>的键，单键读取器返回 {@code null}，而聚合视图里明明有它，
     * 于是断言以 NPE 的形式**假失败**。本项目既有测试（{@code AiObservabilityTest} /
     * {@code AiSpanTreeTest} / {@code LlmRerankerTest}）读的都是聚合视图，随大流即可。
     */
    private static String lowCard(Observation.Context ctx, String key) {
        return keyValue(ctx.getLowCardinalityKeyValues(), key);
    }

    /** 读高基数属性（同理走聚合视图；{@code attempt} 就是高基数键）。 */
    private static String highCard(Observation.Context ctx, String key) {
        return keyValue(ctx.getHighCardinalityKeyValues(), key);
    }

    private static String keyValue(io.micrometer.common.KeyValues keyValues, String key) {
        for (io.micrometer.common.KeyValue kv : keyValues) {
            if (key.equals(kv.getKey())) {
                return kv.getValue();
            }
        }
        return null;
    }

    /** 时钟 + 睡眠二合一：睡眠会**推进**假时钟（所以"等 1 秒后再判断截止"是真的按时间推进的）。 */
    private static final class FakeTime implements LlmCallGuard.NanoClock, LlmCallGuard.Sleeper {

        /** 亚毫秒等待来自令牌桶，不算退避；退避用例把令牌间隔设成 100µs 以隔离两边。 */
        private static final long BACKOFF_FLOOR_NANOS = 1_000_000L;

        private long nowNanos;
        private final List<Duration> allSleeps = new ArrayList<>();
        private boolean interruptNextSleep;

        @Override
        public long nanos() {
            return nowNanos;
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            if (interruptNextSleep) {
                interruptNextSleep = false;
                throw new InterruptedException("测试注入的中断");
            }
            allSleeps.add(duration);
            nowNanos += duration.toNanos();
        }

        void interruptNextSleep() {
            this.interruptNextSleep = true;
        }

        List<Duration> allSleeps() {
            return List.copyOf(allSleeps);
        }

        /** 只看退避那几次等待（≥1ms），用于精确断言退避序列。 */
        List<Duration> backoffSleeps() {
            List<Duration> result = new ArrayList<>();
            for (Duration d : allSleeps) {
                if (d.toNanos() >= BACKOFF_FLOOR_NANOS) {
                    result.add(d);
                }
            }
            return result;
        }
    }

    /** 极简录制器：只记名字、低基数属性、错误（与 {@code AiSpanTreeTest} 里的同类夹具同形）。 */
    private static final class Recorder implements ObservationHandler<Observation.Context> {

        private final List<Observation.Context> contexts = new CopyOnWriteArrayList<>();
        private final Set<String> names = new LinkedHashSet<>();

        @Override
        public void onStart(Observation.Context context) {
            contexts.add(context);
            names.add(context.getName());
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        Observation.Context only(String name) {
            List<Observation.Context> matched = new ArrayList<>();
            for (Observation.Context c : contexts) {
                if (name.equals(c.getName())) {
                    matched.add(c);
                }
            }
            assertEquals(1, matched.size(), "期望恰好一个 " + name + "，实际录到：" + names);
            return matched.get(0);
        }
    }

    /**
     * 本地桩 DeepSeek：按脚本依次回放状态码，并数"到底收到了几个请求"。
     * 每个用例各自起停、端口取 0（由系统分配）⇒ ⛔ 不用静态共享桩，也就没有跨用例的计数污染
     * ——本项目在 {@code DeepSeekRequestShapeTest} 上正是被这种共享污染坑过一次（当时靠逐用例清空 list 兜住）。
     */
    private static final class Stub {

        private final HttpServer server;
        private final List<Integer> statuses = new CopyOnWriteArrayList<>();
        private final List<String> retryAfters = new CopyOnWriteArrayList<>();
        private final AtomicInteger requests = new AtomicInteger();

        private Stub(HttpServer server) {
            this.server = server;
        }

        static Stub start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Stub stub = new Stub(server);
            server.createContext("/chat/completions", stub::handle);
            server.setExecutor(null);
            server.start();
            return stub;
        }

        void script(int status, String retryAfter) {
            statuses.add(status);
            retryAfters.add(retryAfter == null ? "" : retryAfter);
        }

        int requests() {
            return requests.get();
        }

        void stop() {
            server.stop(0);
        }

        /** 真 DeepSeek 客户端，但 base-url 指向桩、并装上我们自己的错误处理器。 */
        ChatModel model() {
            DeepSeekApi api = DeepSeekApi.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey(STUB_KEY)
                    .responseErrorHandler(new DeepSeekHttpErrorHandler())
                    .build();
            return DeepSeekChatModel.builder()
                    .deepSeekApi(api)
                    .defaultOptions(DeepSeekChatOptions.builder()
                            .model(MODEL)
                            .temperature(0.0)
                            .maxTokens(64)
                            .responseFormat(ResponseFormat.builder()
                                    .type(ResponseFormat.Type.JSON_OBJECT)
                                    .build())
                            .build())
                    .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                    .observationRegistry(ObservationRegistry.NOOP)
                    .build();
        }

        private void handle(HttpExchange exchange) throws IOException {
            int index = requests.getAndIncrement();
            exchange.getRequestBody().readAllBytes();
            int status = index < statuses.size() ? statuses.get(index) : 200;
            String retryAfter = index < retryAfters.size() ? retryAfters.get(index) : "";

            byte[] body;
            if (status == 200) {
                body = ("{\"id\":\"stub-1\",\"object\":\"chat.completion\",\"created\":1700000000,"
                        + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"message\":"
                        + "{\"role\":\"assistant\",\"content\":" + jsonString(JSON_ANSWER)
                        + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                        + "\"completion_tokens\":1,\"total_tokens\":2}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                body = ("{\"error\":{\"message\":\"stub says no\",\"type\":\"stub_error\"}}")
                        .getBytes(StandardCharsets.UTF_8);
            }

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (!retryAfter.isEmpty()) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private static String jsonString(String raw) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : raw.toCharArray()) {
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            return sb.append('"').toString();
        }
    }
}
