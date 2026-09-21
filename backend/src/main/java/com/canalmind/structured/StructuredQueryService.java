package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 结构化查询段的服务层：**抽取 → 业务校验 → 参数化执行 → 确定性事实**。
 *
 * <p>分层理由（与规格书 §0.3「阶段一」一致）：模型只负责"把中文问题翻译成工具 + 参数"，
 * 参数是否合法、换算口径、金额算术、SQL 全部在代码里。
 * 因此**每一条拒绝/追问路径都显式存在**，而不是"模型答错"。
 *
 * <p>⚠️ 缺参一律**追问而不是猜**（决策 C-35 的判据之一是"缺参是否追问"）：
 * 日期/吨位这类参数一旦猜错，答案会"看起来正确"却全错，这比答不出更危险。
 */
public final class StructuredQueryService {

    /** 参数名 → 中文（追问话术与留痕都用它，避免出现英文参数名）。 */
    private static final Map<String, String> PARAM_CN = Map.ofEntries(
            Map.entry("fee_type", "费种"),
            Map.entry("cargo_category", "货类"),
            Map.entry("cargo_name", "货种"),
            Map.entry("tonnage", "计费吨位"),
            Map.entry("biz_date", "业务发生日期"),
            Map.entry("days", "计费天数"),
            Map.entry("date_from", "统计起始日"),
            Map.entry("date_to", "统计截止日"),
            Map.entry("metric", "统计口径"),
            Map.entry("group_by", "分组维度"),
            Map.entry("fee_type_filter", "费种"),
            Map.entry("waybill_no", "运单号"),
            Map.entry("status", "运单状态"),
            Map.entry("vessel_id", "船舶编号"),
            Map.entry("vessel_type", "船型"));

    private static final List<String> PER_TIME_FEE_TYPES = List.of("引航费", "停泊费");

    /** 缺参的统一前缀：服务层据此把"缺参"路由到**追问**而不是"拒绝"。 */
    static final String MISSING_PARAM_PREFIX = "缺少必填参数 ";

    private final ToolCatalog catalog;
    private final ToolArgumentExtractor extractor;
    private final TariffRuleLookup tariffRepo;
    private final StructuredDataLookup queryRepo;
    private final FeeCalculationEngine engine;
    private final String businessToday;

    public StructuredQueryService(ToolCatalog catalog, ToolArgumentExtractor extractor,
                                 TariffRuleLookup tariffRepo, StructuredDataLookup queryRepo,
                                 FeeCalculationEngine engine, String businessToday) {
        this.catalog = catalog;
        this.extractor = extractor;
        this.tariffRepo = tariffRepo;
        this.queryRepo = queryRepo;
        this.engine = engine;
        this.businessToday = businessToday;
    }

    /** 只做抽取（不执行），供评测统计"抽取命中率"。 */
    public ExtractionOutcome extract(String question) {
        return extractor.extract(question);
    }

    /** 端到端：抽取 + 执行。返回的 {@link ToolResult} 已带追踪与确定性事实。 */
    public ToolResult ask(String question) {
        ExtractionOutcome outcome = extractor.extract(question);
        List<String> traces = new ArrayList<>();
        traces.add("抽取：状态=" + outcome.status() + "，尝试=" + outcome.attempts() + "，耗时=" + outcome.latencyMs() + "ms");
        if (!outcome.errors().isEmpty()) {
            traces.add("校验错误：" + String.join(" | ", outcome.errors()));
        }
        if (outcome.rawResponses().size() > 1) {
            traces.add("重试次数=" + (outcome.rawResponses().size() - 1) + "（上限 2，见 C-32）");
        }

        if (outcome.degraded()) {
            String kind = outcome.degradeKind();
            String reason = switch (kind) {
                case ExtractionOutcome.KIND_TOOL_NONE -> "问题不属于结构化工具集，应走文档检索通道";
                case ExtractionOutcome.KIND_NOT_JSON, ExtractionOutcome.KIND_SCHEMA_INVALID ->
                        "模型输出经 2 次纠错仍不合规，降级为提示用户重述";
                case ExtractionOutcome.KIND_MISSING_PARAMS -> "缺少必要参数，需要用户补充";
                case ExtractionOutcome.KIND_MODEL_ERROR -> "模型调用失败（网络/鉴权/超时）";
                default -> "抽取未得到可用调用";
            };
            return ToolResult.degraded(null, kind, reason, traces);
        }
        if (outcome.needsClarification()) {
            ToolCall call = outcome.call();
            List<String> missingRequired = missingRequiredParams(call);
            String question2 = clarifyQuestion(call.tool(), missingRequired);
            traces.add("模型申报缺参：" + call.missing());
            return ToolResult.clarify(call.tool(), question2, traces);
        }

        ToolCall call = outcome.call();
        traces.add("工具=" + call.tool() + "，参数=" + call.arguments());
        return execute(call, traces);
    }

