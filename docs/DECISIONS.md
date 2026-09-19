# 决策记录（DECISIONS）

> **用途**：记录 CanalMind 项目中所有 **A 类决策**（影响方向/范围/交付物/对外表达的决策）的确认结果。
> **依据**：`AGENTS.md` §4.2–4.4。
>
> ⚠️ **新会话开始前必须先读本文件**，避免重复讨论已定事项，也避免重复踩已否决的路。

---

## 一、决策分级回顾（详见 `AGENTS.md` §4.3）

| 级别 | AI 的行为 |
|---|---|
| **A 类｜必须确认** | 暂停并提问，等用户确认后再动手 |
| **B 类｜自主但告知** | 可先做，但须说明"有 A/B 方案，我选 X，理由 Y，可回退" |
| **C 类｜完全自主** | 直接做，不必说明 |

**兜底**：不确定是否属 A 类 → 一律按 A 类处理，先问。

---

## 二、已确认决策（Confirmed）

| # | 日期 | 决策点 | 选定方案 | 理由 | 影响模块 |
|---|---|---|---|---|---|
| C-01 | 2026-09-18 | 项目名与目录名 | **CanalMind（运河智答）**，目录 `canalmind/`，Java 包 `com.canalmind.*` | `Canal`+`Mind` 兼顾航运域与 AI 属性，不绑死"平陆"便于迁移；`canalmind` 为合法 Java 包名段 | 全局（后端包名、前端包名、Docker 服务名、数据库名） |
| C-02 | 2026-09-18 | 目录结构 | `canalmind/{AGENTS.md, docs/, backend/, frontend/}` | 资料与代码分离，根目录干净；docs 收拢全部参考资料与知识库 | 全局 |
| C-03 | 2026-09-18 | 知识库参照纪律 | 技术实现必须先查知识库，并以知识库为准；引用需附文件名与行号 | 知识库为项目技术判断的主要依据，避免凭记忆实现 | 全局 |
| C-04 | 2026-09-18 | A 类决策确认机制 | 影响方向的决策一律先确认再动手；拿不准时按 A 类处理 | 用户明确要求"有影响方向的决策要和我确认" | 全局 |
| C-05 | 2026-09-18 | 决策记录机制 | 建立 `docs/DECISIONS.md`，A 类决策确认后落库，新会话先读 | §4.2 原有"写入规格书"缺固定落点，决策会散落 | 全局 |
| C-06 | 2026-09-18 | **仓库拓扑** | **单仓库 monorepo**（docs + backend + frontend 同仓） | 作品集需要单一入口链接；跨前后端的评测报告与 Trace 是核心差异化产物，拆仓会被孤立；单人项目多仓成本高收益零；目录已天然适配 | 全局（CI、提交粒度） |
| C-07 | 2026-09-18 | **入库边界** | **知识库（第三方 javaguide.cn 内容）+ 个人求职资料不入 git**；仓库仅含自己写的产物 | ①版权风险（作品集仓库通常公开）②88% 体积被他人文章占据，面试官第一眼看到的是别人的内容，作品集信号负面 ③求职策略文档与工程无关，不宜公开 | `docs/`、`.gitignore`、`AGENTS.md` §3、§4.1 |
| C-08 | 2026-09-18 | Git 基础设置 | `main` 分支；`core.quotepath=false`（中文文件名正常）；`core.autocrlf=false` + `.gitattributes` 统一 LF | 原 `init.defaultBranch` 未设会默认 `master`；全文件原为 LF，若不锁会在 Windows 产生全仓库换行噪音 | 全局（提交干净度） |
| C-09 | 2026-09-18 | 仿真数据入库方式 | 只提交 **schema migration + seed 生成脚本**，不提交 bulk dump | 3–5 万行数据可由脚本重现，仓库轻量且数据可版本化 | `backend/`、`data/` |
| C-10 | 2026-09-18 | **规格书拆分（公开/私有边界）** | 规格书拆为「工程规格（公开）」+「求职备战笔记（本地私有）」；求职内容移入 `docs/求职备战笔记.md` 并加入 `.gitignore` | push 前审查发现：已提交的规格书含实习状态、秋招时间线、简历话术、面试问答（20 处"面试"/9 处"简历"），**与 C-07 决策冲突**；且引用了被 gitignore 的 `岗位介绍.txt`，形成悬空引用。拆分后公开仓库读起来是工程文档，且消除冲突 | 规格书全文、`.gitignore`、`README.md`、`AGENTS.md` §3 |
| C-11 | 2026-09-18 | **JDK 版本** | **JDK 21**（`D:\JDKmove\JDK21`，实测 21.0.1 LTS） | 环境实测默认仅 Java 8（`JAVA_HOME` 指向 corretto-1.8.0_482），**不满足 Spring Boot 3+/Spring AI 的 Java 17+ 要求**；用户提供本机 JDK 21，已验证可用 | 全局（编译、Maven、IDEA 配置） |
| C-12 | 2026-09-18 | **运行环境不依赖 Docker** | 用**本机 PostgreSQL 18.4 + 本机 Redis**，弃用 Docker Compose | 环境实测**未安装 Docker**（命令不存在，WSL 受限）。而本机 PG18.4 已运行于 5432、Redis 5.0.14.1 已装。已写的 `docker-compose.yml` 保留作为可选方案（供他人 clone 后使用），但不作为本项目主路径 | `docker-compose.yml`、启动文档、`AGENTS.md` §5 |
| C-13 | 2026-09-18 | **仿真数据时间窗（P-04）** | **通航后一年**：`2026-09-16 ~ 2027-09-15`，系统「今天」= 2027-09-15 | 运河 2026-09-16 才通航，真实今天仅 09-18。窗口截至今日时实测运河业务仅 0.92%、过闸费仅 0.30%，运河专项评测题无数据支撑。后移后过闸费占费用 29.05%。**代价**：数据集为前瞻模拟而非历史真实数据，README 须主动交底 | `generate_seed_data.py`、`docs/数据模型-ER图.md`、README 数据声明 |
| C-14 | 2026-09-18 | **仿真数据规模（P-03）** | 核心事实表 `waybill` **32,000 行**，全库 227,026 行 | 落在规格书 §2.2「核心表 3–5 万行」区间内；其余表由 waybill 派生。数据量使索引与聚合查询具有真实意义 | `generate_seed_data.py`、`docs/数据模型-ER图.md` |
| C-15 | 2026-09-18 | **pgvector 版本约束（P-05）** | 必须用 **pgvector 0.8.x**，**禁止 0.7 及以下**；从源码用 MSVC 编译安装 | 用户查证：**PostgreSQL 18 需搭配 pgvector 0.8.x**，0.7 及以下不兼容 PG18。实测本机无 Docker 但有 MSVC 14.36/14.41（`D:\Visual Studio\IDE`），故走源码编译路径 | 后端依赖、`docker-compose.yml`、启动文档 |

