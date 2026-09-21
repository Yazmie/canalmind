package com.canalmind.chat.session;

import java.util.List;

/**
 * 把「更早的若干轮」折成一段摘要——决策 C-38 的 Compaction 那一半。
 *
 * <p><b>为什么是接口而不是直接调用模型</b>：压缩路径是本模块最需要被验证的部分，
 * 尤其"压缩不得吃掉结构化参数"这条硬约束——而验证它必须能**控制摘要器的行为**。
 * 测试可以把桩做成"把全部数字都丢掉"的摘抄，再看近期原始参数是否仍在上下文里
 * （{@code ContextAssemblerTest} 的 {@code L4-008} 用例正是这么做的，⛔ 换不出等价的断言）。
 * 这与 {@code JsonChatInvoker} 让参数抽取的重试逻辑可测是同一思路。
 *
 * <p><b>{@code summarize} 允许抛异常，且失败必须由调用方显式处理</b>：摘要是一次模型调用，
 * 会超时、会被限流、会返回空。失败时 {@link ContextAssembler} **保留全部原文并把失败写进
 * {@code note}**；⛔ 绝不允许"摘要失败就当作没有更早轮次"——
 * 那是一次静默丢历史，而本项目已有"默认值把失败伪装成正常"的实录：
 * `docs/工程复盘/工程方法/桩替身用尽后的默认值会伪装失败原因.md`。
 */
@FunctionalInterface
public interface ContextSummarizer {

    /**
     * 生成新摘要。
     *
     * @param previousSummary 上一版摘要（没有时为空串）。实现方**应把它并入新摘要**，
     *                        否则每压缩一次就会丢掉比这次待折叠轮次更早的内容
     * @param toCompact       **按索引升序**排列的待折叠轮次（⛔ 不含近期轮次——
     *                        近期轮次的原文由 {@link ContextCompactor} 保证留在上下文里）
     * @return 新摘要正文；**不得返回 null 或空白**。折叠之后这些原文就会被丢掉，
     *         返回空摘要等于把历史折没了，故 {@link ContextCompactor} 会拒绝空摘要并抛异常
     * @throws Exception 模型调用失败（超时/限流/解析失败）——由调用方决定如何处置
     */
    String summarize(String previousSummary, List<SessionTurn> toCompact) throws Exception;
}
