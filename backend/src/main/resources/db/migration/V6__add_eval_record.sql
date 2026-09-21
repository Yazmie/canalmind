-- ============================================================================
-- V6：评测运行记录表 `eval_record`（决策 C-41，属 §4.2.1③ 数据与领域模型）
-- ============================================================================
-- 用途：第 4 周「完整评测 Harness」的落库出口 —— 让**每一轮评测都能用 SQL 查到**，
--       而不是只散落在 `backend/target/eval/*.json` 里（那个目录被 .gitignore
--       整目录忽略、且 `mvn clean` 会清空，见 D22）。
--
-- 结构来源（**照抄，非自创**）：知识库《AI 应用评测体系》第 550 行的
--   `record EvalRecord(...)` 完整字段表。该处同时给出第 553–557 行的**三类查询**：
--     ① 查版本：用同一个 `inputHash` 对比不同 `promptVersion`；
--     ② 查趋势：按 `evaluatedAt` 统计各维度分数；
--     ③ 查回归：某个 `gitCommit` 之后哪些指标下降。
--   本迁移的索引即按这三类查询 + 第 6 项「分层通过率报告」的分组键设计。
--
-- ⚠️ 与知识库第 550 行的**四处显式偏离**（都要在此写清，⛔ 不静默改）：
--   1. `trialId` 在知识库是 `String`，此处定为 `INTEGER`——它语义是"同一任务的
--      第几次运行"，整数才能直接 ORDER BY / 做重复运行统计；
--   2. `scores` 在知识库是 `Map<String, Double>`，此处落为 `JSONB`（对象）。
--      ⛔ 不拆子表：拆了会让"取一条记录的分数"退化成 JOIN，而本项目的
--      三类查询都只需 `jsonb_each_text` 透视（示例见本文件末尾）；
--   3. `taskAsOf` 在知识库是 `Instant`，此处定为 `DATE`——本项目业务口径是
--      **业务日**（数据集里叫 `businessToday`，如 2027-09-15），费率按生效期
--      按**日**匹配，带时分秒反而会引入"跨日"歧义；
--   4. 新增字段见下（C-41 授权 3 个 + C-38 授权 2 个 + 1 个必需的分组键）。
--
-- ⚠️ 新增字段及其授权来源（⛔ 逐条可追溯到用户已确认的决策）：
--   `layer`      L1–L6        —— C-41②（第 6 项分层通过率报告的分组键）
--   `split`      dev/holdout  —— C-41②（**把「保留集纪律」机器化**；第 3 周实测踩过
--                                「纪律依赖的开关其实是坏的」，见
--                                docs/工程复盘/数据一致性/保留集纪律依赖的开关其实是坏的/）
--   `judge_kind` oracle/rule/reference_guided —— C-41②（C-39 判分矩阵的落库形态，
--                                避免"规则分"与"Judge 分"在 SQL 里被混算）
--   `session_id` / `turn_index` —— C-38 影响面（多轮上下文管理需要按会话+轮次定位）
--   `route_reason`              —— C-37⑤ / C-39 的证据面：知识库《大模型网关详解》
--                                第 354 行要求"路由日志除模型名外还要记录 route_reason，
--                                否则无法还原这次选择依据"，第 4 周第 1 项的验收要靠它出数
--   `run_id`                    —— ⚠️ **超出 C-41 字面清单的 1 个字段**。理由：知识库把
--                                `runId` 放在 `EvalRunSummary` 上，而本轮**只建 EvalRecord
--                                一张表**（C-41 的确认范围），故必须有**轮次分组键**，
--                                否则 §6.4 的"查趋势 / 查回归"无法按轮次分组。
--                                如不要此字段，须同时决定 EvalRunSummary 是否单独建表。
--
-- ⚠️ 迁移方式：与 V1–V5 一致，**由 `setup_local_db.ps1` 按文件名升序用 `psql -f` 手工应用**；
--    ⛔ 本项目**不引入 Flyway**（`application.yml` 第 52–53 行已写明原因），
--    引入它属"改变既有迁移方式"，须另立 A 类决策。
--
-- ⚠️ 本迁移**只新增一张表 + 一个视图，不改任何既有表**（不动 10 张业务表与
--    `rag_chunk*`）⇒ 对第 1–3 周的既有验收零影响。
-- ============================================================================

