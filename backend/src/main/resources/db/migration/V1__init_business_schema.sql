-- ============================================================================
-- CanalMind 业务域建表
-- 对应规格书 §2.2「核心表设计（10 张）」
--
-- 依赖扩展：btree_gist（tariff_rule 的防重叠区间约束需要）
-- 说明：本迁移只建业务表，不含向量表（向量表随 pgvector 决策单独建）
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ----------------------------------------------------------------------------
-- 1. port 港区 / 作业区
--    平陆运河沿线及西江干线港区。port_code 为业务主键（如 LJ01）。
-- ----------------------------------------------------------------------------
CREATE TABLE port (
    port_code       VARCHAR(16)  PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL,
    river_section   VARCHAR(32)  NOT NULL,          -- 江段：郁江/西江/平陆运河/钦江/沿海
    type            VARCHAR(16)  NOT NULL,          -- 内河港/中转港/海港/枢纽
    is_canal_node   BOOLEAN      NOT NULL DEFAULT FALSE,  -- 是否为运河节点（枢纽/闸区）
    latitude        NUMERIC(9,6),
    longitude       NUMERIC(9,6),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT port_type_chk      CHECK (type IN ('内河港','中转港','海港','枢纽')),
    CONSTRAINT port_river_chk     CHECK (river_section IN ('郁江','西江','平陆运河','钦江','沿海'))
);
COMMENT ON TABLE  port IS '港区/作业区';
COMMENT ON COLUMN port.is_canal_node IS '平陆运河直接节点，用于运河专项统计';

CREATE INDEX idx_port_river_section ON port (river_section);
CREATE INDEX idx_port_type          ON port (type);

-- ----------------------------------------------------------------------------
-- 2. lock 船闸与闸次
--    平陆运河三大梯级枢纽（马道/企石/青年）各有船闸，含多闸室。
--    lock_id 为物理船闸标识，(lock_id, chamber_no) 唯一确定一个闸室。
-- ----------------------------------------------------------------------------
CREATE TABLE lock (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lock_id         VARCHAR(16)  NOT NULL,          -- 船闸标识，如 MD01
    name            VARCHAR(64)  NOT NULL,
    chamber_no      SMALLINT     NOT NULL,          -- 闸室编号
    capacity_ton    INTEGER      NOT NULL,          -- 设计通过能力（吨级）
    chamber_length_m NUMERIC(6,1),
    chamber_width_m  NUMERIC(5,1),
    operating_hours VARCHAR(32)  NOT NULL DEFAULT '24h',  -- 通航时间描述
    is_double_line  BOOLEAN      NOT NULL DEFAULT FALSE,  -- 是否双线船闸
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT lock_chamber_uk  UNIQUE (lock_id, chamber_no),
    CONSTRAINT lock_capacity_chk CHECK (capacity_ton > 0),
    CONSTRAINT lock_chamber_chk  CHECK (chamber_no > 0)
);
COMMENT ON TABLE lock IS '船闸与闸室';

CREATE INDEX idx_lock_capacity ON lock (capacity_ton);

-- ----------------------------------------------------------------------------
-- 3. vessel 船舶
--    内河干散货船/集装箱船为主。
-- ----------------------------------------------------------------------------
CREATE TABLE vessel (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vessel_id       VARCHAR(24)  NOT NULL,          -- 船舶登记标识
    name            VARCHAR(64)  NOT NULL,
    vessel_type     VARCHAR(24)  NOT NULL,          -- 船型
    deadweight_ton  INTEGER      NOT NULL,          -- 载重吨
    draft_m         NUMERIC(4,2) NOT NULL,          -- 满载吃水（米）——决定可通行闸室
    owner_id        BIGINT,                         -- 船东（客户），可空
    built_year      SMALLINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT vessel_id_uk     UNIQUE (vessel_id),
    CONSTRAINT vessel_type_chk  CHECK (vessel_type IN ('干散货船','集装箱船','多用途船','化学品船','驳船')),
    CONSTRAINT vessel_dwt_chk   CHECK (deadweight_ton > 0),
    CONSTRAINT vessel_draft_chk CHECK (draft_m > 0)
);
COMMENT ON TABLE  vessel IS '船舶';
COMMENT ON COLUMN vessel.draft_m IS '满载吃水，过闸可行性判定的关键字段';

