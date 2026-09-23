# 第 5 项只读代码复核：发现清单（供验收与工程复盘归档）

> 复核时间 2026-09-21 09:00–09:10 ｜ 复核方式：**只读**读源码 + 用实测产物交叉验证（未运行、未改动被测代码）
> 被复核对象：`backend/src/main/java/com/canalmind/rag/retrieve/`（17 个主类，394a5c78 线交付中）

## A. 复核通过的关键点（这些是"错了不会报错"的地方，故逐条确认）

| 点 | 证据 | 判断 |
|---|---|---|
| RRF 公式与名次基准 | `RrfFusion` 第 118、122 行：`rank = i + 1`、`fused += 1.0/(k+rank)` | ✅ 名次 **1 起**（写成 `i` 会整体偏移一位且不报错） |
| 同分决胜 | 第 130–131 行：`comparingDouble(...).reversed().thenComparing(chunkId)` | ✅ **`.reversed()` 只作用主判据**，次级仍升序（很多人会把次级一起倒过来） |
| 同路重复 | 第 115–117 行：`seenInThisList` 只计首个 | ✅ 否则"某路返回重复行"会把该条分数抬高 |
| 产物换行 | `RetrievalResultJson` 第 80、137–139、158 行：`LF = String.valueOf((char) 10)`、**两个** indenter 都设为 LF、`Files.write(bytes)` | ✅ 完全按 `AGENTS.md` §7.2 的 Jackson `SYS_LF` 陷阱处理（只设一个不够） |
| 重排温度/输出约束 | `LlmRerankerFactory` 第 161–168 行：`temperature(0.0)` + `JSON_OBJECT` + `maxTokens(4096)` | ✅ 排序任务钉死温度，且解析侧仍按"可能不合法"处理 |
| 编造 chunk_id | `LlmReranker` 第 316–334 行：出现候选集外的 id → **整次降级**、绝不按编造 id 造候选 | ✅ 只忽略那一条会产出"看起来正常"的假结果 |
| 候选数超限 | 第 249–256 行：> 50 条**拒绝调用**而非静默截断 | ✅ 静默截断会让产物看起来与正常重排无异 |
| 关重排的产物语义 | `NoopReranker` 第 40–43 行：不写 `rerankRank/rerankScore`、模型名用 `NO_MODEL` | ✅ 避免产物"看起来像重排跑过了" |
| 策略名一致性 | `RetrievalPipeline` 第 79–90 行：`config.fusionName()` 必须等于实际 `FusionStrategy.name()`，否则构造期报错 | ✅ 防止产物**声称**用了某策略而实际不是 |

## B. 发现的缺陷（已回报给实现线，待其修复后验收）

### B1（真实缺陷）产物"声明的条数"与"实际条数"可能不一致 → 静默错数
- **证据**：`RetrievalConfig` 第 89 行声明关闭重排时上游是 `fusionTopK`；但 `RetrievalPipeline` 第 146 行关闭重排时仍调 `effective.rerank(query, fused, config.rerankTopN())`，而 `NoopReranker` 第 39 行**按 `topN` 截断**。
- **可复现**：`--no-rerank --fusion-top-k 30 --rerank-top-n 3 --top-k 5` → 校验通过、实际只有 3 条、产物却写 `topK=5`。
- **危害**：`eval_retrieval.py` 原有的 `k > topK` 守卫**拦不住**（`5 <= 5`），于是 `HitRate@5` 在 3 条上算出来，数值看起来正常。
- **已做的独立加固（我自己的文件）**：`backend/scripts/eval_retrieval.py` 新增"声明条数 ≠ 实际条数"**硬失败**守卫；已用构造的坏产物实测拦下（退出码 1）、并把既有基线复跑确认仍然通过（退出码 0）。

### B2（真实缺陷，由实现线自己的测试抓出）`.env` 覆盖进程环境变量（优先级反了）
- **证据**：测试 `RetrievalQueryCliTest.deepSeekEnvComesFromDotEnv` 失败信息 —— 断言「进程环境变量优先（与 Spring 的外部化配置顺序一致）」，实际得到 `from-dotenv`。
- **危害**：环境里显式设的变量会被仓库根 `.env` 静默覆盖；表现是"我明明改了环境变量，跑出来还是旧配置"。
- **⚠️ 对本轮实测的影响：无**。因为本项目所有命令都是**先把 `.env` 读进进程环境**再跑（见 `进度看板` §5 与环境配方），两边取值同源，故既有基线数字不受影响。**但若将来有人只设环境变量而不改 `.env`，就会踩到。**

### B3（测试/实现口径不一致，正在修）
- `LlmRerankerTest.promptShapeAndSnippetTruncation`：查询文本里的换行是否折掉的断言不匹配。
- `RetrievalQueryCliTest.unknownOptions`：期望"未知选项→友好的用法提示"，实际从 `Config.fusion("weighted")` 抛 `IllegalArgumentException`（退出码与提示形态未统一）。
- `RetrievalResultJsonTest.noRerankShapesArtifact`：无重排产物的形状断言失败（第 200 行，`Expecting value to be true but was false`）。

## C. 复核后确认的**评估约定**（第 6 项必须遵守，已写进 `item6_plan.md`）

1. 产物 `hits` 就是**最终上下文**（`hits = r.context()`），条数 = `--top-k` = 顶层 `topK`；
   故**要评估到 @k 必须 `--top-k >= k`**，四路统一用 30 的"宽上下文评估档"。
2. `--top-k` 必须 ≤ `--rerank-top-n`（`RetrievalConfig` 校验），否则 rerank 那路构造期报错。
3. 重排档的深度由 `rerank_top_n` 决定 → 跨路比较必须落在**共同的 k** 上，且不得把不同深度的数字同表并列。

## D. 尚未复核（留给验收）

- `RetrievalQueryCli` 的完整参数面与用法提示（40 KB，仅核对了 `--top-k`/`--recall-top-k`/`--fusion-top-k` 三个）；
- `RetrievalCandidate.finalScore()` 的取值语义（融合分 / 重排分 / 最终分三者关系）——**报告引用分数时必须说清用的是哪一个**；
- `Bm25RetrievalChannel` 与既有 `Bm25QueryCli` 的排序等价性（可用 `verify_bm25.py` 的 Python 镜像交叉核对）。