---

## 三、待决决策（Pending）

> 以下是**已识别的 A 类决策**，做到对应模块时需与用户确认。
> 与 `AGENTS.md` §4.5 的 D1–D16 清单对应。

### 近期（第 1 周即需确认）

| # | 决策点 | 选项 | 出处 | 状态 |
|---|---|---|---|---|
| P-01 | **Spring Boot / Spring AI 版本** | 见下方「P-01 查证结果」 | 实测本机 Maven 仓库 | 🔶 **已有硬证据，待确认** |
| P-02 | 技术栈最终确认 | 规格书方案（Spring Boot+Spring AI+PG/PgVector+Redis+MyBatis-Plus）是否照用 | 规格书 §3.2 | ☐ 待确认 |
| P-03 | 业务领域模型与数据规模 | 10 张表设计、仿真数据量级 | 规格书 §2.2 | ✅ 已确认 → **C-14** |
| P-04 | 仿真数据时间窗与运河业务占比 | A 截至今日 / B 通航日起一年 / C 双段窗口 | `docs/数据模型-ER图.md` §四 | ✅ 已确认 → **C-13**（选 B）|
| P-05 | pgvector 安装方式与版本 | Docker / 源码编译 / 放弃 | 实测环境 | ✅ 已确认 → **C-15**（0.8.x + MSVC 源码编译）|
| P-06 | **航线级「是否经运河」标记** | A 加 voyage 字段 / B 加航段路径表 / C 维持现状并声明口径 | 探针 7 实测发现（见下） | ☐ **待决（属于③数据与领域模型，A 类）** |
| D1 | Chunk 切分策略 | 固定长度 / 结构化 / Parent-Child / 语义 | `RAG 优化` 第 364 行；`RAG 文档处理与切分策略` 第 25、67 行 | ☐ 待决 |
| D5 | 向量索引算法 | HNSW / IVFFLAT | `RAG 向量索引算法和向量数据库` 第 111、171 行 | ☐ 待决 |
| D6 | 向量库选型 | PgVector / Milvus / Qdrant / Weaviate / Elasticsearch | 同上 第 244–247 行 | ☐ 待决 |
| D11 | Schema 约束强度 | JSON Mode / JSON Schema / Structured Outputs | `大模型结构化输出` 第 105 行 | ☐ 待决 |
| D12 | 结构化查询形态 | 契约化受控工具集 / 模型自生成 SQL | 规格书 §0.3 | ☐ 待决 |

