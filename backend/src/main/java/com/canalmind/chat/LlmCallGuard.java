package com.canalmind.chat;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 模型调用的**主动限流 + 退避重试 + 穷尽后降级**（**C-58**，2026-09-22，最小可验证版）。
 *
 * <h2>为什么需要它（可引用知识库）</h2>
 *
 * <ul>
 *   <li><b>主动限流</b>：知识库《大模型 API 调用工程实践…》第 247–249 行——"很多团队的限流意识，
 *       是从收到第一个 429 开始的，<b>这已经晚了</b>；供应商的 429 是最后一道墙"。
 *       第 289 行给出模型级的写法：<b>令牌桶 + 并发信号量</b>（本类就按这两样做）。</li>
 *   <li><b>退避与抖动</b>：第 191 行（rate limit error 用**随机指数退避**）、第 195 行（第 1 次等一小会儿、
 *       第 2 次更久）、第 197 行（**抖动**：不要让所有请求在同一时刻一起重试，否则刚恢复就被同一批重试打爆）。</li>
 *   <li><b>次数与截止</b>：第 207–208 行——最多 **2–3 次**、并设**总体截止时间**（"不要因为重试拖成 1 分钟"）。</li>
 *   <li><b>穷尽之后</b>：《大模型网关详解…》第 228 行给的降级顺序最后一步就是
 *       "仍不可用 ⇒ <b>排队、返回降级提示</b>或转人工"；第 231 行要求**每次 attempt 都留下记录**。</li>
 * </ul>
 *
 * <h2>⚠️ 本类<b>不</b>做重试的那部分（如实划界）</h2>
 *
 * <p>只重试**能分类**的失败，即 {@link LlmHttpFailure}（HTTP 429 / 5xx，由
 * {@link DeepSeekHttpErrorHandler} 在 HTTP 层给出）。其余异常**一律原样抛出**：
 * 未知异常重试多少次都只是白烧配额（知识库第 19 行把"哪些能重试"列为重试设计的第一件事）。
 * 其余 4xx <b>不重试</b>，且**不走降级**——它是客户端错，宿主应当如实报错（Span 记 error）。
 *
 * <h2>可测性</h2>
 *
 * <p>时钟、睡眠、抖动三个都从构造器注入（{@code NanoClock} / {@code Sleeper} / {@code Jitter}），
 * 所以"退避序列是不是指数、抖动有没有生效、截止时间有没有守住"全部可以**确定性断言**，
 * ⛔ 不需要真的 sleep、也不会因为机器快慢而 flaky。
 */
public final class LlmCallGuard {

    /** 降级原因（**封闭枚举**，宿主把它写进 {@code ai.degrade.reason}）。 */
    public enum DegradeReason {
        /** 429（供应商限流）且重试次数已用尽。 */
        RATE_LIMITED,
        /** 5xx（服务端故障）且重试次数已用尽。 */
        RETRY_EXHAUSTED,
        /** 再等下去会超出总体截止时间——按知识库第 208 行"到点就失败"，⛔ 不硬拖。 */
        DEADLINE_EXCEEDED,
        /** 并发额度已满：直接拒绝并降级（知识库第 291 行"并发信号量"那一层的含义）。 */
        OVER_CONCURRENCY,
        /** 等待期间线程被中断：如实降级并**恢复中断位**（否则上层看不到中断）。 */
        INTERRUPTED
    }

    /** 睡眠接缝（测试注入假实现 ⇒ 断言等了多久，且不真的等）。 */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** 单调时钟接缝（⛔ 不用 {@code currentTimeMillis}：截止时间判断不该被对时影响）。 */
    public interface NanoClock {
        long nanos();
    }

    /** 抖动源接缝：返回 {@code [0,1)} 的一个数（测试注入固定值 ⇒ 退避序列可预测）。 */
    public interface Jitter {
        double nextFraction();
    }

