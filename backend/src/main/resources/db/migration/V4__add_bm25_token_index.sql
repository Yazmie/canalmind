-- ============================================================================
-- V4：检索通道之二 —— BM25 通道 + 中文分词词元（第 2 周第 4 项）
--
-- 来源：规格书《平陆运河航运物流智能业务助手-项目规格与执行清单.md》**第 629 行**
--       ——"BM25 通道 + 中文分词（**自定义航运词典**）"；
--       决策 **C-25**（docs/DECISIONS.md：落地形态 = 词元入库 + SQL 内 BM25 打分；
--       分词器 = jieba-analysis + 项目自带航运词典）。
--
-- 解决什么问题
-- ----------------------------------------------------------------------------
-- V3 只建了**向量**通道。向量通道的短板在知识库里有明确记载：
-- 《RAG 优化：从召回、重排到上下文工程.md》**第 115 行**：
--   "错误码、型号和版本号这类精确词容易被近义但错误的内容挤掉，需要额外的关键词召回信号"；
-- **第 119 行**："向量检索按语义接近程度找候选，BM25 按出现的词项匹配候选"。
-- 本项目语料**刻意埋了**专有名词（"闸次""过闸""通过能力"）与精确数值（费率、
-- 时限、吨位、闸室尺度），见 docs/规则语料/README.md §5——正是 BM25 该赢的场景。
--
-- 为什么分词在 Java 侧做、只有词元进库（这是本文件的形态选择，务必先读）
-- ----------------------------------------------------------------------------
-- 本项目**没有**可用的 PG 中文分词扩展，这不是主观取舍，是实测事实（2026-09-21）：
--   * `D:\APP\PostgreSQL\18\share\extension` 下 65 个 .control 里没有
--     zhparser / pg_jieba / IK —— 一个中文解析器都没有；
--   * zhparser v2.3（2025-01）的 CI **只有 Linux 与 FreeBSD**，且要先源码编译 SCWS；
--     pg_jieba 最后提交 2023-10，官方只在 CentOS7/PG9.6 与 macOS/PG11 上测过；
--   * 本机 PG18 是 EDB 的 **MSVC** 构建，而本机**没有 make/gcc/cmake/nmake**（实测全 MISSING），
--     要走上游源码编译得先装 MSYS2/MinGW，且 pg_jieba 是 C++，与 MSVC 构建的服务端混用
--     还有 ABI 风险；安装扩展还需管理员权限。
-- 更关键的是：**即使装上中文解析器也拿不到 BM25**——PostgreSQL 原生
-- `ts_rank_cd` 是 cover-density 排序，**不是 BM25**；真正的 PG BM25 只有
-- ParadeDB 的 `pg_search`（Rust/pgrx + Docker 路线，未提供 Windows 支持）。
--
-- 故本通道的形态是：**Java（jieba）负责分词，PostgreSQL 负责存储与打分**。
-- 这样"自定义航运词典"可以是一个受版本管理的文本资源、可用 JUnit 逐条断言，
-- 而 BM25 的分数由**一条可 EXPLAIN、可用 psql 复核的 SQL 函数**算出。
--
-- BM25 公式（写死在这里，是唯一实现；Java 侧不重复实现一遍）
-- ----------------------------------------------------------------------------
--   score(q,d) = Σ_t  idf(t) · (tf(t,d) · (k1+1)) / (tf(t,d) + k1 · (1 - b + b · dl(d)/avgdl))
--   idf(t)     = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
--
-- * 用**加 1 的对数**形式（Lucene BM25Similarity 的写法）而不是经典
--   ln((N-df+0.5)/(df+0.5))：后者在 df > N/2 时会给出**负分**，
--   让"命中更多词"反而扣分。本项目 541 个 chunk、语料高度同质
--   （"船舶""规定"这类词 df 很高），必须用不会翻负的形式。
-- * k1=1.2、b=0.75 是 Lucene 默认值，也是知识库没覆盖时的通行起点；
--   它们**存在档案表里**（rag_token_profile.bm25_k1/bm25_b），不写死在函数里——
--   这样"两次评测分数不同"能归因到具体是哪一版参数（知识库《RAG 优化》第 266–274 行
--   要求版本化"索引参数"）。
--
-- ⚠️ 两个如实记录的边界（不要对外说成"生产级全文检索引擎"）
-- ----------------------------------------------------------------------------
-- ① **统计口径是全档案的，不随过滤条件变化**：N 与 avgdl 取自整个档案，
--    而 WHERE 里的 doc_id / 生效期过滤只影响候选集。这与 Lucene / ES 的做法一致
--    （全局统计），代价是"按文档过滤后排序不再是该子集内的最优序"。
--    知识库《RAG 优化》**第 63 行**建议"能由 Metadata 表达的范围应先收紧"——
--    本项目把过滤放在**打分同一条 SQL** 里（不是先取 Top-K 再筛），
--    所以不会出现 V3 记录过的"过滤后静默少返回"那种退化。
-- ② **规模**：本项目 541 个 chunk，倒排表约 7 万行，任何计划都是毫秒级。
--    这个"在 SQL 里算 BM25"的形态**不是**为百万级语料准备的——
--    到那个量级应换成专用检索引擎（或 PG 的 BM25 扩展），
--    并用《评测报告》里的召回指标来证明换得值。已列为第 4 周规模复核项。
--
-- ⚠️ 实测踩到的坑（已写进 docs/工程复盘/检索工程/）
-- ----------------------------------------------------------------------------
-- * **jieba-analysis 的用户词典必须恰好两列**（`词 词频`）。写成标准 jieba 的
--   `词 词频 tag` 三列时，`WordDictionary.loadUserDict` 只在 `tokens.length == 2`
--   才取词频，于是词频**静默退回默认值 3.0**（log(3/60101878) = -16.81，
--   比基础词典任何词都低）→ 自定义词几乎不会被切出，**不报错**。
--   词典文件由 backend/scripts/build_shipping_dictionary.py 生成并 `--check` 校验。
-- * **词典内容一改，指纹就变**：见下面 rag_assert_token_profile 的守卫。
-- * **语料正文里真的有 `[TABLE:<chunk_id> rows=N cols=M]` 占位标记**（87 处），
--   分词前必须剥掉，否则 16 位十六进制 chunk_id 会变成噪声词元。
--   ⚠️ 注意标记是**方括号**、且携带 chunk_id 与行列数，
--   **不是** 进度看板 §3.2 / C-24 里写的 `[[TABLE:n]]`（该处文档笔误，已在 §3.4 更正）。
--
-- 幂等性
-- ----------------------------------------------------------------------------
-- 本文件可重复执行（CREATE ... IF NOT EXISTS / CREATE OR REPLACE）。
-- 但词元数据须重新生成：Bm25IngestCli 可重复运行（先按档案删后插）。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 分词档案：把「这批词元是谁、用哪版词典、按哪种切分模式产的」变成可查询的事实
-- ----------------------------------------------------------------------------
-- 与 V3 的 rag_index_profile 同构（同样的理由：档案名进 chunk 行，查询时校验）。
-- 区别是这里的"身份"由四件事共同决定：分词器版本、切分模式、**词典内容**、停用词内容。
-- 词典与停用词用 **sha256** 而不是版本号字符串来标识——版本号靠人记得改，
-- sha256 由文件字节算出，改了就是改了。这正是本项目一贯的取向：
-- 把"靠自觉"换成"机制保证"（对照 C-22 配套硬约束）。
CREATE TABLE IF NOT EXISTS rag_token_profile (
    profile_name          TEXT        PRIMARY KEY,
    tokenizer_name        TEXT        NOT NULL,
    tokenizer_version     TEXT        NOT NULL,
    -- INDEX / SEARCH 对应 jieba-analysis 的 SegMode：
    -- INDEX 会把"本身也在词典里的 2~3 字子词"一并输出（召回更宽），
    -- SEARCH 只输出整词。选哪个是**评测变量**，故写进档案而不是写死在代码里。
    segment_mode          TEXT        NOT NULL,
    dictionary_version    TEXT        NOT NULL,
    dictionary_sha256     TEXT        NOT NULL,
    dictionary_terms      INTEGER     NOT NULL,
    stopwords_sha256      TEXT        NOT NULL,
    stopwords_count       INTEGER     NOT NULL,
    -- BM25 参数：存在档案里，函数从档案读（见文件头「公式」段）
    bm25_k1               DOUBLE PRECISION NOT NULL,
    bm25_b                DOUBLE PRECISION NOT NULL,
    -- ⚠️ 只是"最近一次入库时的 chunk 数"这一**审计快照**，
    --    **不参与打分**：N 与 avgdl 一律在查询时从 rag_chunk_token_stat 现算。
    --    理由是本项目已经吃过一次统计信息过期的亏（reltuples=-1，
    --    见 RagChunkRepository.analyzeChunkTable 的注释）——
    --    "表里有数据"≠"规划器/公式知道表里有数据"，所以不把可推导的统计量落库。
    chunk_count_at_ingest INTEGER,
    note                  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rag_token_profile_mode_ck  CHECK (segment_mode IN ('INDEX', 'SEARCH')),
    CONSTRAINT rag_token_profile_k1_ck    CHECK (bm25_k1 > 0 AND bm25_k1 <= 100),
    CONSTRAINT rag_token_profile_b_ck     CHECK (bm25_b >= 0 AND bm25_b <= 1),
    CONSTRAINT rag_token_profile_sha_ck   CHECK (dictionary_sha256 ~ '^[0-9a-f]{64}$'
                                             AND stopwords_sha256  ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rag_token_profile_terms_ck CHECK (dictionary_terms > 0 AND stopwords_count >= 0),
    CONSTRAINT rag_token_profile_chunks_ck CHECK (chunk_count_at_ingest IS NULL
                                             OR chunk_count_at_ingest >= 0)
);

COMMENT ON TABLE rag_token_profile IS
    'BM25 分词档案：这批词元由哪个分词器/哪版词典/哪种切分模式产出。'
    '依据 C-25；形态与 V3 的 rag_index_profile 同构。词典身份用 sha256 而非版本号——'
    '版本号靠人记得改，sha256 由文件字节算出。';
COMMENT ON COLUMN rag_token_profile.segment_mode IS
    'jieba SegMode：INDEX=额外输出也在词典内的 2~3 字子词（召回更宽）；SEARCH=只输出整词。'
    '这是第 4 周消融的候选变量，故记录在档案里。';
COMMENT ON COLUMN rag_token_profile.bm25_k1 IS
    'BM25 词频饱和参数 k1（Lucene 默认 1.2）。打分时由 rag_bm25_search 从本表读取，不写死在函数里。';
COMMENT ON COLUMN rag_token_profile.bm25_b IS
    'BM25 文档长度归一化参数 b（Lucene 默认 0.75），取值 [0,1]。';
COMMENT ON COLUMN rag_token_profile.chunk_count_at_ingest IS
    '⚠️ 仅审计快照，不参与打分。N 与 avgdl 一律在查询时现算，避免统计量过期后静默算错。'
    '取值 = 本次实际进入倒排表的 chunk 数，可能与 count(*) FROM rag_chunk 不等：'
    '实测有 7 个 BODY chunk 的正文只剩表格占位标记、切不出词元，不进 BM25 语料。';

-- ----------------------------------------------------------------------------
-- 2. 每个 chunk 的长度统计（BM25 的 dl 与 avgdl）
-- ----------------------------------------------------------------------------
-- 为什么单独一张表而不是往 rag_chunk 加一列：
--   * rag_chunk 是**切分产物**，词元长度是**分词产物**，两者生命周期不同
--     （换分词词典要重灌词元，但 chunk 本身不变）；
--   * 一个 chunk 可能同时属于多个分词档案（消融对比），加列就装不下。
CREATE TABLE IF NOT EXISTS rag_chunk_token_stat (
    profile_name TEXT    NOT NULL REFERENCES rag_token_profile (profile_name) ON DELETE CASCADE,
    chunk_id     TEXT    NOT NULL REFERENCES rag_chunk (chunk_id) ON DELETE CASCADE,
    term_count   INTEGER NOT NULL,
    PRIMARY KEY (profile_name, chunk_id),
    -- term_count 是 BM25 里的 dl（文档长度，按词元数）。实测体量见 §3.4。
    CONSTRAINT rag_chunk_token_stat_count_ck CHECK (term_count >= 0)
);

COMMENT ON TABLE rag_chunk_token_stat IS
    'BM25 的文档长度 dl：每个 chunk 在某个分词档案下的词元总数（含重复）。
     ⚠️ 不变式「term_count = SUM(rag_chunk_term.term_freq)」无法用 CHECK 表达（跨行），
     由 backend/scripts/verify_bm25.py 逐行断言。';

-- ----------------------------------------------------------------------------
-- 3. 倒排表：term → 哪些 chunk 出现、出现几次
-- ----------------------------------------------------------------------------
-- 这张表**就是倒排索引**：主键 (profile_name, chunk_id, term) 保证一个 chunk 里
-- 同一个词只有一行、且 term_freq 就是词频；再看下面的 rag_chunk_term_posting_idx。
CREATE TABLE IF NOT EXISTS rag_chunk_term (
    profile_name TEXT    NOT NULL,
    chunk_id     TEXT    NOT NULL,
    term         TEXT    NOT NULL,
    term_freq    INTEGER NOT NULL,
    PRIMARY KEY (profile_name, chunk_id, term),
    -- 两级外键：先绑到统计行，再由统计行绑到 rag_chunk。
    -- 这样"有词元却没有长度统计"（会让 dl 缺失、avgdl 失真）在库里就不可能存在。
    FOREIGN KEY (profile_name, chunk_id)
        REFERENCES rag_chunk_token_stat (profile_name, chunk_id) ON DELETE CASCADE,
    CONSTRAINT rag_chunk_term_freq_ck    CHECK (term_freq >= 1),
    -- 词元两端不得有空白：空白会让 '船舶' 与 '船舶 ' 变成两个词，
    -- 表现为"某些查询莫名其妙召回变少"，且不报错。
    CONSTRAINT rag_chunk_term_nonblank_ck CHECK (term <> '' AND term = btrim(term))
);

COMMENT ON TABLE rag_chunk_term IS
    'BM25 倒排表（term → chunk 及词频）。一行 = 某 chunk 中某词元出现 term_freq 次。'
    '词元由 Java 侧 JiebaShippingTokenizer 产出（C-25），本表只存结果。';
COMMENT ON COLUMN rag_chunk_term.term_freq IS
    '词频 tf（本 chunk 内该词元出现次数，已按"去停用词 + 剥表格占位标记"统计）。';

-- 倒排表的核心索引：按词查 chunk。主键以 profile_name 开头、chunk_id 在 term 之前，
-- 无法服务"给定 term 找 chunk"，故必须单独建这一条。
CREATE INDEX IF NOT EXISTS rag_chunk_term_posting_idx
    ON rag_chunk_term (profile_name, term);

-- 按档案统计/清理用
CREATE INDEX IF NOT EXISTS rag_chunk_token_stat_profile_idx
    ON rag_chunk_token_stat (profile_name);

-- ----------------------------------------------------------------------------
-- 4. 档案身份守卫：词典/分词器/参数一变，就地重灌就被拒绝
-- ----------------------------------------------------------------------------
-- 先按名字清掉本文件要建的这几个函数（含所有重载）。
-- 为什么需要：`CREATE OR REPLACE FUNCTION` **不能改返回类型**，而本文件仍在演进
-- （例如给 rag_bm25_search 的 RETURNS TABLE 增列）。没有这一步，改一次返回列
-- 就会报 "cannot change return type of existing function"，逼人手工 DROP。
-- 本项目的迁移是**手工 psql -f 应用且要求可重复执行**（见 docs/进度看板.md §5），
-- 所以这层清理是必要的，不是洁癖。
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT oid::regprocedure AS sig
               FROM pg_proc
              WHERE proname IN ('rag_assert_token_profile', 'rag_bm25_search',
                                'rag_bm25_term_df', 'rag_token_stats')
    LOOP
        EXECUTE 'DROP FUNCTION ' || r.sig;
    END LOOP;
END $$;

-- 为什么用函数而不是触发器：这里要比对的值（词典 sha256）**只有 Java 侧算得出来**
-- ——词典是打包在 jar 里的资源文件。所以守不住"别的写入方"，只能守住本项目的写入路径：
-- 让"忘了新建档案"这件事在**灌数据之前**失败，而不是灌完才发现两批词元混在一起。
--
-- 为什么 k1/b 变化也要新建档案：它们改变**排序**结果。若允许就地改，
-- 上周跑出的评测分数就会在本周被无声重解释，而知识库《RAG 优化》第 266–274 行
-- 要求"一次评测结果必须能对应到索引参数版本"。
CREATE OR REPLACE FUNCTION rag_assert_token_profile(
    p_profile_name       TEXT,
    p_tokenizer_name     TEXT,
    p_tokenizer_version  TEXT,
    p_segment_mode       TEXT,
    p_dictionary_version TEXT,
    p_dictionary_sha256  TEXT,
    p_dictionary_terms   INTEGER,
    p_stopwords_sha256   TEXT,
    p_stopwords_count    INTEGER,
    p_bm25_k1            DOUBLE PRECISION,
    p_bm25_b             DOUBLE PRECISION,
    p_chunk_count        INTEGER,
    p_note               TEXT
) RETURNS TEXT AS $$
DECLARE
    existing rag_token_profile%ROWTYPE;
BEGIN
    SELECT * INTO existing FROM rag_token_profile WHERE profile_name = p_profile_name;

    IF NOT FOUND THEN
        INSERT INTO rag_token_profile (
            profile_name, tokenizer_name, tokenizer_version, segment_mode,
            dictionary_version, dictionary_sha256, dictionary_terms,
            stopwords_sha256, stopwords_count,
            bm25_k1, bm25_b, chunk_count_at_ingest, note
        ) VALUES (
            p_profile_name, p_tokenizer_name, p_tokenizer_version, p_segment_mode,
            p_dictionary_version, p_dictionary_sha256, p_dictionary_terms,
            p_stopwords_sha256, p_stopwords_count,
            p_bm25_k1, p_bm25_b, p_chunk_count, p_note
        );
        RETURN 'created';
    END IF;

    IF existing.tokenizer_name      IS DISTINCT FROM p_tokenizer_name
       OR existing.tokenizer_version IS DISTINCT FROM p_tokenizer_version
       OR existing.segment_mode      IS DISTINCT FROM p_segment_mode
       OR existing.dictionary_version IS DISTINCT FROM p_dictionary_version
       OR existing.dictionary_sha256 IS DISTINCT FROM p_dictionary_sha256
       OR existing.dictionary_terms  IS DISTINCT FROM p_dictionary_terms
       OR existing.stopwords_sha256  IS DISTINCT FROM p_stopwords_sha256
       OR existing.stopwords_count   IS DISTINCT FROM p_stopwords_count
       OR existing.bm25_k1           IS DISTINCT FROM p_bm25_k1
       OR existing.bm25_b            IS DISTINCT FROM p_bm25_b THEN
        RAISE EXCEPTION
            '分词档案 % 的身份已变更，拒绝就地重灌：%'
            '。词元是按旧身份产的，就地重灌会让同一档案里混入两种口径，'
            '而**查询侧无法分辨**（分数仍会算出来，只是不再可比）。'
            '请新建一个档案名（例如换 dictionary_version）后全量重灌；'
            '旧档案若确实要废弃，用 DROP 删掉（会级联删掉它的词元）。',
            p_profile_name,
            format('词典 %s(%s…)→%s(%s…)、分词器 %s/%s→%s/%s、模式 %s→%s、k1 %s→%s、b %s→%s',
                   existing.dictionary_version, left(existing.dictionary_sha256, 8),
                   p_dictionary_version, left(p_dictionary_sha256, 8),
                   existing.tokenizer_name, existing.tokenizer_version,
                   p_tokenizer_name, p_tokenizer_version,
                   existing.segment_mode, p_segment_mode,
                   existing.bm25_k1, p_bm25_k1,
                   existing.bm25_b, p_bm25_b);
    END IF;

    -- 身份一致：只允许更新"审计字段"，身份字段一律不动
    UPDATE rag_token_profile
       SET chunk_count_at_ingest = p_chunk_count,
           note = p_note
     WHERE profile_name = p_profile_name;
    RETURN 'unchanged';
END;
$$ LANGUAGE plpgsql;

-- ----------------------------------------------------------------------------
-- 5. BM25 打分（唯一实现；Java 侧只负责分词与传参，不重复实现公式）
-- ----------------------------------------------------------------------------
-- 参数化过滤与打分在**同一条 SQL** 里完成（理由见文件头「边界①」）：
-- 先把候选集按元数据收窄，再在收窄后的集合上算分。这与"先向量 Top-K 再过滤"
-- （HNSW 的静默少返回，见 V3 与本文件头）是完全不同的语义，别混。
--
-- p_as_of 的区间口径：**左闭右开** [effective_from, effective_to)
--   依据 V1__init_business_schema.sql **第 256 行** daterange(...,'[)') 与第 241 行注释
--   （"查询须按业务日期落在 [from, to) 内过滤"）。故判断写法是
--   `effective_from <= as_of` 且 `effective_to > as_of`（**不是 >=**）。
--   语料实测：541 行中 517 行 effective_to 为 NULL（长期有效）、24 行止于 2026-12-31。
CREATE OR REPLACE FUNCTION rag_bm25_search(
    p_profile_name    TEXT,
    p_terms           TEXT[],
    p_top_k           INTEGER DEFAULT 30,
    p_doc_id          TEXT    DEFAULT NULL,
    p_doc_category_no TEXT    DEFAULT NULL,
    p_chunk_type      TEXT    DEFAULT NULL,
    p_as_of           DATE    DEFAULT NULL
)
RETURNS TABLE (
    chunk_id      TEXT,
    doc_id        TEXT,
    doc_title     TEXT,
    chunk_type    TEXT,
    article_no    TEXT,
    article_title TEXT,
    version       TEXT,
    score         DOUBLE PRECISION,
    matched_terms INTEGER
) AS $$
DECLARE
    v_k1    DOUBLE PRECISION;
    v_b     DOUBLE PRECISION;
    v_docs  DOUBLE PRECISION;
    v_avgdl DOUBLE PRECISION;
BEGIN
    SELECT p.bm25_k1, p.bm25_b INTO v_k1, v_b
      FROM rag_token_profile p WHERE p.profile_name = p_profile_name;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'BM25 档案 % 不存在：请先运行 Bm25IngestCli 建档案并灌词元', p_profile_name;
    END IF;

    IF p_top_k IS NULL OR p_top_k < 1 THEN
        RAISE EXCEPTION 'p_top_k 必须 >= 1，实际 %', p_top_k;
    END IF;

    -- 空查询词：返回 0 行而不是报错。"查不到"是正常结果（知识库第 296–298 行
    -- 讲拒答时也要求"置信度低要能识别"，报错反而让人以为链路坏了）。
    IF p_terms IS NULL OR array_length(p_terms, 1) IS NULL THEN
        RETURN;
    END IF;

    SELECT count(*)::DOUBLE PRECISION, avg(s.term_count)::DOUBLE PRECISION
      INTO v_docs, v_avgdl
      FROM rag_chunk_token_stat s WHERE s.profile_name = p_profile_name;

    IF v_docs IS NULL OR v_docs = 0 OR v_avgdl IS NULL OR v_avgdl = 0 THEN
        RETURN;   -- 档案还没有词元
    END IF;

    RETURN QUERY
    WITH q(term) AS (
        SELECT DISTINCT trim(u.tok)
          FROM unnest(p_terms) AS u(tok)
         WHERE trim(u.tok) <> ''
    ),
    df(term, dfreq) AS (
        SELECT t.term, count(*)::DOUBLE PRECISION
          FROM rag_chunk_term t
          JOIN q ON q.term = t.term
         WHERE t.profile_name = p_profile_name
         GROUP BY t.term
    ),
    idf AS (
        SELECT d.term,
               ln(1.0 + (v_docs - d.dfreq + 0.5) / (d.dfreq + 0.5)) AS idf_value
          FROM df d
    )
    SELECT c.chunk_id, c.doc_id, c.doc_title, c.chunk_type, c.article_no,
           c.article_title, c.version,
           sum(idf.idf_value * (t.term_freq * (v_k1 + 1.0))
               / (t.term_freq + v_k1 * (1.0 - v_b + v_b * s.term_count / v_avgdl)))::DOUBLE PRECISION,
           count(*)::INTEGER
      FROM rag_chunk_term t
      JOIN idf ON idf.term = t.term
      JOIN rag_chunk_token_stat s
        ON s.profile_name = t.profile_name AND s.chunk_id = t.chunk_id
      JOIN rag_chunk c ON c.chunk_id = t.chunk_id
     WHERE t.profile_name = p_profile_name
       AND (p_doc_id          IS NULL OR c.doc_id = p_doc_id)
       AND (p_doc_category_no IS NULL OR c.doc_category_no = p_doc_category_no)
       AND (p_chunk_type      IS NULL OR c.chunk_type = p_chunk_type)
       AND (p_as_of           IS NULL
            OR ((c.effective_from IS NULL OR c.effective_from <= p_as_of)
                AND (c.effective_to IS NULL OR c.effective_to > p_as_of)))
     GROUP BY c.chunk_id, c.doc_id, c.doc_title, c.chunk_type, c.article_no,
              c.article_title, c.version
     -- 用序号排序而非列名：RETURNS TABLE 的 OUT 参数名（score）与表达式同名时
     -- 容易写出"看着对、其实排错"的语句，序号是明确的。
     ORDER BY 8 DESC, c.chunk_id
     LIMIT p_top_k;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION rag_bm25_search(TEXT, TEXT[], INTEGER, TEXT, TEXT, TEXT, DATE) IS
    'BM25 检索（唯一实现）。idf 用 ln(1+(N-df+0.5)/(df+0.5))（Lucene 形式，不会翻负）；'
    'k1/b 从 rag_token_profile 读取；过滤与打分在同一条 SQL 内完成。'
    'p_as_of 按左闭右开 [effective_from, effective_to) 过滤（V1 第 256 行口径）。';

-- ----------------------------------------------------------------------------
-- 6. 证据函数：让"为什么是这个分数"可复核
-- ----------------------------------------------------------------------------
-- df/idf 是 BM25 里最容易被怀疑、也最难手算的两项。单列一个函数，
-- 使 psql 里可以逐词核对（verify_bm25.py 用它做独立交叉验证的对照）。
CREATE OR REPLACE FUNCTION rag_bm25_term_df(
    p_profile_name TEXT,
    p_terms        TEXT[]
)
RETURNS TABLE (term TEXT, df INTEGER, idf DOUBLE PRECISION) AS $$
    SELECT d.term, d.dfreq::INTEGER,
           ln(1.0 + (v.n - d.dfreq + 0.5) / (d.dfreq + 0.5))
      FROM (
        SELECT t.term, count(*)::DOUBLE PRECISION AS dfreq
          FROM rag_chunk_term t
         WHERE t.profile_name = p_profile_name
           AND t.term IN (SELECT trim(u.tok) FROM unnest(p_terms) AS u(tok))
         GROUP BY t.term
      ) d
      CROSS JOIN (
        SELECT count(*)::DOUBLE PRECISION AS n
          FROM rag_chunk_token_stat s WHERE s.profile_name = p_profile_name
      ) v
     ORDER BY d.dfreq DESC, d.term;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION rag_bm25_term_df(TEXT, TEXT[]) IS
    '按查询词给出 df 与 idf，供人工核对 BM25 分数的构成（verify_bm25.py 的对照来源）。';

-- 档案规模统计：入库汇总、《评测报告》检索段与本函数共用同一口径，
-- 避免"报告里的数字"与"库里的数字"各算各的。
CREATE OR REPLACE FUNCTION rag_token_stats(p_profile_name TEXT)
RETURNS TABLE (
    chunk_count     BIGINT,
    distinct_terms  BIGINT,
    total_tokens    BIGINT,
    avg_doc_length  DOUBLE PRECISION,
    min_doc_length  INTEGER,
    max_doc_length  INTEGER
) AS $$
    SELECT (SELECT count(*) FROM rag_chunk_token_stat s WHERE s.profile_name = p_profile_name),
           (SELECT count(DISTINCT t.term) FROM rag_chunk_term t WHERE t.profile_name = p_profile_name),
           (SELECT coalesce(sum(t.term_freq), 0) FROM rag_chunk_term t WHERE t.profile_name = p_profile_name),
           (SELECT avg(s.term_count)::DOUBLE PRECISION FROM rag_chunk_token_stat s WHERE s.profile_name = p_profile_name),
           (SELECT min(s.term_count) FROM rag_chunk_token_stat s WHERE s.profile_name = p_profile_name),
           (SELECT max(s.term_count) FROM rag_chunk_token_stat s WHERE s.profile_name = p_profile_name);
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION rag_token_stats(TEXT) IS
    '档案的词元规模统计（chunk 数 / 词表大小 / 词元总数 / dl 的均值与极值）。'
    '入库 CLI、《评测报告》与复核脚本共用它，保证三处口径一致。';

-- ----------------------------------------------------------------------------
-- 7. 索引
-- ----------------------------------------------------------------------------
-- ⚠️ 刻意**不做**的事（与 V3 §5 同一取舍）：
--   * 不给 (doc_id, effective_from, effective_to) 建额外 btree——541 行下顺序扫描更快；
--   * 不给 term 建 pg_trgm / GIN——本项目要的是**精确词元**匹配（BM25 的立身之本），
--     模糊匹配会把"精确数值"这类区分度信号糊掉。
-- 复核方式与 V3 一致：先用 EXPLAIN (ANALYZE) 看实际计划，再决定是否加索引，
-- 不凭想象建（知识库《RAG 向量索引算法和向量数据库》第 167 行同一思路）。
