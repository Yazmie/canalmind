-- ============================================================================
-- eval_record 表结构自检（第 4 周，V6 的配套）
-- ============================================================================
-- 为什么要有这个文件
-- ----------------------------------------------------------------------------
-- `AGENTS.md` §8.2 与 `docs/工程复盘/工程方法/检查有效性必须被验证.md` 的同一条纪律：
-- **一条约束"写上了"不等于它"会拦"**。本项目已经因此出过事——
-- `docs/规则语料/README.md` §4 早就写"费率取值必须与 tariff_rule 逐字一致"，
-- 但 `validate_corpus.py` 的 11 项检查**没有一项校验数值**，于是两侧在四个维度上
-- 同时不同了很久（见 C-31）。
--
-- 所以本脚本对 `V6__add_eval_record.sql` 做**逐条注入式自证**：
--   ① 结构自检：字段是否与知识库《AI 应用评测体系》第 550 行的 EvalRecord 对齐
--                （逐个字段名对照，缺一个就报错 —— 防止"少迁了一列"静默发生）；
--   ② 有效性自证：**故意写入 12 类违规行**，逐条断言它们**必须被拦**，
--                并断言抛出的 SQLSTATE 是预期的那个（不是"抛了别的错也算过"）；
--   ③ 保留集纪律自证：`eval_record_dev` 视图**必须看不到 holdout 行**；
--   ④ 幂等自证：本脚本可重复运行，且**不留任何数据**（末尾 ROLLBACK）。
--
-- 用法：
--   psql -h 127.0.0.1 -p 5432 -U postgres -d canalmind -v ON_ERROR_STOP=1 \
--        -f backend/scripts/verify_eval_record.sql
--   → 期望末行 [PASS]；退出码 0。任一用例未被拦住则 RAISE EXCEPTION、退出码 3。
--
-- ⚠️ 本脚本**只读 + 临时写**：所有 INSERT 都在事务内，末尾 ROLLBACK，
--    ⛔ 不会在 `eval_record` 里留下任何行（脚本自身会断言这一点）。
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ----------------------------------------------------------------------------
-- ⚠️⚠️ 判据为什么返回 `int` 而**不是**一段 `'OK: …' / 'FAIL: …'` 字符串
-- ----------------------------------------------------------------------------
-- 因为**初版本就是返回字符串的，结果它把一次全绿的复核报成了失败**（本文件实测）：
-- 调用侧用 `line LIKE '%FAIL%'` 判结果，而其中一条用例的**标签**恰好是
-- 「FAILED 却不给任何归因」—— 于是那句**成功提示**
-- `'  OK   FAILED 却不给任何归因 → 被拦（SQLSTATE 23514）'` 里含子串 `FAIL`
-- ⇒ 被计成一次失败。症状正是「**18 行提示全是 OK，结论却说失败 1 个**」，
-- 与 §0.3 坑 #3（校验脚本把一次全绿的复核表现成失败）同源，也与
-- `docs/工程复盘/工程方法/验证工具本身也要被验证/` 同一类：
-- **判据的写法本身会制造假结论**。
-- 改法：**判据不解析自己打印的文本**，直接返回状态码（0 = 拦截成功，1 = 失败）。
-- ----------------------------------------------------------------------------
CREATE FUNCTION pg_temp.expect_reject(label text, stmt text, expected_state text)
RETURNS int
LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN others THEN
        IF SQLSTATE = expected_state THEN
            RAISE NOTICE '  OK   %  → 被拦（SQLSTATE %）', label, SQLSTATE;
            RETURN 0;
        END IF;
        RAISE NOTICE '  FAIL %  → 抛出的不是预期错误：期望 %，实际 %（%）',
                     label, expected_state, SQLSTATE, SQLERRM;
        RETURN 1;
    END;
    RAISE NOTICE '  FAIL %  → **竟然通过了**：该约束没有生效', label;
    RETURN 1;
END $$;

DO $$
DECLARE
    failures int := 0;
    checked  int := 0;
    missing  text;
    n        int;
