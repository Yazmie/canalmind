package com.canalmind.chat.frame;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.canalmind.intent.RouteDecision;
import com.canalmind.structured.ToolResult;

/**
 * 结构化帧的**出站编码**（SSE 的 {@code event: structured} 载荷）。
 *
 * <h2>为什么单独一个类，而不是在装配处顺手拼 JSON</h2>
 * 这个载荷是**前后端之间的合同**：字段名一改，前端就静默少渲染一块。
 * 把它固定成一处、并配一组纯单测，合同才有唯一来源
 * （本项目对"契约只此一份"的态度见决策 C-33）。
 * 本类**不依赖任何 Spring 类型**，可脱离容器做纯单元测试。
 *
 * <h2>⚠️ 两条硬约束，都来自知识库与既有代码</h2>
 * <ol>
 *   <li><b>必须是单行 JSON</b>：SSE 用换行做字段边界，载荷里出现裸换行
 *       会让"上一个事件结束、下一个事件开始"，前端事件边界当场错位。
 *       依据：知识库《大模型 API 调用工程实践》第 82–88、110–114 行；
 *       本项目的 {@code SseTextCodec} 就是为同一原因存在的。
 *       ⇒ 这里用 Jackson 的**默认**（非 pretty）序列化：它会把字符串值里的
 *       LF/CR 转成 JSON 转义序列 {@code \n} / {@code \r}，
 *       **所以"单行"是构造上的性质，不靠调用方自觉**。测试里对此有断言。</li>
 *   <li><b>结构化载荷一次发完，⛔ 不分片增量发</b>：知识库第 448–450 行明确
 *       "不要一边收到 delta 一边 {@code JSON.parse()} 完整对象"。
 *       故本类只提供"整帧编码"这一个入口，**刻意不提供增量 API**——
 *       没有那个方法，就不会有人在上面写出半成品解析。</li>
 * </ol>
 *
 * <h2>字段与真值的对应关系（⛔ 不要在这里"顺手算一下"）</h2>
 * <ul>
 *   <li>{@code tool.rows} / {@code tool.columns} 直接来自 {@link ToolResult}，
 *       是**可逐字符对账**的确定性事实（{@link ToolResult} 的 javadoc 写明
 *       "数值已在 SQL 里 {@code ::text} 定型，保证可逐字符对账"）；</li>
 *   <li>金额**不在本类里解析**：前端按列名（含「金额」的那一列）取值。
 *       ⛔ 服务端刻意不"顺手把金额抽出来单独放一个字段"——那等于给同一份事实
 *       造第二个来源，一旦两处口径漂移，没人知道该信哪个
 *       （本项目已有"两份来源同一事实"的教训）。</li>
 *   <li>{@code route.reason} 是自由文本（可能含用户问句片段），
 *       ⛔ 它**不进 Trace 属性**，只进本载荷与 {@code eval_record}（见 {@code IntentRouter#route}）。</li>
 * </ul>
 */
public final class ChatFrameCodec {

    /**
     * 帧版本号（合同字段 {@code v}）。
     *
     * <p>前端按它分支渲染。**只在字段含义发生不兼容变更时才 +1**；
     * 新增可选字段不算不兼容（前端忽略未知字段）。
     */
    public static final int VERSION = 1;

    /** 引用摘录的字符上限：引用是"让人认出是哪一段"，不是把正文搬一遍。 */
    static final int EXCERPT_LIMIT = 120;

    /**
     * ⚠️ 用**默认**配置的 ObjectMapper：它的 {@code writeValueAsString} 不缩进，
     * 且会转义字符串里的控制字符 —— 这正是"单行"这条硬约束所依赖的行为。
     * ⛔ 不要给它装 pretty printer（那会插入真实换行，直接破坏 SSE 事件边界）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatFrameCodec() {
    }

    /**
     * 编码一帧。
     *
     * @param sessionId 会话标识（已归一化）
     * @param route     路由结论；为 {@code null} 时不出 {@code route} 对象
     * @param tool      结构化通道结果（含 {@code OK} / {@code NO_DATA} /
     *                  {@code NEEDS_CLARIFICATION} / {@code REJECTED} / {@code DEGRADED}
     *                  **五态**，如实透传，⛔ 不在这里归并）；为 {@code null} 时不出 {@code tool} 对象
     * @param citations 引用来源，按**检索名次**排列；可为空
     * @return 单行 JSON 字符串
     */
    public static String encode(String sessionId, RouteDecision route, ToolResult tool,
                                List<FrameCitation> citations) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("v", VERSION);
        root.put("sessionId", sessionId == null ? "anonymous" : sessionId);

