package com.canalmind.chat;

import java.time.Duration;

/**
 * 模型调用的 HTTP 层失败：**带上真实状态码与 {@code Retry-After}**（C-58，2026-09-22）。
 *
 * <h2>为什么必须自己造这个类型（实测，不是设计偏好）</h2>
 *
 * <p>Spring AI 1.1.2 的 DeepSeek 客户端在 429 时抛的是
 * {@code org.springframework.ai.retry.NonTransientAiException}——实测它：
 * ①<b>没有 cause 链</b>（只有一句 message），②<b>没有状态码访问器</b>
 * （{@code javap} 实测该类只继承 {@code RuntimeException(String)}），
 * ③把状态码**只留在消息文本里**：{@code "429 - {\"error\":…}"}。
 *
 * <p>⇒ 想"按 429 决定要不要退避重试"，就只剩两条路：**解析那句散文**（本项目明令禁止
 * 用字符串匹配判断语义），或者**在 HTTP 层自己拿到状态码**。本类走后者：
 * {@link DeepSeekHttpErrorHandler} 从 {@code ClientHttpResponse} 上直接读状态码与响应头。
 *
 * <p>⚠️ 顺带一个实测坑（已由测试钉住）：{@code ResponseErrorHandler} 有**新旧两个**
 * {@code handleError} 重载，实测 **RestClient 走的是旧式 {@code handleError(HttpResponse)}**。
 * 只覆写新式那个重载时，429 不会被抛出来，而是变成一个<b>空 {@code ChatResponse}</b>
 * ——"限流"被静默伪装成"模型答了个空"，正是本项目《空作答被当成成功作答》那条复盘的形状。
 */
public class LlmHttpFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final Duration retryAfter;

    /**
     * @param statusCode HTTP 状态码（本项目的判据只用它，⛔ 不解析消息文本）
     * @param retryAfter 响应头 {@code Retry-After}（没有则为 {@code null}）；⚠️ 单位是秒
     */
    public LlmHttpFailure(int statusCode, Duration retryAfter, String message) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public int statusCode() {
        return statusCode;
    }

    /** 服务端要求的等待时长；没有该响应头时为 {@code null}（由调用方走指数退避）。 */
    public Duration retryAfter() {
        return retryAfter;
    }

    /**
     * 这个失败**值不值得重试**（知识库《大模型 API 调用工程实践…》第 191 行：
     * OpenAI 对 rate limit error 建议随机指数退避；第 190 行列了 429 / 500 / 504 / 529 一档）。
     *
     * <p>⚠️ 其余 4xx 一律**不重试**：客户端错（参数、鉴权、余额）重试多少次都还是错，
     * 只会白烧配额——知识库第 19 行把"哪些错误能重试、哪些不能"列为重试设计的第一件事。
     */
    public boolean retryable() {
        return statusCode == 429 || statusCode >= 500;
    }

    @Override
    public String toString() {
        return "LlmHttpFailure[HTTP " + statusCode
                + (retryAfter == null ? "" : ", Retry-After=" + retryAfter.toSeconds() + "s") + "]";
    }
}