### P-01 查证结果（2026-09-19，基于本机 Maven 仓库实测）

**背景**：知识库 `AI 可观测性与 Trace` 第 420 行以 **Spring AI 2.0.0 + Spring Boot 4.x** 为例。
项目沙箱无外网，故改用「本机 Maven 仓库实际缓存」作为证据来源。

**实测发现（`D:\MavenRepo\maven-3.9.12`，快照时间 2026-05-28）**：

| 事实 | 证据 |
|---|---|
| Spring AI 仅有 **1.0.0 / 1.1.0 / 1.1.2**，**不存在任何 2.x** | 遍历 `org/springframework/ai` 全部构件版本 |
| **Spring AI 1.1.2 的 starter 明确依赖 Spring Boot 3.5.8** | `spring-ai-starter-model-deepseek-1.1.2.pom` → `spring-boot-starter:3.5.8` |
| Spring AI 1.1.2 编译期依赖 **Spring Framework 6.2.14** | `spring-ai-deepseek-1.1.2.pom` |
| 存在 **DeepSeek 官方支持** | `spring-ai-starter-model-deepseek`（含 JAR，SHA1 校验通过） |
| 本机缓存 Spring Boot：3.5.0 / **3.5.9**（无 3.5.8） | `org/springframework/boot/spring-boot` |
| `spring-boot-dependencies:4.0.0` 的 **POM 存在**（仅 BOM，属 Spring Boot 4 线） | `spring-boot-dependencies/4.0.0` |

**⚠️ 结论：知识库第 420 行的「Spring AI 2.0.0 + Spring Boot 4.x」在本机缓存中 **无对应证据**，不应照用。**

**建议方案**：
- **Spring Boot 3.5.9**（本机已缓存，与 Spring AI 1.1.2 的 3.5.x 线一致）
- **Spring AI 1.1.2**（本机缓存最完整：56 个构件；含 DeepSeek starter）

**⚠️ 已知阻塞（需用户在有网环境执行）**：Maven 离线解析失败，原因是
`_remote.repositories` 记录的来源仓库 id 为 `huaweicloud`，而
`settings.xml` 中镜像 id 为 `aliyunmaven`，两者不一致导致 Maven 拒绝使用已缓存构件。

