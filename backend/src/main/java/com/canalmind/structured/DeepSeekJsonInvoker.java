package com.canalmind.structured;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;

import java.util.Objects;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 用 DeepSeek 的 **JSON Mode** 实现 {@link JsonChatInvoker}（决策 C-32）。
 *
 * <p>⚠️ <b>本项目不得声称使用了 Structured Outputs</b>：DeepSeek 的
 * {@code response_format={"type":"json_object"}} 只保证"输出是合法 JSON"，
 * <b>不保证满足任何 Schema</b>（没有 OpenAI strict 那种 Schema 强制）。
 * 因此 Schema 合规性必须由服务端校验 + 错误反馈重试兜住（见 {@link ToolArgumentExtractor}）。
 *
 * <p>⚠️ 另一处厂商约束：JSON Mode 下提示词里**必须出现 "json" 字样**，否则 API 拒绝。
 * 故 {@link ToolCatalog#systemPrompt()} 里明确写了 JSON 相关字样——这不是装饰。
 */
public final class DeepSeekJsonInvoker implements JsonChatInvoker {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekJsonInvoker.class);

    private final ChatClient chatClient;
    private final Integer maxTokens;
    /** 模型标识（用于 Trace 的高基数 {@code ai.config.version}）；取不到时为 null。 */
    private final String modelName;
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.llm.generate}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    public DeepSeekJsonInvoker(ChatModel chatModel, Integer maxTokens) {
        this(chatModel, maxTokens, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 第 562 行的 {@code ai.llm.generate}）。
     *
     * <p>⚠️ 既有构造器保持不变、委托给 {@link ObservationRegistry#NOOP}
     * （使用方：{@code IntentRouterCli}、{@code StructuredQueryCli}、{@code QueryRewriteCli}、
     * {@code ExecutionAccuracyRunTest}），**旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public DeepSeekJsonInvoker(ChatModel chatModel, Integer maxTokens,
                               ObservationRegistry observationRegistry) {
        this(ChatClient.builder(chatModel).build(), maxTokens, modelNameOf(chatModel),
                observationRegistry);
    }

    public DeepSeekJsonInvoker(ChatClient chatClient, Integer maxTokens) {
        this(chatClient, maxTokens, null, ObservationRegistry.NOOP);
    }

    /**
     * 同上（{@link ChatClient} 起手）。
     *
     * <p>⚠️ 这条路上**拿不到模型标识**（{@code ChatClient} 不暴露它的默认选项），
     * 故本构造器产出的 Span <b>不带</b> {@code ai.config.version}——
     * ⛔ 宁可缺一个属性，也不编一个版本号（缺了能看出来，编了看不出来）。
     */
    public DeepSeekJsonInvoker(ChatClient chatClient, Integer maxTokens,
                               ObservationRegistry observationRegistry) {
        this(chatClient, maxTokens, null, observationRegistry);
    }

    /** 全参构造器（唯一给字段赋值的地方）。 */
    private DeepSeekJsonInvoker(ChatClient chatClient, Integer maxTokens, String modelName,
                                ObservationRegistry observationRegistry) {
        this.chatClient = chatClient;
        this.maxTokens = maxTokens;
        this.modelName = modelName;
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    /**
     * 从 {@link ChatModel} 的默认选项里取模型标识；取不到返回 {@code null}。
     *
     * <p>用 {@code getDefaultOptions().getModel()} 而不是让调用方传一个字面量：
     * 字面量与真实配置**必然分叉**（本项目已有"两份来源同一事实"的教训），
     * 而分叉的表现是 Trace 上说 {model A}、实际调的是 {model B}——不会报错。
     */
    private static String modelNameOf(ChatModel chatModel) {
        if (chatModel == null) {
            return null;
        }
        ChatOptions options;
        try {
            options = chatModel.getDefaultOptions();
        } catch (RuntimeException e) {
            // 取不到不算错：埋点不能因为"拿不到模型名"把业务搞挂
            return null;
        }
        String model = options == null ? null : options.getModel();
        return model == null || model.isBlank() ? null : model;
    }

    /**
     * 一次 JSON Mode 调用。
     *
     * <p>规格书 §7.2 第 562 行给模型调用一个 Span（{@code ai.llm.generate}）。
     * 这里是**结构化通道内的那次调用**（参数抽取与分类器都走它），故它的父节点是
     * {@code ai.tool.param.extract} 或 {@code ai.intent.route}——⛔ 不是根。
     *
     * <p>记高基数 {@code ai.config.version} = 模型标识（如 {@code glm-5.3-flash}）：
     * "这条 Trace 是哪一次换模型之前/之后跑的"必须能直接看出来——
     * 本项目"每轮只改少量变量"的归因纪律需要它（换了模型而指标变了，
     * 与改了提示词而指标变了，是两件必须分开的事）。
     * <p>⚠️ 模型标识取不到时**跳过**该属性（见 {@link #modelNameOf}），⛔ 不填占位符。
     */
    @Override
    public String invoke(String systemPrompt, String userPrompt) {
        Observation observation = AiObservation.start(AiSpans.LLM_GENERATE, observationRegistry);
        return observation.observe(() -> {
            if (modelName != null) {
                AiObservation.highCardinality(observation, AiSpans.KEY_CONFIG_VERSION, modelName);
            }
            var options = DeepSeekChatOptions.builder()
                    .responseFormat(ResponseFormat.builder()
                            .type(ResponseFormat.Type.JSON_OBJECT)
                            .build());
            if (maxTokens != null) {
                options.maxTokens(maxTokens);
            }
            String text = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(options.build())
                    .call()
                    .content();
            if (log.isDebugEnabled()) {
                log.debug("JSON Mode 应答 {} 字", text == null ? 0 : text.length());
            }
            return text == null ? "" : text;
        });
    }
}
