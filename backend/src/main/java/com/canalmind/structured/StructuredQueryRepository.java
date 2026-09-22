package com.canalmind.structured;

import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.canalmind.observability.AiObservation;
import com.canalmind.observability.AiSpans;

/**
 * 结构化通道的 SQL 层：**全部参数化**，且分组/口径只能取白名单（决策 C-33 第 2 条）。
 *
 * <p><b>为什么不做 Text-to-SQL</b>：知识库无 Text-to-SQL / Schema Linking 覆盖
 * （`AGENTS.md` §4.6 已登记为缺口），规格书 §0.3 也把它降级为"契约化受控查询"。
 * 让模型生成 SQL 意味着把"注入面 + 口径正确性"一起交给模型；本层把这两个风险
 * 从**模型侧**移到**代码侧**：模型只能给枚举值，SQL 片段从映射表里取。
 *
 * <p>⚠️ <b>数值一律在 SQL 里 {@code ::text} 定型</b>（金额 {@code round(...,2)}）：
 * 结构化通道的产物要拿去和数据库做**逐字符**对账（Execution Accuracy），
 * 若让 JDBC 按 Java 规则格式化，就会出现"值相同、字面不同"的假不一致。
 */
public final class StructuredQueryRepository implements StructuredDataLookup {

    /** 业务窗口（决策 P-04 方案 B）：通航后一年。 */
    public static final LocalDate BUSINESS_START = LocalDate.of(2026, 9, 16);
    public static final LocalDate BUSINESS_END = LocalDate.of(2027, 9, 15);

    private final JdbcTemplate jdbc;
    /** 业务 Span 的注册表（规格书 §7.2 的 {@code db.sql.execute}）；无观测需求时为 NOOP。 */
    private final ObservationRegistry observationRegistry;

    public StructuredQueryRepository(DataSource dataSource) {
        this(dataSource == null ? null : new JdbcTemplate(dataSource), ObservationRegistry.NOOP);
    }

    public StructuredQueryRepository(JdbcTemplate jdbc) {
        this(jdbc, ObservationRegistry.NOOP);
    }

    /**
     * 同上，但**带上观测注册表**（规格书 §7.2 的 {@code db.sql.execute}）。
     *
     * <p>规格书第 553 行要求这一步记"耗时、行数"。耗时<b>由 Micrometer 自动计时</b>
     * （Span 的起止就是耗时的定义），故这里只显式记<b>行数</b>（高基数 {@code db.rows}）
     * ——⛔ 绝不再自己塞一个"耗时"属性：那样会出现两个口径不同的耗时（一个由框架测、
     * 一个由人肉测），而它们必然会漂。
     *
     * <p>⚠️ 既有两个构造器保持不变、委托给 {@link ObservationRegistry#NOOP}：
     * 它们被 {@code StructuredQueryCli}、{@code ExecutionAccuracyRunTest}、
     * {@code QuestionPipeline.create} 使用，**旧路径零变化**。
     *
     * <p>⚠️ 重载是 <b>public</b> 而非包内可见：SQL 执行的埋点若只能从 Spring 装配路径接入，
     * 那么"没有 Spring 的评测路径"（CLI / 评测运行器）就永远拿不到 Span——
     * 而 Trace 的主要来源恰恰是那条路径。
     */
    public StructuredQueryRepository(JdbcTemplate jdbc, ObservationRegistry observationRegistry) {
        this.jdbc = jdbc;
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    }

    /** 结果集就是"标签 + 值 + 计数"三列，与分组维度无关——这样上层只有一种渲染逻辑。 */
    public record GroupRow(String label, String value, long count) {
    }

    /** 分组维度 → SQL 表达式（**白名单**；表里没有的取值一律拒绝）。 */
    private static final Map<String, String> GROUP_EXPR = Map.of(
            "货类", "ct.category",
            "货种", "ct.name",
            "起运港", "coalesce(po.name, v.origin_port)",
            "目的港", "coalesce(pd.name, v.dest_port)",
            "客户", "coalesce(cu.name, '未知客户')",
            "月份", "to_char(w.created_at, 'YYYY-MM')");

