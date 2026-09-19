-- ============================================================================
-- CanalMind 领域模型能力探针
-- ============================================================================
-- 目的：验证数据模型不只是「建得起来」，而是**能回答业务问题**。
--
-- 与 V1 DDL 的关系：DDL 保证结构正确，本探针保证语义可用。
-- 每个查询对应一类真实用户问法，检查返回结果是否符合业务预期。
--
-- 用法：
--   psql -w -h 127.0.0.1 -p 5432 -U postgres -d canalmind -f probe_domain_model.sql
--
-- 时间基准：SYSTEM_TODAY = 2027-09-15（决策 C-13，通航后一年）
-- ============================================================================

\pset pager off
-- 语句级超时兜底：任何单条查询超过 30 秒即失败。
-- 探针只用于验证「模型能力是否可用」，不该出现长时间挂起；
-- 一旦某条超时，说明缺索引或写法退化，应立刻暴露而不是让人干等。
SET statement_timeout = '30s';
\echo ''
\echo '============================================================'
\echo ' CanalMind 领域模型能力探针'
\echo '============================================================'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 1】数据规模与时间窗自洽性'
\echo '  业务问题：「系统里有多少数据？覆盖什么时间段？」'
\echo '  期望：总量 227,026 行；业务日期都在 2026-09-16 ~ 2027-09-15 内'
\echo '---------------------------------------------------------------------------'
-- 注意：UNION 各分支类型必须一致，故统一 ::text（否则报
-- "UNION types bigint and text cannot be matched"）
SELECT '总行数' AS 指标,
       ((SELECT count(*) FROM port) + (SELECT count(*) FROM lock) +
        (SELECT count(*) FROM vessel) + (SELECT count(*) FROM cargo_type) +
        (SELECT count(*) FROM customer) + (SELECT count(*) FROM voyage) +
        (SELECT count(*) FROM waybill) + (SELECT count(*) FROM documents) +
        (SELECT count(*) FROM tariff_rule) + (SELECT count(*) FROM fee_record))::text AS 值
UNION ALL
SELECT 'voyage 最早/最晚', min(etd)::date || ' ~ ' || max(etd)::date FROM voyage
UNION ALL
SELECT 'waybill 最早/最晚', min(created_at)::date || ' ~ ' || max(created_at)::date FROM waybill
UNION ALL
SELECT '超出窗口的运单数（应为 0）',
       count(*)::text FROM waybill
 WHERE created_at::date < DATE '2026-09-16' OR created_at::date > DATE '2027-09-15';

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 2】费率随时间变化 —— 同一问题不同答案的物理基础'
\echo '  业务问题：「建材 800 吨过闸，过闸费多少？」'
\echo '  关键：这句话在 2026-12 问和 2027-08 问，答案必须不同'
\echo '  期望：同一档位在 4 个生效期内给出 4 个不同单价'
\echo '---------------------------------------------------------------------------'
SELECT effective_from AS 生效起,
       COALESCE(effective_to::text, '长期') AS 生效止,
       unit_price AS 单价,
       round((unit_price - lag(unit_price) OVER (ORDER BY effective_from))
             / lag(unit_price) OVER (ORDER BY effective_from) * 100, 2) AS 环比涨幅pct
  FROM tariff_rule
 WHERE fee_type = '过闸费' AND cargo_category = '建材'
   AND min_ton <= 800 AND (max_ton IS NULL OR 800 < max_ton)
 ORDER BY effective_from;

