-- ============================================================================
-- BM25 打分函数的数值正确性测试（第 2 周第 4 项）
--
-- 为什么需要这个文件（而不是只写 JUnit）
-- ----------------------------------------------------------------------------
-- BM25 公式的**唯一实现**在 PostgreSQL 里（V4 迁移的 rag_bm25_search，决策 C-25
-- 选定的形态是"词元入库 + SQL 内打分"）。JUnit 跑在 JVM 里、连不到库，
-- 所以"分数算得对不对"**无法**由单测覆盖。若无本文件，这条链路上最核心的数学
-- 就完全没有测试——只有"排名看起来合理"这种主观判断。
--
-- 本文件的做法：**手算 vs 数据库**。
--   1) 造夹具（3~4 个 chunk、人工指定 dl 与 tf）；
--   2) 用 Python 按 BM25 闭式公式算出期望分数，硬编码为常量（推导写在注释里，
--      可当场核对）；
--   3) 调 database 的 rag_bm25_search，逐条比对（容差 1e-9）；
--   4) 顺带测过滤、Top-K、并列名次、空查询词、以及**档案身份守卫是否真的拦得住**。
--
-- 断言失败即 RAISE EXCEPTION → psql 退出码非 0（配合 -v ON_ERROR_STOP=1）。
-- 结尾 ROLLBACK：夹具**不留库**，可在正式库上安全执行。
--
-- 用法：
--   psql -h 127.0.0.1 -p 5432 -U postgres -d canalmind -v ON_ERROR_STOP=1 ^
--        -f backend/scripts/verify_bm25_fixture.sql
-- 期望：全部 RAISE NOTICE 打印 [OK]，末尾 ROLLBACK，退出码 0。
-- ============================================================================

\echo === BM25 夹具测试：手算期望值 vs 数据库实算 ===

BEGIN;

DO $$
DECLARE
    -- ── 夹具 A 的手算期望（推导见下方注释块）────────────────────────────
    EXP_A_T1_C2 CONSTANT DOUBLE PRECISION := 0.646254990213;
    EXP_A_T1_C1 CONSTANT DOUBLE PRECISION := 0.470003629246;
    EXP_A_T1T3_C2 CONSTANT DOUBLE PRECISION := 1.116258619459;
    EXP_IDF_B CONSTANT DOUBLE PRECISION := 0.356674943939;
    EXP_B_D3 CONSTANT DOUBLE PRECISION := 0.549779212950;
    EXP_B_D1 CONSTANT DOUBLE PRECISION := 0.482208583426;
    EXP_B_D2 CONSTANT DOUBLE PRECISION := 0.240432692014;
    TOL CONSTANT DOUBLE PRECISION := 1e-9;

    v_count      INTEGER;
    v_score      DOUBLE PRECISION;
    v_id         TEXT;
    v_msg        TEXT;
    v_first      TEXT;
    v_idf        DOUBLE PRECISION;
