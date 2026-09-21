package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受控工具集契约目录（决策 C-33）。
 *
 * <p><b>唯一来源</b>：{@code classpath:structured/tool-contracts.json}。
 * 本类在加载时做**契约自检**（fail fast）：工具名重复、缺 envelope、货种表为空等
 * 一律在启动/自检时抛异常，而不是等模型给出畸形参数才发现。
 *
 * <p><b>envelope 的 tool 枚举是派生的，不是抄的</b>：加载后把
 * {@code envelope.properties.tool.enum} 覆盖为「工具名 + none」。
 * 这样「契约里写了 5 个工具、枚举里却只列了 4 个」这类漂移在结构上不可能发生。
 */
public final class ToolCatalog {

    /** 契约资源路径（唯一来源）。 */
    public static final String CONTRACT_RESOURCE = "structured/tool-contracts.json";

    /** 与任何工具都不匹配时，模型应填的取值（用于「不属于工具集」的降级判定）。 */
    public static final String NONE_TOOL = "none";

    private static final String LF = String.valueOf((char) 10);

    private final String contractVersion;
    private final String note;
    private final JsonNode envelope;
    private final Map<String, ToolContract> tools;
    private final List<String> businessRules;
    private final Map<String, CargoInfo> taxonomy;

    /** 货种归类（货种 → 货类 + 是否危险货物）。与语料/种子数据一致，见 C-31 / C-36。 */
    public record CargoInfo(String cargoName, String category, boolean dangerous) {
    }

    private ToolCatalog(String contractVersion, String note, JsonNode envelope,
                        Map<String, ToolContract> tools, List<String> businessRules,
                        Map<String, CargoInfo> taxonomy) {
        this.contractVersion = contractVersion;
        this.note = note;
        this.envelope = envelope;
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.businessRules = List.copyOf(businessRules);
        this.taxonomy = Collections.unmodifiableMap(new LinkedHashMap<>(taxonomy));
    }

