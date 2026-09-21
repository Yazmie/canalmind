-- ============================================================================
-- V5：费率口径与语料对齐（决策 C-31 / C-36）
-- ============================================================================
-- 背景（实测，2026-09-22）
-- ----------------------------------------------------------------------------
-- 语料费率表（docs/规则语料/06、07、08、09）与库内 tariff_rule 在**四个维度上
-- 同时不同**：
--
--   | 维度       | 语料                          | 库内旧值（公式生成） |
--   |------------|-------------------------------|----------------------|
--   | 吨位档位   | 过闸费 0/500/2000/5000        | 0/1000/3000          |
--   | 费率数值   | 建材 2026 版 2.55 元/吨       | 1.0200 元/吨         |
--   | 分档依据   | 引航费/停泊费按**船舶总吨位** | 按**货类**           |
--   | 计费单位   | 港杂费 元/次                  | 元/吨                |
--
-- 而 docs/规则语料/README.md §4 早已声称"费率表取值必须与 tariff_rule 一致"——
-- **规则写了、检查没做**（validate_corpus.py 的 11 项检查没有一项校验数值）。
--
-- 决策 C-31：**以语料为唯一权威，库侧由语料派生**。本迁移负责补上"库侧能表达
-- 语料口径"所缺的结构，具体三件事：
--
--   1) vessel 增 `gross_tonnage`（船舶总吨位）
--      —— `25` 第十二条与 `05` 第六条 / `08` 第二条都规定引航费、停泊费按
--         **船舶总吨位**分档，而原 vessel 表只有 deadweight_ton（载重吨）。
--         ⛔ 这不是"顺手加的字段"：缺了它，这两类费用**按语料口径根本算不出来**
--            —— 旧库用"货类 + 货重"顶替，属建模缺陷（实测引航费 0.60~1.34 元/次）。
--
--   2) tariff_rule 增 `tonnage_basis`（计费吨位口径）
--      —— 同一张表里既要装"按载货吨位分档"（过闸费/港杂费/装卸费），
--         又要装"按船舶总吨位分档"（引航费/停泊费）。不加这一维，
--         两套吨位语义会共用 min_ton/max_ton 而**无法区分**。
--
--   3) `cargo_category` 允许哨兵值 `不区分`
--      —— `08` 第二条：引航费与停泊费"与货物所属货类无关，不得因货类不同而调整"，
--         语料费率表的货类栏写作 `—`。原 CHECK 只允许七个货类，无法表达这一事实。
--
-- ⚠️ 本迁移**只改结构、不动既有数据以外的任何东西**：
--    数据由 backend/scripts/generate_seed_data.py 重新生成后经 load_seed.sql 灌入
--    （该脚本会 TRUNCATE 全部业务表）。迁移对既有行的处理仅为"把 gross_tonnage
--    按与生成器相同的公式回填"，使迁移在**未重灌**的库上也是自洽的。
--
-- ⚠️ 重灌后必须重跑：`probe_domain_model.sql`、`check_l1_feasibility.sql`、
--    `verify_corpus_db_rates.py`（新增，语料↔库逐格比对）。
-- ============================================================================

SET client_min_messages = warning;

-- ----------------------------------------------------------------------------
-- 1. vessel.gross_tonnage（船舶总吨位）
-- ----------------------------------------------------------------------------
-- 说明：`25` 第十一条明确"船舶总吨位是**容积**指标，不等同于载货吨位"，
--       故此处**不得**用 deadweight_ton 代替。仿真数据按
--       `gross_tonnage = GREATEST(1, round(deadweight_ton * 0.55))` 生成
--       （与 generate_seed_data.py 的 gen_vessel 保持同一公式；该系数是
--        数据生成约定，不是语料规则——语料只规定"以检验证书载明为准"）。
-- ----------------------------------------------------------------------------
ALTER TABLE vessel ADD COLUMN IF NOT EXISTS gross_tonnage INTEGER;

UPDATE vessel
   SET gross_tonnage = GREATEST(1, round(deadweight_ton * 0.55)::int)
 WHERE gross_tonnage IS NULL;

ALTER TABLE vessel ALTER COLUMN gross_tonnage SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'vessel_gross_ton_chk') THEN
        ALTER TABLE vessel
            ADD CONSTRAINT vessel_gross_ton_chk CHECK (gross_tonnage > 0);
    END IF;
END $$;

COMMENT ON COLUMN vessel.gross_tonnage IS
    '船舶总吨位（容积指标，非载重吨）；引航费、停泊费的计费分档依据，见规则语料 25-第十二条';

-- ----------------------------------------------------------------------------
-- 2. tariff_rule.tonnage_basis（计费吨位口径）
-- ----------------------------------------------------------------------------
ALTER TABLE tariff_rule
    ADD COLUMN IF NOT EXISTS tonnage_basis VARCHAR(16) NOT NULL DEFAULT '载货吨位';

COMMENT ON COLUMN tariff_rule.tonnage_basis IS
    '计费吨位口径：载货吨位（过闸费/港杂费/装卸费）或 船舶总吨位（引航费/停泊费）；依据规则语料 25-第十二条';

-- ----------------------------------------------------------------------------
-- 3. cargo_category 允许哨兵 `不区分`；并下沉两条"口径配对"约束
-- ----------------------------------------------------------------------------
-- 原约束：tariff_category_chk CHECK (cargo_category IN (七个货类))
-- 新约束：七个货类 + 哨兵 `不区分`
-- ----------------------------------------------------------------------------
ALTER TABLE tariff_rule DROP CONSTRAINT IF EXISTS tariff_category_chk;
ALTER TABLE tariff_rule
    ADD CONSTRAINT tariff_category_chk CHECK (
        cargo_category IN ('建材','能源','金属','农资','化工','集装箱','其他','不区分'));

