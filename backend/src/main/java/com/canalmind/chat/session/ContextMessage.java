package com.canalmind.chat.session;

/**
 * 送进模型的一条对话消息（角色 + 正文），是 {@link ContextAssembler} 的最终产物。
 *
 * <p><b>为什么要一个专门的类型，而不是直接用一组字符串</b>：上下文装配的错误几乎都表现为
 * 「某一块内容错位或缺失」，而这类错误在一组无角色的字符串上**无法断言**。
 * 有了角色字段，测试才能逐条钉死
 * 「基础提示 → 会话摘要 → 最近原始参数 → 各轮问答 → 新问题」这个顺序——
 * 该顺序本身就是决策 C-38③ 的落地形态（摘要只压"更早的叙述"，原始参数紧随其后且独立成块）。
 *
 * <p><b>为什么空正文要在构造点就炸掉</b>：这是 {@code ToolResult} 第 8–9 行同一条纪律的延伸——
 * 那里写的是「⛔ 不要用"空结果集"表达差异——那会把"查了但没有"与"根本没查成"混成一件事」。
 * 一个空正文的 system 块同样会把「本来就没有摘要」与「摘要生成失败」混成一件事；
 * 而"默认值把失败伪装成正常"在本项目已真实发生过一次，见
 * `docs/工程复盘/工程方法/桩替身用尽后的默认值会伪装失败原因.md`。
 *
 * @param role    取 {@link #ROLE_SYSTEM} / {@link #ROLE_USER} / {@link #ROLE_ASSISTANT}，其它值一律拒绝
 * @param content 正文；**逐字保留**（⛔ 不裁剪、不归一化——结构化参数块要求逐字可对账）
 */
public record ContextMessage(String role, String content) {

    /** 系统消息：基础提示、会话摘要、最近原始参数三块都用它。 */
    public static final String ROLE_SYSTEM = "system";

    /** 用户消息：历史各轮的问题原文，以及本轮的新问题。 */
    public static final String ROLE_USER = "user";

    /** 助手消息：历史各轮的应答原文。 */
    public static final String ROLE_ASSISTANT = "assistant";

    public ContextMessage {
        if (!ROLE_SYSTEM.equals(role) && !ROLE_USER.equals(role) && !ROLE_ASSISTANT.equals(role)) {
            throw new IllegalArgumentException("role 必须是 " + ROLE_SYSTEM + " / " + ROLE_USER + " / "
                    + ROLE_ASSISTANT + " 之一，收到：" + role);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不得为空（role=" + role
                    + "）：空块会把「本来就没有这一块」与「生成这一块时失败了」混成同一件事");
        }
    }

    /** 系统消息的简写（三处 system 块的调用点都在 {@link ContextAssembler}）。 */
    public static ContextMessage system(String content) {
        return new ContextMessage(ROLE_SYSTEM, content);
    }

    /** 用户消息的简写。 */
    public static ContextMessage user(String content) {
        return new ContextMessage(ROLE_USER, content);
    }

    /** 助手消息的简写。 */
    public static ContextMessage assistant(String content) {
        return new ContextMessage(ROLE_ASSISTANT, content);
    }
}