    /** 只保留**该工具真正必填**的参数名（模型可能把有默认值的参数也报成 missing）。 */
    private List<String> missingRequiredParams(ToolCall call) {
        List<String> required = catalog.hasTool(call.tool()) ? catalog.tool(call.tool()).requiredParameters() : List.of();
        List<String> out = new ArrayList<>();
        for (String m : call.missing()) {
            if (required.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    /** 追问话术：把缺失的参数翻译成中文，并给一个"怎么补"的例子。 */
    static String clarifyQuestion(String tool, List<String> missing) {
        if (missing.isEmpty()) {
            if ("query_waybill".equals(tool)) {
                return "请补充一个查询条件（运单号、日期范围、状态、货类，或是否只看经运河的运单），我才能查到具体运单。";
            }
            if ("query_vessel".equals(tool)) {
                return "请补充一个查询条件（船舶编号、船型、总吨位下限或吃水上限），我才能查到具体船舶。";
            }
            return "请把问题补充得更具体一些（费种、吨位、业务日期等），我才能给出准确结果。";
        }
        StringBuilder sb = new StringBuilder("请补充以下信息：");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(PARAM_CN.getOrDefault(missing.get(i), missing.get(i)));
        }
        sb.append("。例如「2026 年 10 月的过闸费，货重 1200 吨」这样带上费种、吨位与业务日期。");
        return sb.toString();
    }

    private ToolResult execute(ToolCall call, List<String> traces) {
        try {
            return switch (call.tool()) {
                case "query_tariff" -> queryTariff(call, traces);
                case "quote_fee" -> quoteFee(call, traces);
                case "query_traffic" -> queryTraffic(call, traces);
                case "query_waybill" -> queryWaybill(call, traces);
                case "query_vessel" -> queryVessel(call, traces);
                default -> ToolResult.degraded(call.tool(), ExtractionOutcome.KIND_TOOL_UNKNOWN,
                        "未知工具：" + call.tool(), traces);
            };
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int idx = msg.indexOf(MISSING_PARAM_PREFIX);
            if (idx >= 0) {
                // 「缺参」与「口径不允许」是两件事：前者追问用户，后者如实拒绝（决策 C-35）
                String paramCn = msg.substring(idx + MISSING_PARAM_PREFIX.length()).trim();
                traces.add("缺参追问：" + paramCn);
                String prefix = msg.substring(0, idx).trim();
                return ToolResult.clarify(call.tool(),
                        (prefix.isEmpty() ? "" : prefix + "；") + "请补充" + paramCn + "。"
                                + clarifyQuestion(call.tool(), List.of()), traces);
            }
            traces.add("业务拒绝：" + msg);
            return ToolResult.rejected(call.tool(), msg, traces);
        }
    }

    // ==================== 工具实现 ====================

    private ToolResult queryTariff(ToolCall call, List<String> traces) {
        String feeType = requireArg(call, "fee_type");
        BigDecimal tonnage = requireTonnage(call);
        LocalDate bizDate = requireDate(call, "biz_date");
        String basis = basisOf(feeType, traces);
        String category = categoryOf(call, feeType, traces);

        Optional<TariffRuleRow> found = tariffRepo.findRule(feeType, category, basis, tonnage, bizDate);
        if (found.isEmpty()) {
            String why = whyNoRule(feeType, category, basis, tonnage, bizDate);
            traces.add("费率未命中：" + why);
            return ToolResult.noData("query_tariff", List.of(why), traces);
        }
        TariffRuleRow rule = found.get();
        traces.add("命中规则=" + rule.ruleCode() + "（" + rule.periodText() + "）");

        List<String> facts = new ArrayList<>();
        String priceText = rule.unitPrice().stripTrailingZeros().toPlainString();
        facts.add(feeType + "费率：" + priceText + " " + rule.unit()
                + "（货类 " + rule.cargoCategory() + "，按" + rule.tonnageBasis() + "，档位 "
                + rule.bandText() + "，业务日 " + bizDate + "）");
        facts.add("依据规则码 " + rule.ruleCode() + "，生效期 " + rule.periodText());
        facts.add("计费吨位口径说明：过闸费/港杂费/装卸费按载货吨位，引航费/停泊费按船舶总吨位（语料 25-第十二条）");

        List<List<String>> rows = List.of(List.of(
                feeType, rule.cargoCategory(), rule.tonnageBasis(), rule.bandText(),
                priceText, rule.unit(), rule.ruleCode(), rule.periodText()));
        return ToolResult.ok("query_tariff",
                List.of("费种", "货类", "吨位口径", "档位", "单价", "单位", "规则码", "生效期"),
                rows, facts, traces);
    }

    private ToolResult quoteFee(ToolCall call, List<String> traces) {
        String feeType = requireArg(call, "fee_type");
        BigDecimal tonnage = requireTonnage(call);
        LocalDate bizDate = requireDate(call, "biz_date");
        String basis = basisOf(feeType, traces);
        String category = categoryOf(call, feeType, traces);
        int days = call.intArg("days", 1);
        if (!FeeCalculationEngine.PER_DAY_FEE_TYPE.equals(feeType) && call.arguments().has("days")) {
            traces.add("提示：days 仅停泊费有意义，已忽略（" + feeType + "）");
        }

        boolean dangerous = false;
        String cargoName = call.stringArg("cargo_name");
        if (cargoName != null) {
            ToolCatalog.CargoInfo info = catalog.cargo(cargoName);
            if (info == null) {
                throw new IllegalArgumentException("未知货种：" + cargoName);
            }
            dangerous = info.dangerous();
            traces.add("货种=" + cargoName + " → 货类=" + info.category() + "，危险货物=" + dangerous);
            if (!info.category().equals(category)) {
                throw new IllegalArgumentException("货种 " + cargoName + " 属于货类 " + info.category()
                        + "，与给出的货类 " + category + " 不符（货类与危险品属性只能由货种推导，见 C-31）");
            }
        } else if (!TariffRuleRow.NO_CATEGORY.equals(category)) {
            // 用户只说到货类：危险品属性**查库推导**（不在代码里硬编码"哪些类一致"）
            Boolean uniform = queryRepo.cargoCategoryDangerous(category);
            if (uniform == null) {
                throw new IllegalArgumentException("货类 " + category + " 内部既有危险货物也有普通货物，"
                        + MISSING_PARAM_PREFIX + "货种（例如「成品油」或「动力煤」），否则无法确定是否加收 30%");
            }
            dangerous = uniform;
            traces.add("货类=" + category + " → 库内该货类危险品属性一致，危险货物=" + dangerous
                    + "（由 cargo_type 查库推导，非硬编码）");
        }

        Optional<TariffRuleRow> found = tariffRepo.findRule(feeType, category, basis, tonnage, bizDate);
        if (found.isEmpty()) {
            String why = whyNoRule(feeType, category, basis, tonnage, bizDate);
            traces.add("费率未命中：" + why);
            return ToolResult.noData("quote_fee", List.of(why), traces);
        }
        FeeCalculationEngine.Quote q = engine.quote(found.get(), tonnage, days, dangerous, bizDate);
        traces.add("引擎步骤：" + String.join(" / ", q.steps()));

        List<String> facts = new ArrayList<>();
        facts.add(feeType + "金额 = " + q.amount().toPlainString() + " 元");
        facts.add("计算过程：" + q.summary());
        facts.add("依据规则码 " + q.ruleCode() + "（档位 " + q.bandText() + "，生效期 " + q.periodText() + "）");
        if (q.reliefApplied()) {
            facts.add("已按重点保供物资（" + category + "）过闸费 70% 计收（语料 06-第十三条）");
        }
        if (q.surchargeApplied()) {
            facts.add("已按危险货物加收 30%（语料 06-第十四条 / 09-第六条）");
        }
        facts.add("未实现条款（如实声明）：" + String.join("；", FeeCalculationEngine.UNIMPLEMENTED_CLAUSES));

        List<List<String>> rows = List.of(List.of(
                feeType, cargoName == null ? "—" : cargoName, q.cargoCategory(), q.tonnage().toPlainString(),
                q.tonnageBasis(), q.unitPrice().stripTrailingZeros().toPlainString(), q.unit(),
                String.valueOf(q.days()), q.amount().toPlainString(), q.ruleCode()));
        return ToolResult.ok("quote_fee",
                List.of("费种", "货种", "货类", "计费吨位", "吨位口径", "单价", "单位", "天数", "金额(元)", "规则码"),
                rows, facts, traces);
    }

    private ToolResult queryTraffic(ToolCall call, List<String> traces) {
        LocalDate from = requireDate(call, "date_from");
        LocalDate to = requireDate(call, "date_to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("截止日 " + to + " 早于起始日 " + from);
        }
        String metric = requireArg(call, "metric");
        String groupBy = call.stringArg("group_by");
        if (groupBy == null) {
            groupBy = "无";
        }
        String feeType = call.stringArg("fee_type");
        if (feeType != null && !"费用金额".equals(metric)) {
            traces.add("提示：fee_type 仅在 metric=费用金额 时生效，已忽略");
            feeType = null;
        }
        boolean viaCanalOnly = call.boolArg("via_canal_only", false);
        int topN = "无".equals(groupBy) ? 1 : call.intArg("top_n", 10);

        LocalDate wFrom = from.isBefore(StructuredQueryRepository.BUSINESS_START) ? StructuredQueryRepository.BUSINESS_START : from;
        LocalDate wTo = to.isAfter(StructuredQueryRepository.BUSINESS_END) ? StructuredQueryRepository.BUSINESS_END : to;
        if (wTo.isBefore(wFrom)) {
            String why = "请求区间 " + from + " ~ " + to + " 完全落在业务数据窗口 "
                    + StructuredQueryRepository.BUSINESS_START + " ~ " + StructuredQueryRepository.BUSINESS_END
                    + " 之外，窗口内没有记录（不是查询失败）";
            traces.add(why);
            return ToolResult.noData("query_traffic", List.of(why), traces);
        }
        boolean clipped = !wFrom.equals(from) || !wTo.equals(to);
        if (clipped) {
            traces.add("窗口裁剪：请求 " + from + " ~ " + to + " → 实际统计 " + wFrom + " ~ " + wTo
                    + "（业务窗口 2026-09-16 ~ 2027-09-15）");
        }

        List<StructuredQueryRepository.GroupRow> rows = queryRepo.traffic(
                wFrom, wTo, metric, groupBy, feeType, viaCanalOnly, topN);
        String scope = metric + ("费用金额".equals(metric) && feeType != null ? "（" + feeType + "）" : "")
                + ("无".equals(groupBy) ? "" : "，按" + groupBy) + (viaCanalOnly ? "，仅经运河航次" : "");
        if (rows.isEmpty()) {
            String why = "窗口 " + wFrom + " ~ " + wTo + " 内没有符合条件的记录";
            traces.add(why);
            return ToolResult.noData("query_traffic", List.of(why), traces);
        }

        List<String> facts = new ArrayList<>();
        if ("无".equals(groupBy)) {
            facts.add(scope + " 合计 = " + rows.get(0).value() + "（窗口 " + wFrom + " ~ " + wTo
                    + "，涉及运单 " + rows.get(0).count() + " 票）");
        } else {
            facts.add(scope + " 前 " + rows.size() + " 名（窗口 " + wFrom + " ~ " + wTo + "）：");
            int rank = 1;
            for (StructuredQueryRepository.GroupRow r : rows) {
                facts.add("  " + rank++ + ". " + r.label() + " = " + r.value()
                        + ("运单数".equals(metric) ? " 票" : ""));
            }
            facts.add("并列规则（事前写定）：按值降序、值相同时按名称升序取前 " + topN + " 名（决策 C-35）");
        }
        List<List<String>> table = new ArrayList<>();
        for (StructuredQueryRepository.GroupRow r : rows) {
            table.add(List.of(r.label(), r.value(), String.valueOf(r.count())));
        }
        if (clipped) {
            facts.add("注意：请求区间超出业务数据窗口，已按窗口内可用数据统计（" + wFrom + " ~ " + wTo + "）");
        }
        return ToolResult.ok("query_traffic", List.of("分组", "数值", "运单数"), table, facts, traces);
    }

    private ToolResult queryWaybill(ToolCall call, List<String> traces) {
        String waybillNo = call.stringArg("waybill_no");
        LocalDate from = optionalDate(call, "date_from");
        LocalDate to = optionalDate(call, "date_to");
        String status = call.stringArg("status");
        String category = call.stringArg("cargo_category");
        boolean viaCanalOnly = call.boolArg("via_canal_only", false);
        int limit = call.intArg("limit", 20);

        boolean hasFilter = (waybillNo != null && !waybillNo.isBlank()) || from != null || to != null
                || (status != null && !status.isBlank()) || (category != null && !category.isBlank()) || viaCanalOnly;
        if (!hasFilter) {
            // 无过滤条件 → 追问，而不是把 3.2 万行倒出来
            return ToolResult.clarify("query_waybill", clarifyQuestion("query_waybill", List.of()), traces);
        }
        List<List<String>> rows = queryRepo.waybills(waybillNo, from, to, status, category, viaCanalOnly, limit);
        if (rows.isEmpty()) {
            String why = "没有查到符合条件的运单（条件：" + describeWaybillFilters(waybillNo, from, to, status, category, viaCanalOnly) + "）";
            traces.add(why);
            return ToolResult.noData("query_waybill", List.of(why), traces);
        }
        List<String> facts = new ArrayList<>();
        facts.add("查到 " + rows.size() + " 票运单" + (rows.size() >= limit ? "（已按上限 " + limit + " 截断）" : "")
                + "，条件：" + describeWaybillFilters(waybillNo, from, to, status, category, viaCanalOnly));
        for (List<String> r : rows) {
            facts.add("  运单 " + r.get(0) + "：" + r.get(1) + "（" + r.get(2) + "）" + r.get(3) + " 吨，运费 "
                    + r.get(4) + " 元，状态 " + r.get(5) + "，" + r.get(7) + " → " + r.get(8)
                    + (("是".equals(r.get(9))) ? "，经运河" : "，不经运河") + "，单证：" + r.get(10));
        }
        return ToolResult.ok("query_waybill",
                List.of("运单号", "货种", "货类", "货重(吨)", "运费(元)", "状态", "业务日", "起运港", "目的港", "经运河", "单证"),
                rows, facts, traces);
    }

    private ToolResult queryVessel(ToolCall call, List<String> traces) {
        String vesselId = call.stringArg("vessel_id");
        String vesselType = call.stringArg("vessel_type");
        Integer minGross = call.intArg("min_gross_tonnage", null);
        BigDecimal maxDraft = call.stringArg("max_draft_m") == null ? null : new BigDecimal(call.stringArg("max_draft_m"));
        int limit = call.intArg("limit", 20);
        boolean hasFilter = (vesselId != null && !vesselId.isBlank()) || (vesselType != null && !vesselType.isBlank())
                || minGross != null || maxDraft != null;
        if (!hasFilter) {
            return ToolResult.clarify("query_vessel", clarifyQuestion("query_vessel", List.of()), traces);
        }
        List<List<String>> rows = queryRepo.vessels(vesselId, vesselType, minGross, maxDraft, limit);
        if (rows.isEmpty()) {
            String why = "没有查到符合条件的船舶";
            traces.add(why);
            return ToolResult.noData("query_vessel", List.of(why), traces);
        }
        List<String> facts = new ArrayList<>();
        facts.add("查到 " + rows.size() + " 艘船舶" + (rows.size() >= limit ? "（已按上限 " + limit + " 截断）" : ""));
        for (List<String> r : rows) {
            facts.add("  " + r.get(0) + " " + r.get(1) + "：" + r.get(2) + "，载重吨 " + r.get(3)
                    + "，船舶总吨位 " + r.get(4) + "，吃水 " + r.get(5) + " 米，船东 " + r.get(7));
        }
        facts.add("⚠️ 本工具不能回答「某船能否过闸」：闸室水深不在数据里（语料 03-第六条 明确不规定固定水深），"
                + "船舶长宽字段也不存在——属未实现清单，请如实说明缺什么数据");
        return ToolResult.ok("query_vessel",
                List.of("船舶编号", "船名", "船型", "载重吨", "总吨位", "吃水(m)", "建造年", "船东"),
                rows, facts, traces);
    }

    // ==================== 参数校验小工具 ====================

    private String requireArg(ToolCall call, String name) {
        String v = call.stringArg(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(MISSING_PARAM_PREFIX + PARAM_CN.getOrDefault(name, name));
        }
        return v;
    }

    private BigDecimal requireTonnage(ToolCall call) {
        String raw = call.stringArg("tonnage");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(MISSING_PARAM_PREFIX + "计费吨位");
        }
        BigDecimal v;
        try {
            v = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("计费吨位不是合法数字：" + raw);
        }
        if (v.signum() <= 0) {
            throw new IllegalArgumentException("计费吨位必须为正数，实际 " + raw);
        }
        return v;
    }

    private LocalDate requireDate(ToolCall call, String name) {
        LocalDate d = optionalDate(call, name);
        if (d == null) {
            throw new IllegalArgumentException(MISSING_PARAM_PREFIX + PARAM_CN.getOrDefault(name, name));
        }
        return d;
    }

    private LocalDate optionalDate(ToolCall call, String name) {
        String raw = call.stringArg(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(PARAM_CN.getOrDefault(name, name) + "不是合法日期（须 YYYY-MM-DD）：" + raw);
        }
    }

    /** 计费吨位口径由**费种**决定（语料 `25-第十二条`）——不让模型传，模型只会传错。 */
    private String basisOf(String feeType, List<String> traces) {
        String basis = PER_TIME_FEE_TYPES.contains(feeType)
                ? TariffRuleRow.BASIS_GROSS_TONNAGE : TariffRuleRow.BASIS_CARGO_WEIGHT;
        traces.add("吨位口径（由费种推导）：" + feeType + " → " + basis);
        return basis;
    }

    /**
     * 货类：引航费/停泊费与货类无关（哨兵 `不区分`）；其余费种必须能**确定**货类。
     *
     * <p>⚠️ 只给了 `cargo_name` 时**由它推货类**——这一点实测踩过：首轮 Execution Accuracy 里
     * 有 4 题明明用户说了货种（成品油/硫酸/烧碱/集装箱货物），服务却反问"请补充货类"。
     * 根因不在模型（它已正确填了 `cargo_name`），而在**这里只看 `cargo_category`**。
     * 契约里写的是"货种**或**货类"，服务端就必须真的接受这两种入口，
     * 否则"契约允许的输入"与"服务端接受的状态"不一致——**表现出来像模型抽取不准，其实是实现漏了一支**。
     */
    private String categoryOf(ToolCall call, String feeType, List<String> traces) {
        String category = call.stringArg("cargo_category");
        if (PER_TIME_FEE_TYPES.contains(feeType)) {
            if (category != null && !category.isBlank()) {
                traces.add("提示：引航费/停泊费与货类无关，已忽略传入的货类 " + category);
            }
            return TariffRuleRow.NO_CATEGORY;
        }
        if (category != null && !category.isBlank()) {
            return category;
        }
        String cargoName = call.stringArg("cargo_name");
        if (cargoName != null && !cargoName.isBlank()) {
            ToolCatalog.CargoInfo info = catalog.cargo(cargoName);
            if (info == null) {
                throw new IllegalArgumentException("未知货种：" + cargoName);
            }
            traces.add("货类由货种推导：" + cargoName + " → " + info.category() + "（cargo_name 与 cargo_category 二选一）");
            return info.category();
        }
        throw new IllegalArgumentException("费种 " + feeType + " 按货类分档，" + MISSING_PARAM_PREFIX + "货类");
    }

    /** 未命中费率时给出**可核对的原因**，而不是一句"查不到"。 */
    private String whyNoRule(String feeType, String category, String basis, BigDecimal tonnage, LocalDate bizDate) {
        List<TariffRuleRow> all = tariffRepo.listByFeeType(feeType, bizDate);
        if (all.isEmpty()) {
            return "业务日 " + bizDate + " 没有任何生效中的「" + feeType + "」费率（费率有生效期，过闸费 2026 版自 2026-09-16 起、2027 版自 2027-01-01 起）";
        }
        boolean anyCategory = false;
        boolean anyBand = false;
        for (TariffRuleRow r : all) {
            if (r.cargoCategory().equals(category)) {
                anyCategory = true;
                if (r.covers(tonnage)) {
                    anyBand = true;
                }
            }
        }
        if (!anyCategory) {
            return "业务日 " + bizDate + " 的「" + feeType + "」费率里没有货类「" + category + "」"
                    + "（有：" + all.stream().map(TariffRuleRow::cargoCategory).distinct().toList() + "）";
        }
        if (!anyBand) {
            return "业务日 " + bizDate + " 的「" + feeType + "」+「" + category + "」费率没有覆盖吨位 "
                    + tonnage.stripTrailingZeros().toPlainString() + "（口径 " + basis + "），现有档位："
                    + all.stream().filter(r -> r.cargoCategory().equals(category))
                    .map(TariffRuleRow::bandText).distinct().toList();
        }
        return "费率未命中（费种 " + feeType + "、货类 " + category + "、吨位 " + tonnage + "、业务日 " + bizDate + "）";
    }

    private static String describeWaybillFilters(String waybillNo, LocalDate from, LocalDate to,
                                                String status, String category, boolean viaCanalOnly) {
        List<String> parts = new ArrayList<>();
        if (waybillNo != null && !waybillNo.isBlank()) {
            parts.add("运单号=" + waybillNo);
        }
        if (from != null || to != null) {
            parts.add("日期=" + (from == null ? "最早" : from) + "~" + (to == null ? "最晚" : to));
        }
        if (status != null && !status.isBlank()) {
            parts.add("状态=" + status);
        }
        if (category != null && !category.isBlank()) {
            parts.add("货类=" + category);
        }
        if (viaCanalOnly) {
            parts.add("仅经运河");
        }
        return String.join("，", parts);
    }

    /** 供自检用：契约与业务层的口径是否一致（工具数、货种表、费种枚举）。 */
    public Map<String, Object> selfCheck() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contractVersion", catalog.contractVersion());
        out.put("tools", catalog.tools().keySet());
        out.put("cargoTypes", catalog.cargoTaxonomy().size());
        out.put("businessRules", catalog.businessRules().size());
        out.put("engineUnimplementedClauses", FeeCalculationEngine.UNIMPLEMENTED_CLAUSES.size());
        out.put("businessToday", businessToday);
        JsonNode schema = catalog.envelope().path("properties").path("tool").path("enum");
        List<String> enumValues = new ArrayList<>();
        schema.forEach(n -> enumValues.add(n.asText()));
        out.put("envelopeToolEnum", enumValues);
        return out;
    }

    public ToolCatalog catalog() {
        return catalog;
    }
}
