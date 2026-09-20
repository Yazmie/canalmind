package com.canalmind.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SseTextCodec} 单元测试。
 *
 * <p>覆盖范围对应知识库《大模型 API 调用工程实践》第 124 行的明确要求：
 * <i>"把单元测试覆盖到「含换行、含 CR、含空行」的片段"</i>。
 * 另补充了反斜杠与往返一致性用例——那正是知识库原示例会出错的场景。
 */
class SseTextCodecTest {

    @Nested
    @DisplayName("出站转义：知识库点名要求的三类片段")
    class Escape {

        @Test
        @DisplayName("含换行的片段被转义为字面量 \\n")
        void escapesLineFeed() {
            assertThat(SseTextCodec.escape("第一行\n第二行")).isEqualTo("第一行\\n第二行");
        }

        @Test
        @DisplayName("含 CR 的片段被转义为字面量 \\r")
        void escapesCarriageReturn() {
            assertThat(SseTextCodec.escape("第一行\r第二行")).isEqualTo("第一行\\r第二行");
        }

        @Test
        @DisplayName("含空行的片段两个换行都被转义")
        void escapesBlankLine() {
            assertThat(SseTextCodec.escape("第一段\n\n第二段")).isEqualTo("第一段\\n\\n第二段");
        }

        @Test
        @DisplayName("CRLF 被转义为两个独立序列（不做规范化，以保证可逆）")
        void escapesCrlf() {
            // 刻意不把 \r\n 折叠成 \n：折叠会破坏双射，导致往返不一致
            assertThat(SseTextCodec.escape("A\r\nB")).isEqualTo("A\\r\\nB");
        }

        @Test
        @DisplayName("反斜杠本身被转义，避免与 \\n 混淆")
        void escapesBackslash() {
            assertThat(SseTextCodec.escape("C:\\new\\path")).isEqualTo("C:\\\\new\\\\path");
        }

        @Test
        @DisplayName("无特殊字符时原样返回")
        void plainTextUnchanged() {
            assertThat(SseTextCodec.escape("你好，请问过闸费怎么算？")).isEqualTo("你好，请问过闸费怎么算？");
        }

        @Test
        @DisplayName("null 与空串返回空串，不抛异常")
        void handlesNullAndEmpty() {
            assertThat(SseTextCodec.escape(null)).isEmpty();
            assertThat(SseTextCodec.escape("")).isEmpty();
        }

        @ParameterizedTest(name = "转义后不得残留真实换行：{0}")
        @ValueSource(strings = {
                "列表项\n- A\n- B",
                "代码块\n```java\nint a = 1;\n```",
                "含空行\n\n\n结束",
                "CR 结尾\r"
        })
        @DisplayName("转义结果中不得含真实 LF 或 CR")
        void neverLeavesRealLineBreaks(String input) {
            String escaped = SseTextCodec.escape(input);
            assertThat(escaped).doesNotContain("\n").doesNotContain("\r");
        }
    }

    @Nested
    @DisplayName("往返一致性：unescape(escape(x)) 必须等于 x")
    class RoundTrip {

        @ParameterizedTest(name = "无损往返：{0}")
        @ValueSource(strings = {
                "普通文本",
                "第一行\n第二行",
                "第一行\r第二行",
                "含空行\n\n结束",
                "代码块\n```java\nint a = 1;\n```\n",
                "道歉话术\n\n抱歉，我无法回答。",
                "多段\r\n混合\n换行\r"
        })
        @DisplayName("常规文本无损往返")
        void losslessForCommonText(String input) {
            assertThat(SseTextCodec.unescape(SseTextCodec.escape(input))).isEqualTo(input);
        }

        @Test
        @DisplayName("★ 含文件路径的正文无损往返（知识库原示例会在此出错）")
        void losslessForLiteralBackslash() {
            String original = "C:\\new\\path 与 JSON 示例 {\\n}";
            String escaped = SseTextCodec.escape(original);
            String restored = SseTextCodec.unescape(escaped);

            assertThat(restored)
                    .as("字面量反斜杠不能被误还原为换行")
                    .isEqualTo(original);
        }

        @Test
        @DisplayName("CRLF 不被规范化，可逆")
        void losslessForCrlf() {
            String original = "第一行\r\n第二行";
            assertThat(SseTextCodec.unescape(SseTextCodec.escape(original))).isEqualTo(original);
        }

        @Test
        @DisplayName("末尾孤立反斜杠不抛异常")
        void trailingBackslashIsSafe() {
            assertThat(SseTextCodec.unescape("结尾\\")).isEqualTo("结尾\\");
        }

        @Test
        @DisplayName("未知转义序列原样保留（前向兼容）")
        void unknownEscapeIsPreserved() {
            assertThat(SseTextCodec.unescape("\\t\\x")).isEqualTo("\\t\\x");
        }

        @Test
        @DisplayName("null 与空串返回空串")
        void handlesNullAndEmpty() {
            assertThat(SseTextCodec.unescape(null)).isEmpty();
            assertThat(SseTextCodec.unescape("")).isEmpty();
        }
    }

    @Test
    @DisplayName("转义必须幂等于语义：多轮往返仍与原值一致")
    void idempotentAcrossMultipleRounds() {
        String original = "a\\b\nc\rd\\ne";
        String current = original;
        for (int i = 0; i < 5; i++) {
            current = SseTextCodec.unescape(SseTextCodec.escape(current));
        }
        assertThat(current).isEqualTo(original);
    }
}
