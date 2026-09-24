package com.canalmind.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端页的跨域放行（决策 <b>C-70</b>）。
 *
 * <h2>为什么必须有这个类</h2>
 * 前端 {@code frontend/index.html} 是**单文件、双击即开**的页面（用户明确要求），
 * 于是它的来源（origin）与后端不是同一个：
 * <ul>
 *   <li>直接双击打开 ⇒ 浏览器把源当作**不透明源**，请求头是 {@code Origin: null}；</li>
 *   <li>挂在本地静态服务器上 ⇒ {@code Origin: http://127.0.0.1:<端口>} 之类。</li>
 * </ul>
 * 而请求本身是**简单请求**（GET、无自定义头），所以**浏览器不预检**，
 * 直接把请求发给后端；后端**没有 CORS 配置**时响应里没有
 * {@code Access-Control-Allow-Origin}，浏览器于是**丢弃整个响应并断开连接**。
 *
 * <p>实测到的两个现象正是同一件事的两面（2026-09-24）：
 * <ul>
 *   <li>前端：{@code 接口不可用：无法连接 …（Failed to fetch）}——这是 fetch 层被 CORS 拦的
 *       {@code TypeError}，⛔ 不是"端口不通"；</li>
 *   <li>后端：{@code SseEmitter.send} 时 java.io.IOException「你的主机中的软件中止了一个已建立的连接」
 *       ——请求**已经打到控制器**并开始推流，是浏览器主动断的。</li>
 * </ul>
 *
 * <h2>为什么放行范围刻意很窄</h2>
 * 放行 <b>只到 {@code /api/chat/**}</b>，且默认白名单是 <b>本机 + 文件来源</b>，
 * ⛔ 默认**不是**通配 {@code *}。理由：本接口**没有鉴权**、且每次问答都会**真实调用付费模型**——
 * 若整站通配，用户浏览器里任何一个网页都能静默消耗他的模型额度。
 * 需要更宽（例如页面挂在内网另一台机器）时，用下面这个属性显式覆盖，⛔ 不要改默认值。
 *
 * <h2>依据（知识库无覆盖，按 AGENTS.md §4.1 如实标注）</h2>
 * {@code docs/AI应用开发知识库-Markdown/} 全量检索 {@code CORS|跨域|Access-Control|同源}：
 * 只在《AI 应用评测体系》第 188/571 行命中"同源模型互评"，**与浏览器跨域无关** ⇒ 本条**知识库无覆盖**。
 * 依据为：① Spring Framework 的 MVC CORS 机制（本类用的 {@link CorsRegistry} 即其公开入口）；
 * ② **本项目的实测**——见 {@code ChatCorsTest}（预检从 403 {@code Invalid CORS request}
 * 变为 200 且回显来源；陌生来源仍 403）。
 */
@Configuration
public class ChatCorsConfig implements WebMvcConfigurer {

    /** 放行范围：⛔ 只放聊天接口，不整站放开（见类注释）。 */
    public static final String MAPPING = "/api/chat/**";

    /** 白名单属性名。 */
    public static final String ALLOWED_ORIGINS_PROPERTY = "canalmind.chat.cors.allowed-origin-patterns";

    /**
     * 默认白名单（逗号分隔，支持通配）。
     *
     * <p>三个值各自对应一种真实用法，⛔ 不是随手写的通配：
     * <ol>
     *   <li>{@code null}——**双击打开**页面时浏览器发的 {@code Origin} 就是字符串 {@code "null"}；
     *       ⚠️ 注意它是**字符串**不是空值，写成"留空"是无效的；</li>
     *   <li>{@code http://127.0.0.1:*}—— 页面挂在本地静态服务器上（端口任意）；</li>
     *   <li>{@code http://localhost:*}——同上，用 localhost 访问时。</li>
     * </ol>
     */
    public static final String DEFAULT_ALLOWED_ORIGINS =
            "null,http://127.0.0.1:*,http://localhost:*";

    /**
     * ⚠️ 用 {@code String[]} 接：Spring 的转换服务按逗号切分，
     * 于是 {@code canalmind.chat.cors.allowed-origin-patterns=http://a:1,http://b:2} 可直接生效。
     */
    @Value("${" + ALLOWED_ORIGINS_PROPERTY + ":" + DEFAULT_ALLOWED_ORIGINS + "}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(MAPPING)
                // 用 OriginPattern 而不是 allowedOrigins：pattern 版会把**实际来源**回显回去，
                // 于是 file:// 场景能得到 "Access-Control-Allow-Origin: null"（浏览器认这个）。
                .allowedOriginPatterns(allowedOriginPatterns)
                // ⛔ 不加 PUT/DELETE：本接口只有 GET /stream（ChatStreamController 实测只有这一个映射）。
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                // ⛔ 不开凭证：本项目没有鉴权、也没有 Cookie 会话，
                //    且 allowCredentials(true) 与通配来源在 Spring 里是互斥的（会启动即报错）。
                .allowCredentials(false)
                // 预检结果缓存 30 分钟：SSE 每次问答都可能新建连接，别每次都多一次预检往返。
                .maxAge(1800);
    }
}