-- 口径配对（把语料的两条枚举**下沉为数据库强制**，避免"能建出算不出的规则"）：
--   ① `25` 第十二条：引航费/停泊费按船舶总吨位，且与货类无关（语料写 `—`）；
--      过闸费/港杂费/装卸费按载货吨位，且必须指明货类。
--   ② `25` 第二十七条：计费单位与费种的对应关系（港杂费因堆存仓储另有 元/吨天，
--      见 `20` 第十条与 `25` 第二十七条——本迁移允许该值，但堆存仓储费率本身
--      属"未实现清单"，无堆存记录数据故不入库）。
--
-- ⚠️ 加约束前必须处理存量行：旧库里由公式生成的**引航费/停泊费按货类分档**
--    （`tonnage_basis` 取默认值、`cargo_category` 是七个货类之一），**必然违反①**。
--    这些行本来就与语料不符、且数据可再生（load_seed.sql 会 TRUNCATE 全部业务表
--    并重灌），故此处**显式作废**，而不是把约束降级为 NOT VALID 留个活口。
--    断言：只删"违反新约束的行"，不碰本来就合规的行——避免误伤已对齐的数据。
-- ----------------------------------------------------------------------------
DO $$
DECLARE
    n_bad int;
BEGIN
    -- "合规"= 口径配对 ② 与 单位配对 ③ **同时**成立（下面两条 CHECK 的合取）。
    -- 两个条件必须一起判：实测只判口径配对时，旧港杂费（元/吨）与旧停泊费（元/吨）
    -- 会被留下，随后 ADD CONSTRAINT 直接报 "is violated by some row"（V5 第 155 行踩到）。
    CREATE TEMP TABLE _v5_bad_rule AS
    SELECT id FROM tariff_rule
     WHERE NOT (
           ((fee_type IN ('引航费','停泊费')
                 AND tonnage_basis = '船舶总吨位' AND cargo_category = '不区分')
         OR (fee_type IN ('过闸费','港杂费','装卸费')
                 AND tonnage_basis = '载货吨位' AND cargo_category <> '不区分'))
       AND
           ((fee_type IN ('过闸费','装卸费') AND unit = '元/吨')
         OR (fee_type = '引航费'            AND unit = '元/次')
         OR (fee_type = '港杂费'            AND unit IN ('元/次','元/吨天'))
         OR (fee_type = '停泊费'            AND unit = '元/吨天')));

    SELECT count(*) INTO n_bad FROM _v5_bad_rule;

    IF n_bad > 0 THEN
        RAISE NOTICE 'V5：作废 % 条与语料口径不符的旧费率行（及其费用明细）；随后须重灌 load_seed.sql', n_bad;
        DELETE FROM fee_record WHERE rule_id IN (SELECT id FROM _v5_bad_rule);
        DELETE FROM tariff_rule WHERE id IN (SELECT id FROM _v5_bad_rule);
    ELSE
        RAISE NOTICE 'V5：未发现与语料口径不符的旧费率行（库内数据已对齐）';
    END IF;

    DROP TABLE _v5_bad_rule;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tariff_basis_pair_chk') THEN
        ALTER TABLE tariff_rule
            ADD CONSTRAINT tariff_basis_pair_chk CHECK (
                (fee_type IN ('引航费','停泊费')
                     AND tonnage_basis = '船舶总吨位' AND cargo_category = '不区分')
             OR (fee_type IN ('过闸费','港杂费','装卸费')
                     AND tonnage_basis = '载货吨位' AND cargo_category <> '不区分'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tariff_unit_pair_chk') THEN
        ALTER TABLE tariff_rule
            ADD CONSTRAINT tariff_unit_pair_chk CHECK (
                (fee_type IN ('过闸费','装卸费') AND unit = '元/吨')
             OR (fee_type IN ('引航费')            AND unit = '元/次')
             OR (fee_type IN ('港杂费')            AND unit IN ('元/次','元/吨天'))
             OR (fee_type IN ('停泊费')            AND unit = '元/吨天'));
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 4. 防重叠约束：把 tonnage_basis 也纳入 key
-- ----------------------------------------------------------------------------
-- 原约束把"同一（费种 + 货类）"下的吨位区间与生效期都视为同一条时间线，
-- 但"载货吨位 500 吨"与"船舶总吨位 500 吨"**不是同一件事**，不该互相排他。
-- 纳入后语义为：同一费种 + 同一货类 + 同一吨位口径下不得重叠（这正是我们要的）。
-- ⚠️ 同时消除一个真实缺陷：旧数据把 effective_to 写成"下一期起始日前一天"
--    却又按 `<=` 匹配，于是 daterange[...,'[)') 在每期边界留下**一天空隙**
--    （2026-12-31 无规则覆盖，尽管实际匹配能取到）。C-36① 统一为左闭右开后，
--    生效期改为"下期起始日"本身，区间首尾相接，空隙消失。
-- ----------------------------------------------------------------------------
ALTER TABLE tariff_rule DROP CONSTRAINT IF EXISTS tariff_no_overlap;
ALTER TABLE tariff_rule
    ADD CONSTRAINT tariff_no_overlap
    EXCLUDE USING gist (
        fee_type        WITH =,
        cargo_category  WITH =,
        tonnage_basis   WITH =,
        numrange(min_ton, max_ton, '[)')                  WITH &&,
        daterange(effective_from, effective_to, '[)')     WITH &&
    );

CREATE INDEX IF NOT EXISTS idx_tariff_basis ON tariff_rule (fee_type, tonnage_basis, effective_from);