    /**
     * 运量/运单数/费用金额统计。
     *
     * @param metric        吨位 / 运单数 / 费用金额（白名单）
     * @param groupBy       货类 / 货种 / 起运港 / 目的港 / 客户 / 月份 / 无
     * @param feeType       仅 metric=费用金额 时生效；null = 全部费种
     * @param viaCanalOnly  是否只统计经运河航次
     */
    @Override
    public List<GroupRow> traffic(LocalDate from, LocalDate to, String metric, String groupBy,
                                 String feeType, boolean viaCanalOnly, int topN) {
        boolean byFee = "费用金额".equals(metric);
        String labelExpr;
        String groupClause;
        if (groupBy == null || "无".equals(groupBy)) {
            labelExpr = "'合计'";
            groupClause = "";
        } else {
            String expr = GROUP_EXPR.get(groupBy);
            if (expr == null) {
                throw new IllegalArgumentException("不支持的分组维度：" + groupBy + "（白名单：" + GROUP_EXPR.keySet() + " / 无）");
            }
            labelExpr = expr;
            groupClause = " GROUP BY " + expr;
        }

        String valueExpr;
        String countExpr;
        String orderExpr;
        if (byFee) {
            valueExpr = "coalesce(round(sum(f.amount), 2), 0)::text";
            countExpr = "count(DISTINCT w.waybill_no)";
            orderExpr = "coalesce(sum(f.amount), 0)";
        } else if ("吨位".equals(metric)) {
            valueExpr = "coalesce(round(sum(w.weight_ton), 2), 0)::text";
            countExpr = "count(*)";
            orderExpr = "coalesce(sum(w.weight_ton), 0)";
        } else if ("运单数".equals(metric)) {
            // ⚠️ 排序用**数值**而不是 valueExpr：valueExpr 已 ::text 定型，
            // 按文本降序会得出 "9" > "10" 这种错误名次（实测踩到，见工程复盘/数据一致性）
            valueExpr = "count(*)::text";
            countExpr = "count(*)";
            orderExpr = "count(*)";
        } else {
            throw new IllegalArgumentException("不支持的统计口径：" + metric + "（白名单：吨位 / 运单数 / 费用金额）");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(labelExpr).append(" AS label, ")
                .append(valueExpr).append(" AS value, ").append(countExpr).append(" AS n")
                .append("  FROM waybill w")
                .append("  JOIN voyage v ON v.id = w.voyage_id")
                .append("  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code")
                .append("  LEFT JOIN customer cu ON cu.id = w.customer_id")
                .append("  LEFT JOIN port po ON po.port_code = v.origin_port")
                .append("  LEFT JOIN port pd ON pd.port_code = v.dest_port");
        List<Object> params = new ArrayList<>();
        if (byFee) {
            sql.append("  LEFT JOIN fee_record f ON f.waybill_no = w.waybill_no");
            if (feeType != null && !feeType.isBlank()) {
                sql.append(" AND f.fee_type = ?");
                params.add(feeType);
            }
        }
        sql.append(" WHERE w.created_at::date >= ?::date AND w.created_at::date <= ?::date");
        params.add(from.toString());
        params.add(to.toString());
        if (viaCanalOnly) {
            sql.append(" AND v.via_canal = true");
        }
        sql.append(groupClause);
        // 并列规则事前写定（决策 C-35）：值降序 → 标签升序，保证结果集可逐行比对
        sql.append(" ORDER BY ").append(orderExpr).append(" DESC NULLS LAST, label ASC");
        sql.append(" LIMIT ?");
        params.add(topN);

        Observation observation = AiObservation.start(AiSpans.DB_SQL_EXECUTE, observationRegistry);
        return observation.observe(() -> {
            // ⚠️ 属性必须在 lambda **内部**写：observe(...) 退出时 Span 就已 stop，
            // 之后再调 highCardinality 只改了一个已经上报完的 context（不会出现在任何 handler 里）。
            List<GroupRow> rows = jdbc.query(sql.toString(),
                    (rs, i) -> new GroupRow(rs.getString("label"), rs.getString("value"), rs.getLong("n")),
                    params.toArray());
            AiObservation.highCardinality(observation, AiSpans.KEY_DB_ROWS,
                    Integer.toString(rows.size()));
            return rows;
        });
    }

    /** 费用金额合计（按费种分列），用于"费用构成"类问题。 */
    public List<GroupRow> feeAmountByType(LocalDate from, LocalDate to, boolean viaCanalOnly) {
        String sql = """
                SELECT f.fee_type AS label, round(sum(f.amount), 2)::text AS value, count(*) AS n
                  FROM fee_record f
                  JOIN waybill w ON w.waybill_no = f.waybill_no
                  JOIN voyage v ON v.id = w.voyage_id
                 WHERE w.created_at::date >= ?::date AND w.created_at::date <= ?::date
                """ + (viaCanalOnly ? "   AND v.via_canal = true" : "") + """
                 GROUP BY f.fee_type
                 ORDER BY round(sum(f.amount), 2) DESC, f.fee_type ASC
                """;
        Observation observation = AiObservation.start(AiSpans.DB_SQL_EXECUTE, observationRegistry);
        return observation.observe(() -> {
            List<GroupRow> rows = jdbc.query(sql,
                    (rs, i) -> new GroupRow(rs.getString("label"), rs.getString("value"), rs.getLong("n")),
                    from.toString(), to.toString());
            AiObservation.highCardinality(observation, AiSpans.KEY_DB_ROWS,
                    Integer.toString(rows.size()));
            return rows;
        });
    }

    /** 运单明细（含货种/货类/单证汇总）；所有过滤条件都可空，但服务层保证至少一个条件。 */
    @Override
    public List<List<String>> waybills(String waybillNo, LocalDate from, LocalDate to, String status,
                                      String cargoCategory, boolean viaCanalOnly, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT w.waybill_no,
                       ct.name AS cargo_name,
                       ct.category AS cargo_category,
                       w.weight_ton::text AS weight_ton,
                       round(w.freight_amount, 2)::text AS freight_amount,
                       w.status,
                       w.created_at::date::text AS biz_date,
                       coalesce(po.name, v.origin_port) AS origin,
                       coalesce(pd.name, v.dest_port) AS dest,
                       CASE WHEN v.via_canal THEN '是' ELSE '否' END AS via_canal,
                       coalesce((SELECT string_agg(d.doc_type || ':' || d.status, '；' ORDER BY d.doc_type)
                                   FROM documents d WHERE d.waybill_no = w.waybill_no), '无单证') AS docs
                  FROM waybill w
                  JOIN voyage v ON v.id = w.voyage_id
                  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
                  LEFT JOIN port po ON po.port_code = v.origin_port
                  LEFT JOIN port pd ON pd.port_code = v.dest_port
                 WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (waybillNo != null && !waybillNo.isBlank()) {
            sql.append(" AND w.waybill_no = ?");
            params.add(waybillNo);
        }
        if (from != null) {
            sql.append(" AND w.created_at::date >= ?::date");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND w.created_at::date <= ?::date");
            params.add(to.toString());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND w.status = ?");
            params.add(status);
        }
        if (cargoCategory != null && !cargoCategory.isBlank()) {
            sql.append(" AND ct.category = ?");
            params.add(cargoCategory);
        }
        if (viaCanalOnly) {
            sql.append(" AND v.via_canal = true");
        }
        // 排序键必须唯一且确定：日期降序 + 运单号升序
        sql.append(" ORDER BY w.created_at DESC, w.waybill_no ASC LIMIT ?");
        params.add(limit);

        Observation observation = AiObservation.start(AiSpans.DB_SQL_EXECUTE, observationRegistry);
        return observation.observe(() -> {
            List<List<String>> rows = jdbc.query(sql.toString(), (rs, i) -> {
                List<String> row = new ArrayList<>();
                for (int c = 1; c <= 11; c++) {
                    row.add(rs.getString(c));
                }
                return List.copyOf(row);
            }, params.toArray());
            AiObservation.highCardinality(observation, AiSpans.KEY_DB_ROWS,
                    Integer.toString(rows.size()));
            return rows;
        });
    }

    /** 船舶明细。 */
    @Override
    public List<List<String>> vessels(String vesselId, String vesselType, Integer minGrossTonnage,
                                     java.math.BigDecimal maxDraft, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT ve.vessel_id, ve.name, ve.vessel_type,
                       ve.deadweight_ton::text, ve.gross_tonnage::text,
                       ve.draft_m::text, ve.built_year::text,
                       coalesce(cu.name, '未知船东') AS owner
                  FROM vessel ve
                  LEFT JOIN customer cu ON cu.id = ve.owner_id
                 WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (vesselId != null && !vesselId.isBlank()) {
            sql.append(" AND ve.vessel_id = ?");
            params.add(vesselId);
        }
        if (vesselType != null && !vesselType.isBlank()) {
            sql.append(" AND ve.vessel_type = ?");
            params.add(vesselType);
        }
        if (minGrossTonnage != null) {
            sql.append(" AND ve.gross_tonnage >= ?");
            params.add(minGrossTonnage);
        }
        if (maxDraft != null) {
            sql.append(" AND ve.draft_m <= ?");
            params.add(maxDraft);
        }
        sql.append(" ORDER BY ve.vessel_id ASC LIMIT ?");
        params.add(limit);
        Observation observation = AiObservation.start(AiSpans.DB_SQL_EXECUTE, observationRegistry);
        return observation.observe(() -> {
            List<List<String>> rows = jdbc.query(sql.toString(), (rs, i) -> {
                List<String> row = new ArrayList<>();
                for (int c = 1; c <= 8; c++) {
                    row.add(rs.getString(c));
                }
                return List.copyOf(row);
            }, params.toArray());
            AiObservation.highCardinality(observation, AiSpans.KEY_DB_ROWS,
                    Integer.toString(rows.size()));
            return rows;
        });
    }

    @Override
    public Boolean cargoCategoryDangerous(String cargoCategory) {
        Observation observation = AiObservation.start(AiSpans.DB_SQL_EXECUTE, observationRegistry);
        return observation.observe(() -> {
            // count(DISTINCT is_dangerous) = 1 ⇒ 该货类内部属性一致，可安全推导；= 2 ⇒ 无法推导（须追问货种）
            var row = jdbc.queryForMap("""
                    SELECT count(DISTINCT is_dangerous) AS kinds, bool_and(is_dangerous) AS uniform
                      FROM cargo_type
                     WHERE category = ?
                    """, cargoCategory);
            // ⚠️ 这是**聚合查询**，结果集恒为 1 行。如实记 1（而不是硬凑一个"受影响行数"）：
            //    db.rows 的含义是"这条 SQL 返回了几行"，聚合的答案就是 1 行——
            //    想知道"覆盖了多少货种"要另建属性，⛔ 不许把 kinds 塞进 db.rows（那是两个口径）。
            AiObservation.highCardinality(observation, AiSpans.KEY_DB_ROWS,
                    row.isEmpty() ? "0" : "1");
            if (row.isEmpty() || ((Number) row.get("kinds")).intValue() == 0) {
                throw new IllegalArgumentException("未知货类：" + cargoCategory);
            }
            if (((Number) row.get("kinds")).intValue() > 1) {
                return null;
            }
            return (Boolean) row.get("uniform");
        });
    }

    /** 业务窗口内的运单总数（用于"窗口外无数据"的如实说明）。 */
    public long waybillCountInWindow() {
        Observation observation = AiObservation.start(AiSpans.DB_SQL_EXECUTE, observationRegistry);
        return observation.observe(() -> {
            Long n = jdbc.queryForObject("SELECT count(*) FROM waybill WHERE created_at::date >= ?::date AND created_at::date <= ?::date",
                    Long.class, BUSINESS_START.toString(), BUSINESS_END.toString());
            // 同 cargoCategoryDangerous：聚合查询的结果集恒为 1 行（无匹配时 count=0 仍是 1 行）
            AiObservation.highCardinality(observation, AiSpans.KEY_DB_ROWS, "1");
            return n == null ? 0L : n;
        });
    }
}