    // ---- 生产默认值（都可由环境变量覆盖，见 fromEnvironment） ----
    /** 知识库第 207 行："通常 2-3 次足够，别无限重试" ⇒ 默认重试 2 次（合计最多 3 次尝试）。 */
    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final Duration DEFAULT_BASE_BACKOFF = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(4);
    /** 知识库第 208 行的例子就是 15 秒；本项目取 12 秒（比它更保守）。 */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(12);
    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final int DEFAULT_REQUESTS_PER_MINUTE = 60;

    public static final String ENV_MAX_RETRIES = "CANALMIND_LLM_MAX_RETRIES";
    public static final String ENV_MAX_CONCURRENT = "CANALMIND_LLM_MAX_CONCURRENT";
    public static final String ENV_REQUESTS_PER_MINUTE = "CANALMIND_LLM_REQUESTS_PER_MINUTE";
    public static final String ENV_DEADLINE_MS = "CANALMIND_LLM_DEADLINE_MS";
    public static final String ENV_BASE_BACKOFF_MS = "CANALMIND_LLM_BASE_BACKOFF_MS";

    private final int maxRetries;
    private final int requestsPerMinute;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration deadline;
    private final Sleeper sleeper;
    private final NanoClock clock;
    private final Jitter jitter;
    private final Semaphore permits;

    /** 预留式令牌桶的下一次可用时刻（单调时钟）。 */
    private final Object bucketLock = new Object();
    private long nextTokenNanos = Long.MIN_VALUE;

    private final AtomicInteger inFlight = new AtomicInteger();

