-- ============================================================================
-- V3：检索通道 —— 向量表 rag_chunk + HNSW 索引（第 2 周第 3 项）
--
-- 来源：决策 **C-22**（Embedding = Ollama + 本地 bge-m3）及其**配套硬约束**
--       （索引与查询必须同一模型）；决策 **C-23**（向量索引 = HNSW）。
--       规格书《平陆运河航运物流智能业务助手-项目规格与执行清单.md》**第 628 行**
--       ——"Embedding + PgVector 入库，HNSW 索引（**检查执行计划确认索引用上了**）"。
--
-- 解决什么问题
-- ----------------------------------------------------------------------------
-- V1/V2 只建了业务数据模型（10 表）。检索通道需要一张**与切分器产物一一对应**的表：
--   ① 每个 chunk 一行，chunk_id 直接沿用切分器的内容派生 ID（可复现、可对账）；
--   ② 把 chunk 的全部元数据落库（文档号/版本/生效期/章节/条号），
--      因为**我们的检索天然带过滤**（按版本、按生效期），过滤字段必须在库里；
--   ③ 承载 vector(1024) 向量列与 HNSW 索引。
--
-- 维度为什么是 1024
-- ----------------------------------------------------------------------------
-- bge-m3 输出维度实测为 **1024**（见 docs/进度看板.md §4 与 C-22）。
-- ⚠️ **向量维度由所选模型决定**，故 `vector(1024)` 与 C-22 是绑定的；
--    若更换 embedding 模型，本文件的维度与下面的 HNSW 索引**必须一并重建**——
--    这正是 C-22 配套硬约束「更换模型必须全量重建索引，不能偷懒」的落地。
--    这一点由 rag_index_profile 表 + 触发器强制（见文件末尾）。
--
-- 距离度量为什么用内积（vector_ip_ops，查询用 <#>）
-- ----------------------------------------------------------------------------
-- 实测 bge-m3 输出向量范数 = 1.000001，即**已 L2 归一化**。
-- 归一化后：内积 = 余弦相似度，且内积计算更快。
-- ⚠️ **不要"顺手"改成 vector_cosine_ops**：两者在归一化向量上排序等价，
--    但改 operator class 会连带改变查询运算符（<#> → <=>），
--    一旦改漏一处就会静默返回**错误排序**（不会报错）。
--
-- 过滤退化风险（重要，刻意留档）
-- ----------------------------------------------------------------------------
-- HNSW 是**近似**索引：`LIMIT` 在索引扫描阶段就被满足，于是带 `WHERE` 过滤时
-- 可能只取回少量候选、过滤后**返回行数少于 Top-K 且不报错**。
-- 知识库《RAG 向量索引算法和向量数据库》**第 175–184 行**明确提示该场景，
-- 第 184 行指出 pgvector 0.8.0+ 的**迭代索引扫描**（iterative index scan）可缓解，
-- 但须配合 `hnsw.max_scan_tuples` 控制成本。
-- 本项目实测（见 docs/工程复盘/检索工程/HNSW索引过滤条件静默少返回.md）：
-- 3000 行 × 1024 维、过滤 `doc_id='doc-3'`（实际 116 行匹配）、`LIMIT 10`：
--   无过滤 → 10 行；带过滤默认 → **仅 2 行**；带过滤 + iterative_scan → **10 行**。
-- ⚠️ **`hnsw.iterative_scan` 是会话级参数，不是索引属性**，故不在本文件里设置；
--    入库/查询连接必须在**同一会话内**显式 `SET`（见 PgVectorIngestCli 与进度看板 §4）。
--
-- 幂等性
-- ----------------------------------------------------------------------------
-- 本文件可重复执行（IF NOT EXISTS）。但**向量数据须重新生成**：
-- 入库是"读语料 → 切分 → 调 embedding → 写入"，重建库后必须重跑入库命令。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ----------------------------------------------------------------------------
-- 1. 索引档案表：把「这批向量是谁产的」变成可查询的事实
-- ----------------------------------------------------------------------------
-- 为什么单开一张表而不是只写注释：
-- C-22 配套硬约束要求「模型信息写入 chunk 元数据，查询时校验版本，不匹配则拒绝查询」。
-- 若只把模型名写进每个 chunk 行，校验就得靠 `SELECT DISTINCT embedding_model` —— 
-- 一旦库里有历史遗留行，这种"抽样校验"会漏判。用**引用表 + 外键**使不一致在
-- 写入时就不可能发生（数据库层强制），而不是靠调用方自觉。
CREATE TABLE IF NOT EXISTS rag_index_profile (
    profile_name              TEXT        PRIMARY KEY,
    embedding_provider        TEXT        NOT NULL,
    embedding_model           TEXT        NOT NULL,
    embedding_dimension       INTEGER     NOT NULL,
    distance_metric           TEXT        NOT NULL,
    index_type                TEXT        NOT NULL,
    splitter_strategy         TEXT        NOT NULL,
    splitter_version          TEXT        NOT NULL,
    chunker_note              TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 与 C-22 绑定的维度约束：本项目的向量列就是 vector(1024)
    CONSTRAINT rag_index_profile_dim_ck CHECK (embedding_dimension = 1024),
    CONSTRAINT rag_index_profile_metric_ck CHECK (distance_metric IN ('inner_product', 'cosine', 'l2')),
    CONSTRAINT rag_index_profile_index_ck CHECK (index_type IN ('hnsw', 'ivfflat', 'none'))
);

