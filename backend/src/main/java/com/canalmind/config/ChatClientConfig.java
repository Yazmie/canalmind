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
     * ⚠️⚠️ **这是 v1，已被冻结，⛔ 不要再直接改这段文字**（C-51）。
     *
     * <p>**为什么冻结**：L3/L5/L6 的既有出分产物就是**本段文字**的产物，
     * 且已由 git 取证证明"出分之后答案路径零行为改动"（见 DECISIONS 的 **C-51 收口**）。
     * 直接改这里 ⇒ v1 的判据基线当场作废，v1/v2 对照也就无法归因。
     *
     * <p>⇒ 要做 Prompt 对照，请**另起版本**：见 {@link PromptVersions}——
     * 它**从本段派生** v2，⛔ 不复制一份（复制就会漂移：本项目已有"两份来源同一事实"的教训）。
     *
     * <p>本段改为**包内可见**正是为了让 {@link PromptVersions} 与测试能钉住它。
     */
    static final String SYSTEM_PROMPT = """
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
