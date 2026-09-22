package com.canalmind.chat;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * 把 HTTP 错误**如实**翻译成 {@link LlmHttpFailure}（带状态码与 {@code Retry-After}）。
 * 装法：{@code DeepSeekApi.builder().responseErrorHandler(new DeepSeekHttpErrorHandler())}。
 *
 * <h2>接口的真实形状（{@code javap} 实测 spring-web 6.2.15，⛔ 别凭印象写）</h2>
 *
 * <p>{@code ResponseErrorHandler} 上只有三个方法：
 * {@code hasError(ClientHttpResponse)}、{@code handleError(URI, HttpMethod, ClientHttpResponse)}
 * （{@code default}）、{@code handleError(ClientHttpResponse)}（{@code default}）。
 * ⚠️ 我一开始"照着印象"写了一个 {@code handleError(ClientHttpRequest, ClientHttpResponse)} 重载并加
 * {@code @Override}——**接口上根本没有这个签名**，编译直接报"方法不会覆盖或实现超类型的方法"。
 * ⇒ 用框架接口前先 `javap` 看一眼，比记忆便宜。
 *
 * <h2>⚠️ 实测踩到并已钉住的坑：429 会被静默变成"空应答"</h2>
 *
 * <p>用 JDK {@code HttpServer} 桩 + 真 {@code DeepSeekApi} 实测：
 * <b>只</b>覆写三参数那个 {@code default} 重载时，429 <b>不抛异常</b>，
 * 调用方拿到一个 <b>空 {@code ChatResponse}</b>（generations 为空）；
 * 覆写单参数那个之后，异常**原样穿透**到调用方，且能读到 {@code 429 TOO_MANY_REQUESTS} 与 {@code retry-after=1}。
 *
 * <p>⇒ 两个都覆写（单参数那个是实测真正生效的路径，三参数的是防御将来的默认实现变化）。
 * ⛔ 不要因为"看着多余"删掉任何一个。
 */
public class DeepSeekHttpErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    /** ⚠️ 实测**真正被调用**的是这个单参数重载（见类注释的实测过程）。 */
    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        throw failureOf(response);
    }

    /** 三参数 {@code default} 重载（接口上确有）——一并覆写，免得将来框架改走这条路径时行为变化。 */
    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
            throws IOException {
        throw failureOf(response);
    }

    private static LlmHttpFailure failureOf(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        // ⚠️ 只带状态码与一句说明，⛔ 不把响应体正文塞进异常消息：
        //    服务端错误体里可能回显我们发出去的提示词片段（与 §7.3「不把原文带出去」同一条纪律）。
        return new LlmHttpFailure(status.value(), parseRetryAfter(retryAfter),
                "模型调用被服务端拒绝：HTTP " + status.value() + " " + status);
    }

    /**
     * 解析 {@code Retry-After}。⚠️ 只认**秒数**（HTTP-date 形式少见且要处理时区，本项目不用）；
     * 非数字或负数一律当没有——⛔ 不猜、也不四舍五入成一个假的等待时长。
     */
    private static Duration parseRetryAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(raw.strip());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }
}