SET client_min_messages = warning;

-- ----------------------------------------------------------------------------
-- 1. eval_record 评测运行记录
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS eval_record (
    id                    BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- --- 身份（知识库第 550 行前四项 + 轮次分组键） ---
    eval_id               VARCHAR(96)  NOT NULL,   -- 本次评测记录 ID
    run_id                VARCHAR(64)  NOT NULL,   -- 本轮 Harness 运行 ID（同轮记录共享）
    task_id               VARCHAR(32)  NOT NULL,   -- 评测任务 ID（即数据集里的题目 id，如 L4-008）
    trial_id              INTEGER      NOT NULL DEFAULT 1,  -- 同一任务的第几次运行
    turn_index            INTEGER,                 -- 多轮题的第几轮（单轮题为 NULL）

    -- --- 分层与判分口径（C-41② / C-39） ---
    layer                 VARCHAR(4)   NOT NULL,   -- L1–L6
    split                 VARCHAR(8)   NOT NULL,   -- dev / holdout（保留集纪律的落库形态）
    judge_kind            VARCHAR(20)  NOT NULL,   -- oracle / rule / reference_guided

    -- --- 版本（`AGENTS.md` §6 第 2 条：每轮只改少量变量，且要记全版本） ---
    prompt_version        VARCHAR(64),
    model_id              VARCHAR(64),
    dataset_version       VARCHAR(32)  NOT NULL,
    task_as_of            DATE,                    -- 业务时间点（"今天"是哪天）
    timezone              VARCHAR(32)  NOT NULL DEFAULT 'Asia/Shanghai',
    environment_version   VARCHAR(128),            -- 库种子 / 索引 / 分词档案 / 工具桩版本
    evidence_snapshot_uri TEXT,

    -- --- 输入与输出 ---
    input_hash            VARCHAR(64)  NOT NULL,   -- 跨版本对比同一用例的键
    raw_input             TEXT         NOT NULL,
    reference_output      TEXT,                    -- 参考答案（有金标准时）
    actual_output         TEXT,
    transcript_uri        TEXT,                    -- Trace / Transcript 存放处
    route_reason          TEXT,                    -- 本次路由的判据（C-37⑤）

    session_id            VARCHAR(64),             -- C-38：多轮题的会话标识

    -- --- 结论与归因 ---
    outcome_status        VARCHAR(16)  NOT NULL,   -- SUCCESS / FAILED / PARTIAL
    scores                JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- 各维度分数
    judge_model           VARCHAR(64),
    grader_version        VARCHAR(64),             -- 评分器 / Judge Prompt 版本
    judge_reasoning       TEXT,
    error_category        VARCHAR(32),             -- 失败现象分类（badcase 聚类用）
    root_cause_layer      VARCHAR(16),             -- DATA/MODEL/AGENT/TOOL/ENV/GRADER/ROUTER
    confidence            NUMERIC(4,3),            -- 经校准的置信信号，低置信进人工复核

    evaluated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    git_commit            VARCHAR(40),

    -- --- 约束 ---
    CONSTRAINT eval_record_eval_id_uk UNIQUE (eval_id),
    -- 同一轮里"同一题的第几次运行的第几轮"只能有一条。
    -- ⚠️ `NULLS NOT DISTINCT` 不可省：单轮题的 turn_index 是 NULL，而 SQL 默认
    --    认为两个 NULL 互不相等 ⇒ 少了它，**单轮题可以去重失败、重复落库**。
    CONSTRAINT eval_record_slot_uk
        UNIQUE NULLS NOT DISTINCT (run_id, task_id, trial_id, turn_index),

    CONSTRAINT eval_record_layer_chk
        CHECK (layer IN ('L1','L2','L3','L4','L5','L6')),
    CONSTRAINT eval_record_split_chk
        CHECK (split IN ('dev','holdout')),
    CONSTRAINT eval_record_judge_kind_chk
        CHECK (judge_kind IN ('oracle','rule','reference_guided')),
    CONSTRAINT eval_record_status_chk
        CHECK (outcome_status IN ('SUCCESS','FAILED','PARTIAL')),
    CONSTRAINT eval_record_root_chk
        CHECK (root_cause_layer IS NULL OR root_cause_layer IN
               ('DATA','MODEL','AGENT','TOOL','ENV','GRADER','ROUTER')),
    CONSTRAINT eval_record_trial_chk   CHECK (trial_id >= 1),
    CONSTRAINT eval_record_turn_chk    CHECK (turn_index IS NULL OR turn_index >= 1),
    CONSTRAINT eval_record_conf_chk
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT eval_record_scores_chk  CHECK (jsonb_typeof(scores) = 'object'),
    -- ⚠️ 这条约束是本项目的**纪律落库**，不是形式主义：
    --    「失败必须被归因」——否则 badcase 只能聚类成一堆无名 FAILED，
    --    而本项目反复踩过的正是"失败被伪装成别的东西"（§0.3 坑 #2/#3，
    --    docs/工程复盘/工程方法/验证工具本身也要被验证/)。故 FAILED 行**必须**
    --    至少给出 error_category 或 root_cause_layer 之一。
    CONSTRAINT eval_record_failed_cause_chk
        CHECK (outcome_status <> 'FAILED'
               OR error_category IS NOT NULL
               OR root_cause_layer IS NOT NULL)
);