### P-01 补充查证（2026-09-19 第二轮，含对前一轮结论的更正）

**❌ 前一轮的错误结论**：曾称「`spring-boot-starter-web:3.5.9` 已可离线解析」。
**该结论是错的** —— 当时用 `Select-Object -Last 10` 截断了输出，漏看了错误。
实测**仅 Spring Boot（不含 Spring AI）同样离线解析失败**。

**离线阻塞的真实范围**（已量化）：

| 阻塞类型 | 具体缺失 |
|---|---|
| 来源仓库 id 不匹配 | `_remote.repositories` 记 `huaweicloud`，settings 镜像 id 为 `aliyunmaven` → Maven 判定 "present, but unavailable" |
| **插件 JAR 缺失** | `maven-compiler-plugin:3.13.0`、`maven-resources-plugin`、`maven-dependency-plugin` 的传递依赖（`doxia-sink-api`、`commons-io`、`plexus-xml`）等 |
| 全库 `.lastUpdated` 失败标记 | 1114 个 |

**✅ 但找到了可复现的离线构建配方（已实测 BUILD SUCCESS）**：

```bash
mvn -o \
  -s <settings 中镜像 id 必须为 huaweicloud> \
  -gs <同上，全局设置也需覆盖> \
  "-Daether.enhancedLocalRepository.trackingFilename=_none.repositories" \
  clean compile
```

配合在 POM 中**固定插件版本到本机已缓存者**：

| 插件 | Boot 默认（缺失） | 本机已缓存 → 改用 |
|---|---|---|
| `maven-compiler-plugin` | 3.13.0 | **3.14.0** |
| `maven-resources-plugin` | 3.3.1 | 3.3.1（恰好已有） |

**实测结果**：`Spring Boot 3.5.9 + Spring AI 1.1.2 + spring-ai-starter-model-deepseek`
离线编译通过（`BUILD SUCCESS`，`javac target 21`），且
`ChatClient` / `DeepSeekChatModel` 类可正常引用。

**因此 P-01 的方案在技术上已被验证可行**，仅差用户确认。

### ⚠️ 影响 D6（向量库选型）的新发现

Spring AI **1.1.2** 在本机仓库中**没有 PgVector store**：

| 向量库 | 1.1.2 本地可用 |
|---|---|
| Milvus | ✅ `spring-ai-starter-vector-store-milvus` |
| Qdrant | ✅ `spring-ai-starter-vector-store-qdrant` |
| Redis | ✅ `spring-ai-starter-vector-store-redis` |
| **PgVector** | ❌ **无 `spring-ai-pgvector-store` / `spring-ai-starter-vector-store-pgvector`** |

**这不改变规格书的 PgVector 选型**（联网后可正常下载），但需注意：
- 若坚持 PgVector，**必须联网下载**该构件，且需先完成 pgvector 扩展编译（C-15）
- 若某天 PgVector 扩展编译受阻，Milvus/Qdrant/Redis 三个方案**本地已具备**，可作为备选
- 该发现记入 D6，正式选型仍在第 2 周决策时确认

**待联网补下载的构件（本地缺失，第 2 周检索通道会用到）**：

| 构件 | 本地状况 | 何时需要 |
|---|---|---|
| `spring-ai-rag:1.1.2` | 仅有 1.1.0 | 第 2 周（RAG 编排） |
| `spring-ai-pgvector-store:1.1.2` | **完全缺失** | 第 2 周（向量检索入库） |
| `spring-ai-starter-vector-store-pgvector:1.1.2` | **完全缺失** | 第 2 周 |
| `maven-compiler-plugin:3.13.0` | 仅有 3.10.1/3.11.0/3.14.0 | 首次联网构建即可解决 |

> 联网后一次 `mvn dependency:go-offline` 即可补齐全部缺失构件。

### P-06 详情：航线级「是否经运河」标记（2026-09-19 探针 7 实测发现）

