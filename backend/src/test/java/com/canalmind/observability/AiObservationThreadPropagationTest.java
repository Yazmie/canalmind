package com.canalmind.observability;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线程时 Span 父指针的行为（**4 个场景全部实测**，2026-09-22）。
 *
 * <p>依据：知识库《AI 可观测性与 Trace…》第 306 行——"OpenTelemetry Java 上下文默认基于
 * {@code ThreadLocal}；提交给普通线程池后若没有自动插桩或显式包装，子线程看不到父上下文"；
 * 第 324 行——Spring AI 流式调用切到 {@code ForkJoinPool.commonPool()}，导致 HTTP Span
 * 没挂成模型 Span 的子节点（规格书 §7.5 第 585–588 行把它列为"必须知道的坑"）。
 *
 * <p>实测结论（4 个场景）：
 * <table border="1">
 *   <caption>跨线程时子 Span 的父指针</caption>
 *   <tr><th>场景</th><th>子的父</th></tr>
 *   <tr><td>① 裸线程池</td><td>⛔ {@code null}</td></tr>
 *   <tr><td>② 注册 accessor <b>且</b> {@code ContextExecutorService.wrap} 包装线程池</td><td>✅ {@code ai.agent.run}</td></tr>
 *   <tr><td>③ <b>只</b>注册 accessor、不包装线程池</td><td>⛔ {@code null}</td></tr>
 *   <tr><td>④ {@code ForkJoinPool.commonPool()}（SSE 的实际形态）</td><td>⛔ {@code null}</td></tr>
 * </table>
 *
 * <p>⚠️ 场景 ③ 是**关键反例**：很容易以为"注册了 {@code ThreadLocalAccessor} 就自动传播了"——
 * 实测**不是**，必须**显式包装执行器**。这正是本项目把它固化为测试的原因。
 *
 * <p>⚠️ 本项目现状：复合通道按 **C-37 串行**执行，业务编排里**没有**自建线程池，
 * 故跨线程丢父**目前不影响业务 Span**。但 **SSE 流式路径存在**（知识库第 324 行），
 * 且将来一旦并行化，**必须**用 {@code ContextExecutorService.wrap} 包装，否则调用链静默变平。
 */
@DisplayName("跨线程传播：父指针会静默丢失，且「只注册 accessor」救不回来")
class AiObservationThreadPropagationTest {

    /** ⚠️ 用哨兵表示"无父"，⛔ 不能往 `ConcurrentHashMap` 里放 `null`——它会直接抛 NPE（实测踩过）。 */
    private static final String NO_PARENT = "<无父>";

    /** 记录每个 Span 启动时的父名（在 worker 线程上写、主线程读，故用并发容器）。 */
    private final Map<String, String> parents = new ConcurrentHashMap<>();

    @AfterEach
    void releaseGlobalAccessor() {
        // ⚠️ ObservationThreadLocalAccessor 是**全局单例**，注册进 ContextRegistry.getInstance()
        //    会影响**整个测试 JVM**。本类用完必须注销，⛔ 不把全局状态留给别的测试——
        //    否则别的测试若是包装了执行器，就会意外拿到本类设过的 registry（假绿）。
        ContextRegistry.getInstance().removeThreadLocalAccessor(ObservationThreadLocalAccessor.KEY);
    }