COMMENT ON TABLE eval_record IS
    '评测运行记录（结构照抄知识库《AI 应用评测体系》第 550 行 EvalRecord；第 4 周 Harness 的落库出口）';
COMMENT ON COLUMN eval_record.run_id IS
    '本轮 Harness 运行 ID；同一次运行的所有题共享，用于按轮次分组查趋势/查回归';
COMMENT ON COLUMN eval_record.layer IS
    '评测层：L1 结构化可答 / L2 文档检索 / L3 不可答 / L4 多轮改写 / L5 复合意图 / L6 对抗与干扰';
COMMENT ON COLUMN eval_record.split IS
    'dev 或 holdout。⚠️ 调参只准看 dev（AGENTS.md §6 第 4 条）；holdout 只用于出报告';
COMMENT ON COLUMN eval_record.judge_kind IS
    '判分器类型：oracle（DB 执行结果）/ rule（纯规则）/ reference_guided（给证据的 LLM Judge）；⛔ 三者在报告里不得混算';
COMMENT ON COLUMN eval_record.scores IS
    '各维度分数（JSONB 对象，键为维度名）；用 jsonb_each_text 透视，无需拆子表';
COMMENT ON COLUMN eval_record.root_cause_layer IS
    '根因层：DATA/MODEL/AGENT/TOOL/ENV/GRADER/ROUTER；FAILED 行必须与 error_category 至少给出一个';
COMMENT ON COLUMN eval_record.route_reason IS
    '本次意图路由的判据（依据《大模型网关详解》第 354 行：不记 route_reason 就无法还原选择依据）';
COMMENT ON COLUMN eval_record.task_as_of IS
    '业务时间点（"今天"是哪天）；用 DATE 而非带时分秒的 Instant，避免跨日歧义';
COMMENT ON COLUMN eval_record.evidence_snapshot_uri IS
    '检索结果 / 工具响应的快照地址（第 4 周指向 backend/target/eval/**，⚠️ 该目录不入库，见 D22）';

-- ----------------------------------------------------------------------------
-- 2. 索引：按"三类查询 + 分层报告"设计（知识库第 553–557 行）
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_eval_record_run        ON eval_record (run_id);
-- 分层通过率报告的主分组键（layer × split × 判分口径）
CREATE INDEX IF NOT EXISTS idx_eval_record_layer      ON eval_record (layer, split, judge_kind);
-- 查趋势：按 evaluatedAt 统计各维度分数
CREATE INDEX IF NOT EXISTS idx_eval_record_evaluated  ON eval_record (evaluated_at);
-- 查回归：某个 gitCommit 之后哪些指标下降
CREATE INDEX IF NOT EXISTS idx_eval_record_git        ON eval_record (git_commit);
-- 查版本：用同一个 inputHash 对比不同 promptVersion
CREATE INDEX IF NOT EXISTS idx_eval_record_input_hash ON eval_record (input_hash);
-- 多轮题按会话+轮次回放
CREATE INDEX IF NOT EXISTS idx_eval_record_session    ON eval_record (session_id, turn_index);

