-- ============================================================================
-- CanalMind L1 题目可行性核查（Golden Set 前置验证）
-- ============================================================================
-- 目的：Golden Set 的 L1 层（结构化可答，规划 40 条）依赖「数据模型能答出
-- 数值/聚合问题」这一假设。**这个假设若在写题时才被推翻，第 3–4 周会返工。**
-- 本脚本在进入第 3 周之前，用真实 SQL 逐条核验典型问法是否可答。
--
-- 判定标准：
--   可答 = 能用一条参数化 SQL 给出确定答案（这是「受控查询工具集」的形态）
--   不可答 = 缺字段 / 缺数据 / 语义无法从现有表推导
--
-- 用法：
--   psql -w -h 127.0.0.1 -p 5432 -U postgres -d canalmind -f check_l1_feasibility.sql
-- ============================================================================

\pset pager off
SET statement_timeout = '30s';

\echo ''
\echo '============================================================'
\echo ' L1 题目可行性核查'
\echo '============================================================'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 1】费率查询：「建材 800 吨，2027-08-01 的过闸费单价是多少？」'
\echo '  需要：按 费种+货类+吨位档+业务日期 四维定位唯一费率'
\echo '---------------------------------------------------------------------------'
SELECT fee_type, cargo_category, min_ton, max_ton, unit_price, unit,
       effective_from, COALESCE(effective_to::text,'长期') AS effective_to
  FROM tariff_rule
 WHERE fee_type='过闸费' AND cargo_category='建材'
   AND min_ton <= 800 AND (max_ton IS NULL OR 800 < max_ton)
   AND effective_from <= DATE '2027-08-01'
   AND (effective_to IS NULL OR DATE '2027-08-01' < effective_to);
\echo '  → 判定：可答（命中唯一行）'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 2】费用测算：「六景到钦州 5000 吨水泥，运费多少？」'
\echo '  需要：能按 起点+终点+货类+吨位 找到可比历史运单并给费用'
\echo '---------------------------------------------------------------------------'
SELECT count(*) AS 可比运单数,
       round(avg(w.freight_amount),2) AS 平均运费,
       round(avg(w.freight_amount / NULLIF(w.weight_ton,0)),4) AS 平均吨单价
  FROM waybill w
  JOIN voyage v ON v.id = w.voyage_id
  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
 WHERE v.origin_port = 'NN01' AND v.dest_port = 'QZ02' AND ct.category = '建材';
\echo '  ⚠️ 注意：freight_amount 若非全量回填，此问法可能返回 NULL 平均值'

-- freight_amount 回填率核查（决定该问法是否可靠）
\echo ''
\echo '  -- freight_amount 回填率（关键：决定「运费多少」类问法是否可用）'
SELECT count(*) AS 运单总数,
       count(freight_amount) AS 有运费值,
       round(100.0*count(freight_amount)/NULLIF(count(*),0),2) AS 回填率pct
  FROM waybill;

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 3】运量统计：「上个月哪个货类运量最大？」'
\echo '  需要：按时间分组 + 按货类聚合 + 排序取第一'
\echo '---------------------------------------------------------------------------'
SELECT ct.category AS 货类, ct.name AS 货种,
       count(*) AS 运单数, round(sum(w.weight_ton),2) AS 总吨位
  FROM waybill w
  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
 WHERE w.created_at >= TIMESTAMPTZ '2027-08-01 00:00:00+08'
   AND w.created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+08'
 GROUP BY 1,2 ORDER BY 4 DESC LIMIT 3;
\echo '  → 判定：可答'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 4】费率对比：「今年过闸费比去年涨了多少？」'
\echo '  需要：同一档位跨生效期比较。**但数据窗只有一年**——这是关键风险点'
\echo '---------------------------------------------------------------------------'
SELECT cargo_category, min_ton, max_ton,
       count(*) AS 生效期数,
       min(effective_from) AS 最早, max(effective_from) AS 最晚,
       round(100.0*(max(unit_price)-min(unit_price))/NULLIF(min(unit_price),0),2) AS 区间涨幅pct
  FROM tariff_rule WHERE fee_type='过闸费'
 GROUP BY 1,2,3 ORDER BY 1,2 LIMIT 5;
