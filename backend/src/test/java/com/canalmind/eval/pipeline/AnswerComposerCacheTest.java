package com.canalmind.eval.pipeline;

import com.canalmind.cache.AnswerCache;
import com.canalmind.cache.AnswerCacheFactory;
import com.canalmind.cache.InMemoryCache;
import com.canalmind.intent.RouteDecision;
import com.canalmind.observability.AiSpans;
import com.canalmind.structured.ToolResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-55 在**作答器**这一层的接线测试：什么会被缓存、什么绝不缓存，以及命中时的可观测后果。
 *
 * <p>⚠️ 本类只覆盖"缓存判据与命中行为"，**不覆盖评测路径是否真的没开缓存**——
 * 那是装配层的决定（评测 CLI 不调用 {@code attachAnswerCache}），见
 * `docs/进度看板.md` §0.4.8。⛔ 别把本类当成"全链路无缓存"的证据。
 *
 * <p>零付费：模型是 Mockito 桩，不发任何网络请求。
 */
class AnswerComposerCacheTest {

    private static final String V = "rules:abc:chunks:12";
    private static final Duration TTL = Duration.ofHours(1);

    private static RouteDecision route(String channel) {
        return new RouteDecision(channel, List.of(), "R1", "测试构造", false, "", 1.0);
    }

    private static ToolResult okTool() {
        return ToolResult.ok("query_tariff", List.of(), List.of(), List.of(), List.of());
    }

    private static ToolResult noDataTool() {
        return ToolResult.noData("query_tariff", List.of(), List.of());
    }

    private static Map<String, String> fastEnv() {
        return Map.of("CANALMIND_LLM_BASE_BACKOFF_MS", "1", "CANALMIND_LLM_DEADLINE_MS", "60000");
    }

    // ------------------------------------------------------------------ 默认关

    @Test
    @DisplayName("默认关：没 attach 就不缓存，同问两次就是两次模型调用")
    void disabledByDefaultNeverCaches() {
        AtomicInteger calls = new AtomicInteger();
        AnswerComposer composer = new AnswerComposer(model(calls, "过闸费按核载吨位计收。"), null,
                fastEnv(), ObservationRegistry.NOOP);

        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");
        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");

        assertEquals(2, calls.get());
        assertEquals("noop", composer.cacheBackend(), "默认必须是 noop");
    }

    // ------------------------------------------------------------------ 命中

    @Test
    @DisplayName("开 + 结构化 + 工具 OK：第二次同问**零模型调用**，且**不起** ai.llm.generate Span")
    void structuredOkHitsCacheWithoutModelCallOrSpan() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryCache cache = new InMemoryCache();
        Recorder recorder = new Recorder();
        AnswerComposer composer = new AnswerComposer(model(calls, "过闸费按核载吨位计收。"), null,
                fastEnv(), registryOf(recorder)).attachAnswerCache(cache, V, TTL);

        String first = composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");
        int spansAfterFirst = countLlmSpans(recorder);

        // ⚠️ 问句换了"写法"也必须命中：这里用**首尾空白 + 全角/半角标点**——
        //    归一化只把"连续空白折成一个空格"，⛔ **不删内部空格**
        //    （`"过闸费  怎么收"` 与 `"过闸费怎么收"` 是**两个键**，安全方向的漏命中）。
        String second = composer.compose("  过闸费怎么收? ", route(RouteDecision.CHANNEL_STRUCTURED),
                okTool(), "");

        assertEquals("过闸费按核载吨位计收。", first);
        assertEquals(first, second, "命中时应当返回**同一份**缓存文本");
        assertEquals(1, calls.get(), "第二次绝不能再调模型");
        assertEquals(1L, cache.hits(), "命中计数要能对外核对");
        assertEquals(1, spansAfterFirst, "第一次应当有 1 个 ai.llm.generate Span");
        assertEquals(spansAfterFirst, countLlmSpans(recorder), "命中时不该再多一个 Span——那一刻没调模型");
    }

    // ------------------------------------------------------------------ 绝不缓存的三类

    @Test
    @DisplayName("检索通道：即使工具 OK 也不缓存（知识库第 308 行「需要专业判断」那类必须绕过）")
    void retrievalChannelIsNeverCached() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryCache cache = new InMemoryCache();
        AnswerComposer composer = new AnswerComposer(model(calls, "检索作答。"), null, fastEnv(),
                ObservationRegistry.NOOP).attachAnswerCache(cache, V, TTL);

        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_RETRIEVAL), okTool(), "");
        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_RETRIEVAL), okTool(), "");

        assertEquals(2, calls.get());
        assertEquals(0L, cache.hits());
    }

    @Test
    @DisplayName("工具不是 OK（含 DEGRADED）：不缓存——降级意味着答案是模型猜的，缓存等于固化错误")
    void nonOkToolIsNeverCached() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryCache cache = new InMemoryCache();
        AnswerComposer composer = new AnswerComposer(model(calls, "未查询到匹配的规则。"), null,
                fastEnv(), ObservationRegistry.NOOP).attachAnswerCache(cache, V, TTL);

        assertFalse(noDataTool().ok(), "前提：非 OK 工具");
        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), noDataTool(), "");
        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), noDataTool(), "");

        assertEquals(2, calls.get());
        assertEquals(0L, cache.hits());
    }

    @Test
    @DisplayName("数据版本取不到：宁可一次都不缓存，⛔ 不许退回「无版本键」")
    void unavailableDataVersionNeverCaches() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryCache cache = new InMemoryCache();
        AnswerComposer composer = new AnswerComposer(model(calls, "过闸费按核载吨位计收。"), null,
                fastEnv(), ObservationRegistry.NOOP)
                .attachAnswerCache(cache, AnswerCacheFactory.DataVersion.UNAVAILABLE, TTL);

        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");
        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");

        assertEquals(2, calls.get());
        assertEquals(0L, cache.hits(), "版本不可用 ⇒ 必须一次都不命中");
    }

    @Test
    @DisplayName("noop 缓存即使被 attach 也不缓存（开关与实现两层都要拦住）")
    void attachedNoopStillDoesNotCache() {
        AtomicInteger calls = new AtomicInteger();
        AnswerComposer composer = new AnswerComposer(model(calls, "过闸费按核载吨位计收。"), null,
                fastEnv(), ObservationRegistry.NOOP).attachAnswerCache(AnswerCache.noop(), V, TTL);

        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");
        composer.compose("过闸费怎么收？", route(RouteDecision.CHANNEL_STRUCTURED), okTool(), "");

        assertEquals(2, calls.get());
        assertFalse(AnswerCache.noop().enabled());
    }

    // ------------------------------------------------------------------ 夹具

    private static ChatModel model(AtomicInteger calls, String text) {
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.call(Mockito.any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        });
        return model;
    }

    private static int countLlmSpans(Recorder recorder) {
        return (int) recorder.started.stream().filter(AiSpans.LLM_GENERATE::equals).count();
    }

    private static ObservationRegistry registryOf(Recorder recorder) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        return registry;
    }

    /** 只记 Span **名字**：本类关心的是"有没有起这个 Span"，不是它的属性。 */
    private static final class Recorder implements ObservationHandler<Observation.Context> {
        private final List<String> started = new ArrayList<>();

        @Override
        public void onStart(Observation.Context context) {
            started.add(context.getName());
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