BEGIN
    -- ========================================================================
    -- 夹具 A：并列名次 + 多词累加（k1=1.2, b=0.75）
    --   c1: t1×1, t2×2   → dl=3
    --   c2: t1×2, t3×1   → dl=3
    --   c3: t3×1, t2×1, t4×1 → dl=3
    --   N=3, avgdl=3（三篇 dl 全为 3 → b 项不起作用，本夹具只测 tf 与名次）
    --
    -- df(t1)=2 → idf = ln(1 + (3-2+0.5)/(2+0.5)) = ln(1.6) = 0.470003629246
    -- c1,tf=1: 0.470003629246 × (1×2.2)/(1 + 1.2×(1-0.75+0.75×3/3))
    --        = 0.470003629246 × 2.2/2.2                = 0.470003629246
    -- c2,tf=2: 0.470003629246 × (2×2.2)/(2 + 1.2×1.0)  = 0.470003629246 × 1.375
    --                                                  = 0.646254990213  ← 饱和但未饱和到顶
    -- df(t3)=2 → idf 同为 0.470003629246
    -- 查询 {t1,t3}：c2 = 0.646254990213 + 0.470003629246 = 1.116258619459
    --                c1 = 0.470003629246（只命中 t1）
    --                c3 = 0.470003629246（只命中 t3）→ 与 c1 **同分**，
    --                     期望按 chunk_id 升序（ORDER BY 8 DESC, c.chunk_id）
    -- ========================================================================
    PERFORM rag_assert_token_profile(
        'fixture:bm25-a', 'fixture', '1', 'SEARCH', 'fixture',
        repeat('a', 64), 1, repeat('b', 64), 1, 1.2, 0.75, 3, 'verify_bm25_fixture.sql 夹具 A');

    INSERT INTO rag_chunk (chunk_id, chunk_type, doc_id, doc_title, doc_category_no,
                           version, strategy, splitter_version, content, char_count)
    VALUES ('c1', 'BODY', 'docA', '夹具A', '01', 'V1', 'fixture', '1', 'x', 1),
           ('c2', 'BODY', 'docA', '夹具A', '01', 'V1', 'fixture', '1', 'x', 1),
           ('c3', 'TABLE', 'docB', '夹具B', '02', 'V1', 'fixture', '1', 'x', 1);

    INSERT INTO rag_chunk_token_stat (profile_name, chunk_id, term_count) VALUES
        ('fixture:bm25-a', 'c1', 3),
        ('fixture:bm25-a', 'c2', 3),
        ('fixture:bm25-a', 'c3', 3);

    INSERT INTO rag_chunk_term (profile_name, chunk_id, term, term_freq) VALUES
        ('fixture:bm25-a', 'c1', 't1', 1), ('fixture:bm25-a', 'c1', 't2', 2),
        ('fixture:bm25-a', 'c2', 't1', 2), ('fixture:bm25-a', 'c2', 't3', 1),
        ('fixture:bm25-a', 'c3', 't3', 1), ('fixture:bm25-a', 'c3', 't2', 1),
        ('fixture:bm25-a', 'c3', 't4', 1);

    -- ① idf 与闭式解一致
    SELECT d.idf INTO v_idf
      FROM rag_bm25_term_df('fixture:bm25-a', ARRAY['t1']) d WHERE d.term = 't1';
    IF abs(v_idf - EXP_A_T1_C1) > TOL THEN
        RAISE EXCEPTION 'idf(t1) = % ，期望 %（闭式解 ln(1.6)）', v_idf, EXP_A_T1_C1;
    END IF;
    RAISE NOTICE '[OK] idf(t1) = % （闭式 ln(1+(3-2+0.5)/(2+0.5))）', v_idf;

    -- ② 单查询词：tf 饱和后的分数与名次
    SELECT s.score INTO v_score
      FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1'], 10) s WHERE s.chunk_id = 'c2';
    IF abs(v_score - EXP_A_T1_C2) > TOL THEN
        RAISE EXCEPTION 'c2 分数 = %，期望 %', v_score, EXP_A_T1_C2;
    END IF;
    SELECT s.chunk_id INTO v_first FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1'], 10) s LIMIT 1;
    IF v_first <> 'c2' THEN
        RAISE EXCEPTION '查询 t1 的 Top-1 应为 c2（tf=2 应压过 c1 的 tf=1），实际 %', v_first;
    END IF;
    RAISE NOTICE '[OK] tf 饱和：c2(tf=2) = % > c1(tf=1) = %，Top-1 = c2',
        EXP_A_T1_C2, EXP_A_T1_C1;

    -- ③ 多词累加 + 并列名次按 chunk_id 升序
    SELECT s.score INTO v_score
      FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1', 't3'], 10) s WHERE s.chunk_id = 'c2';
    IF abs(v_score - EXP_A_T1T3_C2) > TOL THEN
        RAISE EXCEPTION 'c2 双词分数 = %，期望 %（0.646254990213+0.470003629246）',
            v_score, EXP_A_T1T3_C2;
    END IF;
    SELECT s.chunk_id INTO v_first FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1', 't3'], 10) s LIMIT 1;
    IF v_first <> 'c2' THEN
        RAISE EXCEPTION '双词查询 Top-1 应为 c2（命中 2 个词），实际 %', v_first;
    END IF;
    RAISE NOTICE '[OK] 多词累加：c2 = %（命中数 2）', v_score;

    -- ④ Top-K 限制
    SELECT count(*) INTO v_count FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1', 't3'], 2) s;
    IF v_count <> 2 THEN
        RAISE EXCEPTION 'p_top_k=2 应返回 2 行，实际 %', v_count;
    END IF;
    -- ⑤ 空查询词 / NULL → 0 行且不报错（"查不到"是正常结果）
    SELECT count(*) INTO v_count FROM rag_bm25_search('fixture:bm25-a', ARRAY[]::TEXT[], 10) s;
    IF v_count <> 0 THEN
        RAISE EXCEPTION '空查询词应返回 0 行，实际 %', v_count;
    END IF;
    SELECT count(*) INTO v_count FROM rag_bm25_search('fixture:bm25-a', NULL, 10) s;
    IF v_count <> 0 THEN
        RAISE EXCEPTION 'NULL 查询词应返回 0 行，实际 %', v_count;
    END IF;
    -- ⑥ 元数据过滤：chunk_type 与 doc_id
    SELECT count(*) INTO v_count
      FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1', 't3'], 10, NULL, NULL, 'TABLE') s;
    IF v_count <> 1 THEN
        RAISE EXCEPTION 'chunk_type=TABLE 过滤应只剩 c3，实际 % 行', v_count;
    END IF;
    SELECT count(*) INTO v_count
      FROM rag_bm25_search('fixture:bm25-a', ARRAY['t1', 't3'], 10, 'docB') s;
    IF v_count <> 1 THEN
        RAISE EXCEPTION 'doc_id=docB 过滤应只剩 c3，实际 % 行', v_count;
    END IF;
    RAISE NOTICE '[OK] Top-K 截断 / 空查询词 / 元数据过滤（chunk_type、doc_id）行为符合预期';

    -- ========================================================================
    -- 夹具 B：文档长度归一化（b 项）——夹具 A 的 dl 全相等，测不到 b
    --   d1: termA×1                      → dl=1
    --   d2: termA×1, termN×5             → dl=6（同样的 tf，但文档更长 → 分数更低）
    --   d3: termA×3                      → dl=3（tf 更高但未饱和到顶）
    --   d4: termM×1                      → dl=1（不命中）
    --   N=4, avgdl=(1+6+3+1)/4=2.75
    --
    -- df(termA)=3 → idf = ln(1 + (4-3+0.5)/(3+0.5)) = ln(1.428571428571) = 0.356674943939
    -- d1, tf=1, dl=1: 2.2/(1+1.2×(0.25+0.75×1/2.75))  = 2.2/1.627272727 = 1.351955307 ← ×idf
    -- d2, tf=1, dl=6: 2.2/(1+1.2×(0.25+0.75×6/2.75))  = 2.2/3.263636364 = 0.674094932
    -- d3, tf=3, dl=3: 6.6/(3+1.2×(0.25+0.75×3/2.75))  = 6.6/4.281818182 = 1.541386880
    -- 乘 idf 后：d3=0.549779212950 > d1=0.482208583426 > d2=0.240432692014
    -- ========================================================================
    PERFORM rag_assert_token_profile(
        'fixture:bm25-b', 'fixture', '1', 'SEARCH', 'fixture',
        repeat('a', 64), 1, repeat('b', 64), 1, 1.2, 0.75, 4, 'verify_bm25_fixture.sql 夹具 B');

    INSERT INTO rag_chunk (chunk_id, chunk_type, doc_id, doc_title, strategy, splitter_version,
                           content, char_count, effective_from, effective_to)
    VALUES ('d1', 'BODY', 'docC', '夹具C', 'fixture', '1', 'x', 1, DATE '2027-01-01', NULL),
           ('d2', 'BODY', 'docC', '夹具C', 'fixture', '1', 'x', 1, DATE '2027-01-01', NULL),
           ('d3', 'BODY', 'docC', '夹具C', 'fixture', '1', 'x', 1, DATE '2027-01-01', NULL),
           -- d4 的生效期止 = 2027-01-01，用于验证 as_of 的**左闭右开**
           ('d4', 'BODY', 'docC', '夹具C', 'fixture', '1', 'x', 1, DATE '2026-01-01', DATE '2027-01-01');

    INSERT INTO rag_chunk_token_stat (profile_name, chunk_id, term_count) VALUES
        ('fixture:bm25-b', 'd1', 1),
        ('fixture:bm25-b', 'd2', 6),
        ('fixture:bm25-b', 'd3', 3),
        ('fixture:bm25-b', 'd4', 1);

    INSERT INTO rag_chunk_term (profile_name, chunk_id, term, term_freq) VALUES
        ('fixture:bm25-b', 'd1', 'termA', 1),
        ('fixture:bm25-b', 'd2', 'termA', 1), ('fixture:bm25-b', 'd2', 'termN', 5),
        ('fixture:bm25-b', 'd3', 'termA', 3),
        ('fixture:bm25-b', 'd4', 'termM', 1);

    SELECT d.idf INTO v_idf
      FROM rag_bm25_term_df('fixture:bm25-b', ARRAY['termA']) d WHERE d.term = 'termA';
    IF abs(v_idf - EXP_IDF_B) > TOL THEN
        RAISE EXCEPTION 'idf(termA) = %，期望 %', v_idf, EXP_IDF_B;
    END IF;

    SELECT s.score INTO v_score FROM rag_bm25_search('fixture:bm25-b', ARRAY['termA'], 10) s
     WHERE s.chunk_id = 'd3';
    IF abs(v_score - EXP_B_D3) > TOL THEN
        RAISE EXCEPTION 'd3 分数 = %，期望 %（tf=3, dl=3）', v_score, EXP_B_D3;
    END IF;
    SELECT s.score INTO v_score FROM rag_bm25_search('fixture:bm25-b', ARRAY['termA'], 10) s
     WHERE s.chunk_id = 'd1';
    IF abs(v_score - EXP_B_D1) > TOL THEN
        RAISE EXCEPTION 'd1 分数 = %，期望 %（tf=1, dl=1）', v_score, EXP_B_D1;
    END IF;
    SELECT s.score INTO v_score FROM rag_bm25_search('fixture:bm25-b', ARRAY['termA'], 10) s
     WHERE s.chunk_id = 'd2';
    IF abs(v_score - EXP_B_D2) > TOL THEN
        RAISE EXCEPTION 'd2 分数 = %，期望 %（tf=1 但 dl=6 → 应被长度归一化压低）', v_score, EXP_B_D2;
    END IF;
    IF NOT (EXP_B_D3 > EXP_B_D1 AND EXP_B_D1 > EXP_B_D2) THEN
        RAISE EXCEPTION '期望的顺序 d3 > d1 > d2 不成立，说明归一化方向反了';
    END IF;
    RAISE NOTICE '[OK] 长度归一化：d3(tf=3,dl=3) = % > d1(tf=1,dl=1) = % > d2(tf=1,dl=6) = %',
        EXP_B_D3, EXP_B_D1, EXP_B_D2;

    -- ⑦ as_of 左闭右开：d4 的 effective_to = 2027-01-01，查 2027-01-01 时**不**应命中
    SELECT count(*) INTO v_count
      FROM rag_bm25_search('fixture:bm25-b', ARRAY['termM'], 10, NULL, NULL, NULL, DATE '2027-01-01') s;
    IF v_count <> 0 THEN
        RAISE EXCEPTION 'as_of 右开失效：effective_to=2027-01-01 的行在 as_of=2027-01-01 时被命中（% 行）', v_count;
    END IF;
    SELECT count(*) INTO v_count
      FROM rag_bm25_search('fixture:bm25-b', ARRAY['termM'], 10, NULL, NULL, NULL, DATE '2026-12-31') s;
    IF v_count <> 1 THEN
        RAISE EXCEPTION 'as_of 区间内应命中 d4，实际 % 行', v_count;
    END IF;
    -- 长期有效（effective_to IS NULL）不受 as_of 影响
    SELECT count(*) INTO v_count
      FROM rag_bm25_search('fixture:bm25-b', ARRAY['termA'], 10, NULL, NULL, NULL, DATE '2099-01-01') s;
    IF v_count <> 3 THEN
        RAISE EXCEPTION 'effective_to IS NULL 应视为长期有效，期望 3 行，实际 %', v_count;
    END IF;
    RAISE NOTICE '[OK] as_of 左闭右开 [effective_from, effective_to)：d4 在 2027-01-01 不命中、2026-12-31 命中';

    -- ⑧ 统计函数自洽
    SELECT s.avg_doc_length INTO v_score
      FROM rag_token_stats('fixture:bm25-b') s;
    IF abs(v_score - 2.75) > TOL THEN
        RAISE EXCEPTION 'avgdl = %，期望 2.75', v_score;
    END IF;
    DECLARE
        v_stats RECORD;
    BEGIN
        SELECT * INTO v_stats FROM rag_token_stats('fixture:bm25-b') s;
        IF v_stats.chunk_count <> 4 OR v_stats.distinct_terms <> 3 OR v_stats.total_tokens <> 11 THEN
            RAISE EXCEPTION '统计不符：chunk=% 词表=% 词元总数=%（期望 4/3/11）',
                v_stats.chunk_count, v_stats.distinct_terms, v_stats.total_tokens;
        END IF;
    END;
    RAISE NOTICE '[OK] rag_token_stats：4 chunk / 3 词 / 11 词元 / avgdl 2.75';

    -- ⑨ 档案身份守卫：词典 sha256 变了就必须拒绝就地重灌
    BEGIN
        PERFORM rag_assert_token_profile(
            'fixture:bm25-b', 'fixture', '1', 'SEARCH', 'fixture-v2',
            repeat('c', 64), 2, repeat('b', 64), 1, 1.2, 0.75, 4, '偷换词典');
        RAISE EXCEPTION 'GUARD_NOT_TRIGGERED';
    EXCEPTION WHEN OTHERS THEN
        v_msg := SQLERRM;
        IF v_msg = 'GUARD_NOT_TRIGGERED' THEN
            RAISE EXCEPTION '守卫没拦住"换词典后就地重灌"——这正是它存在的理由，属缺陷';
        END IF;
        IF position('身份已变更' IN v_msg) = 0 THEN
            RAISE EXCEPTION '守卫报了预期之外的错：%', v_msg;
        END IF;
        RAISE NOTICE '[OK] 守卫拦截词典变更（sha256 aaaa→cccc）：%', left(v_msg, 60);
    END;

    BEGIN
        PERFORM rag_assert_token_profile(
            'fixture:bm25-b', 'fixture', '1', 'SEARCH', 'fixture',
            repeat('a', 64), 1, repeat('b', 64), 1, 2.0, 0.75, 4, '改 k1');
        RAISE EXCEPTION 'GUARD_NOT_TRIGGERED';
    EXCEPTION WHEN OTHERS THEN
        v_msg := SQLERRM;
        IF v_msg = 'GUARD_NOT_TRIGGERED' THEN
            RAISE EXCEPTION '守卫没拦住"改 k1 后就地重灌"：参数变更会改变排序，必须新建档案';
        END IF;
        RAISE NOTICE '[OK] 守卫拦截 BM25 参数变更（k1 1.2→2.0）';
    END;

    -- ⑩ 身份一致时必须放行（否则守卫会把正常重灌也挡掉）
    PERFORM rag_assert_token_profile(
        'fixture:bm25-b', 'fixture', '1', 'SEARCH', 'fixture',
        repeat('a', 64), 1, repeat('b', 64), 1, 1.2, 0.75, 4, '正常重灌');
    RAISE NOTICE '[OK] 身份一致时允许重灌';

    RAISE NOTICE '';
    RAISE NOTICE '==== 夹具测试全部通过（10 组断言）====';
END $$;

ROLLBACK;
