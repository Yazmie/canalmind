package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.canalmind.rag.retrieve.LlmRerankerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution Accuracy 的**取数驱动**（第 3 周任务 6）：跑真实链路（真库 + 真模型），把原始结果落盘。
 *
 * <p>⚠️ 三个刻意的设计决定：
 * <ol>
 *   <li><b>这个类不判分。</b>它只负责"跑 + 落盘"，判据（金额到分相等、聚合逐行相等、缺参看是否追问）
 *       只有一份实现，在 {@code backend/scripts/eval_execution_accuracy.py} 里——否则判分逻辑
 *       会在 Java 与 Python 两侧漂移，报告的数字就没人能复核了。</li>
 *   <li><b>默认不跑。</b>它要真连 DeepSeek（花钱、要网）和真库，故用环境变量
 *       {@code CANALMIND_EA_RUN=1} 显式放行；日常 {@code mvn test} 会跳过它（否则离线/无网即红）。
 *       可选 {@code CANALMIND_EA_LIMIT=N} 只跑前 N 条做冒烟。</li>
 *   <li><b>产物钉死字节</b>：键排序、LF、无 BOM，并记录所测数据集的 sha256——
 *       否则"这次 62.5%、上次 60%"这种对比无法证明两次测的是同一份题。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "CANALMIND_EA_RUN", matches = "1")
class ExecutionAccuracyRunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GOLDEN = Path.of("..", "docs", "评测报告", "golden-set-v3.json");
    private static final Path OUT = Path.of("target", "eval", "ea-results.json");
    private static final String LF = String.valueOf((char) 10);

    @Test
    @DisplayName("跑 L1 金标准集，落盘原始结果（判分在 Python 侧）")
    void runL1GoldenSet() throws Exception {
        assertTrue(Files.exists(GOLDEN), "缺少金标准集：" + GOLDEN.toAbsolutePath());

        JsonNode golden = MAPPER.readTree(Files.readAllBytes(GOLDEN));
        String sha = sha256(Files.readAllBytes(GOLDEN));

        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : golden.path("items")) {
            if ("L1".equals(item.path("layer").asText())) {
                items.add(item);
            }
        }
        assertTrue(items.size() >= 20, "L1 题量过少，疑似读错文件：" + items.size());

        String limitRaw = System.getenv("CANALMIND_EA_LIMIT");
        if (limitRaw != null && !limitRaw.isBlank()) {
            int limit = Integer.parseInt(limitRaw.trim());
            items = items.subList(0, Math.min(limit, items.size()));
        }

        ToolCatalog catalog = ToolCatalog.loadDefault();
        StructuredQueryService service = buildService(catalog);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("mode", "execution-accuracy-raw");
        out.put("businessToday", StructuredQueryCli.BUSINESS_TODAY);
        out.put("contractVersion", catalog.contractVersion());
        ObjectNode src = out.putObject("dataset");
        src.put("path", GOLDEN.toString().replace('\\', '/'));
        src.put("sha256", sha);
        src.put("l1Items", items.size());
        ArrayNode results = out.putArray("results");

        int n = 0;
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            String question = item.path("question").asText();
            long started = System.nanoTime();
            ToolResult r;
            String error = null;
            try {
                r = service.ask(question);
            } catch (RuntimeException e) {
                r = null;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long ms = (System.nanoTime() - started) / 1_000_000L;
            n++;
            System.out.println("[" + n + "/" + items.size() + "] " + id + " " + ms + "ms → "
                    + (r == null ? "异常 " + error : r.status() + " / " + r.tool()));

            ObjectNode node = results.addObject();
            node.put("id", id);
            node.put("question", question);
            node.put("latencyMs", ms);
            if (r == null) {
                node.put("status", "EXCEPTION");
                node.put("error", error);
                continue;
            }
            node.put("status", r.status());
            if (r.degradeKind() != null) {
                node.put("degradeKind", r.degradeKind());
            }
            if (r.tool() != null) {
                node.put("tool", r.tool());
            }
            if (r.question() != null) {
                node.put("clarification", r.question());
            }
            ArrayNode cols = node.putArray("columns");
            r.columns().forEach(cols::add);
            ArrayNode rows = node.putArray("rows");
            for (List<String> row : r.rows()) {
                ArrayNode rr = rows.addArray();
                row.forEach(rr::add);
            }
            ArrayNode facts = node.putArray("facts");
            r.facts().forEach(facts::add);
            ArrayNode traces = node.putArray("traces");
            r.traces().forEach(traces::add);
        }

        Files.createDirectories(OUT.getParent());
        String text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out) + LF;
        // ⚠️ Windows 上 Jackson 的默认缩进器就是 CRLF（见 AGENTS §7.2）——这里统一收成 LF，保证字节可复现
        text = text.replace(String.valueOf((char) 13) + LF, LF).replace(String.valueOf((char) 13), LF);
        Files.write(OUT, text.getBytes(StandardCharsets.UTF_8));

        assertEquals(items.size(), results.size());
        System.out.println(LF + "原始结果：" + OUT.toAbsolutePath()
                + "（数据集 sha256=" + sha.substring(0, 12) + "…）");
        System.out.println("下一步：python backend\\scripts\\eval_execution_accuracy.py");
    }

    /** 与 CLI 同源装配（复用其数据源与工厂方法，避免两套装配漂移）。 */
    private static StructuredQueryService buildService(ToolCatalog catalog) {
        var env = LlmRerankerFactory.environmentWithDotEnv(List.of(Path.of(".env"), Path.of("../.env")));
        var dataSource = StructuredQueryCli.dataSource(env);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SchemaGate gate = SchemaGate.of(catalog);
        String maxTokensRaw = env.getOrDefault("CANALMIND_STRUCTURED_MAX_TOKENS", "1024");
        int maxTokens = Integer.parseInt(maxTokensRaw.trim());
        JsonChatInvoker invoker = new DeepSeekJsonInvoker(
                LlmRerankerFactory.chatModelFromEnvironment(env, maxTokens), maxTokens);
        ToolArgumentExtractor extractor = new ToolArgumentExtractor(
                catalog, gate, invoker, ToolArgumentExtractor.DEFAULT_MAX_RETRIES,
                StructuredQueryCli.BUSINESS_TODAY);
        return new StructuredQueryService(catalog, extractor,
                new TariffRuleRepository(jdbc), new StructuredQueryRepository(jdbc),
                new FeeCalculationEngine(), StructuredQueryCli.BUSINESS_TODAY);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