-- ----------------------------------------------------------------------------
-- 3. 保留集纪律的机器化（C-41② / C-38 的配套）
-- ----------------------------------------------------------------------------
-- ⚠️ **诚实说明这条视图的强度**：它**不是**安全边界（谁都能绕过视图直接查表），
--    它是一条**纪律的默认路径**——调参 / 自检脚本一律查本视图，只有"出报告"
--    的那一个入口才允许查全表。这样"不小心把 holdout 数字看了"需要**主动多打几个字**，
--    而不是像第 3 周踩到的那样"开关宣称支持、实际取不到数，于是纪律静默失效"。
--    依据：`AGENTS.md` §6 第 4 条（保留集纪律）+ 知识库《AI 应用评测体系》第 88 行。
CREATE OR REPLACE VIEW eval_record_dev AS
    SELECT * FROM eval_record WHERE split = 'dev';

COMMENT ON VIEW eval_record_dev IS
    '仅 dev 分片：调参与自检脚本只准查本视图；holdout 只用于出报告（AGENTS.md §6 第 4 条）';

-- ----------------------------------------------------------------------------
-- 4. 本文件末尾留三条查询示例（对应知识库第 553–557 行的三类查询 + 分层报告）
-- ----------------------------------------------------------------------------
-- ① 查版本（同一用例跨 Prompt 版本对比）：
--      SELECT prompt_version, task_id, scores
--        FROM eval_record
--       WHERE input_hash = :h AND layer = 'L5'
--       ORDER BY prompt_version, evaluated_at;
--
-- ② 查趋势（每轮各维度均分）：
--      SELECT run_id,
--             s.key AS dimension,
--             round(avg(s.value::numeric), 4) AS avg_score,
--             count(*) AS n
--        FROM eval_record r, jsonb_each_text(r.scores) AS s(key, value)
--       GROUP BY run_id, s.key ORDER BY run_id, s.key;
--
-- ③ 查回归（某提交之后哪些指标下降）——与上一稳定轮次逐维度相减：
--      SELECT cur.layer, cur.dimension,
--             prev.avg_score AS baseline, cur.avg_score AS current,
--             round(cur.avg_score - prev.avg_score, 4) AS delta
--        FROM (SELECT layer, s.key AS dimension, avg(s.value::numeric) AS avg_score
--                FROM eval_record r, jsonb_each_text(r.scores) AS s(key, value)
--               WHERE r.run_id = :cur GROUP BY layer, s.key) cur
--        JOIN (SELECT layer, s.key AS dimension, avg(s.value::numeric) AS avg_score
--                FROM eval_record r, jsonb_each_text(r.scores) AS s(key, value)
--               WHERE r.run_id = :prev GROUP BY layer, s.key) prev
--          ON (cur.layer, cur.dimension) = (prev.layer, prev.dimension)
--       ORDER BY cur.layer, cur.dimension;
--
-- ④ 第 6 项分层通过率报告（**分母口径必须显式写在 SQL 里**，⛔ 不得在报告里另算一遍）：
--      SELECT layer, split, judge_kind, outcome_status, count(*)
--        FROM eval_record WHERE run_id = :run
--       GROUP BY layer, split, judge_kind, outcome_status
--       ORDER BY layer, split, judge_kind, outcome_status;
--
-- ⚠️ 知识库第 550 行还有一个 `EvalRunSummary` 结构（runId / totalCases /
--    avgScores / passRates / baselineScores / passedRegression / regressionDetails）。
--    本轮**不单独建表**——它的每一个字段都可由本表按 `run_id` 聚合得出
--    （见上面 ②③④）。若将来需要"把每轮汇总**冻结**下来做回归门禁"，
--    应另立 A 类决策再建 `eval_run`，⛔ 不要在 Harness 里偷偷写第二份汇总。
-- ============================================================================