CREATE INDEX idx_vessel_type   ON vessel (vessel_type);
CREATE INDEX idx_vessel_dwt    ON vessel (deadweight_ton);
CREATE INDEX idx_vessel_owner  ON vessel (owner_id);

-- ----------------------------------------------------------------------------
-- 4. cargo_type 货类
--    is_dangerous 决定是否需要危化品单证与专用闸次。
-- ----------------------------------------------------------------------------
CREATE TABLE cargo_type (
    cargo_code      VARCHAR(16)  PRIMARY KEY,
    name            VARCHAR(48)  NOT NULL,
    category        VARCHAR(16)  NOT NULL,          -- 大类
    is_dangerous    BOOLEAN      NOT NULL DEFAULT FALSE,
    hazard_class    VARCHAR(8),                     -- 危险品类别，非危险品为 NULL
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT cargo_category_chk CHECK (category IN ('建材','能源','金属','农资','化工','集装箱','其他')),
    -- 危险品必须有 hazard_class，非危险品必须为空
    CONSTRAINT cargo_hazard_chk CHECK (
        (is_dangerous = TRUE  AND hazard_class IS NOT NULL) OR
        (is_dangerous = FALSE AND hazard_class IS NULL)
    )
);
COMMENT ON TABLE cargo_type IS '货类';

CREATE INDEX idx_cargo_category ON cargo_type (category);
CREATE INDEX idx_cargo_danger   ON cargo_type (is_dangerous) WHERE is_dangerous = TRUE;

-- ----------------------------------------------------------------------------
-- 5. customer 客户
-- ----------------------------------------------------------------------------
CREATE TABLE customer (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     VARCHAR(24)  NOT NULL,
    name            VARCHAR(96)  NOT NULL,
    type            VARCHAR(16)  NOT NULL,          -- 货主/货代/船运公司
    credit_level    VARCHAR(4)   NOT NULL DEFAULT 'B',
    contact         VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT customer_id_uk    UNIQUE (customer_id),
    CONSTRAINT customer_type_chk CHECK (type IN ('货主','货代','船运公司')),
    CONSTRAINT customer_credit_chk CHECK (credit_level IN ('A','B','C','D'))
);
COMMENT ON TABLE customer IS '客户';

CREATE INDEX idx_customer_type   ON customer (type);
CREATE INDEX idx_customer_credit ON customer (credit_level);

-- ----------------------------------------------------------------------------
-- 6. voyage 航次
--    一个航次 = 一条船从起运港到目的港的一次运输。
-- ----------------------------------------------------------------------------
CREATE TABLE voyage (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    voyage_no       VARCHAR(32)  NOT NULL,
    vessel_id       BIGINT       NOT NULL REFERENCES vessel (id),
    origin_port     VARCHAR(16)  NOT NULL REFERENCES port (port_code),
    dest_port       VARCHAR(16)  NOT NULL REFERENCES port (port_code),
    etd             TIMESTAMPTZ,                    -- 预计离港
    eta             TIMESTAMPTZ,                    -- 预计到港
    status          VARCHAR(16)  NOT NULL DEFAULT '计划',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT voyage_no_uk     UNIQUE (voyage_no),
    CONSTRAINT voyage_status_chk CHECK (status IN ('计划','在途','已完成','已取消')),
    CONSTRAINT voyage_port_chk   CHECK (origin_port <> dest_port),
    CONSTRAINT voyage_time_chk   CHECK (eta IS NULL OR etd IS NULL OR eta >= etd)
);
COMMENT ON TABLE voyage IS '航次';

