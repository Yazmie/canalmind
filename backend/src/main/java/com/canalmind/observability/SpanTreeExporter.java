package com.canalmind.observability;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 §7.2 的 Span 树**导成 JSON** 的自写导出器（**C-54** / 裁示 **D15**，2026-09-22）。
 *
 * <h2>为什么不接真 OTLP（如实记一笔）</h2>
 *
 * <p>规格书第 661 行要求"接一个 OTLP 导出器 + 8 个集成测试场景"。真接要新增
 * {@code micrometer-tracing} / {@code micrometer-tracing-bridge-otel} /
 * {@code opentelemetry-exporter-otlp} 三组依赖（本机 {@code .m2repo} 里
 * {@code micrometer-tracing-*} 目前是 <b>0 个 jar</b>），而本项目是
 * {@code mvn -o} 的**离线构建** ⇒ 需要额外同步约 1GB 依赖。
 * 裁示选择了**自写导出器**这条路：零新依赖、离线构建不受影响、能真的看见树。
 * ⛔ <b>因此第 661 行那一项仍未完成、不得勾选</b>——本类不是 OTLP 导出器，
 * 它把树写成一个本地文件。
 *
 * <h2>默认关（这决定了它不改变任何既有行为）</h2>
 *
 * <p>只有显式设了 {@code -Dcanalmind.observability.export=<文件路径>} 才挂
 * （见 {@link #attachIfEnabled(ObservationRegistry)}）。不设时**连
 * {@link ObservationHandler} 都不注册**，注册表原样返回 ⇒ 既有的 573 例测试与
 * 所有 CLI 的默认行为**零变化**。
 *
 * <h2>为什么可以逐字导出属性（不再自己过滤一遍）</h2>
 *
 * <p>§7.3 的闸门（{@link AiObservation}）已在**写入侧**保证属性里不会出现
 * 原始问句、文档正文、提示词原文，本类因此可以放心逐字导出。⛔ 刻意**不**在这里
 * 再过滤一次：两处各过滤一次会让人以为闸门不管用，真正的边界也就没人知道了。
 *
 * <h2>⚠️ 父指针是怎么连的（以及它的局限）</h2>
 *
 * <p>实测（{@code javap}）：{@code Observation.Context.getParentObservation()} 返回的是
 * {@code ObservationView}，而该接口**只有** {@code getContextView()} /
 * {@code getObservationRegistry()} / {@code getEnclosingScope()} 三个方法
 * ——<b>拿不到父的 {@code Context} 对象</b>，所以<b>无法用对象身份连边</b>。
 * 本类改为「**父名 + 时间包含 + 取最内层**」重建父子关系：
 * 父的区间必须包含子的起点，多个同名候选时取**最内层**（起点最晚的那个）。
 *
 * <p>为此每个 Span 都记 {@code System.nanoTime()}：<b>不能用毫秒</b>——
 * 本项目里 {@code ai.rag.retrieve → db.vector.search} 这类嵌套常常落在同一毫秒内，
 * 毫秒分辨率下"取最内层"只能靠猜。
 *
 * <p>⚠️ <b>已知局限</b>：同名的两个兄弟若<b>时间上重叠</b>（并行执行）会连错。
 * 本项目编排是串行的（复合题是串行两步），故当前实测树与 §7.2 一致；
 * 一旦引入并行通道，这里的算法要先改。
 *
 * <p>连不上父的 Span <b>不猜</b>：如实写成根节点并带上 {@code parentMissing}
 * 字段（父名）——"父指针静默丢失"正是知识库第 306/316 行警告的症状，
 * 它必须**看得见**（另有 {@code AiObservationThreadPropagationTest} 守护跨线程那一面）。
 *
 * <h2>⛔ 导出器不得改变业务</h2>
 *
 * <p>{@link #onStart} / {@link #onStop} 内部**任何异常都被吞掉**并打到 stderr：
 * 若从这里抛出去，会顺着 {@code observation.observe(...)} 冒到业务调用点，
 * 那就是"埋点把业务搞挂了"（本项目最忌讳的一类事故）。
 *
 * <h2>换行纪律</h2>
 *
 * <p>Jackson 的 {@code DefaultPrettyPrinter} 默认缩进器是 {@code DefaultIndenter.SYS_LF}
 * （Windows 上 = CRLF）。本项目要求产物**只用 LF**，故物体与数组**都要**显式换缩进器
 * （只换物体、不换数组是踩过的坑，见 {@code ChunkManifestCli} 第 115 行那段注释）。
 *
 * <h2>⛔ 异常消息不落进导出物</h2>
 *
 * <p>Span 的 error 只记**异常类型名与消息长度**，⛔ 不记消息正文：异常消息里
 * 可能是 SQL 片段或用户问句原文，这与 §7.3"不把原文写进属性"是同一条纪律。
 * 消息正文仍照旧由调用方打到日志/控制台。
 */
public final class SpanTreeExporter implements ObservationHandler<Observation.Context> {

    /** 显式开启用的系统属性，值是输出文件路径。⛔ 不设 = 完全不挂。 */
    public static final String ENABLE_PROPERTY = "canalmind.observability.export";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ⛔ 不用 System.lineSeparator()，也不用转义字面量（{@code AGENTS.md} §7.2 第 2 条）。 */
    private static final String LF = String.valueOf((char) 10);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private static final DefaultPrettyPrinter PRINTER = lfPrettyPrinter();

    private static DefaultPrettyPrinter lfPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter lfIndenter = new DefaultIndenter("  ", LF);
        printer.indentObjectsWith(lfIndenter);
        printer.indentArraysWith(lfIndenter);
        return printer;
    }

    /** 按**启动顺序**追加（{@code synchronizedList} 的 add 是原子的 ⇒ 下标即启动次序）。 */
    private final List<Span> spans = Collections.synchronizedList(new ArrayList<>());

    /** ⚠️ 必须按**对象身份**查表：同一个 {@code Context} 在 onStart/onStop 是同一个对象。 */
    private final Map<Observation.Context, Span> byContext =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private final AtomicLong sequence = new AtomicLong();

    /**
     * 显式挂到某个注册表上，并返回导出器本身（调用方自己决定何时 {@link #writeTo(Path)}）。
     *
     * <p>这是**没有副作用**的那一半：不读系统属性、不注册关机钩子。
     */
    public static SpanTreeExporter attach(ObservationRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        SpanTreeExporter exporter = new SpanTreeExporter();
        registry.observationConfig().observationHandler(exporter);
        return exporter;
    }

    /**
     * 属性 {@link #ENABLE_PROPERTY} 指定的导出路径；**未设或空 ⇒ 返回 {@code null}**（= 关闭）。
     *
     * <p>单独抽成一个纯函数是为了**可测**：挂上之后的落盘发生在 JVM 退出的关机钩子里，
     * 进程内断言不到文件内容（测试改成显式 {@link #attach} 后用 {@link #writeTo(Path)} 验内容），
     * 能当场断言的恰好就是"属性读没读到、读成了什么"。
     */
    public static Path exportTarget() {
        String target = System.getProperty(ENABLE_PROPERTY);
        return target == null || target.isBlank() ? null : Path.of(target.trim()).toAbsolutePath();
    }

    /**
     * 只有显式设了 {@link #ENABLE_PROPERTY} 才挂；否则**原样返回**注册表。
     *
     * <p>挂上时同时在 JVM 退出前把树落盘（跑 CLI 的人不必自己调 {@link #writeTo}）。
     * 落盘失败只打 stderr，⛔ 不影响退出码——一个观测设施不该让命令失败。
     */
    public static ObservationRegistry attachIfEnabled(ObservationRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Path path = exportTarget();
        if (path == null) {
            return registry;
        }
        SpanTreeExporter exporter = attach(registry);
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> exporter.flushQuietly(path), "canalmind-span-export"));
        System.err.println("[OBS] Span 树将在 JVM 退出时导出到 " + path
                + "（属性 " + ENABLE_PROPERTY + "）");
        return registry;
    }

    /** 已记录的 Span 条数（含尚未 stop 的）。 */
    public int spanCount() {
        return spans.size();
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    @Override
    public void onStart(Observation.Context context) {
        try {
            Span span = new Span(
                    sequence.incrementAndGet(),
                    context.getName(),
                    parentNameOf(context),
                    System.nanoTime(),
                    System.currentTimeMillis(),
                    Thread.currentThread().getName());
            spans.add(span);
            byContext.put(context, span);
        } catch (RuntimeException e) {
            warn("onStart 记录 Span 失败（已忽略，业务不受影响）", e);
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        try {
            Span span = byContext.remove(context);
            if (span == null) {
                // 没配过 onStart 的上下文（例如注册表在观测途中才被挂上）——如实跳过。
                return;
            }
            Map<String, String> low = new LinkedHashMap<>();
            for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                low.put(kv.getKey(), kv.getValue());
            }
            Map<String, String> high = new LinkedHashMap<>();
            for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                high.put(kv.getKey(), kv.getValue());
            }
            Throwable error = context.getError();
            span.finish(
                    System.nanoTime(),
                    low,
                    high,
                    error == null ? null : error.getClass().getName(),
                    error == null || error.getMessage() == null ? -1 : error.getMessage().length());
        } catch (RuntimeException e) {
            warn("onStop 收尾 Span 失败（已忽略，业务不受影响）", e);
        }
    }

    /** 渲染成 JSON 文本（LF 换行、UTF-8 无 BOM 的字面量版本）。 */
    public String toJson() {
        try {
            return MAPPER.writer(PRINTER).writeValueAsString(render());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 走到这里说明内存里的属性文档结构出了问题——⛔ 不抛，返回一份最小可读的说明。
            warn("渲染 Span 树失败", e);
            return "{\"error\":\"render-failed\"}" + LF;
        }
    }

    /** 写到一个文件（自动建父目录；UTF-8 **无 BOM**，换行 LF）。 */
    public void writeTo(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Files.write(absolute, toJson().getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 内部：连边与渲染 ====================

    /**
     * 从注册表里已有的上下文取父名。
     *
     * <p>⚠️ 父是 {@code ObservationView}（实测），只能取名字；父指针**为 null 就是根**，
     * 这里如实保留 null（⛔ 不伪造一个根的名字）。
     */
    private static String parentNameOf(Observation.Context context) {
        var parent = context.getParentObservation();
        return parent == null ? null : parent.getContextView().getName();
    }

    private ObjectNode render() {
        List<Span> all;
        synchronized (spans) {
            all = new ArrayList<>(spans);
        }
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) {
            byName.computeIfAbsent(all.get(i).name, key -> new ArrayList<>()).add(i);
        }
        int[] parent = new int[all.size()];
        String[] missing = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            parent[i] = -1;
            Span self = all.get(i);
            if (self.parentName == null || self.parentName.isBlank()) {
                continue;
            }
            int innermost = -1;
            for (int candidate : byName.getOrDefault(self.parentName, List.of())) {
                if (candidate == i || !contains(all.get(candidate), self)) {
                    continue;
                }
                if (innermost < 0 || startsBefore(all.get(innermost), all.get(candidate))) {
                    innermost = candidate;
                }
            }
            if (innermost >= 0) {
                parent[i] = innermost;
            } else {
                // ⛔ 不猜一个父：父名对不上任何包含它的同名 Span，就如实标出来。
                missing[i] = self.parentName;
            }
        }
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            children.add(new ArrayList<>());
        }
        int unresolved = 0;
        for (int i = 0; i < all.size(); i++) {
            if (parent[i] >= 0) {
                children.get(parent[i]).add(i);
            } else if (missing[i] != null) {
                unresolved++;
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("convention", AiSpans.SEMANTIC_CONVENTION_VERSION);
        root.put("exportedAt", TIMESTAMP.format(Instant.now()));
        root.put("spanCount", all.size());
        root.put("unresolvedParentCount", unresolved);
        ArrayNode roots = root.putArray("roots");
        for (int i = 0; i < all.size(); i++) {
            if (parent[i] < 0) {
                roots.add(toNode(all, children, i, missing[i]));
            }
        }
        return root;
    }

    /** 候选父的区间是否包含子的**起点**（父的终点未知时按"仍在跑"处理）。 */
    private static boolean contains(Span candidate, Span child) {
        long candidateEnd = candidate.stopNanos >= 0 ? candidate.stopNanos : Long.MAX_VALUE;
        return candidate.startNanos <= child.startNanos && candidateEnd >= child.startNanos;
    }

    private static boolean startsBefore(Span a, Span b) {
        return a.startNanos < b.startNanos
                || (a.startNanos == b.startNanos && a.sequence < b.sequence);
    }

    private ObjectNode toNode(List<Span> all, List<List<Integer>> children, int index,
                              String parentMissing) {
        Span span = all.get(index);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", span.name);
        node.put("thread", span.threadName);
        node.put("startMillis", span.startMillis);
        if (span.stopNanos >= 0) {
            node.put("durationMillis", (span.stopNanos - span.startNanos) / 1_000_000.0);
        } else {
            // ⚠️ 只 start 没 stop（观察泄漏）如实写 null，⛔ 不写成 0 —— 0 会被读成"瞬间完成"。
            node.putNull("durationMillis");
        }
        if (parentMissing != null) {
            node.put("parentMissing", parentMissing);
        }
        ObjectNode low = node.putObject("lowCardinality");
        span.lowCardinality.forEach(low::put);
        ObjectNode high = node.putObject("highCardinality");
        span.highCardinality.forEach(high::put);
        if (span.errorType == null) {
            node.putNull("error");
        } else {
            ObjectNode error = node.putObject("error");
            error.put("type", span.errorType);
            error.put("messageLength", span.errorMessageLength);
            // 让读者一眼看见"消息是被**刻意**扣下的"，免得以为导出器漏了字段。
            error.put("messageWithheld", true);
        }
        ArrayNode kids = node.putArray("children");
        for (int child : children.get(index)) {
            kids.add(toNode(all, children, child, null));
        }
        return node;
    }

    private void flushQuietly(Path path) {
        try {
            writeTo(path);
            System.err.println("[OBS] Span 树已导出：" + path + "（" + spanCount() + " 个 Span）");
        } catch (IOException | RuntimeException e) {
            warn("导出 Span 树到 " + path + " 失败", e);
        }
    }

    private static void warn(String message, Throwable cause) {
        System.err.println("[WARN] " + message + "：" + cause);
    }

    /** 一条 Span（字段可被 onStop 在别的线程改写，故 stopNanos 之后的那几个是 volatile）。 */
    private static final class Span {

        private final long sequence;
        private final String name;
        private final String parentName;
        private final long startNanos;
        private final long startMillis;
        private final String threadName;

        private volatile long stopNanos = -1;
        private volatile Map<String, String> lowCardinality = Map.of();
        private volatile Map<String, String> highCardinality = Map.of();
        private volatile String errorType;
        private volatile int errorMessageLength = -1;

        Span(long sequence, String name, String parentName, long startNanos, long startMillis,
             String threadName) {
            this.sequence = sequence;
            this.name = name;
            this.parentName = parentName;
            this.startNanos = startNanos;
            this.startMillis = startMillis;
            this.threadName = threadName;
        }

        void finish(long stopNanos, Map<String, String> lowCardinality,
                    Map<String, String> highCardinality, String errorType, int errorMessageLength) {
            this.lowCardinality = lowCardinality;
            this.highCardinality = highCardinality;
            this.errorType = errorType;
            this.errorMessageLength = errorMessageLength;
            // 最后写 stopNanos：渲染侧以"stopNanos 已知"判断这条 Span 是否收尾完成。
            this.stopNanos = stopNanos;
        }
    }
}
