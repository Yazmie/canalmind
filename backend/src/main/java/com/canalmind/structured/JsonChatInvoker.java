package com.canalmind.structured;

/**
 * 「把提示词发出去、把应答文本拿回来」的最小抽象。
 *
 * <p><b>为什么要这层</b>：参数抽取的重试/降级逻辑是最需要被测的部分，
 * 而它不该依赖真实模型。本接口让单测用桩把「第一次畸形、第二次正确」这类剧本演出来
 * （与第 1 周 `DeepSeekRequestShapeTest` 用本地桩验证请求形状同一思路）。
 */
@FunctionalInterface
public interface JsonChatInvoker {

    /** 一次调用。实现方负责 JSON Mode 等厂商参数；失败抛异常（由抽取器转成降级）。 */
    String invoke(String systemPrompt, String userPrompt) throws Exception;
}
