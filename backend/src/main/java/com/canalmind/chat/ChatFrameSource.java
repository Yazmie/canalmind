package com.canalmind.chat;

import java.util.Optional;

/**
 * 结构化帧的来源（SSE 的 {@code event: structured} 载荷）。
 *
 * <p><b>为什么做成接口而不是让 Controller 直接依赖装配类</b>：
 * {@link ChatStreamController} 只该关心"怎么把帧发出去"，不该关心"帧从哪来"。
 * 拆开之后有两件实测好处：
 * <ol>
 *   <li><b>既有行为可证零变化</b>：接口自带 {@link #NONE} 常量，
 *       容器里没有装配类时（默认配置就是这样）行为与本类引入前**逐字节一致**，
 *       无需改任何既有测试来"适应"新功能；</li>
 *   <li><b>协议可脱容器单测</b>：测试可以直接塞一个返回固定 JSON 的 lambda，
 *       验证"帧上线后没有破坏 SSE 事件边界"，不必连库、不必调模型。</li>
 * </ol>
 *
 * <p>⚠️ 实现方必须保证返回值**是单行 JSON**：SSE 以换行为字段边界，
 * 载荷里出现裸换行会让事件边界错位（本项目 {@link SseTextCodec} 就是为此而存在，
 * 依据知识库《大模型 API 调用工程实践》第 110–114 行）。
 * 本接口刻意返回**已编码好的字符串**而不是对象，正是为了让"编码成什么样"
 * 这件事发生在实现方（{@code ChatFrameCodec}）而不是散落在 Controller 里。
 */
@FunctionalInterface
public interface ChatFrameSource {

    /**
     * 为一次提问装配结构化帧。
     *
     * @param question  用户问题（**原始文本**，不做归一化——路由与抽取层各自会归一化）
     * @param sessionId 已归一化的会话标识（缺省为 {@code anonymous}）
     * @return 单行 JSON 载荷；**没有可展示的结构化内容时返回空**
     *         （空 ≠ 失败：纯闲聊、空问题、通道无结果都走这里）
     */
    Optional<String> frameFor(String question, String sessionId);

    /**
     * 「没有帧源」的默认实现：永远返回空 ⇒ 出站事件集合与引入本接口之前完全相同。
     *
     * <p>它同时是 {@link ChatStreamController} 在容器里没有装配类时使用的兜底值。
     */
    ChatFrameSource NONE = (question, sessionId) -> Optional.empty();
}
