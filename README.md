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
| 部署 | Docker Compose |

## 快速开始

```bash
# 1. 配置密钥
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY

# 2. 启动依赖（PostgreSQL + PgVector + Redis）
docker compose up -d

# 3. 启动后端
cd backend && ./mvnw spring-boot:run

# 4. 启动前端
cd frontend && npm install && npm run dev
```

## 目录结构

```
canalmind/
├── AGENTS.md              # 工程约定与决策纪律
├── docker-compose.yml     # 一键起依赖
├── docs/
│   ├── DECISIONS.md       # 决策记录（已确认/待决/已否决）
│   ├── 规格与执行清单.md    # 工程技术规格书（唯一执行依据）
│   └── 评测报告/           # ★ 评测产物
├── backend/               # Java 后端
└── frontend/              # 前端
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

## 已知限制

- 知识库文档来源为公开资料整理，**生效版本权威性有限**，生产使用需接入官方数据源
- SSE 流式调用下，HTTP Span 无法挂到模型 Span 下（Spring AI 已知限制，详见 `docs/DECISIONS.md`）
- 结构化查询目前为契约化受控工具集，尚未支持模型自生成 SQL

## 文档

| 文档 | 内容 |
|---|---|
| `docs/规格与执行清单.md` | 项目定位、领域建模、模块规格、5 周执行计划 |
| `docs/DECISIONS.md` | 所有方向性决策的确认记录与已否决方案 |
| `AGENTS.md` | 工程约定、知识库参照要求、决策分级机制 |

---

*项目状态：🚧 开发中（第 1 周：领域建模与环境搭建）*