**发现过程**：编写 `backend/scripts/probe_domain_model.sql` 时，探针 7 想验证
「多少业务真正走了运河」，结果实测 **97.67%**，与 `docs/数据模型-ER图.md`
原先写的「100%（全部经运河）」**不符**。

**根因**：`port.is_canal_node` 是**港口**属性，只能回答「这个港口是不是运河节点」，
**回答不了「这条航线是否穿越运河」**。实测存在两端都非节点、但地理上仍经运河的航线：

```
南宁港六景作业区 → 北海港     56 航次（两端均非运河节点）
```

按地理实际该航线仍需 郁江 → 平陆运河 → 钦州 → 沿海 → 北海，
即**端点口径会漏计**，97.67% 只是**下界**。

同时发现：生成器把过闸费设为**必收项**（`generate_seed_data.py` 第 504 行注释
「业务窗口中所有货流均经平陆运河」），因此**这 56 个航次上的 743 张运单
明明两端都不在运河节点，却仍被收了过闸费**。

**性质**：这不是 bug，而是**建模口径不完整** —— 缺一个权威的「航线是否经运河」事实来源。

| 选项 | 做法 | 代价 | 影响 |
|---|---|---|---|
| **A** | `voyage` 加 `via_canal BOOLEAN` 字段，生成时按航线规则判定 | 改 DDL + 重生成数据 | 查询简单，但规则仍是我们自己定的 |
| **B** | 新增航段路径表（`voyage_leg`），记录经停港序列，据此推导 | 建模成本最高 | 最贴近真实，也能支撑「途经哪些闸」类问题 |
| **C** | 维持现状，在文档与对外表述中**明确声明口径**（过闸费覆盖率 100% / 端点口径 97.67% 下界） | 零成本 | 无法回答「不经运河的货流」类对比问题 |

**我的建议：A**（若时间紧则 C）。理由：本项目**不需要精确的航线路径**，
只需要一个「是否经运河」的判定位；A 的改造成本可控，且能让
「运河 vs 非运河」成为**可对比的评测维度**（例如"不走运河的话费用是多少"）。
但**跑道规模**（B）对本项目收益递减，不建议。

⚠️ **该决策属于 A 类（③数据与领域模型）**，且会改动已确认的 C-14 数据规模，
**须用户确认后再动手**。在确认前：

- 探针 7 已改为**同时输出两个口径**并标注 97.67% 是下界
- `docs/数据模型-ER图.md` §四已更正，删除「100%」的绝对表述
- **对外表述中不得引用单一数字**称"运河业务占比 X%"

### 中期（第 2–3 周）

| # | 决策点 | 选项 | 出处 | 状态 |
|---|---|---|---|---|
| D2 | 混合检索融合方式 | RRF / 归一化加权 | `RAG 优化` 第 123 行 | ☐ 待决 |
| D3 | **是否启用混合检索** | 纯向量 vs 向量+BM25 | `RAG 优化` 第 125 行 | ☐ 待决 |
| D4 | Rerank 方案 | 专用 Reranker / LLM Rerank | `RAG 优化` 第 184 行 | ☐ 待决 |
| D8 | Query Rewrite 策略 | 多种策略 | `RAG 优化` 第 137 行 | ☐ 待决 |
| D7 | 路由策略演进 | 固定规则→级联→语义分类→学习型→个性化→Agentic | `大模型网关详解` 第 334 行 | ☐ 待决 |
| （外部） | 中文 BM25 分词方案 | zhparser / pg_jieba / 应用层 IK | 知识库无覆盖，需外部查证 | ☐ 待决 |

### 后期（第 4–5 周）