    private ObservationRegistry newRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                parents.put(context.getName(),
                        context.getParentObservation() == null
                                ? NO_PARENT
                                : context.getParentObservation().getContextView().getName());
            }
        });
        return registry;
    }

    /** 把注册了本 registry 的 accessor 装进全局 ContextRegistry（场景 ②③④ 的前置）。 */
    private void installAccessorFor(ObservationRegistry registry) {
        ObservationThreadLocalAccessor accessor = ObservationThreadLocalAccessor.getInstance();
        accessor.setObservationRegistry(registry);
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
    }

    @Test
    @DisplayName("① 裸线程池：子的父指针为 null（ThreadLocal 不跨线程）")
    void plainExecutorLosesTheParent() throws Exception {
        ObservationRegistry registry = newRegistry();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
            root.start();
            try (Observation.Scope scope = root.openScope()) {
                pool.submit(() -> AiObservation.start(AiSpans.INTENT_ROUTE, registry)
                        .observe(() -> "路由")).get(5, TimeUnit.SECONDS);
            }
            root.stop();
        } finally {
            pool.shutdownNow();
        }

        // ⚠️ 必须先证明「Span 真的产生了」：否则「父为 null」会因为「压根没 Span」而**假绿**。
        assertTrue(parents.containsKey(AiSpans.INTENT_ROUTE),
                "子 Span 必须在 worker 线程上真的启动过——跨线程丢的只是**父指针**，不是 Span 本身");
        assertEquals(NO_PARENT, parents.get(AiSpans.INTENT_ROUTE),
                "⚠️ 这条断言断言的是**当前的事实**（跨线程丢父），不是我们想要的行为；"
                        + "若它变红说明 Micrometer/context-propagation 改了默认语义，请复核 README 已知限制第 9 条");
    }

    @Test
    @DisplayName("③ 只注册 accessor、不包装线程池 ⇒ 还是 null（「注册了就自动传播」是错的）")
    void registeringTheAccessorAloneIsNotEnough() throws Exception {
        ObservationRegistry registry = newRegistry();
        installAccessorFor(registry);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
            root.start();
            try (Observation.Scope scope = root.openScope()) {
                pool.submit(() -> AiObservation.start(AiSpans.INTENT_ROUTE, registry)
                        .observe(() -> "路由")).get(5, TimeUnit.SECONDS);
            }
            root.stop();
        } finally {
            pool.shutdownNow();
        }

        assertTrue(parents.containsKey(AiSpans.INTENT_ROUTE),
                "同样先证明 Span 产生了，再断言它丢了父——否则这条测试会因为「什么都没跑」而假绿");
        assertEquals(NO_PARENT, parents.get(AiSpans.INTENT_ROUTE),
                "只注册 ThreadLocalAccessor 不会自动传播——必须显式 ContextExecutorService.wrap，"
                        + "这条断言就是防止有人误以为「注册即传播」");
    }

    @Test
    @DisplayName("② 包装线程池 ⇒ 父指针保住（可行的修法）")
    void wrappingTheExecutorPreservesTheParent() throws Exception {
        ObservationRegistry registry = newRegistry();
        installAccessorFor(registry);
        ExecutorService raw = Executors.newSingleThreadExecutor();
        ExecutorService wrapped = ContextExecutorService.wrap(raw);
        try {
            Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
            root.start();
            try (Observation.Scope scope = root.openScope()) {
                wrapped.submit(() -> AiObservation.start(AiSpans.INTENT_ROUTE, registry)
                        .observe(() -> "路由")).get(5, TimeUnit.SECONDS);
            }
            root.stop();
        } finally {
            wrapped.shutdownNow();
        }

        assertEquals(AiSpans.AGENT_RUN, parents.get(AiSpans.INTENT_ROUTE),
                "❗ 这是「跨线程也要保住调用链」的唯一可行做法：注册 accessor + 包装执行器，两者缺一不可");
    }

    @Test
    @DisplayName("④ ForkJoinPool.commonPool()（SSE 的实际形态）：同样丢父——与知识库第 324 行记的限制同机制")
    void commonPoolLosesTheParent() throws Exception {
        ObservationRegistry registry = newRegistry();
        installAccessorFor(registry);

        Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
        root.start();
        try (Observation.Scope scope = root.openScope()) {
            // ⚠️ CompletableFuture 的异步方法默认跑在 ForkJoinPool.commonPool() 上——
            //    这正是知识库第 324 行说 Spring AI 流式路径会切过去的地方。
            CompletableFuture.runAsync(() -> AiObservation.start(AiSpans.LLM_GENERATE, registry)
                    .observe(() -> "生成")).get(5, TimeUnit.SECONDS);
        }
        root.stop();

        assertEquals(NO_PARENT, parents.get(AiSpans.LLM_GENERATE),
                "⚠️ 注意本测试**只证明了这个机制**（commonPool 未被包装 ⇒ 丢父）；"
                        + "⛔ 它不构成「已复现 Spring AI 的行为」的结论——那是知识库第 324 行的记录，不是本项目的实测");
        assertTrue(parents.containsKey(AiSpans.LLM_GENERATE),
                "Span 本身仍然产生了——即「Span 都在、只是边断了」，这正是它难被发现的原因");
    }
}
