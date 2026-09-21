package com.canalmind.structured;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 费率规则查询（**一律参数化 SQL**，决策 C-33 第 2 条）。
 *
 * <p>命中判据与语料一致，且与数据库里的 {@code tariff_no_overlap} EXCLUDE 约束**同构**：
 * 费种 + 货类 + 吨位口径 + 吨位区间（左闭右开）+ 业务日落在生效期（左闭右开）。
 * 因为约束保证"任一时点、任一货类、任一吨位口径、任一吨位段最多命中一条"，
 * 所以这里返回 {@link Optional}；**命中 0 条或多条都算异常**，不静默取第一条。
 */
public final class TariffRuleRepository implements TariffRuleLookup {

    private static final String FIND_RULE = """
            SELECT id, rule_code, fee_type, cargo_category, tonnage_basis,
                   min_ton, max_ton, unit_price, unit, effective_from, effective_to
              FROM tariff_rule
             WHERE fee_type = ?
               AND cargo_category = ?
               AND tonnage_basis = ?
               AND ? >= min_ton
               AND (max_ton IS NULL OR ? < max_ton)
               AND ? >= effective_from
               AND (effective_to IS NULL OR ? < effective_to)
             ORDER BY effective_from DESC, min_ton DESC
            """;

    private final JdbcTemplate jdbc;

    public TariffRuleRepository(DataSource dataSource) {
        this(dataSource == null ? null : new JdbcTemplate(dataSource));
    }

    public TariffRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查命中规则；**多条同时命中即抛**（说明库侧约束被绕过，属数据事故，不能悄悄取第一条）。 */
    @Override
    public Optional<TariffRuleRow> findRule(String feeType, String cargoCategory, String tonnageBasis,
                                            BigDecimal tonnage, LocalDate bizDate) {
        List<TariffRuleRow> rows = jdbc.query(FIND_RULE,
                (rs, i) -> new TariffRuleRow(
                        rs.getLong("id"),
                        rs.getString("rule_code"),
                        rs.getString("fee_type"),
                        rs.getString("cargo_category"),
                        rs.getString("tonnage_basis"),
                        rs.getBigDecimal("min_ton"),
                        rs.getBigDecimal("max_ton"),
                        rs.getBigDecimal("unit_price"),
                        rs.getString("unit"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)),
                feeType, cargoCategory, tonnageBasis, tonnage, tonnage, bizDate, bizDate);
        if (rows.size() > 1) {
            throw new IllegalStateException("费率命中 " + rows.size() + " 条（违反唯一性）："
                    + feeType + "/" + cargoCategory + "/" + tonnageBasis + "/" + tonnage + "/" + bizDate
                    + " -> " + rows.stream().map(TariffRuleRow::ruleCode).toList());
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 某费种在某业务日的全部档位（用于回答"这个费种有哪些档"）。 */
    @Override
    public List<TariffRuleRow> listByFeeType(String feeType, LocalDate bizDate) {
        return jdbc.query("""
                SELECT id, rule_code, fee_type, cargo_category, tonnage_basis,
                       min_ton, max_ton, unit_price, unit, effective_from, effective_to
                  FROM tariff_rule
                 WHERE fee_type = ?
                   AND ? >= effective_from
                   AND (effective_to IS NULL OR ? < effective_to)
                 ORDER BY cargo_category, min_ton
                """,
                (rs, i) -> new TariffRuleRow(
                        rs.getLong("id"), rs.getString("rule_code"), rs.getString("fee_type"),
                        rs.getString("cargo_category"), rs.getString("tonnage_basis"),
                        rs.getBigDecimal("min_ton"), rs.getBigDecimal("max_ton"),
                        rs.getBigDecimal("unit_price"), rs.getString("unit"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)),
                feeType, bizDate, bizDate);
    }
}
