package com.canalmind.chat;

/**
 * SSE 正文转义编解码。
 *
 * <p><b>为什么需要它</b>：SSE 的 {@code data:} 字段以换行作为字段边界。
 * 若模型输出里含真实换行（列表、代码块、道歉话术都会），直接塞进 {@code data:}
 * 会让协议把换行当成"字段结束"，事件边界立刻错位。
 * 依据：知识库《大模型 API 调用工程实践》第 110–114 行。
 *
 * <p><b>本实现与知识库建议的差异（刻意，已验证）</b>：
 * 知识库第 117 行给的最小示例只转义 {@code \n} 与 {@code \r}：
 * <pre>
 *   chunk.replace("\n", "\\n").replace("\r", "\\r")
 * </pre>
 * 该写法**不是双射**：正文里本来就有的字面量 {@code \n}（反斜杠 + n）
 * 在还原时会被误当作转义序列，导致内容损坏。实测：
 * <pre>
 *   原文   : C:\new\path 与 JSON 示例 {\n}
 *   还原后 : C:&lt;LF&gt;ew\path 与 JSON 示例 {&lt;LF&gt;}
 * </pre>
 * 航运问答里出现文件路径、JSON 示例、代码片段是很常见的，因此这里
 * **额外转义反斜杠本身**，使 {@code unescape(escape(x))} 恒等于 {@code x}。
 *
 * <p>这不违背知识库——它在第 124 行明确说"选型核心是：团队要在服务端和前端
 * 固定同一种语义，并把单元测试覆盖到「含换行、含 CR、含空行」的片段"，
 * 即把具体约定留给项目自定。本类就是那个约定，见 {@code SseTextCodecTest}。
 *
 * <p><b>协议约定</b>（前端必须实现同样的解码）：
 * <table border="1">
 *   <caption>转义映射</caption>
 *   <tr><th>原始字符</th><th>出站字面量</th></tr>
 *   <tr><td>{@code \}（反斜杠）</td><td>{@code \\}</td></tr>
 *   <tr><td>{@code LF}（\n）</td><td>{@code \n}</td></tr>
 *   <tr><td>{@code CR}（\r）</td><td>{@code \r}</td></tr>
 * </table>
 *
 * <p>本类**刻意不依赖任何 Spring 类型**，只处理 {@link String}，
 * 因此可脱离容器做纯单元测试。
 */
public final class SseTextCodec {

    private SseTextCodec() {
    }

    /**
     * 出站转义：把正文中会破坏 SSE 分帧的字符转成字面量。
     *
     * <p>单趟扫描实现，不使用连续的 {@code replace}——
     * 链式 replace 极易产生"前一步产物被后一步再次改写"的顺序依赖缺陷。
     */
    public static String escape(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(chunk.length() + 16);
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            switch (c) {
                // 反斜杠必须转义，否则与下面两个转义序列混淆（见类注释）
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 入站还原：与 {@link #escape} 严格互逆。
     *
     * <p>单趟扫描，遇到反斜杠就看下一个字符决定还原成什么；
     * 不认识的转义序列按"反斜杠后跟该字符"原样保留，
     * 保证前向兼容（将来若新增转义类型，旧版前端不会崩，只会原样显示）。
     */
    public static String unescape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= text.length()) {
                // 末尾孤立反斜杠：原样保留，不抛异常
                sb.append('\\');
                break;
            }
            char next = text.charAt(++i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                // 未知转义：原样输出，保证前向兼容
                default -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }
}
