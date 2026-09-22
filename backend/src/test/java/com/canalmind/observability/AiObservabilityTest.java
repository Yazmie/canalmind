package com.canalmind.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务埋点的契约测试（规格书 §7.2/§7.3；知识库《AI 可观测性与 Trace…》第 55、202、448、451 行）。
 *
 * <p><b>这个测试类存在的核心理由</b>：本项目刚立下一条硬规定——「凡汇总句，必须能用一条
 * 能跑的命令当场证伪」（`AGENTS.md` §6 第 6 条）。而"规格书 §7.2 列了 16 个业务 Span、
 * 代码里全都定义了"正是一句典型的<b>汇总句</b>：它看起来像结论，却没人验证过。
 * 于是这里<b>直接读规格书原文</b>，把那份清单解析出来与 {@link AiSpans#ALL} 逐字比对——
 * 规格书改了而代码没跟上（或反过来），这个测试会红。
 *
 * <p>另外三组测试守护的是"闸门真的会拦"，而不是"我写了个 if"：
 * 每道闸门都配了<b>真实场景的反例</b>（中文问题原文、带换行的文档片段、未登记的键）。
 */
class AiObservabilityTest {

    // ==================== 一、规格书 §7.2 与代码的双向一致 ====================

    @Test
    @DisplayName("规格书 §7.2 列出的 Span 名单，与 AiSpans.ALL 逐字、逐序一致")
    void spanNamesMatchSpec() throws IOException {
        List<String> fromSpec = parseSpanNamesFromSpec();
        assertEquals(16, fromSpec.size(),
                "规格书 §7.2 的 Span 树应含 16 个名字；解析结果=" + fromSpec
                        + "（若规格书确实改了条数，请同步改本断言与 AiSpans）");
        assertEquals(fromSpec, AiSpans.ALL,
                "规格书 §7.2 与 AiSpans 不一致——埋点名必须与规格书逐字一致（含顺序）");
    }

    @Test
    @DisplayName("低基数与高基数键的白名单不相交，且都在规格书 §7.3 的两侧之内")
    void cardinalityKeySetsAreDisjointAndNonEmpty() {
        assertTrue(AiSpans.LOW_CARDINALITY_KEYS.size() >= 3, "规格书 §7.3 第 570 行点名了 3 个低基数键");
        assertTrue(AiSpans.HIGH_CARDINALITY_KEYS.size() >= 4, "规格书 §7.3 第 571 行列举了高基数一侧");
        for (String k : AiSpans.LOW_CARDINALITY_KEYS) {
            assertFalse(AiSpans.HIGH_CARDINALITY_KEYS.contains(k),
                    "键「" + k + "」同时出现在两一侧——它到底进不进 Metrics 将无法判断");
        }
        // 规格书 §7.3 第 570 行逐个点名
        assertTrue(AiSpans.LOW_CARDINALITY_KEYS.contains("ai.rag.strategy"));
        assertTrue(AiSpans.LOW_CARDINALITY_KEYS.contains("ai.rag.result"));
        assertTrue(AiSpans.LOW_CARDINALITY_KEYS.contains("ai.intent.type"));
        // 规格书 §7.3 第 571 行逐个点名
        assertTrue(AiSpans.HIGH_CARDINALITY_KEYS.contains("knowledge_base_id"));
        assertTrue(AiSpans.HIGH_CARDINALITY_KEYS.contains("ai.rag.result_count"));
    }

    @Test
    @DisplayName("语义约定版本已钉在埋点库里（知识库第 113 行要求）")
    void semanticConventionVersionIsPinned() {
        assertNotNull(AiSpans.SEMANTIC_CONVENTION_VERSION);
        assertTrue(AiSpans.SEMANTIC_CONVENTION_VERSION.contains("canalmind-spans/v1"),
                "版本号必须可被字符串匹配（升级时要靠它找出哪些 Span 需跟着改）");
        assertTrue(AiSpans.SEMANTIC_CONVENTION_VERSION.contains("Development"),
                "知识库第 111 行：OTel 的 GenAI/Agent 约定仍是 Development，这一事实必须留在版本串里");
    }

    // ==================== 二、三道闸门：反例必须真的被拒 ====================

    @Test
    @DisplayName("闸门①：未登记的键不能写进低基数位（防「在别处悄悄新增开放取值的键」）")
    void gate1RejectsUnregisteredLowCardinalityKey() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiObservation.lowCardinality(o, "ai.rag.user_question", "任意值"));
        assertTrue(e.getMessage().contains("未登记"), "报错要说清是「键没登记」而不是别的：" + e.getMessage());
    }

    @Test
    @DisplayName("闸门②：中文问题原文不能写进低基数位（知识库第 451、531–533 行明令禁止）")
    void gate2RejectsChineseQuestionInLowCardinalitySlot() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.INTENT_ROUTE, registry);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiObservation.lowCardinality(o, AiSpans.KEY_INTENT_TYPE,
                        "平陆运河的过闸费怎么算？"));
        assertTrue(e.getMessage().contains("不像封闭枚举"),
                "报错要指出取值形状不对，并说明为什么：" + e.getMessage());
    }

    @Test
    @DisplayName("闸门②补：低基数取值允许的封闭枚举必须真的能通过（闸门不能把正常用法也拦掉）")
    void gate2AllowsClosedEnumValues() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
        AiObservation.lowCardinality(o, AiSpans.KEY_RAG_STRATEGY, "VECTOR_ONLY");
        AiObservation.ragResult(o, false);
        AiObservation.lowCardinality(o, AiSpans.KEY_INTENT_TYPE, "STRUCTURED");
        o.stop();
    }

    @Test
    @DisplayName("闸门③：带换行的文档片段不能写进高基数位（那是正文，不是标识符）")
    void gate3RejectsMultilineDocumentText() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
        String docText = "第六条 过闸费按船舶总吨位计收" + (char) 10 + "（二）危险品加收 30%";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiObservation.highCardinality(o, AiSpans.KEY_DOCUMENT_IDS, docText));
        assertTrue(e.getMessage().contains("含换行"), "报错要点明「含换行 ⇒ 这几乎一定是正文」：" + e.getMessage());
    }

    @Test
    @DisplayName("闸门③补：超长的单行 Prompt 原文也不能写进高基数位")
    void gate3RejectsOverlongValue() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.LLM_GENERATE, registry);
        String longPrompt = "请根据以下资料回答".repeat(60);   // 单行、无换行、但远超上限
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiObservation.highCardinality(o, AiSpans.KEY_DOCUMENT_IDS, longPrompt));
        assertTrue(e.getMessage().contains("超过上限"), "报错要点明长度超限：" + e.getMessage());
    }

    @Test
    @DisplayName("闸门补充：低基数取值不允许为空（空值会让聚合看起来像另一个枚举值）")
    void gatesRejectEmptyValues() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
        assertThrows(IllegalArgumentException.class,
                () -> AiObservation.lowCardinality(o, AiSpans.KEY_RAG_RESULT, ""));
        assertThrows(IllegalArgumentException.class,
                () -> AiObservation.highCardinality(o, AiSpans.KEY_RAG_RESULT_COUNT, ""));
    }

    @Test
    @DisplayName("attempt 从 1 起：传 0 会被拒（知识库第 55 行，0 会与「未开始」混淆）")
    void attemptStartsAtOne() {
        ObservationRegistry registry = ObservationRegistry.create();
        Observation o = AiObservation.start(AiSpans.TOOL_PARAM_EXTRACT, registry);
        assertThrows(IllegalArgumentException.class, () -> AiObservation.attempt(o, 0));
        AiObservation.attempt(o, 1);
        AiObservation.attempt(o, 2);
        o.stop();
    }

    // ==================== 三、父子关系：实测 Micrometer 的真实语义 ====================

    @Test
    @DisplayName("嵌套用 observe(...) 时，子节点的父指针指向外层（§7.2 的树形结构靠它成立）")
    void nestedObservationsFormParentChildTree() {
        ObservationRegistry registry = ObservationRegistry.create();
        Capturing handler = new Capturing();
        registry.observationConfig().observationHandler(handler);

        AiObservation.start(AiSpans.AGENT_RUN, registry).observe(() ->
                AiObservation.start(AiSpans.INTENT_ROUTE, registry).observe(() ->
                        AiObservation.start(AiSpans.LLM_GENERATE, registry).observe(() -> "生成结果")));

        assertEquals(List.of(AiSpans.AGENT_RUN, AiSpans.INTENT_ROUTE, AiSpans.LLM_GENERATE),
                handler.startedNames, "三个 Span 都要真的启动过");
        assertEquals(AiSpans.AGENT_RUN, handler.parentOf(AiSpans.INTENT_ROUTE),
                "子 Span 的父必须是 ai.agent.run —— 这正是规格书 §7.2 那棵树的第 2 层");
        assertEquals(AiSpans.INTENT_ROUTE, handler.parentOf(AiSpans.LLM_GENERATE),
                "第三层必须挂第二层，而不是直接挂根（挂根会让「路由错了导致答错」无法归因）");
        assertNull(handler.parentOf(AiSpans.AGENT_RUN), "根 Span 不应有父节点");
    }

    @Test
    @DisplayName("⚠️ 只 start()/stop()（不开 scope）时父指针全为 null——这就是「一律用 observe」的原因")
    void bareStartDoesNotNest() {
        ObservationRegistry registry = ObservationRegistry.create();
        Capturing handler = new Capturing();
        registry.observationConfig().observationHandler(handler);

        Observation root = AiObservation.start(AiSpans.AGENT_RUN, registry);
        root.start();
        Observation child = AiObservation.start(AiSpans.INTENT_ROUTE, registry);
        child.start();
        child.stop();
        root.stop();

        // 实测（2026-09-22，见 docs/工程复盘/工程方法/观测埋点的父指针会静默丢失.md）：
        // Micrometer 的父指针是在子 Span start() 时从「当前观测」取的，而「当前观测」只有
        // openScope() 之后才存在 ⇒ 手工 start/stop 会得到一棵**全是 null 父**的平树，
        // 而且**不报任何错**。故业务埋点一律用 observe(...) / observeChecked(...)。
        assertNull(handler.parentOf(AiSpans.INTENT_ROUTE),
                "若这条断言变红，说明 Micrometer 改了父子语义——请复核全部埋点写法与那条复盘条目");
    }

    @Test
    @DisplayName("兄弟 Span 平级：结构化通道与检索通道都直接挂在根下（不串成一条链）")
    void siblingsShareTheSameParent() {
        ObservationRegistry registry = ObservationRegistry.create();
        Capturing handler = new Capturing();
        registry.observationConfig().observationHandler(handler);

        AiObservation.start(AiSpans.AGENT_RUN, registry).observe(() -> {
            AiObservation.start(AiSpans.STRUCTURED_QUERY, registry).observe(() -> "结构化");
            AiObservation.start(AiSpans.RAG_RETRIEVE, registry).observe(() -> "检索");
            return "作答";
        });

        assertEquals(AiSpans.AGENT_RUN, handler.parentOf(AiSpans.RAG_RETRIEVE),
                "检索通道必须挂在根下，而不是挂在结构化通道下（否则两通道会被误读成前后依赖）");
        assertEquals(AiSpans.AGENT_RUN, handler.parentOf(AiSpans.STRUCTURED_QUERY),
                "两个通道是平级兄弟（规格书 §7.2 的树里它们是同层）");
    }

    @Test
    @DisplayName("错误的 Span 会被记录成 error（规格书 §7.2 要求错误状态可查）")
    void errorIsRecorded() {
        ObservationRegistry registry = ObservationRegistry.create();
        Capturing handler = new Capturing();
        registry.observationConfig().observationHandler(handler);

        Observation o = AiObservation.start(AiSpans.LLM_GENERATE, registry);
        o.start();
        o.error(new IllegalStateException("模型超时"));
        o.stop();

        assertNotNull(handler.errorOf(AiSpans.LLM_GENERATE), "错误必须挂在该 Span 上");
    }

    // ==================== 四、NOOP 安全：没有观测环境时埋点不能改变行为 ====================

    @Test
    @DisplayName("传 ObservationRegistry.NOOP 时不抛异常、业务值原样返回（既有测试因此不会因埋点变红）")
    void noopRegistryIsSafe() {
        Observation noop = AiObservation.start(AiSpans.FEE_CALCULATE, ObservationRegistry.NOOP);
        assertTrue(noop.isNoop(), "NOOP 注册表产出的 Observation 应为 noop");
        AiObservation.lowCardinality(noop, AiSpans.KEY_RAG_RESULT, AiSpans.VALUE_RESULT_HIT);
        AiObservation.highCardinality(noop, AiSpans.KEY_DB_ROWS, "0");
        String result = noop.observe(() -> "业务返回值");
        assertEquals("业务返回值", result, "埋点不得改变返回值");
    }

    @Test
    @DisplayName("null 注册表会被明确拒绝，而不是等到运行期才 NPE")
    void nullRegistryIsRejected() {
        assertThrows(NullPointerException.class, () -> AiObservation.start(AiSpans.AGENT_RUN, null));
        assertThrows(NullPointerException.class,
                () -> AiObservation.start(null, ObservationRegistry.create()));
    }

    // ==================== 五、埋点入口唯一：不许绕过闸门写属性 ====================

    @Test
    @DisplayName("全仓库没有绕过闸门的裸调用（⛔ 直接调 lowCardinalityKeyValue / highCardinalityKeyValue）")
    void noBareKeyValueCallsOutsideTheGate() throws IOException {
        Path srcRoot = repoRoot().resolve("backend").resolve("src").resolve("main").resolve("java");
        assertTrue(Files.isDirectory(srcRoot), "找不到主源码目录：" + srcRoot);
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcRoot)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("AiObservation.java")) {
                    continue;   // 闸门自己当然要用底层 API
                }
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains(".lowCardinalityKeyValue(") || line.contains(".highCardinalityKeyValue(")) {
                        offenders.add(srcRoot.relativize(p) + ":" + (i + 1) + " → " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "有埋点绕过了 AiObservation 的基数闸门（规格书 §7.3 会因此形同虚设）：" + offenders);
    }

    // ==================== 辅助 ====================

    /**
     * 从规格书 §7.2 的代码块里解析 Span 名单。
     *
     * <p>⚠️ <b>刻意不用行号定位</b>：本项目已实测过"行号当天即失效"（同一会话内漂两次，
     * 见 `docs/工程复盘/工程方法/` 的锚点约定）。这里改为按<b>小节标题</b>定位，
     * 再取该标题后的第一个围栏代码块——规格书在标题之后插入文字不会让它失效。
     */
    private static List<String> parseSpanNamesFromSpec() throws IOException {
        Path spec = findSpec();
        List<String> lines = Files.readAllLines(spec, StandardCharsets.UTF_8);
        int heading = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("### 7.2 ")) {
                heading = i;
                break;
            }
        }
        assertTrue(heading >= 0, "规格书里找不到「### 7.2 」小节，无法核对 Span 名单：" + spec);

        List<String> names = new ArrayList<>();
        boolean inBlock = false;
        for (int i = heading + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.trim().startsWith("```")) {
                if (inBlock) {
                    break;          // 第一个代码块结束
                }
                inBlock = true;
                continue;
            }
            if (!inBlock) {
                continue;
            }
            // 剥掉树形连接符（├ └ │ ─）与缩进，取第一个空白前的 token 作为 Span 名
            String cleaned = line.replaceAll("^[\\s\\u2502\\u251C\\u2514\\u2500]+", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            names.add(cleaned.split("\\s+")[0]);
        }
        return names;
    }

    /** 定位规格书原文（按文件名前缀匹配，避免把长文件名硬编码进测试）。 */
    private static Path findSpec() throws IOException {
        Path docs = repoRoot().resolve("docs");
        try (Stream<Path> files = Files.list(docs)) {
            return files.filter(p -> p.getFileName().toString().startsWith("平陆运河")
                            && p.getFileName().toString().endsWith(".md"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("docs/ 下找不到规格书（平陆运河*.md）：" + docs));
        }
    }

    /**
     * 向上找到同时含 {@code docs/} 与 {@code backend/} 的仓库根。
     *
     * <p>⚠️ <b>为什么不直接用相对路径</b>：本项目实测踩过"同一个相对路径解析到两个目录"
     * （`Set-Location` 只改 PowerShell 当前位置，而 JVM 用进程工作目录）。
     * 这里由"向上探测特征"决定，故从 `backend/` 或从仓库根运行都能命中同一个根。
     */
    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isDirectory(p.resolve("docs")) && Files.isDirectory(p.resolve("backend"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
                "找不到仓库根（应含 docs/ 与 backend/）。当前工作目录 = " + Paths.get("").toAbsolutePath());
    }

    /** 捕获 Span 的启动/停止/错误与父指针，供断言——不依赖任何导出后端。 */
    private static final class Capturing implements ObservationHandler<Observation.Context> {

        final List<String> startedNames = new ArrayList<>();
        private final Map<String, String> parents = new LinkedHashMap<>();
        private final Map<String, Throwable> errors = new LinkedHashMap<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            startedNames.add(context.getName());
            // ⚠️ 父节点要从 getContextView() 取名字：ObservationView 本身没有 getName()
            parents.put(context.getName(), context.getParentObservation() == null
                    ? null
                    : context.getParentObservation().getContextView().getName());
        }

        @Override
        public void onError(Observation.Context context) {
            errors.put(context.getName(), context.getError());
        }

        String parentOf(String name) {
            return parents.get(name);
        }

        Throwable errorOf(String name) {
            return errors.get(name);
        }
    }

    /** 保证 {@link KeyValues} 的导入被用上（低基数与高基数键值集合是两条独立通道）。 */
    @Test
    @DisplayName("低基数与高基数属性落在两个不同的集合里（这正是「只进 Trace」的落地方式）")
    void lowAndHighCardinalityKeysLandInSeparateSets() {
        ObservationRegistry registry = ObservationRegistry.create();
        Capturing handler = new Capturing();
        registry.observationConfig().observationHandler(handler);
        Observation o = AiObservation.start(AiSpans.RAG_RETRIEVE, registry);
        AiObservation.lowCardinality(o, AiSpans.KEY_RAG_STRATEGY, "VECTOR_ONLY");
        AiObservation.highCardinality(o, AiSpans.KEY_RAG_RESULT_COUNT, "30");
        o.start();
        Observation.Context ctx = o.getContext();
        o.stop();

        KeyValues low = ctx.getLowCardinalityKeyValues();
        KeyValues high = ctx.getHighCardinalityKeyValues();
        assertEquals("VECTOR_ONLY", valueOf(low, AiSpans.KEY_RAG_STRATEGY));
        assertFalse(containsKey(low, AiSpans.KEY_RAG_RESULT_COUNT),
                "高基数键不得出现在低基数集合里——否则它会进 Metrics");
        assertEquals("30", valueOf(high, AiSpans.KEY_RAG_RESULT_COUNT));
        assertFalse(containsKey(high, AiSpans.KEY_RAG_STRATEGY),
                "低基数键不必占用高基数位（会白白放大 Trace 体积）");
        assertFalse(handler.startedNames.isEmpty());
    }

    /**
     * 判断键值集合里有没有某个键。
     *
     * <p>⚠️ <b>为什么不写 {@code keyValues.get(key)}</b>：{@code io.micrometer.common.KeyValues}
     * <b>没有 {@code get(String)} 方法</b>（实测：它只实现 {@link Iterable} 并给出
     * {@code stream()}）。这里按 Iterable 遍历，既避免误用 API，也不依赖"缺失时返回
     * null 还是 {@code KeyValue.NONE}"这种未核实的语义。
     */
    private static boolean containsKey(KeyValues keyValues, String key) {
        for (KeyValue kv : keyValues) {
            if (kv.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 取键值集合里某个键的值；不存在时返回 {@code null}（调用点的断言会给出可读的失败信息）。 */
    private static String valueOf(KeyValues keyValues, String key) {
        for (KeyValue kv : keyValues) {
            if (kv.getKey().equals(key)) {
                return kv.getValue();
            }
        }
        return null;
    }
}