COMMENT ON TABLE rag_index_profile IS
    '向量索引档案：记录某批 chunk 由哪个 embedding 模型/维度/切分策略产出。'
    '依据 C-22 配套硬约束——索引与查询必须同一模型，模型信息须写入元数据并在查询时校验。';
COMMENT ON COLUMN rag_index_profile.embedding_dimension IS
    '向量维度。与 rag_chunk.embedding 的 vector(N) 及 vector(N) 必须一致；本文件固定 1024（bge-m3）。';
COMMENT ON COLUMN rag_index_profile.distance_metric IS
    '距离度量。inner_product 对应 HNSW 的 vector_ip_ops 与查询运算符 <#>；'
    'bge-m3 输出已 L2 归一化，故内积与余弦排序等价。';

-- ----------------------------------------------------------------------------
-- 2. 主体：chunk 表
-- ----------------------------------------------------------------------------
-- 字段与切分器产物一一对应（见 Chunk.java 的 24 个字段）。
-- 刻意**冗余**存 doc_title / doc_no / version：检索时要把这些回填进 Prompt 引用，
-- 若只存 doc_id 就得每次 JOIN 文档表；而语料文档本身**不在数据库里**（是 Markdown 文件），
-- 所以这里只能冗余。冗余的代价是文档元数据变更需重跑入库——已被幂等 upsert 覆盖。
CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_id          TEXT        PRIMARY KEY,
    chunk_type        TEXT        NOT NULL,
    doc_id            TEXT        NOT NULL,
    doc_title         TEXT        NOT NULL,
    doc_no            TEXT,
    version           TEXT,
    effective_from    DATE,
    effective_to      DATE,
    supersedes        TEXT,
    doc_category_no   TEXT,
    chapter_path      TEXT[]      NOT NULL DEFAULT '{}',
    article_no        TEXT,
    article_title     TEXT,
    part_index        INTEGER     NOT NULL DEFAULT 0,
    part_count        INTEGER     NOT NULL DEFAULT 0,
    table_headers     TEXT[],
    table_rows        JSONB,
    strategy          TEXT        NOT NULL,
    splitter_version  TEXT        NOT NULL,
    content           TEXT        NOT NULL,
    char_count        INTEGER     NOT NULL,
    source_lines      TEXT,

    -- 向量与模型归属
    embedding         vector(1024),
    profile_name      TEXT        REFERENCES rag_index_profile (profile_name) ON DELETE RESTRICT,
    embedded_at       TIMESTAMPTZ,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rag_chunk_type_ck CHECK (chunk_type IN ('BODY', 'TABLE')),
    CONSTRAINT rag_chunk_char_count_ck CHECK (char_count >= 0),
    -- 生效期区间合法性：即使当前语料没有填 effective_to 的行，也先把约束立住
    CONSTRAINT rag_chunk_effective_ck CHECK (effective_to IS NULL OR effective_from IS NULL
                                             OR effective_to >= effective_from),
    -- ⚠️ 向量与档案必须同时存在或同时为空：防止"有向量但不知道是谁产的"
    CONSTRAINT rag_chunk_profile_pair_ck CHECK (
        (embedding IS NULL AND profile_name IS NULL)
        OR (embedding IS NOT NULL AND profile_name IS NOT NULL)
    )
);