\echo '  ⚠️ 风险：窗口仅 2026-09~2027-09，跨「年」比较需定义"去年"为何'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 5】船闸能力匹配：「吃水 4.5 米的船能过闸吗？」'
\echo '  需要：vessel.draft_m 与 lock 闸室尺度可比较'
\echo '---------------------------------------------------------------------------'
SELECT l.name AS 船闸, l.chamber_no AS 闸室, l.capacity_ton AS 设计吨级,
       l.chamber_length_m AS 长, l.chamber_width_m AS 宽, l.is_double_line AS 双线,
       count(v.id) AS 吃水超限船数
  FROM lock l
  LEFT JOIN vessel v ON v.draft_m > 4.5
 GROUP BY 1,2,3,4,5,6 ORDER BY 7 ASC LIMIT 5;
\echo '  ⚠️ 注意：lock 有闸室尺度，但**没有水深字段**，无法直接判定"吃水是否可过"'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 6】航线可达性：「南宁到北海走不走运河？」（P-06 关联）'
\echo '  需要：判断航线是否经运河 —— 已知 P-06 缺口'
\echo '---------------------------------------------------------------------------'
SELECT p1.name || ' → ' || p2.name AS 航线,
       p1.river_section AS 起点水系, p2.river_section AS 终点水系,
       (p1.is_canal_node OR p2.is_canal_node) AS 端点判为经运河
  FROM voyage v
  JOIN port p1 ON p1.port_code=v.origin_port
  JOIN port p2 ON p2.port_code=v.dest_port
 WHERE v.origin_port='NN01' AND v.dest_port='BH01' LIMIT 1;
\echo '  ❌ 判定：不可靠（P-06 缺口：端点全非运河节点 → 误判为"不经运河"）'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 7】单证状态：「这票货还缺什么单据？」'
\echo '  需要：按运单查单据类型与状态'
\echo '---------------------------------------------------------------------------'
SELECT d.doc_type AS 单据类型, d.status AS 状态, d.submit_time AS 提交时间
  FROM documents d
 WHERE d.waybill_no = (SELECT waybill_no FROM waybill ORDER BY id LIMIT 1)
 ORDER BY d.doc_type;
\echo '  → 判定：可答'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 8】客户维度：「运量前 5 的货主是谁？」'
\echo '---------------------------------------------------------------------------'
SELECT c.name AS 货主, c.type AS 类型, c.credit_level AS 信用,
       count(*) AS 运单数, round(sum(w.weight_ton),2) AS 总吨位
  FROM waybill w JOIN customer c ON c.id = w.customer_id
 GROUP BY 1,2,3 ORDER BY 5 DESC LIMIT 5;
\echo '  → 判定：可答'

-- ---------------------------------------------------------------------------
\echo ''
\echo '【核查 9】危险品：「哪些货是危险品？走了多少？」'
\echo '---------------------------------------------------------------------------'
SELECT ct.name AS 货种, ct.hazard_class AS 危险类别,
       count(w.id) AS 运单数, round(sum(w.weight_ton),2) AS 吨位
  FROM cargo_type ct LEFT JOIN waybill w ON w.cargo_code = ct.cargo_code
 WHERE ct.is_dangerous
 GROUP BY 1,2 ORDER BY 4 DESC NULLS LAST LIMIT 5;
\echo '  → 判定：可答（但若危险品运单数为 0，则相关题目无法出）'

\echo ''
\echo '  -- 危险品运单总量（为 0 则危险品类问法无数据支撑）'
SELECT count(*) AS 危险品运单数
  FROM waybill w JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
 WHERE ct.is_dangerous;

\echo ''
\echo '============================================================'
\echo ' 核查完毕。'
\echo '============================================================'
\echo ''