-- 同一档位是否随时间单调递增（模拟通胀/调价）
\echo '  -- 各费率档位的生效期数量分布（应无重叠，EXCLUDE 已保证）'
SELECT fee_type AS 费种, count(*) AS 规则数,
       count(DISTINCT (min_ton, COALESCE(max_ton, -1))) AS 档位数
  FROM tariff_rule GROUP BY fee_type ORDER BY fee_type;

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 3】结构化查询通道 —— 单表聚合'
\echo '  业务问题：「上个月过闸费收了多少？」'
\echo '  期望：能按时间范围聚合，且走索引'
\echo '---------------------------------------------------------------------------'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
SELECT date_trunc('month', fr.created_at) AS 月份,
       fr.fee_type AS 费种,
       count(*) AS 笔数,
       sum(fr.amount) AS 金额
  FROM fee_record fr
 WHERE fr.created_at >= TIMESTAMPTZ '2027-08-01 00:00:00+08'
   AND fr.created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+08'
 GROUP BY 1, 2 ORDER BY 2;

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 4】跨表关联 —— 复合问法'
\echo '  业务问题：「南宁货主走运河运建材的运单，货值前 5 名」'
\echo '  期望：waybill → voyage → customer/vessel/port 四表以上能连通'
\echo '---------------------------------------------------------------------------'
-- 注意别名：v = voyage，vs = vessel。voyage 没有 name 列，
-- 船名必须再 join 一次 vessel，否则报 "column v.name does not exist"。
SELECT c.name AS 货主, w.waybill_no AS 运单号,
       vs.name AS 船名, ct.name AS 货种,
       w.weight_ton AS 吨位,
       w.freight_amount AS 运费,
       p1.name || ' → ' || p2.name AS 航段
  FROM waybill w
  JOIN voyage v   ON v.id = w.voyage_id
  JOIN vessel vs  ON vs.id = v.vessel_id
  JOIN customer c ON c.id = w.customer_id
  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
  JOIN port p1 ON p1.port_code = v.origin_port
  JOIN port p2 ON p2.port_code = v.dest_port
 WHERE ct.category = '建材'
 ORDER BY w.weight_ton DESC
 LIMIT 5;

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 5】费用明细可审计 —— calc_basis 追溯'
\echo '  业务问题：「这笔 3200 元的过闸费怎么算出来的？」'
\echo '  期望：calc_basis 能还原 weight × unit_price，并指明所用费率版本'
\echo ''
\echo '  注意：这里按 id 取样而非 ORDER BY amount DESC —— fee_record.amount 上没有索引，'
\echo '  全表排序会退化为 11 万行的外部排序（实测卡住 4.5 分钟）。'
\echo '  能力探针只需证明「字段可追溯」，无需找最大额，故用轻量取样。'
\echo '---------------------------------------------------------------------------'
SELECT fr.waybill_no AS 运单号,
       fr.fee_type AS 费种,
       fr.amount AS 金额,
       fr.calc_basis ->> 'weight' AS 计费吨,
       fr.calc_basis ->> 'unit_price' AS 单价,
       fr.calc_basis ->> 'formula' AS 公式,
       tr.effective_from AS 所用费率生效日,
       tr.rule_code AS 命中规则
  FROM fee_record fr
  LEFT JOIN tariff_rule tr ON tr.id = fr.rule_id
 WHERE fr.calc_basis IS NOT NULL
 ORDER BY fr.id LIMIT 5;

-- 一致性抽检：金额 = 吨位 × 单价（对按吨计费者）
--
-- 注意：这里刻意只抽 2 万行而不是全表。全量关联（11 万 fee_record × waybill × tariff_rule）
-- 在无索引辅助的临时集群上可能跑数十秒，作为「能力探针」没必要付出这个代价。
-- 全量校验属于评测阶段（第 5 周）的活儿，届时会写成独立脚本并记录基线与耗时。
\echo '  -- 一致性抽检（前 2 万条按吨计费记录）'
SELECT count(*) AS 抽检笔数,
       count(*) FILTER (WHERE abs(fr.amount - w.weight_ton * tr.unit_price) < 0.02) AS 相符笔数,
       count(*) FILTER (WHERE abs(fr.amount - w.weight_ton * tr.unit_price) >= 0.02) AS 不符笔数
  FROM (SELECT * FROM fee_record WHERE fee_type <> '停泊费' LIMIT 20000) fr
  JOIN waybill w ON w.waybill_no = fr.waybill_no
  JOIN tariff_rule tr ON tr.id = fr.rule_id
 WHERE tr.unit = '元/吨';

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 6】文档通道关联 —— RAG 检索的锚点'
\echo '  业务问题：「这票货缺哪些单据？」'
\echo '  期望：documents 能以 waybill_no 关联，且状态可查'
\echo '---------------------------------------------------------------------------'
SELECT status AS 单据状态, count(*) AS 数量
  FROM documents GROUP BY status ORDER BY 2 DESC;

