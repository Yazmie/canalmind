package com.canalmind.structured;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 结构化查询段的服务层：**抽取 → 业务校验 → 参数化执行 → 确定性事实**。
 *
 * <p>分层理由（与规格书 §0.3「阶段一」一致）：模型只负责"把中文问题翻译成工具 + 参数"，
 * 参数是否合法、换算口径、金额算术、SQL 全部在代码里。
 * 因此**每一条拒绝/追问路径都显式存在**，而不是"模型答错"。
 *
 * <p>⚠️ 缺参一律**追问而不是猜**（决策 C-35 的判据之一是"缺参是否追问"）：
 * 日期/吨位这类参数一旦猜错，答案会"看起来正确"却全错，这比答不出更危险。
 *
 * <p><b>五种终态的分界（决策 D24 → C-65 修完 6 类缺口后的口径）</b>：
 * <table>
 *   <tr><th>状态</th><th>什么时候</th><th>例子</th></tr>
 *   <tr><td>{@code OK}</td><td>参数合法、窗口内、量级内，且查到了确定性事实</td><td>1200 吨建材过闸费</td></tr>
 *   <tr><td>{@code NEEDS_CLARIFICATION}</td><td><b>用户要补一个参数</b>：缺参（C-35）、
 *       日历上不存在的日期（缺口②）、单位错（缺口③）</td><td>「2027-02-30」「5000 米」</td></tr>
 *   <tr><td>{@code REJECTED}</td><td><b>用户要的东西本身与契约/口径冲突</b>：参数取值越界或枚举不符（缺口①）、
 *       引航费/停泊费按货类分档（缺口⑥）</td><td>{@code limit=10000}、注入串当分组维度</td></tr>
 *   <tr><td>{@code NO_DATA}</td><td><b>窗口内查过、确实没有</b>（含部分裁剪后窗口内无记录）</td><td>窗口内某货类无运单</td></tr>
 *   <tr><td>{@code DEGRADED}</td><td><b>这一路这次给不出确定性结果</b>：模型输出畸形/调用失败/不属于工具集、
 *       合法日期但在业务窗口外（缺口⑤）、吨位远超仿真数据量级（缺口④）</td><td>「2030-01-01」「5 万吨」</td></tr>
 * </table>
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
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code ai.structured.query}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    public StructuredQueryService(ToolCatalog catalog, ToolArgumentExtractor extractor,
                                 TariffRuleLookup tariffRepo, StructuredDataLookup queryRepo,
                                 FeeCalculationEngine engine, String businessToday) {
        this(catalog, extractor, tariffRepo, queryRepo, engine, businessToday,
                ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 的 {@code ai.structured.query}）。
     *
     * <p>⚠️ 既有 6 参构造器保持不变、委托给 {@link ObservationRegistry#NOOP}：
     * 它被 {@code StructuredQueryCli}、{@code ExecutionAccuracyRunTest}、
     * {@code StructuredQueryServiceTest} 使用，**旧路径零变化**。
     *
     * @param observationRegistry 由装配点注入；⛔ 不得传 {@code null}
     */
    public StructuredQueryService(ToolCatalog catalog, ToolArgumentExtractor extractor,
                                 TariffRuleLookup tariffRepo, StructuredDataLookup queryRepo,
                                 FeeCalculationEngine engine, String businessToday,
                                 ObservationRegistry observationRegistry) {
        this.catalog = catalog;
        this.extractor = extractor;
        this.tariffRepo = tariffRepo;
        this.queryRepo = queryRepo;
        this.engine = engine;
        this.businessToday = businessToday;
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    /** 只做抽取（不执行），供评测统计"抽取命中率"。 */
    public ExtractionOutcome extract(String question) {
        return extractor.extract(question);
    }

    /** 端到端：抽取 + 执行。返回的 {@link ToolResult} 已带追踪与确定性事实。 */
    public ToolResult ask(String question) {
        return askDetailed(question).result();
    }

    /**
     * 抽取 + 执行，并把**抽取结果**（工具 + 实参）一并交给调用方。
     *
     * <p>⚠️ **为什么需要这个方法**：L4「多轮参数继承」的判据是**工具 + 实参**，
     * 而实参目前只出现在 {@code traces} 的**人类可读字符串**里（`工具=X，参数={...}`）。
     * 从那种字符串反解实参，正是「**拿给人看的标签当机器判据**」——
     * 本项目已因同类错误两次得出**错误的通过结论**（见《评测报告》§6.1 第 3、8 条）。
     * 故此处**显式开出类型化出口**，⛔ 评测侧不许去解析 traces 文本。
     *
     * <p>⛔ 本方法**不改变** {@link #ask(String)} 的任何行为，也**不增加**付费调用次数：
     * 它只是把原来内部那一次抽取的结果也返回出来（每轮仍是 1 次抽取）。
     *
     * @param question 用户本轮问题（多轮场景下由评测侧拼入上文，见 L4 运行器）
     */
    public AskOutcome askDetailed(String question) {
        Observation observation = AiObservation.start(AiSpans.STRUCTURED_QUERY, observationRegistry);
        return observation.observe(() -> {
            ExtractionOutcome outcome = extractor.extract(question);
            ToolResult result = run(outcome, question);
            // 规格书 §7.3 第 570 行的低基数键 ai.rag.result（hit / empty）在这里表达
            // "结构化通道这次到底给没给出可用结果"：只有 STATUS_OK 算命中，
            // NO_DATA / REJECTED / NEEDS_CLARIFICATION / DEGRADED 四种状态一律记 empty
            // ——它们对下游是同一件事："这一路没有可用的确定性事实"。
            // ⚠️ 四种状态**不可混为一谈**（AnswerComposer.statusLine 逐条翻译），
            //    所以它们的区分仍靠 ToolResult.status() 落在 eval_record/traces 里，
            //    ⛔ 不是靠这个二值属性。
            AiObservation.ragResult(observation, !ToolResult.STATUS_OK.equals(result.status()));
            return new AskOutcome(outcome, result);
        });
    }

    /** {@link #askDetailed(String)} 的返回值：抽取（工具 + 实参 + 降级/追问状态）与执行结果。 */
    public record AskOutcome(ExtractionOutcome extraction, ToolResult result) {}

    /**
     * 抽取之后的一切：校验信息、降级、拒绝、追问、执行。（由 {@link #askDetailed} 调用）
     *
     * @param question 用户**原始问句**：单位一致性守卫（缺口③）要靠它比对
     *                 "模型传进来的吨位是不是抄了问句里的一个长度值"。
     *                 ⚠️ 这是本方法唯一用到问句的地方，⛔ 不参与参数抽取（抽取在模型侧）。
     */
    private ToolResult run(ExtractionOutcome outcome, String question) {
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
        // ── 缺口①（决策 D24 → C-65）：**值越界 / 枚举不符** ⇒ 拒绝执行，⛔ 不是整条通道降级 ──
        // 语义差别是实质性的：DEGRADED 的意思是"这一路这次没算出东西，请用户重述"，
        // 而这里的事实是"用户要的取值本身越出了契约允许的范围"（limit=10000、把注入串当分组维度）
        // ⇒ 必须**明确拒绝**并说明边界，而不是让用户以为只是"系统抽不出参数"。
        // ⚠️ 走到这里说明参数**从未送进执行层**：违规调用不得触达数据层（契约与既有测试都钉着这一条）。
        if (outcome.rejected()) {
            ToolCall call = outcome.call();
            String reason = outcome.errors().isEmpty() ? "参数取值不符合工具契约"
                    : String.join("；", outcome.errors());
            traces.add("违规拒绝（参数取值越界/枚举不符，未执行）：" + reason);
            return ToolResult.rejected(call == null ? null : call.tool(), reason, traces);
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
        return execute(call, traces, question);
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

    /**
     * 「**需要用户澄清**」而不是「缺参」的抛出物（缺口②/③）。
     *
     * <p>⚠️ 为什么不复用 {@code MISSING_PARAM_PREFIX} 那条路径：那条路径的话术由**缺参模板**拼装
     * （"{@code 前缀}；请补充{@code 参数名}。例如「2026 年 10 月的过闸费，货重 1200 吨」…"），
     * 而这两类要说清的是**错在哪**——"「5000 米」是长度单位、要的是吨"、
     * "2027 年 2 月只有 28 天、2027-02-30 在日历上不存在"。用缺参模板会把这句话挤掉，
     * 而数据集的 rubric 恰好要求把它们说出来。
     *
     * <p>⛔ 两者的**状态码相同**（都是 {@code NEEDS_CLARIFICATION}）：对下游与评测而言，
     * "用户需要补/改一个参数"是同一件事，区别只在话术。
     */
    private static final class NeedClarificationException extends RuntimeException {

        private final String kind;

        NeedClarificationException(String kind, String question) {
            super(question);
            this.kind = kind;
        }

        String kind() {
            return kind;
        }
    }

    private ToolResult execute(ToolCall call, List<String> traces, String question) {
        try {
            return switch (call.tool()) {
                case "query_tariff" -> queryTariff(call, traces, question);
                case "quote_fee" -> quoteFee(call, traces, question);
                case "query_traffic" -> queryTraffic(call, traces);
                case "query_waybill" -> queryWaybill(call, traces);
                case "query_vessel" -> queryVessel(call, traces);
                default -> ToolResult.degraded(call.tool(), ExtractionOutcome.KIND_TOOL_UNKNOWN,
                        "未知工具：" + call.tool(), traces);
            };
        } catch (NeedClarificationException e) {
            traces.add("需要用户澄清（" + e.kind() + "）：" + e.getMessage());
            return ToolResult.clarify(call.tool(), e.getMessage(), traces);
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

    // ==================== 参数守卫（缺口②③④⑤） ====================

    /**
     * 缺口③：问句里的「数字 + 长度单位」与该吨位同值、且问句里没有同值的「…吨」⇒ **追问**。
     *
     * <p>⚠️ 诚实边界：本层的确定性信息只有"模型交给我的 tonnage"与"用户原话"，
     * 判不出模型是猜的还是抄的 ⇒ 只做**证据驱动**的追问，判定规则见 {@link TonnageUnitGuard}。
     */
    private void requireUnitConsistency(String question, BigDecimal tonnage, List<String> traces) {
        String why = TonnageUnitGuard.explainIfLengthUnitMistake(question, tonnage);
        if (why != null) {
            traces.add("单位一致性检查：不通过");
            throw new NeedClarificationException("单位错误", why + "。请补充实际计费吨位（吨）。");
        }
        traces.add("单位一致性检查：问句里没有与该吨位同值的长度单位表述");
    }

    /**
     * 缺口④：计费吨位远超仿真业务数据量级 ⇒ {@code DEGRADED}（给量级提示，⛔ 不给确定金额）。
     *
     * <p>阈值与出处全部在 {@link SimulatedDataScope}（具名常量 + 文档锚点），此处只做调用。
     *
     * @return 命中时返回该降级结果，未命中返回 {@code null}
     */
    private ToolResult outOfScopeOrNull(String tool, String tonnageBasis, BigDecimal tonnage,
                                        List<String> traces) {
        String why = SimulatedDataScope.explainIfOutOfScope(tonnageBasis, tonnage);
        if (why == null) {
            return null;
        }
        traces.add("量级检查：超出仿真数据量级");
        return ToolResult.degraded(tool, ExtractionOutcome.KIND_MAGNITUDE_OUT_OF_RANGE, why, traces);
    }

    /**
     * 缺口⑤：**合法日期但落在业务数据窗口之外** ⇒ {@code DEGRADED}（⛔ 不是 {@code NO_DATA}）。
     *
     * <p>⚠️ 与缺口②的分界必须写清（决策 D24）：
     * <ul>
     *   <li>② 是「**日历上不存在的日期**」（{@code 2027-02-30}）⇒ **追问**（用户给的日期本身是错的，要改）；</li>
     *   <li>⑤ 是「**合法日期但在窗口外**」（{@code 2030-01-01}）⇒ {@code DEGRADED}
     *       （日期没错，是这一路没有该区间的数据与费率 ⇒ 不能装作"查过了、没有"）；</li>
     *   <li>窗口内 ⇒ 一切照常。</li>
     * </ul>
     *
     * <p>⚠️ 为什么不判 {@code NO_DATA}：{@code NO_DATA} 的语义是"**在业务窗口内查过、确实没有**"
     * （{@code query_traffic} 的部分裁剪就属于它）；而"整段区间都在窗口外"意味着
     * **本层根本没有可比对的数据与费率**，把它说成"没有数据"会让回答层把它当成一条正常事实。
     */
    private ToolResult outOfWindowOrNull(String tool, String what, LocalDate date, List<String> traces) {
        if (!date.isBefore(StructuredQueryRepository.BUSINESS_START)
                && !date.isAfter(StructuredQueryRepository.BUSINESS_END)) {
            return null;
        }
        String why = what + " " + date + " 落在业务数据窗口 "
                + StructuredQueryRepository.BUSINESS_START + " ~ " + StructuredQueryRepository.BUSINESS_END
                + "（通航后一年）之外：本层没有该区间的费率与业务数据，故不给确定结果";
        traces.add(why);
        return ToolResult.degraded(tool, ExtractionOutcome.KIND_OUT_OF_WINDOW, why, traces);
    }

    // ==================== 工具实现 ====================

    private ToolResult queryTariff(ToolCall call, List<String> traces, String question) {
        String feeType = requireArg(call, "fee_type");
        BigDecimal tonnage = requireTonnage(call);
        requireUnitConsistency(question, tonnage, traces);
        LocalDate bizDate = requireDate(call, "biz_date");
        String basis = basisOf(feeType, traces);
        String category = categoryOf(call, feeType, traces);

        ToolResult outOfScope = outOfScopeOrNull("query_tariff", basis, tonnage, traces);
        if (outOfScope != null) {
            return outOfScope;
        }
        ToolResult outOfWindow = outOfWindowOrNull("query_tariff", "业务日", bizDate, traces);
        if (outOfWindow != null) {
            return outOfWindow;
        }

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

    private ToolResult quoteFee(ToolCall call, List<String> traces, String question) {
        String feeType = requireArg(call, "fee_type");
        BigDecimal tonnage = requireTonnage(call);
        requireUnitConsistency(question, tonnage, traces);
        LocalDate bizDate = requireDate(call, "biz_date");
        // ⚠️ 量级/窗口两道守卫**刻意放在查危险品属性之前**：它们一旦命中就整条不执行，
        //    此时数据层不该被调用（与缺口①"违规不得触达数据层"同一条纪律）。
        String basis = basisOf(feeType, traces);
        ToolResult outOfScope = outOfScopeOrNull("quote_fee", basis, tonnage, traces);
        if (outOfScope != null) {
            return outOfScope;
        }
        ToolResult outOfWindow = outOfWindowOrNull("quote_fee", "业务日", bizDate, traces);
        if (outOfWindow != null) {
            return outOfWindow;
        }
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
            // 缺口⑤（决策 D24）：整段区间都在业务窗口外 ⇒ DEGRADED，⛔ 不是 NO_DATA。
            // 依据：数据集 `L6-015`（2027-10-01 ~ 2027-10-20）期望 `DEGRADED`；
            // 而"窗口内查过确实没有"才是 NO_DATA（部分裁剪下面那一支即属此类，保持不变）。
            String why = "请求区间 " + from + " ~ " + to + " 完全落在业务数据窗口 "
                    + StructuredQueryRepository.BUSINESS_START + " ~ " + StructuredQueryRepository.BUSINESS_END
                    + " 之外，本层没有该区间的费率与业务数据（不是查询失败，也不是「窗口内没有记录」）";
            traces.add(why);
            return ToolResult.degraded("query_traffic", ExtractionOutcome.KIND_OUT_OF_WINDOW, why, traces);
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

    /** 契约里日期参数的形状（{@code tool-contracts.json} 的 {@code pattern}）。 */
    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 日期参数：**缺** ⇒ 走缺参追问；**形状对但日历上不存在** ⇒ 缺口②追问；其余一律如实拒绝。
     *
     * <p>⚠️⚠️ <b>缺口②（决策 D24 → C-65）：{@code 2027-02-30} 这类"日历上不存在的日期"要**追问**，
     * ⛔ 不是 {@code REJECTED}</b>。原实现一律抛"业务拒绝：业务发生日期不是合法日期"，实测（2026-09-23 探针）
     * 桩发 {@code biz_date=2027-02-30} ⇒ {@code REJECTED}，而数据集 `L6-014` 要的是
     * {@code NEEDS_CLARIFICATION}（rubric：「必须指出 2027-02-30 不是合法日期（2027 年 2 月只有 28 天）
     * 并请用户确认日期」）。
     *
     * <p>⚠️ 为什么这个区分是**实质**的：{@code REJECTED} 的话术是"无法执行"，
     * 而这里用户只是**把日期写错了**——他要的东西（"那天的运量"）完全合法，
     * 换个真实存在的日期就能答。把它当作"拒绝"会让回答层直接拒答，而不是告诉用户"这个日期不存在"。
     *
     * <p>⚠️ 但**只对"形状合乎契约却过不了日历"的取值**这么做（{@code 2027-02-30}、{@code 2027-13-01}）：
     * 形状本身就不对的取值（{@code 2026年10月1日}）由 `SchemaGate` 的 `pattern` 拦下
     * ⇒ 走重试后降级，⛔ 不归到这里。这样"追问"的适用面被压到最小。
     */
    private LocalDate optionalDate(ToolCall call, String name) {
        String raw = call.stringArg(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            String cn = PARAM_CN.getOrDefault(name, name);
            String why = calendarExplain(raw.trim());
            if (why != null) {
                throw new NeedClarificationException("不存在的日期",
                        cn + " " + raw.trim() + " 在日历上不存在（" + why + "），"
                                + "请确认一个真实存在的日期后我再统计（⛔ 不会自行改成相近日期）");
            }
            throw new IllegalArgumentException(cn + "不是合法日期（须 YYYY-MM-DD）：" + raw);
        }
    }

    /**
     * 给"形状对但日历上不存在"的日期一句可核对的解释；⛔ 解释不出来就返回 {@code null}
     * （宁可按原样拒绝，也不猜一句可能说错的话）。
     */
    private static String calendarExplain(String raw) {
        if (!DATE_SHAPE.matcher(raw).matches()) {
            return null;
        }
        int year;
        int month;
        int day;
        try {
            year = Integer.parseInt(raw.substring(0, 4));
            month = Integer.parseInt(raw.substring(5, 7));
            day = Integer.parseInt(raw.substring(8, 10));
        } catch (NumberFormatException e) {
            return null;
        }
        if (month < 1 || month > 12) {
            return "月份 " + raw.substring(5, 7) + " 不存在，只能是 01–12";
        }
        int lastDay = YearMonth.of(year, month).lengthOfMonth();
        if (day < 1 || day > lastDay) {
            return year + " 年 " + month + " 月只有 " + lastDay + " 天";
        }
        // 形状对、月日都合法 ⇒ 理论上不会被 LocalDate.parse 拒绝；真到这就是无法解释的情形
        return null;
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
     *
     * <p>⚠️⚠️ <b>缺口⑥（决策 D24 → C-65）：引航费/停泊费传入货类/货种时**拒绝**，⛔ 不再"静默忽略"</b>。
     * 原实现是"提示：引航费/停泊费与货类无关，已忽略传入的货类 化工"然后**照常算出确定金额**——
     * 两次确定性探针（2026-09-23）实测：引航费+化工 得 300.00 元、停泊费+金属 得 360.00 元，状态都是 {@code OK}。
     * 数据集 `L6-008`/`L6-009` 要的是 {@code REJECTED}，理由是**用户的诉求本身与口径矛盾**
     * （语料 `08-引航费与停泊费费率表` 第二条/第四条/第七条末段：这两类费"与货物所属货类无关，
     * 不得因货类不同而调整"，费率表货类栏为「不区分」）：
     * <ul>
     *   <li>"忽略后照算"会给出一个**看起来正确、实则答非所问**的金额（用户以为已按货类分档）；
     *       这正是本项目反复强调的「错答比不答危险」。</li>
     *   <li>拒绝时**如实说明**为什么不能按货类分档（而不是只说"参数不合法"）。</li>
     * </ul>
     */
    private String categoryOf(ToolCall call, String feeType, List<String> traces) {
        String category = call.stringArg("cargo_category");
        String cargoName = call.stringArg("cargo_name");
        if (PER_TIME_FEE_TYPES.contains(feeType)) {
            if (category != null && !category.isBlank()) {
                throw new IllegalArgumentException("「" + feeType + "」按**船舶总吨位**分档、**与货类无关**"
                        + "（语料 08-引航费与停泊费费率表：第二条/第四条/第七条末段，费率表货类栏为「不区分」），"
                        + "不得因货类不同而调整 ⇒ 无法按货类「" + category + "」给「" + feeType
                        + "」分档；传入的货类**不被接受**（不是忽略后照算）");
            }
            if (cargoName != null && !cargoName.isBlank()) {
                throw new IllegalArgumentException("「" + feeType + "」按**船舶总吨位**分档、**与货种/货类无关**"
                        + "（语料 08-引航费与停泊费费率表：第二条/第四条/第七条末段），"
                        + "不接受传入的货种「" + cargoName + "」（不是忽略后照算）");
            }
            return TariffRuleRow.NO_CATEGORY;
        }
        if (category != null && !category.isBlank()) {
            return category;
        }
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