        if (route != null) {
            ObjectNode r = root.putObject("route");
            r.put("channel", route.channel());
            r.put("ruleId", route.ruleId());
            r.put("reason", route.reason());
        }

        if (tool != null) {
            ObjectNode t = root.putObject("tool");
            // ⚠️ 用 (String) 强转以选中 put(String, String) 重载：
            //    值为 null 时写入 NullNode（字段仍在，前端读取得到 null），
            //    ⛔ 不要用 putNull 之外的写法让字段时有时无——那会让前端多一种分支。
            //    tool 名与 degradeKind 都可能为 null（ToolResult.degraded(null, ...) 就是）。
            t.put("name", (String) tool.tool());
            t.put("status", tool.status());

            ArrayNode columns = t.putArray("columns");
            for (String c : tool.columns()) {
                columns.add(c);
            }
            ArrayNode rows = t.putArray("rows");
            for (List<String> row : tool.rows()) {
                ArrayNode one = rows.addArray();
                if (row != null) {
                    for (String cell : row) {
                        one.add(cell);
                    }
                }
            }
            ArrayNode facts = t.putArray("facts");
            for (String f : tool.facts()) {
                facts.add(f);
            }
            t.put("question", (String) tool.question());
            t.put("degradeKind", (String) tool.degradeKind());
        }

        ArrayNode cits = root.putArray("citations");
        if (citations != null) {
            for (FrameCitation c : citations) {
                ObjectNode one = cits.addObject();
                one.put("chunkId", c.chunkId());
                one.put("docTitle", c.docTitle());
                one.put("docNo", c.docNo());
                one.put("articleNo", c.articleNo());
                one.put("articleTitle", c.articleTitle());
                ArrayNode path = one.putArray("chapterPath");
                for (String p : c.chapterPath()) {
                    path.add(p);
                }
                one.put("partIndex", c.partIndex());
                one.put("partCount", c.partCount());
                one.put("excerpt", c.excerpt());
            }
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // 纯 JSON 树序列化在实践上不会失败（没有自定义序列化器）。
            // 真失败也不能把半截载荷发上线：直接抛，由调用方按失败软着陆丢弃该帧。
            throw new IllegalStateException("结构化帧编码失败", e);
        }
    }

    /**
     * 把 chunk 正文压成一行摘录：折掉换行、压缩空白、截断。
     *
     * <p>⚠️ 折换行**不只是为了好看**：摘录会进 JSON 字符串，虽然 Jackson 会转义，
     * 但一份带十几行换行的正文摘录在 UI 里会把引用区撑得比答案还长，
     * 反而看不出"这是哪一段"。
     *
     * @param content 正文，可为 {@code null}
     * @return 单行摘录（超长时带省略号）；输入为空时返回空串
     */
    public static String excerpt(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(content.length(), EXCERPT_LIMIT + 1));
        boolean lastWasBlank = false;
        for (int i = 0; i < content.length() && sb.length() < EXCERPT_LIMIT; i++) {
            char c = content.charAt(i);
            // 换行/回车/制表与连续空白统一折成一个空格。
            // ⚠️ 这里用字符比较而不是转义字面量：本项目有过"反斜杠写法静默损坏"的事故
            //    （见 AGENTS.md §7.2），计算字符任何编辑工具都折坏不了。
            boolean blank = c == ' ' || c == (char) 9 || c == (char) 10 || c == (char) 13;
            if (blank) {
                if (!lastWasBlank && sb.length() > 0) {
                    sb.append(' ');
                }
                lastWasBlank = true;
            } else {
                sb.append(c);
                lastWasBlank = false;
            }
        }
        String out = sb.toString().trim();
        return content.length() > EXCERPT_LIMIT ? out + "…" : out;
    }
}
