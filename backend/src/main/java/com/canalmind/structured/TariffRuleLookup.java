package com.canalmind.structured;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 费率查询端口（由 {@link TariffRuleRepository} 实现）。
 *
 * <p>抽成接口的唯一理由是**可测**：服务层的"缺参追问 / 口径拒绝 / 未命中如实说明"
 * 这些分支必须能离线单测，而真实实现要连库。
 */
public interface TariffRuleLookup {

    /** 按 费种 + 货类 + 吨位口径 + 吨位 + 业务日 命中唯一规则。 */
    Optional<TariffRuleRow> findRule(String feeType, String cargoCategory, String tonnageBasis,
                                     BigDecimal tonnage, LocalDate bizDate);

    /** 某费种在某业务日的全部档位（用于"未命中时给出可核对的原因"）。 */
    List<TariffRuleRow> listByFeeType(String feeType, LocalDate bizDate);
}
