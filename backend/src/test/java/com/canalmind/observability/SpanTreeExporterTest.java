package com.canalmind.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpanTreeExporter} 的测试（**C-54**，2026-09-22）。
 *
 * <p>断言的目标是**导出物本身**（JSON 文本/字节），而不是内部字段——因为这东西的
 * 唯一用途就是"让人看见树"，所以"树看没看见"必须由产物来回答。
 */
class SpanTreeExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ⛔ 不用 System.lineSeparator()，也不用转义字面量（{@code AGENTS.md} §7.2 第 2 条）。 */
    private static final String LF = String.valueOf((char) 10);

    /** ⚠️ 属性是**进程级**的，测试之间必须清掉，否则会污染同 JVM 里的其它用例。 */
    @AfterEach
    void clearExportProperty() {
        System.clearProperty(SpanTreeExporter.ENABLE_PROPERTY);
    }

    // ==================== ① 开关 ====================

    @Test
    @DisplayName("★ 默认关：不设属性时注册表**原样返回**（既有 573 例测试与所有 CLI 的默认行为零变化）")
    void disabledByDefault() {
        System.clearProperty(SpanTreeExporter.ENABLE_PROPERTY);
        ObservationRegistry registry = ObservationRegistry.create();

        assertThat(SpanTreeExporter.exportTarget()).isNull();
        assertThat(SpanTreeExporter.attachIfEnabled(registry))
                .as("关闭时必须返回**同一个**注册表实例——挂了 handler 就不再是它了")
                .isSameAs(registry);
    }

    @Test
    @DisplayName("显式开启：读得到目标路径，并把「要导出到哪」报到 stderr（否则跑完什么都没看见）")
    void enabledByPropertyAnnouncesTarget() {
        Path out = Path.of("target", "spans-export-probe", "spans.json");
        System.setProperty(SpanTreeExporter.ENABLE_PROPERTY, out.toString());
        assertThat(SpanTreeExporter.exportTarget()).isEqualTo(out.toAbsolutePath());

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            ObservationRegistry registry =
                    SpanTreeExporter.attachIfEnabled(ObservationRegistry.create());
            // 挂上之后照常能埋点（导出器必须对业务透明）
            AiObservation.start(AiSpans.AGENT_RUN, registry).observe(() -> { });
        } finally {
            System.setErr(original);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains("[OBS]")
                .contains("spans.json");
    }

    // ==================== ② 树形 ====================

    @Test
    @DisplayName("★ 导出的是**树**（三层嵌成三层），且属性按低/高基数分开落")
    void rendersNestedTreeWithSplitAttributes() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);

        Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
        root.observe(() -> {
            AiObservation.lowCardinality(root, AiSpans.KEY_INTENT_TYPE, "COMPOSITE");
            Observation retrieve = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
            retrieve.observe(() -> {
                AiObservation.highCardinality(
                        retrieve, AiSpans.KEY_KNOWLEDGE_BASE_ID, "canalmind-rules");
                AiObservation.start(AiSpans.DB_VECTOR_SEARCH, registry).observe(() -> { });
            });
        });

        JsonNode json = MAPPER.readTree(exporter.toJson());
        assertThat(json.get("spanCount").asInt()).isEqualTo(3);
        assertThat(json.get("unresolvedParentCount").asInt()).isZero();
        assertThat(json.get("roots").size()).as("必须只有一个根").isEqualTo(1);

        JsonNode rootNode = json.get("roots").get(0);
        assertThat(rootNode.get("name").asText()).isEqualTo(AiSpans.AGENT_RUN);
        JsonNode retrieveNode = rootNode.get("children").get(0);
        assertThat(retrieveNode.get("name").asText()).isEqualTo(AiSpans.RAG_RETRIEVE);
        assertThat(retrieveNode.get("children").get(0).get("name").asText())
                .as("三层必须真的嵌成三层——摊平成一张列表就看不出调用链，那等于没导出树")
                .isEqualTo(AiSpans.DB_VECTOR_SEARCH);
        assertThat(rootNode.get("lowCardinality").get(AiSpans.KEY_INTENT_TYPE).asText())
                .isEqualTo("COMPOSITE");
        assertThat(retrieveNode.get("highCardinality").get(AiSpans.KEY_KNOWLEDGE_BASE_ID).asText())
                .isEqualTo("canalmind-rules");
        assertThat(retrieveNode.get("lowCardinality").has(AiSpans.KEY_KNOWLEDGE_BASE_ID))
                .as("高基数键⛔ 不许混进低基数那一组（§7.3 的分层在产物里要看得见）")
                .isFalse();
    }

    @Test
    @DisplayName("★ 同名兄弟不塌成一条：两个同名的父各带 3 个同名子，各连各的（靠时间包含取最内层）")
    void repeatedNamesAttachToTheirOwnParent() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);

        Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
        root.observe(() -> {
            for (int i = 0; i < 2; i++) {
                Observation query = AiObservation.start(AiSpans.STRUCTURED_QUERY, registry);
                query.observe(() -> {
                    for (int j = 0; j < 3; j++) {
                        AiObservation.start(AiSpans.DB_SQL_EXECUTE, registry).observe(() -> { });
                    }
                });
            }
        });

        JsonNode json = MAPPER.readTree(exporter.toJson());
        assertThat(json.get("spanCount").asInt()).isEqualTo(1 + 2 + 6);
        JsonNode rootNode = json.get("roots").get(0);
        assertThat(rootNode.get("children").size()).isEqualTo(2);
        for (JsonNode query : rootNode.get("children")) {
            assertThat(query.get("name").asText()).isEqualTo(AiSpans.STRUCTURED_QUERY);
            assertThat(query.get("children").size())
                    .as("每个父只带**自己的** 3 个子；这里是 6 就说明按名字连边塌成了一坨")
                    .isEqualTo(3);
            for (JsonNode child : query.get("children")) {
                assertThat(child.get("name").asText()).isEqualTo(AiSpans.DB_SQL_EXECUTE);
            }
        }
        assertThat(json.get("unresolvedParentCount").asInt()).isZero();
    }

    @Test
    @DisplayName("⚠️ 父指针指到一个**已经结束**的父时不猜：如实标 parentMissing 并计数")
    void unresolvableParentIsReportedNotGuessed() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);

        Observation parent = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
        parent.observe(() -> { });
        // 观测 API 仍把 parent 报成父，但父的区间在子起点之前就结束了 ⇒ 包含关系不成立
        Observation child = AiObservation.start(AiSpans.DB_VECTOR_SEARCH, registry)
                .parentObservation(parent);
        child.observe(() -> { });

        JsonNode json = MAPPER.readTree(exporter.toJson());
        assertThat(json.get("unresolvedParentCount").asInt())
                .as("连不上父必须被**数出来**——否则读者会把它当成一个正常的根节点")
                .isEqualTo(1);
        JsonNode orphan = json.get("roots").get(1);
        assertThat(orphan.get("name").asText()).isEqualTo(AiSpans.DB_VECTOR_SEARCH);
        assertThat(orphan.get("parentMissing").asText())
                .as("要写出它**声称**的父是谁，⛔ 不是伪造一个父、也不是静默丢父名")
                .isEqualTo(AiSpans.RAG_RETRIEVE);
    }

    // ==================== ③ 出错与漏收尾 ====================

    @Test
    @DisplayName("★ 出错只记异常类型、**扣下消息正文**（消息里可能是 SQL 或用户问句原文）")
    void errorRecordsTypeButWithholdsTheMessage() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);
        String secret = "SELECT * FROM tariff_rule WHERE id=1";

        Observation failing = AiObservation.start(AiSpans.DB_SQL_EXECUTE, registry);
        assertThatThrownBy(() -> failing.observe(() -> {
            throw new IllegalStateException(secret);
        })).isInstanceOf(IllegalStateException.class);

        String json = exporter.toJson();
        JsonNode error = MAPPER.readTree(json).get("roots").get(0).get("error");
        assertThat(error.get("type").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(error.get("messageLength").asInt()).isEqualTo(secret.length());
        assertThat(error.get("messageWithheld").asBoolean())
                .as("要把「扣下消息」这件事**写在产物里**，免得读者以为导出器漏了字段")
                .isTrue();
        assertThat(json)
                .as("⚠️ 字符串级断言：消息原文一个字都不许出现在导出物里")
                .doesNotContain(secret)
                .doesNotContain("tariff_rule");
    }

    @Test
    @DisplayName("只 start 没 stop 的 Span 写 durationMillis=null（⛔ 不写 0——0 会被读成「瞬间完成」）")
    void unstoppedSpanRendersNullDuration() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);

        AiObservation.start(AiSpans.RAG_RETRIEVE, registry).start();

        JsonNode node = MAPPER.readTree(exporter.toJson()).get("roots").get(0);
        assertThat(node.get("durationMillis").isNull()).isTrue();
        assertThat(node.get("name").asText()).isEqualTo(AiSpans.RAG_RETRIEVE);
    }

    @Test
    @DisplayName("⛔ 导出器不许把业务搞挂：重复 stop、以及 stop 一个没 start 过的 context，都不许抛")
    void exporterNeverThrowsAtBusinessExpense() {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);

        Observation observation = AiObservation.start(AiSpans.AGENT_RUN, registry);
        observation.observe(() -> { });

        exporter.onStop(observation.getContext());
        exporter.onStop(AiObservation.start(AiSpans.AGENT_RUN, ObservationRegistry.create())
                .start().getContext());
        assertThat(exporter.spanCount()).as("两条多余的 stop 不得凭空多出 Span").isEqualTo(1);
    }

    // ==================== ④ 字节纪律 ====================

    @Test
    @DisplayName("★ 产物只有 LF、没有 BOM，且自动建父目录（Jackson 默认缩进器在 Windows 上是 CRLF）")
    void fileUsesLfOnlyWithoutBom(@TempDir Path tempDir) throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        SpanTreeExporter exporter = SpanTreeExporter.attach(registry);
        AiObservation.start(AiSpans.AGENT_RUN, registry).observe(() -> { });

        Path out = tempDir.resolve("deep").resolve("nested").resolve("spans.json");
        exporter.writeTo(out);

        byte[] bytes = Files.readAllBytes(out);
        assertThat((int) bytes[0]).as("⛔ 不许有 BOM").isNotEqualTo(0xEF);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).startsWith("{").contains(LF);
        assertThat(text.indexOf((char) 13))
                .as("CR 必须为 0：DefaultPrettyPrinter 的默认缩进器是 SYS_LF（Windows = CRLF），"
                        + "只换物体不换数组也会漏——本项目踩过这个坑")
                .isEqualTo(-1);
    }
}
