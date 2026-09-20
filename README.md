# CanalMind（运河智答）

> 平陆运河航运物流智能业务助手 — 面向内河航运场景的**双通道智能业务问答系统**

[![Java](https://img.shields.io/badge/Java-17+-orange)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-待定-lightgrey)]()
[![Spring AI](https://img.shields.io/badge/Spring%20AI-待定-lightgrey)]()
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-PgVector-blue)]()
[![License](https://img.shields.io/badge/License-待定-lightgrey)]()

---

## 项目解决了什么问题

通用大模型答不好航运业务问题，根因是**答案分散在两个地方**：

| 问题类型 | 例子 | 权威载体 |
|---|---|---|
| 规则/流程类 | "出口报关需要哪些材料？" | **规则文档** |
| 数值/聚合类 | "六景到广州港 100 吨水泥运费多少？" | **业务数据库** |

单一 RAG 只能覆盖前者，且模型**算术不可信**。CanalMind 的做法不是"检索文档"，而是**先判断该查哪儿，再去查**。

## 核心设计

```
                         ┌─────────────────┐
        用户提问 ────────▶│   意图路由层     │  ← 规则优先，分类器兜底
                         └────────┬────────┘
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                   ▼
      ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
      │ 结构化查询通道 │   │  文档检索通道  │   │  确定性计算通道│
      │ 受控查询工具集 │   │ 混合检索+Rerank│   │  费用计算引擎  │
      └───────┬───────┘   └───────┬───────┘   └───────┬───────┘
              └───────────────────┼───────────────────┘
                                  ▼
                    上下文编排 + 生成（SSE 流式）
                                  ▼
                    Trace 可观测性 + 分层评测闭环
```

### 三个关键技术决策

1. **模型只抽参数，不算钱** — 费用计算走确定性代码 + SQL 聚合。不信任模型算术，只信任其意图理解。
2. **结构化查询用 JSON Schema 契约约束**，不让模型自由生成 SQL。安全性与正确性优先于灵活性。
3. **评测分检索段与生成段**，客观正确性用数据库执行结果做 oracle，主观质量才交给 LLM Judge。

## 技术栈

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot + Spring AI |
| 模型 | DeepSeek API |
| 数据库 | PostgreSQL + PgVector |
| 缓存 | Redis |
| ORM | MyBatis-Plus |
| 可观测 | Micrometer Observation + OpenTelemetry (OTLP) |
| 部署 | 本机 PostgreSQL + Redis（**不使用 Docker**，见下） |

## 快速开始

### 前置要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | Spring Boot 3.x / Spring AI 要求 Java 17+ |
| Maven | 3.9+ | 本项目暂未提供 `mvnw` 包装器，请使用本机 Maven |
| PostgreSQL | **18** | 需扩展 `btree_gist`、`vector` |
| pgvector | **0.8.x** | ⚠️ PostgreSQL 18 不兼容 0.7 及以下 |
| Redis | 5.0+ | 缓存与会话 |
| Python | 3.10+ | 仅用于仿真数据生成与评测脚本 |

> **不使用 Docker**：本项目主路径直接使用本机 PostgreSQL 与 Redis。
> 仓库内 `docker-compose.yml` 保留，供未装本机数据库的读者快速试用。

### 首次环境自检

```powershell
# 逐项检查环境，输出可用性与版本号
powershell -ExecutionPolicy Bypass -File backend/scripts/verify_env.ps1
```

### 初始化数据库

**推荐：一键脚本**（建库 → 建表 → 装载 → 校验，实测 10 秒内完成）

```powershell
# 需先设置口令（脚本绝不弹交互提示，无口令会立即失败而非挂起）
$env:PGPASSWORD = "你的口令"
powershell -ExecutionPolicy Bypass -File backend/scripts/setup_local_db.ps1

# 库已存在需重建时（会清空现有数据）
powershell -ExecutionPolicy Bypass -File backend/scripts/setup_local_db.ps1 -Recreate
```

**手动分步**（等价于上面的脚本）：

```bash
# 1. 建库
createdb -U postgres canalmind

# 2. 建表（含 btree_gist 扩展与费率防重叠约束）
psql -U postgres -d canalmind -f backend/src/main/resources/db/migration/V1__init_business_schema.sql

# 3. 生成仿真数据（确定性，固定随机种子）
python backend/scripts/generate_seed_data.py

# 4. 装载（CSV 不入库版本控制，需本地生成）
psql -U postgres -d canalmind -f backend/scripts/seed_output/load_seed.sql
```

### 验证数据模型可用

```bash
# 能力探针：8 项检查「模型能否回答业务问题」（不只是建得起来）
psql -U postgres -d canalmind -f backend/scripts/probe_domain_model.sql

# L1 出题可行性：核对哪些问法可答、哪些不可答（第 3-4 周前置）
psql -U postgres -d canalmind -f backend/scripts/check_l1_feasibility.sql
```

> **pgvector 尚未安装**时，第 2 步仍可成功（`vector` 扩展不在 V1 迁移中）；
> 向量表随检索通道实现时单独建。安装方式见 `backend/scripts/build_pgvector.ps1`。

### 启动服务

**1. 配置密钥**

```bash
cp .env.example .env   # 填入 DEEPSEEK_API_KEY
```

> ⚠️ `.env` 里的 `DEEPSEEK_API_KEY` **不会**被 Spring AI 自动读取。
> `application.yml` 中已用 `${DEEPSEEK_API_KEY:}` 显式桥接到 Spring AI 真正读取的
> `spring.ai.deepseek.api-key`。若密钥留空，应用会**启动即失败**并提示
> `DeepSeek API key must be set`——这是刻意设计，避免等到第一次问答才暴露问题。

**2. 启动后端**（本项目未提供 `mvnw`，用本机 Maven；**须先将 `JAVA_HOME` 指向 JDK 21**）

```bash
cd backend && mvn spring-boot:run
```

**3. 验证流式接口**

```bash
curl -N "http://localhost:8080/api/chat/stream?question=过闸费怎么算&sessionId=demo"
```

事件类型：`meta`（会话信息）→ `token`（增量正文，**换行已转义为字面量 `\n`**）→ `done`（分片数与耗时）。
异常时以 `error` 事件结束，而不是直接断连。

> **转义是接口契约的一部分**：前端必须用同一套语义解码，否则正文里的
> 换行会让 SSE 事件边界错位。约定见 `SseTextCodec`（服务端与前端**必须固定同一种语义**，
> 知识库《大模型 API 调用工程实践》第 124 行）。

**4. 启动前端**（当前 `frontend/` 仅有 `.gitkeep`）

```bash
cd frontend && npm install && npm run dev
```

### 运行测试

```bash
cd backend && mvn test
```

测试分三层，**刻意分开**，以便失败时能判断是接线错、协议错还是网络错：

| 层 | 内容 | 是否依赖真实大模型 |
|---|---|---|
| 单元测试 | `SseTextCodec` 转义与往返一致性（覆盖含换行 / CR / 空行 / 反斜杠片段） | 否 |
| 端到端测试 | **真实启动 Tomcat，走真实 HTTP 连接**读 SSE 流，校验事件分帧未错位 | 否（用桩模型） |
| **调用链测试** | **本地桩 DeepSeek 服务器** + 真实 Spring AI 客户端，断言**真正发出去的请求**：路径 `/chat/completions`、`Bearer` 鉴权头、请求体含 `stream:true`/模型名/系统提示词/温度，并校验增量确实是**逐个**到达而非整体缓冲 | 否（本地桩） |

> 测试**不依赖真实大模型**：外部服务不稳定、慢、且要花钱。
> 真实模型的连通性由手工冒烟验证，见 `.env` 配置后的 `curl` 步骤。

<details>
<summary>为什么要专门测「调用链」（一个靠读文档判断不了的风险）</summary>

本项目类路径上**只有 `spring-webflux`，没有 Netty / Apache HttpClient / OkHttp**
（实测 110 个构件，HTTP 客户端相关仅 reactor-core 与 spring-webflux）。
而 Spring AI 的流式调用走 `WebClient` —— 没有传输层实现时，可能构造即失败，
或退化成"等响应全部到齐再返回"，**流式就名存实亡**。

实测结论：`WebClient` 能借 Spring Boot 自动配置的 JDK HttpClient 连接器正常工作，
**不需要引入 Netty**，且增量是逐个 flush 到达的（桩发 4 个增量 → 收到 4 个 `token` 事件）。
该结论由 `DeepSeekRequestShapeTest` 持续守护——若哪天升级把这条链路打断，测试会立刻失败。

顺带记录一个观测事实：Spring AI 发的是 `Accept: */*`，**流式由请求体的 `stream:true` 决定**，
不靠 Accept 协商。排障时看到 `*/*` 不必怀疑流式没开。

</details>

<details>
<summary>受限环境（沙箱）跑测试的额外参数</summary>

Spring Boot 测试层会静态初始化 Mockito，其 ByteBuddy agent 在受限沙箱内无法自附加，
需要显式以 agent 方式加载：

```bash
mvn test -DargLine=-javaagent:<本地仓库>/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar
```

</details>

## 目录结构

```
canalmind/
├── AGENTS.md              # 工程约定与决策纪律
├── docker-compose.yml     # 可选：一键起依赖（非主路径）
├── docs/
│   ├── DECISIONS.md       # 决策记录（已确认/待决/已否决）
│   ├── 数据模型-ER图.md    # ER 图与表设计说明
│   ├── 规格与执行清单.md    # 工程技术规格书（唯一执行依据）
│   └── 评测报告/           # ★ 评测产物
├── backend/               # Java 后端（Spring Boot + Spring AI）
│   ├── src/main/java/com/canalmind/
│   │   ├── chat/          # SSE 流式问答接口与转义编解码
│   │   └── config/        # ChatClient 装配
│   └── scripts/           # 环境自检、仿真数据生成、pgvector 编译
└── frontend/              # 前端（当前仅 .gitkeep）
```

> **说明**：`docs/AI应用开发知识库-Markdown/` 为本地只读参考资料（第三方内容），不纳入版本控制。

## 评测

本项目所有指标均可复现，**不使用无出处数字**。

| 指标 | 数值 | 状态 |
|---|---|---|
| 检索段 Context Recall | 待测 | ⏳ 第 2 周 |
| 检索段 Context Precision@k | 待测 | ⏳ 第 2 周 |
| 结构化查询 Execution Accuracy | 待测 | ⏳ 第 3 周 |
| 生成段 Faithfulness / 拒答正确率 | 待测 | ⏳ 第 4 周 |

完整方法与消融实验见 `docs/评测报告/`。

## 数据说明

⚠️ **业务库为自建仿真数据集**。真实企业运营数据不可获得，因此：

- 表结构、计费规则、约束条件按**公开的内河航运业务逻辑**设计
- 仿真数据由脚本生成，通过 schema migration + seed 脚本可完整重现
- **不提交 bulk 数据 dump**，仓库内仅保留生成脚本

⚠️ **重要交底：数据集的时间窗口是「前瞻模拟」，不是历史真实数据**

平陆运河于 **2026-09-16** 正式通航。本项目的仿真数据窗口取**通航后一年**
（2026-09-16 ~ 2027-09-15），系统「今天」为 2027-09-15。

原因是：若窗口截至真实今天，运河业务在数据中占比不足 1%，所有运河专项查询
（过闸费、运河吞吐量）都缺乏数据支撑。窗口后移后过闸费占费用明细的 29%。

**这意味着数据中的日期晚于真实日期**，请勿将其当作历史数据引用，也不要
基于本数据集对外宣称任何业务结论。详见 `docs/数据模型-ER图.md` §四。

## 已知限制

- **pgvector 尚未安装**，向量检索通道未开始（检索通道为第 2 周任务）
- **后端服务尚未搭建**：Spring Boot / Spring AI 版本待确认后方可定骨架
- 知识库文档来源为公开资料整理，**生效版本权威性有限**，生产使用需接入官方数据源
- SSE 流式调用下，HTTP Span 无法挂到模型 Span 下（Spring AI 已知限制，详见 `DECISIONS.md`）
- 结构化查询目前为契约化受控工具集，尚未支持模型自生成 SQL

## 当前进度

| 模块 | 状态 |
|---|---|
| 领域建模（10 张表 + 索引 + 约束） | ✅ 完成，已在真实 PG18.4 执行验证 |
| 仿真数据生成（22.7 万行） | ✅ 完成，7 项一致性检查通过 |
| ER 图与设计说明 | ✅ 完成 |
| 环境自检脚本 | ✅ 完成（`backend/scripts/verify_env.ps1`） |
| **一键建库脚本** | ✅ 完成并端到端实测（`backend/scripts/setup_local_db.ps1`） |
| **领域模型能力探针** | ✅ 8 项全部通过（`backend/scripts/probe_domain_model.sql`） |
| **L1 题目可行性核查** | ✅ 9 类问法核验（`backend/scripts/check_l1_feasibility.sql`） |
| pgvector 安装 | ⛔ 待编译（脚本已备：`backend/scripts/build_pgvector.ps1`） |
| **RAG 规则文档语料** | ⛔ **缺失**（已定方案，见 `docs/DECISIONS.md` **C-19**：自建仿真语料 20–30 篇） |
| **后端骨架 / DeepSeek 接线 / SSE 流式接口** | ✅ 完成，34 项测试全过（含真实 HTTP 端到端 + 本地桩调用链） |
| 文档检索通道 | ⏳ 第 2 周 |
| 结构化查询通道 | ⏳ 第 3 周 |
| 评测体系 | ⏳ 第 4 周 |
| Trace 观测 | ⏳ 第 5 周 |

> **所有能力指标均为「待测」**，尚未产生任何评测数据。本项目不使用无出处的数字。
>
> 已通过的 34 项测试验证的是**框架接线、调用链请求形态与 SSE 协议行为**，**不是模型效果**——
> 它们证明"请求发得对、流式确实在流、换行转义在协议层成立"，
> **不证明"回答是准的"**。后者要等第 4 周评测报告。
>
> ⚠️ **唯一未经我验证的一步**：到 DeepSeek 服务器的那一跳（需要有效密钥与外网）。
> 请求的正确性已由桩服务器验证到网络边界；真实连通性请按上方 `curl` 步骤自行确认。

**已知建模缺口**（记录而非隐藏）：

- `port.is_canal_node` 是**港口**属性，回答不了「这条航线是否穿越运河」。
  已定方案见 `docs/DECISIONS.md` **C-18**（`voyage` 增加 `via_canal`），**已实施**：
  实测 2,344 / 2,400 航次经运河（**97.67%**），且过闸费与该字段严格一致
  （不变式检查 0 / 0 通过）。**注意**：这是仿真数据的**端点简化口径**，
  不是真实航道几何判定，对外表述须带此限定。
- `lock` 表**无水深字段**，故「吃水 X 米的船能否过闸」不可答；
  等价问法（如「哪些船吃水超过 X 米」）可答。详见 **L1 可行性核查**。
- 数据时间窗为**前瞻模拟**（系统「今天」= 2027-09-15），非历史真实数据，详见 **C-13**。

## 文档

| 文档 | 内容 |
|---|---|
| `docs/规格与执行清单.md` | 项目定位、领域建模、模块规格、5 周执行计划 |
| `docs/DECISIONS.md` | 所有方向性决策的确认记录与已否决方案 |
| `docs/数据模型-ER图.md` | ER 图、表设计关键点、数据窗口说明、一致性检查结果 |
| `AGENTS.md` | 工程约定、知识库参照要求、决策分级机制 |

---

*项目状态：🚧 开发中（第 1 周：领域建模与环境搭建）*