| # | 决策点 | 选项 | 出处 | 状态 |
|---|---|---|---|---|
| D13 | 评测样本规模与分层 | 起始 20–50 条 → 目标 150–200；分层维度 | `AI 应用评测体系` 第 92–100 行 | ☐ 待决 |
| D14 | Judge 用法 | 规则 / LLM-as-Judge / Reference-guided Judge | 同上 第 120、196–198 行 | ☐ 待决 |
| D10 | 上下文超限处理 | 滑动窗口 / Compaction / 结构化笔记 | `上下文工程` 第 172 行 | ☐ 待决 |
| D15 | 观测平台选型 | 通用 APM / LLM Trace 平台 / 自建 OTLP | `AI 可观测性与 Trace` 第 488–497 行 | ☐ 待决 |
| D16 | 缓存策略 | 普通缓存 / 语义缓存 | `大模型网关详解` 第 300–312 行 | ☐ 待决 |
| D9 | 是否上 GraphRAG | 向量RAG(+混合+rerank) vs 图结构 | `GraphRAG` 第 89、230–232 行 | ☐ 待决 |
| （范围） | 5 周内是否砍模块 | 砍 GraphRAG / 自生成 SQL / 缓存 / 限流 | 规格书 §8「如果只有 3 周」 | ☐ 待决 |

---

## 四、已否决方案（Rejected）

> 记录被明确否决的路线及原因，**避免后续会话重新提议**。

| # | 日期 | 否决的方案 | 否决原因 |
|---|---|---|---|
| R-01 | 2026-09-18 | 纯"文档 RAG 智能客服"（原 `平陆运河航运物流智能客服系统.md` 方案） | 价值押在拿不到的知识库上；技术内核停在 2024 水位；产出无法验证（无 Golden Set/分层/消融） |
| R-02 | 2026-09-18 | 直接做通用 Text-to-SQL / NL2SQL | 知识库零覆盖，难度与风险过高；已降级为"契约化受控查询工具集" |
| R-03 | 2026-09-18 | 项目名包含"平陆"二字（如 `pingluai`） | 绑死单一场地，通用性弱，其他行业岗位看不出可迁移性 |
| R-04 | 2026-09-18 | 简单并入既有 `AGENTS.md`（只加一句"多方案要问"） | 触发条件过窄（仅限知识库多方案），无法覆盖范围变更、领域模型、指标口径、对外表达等方向性决策 |

---

