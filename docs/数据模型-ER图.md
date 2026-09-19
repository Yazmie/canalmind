# CanalMind 数据模型（ER 图与设计说明）

> 对应规格书 §2.2「核心表设计（10 张）」
> DDL 位置：`backend/src/main/resources/db/migration/V1__init_business_schema.sql`
> 仿真数据生成：`backend/scripts/generate_seed_data.py`

---

## 一、实体关系图

```
                    ┌──────────────┐
                    │    port      │ 港区/作业区
                    │  port_code PK│
                    │ is_canal_node│← 运河节点标记
                    └──────┬───────┘
                           │ origin_port / dest_port
                           ▼
   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
   │   vessel     │──▶│   voyage     │   │    lock      │ 船闸/闸室
   │  vessel_id   │   │  voyage_no PK│   │(lock_id,ch.) │
   │  draft_m ★   │   │  etd / eta   │   │ capacity_ton │
   └──────────────┘   └──────┬───────┘   └──────────────┘
                             │
                             ▼
   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
   │  customer    │──▶│  waybill  ★  │◀──│  cargo_type  │
   │ credit_level │   │ waybill_no PK│   │is_dangerous ★│
   └──────────────┘   │ weight_ton   │   │ hazard_class │
                      │freight_amount│   └──────────────┘
                      └──┬────────┬──┘
                         │        │
              ┌──────────▼─┐   ┌──▼───────────────┐
              │ documents  │   │   fee_record     │
              │ doc_type   │   │  amount          │
              │ status     │   │  calc_basis JSONB│★可审计
              └────────────┘   └────────┬─────────┘
                                        │ rule_id
                                        ▼
                              ┌──────────────────┐
                              │  tariff_rule  ★  │
                              │  min_ton/max_ton │
                              │ effective_from/to│★带生效期
                              │ EXCLUDE 防重叠    │
                              └──────────────────┘
```

带 ★ 为设计关键点。

---

## 二、表清单与规模

| # | 表 | 说明 | 生成行数 |
|---|---|---|---|
| 1 | `port` | 港区/作业区 | 14 |
| 2 | `lock` | 船闸与闸室 | 6 |
| 3 | `vessel` | 船舶 | 240 |
| 4 | `cargo_type` | 货类 | 21 |
| 5 | `customer` | 客户 | 320 |
| 6 | `voyage` | 航次 | 2,400 |
| 7 | `waybill` | 运单（核心事实表） | 32,000 |
| 8 | `documents` | 单证 | 81,321 |
| 9 | `tariff_rule` | 计费规则（带生效期） | 357 |
| 10 | `fee_record` | 费用明细（可审计） | 78,406 |

合计约 **19.5 万行**（超出规格书 §2.2 的「3–5 万行」目标，见 §四说明）。

---

## 三、四个设计关键点

### 1. `tariff_rule` 带生效期 —— 让「同一问题不同答案」成立

每条费率规则有 `effective_from` / `effective_to`（`NULL` = 长期有效）。
查询某业务日期的费率，必须按时间点过滤。

**没有这个字段，结构化查询就退化成单表 `SELECT`，Text-to-SQL 类任务失去意义。** 有了它，「2026 年 6 月过闸费多少」和「现在的过闸费多少」是两个不同答案，才需要真正的查询能力。

### 2. `tariff_rule` 的 EXCLUDE 防重叠约束 —— 保证费率唯一命中

```sql
EXCLUDE USING gist (
    fee_type       WITH =,
    cargo_category WITH =,
    numrange(min_ton, max_ton, '[)')              WITH &&,
    daterange(effective_from, effective_to, '[)') WITH &&
)
```

依赖 `btree_gist` 扩展。**从数据库层面保证「任一时点、任一货类、任一吨位段」最多命中一条费率**，避免应用层靠代码保证唯一性。

> 注：`tariff_rule` 仅 357 行，用 `EXCLUDE` 的写入开销可接受。若未来这张表变成高频写入，需重新评估（属 A 类决策）。

### 3. `fee_record.calc_basis` (JSONB) —— 费用可审计

金额不能只存结果，必须能回答「这个数怎么算出来的」：

```json
{"weight": "2450.30", "unit_price": "1.0080", "formula": "货重 × 单价",
 "as_of": "2026-08-14", "days": 1}
```

这是**费用计算通道（确定性计算）**的输出依据，也是评测时 Execution Accuracy 的比对基础。

### 4. `port.is_canal_node` —— 区分运河节点与外围港

用于「运河专项」类查询（如「运河沿线港口的吞吐量」），无需靠港口名字符串匹配。

---

## 四、数据窗口的现实约束（⚠️ 待决策）

生成器当前的业务窗口是 **2025-10-01 ~ 2026-09-18**，而平陆运河 **2026-09-16** 才正式通航。实测分布：

| 指标 | 实测值 |
|---|---|
| 运单日期在通航前 | 31,706（**99.08%**） |
| 运单日期在通航后 | 294（**0.92%**） |
| `fee_record` 中过闸费占比 | 235 / 78,406（**0.30%**） |

**问题**：这是一个「运河航运」项目，但运河相关的数据只占极少数。原因是运河刚通航两天，历史数据不可能包含运河业务。

**待决策的三个方向**（见 `DECISIONS.md` P-04）：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A** | 保持现状（窗口截至今天） | 数据诚实，但过闸费样本极少，L1 层评测题缺乏数据支撑 |
| **B** | 窗口改为通航日起算一年（2026-09-16 ~ 2027-09-16），数据以「计划/在途」为主 | 运河数据充分，但数据是「前瞻模拟」而非历史 |
| **C** | 双段窗口：通航前一年 + 通航后一年 | 兼顾，但两段语义不同，查询时需注意 |

---

## 五、数据生成的可复现性

- **固定随机种子** `SEED = 20260918`：任何人任何机器跑出的数据完全一致。
- 这是评测可复现的前提 —— 数据集每次不同，指标就没有意义。
- CSV 产物**不入 git**（`.gitignore`），只提交生成脚本（决策 C-09）。

### 命名与 id 约定

- 业务表用 `GENERATED ALWAYS AS IDENTITY`，`COPY` 不允许直接写 `id`。
- 装载脚本走「临时表 → `INSERT ... OVERRIDING SYSTEM VALUE`」两步，
  既保住 DDL 强约束，又让仿真数据的 `id` 与外键引用完全可控。
- 装载末尾用 `setval` 修正 identity 序列，避免后续自增冲突。
