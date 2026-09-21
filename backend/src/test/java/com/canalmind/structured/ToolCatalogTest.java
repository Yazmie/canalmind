package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约目录的测试：**契约本身就是交付物**（C-33），所以它的自检要像代码一样被测。
 *
 * <p>重点守三件事：①envelope 的工具枚举**派生**自工具清单（不许手写漂移）；
 * ②契约缺件时**启动即失败**，而不是等模型给出畸形参数；③提示词里必须带上
 * 口径与货种表（缺了它模型会漏掉危险品加收/重点保供折扣，而那不会报错）。
 */
class ToolCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("默认契约：5 个工具、版本非空、货种 21 种")
    void loadsDefaultContract() {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        assertEquals(5, catalog.tools().size(), "C-33 定的是 3–5 个工具，当前为 5");
        assertTrue(catalog.tools().keySet().containsAll(
                List.of("query_tariff", "quote_fee", "query_traffic", "query_waybill", "query_vessel")));
        assertFalse(catalog.contractVersion().isBlank(), "契约版本不得为空：产物要记录它");
        assertEquals(21, catalog.cargoTaxonomy().size());
        assertTrue(catalog.businessRules().size() >= 5);
    }

    @Test
    @DisplayName("envelope 的 tool 枚举由工具清单派生（含 none），不靠手写")
    void envelopeEnumIsDerived() {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        List<String> values = new ArrayList<>();
        catalog.envelope().path("properties").path("tool").path("enum").forEach(n -> values.add(n.asText()));
        assertTrue(values.containsAll(catalog.tools().keySet()), "枚举必须覆盖全部工具名");
        assertTrue(values.contains(ToolCatalog.NONE_TOOL), "枚举必须含 none（不属于工具集时用）");
        assertEquals(catalog.tools().size() + 1, values.size(), "枚举 = 工具名 + none，不应有多余项");
    }

    @Test
    @DisplayName("提示词必须带上工具说明、业务口径与货种表")
    void systemPromptCarriesContract() {
        String prompt = ToolCatalog.loadDefault().systemPrompt();
        for (String tool : List.of("query_tariff", "quote_fee", "query_traffic", "query_waybill", "query_vessel")) {
            assertTrue(prompt.contains(tool), "提示词缺工具 " + tool);
        }
        assertTrue(prompt.contains("加收 30%"), "缺危险货物加收口径：模型会漏掉 1.30 系数");
        assertTrue(prompt.contains("70%"), "缺重点保供口径：模型会漏掉 0.70 系数");
        assertTrue(prompt.contains("船舶总吨位"), "缺吨位口径：引航费/停泊费会被按货重算");
        assertTrue(prompt.contains("成品油"), "缺货种表：模型无法把「成品油」映射到货类与危险品属性");
        assertTrue(prompt.contains("json") || prompt.contains("JSON"), "JSON Mode 要求提示词出现 json 字样");
    }

    @Test
    @DisplayName("货种 → 货类/危险品 推导（金额口径的关键输入）")
    void cargoTaxonomyResolvesDangerous() {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        assertEquals("能源", catalog.cargo("成品油").category());
        assertTrue(catalog.cargo("成品油").dangerous(), "成品油是危险货物");
        assertFalse(catalog.cargo("动力煤").dangerous(), "动力煤属能源但非危险货物");
        assertEquals("建材", catalog.cargo("机制砂").category());
        assertEquals("集装箱", catalog.cargo("集装箱货物").category());
        assertTrue(catalog.cargo("烧碱").dangerous());
    }

    @Test
    @DisplayName("契约缺件/自相矛盾时立即失败（fail fast，不静默降级）")
    void rejectsBrokenContracts() throws Exception {
        JsonNode ok = MAPPER.readTree(ToolCatalog.class.getClassLoader()
                .getResourceAsStream(ToolCatalog.CONTRACT_RESOURCE));

        JsonNode noTools = ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noTools).putArray("tools");
        assertThrows(IllegalStateException.class, () -> ToolCatalog.fromJson(noTools));

        JsonNode dup = ok.deepCopy();
        var tools = (com.fasterxml.jackson.databind.node.ArrayNode) dup.get("tools");
        tools.add(tools.get(0).deepCopy());
        assertThrows(IllegalStateException.class, () -> ToolCatalog.fromJson(dup));

        JsonNode tooFew = ok.deepCopy();
        var fewTools = (com.fasterxml.jackson.databind.node.ArrayNode) tooFew.get("tools");
        while (fewTools.size() > 2) {
            fewTools.remove(fewTools.size() - 1);
        }
        assertThrows(IllegalStateException.class, () -> ToolCatalog.fromJson(tooFew));

        JsonNode noRules = ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noRules).putArray("businessRules");
        assertThrows(IllegalStateException.class, () -> ToolCatalog.fromJson(noRules));

        JsonNode noTaxonomy = ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noTaxonomy).putArray("cargoTaxonomy");
        assertThrows(IllegalStateException.class, () -> ToolCatalog.fromJson(noTaxonomy));

        JsonNode noVersion = ok.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noVersion).remove("contractVersion");
        assertThrows(IllegalStateException.class, () -> ToolCatalog.fromJson(noVersion));
    }

    @Test
    @DisplayName("未知工具名：查询抛错，hasTool 返回 false")
    void unknownToolIsRejected() {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        assertThrows(IllegalArgumentException.class, () -> catalog.tool("drop_table"));
        assertFalse(catalog.hasTool("drop_table"));
        assertTrue(catalog.hasTool("quote_fee"));
    }

    @Test
    @DisplayName("必填参数名单来自 Schema（服务层据此判断「该追问哪个参数」）")
    void requiredParametersFromSchema() {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        assertTrue(catalog.tool("query_tariff").requiredParameters().containsAll(
                List.of("fee_type", "tonnage", "biz_date")));
        assertTrue(catalog.tool("query_traffic").requiredParameters().containsAll(
                List.of("date_from", "date_to", "metric")));
        assertTrue(catalog.tool("query_waybill").requiredParameters().isEmpty(),
                "运单查询的必填项是「至少一个过滤条件」，由业务层判，不写在 Schema 的 required 里");
        assertNotNull(catalog.note());
    }

    @Test
    @DisplayName("工具块渲染含 notes（边界提醒进提示词）")
    void promptBlockIncludesNotes() {
        ToolCatalog catalog = ToolCatalog.loadDefault();
        String block = catalog.tool("query_vessel").promptBlock(String.valueOf((char) 10));
        assertTrue(block.contains("query_vessel"));
        assertTrue(block.contains("注意："), "notes 必须进提示词：query_vessel 的边界（不能判过闸）就写在那里");
    }
}