## 五、变更日志（Changelog）

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-09-18 | 建立本文件 | 落实 `AGENTS.md` §4.4，A 类决策需固定落点 |
| 2026-09-18 | 确认 C-01–C-05 | 项目更名 CanalMind、目录重构、确立决策纪律 |
| 2026-09-18 | 确认 C-06–C-09 | 仓库拓扑定为 monorepo；确定入库边界（知识库与个人资料不入库）；Git 基础设置；仿真数据仅提交脚本 |
| 2026-09-18 | 初始化 git 仓库 | `git init -b main`，首个提交 13 个文件 / 1855 行；已验证无密钥泄漏、全部 LF |
| 2026-09-18 | 确认 C-10 | push 前审查发现规格书含个人求职内容，与 C-07 冲突。拆分规格书为「工程规格（公开）」+「求职备战笔记（本地私有）」 |
| 2026-09-18 | 开始第 1 周任务 | 环境实测发现三个阻塞项：默认仅 Java 8、未安装 Docker、pgvector 未装。确认 C-11（JDK 21）、C-12（本机 PG+Redis 不依赖 Docker） |
| 2026-09-18 | 完成 10 张业务表 DDL | `V1__init_business_schema.sql`，含 `btree_gist` 依赖与 `tariff_rule` 防重叠 EXCLUDE 约束 |
| 2026-09-18 | 完成仿真数据生成脚本 | `generate_seed_data.py`，固定种子确定性生成 19.5 万行；FK 完整性 0 错误、费率规则 0 重叠。发现过闸费生成 bug 并修复 |
| 2026-09-18 | 提出 P-04、P-05 | P-04：运河通航仅两日导致运河业务占比 0.92%、过闸费 0.30%，需定时间窗策略；P-05：pgvector 安装方式待定 |
| 2026-09-18 | 确认 C-13 ~ C-15 | P-04 选方案 B（窗口后移至通航后一年）；P-03 确认保持 32,000 行 waybill；P-05 定 pgvector **0.8.x** 源码编译（PG18 不兼容 0.7 及以下） |
| 2026-09-18 | 修复数据生成器 3 个 bug | ①过闸费零生成 ②CSV 未 gitignore（违反 C-09）③日期越界与编号年份不一致。修复后 7 项一致性检查全部通过 |
| 2026-09-19 | **DDL 与装载在真实 PG18.4 上执行验证通过** | 临时集群（端口 55432）实测：DDL 无错误、10 张表、46 索引、`btree_gist` 与 `tariff_no_overlap` EXCLUDE 约束生效；227,026 行全部入库。**并修复装载脚本 bug**：`setval` 对无 `id` 列的 `port`/`cargo_type` 报错（`WHERE EXISTS` 无法兜底，PostgreSQL 解析期即报错），改为显式 `TABLES_WITH_ID` 列表 |
| 2026-09-19 | 新增环境自检与 pgvector 编译脚本 | `verify_env.ps1`（8 项通过 / 1 项失败）、`build_pgvector.ps1`（含 PG18 需 0.8+ 的版本守卫）。**唯一剩余环境缺口：pgvector 未安装** |
| 2026-09-19 | 验证 Redis 可用 | 本机 Redis 5.0.14.1 启动成功，PING/读写/DEL 均正常 |
| 2026-09-19 | **P-01 取得硬证据** | 实测本机 Maven 仓库：Spring AI 只有 1.0.0/1.1.0/1.1.2（**无 2.x**）；1.1.2 starter 依赖 **Boot 3.5.8**；含 DeepSeek 官方 starter。**知识库的「Spring AI 2.0.0 + Boot 4.x」无证据支撑**。建议 Boot 3.5.9 + Spring AI 1.1.2，待用户确认 |
| 2026-09-19 | 更正 P-01 前一轮的错误结论 | 前轮曾称「Boot 3.5.9 已可离线解析」——**该结论错误**（当时用 `Select-Object -Last 10` 截断输出漏看错误）。实测仅 Boot 也失败。**已找到可复现的离线构建配方**并实测 BUILD SUCCESS：统一镜像 id 为 `huaweicloud` + `-gs` 同时覆盖全局设置 + `-Daether.enhancedLocalRepository.trackingFilename=_none.repositories` + POM 固定插件版本（compiler 3.14.0） |
| 2026-09-19 | 新增 `setup_local_db.ps1` 并端到端实测通过 | 一键建库→建表→装载→校验。实测输出：10 张表、EXCLUDE 约束生效、227,026 行、32,000 waybill / 110,167 fee_record。**过程中修复 3 个脚本缺陷**：①`psql` 缺 `-w` 导致认证失败时挂起 2 分钟 ②`$ErrorActionPreference="Stop"` 使 psql 的 stderr 变成终止错误、友好提示打不出来 ③`Test-NetConnection` 在受限环境误报端口不通 |
| 2026-09-19 | 新增 `probe_domain_model.sql` 领域模型能力探针（8 项） | 验证模型**能回答业务问题**（不止建得起来）。8 项全部通过，耗时 0.4 秒。**发现真实建模缺口 → P-06**：`port.is_canal_node` 无法回答「航线是否经运河」，端点口径实测 97.67% 与文档原写的 100% 不符。**已更正 `数据模型-ER图.md` 并删除「100%」绝对表述**。过程中修复 3 个探针缺陷（UNION 类型不匹配、`v.name` 别名错、`ORDER BY amount` 无索引导致卡死 4.5 分钟） |

---

*本文件随项目推进持续更新 ｜ v1.0 ｜ 2026-09-18*
