package com.canalmind.structured;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 业务数据查询端口（由 {@link StructuredQueryRepository} 实现）。
 *
 * <p>与 {@link TariffRuleLookup} 同理：抽接口是为了让服务层可离线单测。
 * ⚠️ 分组维度等**取值白名单仍在实现里**，接口只固定形状——白名单不能因为"测试要方便"而放开。
 */
public interface StructuredDataLookup {

    List<StructuredQueryRepository.GroupRow> traffic(LocalDate from, LocalDate to, String metric,
                                                    String groupBy, String feeType, boolean viaCanalOnly, int topN);

    List<List<String>> waybills(String waybillNo, LocalDate from, LocalDate to, String status,
                               String cargoCategory, boolean viaCanalOnly, int limit);

    List<List<String>> vessels(String vesselId, String vesselType, Integer minGrossTonnage,
                              BigDecimal maxDraft, int limit);

    /**
     * 某货类内部的「是否危险货物」是否**一致**：一致则返回该取值，不一致返回 {@code null}。
     *
     * <p>为什么需要它：用户只说"化工品"（货类）时，危险品属性决定要不要加收 30%。
     * 语料/种子数据里**化工 3 种全是危险货物、建材/金属/农资/集装箱/其他全不是**，
     * 但**能源既有危险（成品油）也有普通（动力煤、焦炭）**——后者无法由货类推导，
     * 只能追问货种。⛔ 这个判断必须**查库得出**，不能把"哪些类一致"硬编码进代码：
     * 货种表才是权威（`cargo_type`），硬编码会在数据变更时静默算错钱。
     */
    Boolean cargoCategoryDangerous(String cargoCategory);
}
