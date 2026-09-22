package com.canalmind.eval.pipeline;

import com.canalmind.chat.LlmCallGuard;
import com.canalmind.chat.LlmHttpFailure;
import com.canalmind.observability.AiSpans;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C-58 的**接线**测试：守卫（{@link LlmCallGuard}）是否真的接在作答路径上、接上之后行为对不对。
 *
 * <p>⚠️ 为什么要单独写这一类：守卫自己的测试（{@code LlmCallGuardTest}）全绿，**只能证明守卫本身对**，
 * 证明不了"它被用上了"。而"加了一个类却没人调它"恰好是本项目最需要防的假 POSS 型错误——
 * 所以这里用 {@code Mockito.verify(model, times(3))} 数**真发出去几次模型调用**。
 *
 * <p>零付费：桩模型是 Mockito mock，⛔ 不出网。
 */
class AnswerComposerGuardWiringTest {

    @Test
    @DisplayName("接线：429 两次后成功 ⇒ 作答器内部真的重试了 3 次，并返回真实答复")
    void retriesTransportFailuresInsideComposer() {
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.call(Mockito.any(Prompt.class)))
                .thenThrow(new LlmHttpFailure(429, null, "限流"))
                .thenThrow(new LlmHttpFailure(429, null, "限流"))
                .thenReturn(answer("过闸费按吨位分档：0-1000 吨…"));

        Recorder recorder = new Recorder();
        AnswerComposer composer = new AnswerComposer(model, null, fastEnv(), registryOf(recorder));

        String text = composer.compose("过闸费怎么收？", null, null, "");

        assertEquals("过闸费按吨位分档：0-1000 吨…", text);
        Mockito.verify(model, Mockito.times(3)).call(Mockito.any(Prompt.class));

        Observation.Context ctx = recorder.only(AiSpans.LLM_GENERATE);
        assertNull(lowCard(ctx, AiSpans.KEY_DEGRADE_REASON),
                "重试后成功了 ⇒ 这不是降级，⛔ 不该留下降级原因");
        assertNull(ctx.getError());
    }

    @Test
    @DisplayName("接线：一直 429 ⇒ 如实抛错 + Span 留下 ai.degrade.reason=RATE_LIMITED（⛔ 不返回话术）")
    void degradesLoudlyWithReasonOnSpanWhenRateLimitedForever() {
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.call(Mockito.any(Prompt.class)))
                .thenThrow(new LlmHttpFailure(429, null, "限流"));

        Recorder recorder = new Recorder();
        AnswerComposer composer = new AnswerComposer(model, null, fastEnv(), registryOf(recorder));

        LlmCallGuard.LlmCallDegradedException thrown =
                assertThrows(LlmCallGuard.LlmCallDegradedException.class,
                        () -> composer.compose("过闸费怎么收？", null, null, ""));

        assertEquals(LlmCallGuard.DegradeReason.RATE_LIMITED, thrown.reason());
        assertEquals(3, thrown.attempts(), "重试 2 次 + 首发 1 次");
        Mockito.verify(model, Mockito.times(3)).call(Mockito.any(Prompt.class));

        Observation.Context ctx = recorder.only(AiSpans.LLM_GENERATE);
        assertEquals("RATE_LIMITED", lowCard(ctx, AiSpans.KEY_DEGRADE_REASON),
                "降级原因必须落在 Span 上，否则线上只知道「失败了」，不知道「是被限流」");
        assertEquals("3", highCard(ctx, AiSpans.KEY_ATTEMPT),
                "尝试次数也要落 Span（知识库第 55 行：重试必须能被区分出来）");
    }

    @Test
    @DisplayName("接线：400 不重试 ⇒ 只发一次请求，且异常如实穿透")
    void doesNotRetryClientErrorsInsideComposer() {
        ChatModel model = Mockito.mock(ChatModel.class);
        Mockito.when(model.call(Mockito.any(Prompt.class)))
                .thenThrow(new LlmHttpFailure(400, null, "参数错"));

        Recorder recorder = new Recorder();
        AnswerComposer composer = new AnswerComposer(model, null, fastEnv(), registryOf(recorder));

        LlmHttpFailure thrown = assertThrows(LlmHttpFailure.class,
                () -> composer.compose("过闸费怎么收？", null, null, ""));

        assertEquals(400, thrown.statusCode());
        Mockito.verify(model, Mockito.times(1)).call(Mockito.any(Prompt.class));

        Observation.Context ctx = recorder.only(AiSpans.LLM_GENERATE);
        assertNull(lowCard(ctx, AiSpans.KEY_DEGRADE_REASON),
                "客户端错不做降级（它不会被「再试一次」治好）⇒ 不该留降级原因");
    }

    // ================= 夹具 =================

    /** ⚠️ 退避基数压到 1ms、截止放宽，否则用例真的要睡 1.5 秒（守卫自己的测试已覆盖真实时长语义）。 */
    private static Map<String, String> fastEnv() {
        return Map.of(
                LlmCallGuard.ENV_BASE_BACKOFF_MS, "1",
                LlmCallGuard.ENV_DEADLINE_MS, "60000");
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ObservationRegistry registryOf(Recorder recorder) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        return registry;
    }

    /** 读低基数属性：与既有测试同法（聚合视图）。 */
    private static String lowCard(Observation.Context ctx, String key) {
        return keyValue(ctx.getLowCardinalityKeyValues(), key);
    }

    private static String highCard(Observation.Context ctx, String key) {
        return keyValue(ctx.getHighCardinalityKeyValues(), key);
    }

    private static String keyValue(KeyValues keyValues, String key) {
        for (KeyValue kv : keyValues) {
            if (key.equals(kv.getKey())) {
                return kv.getValue();
            }
        }
        return null;
    }

    /** 极简录制器（同 {@code AiSpanTreeTest} 里那只的形状）。 */
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
}