    /** 从 classpath 加载默认契约（资源缺失即抛，不静默降级）。 */
    public static ToolCatalog loadDefault() {
        ClassLoader cl = ToolCatalog.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到工具契约资源：" + CONTRACT_RESOURCE
                        + "（它必须随 jar 一起打包，缺了整条结构化通道的契约就没了）");
            }
            return fromJson(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("读取工具契约失败：" + CONTRACT_RESOURCE, e);
        }
    }

    /** 由已解析的 JSON 构建（供测试直接喂构造好的契约）。 */
    public static ToolCatalog fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("工具契约根节点必须是 JSON 对象");
        }
        String version = root.path("contractVersion").asText(null);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("工具契约缺 contractVersion");
        }
        JsonNode toolsNode = root.path("tools");
        if (!toolsNode.isArray() || toolsNode.isEmpty()) {
            throw new IllegalStateException("工具契约的 tools 必须是**非空**数组（3–5 个，见 C-33）");
        }
        Map<String, ToolContract> tools = new LinkedHashMap<>();
        for (JsonNode t : toolsNode) {
            ToolContract c = new ToolContract(
                    t.path("name").asText(null),
                    t.path("title").asText(""),
                    t.path("description").asText(null),
                    t.path("parameters"),
                    t.path("notes").asText(""));
            if (tools.putIfAbsent(c.name(), c) != null) {
                throw new IllegalStateException("工具名重复：" + c.name());
            }
        }
        if (tools.size() < 3 || tools.size() > 5) {
            throw new IllegalStateException("工具数量必须在 3–5 之间（决策 C-33），实际 " + tools.size());
        }

        JsonNode envelope = root.path("envelope").deepCopy();
        if (!envelope.isObject()) {
            throw new IllegalStateException("工具契约缺 envelope（模型的输出契约）");
        }
        // 派生 tool 枚举：工具名 + none —— 让「契约与枚举漂移」在结构上不可能
        Set<String> names = new LinkedHashSet<>(tools.keySet());
        names.add(NONE_TOOL);
        var enumArr = envelope.withObject("/properties").withObject("tool").putArray("enum");
        names.forEach(enumArr::add);

        List<String> rules = new ArrayList<>();
        for (JsonNode r : root.path("businessRules")) {
            rules.add(r.asText());
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException("业务规则（businessRules）为空：模型没有口径，会把危险品加收、重点保供折扣全部漏掉");
        }

        Map<String, CargoInfo> tax = new LinkedHashMap<>();
        for (JsonNode c : root.path("cargoTaxonomy")) {
            CargoInfo info = new CargoInfo(c.path("cargo_name").asText(), c.path("category").asText(),
                    c.path("is_dangerous").asBoolean(false));
            if (info.cargoName().isBlank() || info.category().isBlank()) {
                throw new IllegalStateException("货种表条目缺 cargo_name 或 category：" + c);
            }
            if (tax.putIfAbsent(info.cargoName(), info) != null) {
                throw new IllegalStateException("货种重复：" + info.cargoName());
            }
        }
        if (tax.isEmpty()) {
            throw new IllegalStateException("货种表（cargoTaxonomy）为空：模型无法把「成品油」映射到货类与危险品属性");
        }

        // 工具 Schema 的必填项不得为空对象却没有 required（query_waybill/query_vessel 允许空 required，但必须有 properties）
        for (ToolContract c : tools.values()) {
            if (!c.parameters().path("properties").isObject() || c.parameters().path("properties").isEmpty()) {
                throw new IllegalStateException("工具 " + c.name() + " 的 parameters 缺 properties");
            }
        }
        return new ToolCatalog(version, root.path("note").asText(""), envelope, tools, rules, tax);
    }

    public String contractVersion() {
        return contractVersion;
    }

    public String note() {
        return note;
    }

    /** 模型的输出契约（envelope），已把 tool 枚举按工具清单派生。 */
    public JsonNode envelope() {
        return envelope;
    }

    public Map<String, ToolContract> tools() {
        return tools;
    }

    public ToolContract tool(String name) {
        ToolContract c = name == null ? null : tools.get(name);
        if (c == null) {
            throw new IllegalArgumentException("未知工具：" + name + "（合法取值：" + tools.keySet() + " / " + NONE_TOOL + "）");
        }
        return c;
    }

    public boolean hasTool(String name) {
        return name != null && tools.containsKey(name);
    }

    public List<String> businessRules() {
        return businessRules;
    }

    public Map<String, CargoInfo> cargoTaxonomy() {
        return taxonomy;
    }

    public CargoInfo cargo(String cargoName) {
        return cargoName == null ? null : taxonomy.get(cargoName);
    }

    /** 系统提示词（把契约翻译成模型能照做的指令）。 */
    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是平陆运河航运物流业务助手 CanalMind 的「结构化查询参数抽取器」。").append(LF);
        sb.append("你的唯一任务：把用户问题映射成**一次**受控工具调用，并只输出一个 JSON 对象。").append(LF);
        sb.append(LF);
        sb.append("【输出契约】").append(LF);
        sb.append(envelope.toString()).append(LF);
        sb.append(LF);
        sb.append("【硬规定】").append(LF);
        sb.append("1. 只输出 JSON 对象本身：不要 Markdown 代码块、不要解释文字、不要多余键。").append(LF);
        sb.append("2. arguments 里**只放用户明确说出或能从问题唯一推算出的参数**；不要猜测、不要编造日期。").append(LF);
        sb.append("3. 必填参数在问题里没给出时：仍然选择最合适的工具，把参数名写进 missing 数组，不要编一个值。").append(LF);
        sb.append("4. 用户问题与任何工具都不匹配（闲聊、政策咨询、文档检索类）时：tool 填 \"none\"，arguments 填 {}。").append(LF);
        sb.append("5. 金额一律不要自己算——需要金额时选 quote_fee，由服务端确定性引擎算。").append(LF);
        sb.append("6. 日期一律 YYYY-MM-DD。用户说「今天」时以系统给出的业务当天为准。").append(LF);
        // ⚠️ 第 7 条是**实测补的**：首轮 Execution Accuracy 里 4/40 题答错，全是同一形态——
        // 用户已经说了货种（成品油/硫酸/烧碱/集装箱货物），模型却仍把「货类」放进 missing 去反问。
        // 原因是契约为 quote_fee 同时声明了 cargo_name 与 cargo_category，模型不确定"给了这个还要不要那个"。
        // ⇒ 在提示词里把"等价关系"写明：**货种蕴含货类**。
        sb.append("7. 用户说的是**货种**（如 成品油 / 烧碱 / 硫酸 / 集装箱货物）时，填 cargo_name —— "
                + "**不要再索要货类**：货类与危险品属性由货种推导。"
                + "只有用户说到的是**货类**（如 建材 / 化工品 / 能源）时才填 cargo_category。").append(LF);
        sb.append(LF);
        sb.append("【可用工具】").append(LF);
        for (ToolContract c : tools.values()) {
            sb.append(c.promptBlock(LF));
        }
        sb.append(LF);
        sb.append("【业务口径（务必遵守）】").append(LF);
        int i = 1;
        for (String r : businessRules) {
            sb.append(i++).append(". ").append(r).append(LF);
        }
        sb.append(LF);
        sb.append("【货种对照表（货种 → 货类 / 是否危险货物）】").append(LF);
        for (CargoInfo c : taxonomy.values()) {
            sb.append(c.cargoName()).append('=').append(c.category())
                    .append(c.dangerous() ? "/危险" : "/普通").append(' ');
        }
        sb.append(LF);
        return sb.toString();
    }

    /** 用户提示词（问题 + 业务当天）。 */
    public String userPrompt(String question, String businessToday) {
        return "业务当天：" + businessToday + LF + "用户问题：" + question + LF
                + "请输出一次工具调用的 JSON。";
    }
}