CREATE INDEX idx_voyage_vessel_etd ON voyage (vessel_id, etd DESC);
CREATE INDEX idx_voyage_status     ON voyage (status);
CREATE INDEX idx_voyage_etd        ON voyage (etd DESC);

-- ----------------------------------------------------------------------------
-- 7. waybill 运单（★ 核心事实表，数据量最大）
-- ----------------------------------------------------------------------------
CREATE TABLE waybill (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    waybill_no      VARCHAR(32)  NOT NULL,
    voyage_id       BIGINT       NOT NULL REFERENCES voyage (id),
    customer_id     BIGINT       NOT NULL REFERENCES customer (id),
    cargo_code      VARCHAR(16)  NOT NULL REFERENCES cargo_type (cargo_code),
    weight_ton      NUMERIC(10,2) NOT NULL,
    freight_amount  NUMERIC(14,2),                  -- 运费总额，由 fee_record 汇总回填
    status          VARCHAR(16)  NOT NULL DEFAULT '待装船',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT waybill_no_uk     UNIQUE (waybill_no),
    CONSTRAINT waybill_status_chk CHECK (status IN ('待装船','在途','已卸船','已结算','已取消')),
    CONSTRAINT waybill_weight_chk CHECK (weight_ton > 0),
    CONSTRAINT waybill_freight_chk CHECK (freight_amount IS NULL OR freight_amount >= 0)
);
COMMENT ON TABLE  waybill IS '运单（核心事实表）';
COMMENT ON COLUMN waybill.freight_amount IS '运费总额，由 fee_record 汇总回填，冗余用于列表查询';

-- 规格书 §2.2 要求的三个索引
CREATE INDEX idx_waybill_created_at  ON waybill (created_at DESC);
CREATE INDEX idx_waybill_cargo_code  ON waybill (cargo_code);
CREATE INDEX idx_waybill_customer_id ON waybill (customer_id);
-- 补充：运单→航次连接、按状态统计
CREATE INDEX idx_waybill_voyage      ON waybill (voyage_id);
CREATE INDEX idx_waybill_status      ON waybill (status);
-- 复合索引：按货类做时间范围聚合（评测 L1 层的典型查询场景）
CREATE INDEX idx_waybill_cargo_time  ON waybill (cargo_code, created_at DESC);

-- ----------------------------------------------------------------------------
-- 8. documents 单证
-- ----------------------------------------------------------------------------
CREATE TABLE documents (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    waybill_no      VARCHAR(32)  NOT NULL REFERENCES waybill (waybill_no) ON UPDATE CASCADE,
    doc_type        VARCHAR(24)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT '待提交',
    submit_time     TIMESTAMPTZ,
    audit_time      TIMESTAMPTZ,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT documents_uk        UNIQUE (waybill_no, doc_type),
    CONSTRAINT documents_type_chk  CHECK (doc_type IN ('出口报关单','水路运单','舱单','危险品申报单','检验检疫证','提货单')),
    CONSTRAINT documents_status_chk CHECK (status IN ('待提交','已提交','审核中','已通过','已驳回')),
    CONSTRAINT documents_audit_chk  CHECK (audit_time IS NULL OR submit_time IS NULL OR audit_time >= submit_time)
);
COMMENT ON TABLE documents IS '单证';

CREATE INDEX idx_documents_waybill ON documents (waybill_no);
CREATE INDEX idx_documents_status  ON documents (status);
CREATE INDEX idx_documents_type    ON documents (doc_type);