BEGIN
    RAISE NOTICE '=== eval_record 结构自检（V6 配套）===';

    -- ------------------------------------------------------------------
    -- ① 结构自检：字段必须与知识库第 550 行的 EvalRecord 对齐
    -- ------------------------------------------------------------------
    -- 左：本项目列名；右：知识库 record 里的字段名（仅作对照注释，不参与判断）
    --   eval_id ↔ evalId | task_id ↔ taskId | trial_id ↔ trialId
    --   prompt_version ↔ promptVersion | model_id ↔ modelId
    --   dataset_version ↔ datasetVersion | task_as_of ↔ taskAsOf
    --   timezone ↔ timezone | environment_version ↔ environmentVersion
    --   evidence_snapshot_uri ↔ evidenceSnapshotUri | input_hash ↔ inputHash
    --   raw_input ↔ rawInput | reference_output ↔ referenceOutput
    --   actual_output ↔ actualOutput | transcript_uri ↔ transcriptUri
    --   outcome_status ↔ outcomeStatus | scores ↔ scores
    --   judge_model ↔ judgeModel | grader_version ↔ graderVersion
    --   judge_reasoning ↔ judgeReasoning | error_category ↔ errorCategory
    --   root_cause_layer ↔ rootCauseLayer | confidence ↔ confidence
    --   evaluated_at ↔ evaluatedAt | git_commit ↔ gitCommit
    -- 加上本项目扩展：run_id / layer / split / judge_kind / session_id /
    --   turn_index / route_reason（授权来源见 V6 文件头）
    SELECT string_agg(c, ', ') INTO missing
      FROM unnest(ARRAY[
            'eval_id','task_id','trial_id','prompt_version','model_id',
            'dataset_version','task_as_of','timezone','environment_version',
            'evidence_snapshot_uri','input_hash','raw_input','reference_output',
            'actual_output','transcript_uri','outcome_status','scores',
            'judge_model','grader_version','judge_reasoning','error_category',
            'root_cause_layer','confidence','evaluated_at','git_commit',
            'run_id','layer','split','judge_kind','session_id','turn_index',
            'route_reason']) AS c
     WHERE c NOT IN (SELECT column_name FROM information_schema.columns
                      WHERE table_schema = 'public' AND table_name = 'eval_record');
    checked := checked + 1;
    IF missing IS NULL THEN
        RAISE NOTICE '  OK   32 个字段全部就位（知识库第 550 行 25 个 + 本项目扩展 7 个）';
    ELSE
        RAISE NOTICE '  FAIL 缺字段：%', missing;
        failures := failures + 1;
    END IF;

    SELECT count(*) INTO n FROM pg_indexes
     WHERE schemaname = 'public' AND tablename = 'eval_record';
    checked := checked + 1;
    IF n >= 8 THEN   -- 1 个 PK + 1 个槽位唯一 + 6 个查询索引（V6 §2）
        RAISE NOTICE '  OK   eval_record 索引 % 个（PK + 槽位唯一 + 6 条查询索引）', n;
    ELSE
        RAISE NOTICE '  FAIL 索引只有 % 个，少于预期的 8 个（V6 §2 的查询索引没建全）', n;
        failures := failures + 1;
    END IF;

    -- ------------------------------------------------------------------
    -- ② 基线：一条**合法**行必须能插进去（否则后面的"被拦"毫无意义）
    -- ------------------------------------------------------------------
    INSERT INTO eval_record
        (eval_id, run_id, task_id, trial_id, turn_index,
         layer, split, judge_kind,
         prompt_version, model_id, dataset_version, task_as_of,
         environment_version, input_hash, raw_input, reference_output,
         actual_output, transcript_uri, route_reason, session_id,
         outcome_status, scores, judge_model, grader_version, judge_reasoning,
         error_category, root_cause_layer, confidence, git_commit)
    VALUES
        ('EV-VERIFY-L4-008-t1-r1', 'RUN-VERIFY', 'L4-008', 1, 1,
         'L4', 'dev', 'rule',
         'intent-router-v1', 'deepseek-v4-flash', 'v3', DATE '2027-09-15',
         'corpus-2026-09-21/jieba:shipping-dict-v1:rules-v2/ollama:bge-m3:1024',
         repeat('a', 64), '还有哪些跟它一个船型、但比它大的？',
         '{"expectTool":"query_vessel","expectArgs":{"min_gross_tonnage":1520}}',
         '{"tool":"query_vessel","args":{"vessel_type":"多用途船"}}',
         'target/eval/run-verify/L4-008.json',
         'rule:regex"比它大"→比较意图+多轮继承', 'sess-verify-1',
         'SUCCESS', '{"argExact":1.0,"toolMatch":1.0}'::jsonb,
         NULL, 'grader-l4-args-v1', NULL,
         NULL, NULL, 1.000, '0000000000000000000000000000000000000000');
    checked := checked + 1;
    RAISE NOTICE '  OK   合法行插入成功（占位一个带 turn_index 的槽位）';

    -- 再插一条 holdout 行，用于 ③ 的视图自证
    INSERT INTO eval_record
        (eval_id, run_id, task_id, layer, split, judge_kind, dataset_version,
         input_hash, raw_input, outcome_status, scores)
    VALUES
        ('EV-VERIFY-HOLDOUT', 'RUN-VERIFY', 'L3-099', 'L3', 'holdout',
         'reference_guided', 'v3', repeat('b', 64), '（保留集占位行）',
         'PARTIAL', '{"faithfulness":3}'::jsonb);

    -- ------------------------------------------------------------------
    -- ③ 有效性自证：12 类违规**必须被拦**
    -- ------------------------------------------------------------------
    -- 每条都断言"抛出的是预期 SQLSTATE"，避免"抛了别的错也当成拦住了"
    -- （`工程复盘/工程方法/验证工具本身也要被验证/探针假结论与正确姿势.md` 同类）。

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('层值越界 L9',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-LAYER','RUN-VERIFY','X-1','L9','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS')$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('分片越界 test',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-SPLIT','RUN-VERIFY','X-2','L1','test','rule','v3',
                   repeat('c',64),'x','SUCCESS')$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('判分口径越界 llm_judge',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-KIND','RUN-VERIFY','X-3','L1','dev','llm_judge','v3',
                   repeat('c',64),'x','SUCCESS')$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('结论状态越界 OK',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-STATUS','RUN-VERIFY','X-4','L1','dev','rule','v3',
                   repeat('c',64),'x','OK')$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('根因层越界 FOO',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status,root_cause_layer)
           VALUES ('EV-BAD-ROOT','RUN-VERIFY','X-5','L1','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS','FOO')$q$, '23514');

    -- ⚠️ 本用例的标签里**必须保留 "FAILED" 这个词**（它就是被测语义本身），
    --    这也正是上面的判据改成返回状态码的原因——⛔ 别再改回字符串匹配。
    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('FAILED 却不给任何归因（本项目最易重踩的一条）',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-NOCAUSE','RUN-VERIFY','X-6','L1','dev','rule','v3',
                   repeat('c',64),'x','FAILED')$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('置信度越界 1.5',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status,confidence)
           VALUES ('EV-BAD-CONF','RUN-VERIFY','X-7','L1','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS',1.5)$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('scores 不是对象（写成数组）',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,layer,split,judge_kind,
              dataset_version,input_hash,raw_input,outcome_status,scores)
           VALUES ('EV-BAD-SCORES','RUN-VERIFY','X-8','L1','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS','[]'::jsonb)$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('trial_id = 0',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,trial_id,layer,split,
              judge_kind,dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-TRIAL','RUN-VERIFY','X-9',0,'L1','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS')$q$, '23514');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('turn_index = 0',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,turn_index,layer,split,
              judge_kind,dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-BAD-TURN','RUN-VERIFY','X-10',0,'L1','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS')$q$, '23514');

    -- ⭐ 下面两条是本表最容易"假通过"的地方，必须**分别**验：
    --    单轮题的 turn_index 是 NULL，而 SQL 默认认为两个 NULL 互不相等
    --    ⇒ 若约束漏写 `NULLS NOT DISTINCT`，"单轮题重复落库"会**静默成功**。
    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('同一槽位重复落库（turn_index = 1）',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,trial_id,turn_index,
              layer,split,judge_kind,dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-VERIFY-DUP-1','RUN-VERIFY','L4-008',1,1,'L4','dev','rule','v3',
                   repeat('c',64),'x','SUCCESS')$q$, '23505');

    checked := checked + 1;
    failures := failures + pg_temp.expect_reject('同一槽位重复落库（turn_index = NULL，验 NULLS NOT DISTINCT）',
        $q$INSERT INTO eval_record (eval_id,run_id,task_id,trial_id,turn_index,
              layer,split,judge_kind,dataset_version,input_hash,raw_input,outcome_status)
           VALUES ('EV-VERIFY-DUP-2','RUN-VERIFY','L3-099',1,NULL,'L3','holdout',
                   'reference_guided','v3',repeat('c',64),'x','SUCCESS')$q$, '23505');

    -- ------------------------------------------------------------------
    -- ④ 保留集纪律自证：dev 视图**必须看不到** holdout 行
    -- ------------------------------------------------------------------
    SELECT count(*) INTO n FROM eval_record WHERE run_id = 'RUN-VERIFY';
    checked := checked + 1;
    IF n = 2 THEN
        RAISE NOTICE '  OK   事务内共 2 行（1 dev + 1 holdout）';
    ELSE
        RAISE NOTICE '  FAIL 事务内应有 2 行，实际 %', n;
        failures := failures + 1;
    END IF;

    SELECT count(*) INTO n FROM eval_record_dev WHERE run_id = 'RUN-VERIFY';
    checked := checked + 1;
    IF n = 1 THEN
        RAISE NOTICE '  OK   eval_record_dev 只看到 1 行（holdout 被隔离）';
    ELSE
        RAISE NOTICE '  FAIL eval_record_dev 看到 % 行，应只有 1 行——保留集隔离未生效', n;
        failures := failures + 1;
    END IF;

    SELECT count(*) INTO n FROM eval_record_dev WHERE split = 'holdout';
    checked := checked + 1;
    IF n = 0 THEN
        RAISE NOTICE '  OK   eval_record_dev 中 holdout 行数 = 0';
    ELSE
        RAISE NOTICE '  FAIL eval_record_dev 里出现了 % 行 holdout', n;
        failures := failures + 1;
    END IF;

    -- ------------------------------------------------------------------
    -- 结论
    -- ------------------------------------------------------------------
    RAISE NOTICE '---------------------------------------------';
    RAISE NOTICE '自检项 % 个，失败 % 个', checked, failures;
    IF failures > 0 THEN
        RAISE EXCEPTION 'eval_record 自检未通过：% / % 项失败', failures, checked;
    END IF;
    RAISE NOTICE '[PASS] eval_record 结构齐全、12 类违规全部被拦、保留集视图隔离生效';
END $$;

-- 本脚本**不落任何数据**（自检用的两行随事务回滚）。
ROLLBACK;
