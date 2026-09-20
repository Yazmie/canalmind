package com.canalmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 装配。
 *
 * <p>Spring AI 的自动配置在发现 {@code ChatModel} Bean 之后会提供
 * {@code ChatClient.Builder}，这里拿它来固化系统提示词等默认项，
 * 避免每次调用都重复拼装。
 *
 * <p><b>第 1 周范围说明</b>：此处只有一个通用系统提示词。
 * 第 2 周实现意图路由后，会按「结构化查询通道 / 文档检索通道 / 确定性计算通道」
 * 拆分出各自的提示词与工具集，届时本类会改为按通道构造多个 ChatClient。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 业务助手基础人设。
     *
     * <p>⚠️ 这里的约束是**第 1 周的占位版本**。第 4 周做评测时，
     * 提示词版本要随评测报告一起记录（规格书 §10 纪律：每轮只改少量变量）。
     */
    private static final String SYSTEM_PROMPT = """
            你是平陆运河航运物流业务助手，服务于内河航运物流场景。

            回答要求：
            1. 只依据业务数据库查询结果与规则文档作答，不得编造。
            2. 涉及费用、时限、流程等关键信息时，明确标注依据来源。
            3. 检索不到依据时，直接说明无法回答，并建议转人工，不要猜测。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }
}