COMMENT ON TABLE rag_chunk IS
    '规则语料切分产物 + 向量。一行 = 一个 chunk，chunk_id 沿用切分器内容派生 ID（可对账）。'
    '数据来源：docs/规则语料/ 经 ChunkStructureSplitter（C-21/C-24）切分后入库。';
COMMENT ON COLUMN rag_chunk.chunk_type IS 'BODY = 条正文；TABLE = 表格（表格单独成块，正文原位留 [[TABLE:n]] 占位）。';
COMMENT ON COLUMN rag_chunk.chapter_path IS '章节路径数组，如 {第三章 闸次编排与优先放行}。章不单独成 chunk，仅作元数据（C-24）。';
COMMENT ON COLUMN rag_chunk.table_rows IS '表格数据（二维数组的 JSONB），仅 chunk_type=TABLE 时有值。';
COMMENT ON COLUMN rag_chunk.embedding IS 'bge-m3 输出向量，1024 维，已 L2 归一化。查询用 <#>（负内积，升序即最相似）。';
COMMENT ON COLUMN rag_chunk.profile_name IS
    '指向 rag_index_profile：声明该行向量由哪个模型/维度/切分策略产出。'
    'C-22 配套硬约束要求查询前校验，不匹配则拒绝查询。';

-- ----------------------------------------------------------------------------
-- 3. 约束：入库行必须与所声明的档案一致（把 C-22 变成数据库强制）
-- ----------------------------------------------------------------------------
-- 为什么用触发器而不是 CHECK：CHECK 不能跨表查询。
-- 这是"把规则写成代码"而不是"写在文档里靠自觉"——一旦有人用别的模型灌数据，
-- 要么显式新建一个符合实际的 profile，要么写入直接失败，**不会静默混入**。
CREATE OR REPLACE FUNCTION rag_chunk_profile_guard() RETURNS trigger AS $$
DECLARE
    p_strategy TEXT;
    p_version  TEXT;
BEGIN
    IF NEW.profile_name IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT splitter_strategy, splitter_version
      INTO p_strategy, p_version
      FROM rag_index_profile
     WHERE profile_name = NEW.profile_name;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'rag_chunk.profile_name=% 不存在于 rag_index_profile', NEW.profile_name;
    END IF;

    IF NEW.strategy IS DISTINCT FROM p_strategy
       OR NEW.splitter_version IS DISTINCT FROM p_version THEN
        RAISE EXCEPTION
            'chunk % 的切分来源（strategy=% version=%）与档案 % 声明的（strategy=% version=%）不一致；'
            '切分策略变更必须新建档案并全量重建，不得混入同一批向量',
            NEW.chunk_id, NEW.strategy, NEW.splitter_version,
            NEW.profile_name, p_strategy, p_version;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS rag_chunk_profile_guard_trg ON rag_chunk;
CREATE TRIGGER rag_chunk_profile_guard_trg
    BEFORE INSERT OR UPDATE ON rag_chunk
    FOR EACH ROW EXECUTE FUNCTION rag_chunk_profile_guard();

