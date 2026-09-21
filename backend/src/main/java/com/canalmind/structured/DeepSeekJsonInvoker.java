package com.canalmind.structured;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;

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

    public DeepSeekJsonInvoker(ChatModel chatModel, Integer maxTokens) {
        this(ChatClient.builder(chatModel).build(), maxTokens);
    }

    public DeepSeekJsonInvoker(ChatClient chatClient, Integer maxTokens) {
        this.chatClient = chatClient;
        this.maxTokens = maxTokens;
    }

    @Override
    public String invoke(String systemPrompt, String userPrompt) {
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
    }
}