    private LlmCallGuard(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.requestsPerMinute = builder.requestsPerMinute;
        this.baseBackoff = builder.baseBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.deadline = builder.deadline;
        this.sleeper = builder.sleeper;
        this.clock = builder.clock;
        this.jitter = builder.jitter;
        this.permits = new Semaphore(builder.maxConcurrent);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不得为负，实际：" + maxRetries);
        }
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("requestsPerMinute 必须 >= 1，实际：" + requestsPerMinute);
        }
        if (baseBackoff.isZero() || baseBackoff.isNegative() || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException(
                    "退避区间不合法：base=" + baseBackoff + " max=" + maxBackoff);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 从环境变量读参数（⛔ 缺省就是生产默认值；本方法**不**读密钥、不发起任何调用）。
     *
     * <p>与 {@code LlmRerankerFactory.fromEnvironment} 同一风格：CLI / 评测路径没有 Spring 容器，
     * {@code Map<String,String> env} 就是那条路径的配置入口。
     */
    public static LlmCallGuard fromEnvironment(Map<String, String> env) {
        Builder builder = builder();
        builder.maxRetries(intOf(env, ENV_MAX_RETRIES, DEFAULT_MAX_RETRIES));
        builder.requestsPerMinute(intOf(env, ENV_REQUESTS_PER_MINUTE, DEFAULT_REQUESTS_PER_MINUTE));
        builder.deadline(Duration.ofMillis(intOf(env, ENV_DEADLINE_MS,
                (int) DEFAULT_DEADLINE.toMillis())));
        builder.baseBackoff(Duration.ofMillis(intOf(env, ENV_BASE_BACKOFF_MS,
                (int) DEFAULT_BASE_BACKOFF.toMillis())));
        builder.overallConcurrency(intOf(env, ENV_MAX_CONCURRENT, DEFAULT_MAX_CONCURRENT));
        return builder.build();
    }

    /** 当前在飞的调用数（供宿主/测试观察并发信号量是否真的在限）。 */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * 执行一次受保护的模型调用。
     *
     * @param scene       场景名（只用于日志与降级原因的可读性，⛔ 不得是用户问句原文）
     * @param attempt     真正的调用（应当把 {@link LlmHttpFailure} 原样冒上来）
     * @param onDegraded  次数用尽 / 超时 / 并发满 / 被中断时的**降级出口**：
     *                    宿主在这里返回一条降级答复，并把原因写进 Span
     *                    （{@code AiObservation.degrade(observation, e.reason())}）。
     *                    ⚠️ 传一个<b>会抛异常</b>的出口也可以——那就等价于"不降级、直接失败"
     *                    （Span 会照常记 error）。
     * @return 正常结果，或 {@code onDegraded} 的返回
     * @throws LlmHttpFailure 不可重试的 HTTP 失败（如 401/400）——**不降级**，如实抛给宿主
     */
    public <T> T call(String scene, Supplier<T> attempt,
                      Function<LlmCallDegradedException, T> onDegraded) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(onDegraded, "onDegraded");

        long deadlineNanos = clock.nanos() + deadline.toNanos();
        if (!permits.tryAcquire()) {
            // 并发满了：立刻降级（⛔ 不排队等——排队会把上游的等待转嫁成我们自己的超时）。
            return onDegraded.apply(new LlmCallDegradedException(
                    scene, DegradeReason.OVER_CONCURRENCY, 0, null));
        }
        inFlight.incrementAndGet();
        try {
            int attemptNo = 0;
            while (true) {
                // ── ① 主动限流：每次尝试前都先等令牌 ──
                // ⚠️ 注意"重试也要吃令牌"：知识库第 191 行明说"失败请求也计入每分钟限制"，
                //    所以退避重试不能绕过限流桶，否则限流就是假的。
                long waitNanos = reserveWaitNanos(clock.nanos());
                if (waitNanos > 0) {
                    if (clock.nanos() + waitNanos > deadlineNanos) {
                        return onDegraded.apply(new LlmCallDegradedException(
                                scene, DegradeReason.DEADLINE_EXCEEDED, attemptNo, null));
                    }
                    if (!sleep(Duration.ofNanos(waitNanos))) {
                        return onDegraded.apply(new LlmCallDegradedException(
                                scene, DegradeReason.INTERRUPTED, attemptNo, null));
                    }
                }
                attemptNo++;
                try {
                    return attempt.get();
                } catch (LlmHttpFailure failure) {
                    if (!failure.retryable()) {
                        // ⛔ 4xx（除 429）不重试、也不降级：客户端错就该如实失败（宿主 Span 记 error）。
                        throw failure;
                    }
                    if (attemptNo > maxRetries) {
                        return onDegraded.apply(new LlmCallDegradedException(
                                scene, reasonOf(failure), attemptNo, failure));
                    }
                    long delay = backoffNanos(attemptNo, failure);
                    if (clock.nanos() + delay > deadlineNanos) {
                        return onDegraded.apply(new LlmCallDegradedException(
                                scene, DegradeReason.DEADLINE_EXCEEDED, attemptNo, failure));
                    }
                    warn(scene, attemptNo, delay, failure);
                    if (!sleep(Duration.ofNanos(delay))) {
                        return onDegraded.apply(new LlmCallDegradedException(
                                scene, DegradeReason.INTERRUPTED, attemptNo, failure));
                    }
                }
            }
        } finally {
            inFlight.decrementAndGet();
            permits.release();
        }
    }

    /**
     * 预留一个令牌，返回**需要等待多久**（0 = 立即可用）——预留式令牌桶：
     * 把"下一次可用时刻"往后推一个间隔，调用方再睡到那一刻。
     * ⚠️ 在锁内算完就出来，⛔ 不持有锁睡眠（否则会把限流器变成串行闸门）。
     */
    private long reserveWaitNanos(long nowNanos) {
        long waitUntil;
        synchronized (bucketLock) {
            long intervalNanos = 60_000_000_000L / requestsPerMinute;
            waitUntil = Math.max(nextTokenNanos, nowNanos);
            nextTokenNanos = waitUntil + intervalNanos;
        }
        return Math.max(0, waitUntil - nowNanos);
    }

    /**
     * 退避时长：服务端给了 {@code Retry-After} 就**听服务端的**；否则指数增长 + **等量抖动**。
     *
     * <p>等量抖动（{@code 上限/2 + random(0, 上限/2)}）而不是"全抖动"：知识库第 195 行要求
     * "第 1 次等<b>一小会儿</b>、第 2 次更久"，全抖动可能给出接近 0 的等待，那就等于没退避。
     */
    private long backoffNanos(int attemptNo, LlmHttpFailure failure) {
        if (failure.retryAfter() != null) {
            return failure.retryAfter().toNanos();
        }
        long exponential = baseBackoff.toNanos() << Math.min(attemptNo - 1, 16);
        long capped = Math.min(exponential, maxBackoff.toNanos());
        long half = capped / 2;
        return half + (long) (half * jitter.nextFraction());
    }

    /** @return {@code true} = 睡够了；{@code false} = 被中断（**中断位已恢复**，降级出口由调用方给） */
    private boolean sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return true;
        }
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static DegradeReason reasonOf(LlmHttpFailure failure) {
        return failure.statusCode() == 429 ? DegradeReason.RATE_LIMITED : DegradeReason.RETRY_EXHAUSTED;
    }

    private static void warn(String scene, int attemptNo, long delayNanos, LlmHttpFailure failure) {
        System.err.println("[WARN] 模型调用重试：场景=" + scene + " 第 " + attemptNo + " 次失败（"
                + failure + "）⇒ 等待 " + (delayNanos / 1_000_000) + " ms 后重试");
    }

    private static int intOf(Map<String, String> env, String key, int fallback) {
        if (env == null) {
            return fallback;
        }
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("环境变量 " + key + " 不是整数：" + raw);
        }
    }

    /** 降级信号：宿主据此返回降级答复并把原因写进 Span。 */
    public static final class LlmCallDegradedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String scene;
        private final DegradeReason reason;
        private final int attempts;
        private final LlmHttpFailure lastFailure;

        LlmCallDegradedException(String scene, DegradeReason reason, int attempts,
                                 LlmHttpFailure lastFailure) {
            super("模型调用降级：场景=" + scene + " 原因=" + reason + " 尝试=" + attempts
                    + (lastFailure == null ? "" : " 最后一次失败=" + lastFailure));
            this.scene = scene;
            this.reason = reason;
            this.attempts = attempts;
            this.lastFailure = lastFailure;
        }

        public String scene() {
            return scene;
        }

        public DegradeReason reason() {
            return reason;
        }

        /** 已经尝试过几次（含首次）——写进 Span 的 {@code ai.llm.attempt} 就是它。 */
        public int attempts() {
            return attempts;
        }

        /** 最后一次 HTTP 失败（并发满/超时这条路上可能为 {@code null}）。 */
        public LlmHttpFailure lastFailure() {
            return lastFailure;
        }
    }

    /** 构造器（默认值即生产值；测试只覆盖它关心的那几个）。 */
    public static final class Builder {

        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int requestsPerMinute = DEFAULT_REQUESTS_PER_MINUTE;
        private Duration baseBackoff = DEFAULT_BASE_BACKOFF;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private Duration deadline = DEFAULT_DEADLINE;
        private Sleeper sleeper = duration -> Thread.sleep(Math.max(0, duration.toMillis()));
        private NanoClock clock = System::nanoTime;
        private Jitter jitter = Math::random;
        private int maxConcurrent = DEFAULT_MAX_CONCURRENT;

        public Builder maxRetries(int value) {
            this.maxRetries = value;
            return this;
        }

        public Builder requestsPerMinute(int value) {
            this.requestsPerMinute = value;
            return this;
        }

        public Builder baseBackoff(Duration value) {
            this.baseBackoff = Objects.requireNonNull(value, "baseBackoff");
            return this;
        }

        public Builder maxBackoff(Duration value) {
            this.maxBackoff = Objects.requireNonNull(value, "maxBackoff");
            return this;
        }

        public Builder deadline(Duration value) {
            this.deadline = Objects.requireNonNull(value, "deadline");
            return this;
        }

        public Builder overallConcurrency(int value) {
            this.maxConcurrent = value;
            return this;
        }

        public Builder sleeper(Sleeper value) {
            this.sleeper = Objects.requireNonNull(value, "sleeper");
            return this;
        }

        public Builder clock(NanoClock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        public Builder jitter(Jitter value) {
            this.jitter = Objects.requireNonNull(value, "jitter");
            return this;
        }

        public LlmCallGuard build() {
            return new LlmCallGuard(this);
        }
    }
}