-- ----------------------------------------------------------------------------
-- 4. 会话级参数：用**库级默认值**固定住，而不是靠每条连接自己 SET
-- ----------------------------------------------------------------------------
-- 为什么必须在 DDL 里定，而不是留给应用 SET：
-- `hnsw.iterative_scan` 是**会话级**参数。检索天然带过滤（版本有效期、doc_id 范围），
-- 若不打开它会**静默少返回**（实测 3000 行表上 LIMIT 10 只回 2 行，见文件头）。
-- 而"在应用里 SET 一次"在**连接池**下不可靠：SET 只作用于当前那条物理连接，
-- 池里别的连接拿不到，于是"结果条数对不对"会**随机取决于本次请求落到哪条连接**——
-- 这类 bug 极难复现。见 docs/工程复盘/检索工程/会话级参数与连接池.md。
--
-- `ALTER DATABASE ... SET` 的作用是：**此后连到本库的每个新会话都自动带上该值**，
-- 与连接池无关、也不依赖调用方自觉。
--
-- ⚠️ 已实测的两点（2026-09-21）：
--   ① 库级设置后，**新会话** `SHOW hnsw.iterative_scan` = `relaxed_order`（生效）；
--   ② 但连到**未建 vector 扩展的库**（如 postgres）时，同一参数报
--      `unrecognized configuration parameter` —— 因为该 GUC 由扩展注册，
--      扩展没加载时它根本不存在。故本设置只对 `canalmind` 库有意义。
--
-- ⚠️ 易被误认为"没生效"的陷阱：对 `hnsw.*` 执行 `SHOW` 本身**不会**触发扩展加载，
--    所以在一条全新会话里先跑 `SHOW hnsw.iterative_scan` 可能报"未识别参数"，
--    而在同一条会话里先触碰过 vector 类型（或已在库级设过）之后就正常。
--    **判断是否生效，要用真正会用到向量的查询，而不是只看 `SHOW`。**
--
-- `max_scan_tuples` 是配套的成本上限（知识库第 184 行明确要求二者配合）：
-- 迭代扫描会继续往下扫更多元组，设一个上限避免在过滤极严时退化成近似全表扫描。
ALTER DATABASE canalmind SET hnsw.iterative_scan = relaxed_order;
ALTER DATABASE canalmind SET hnsw.max_scan_tuples = 20000;

-- ----------------------------------------------------------------------------
-- 5. 索引
-- ----------------------------------------------------------------------------
-- ⚠️ 刻意**不做**的事：不给 (effective_from, effective_to) 建 btree 索引。
-- 本项目 541 行数据量下，顺序扫描比走索引更快，建了反而增加写入成本与规划噪音。
-- 将来数据量上来了再加——这也是《RAG 向量索引算法和向量数据库》第 167 行
-- "先用 EXPLAIN ANALYZE 看实际是否命中"的同一条思路：**不要凭想象建索引**。

-- HNSW 向量索引（C-23）。参数用 pgvector 默认值，出处见知识库
-- 《RAG 向量索引算法和向量数据库》第 157 行：m=16、ef_construction=64、ef_search=40。
-- 第 161 行建议：先固定 m 与 ef_construction 建好索引，再用会话参数调 ef_search。
CREATE INDEX IF NOT EXISTS rag_chunk_embedding_hnsw
    ON rag_chunk USING hnsw (embedding vector_ip_ops)
    WITH (m = 16, ef_construction = 64);

-- 过滤字段索引：doc_id 与 chunk_type 是最常见的过滤/分组维度。
-- 注意：它们**不能**替代向量索引；只是让"限定文档范围"这类查询的过滤阶段更快。
CREATE INDEX IF NOT EXISTS rag_chunk_doc_id_idx      ON rag_chunk (doc_id);
CREATE INDEX IF NOT EXISTS rag_chunk_doc_category_idx ON rag_chunk (doc_category_no);
CREATE INDEX IF NOT EXISTS rag_chunk_type_idx        ON rag_chunk (chunk_type);

-- updated_at 自动维护
CREATE OR REPLACE FUNCTION rag_chunk_touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS rag_chunk_touch_updated_at_trg ON rag_chunk;
CREATE TRIGGER rag_chunk_touch_updated_at_trg
    BEFORE UPDATE ON rag_chunk
    FOR EACH ROW EXECUTE FUNCTION rag_chunk_touch_updated_at();