\echo '  -- 缺单据的运单（有运单但单据数不足 3 类）'
SELECT count(*) AS 单据不全的运单数 FROM (
  SELECT w.waybill_no, count(DISTINCT d.doc_type) AS types
    FROM waybill w LEFT JOIN documents d ON d.waybill_no = w.waybill_no
   WHERE w.created_at < TIMESTAMPTZ '2027-03-01 00:00:00+08'   -- 限早期运单，控制规模
   GROUP BY w.waybill_no HAVING count(DISTINCT d.doc_type) < 3
) t;

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 7】运河业务占比 —— 领域边界确认'
\echo '  业务问题：「多少业务真正走了平陆运河？」'
\echo '  期望：能判定「航次是否经运河」，占比可算'
\echo ''
\echo '  ⚠️ 本探针揭示一个建模缺口（已记入 DECISIONS.md P-06）：'
\echo '  port.is_canal_node 是**港口**属性，无法回答「这条航线是否穿越运河」。'
\echo '  南宁港六景作业区 → 北海港 这类航线两端都不是运河节点，'
\echo '  但按地理实际仍需经运河（郁江→平陆运河→钦州→沿海→北海）。'
\echo '  因此下面第二个查询给出的是**下界**（endpoint 口径），不是真实占比。'
\echo '---------------------------------------------------------------------------'
\echo '  -- 港口类型分布'
SELECT type AS 港口类型, count(*) AS 数量,
       count(*) FILTER (WHERE is_canal_node) AS 运河节点数
  FROM port GROUP BY type ORDER BY 2 DESC;

\echo ''
\echo '  -- 【下界口径】至少一端为运河节点的航次占比'
\echo '     注意：这是下界。仅一端非节点、但实际经运河的航线会被漏计。'
SELECT count(*) AS 总航次,
       count(*) FILTER (WHERE p1.is_canal_node OR p2.is_canal_node) AS 至少一端为节点,
       round(100.0 * count(*) FILTER (WHERE p1.is_canal_node OR p2.is_canal_node)
             / NULLIF(count(*), 0), 2) AS 下界占比pct
  FROM voyage v
  JOIN port p1 ON p1.port_code = v.origin_port
  JOIN port p2 ON p2.port_code = v.dest_port;

\echo ''
\echo '  -- 两端都非节点的航线（需人工判断是否真的不经运河）'
SELECT p1.name || ' → ' || p2.name AS 航线, count(*) AS 航次数,
       (SELECT count(*) FROM waybill w WHERE w.voyage_id IN (
          SELECT v2.id FROM voyage v2
           WHERE v2.origin_port = v.origin_port AND v2.dest_port = v.dest_port)) AS 运单数
  FROM voyage v
  JOIN port p1 ON p1.port_code = v.origin_port
  JOIN port p2 ON p2.port_code = v.dest_port
 WHERE NOT p1.is_canal_node AND NOT p2.is_canal_node
 GROUP BY p1.name, p2.name, v.origin_port, v.dest_port ORDER BY 2 DESC;

-- ---------------------------------------------------------------------------
\echo ''
\echo '【探针 8】状态时序自洽 —— 防止「未来运单已结算」'
\echo '  业务问题：「当前在途运单有哪些？」'
\echo '  期望：状态与时间一致，无未来单据处于终态'
\echo '---------------------------------------------------------------------------'
SELECT status AS 状态, count(*) AS 数量,
       min(created_at)::date AS 最早, max(created_at)::date AS 最晚
  FROM waybill GROUP BY status ORDER BY 2 DESC;

\echo '  -- 异常检查：状态为终态但创建时间在未来（应为 0）'
SELECT count(*) AS 时序异常运单数
  FROM waybill
 WHERE status IN ('已结算', '已完成')
   AND created_at::date > DATE '2027-09-15';

\echo ''
\echo '============================================================'
\echo ' 探针执行完毕。以上每项若符合「期望」，即证明模型语义可用。'
\echo '============================================================'
\echo ''