-- ----------------------------------------------------------------------------
-- 9. tariff_rule 计费规则（★ 带生效期 + 防重叠约束）
--
--    设计要点：费率随时间变化，因此每条规则带 effective_from / effective_to。
--    查询某天费率时必须按时间点过滤，这天然引出「快照回归」的语义。
--
--    防重叠约束：同一 (fee_type, cargo_category, 吨位区间) 在时间上不得重叠，
--    从数据库层面保证「任一时点、任一货类、任一吨位段」最多命中一条费率。
-- ----------------------------------------------------------------------------
CREATE TABLE tariff_rule (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_code       VARCHAR(32)   NOT NULL,
    fee_type        VARCHAR(16)   NOT NULL,
    cargo_category  VARCHAR(16)   NOT NULL,
    min_ton         NUMERIC(10,2) NOT NULL,
    max_ton         NUMERIC(10,2),                  -- NULL 表示上不封顶
    unit_price      NUMERIC(12,4) NOT NULL,         -- 单价（元/吨 或 元/次）
    unit            VARCHAR(8)    NOT NULL DEFAULT '元/吨',
    effective_from  DATE          NOT NULL,
    effective_to    DATE,                           -- NULL 表示长期有效
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT tariff_rule_code_uk  UNIQUE (rule_code),
    CONSTRAINT tariff_fee_type_chk  CHECK (fee_type IN ('过闸费','港杂费','装卸费','引航费','停泊费')),
    CONSTRAINT tariff_category_chk  CHECK (cargo_category IN ('建材','能源','金属','农资','化工','集装箱','其他')),
    CONSTRAINT tariff_unit_chk      CHECK (unit IN ('元/吨','元/次','元/吨天')),
    CONSTRAINT tariff_price_chk     CHECK (unit_price >= 0),
    CONSTRAINT tariff_min_ton_chk   CHECK (min_ton >= 0),
    CONSTRAINT tariff_range_chk     CHECK (max_ton IS NULL OR max_ton > min_ton),
    CONSTRAINT tariff_period_chk    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
COMMENT ON TABLE  tariff_rule IS '计费规则（带生效期）';
COMMENT ON COLUMN tariff_rule.effective_to IS 'NULL 表示长期有效；查询须按业务日期落在 [from, to) 内过滤';
COMMENT ON COLUMN tariff_rule.max_ton      IS 'NULL 表示吨位上不封顶';

-- 费率查询主索引：按 费用类型 + 货类 + 生效日 定位
CREATE INDEX idx_tariff_lookup ON tariff_rule (fee_type, cargo_category, effective_from DESC);
CREATE INDEX idx_tariff_period ON tariff_rule (effective_from, effective_to);

-- 防重叠区间约束（依赖 btree_gist）
-- 同一 (fee_type, cargo_category) 下，吨位区间与生效期均不得重叠
ALTER TABLE tariff_rule
    ADD CONSTRAINT tariff_no_overlap
    EXCLUDE USING gist (
        fee_type        WITH =,
        cargo_category  WITH =,
        numrange(min_ton, max_ton, '[)')                  WITH &&,
        daterange(effective_from, effective_to, '[)')     WITH &&
    );

-- ----------------------------------------------------------------------------
-- 10. fee_record 费用明细（★ 可审计）
--     calc_basis 记录计算依据，使费用可追溯到具体规则与公式。
-- ----------------------------------------------------------------------------
CREATE TABLE fee_record (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    waybill_no      VARCHAR(32)   NOT NULL REFERENCES waybill (waybill_no) ON UPDATE CASCADE,
    fee_type        VARCHAR(16)   NOT NULL,
    rule_id         BIGINT        REFERENCES tariff_rule (id),   -- 命中的费率规则
    amount          NUMERIC(14,2) NOT NULL,
    calc_basis      JSONB,          -- 计算依据：{weight, unit_price, formula, as_of}
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fee_type_chk   CHECK (fee_type IN ('过闸费','港杂费','装卸费','引航费','停泊费')),
    CONSTRAINT fee_amount_chk CHECK (amount >= 0)
);
COMMENT ON TABLE  fee_record IS '费用明细';
COMMENT ON COLUMN fee_record.calc_basis IS '计算依据 JSONB：{weight, unit_price, formula, as_of}，用于审计与溯源';

CREATE INDEX idx_fee_waybill  ON fee_record (waybill_no);
CREATE INDEX idx_fee_type     ON fee_record (fee_type);
CREATE INDEX idx_fee_rule     ON fee_record (rule_id);
CREATE INDEX idx_fee_created  ON fee_record (created_at DESC);
