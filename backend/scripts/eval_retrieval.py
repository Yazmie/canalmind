r"""Golden Set L2 上的**检索指标**计算（纯规则口径，决策 C-28）。

为什么需要它
------------
1. **指标必须有基线才算数**。项目纪律（`AGENTS.md` §6 第 1 条）要求"任何写进文档的数字都要能
   定位到《评测报告》"，而"能不能召回"必须先有**纯向量检索的裸基线**，
   否则第 5/6 项（RRF 融合、LLM Rerank、三档 Top-K）的增益无从判断——
   知识库《RAG 优化》第 178 行专门提醒：**Rerank 不会补回候选池里没有的文档，
   要先检查召回**。本脚本就是那把尺子。
2. **口径必须写死在代码里**。知识库《AI 应用评测体系》第 248 行警告"不要混用不同来源的
   指标口径"；第 229–240 行给出 Recall@k / HitRate@k / MRR 的传统 IR 定义。
   本脚本只实现**纯规则**口径（靠 gold chunk id 命中），**不使用任何 LLM 打分**——
   LLM 口径（RAGAS 之 Context Recall）另属 L1/L5 层，两者不可互相替代（第 246、248 行）。

指标定义（本脚本唯一权威，报告里出现的数字都按这里算）
------------------------------------------------------
设某题的 gold 集合为 G（|G| = m，本数据集 L2 层 m 恒为 1，见下"已知退化"），
检索返回的 top-k 列表为 R_k（k 为截断位，rank 从 1 起）：

| 指标 | 定义 | 说明 |
|---|---|---|
| `HitRate@k` | `1{R_k ∩ G ≠ ∅}` 的均值 | 至少命中一个 gold 的题占比 |
| `Recall@k` | `\|R_k ∩ G\| / m` 的均值 | 召回率；m=1 时与 HitRate@k **恒等** |
| `Precision@k` | `\|R_k ∩ G\| / k` 的均值 | ⚠️ m=1 时上界仅 1/k，**几乎不携带信息**，仅为口径完整而列 |
| `Precision@m`（即 R-Precision / Top-1 准确率） | `\|R_m ∩ G\| / m` 的均值 | m=1 时即"第一个结果就是答案"的比例，**本数据集最有信息量的头部指标** |
| `MRR@k` | `1/rank_1` 的均值（无命中记 0），`rank_1` = 首个 gold 的排名 | 对"答案排第几"敏感 |

⚠️ **已知退化（必须在报告里说明，不能当作实现问题）**：L2 层 40 条题的 gold 均为
**单个 chunk**（依据 C-28 的"最小充分集"标注原则），故 `Recall@k ≡ HitRate@k`。
若想让 Recall 与 HitRate 分离，需要 **gold 多证据**的题（跨文档复合问题，属 L5 层）。
本脚本会把这个恒等式**实测验证**出来并写进产物（`recallEqualsHitRate`），
而不是靠人记得。

输入与产物
----------
    # 1) 先用向量通道跑出原始命中（Java CLI，见 --hits）
    # 2) 再离线算指标（**可反复重算而不必重跑检索**——这是把原始命中单独落盘的用意）
    python backend/scripts/eval_retrieval.py --hits backend/target/eval/vector-l2-hits.json
    python backend/scripts/eval_retrieval.py --hits ... --json docs/评测报告/retrieval-baseline-l2.json

    # 原始命中格式 = VectorQueryCli 的 --out JSON（键名严格一致，本脚本按 schema 校验）
    # 退出码 0 = 指标计算完成；数据不全（缺题/多题/重复题）则非 0，不做"部分算分"

子集出分（`--subset`）
----------------------
**用途（为什么必须有）**：项目纪律要求「调参只用开发集（dev），保留集（holdout）不参与调参」
（`AGENTS.md` §6 第 4 条）。但默认模式下本脚本硬要求 40 题全部到齐，于是"只含 dev 32 题的产物"
会被判失败——**纪律就被工具逼成了违规**：最省事的做法反而变成跑全 40 题、顺手看一眼保留集。
本开关让「只用 dev 出分」成为一类**被工具支持的正规操作**：只对产物里**实际存在**的题目出分，
不因缺席而失败，并把覆盖信息（`subset` 节点：出分题数 / 金标题数 / 缺席题清单 / 缺席题按划分计数）
写进产物，同时把缺席题按 split 打印到 stdout。

⛔ **子集出分的数字不得与全量数字并列比较**：两者分母不同（例如 32 vs 40），
并列即误读。本项目同一天已经吃过「旁证一样、结论不同」的教训——数字看着同源，结论却不同。

⚠️ 子集模式**只放松"缺席即失败"这一条**，其余底线一条不放松：
产物里出现金标集之外的题（不认识的 query）→ 失败；声明条数 ≠ 实际条数 → 失败；
产物一题都没有 → 失败；`--k` 超过产物 `topK` → 失败。

`--k` 不含产物 `topK` 时怎么办（不换口径、不写 0）
-------------------------------------------------
`MRR@topK`、「严格口径 vs 可答口径」对照块都**锚在产物声明的 `topK` 上**（固定口径，
不是"随便挑一个 k"）。若 `--k` 里没有 `topK`——例如对 `topK=30` 的产物传 `--k 1,3,5,8,10`
（真实工作场景）——本脚本的行为是：

* 只对 `--k` 里**已有的档**出 `MRR@k`；`MRR@{topK}` 那一格渲染成 `—`，并打印
  `MRR@{topK} 未出：--k 未包含 {topK}（MRR@k 依赖 k，不同 k 不可比）`；
  锚在 `topK` 上的那张对照块整块不出，改为一行说明（另附唯一不依赖 k 的 `answerableTop1`）。
* 产物里**不写**那个键（⛔ 不写 `null`、更不写 `0`）。
* ⛔ **不拿别的 k 顶替**（例如用 `MRR@10` 顶 `MRR@30`）——那是换口径，而 MRR / HitRate
  都依赖 k，不同 k 的数字**本来就不可比**；也不写 `0`（会被读成"得了 0 分"）。
* 需要那张表时把 `topK` 加进 `--k` 即可（脚本会在提示里给出可直接复制的命令）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

# ⚠️ 必须把 stdout/stderr 钉成 UTF-8：本机控制台默认代码页是 GBK，
# 而本脚本要打印中文标签（题型名、题面、提示语）——不钉就会在打印时抛
# `UnicodeEncodeError: 'gbk' codec can't encode character …`，**跑了一半才炸**。
# 兄弟脚本 verify_bm25.py 有同样的块（本项目第一次踩到就是在那里）。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # pragma: no cover - 受限环境下拿不到 reconfigure
        pass

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_bm25 import Db, load_env  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "docs" / "评测报告" / "golden-l2-v2.json"
DEFAULT_PSQL = Path(r"D:\APP\PostgreSQL\18\bin\psql.exe")
DEFAULT_KS = (5, 10, 20, 30)

METRIC_DEFINITIONS = {
    "HitRate@k": "至少命中一个 gold chunk 的题占比",
    "Recall@k": "|R_k ∩ G| / |G| 的均值",
    "Precision@k": "|R_k ∩ G| / k 的均值（m=1 时上界仅 1/k，信息量低，仅为口径完整而列）",
    "Precision@m": "|R_m ∩ G| / |G| 的均值（R-Precision；m=1 即 Top-1 准确率）",
    "MRR@k": "首个 gold 命中排名倒数的均值（无命中记 0）",
    "answerableRate@k": "至少命中一个 gold **或可接受等价块**（A）的题占比——**新增口径**；"
                        "与 HitRate@k 之差 = 语料跨文档复述造成的低估幅度。"
                        "⚠️ 与严格口径并列报告，不得混用（严格口径只读作下界）",
    "answerableTop1": "榜首落在 G ∪ A 内的题占比（**新增口径**）",
    "siblingExemptPrecision@k": "把「与 gold/等价块同 (doc_id, article_no) 的兄弟块」也视为正确的 Precision@k"
                                "（**新增对照口径**：同条正文块与表格块属同一出处，是切分产物而非检索错误）",
    "wording": "传统 IR 纯规则口径，只用 chunk_id 命中判定，不含任何 LLM 打分；"
               "与 RAGAS 的 Context Recall 属不同口径，不可互相替代（知识库《AI 应用评测体系》第 246、248 行）",
}


def sha256_file(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def load_hits(path: Path) -> dict:
    """读并**校验** VectorQueryCli 的命中 JSON。键名不符就报错，不做猜测式容错。"""
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("channel", "topK", "queryCount", "results"):
        if key not in data:
            raise SystemExit(f"[FAIL] 命中文件缺少字段 {key!r}：{path}")
    if len(data["results"]) != data["queryCount"]:
        raise SystemExit(f"[FAIL] queryCount={data['queryCount']} 与 results 条数 "
                         f"{len(data['results'])} 不一致：{path}")
    for r in data["results"]:
        if "query" not in r or "hits" not in r:
            raise SystemExit(f"[FAIL] results 元素缺少 query/hits：{r}")
        for i, h in enumerate(r["hits"], start=1):
            for key in ("rank", "chunkId", "score"):
                if key not in h:
                    raise SystemExit(f"[FAIL] 命中项缺少字段 {key!r}：{h}")
            if h["rank"] != i:
                raise SystemExit(f"[FAIL] rank 不连续：第 {i} 个命中项 rank={h['rank']}")
    return data


def check_declared_hit_counts(hits_data: dict) -> None:
    """声明条数 vs 实际条数：必须一致（否则指标是静默错的）。

    ⚠️ 为什么单独查这一条：`topK` 是产物**声明**的条数，而指标是在 `hits` **实际**条数上算的。
    若某个实现把上游截断得比 `contextTopN` 更早（例如关闭重排时仍按 `rerankTopN` 截断），
    就会产生"声明 5 条、实际 3 条"的产物——此时 `HitRate@5` 只在 3 条上算，
    **数值看起来正常、也不会触发上面那条 k > topK 的守卫**。
    这正是本项目反复出现的"静默错数"族（参见 `docs/工程复盘/检索工程/度量口径没进产物`），
    故在评测入口就把"声明与实际不符"判为失败，而不是留给人眼去对。

    ⚠️ 零条产物的处理（本次新增，与 `--subset` 无关的顺带收口）：原先这一守卫用
    `min(hit_counts.values())`，产物为空时 `hit_counts` 是空字典，`min()` 直接抛
    `ValueError: min() arg is an empty sequence`——**崩溃不是"失败"的合格形式**（栈回溯既不
    自证原因，也会被误读成脚本坏了）。故空产物在此显式判失败；子集模式**不豁免**这一条
    （子集允许缺席，不允许一题都没有）。
    """
    top_k = int(hits_data["topK"])
    if not hits_data["results"]:
        raise SystemExit(
            f"[FAIL] 产物里 0 条检索结果（声明 queryCount={hits_data['queryCount']}、topK={top_k}）。\n"
            f"       空产物不出分：子集出分（--subset）只允许**缺席部分题**，不允许一题都没有。"
        )
    hit_counts = {r["query"].strip(): len(r["hits"]) for r in hits_data["results"]}
    min_hits, max_hits = min(hit_counts.values()), max(hit_counts.values())
    if min_hits != top_k or max_hits != top_k:
        offenders = [f"{q[:36]}={n}" for q, n in hit_counts.items() if n != top_k][:3]
        raise SystemExit(
            f"[FAIL] 产物声明 topK={top_k}，但每题实际命中条数为 {min_hits}~{max_hits}，二者不一致。\n"
            f"       不一致样本：{'；'.join(offenders)}\n"
            f"       指标是在实际条数上算的，直接计算会得到「看起来正常但偏低」的数字。\n"
            f"       请修产物写出层（声明条数必须等于实际写入条数），不要用改这里的办法绕过。"
        )


def resolve_coverage(items: dict[str, dict], hits_data: dict, subset: bool) -> tuple[dict, dict, list[str]]:
    """把「产物里的题」与「金标集里的题」对齐，并生成**覆盖信息**（写进产物的 `subset` 节点）。

    返回 `(by_question, coverage, problems)`；`problems` 非空即失败（调用方逐条打印后返回 2）。

    ⛔ **`--subset` 只放松"缺席即失败"这一条**，别的底线一条都不放松：
      · 产物里出现金标集之外的题（不认识的 query）→ 失败（两种模式都失败）。
        这一条最要紧：若子集模式对它也放行，就等于允许"用别的问题顶替缺席的题"，
        指标会算在错误的题上而**毫无异常**。
      · 一题都没有 → 失败。
    覆盖信息必须**写全**（出分题数 / 金标题数 / 缺席题清单 / 缺席题按划分计数），
    因为"产物身份不能吃默认值"：只写一个 `subset: true` 的布尔量，
    下一个读产物的人仍会把 32 题的分母当成 40 题（本项目今天的教训）。
    """
    golden_by_question = {it["question"].strip(): iid for iid, it in items.items()}
    by_question: dict[str, dict] = {}
    for r in hits_data["results"]:
        q = r["query"].strip()
        if q in by_question:
            raise SystemExit(f"[FAIL] 命中文件里同一问题出现多次：{q}")
        by_question[q] = r

    present: list[str] = []
    missing: list[str] = []
    missing_problems: list[str] = []
    for iid, it in items.items():
        if it["question"].strip() in by_question:
            present.append(iid)
        else:
            missing.append(iid)
            missing_problems.append(f"缺少题目 {iid} 的检索结果")
    unknown_problems = [f"命中文件里有多余问题（不在数据集内）：{q[:40]}"
                        for q in by_question if q not in golden_by_question]

    problems = (unknown_problems if subset else missing_problems + unknown_problems)
    if problems:
        return by_question, {}, problems
    if not present:
        return by_question, {}, [
            f"产物里没有任何一条题的检索结果（金标集共 {len(items)} 题）——"
            f"子集出分允许缺席，但不允许一题都不出"
        ]

    splits = sorted({it["split"] for it in items.values()})
    missing_by_split = Counter(items[iid]["split"] for iid in missing)
    evaluated_by_split = Counter(items[iid]["split"] for iid in present)
    coverage = {
        "subset": bool(subset),
        "note": ("子集出分：只对产物里实际存在的题目出分；⛔ 本产物各指标的分母与全量不同，"
                 "不得与全量数字并列比较" if subset else
                 "全量出分：金标集全部题目到齐（缺席任何一题即失败）"),
        "goldenQueries": len(items),
        "evaluatedQueries": len(present),
        "missingQueryCount": len(missing),
        "missingQueries": missing,
        "missingBySplit": {s: missing_by_split.get(s, 0) for s in splits},
        "evaluatedBySplit": {s: evaluated_by_split.get(s, 0) for s in splits},
    }
    return by_question, coverage, []


def build_by_split(rows: list[dict], ks: tuple[int, ...], splits: list[str],
                   coverage: dict, items: dict[str, dict]) -> dict:
    """按划分出分。⛔ 某 split 的题**全部缺席**时，绝不产出任何指标键。

    为什么单独写这一条（2026-09-21 的教训）：子集出分时缺席的 split（例如 holdout）
    若被渲染成一张 `0.0000` 的表，读者会把它读成"该划分得了 0 分"，而真相是"没有数据"。
    **没有数据 ≠ 0 分**——这两者在本项目的报告里必须永远可区分。故缺席的 split 只写
    缺席信息（`splitAbsent` / `missingQueryCount` / `missingQueries` / `note`），
    不写任何 `HitRate@k` 之类的键：下游 `compare_runs.py` 对缺失键本就渲染成 `—`（不是 0）。
    """
    out: dict = {}
    for s in splits:
        sub = [r for r in rows if r["split"] == s]
        if sub:
            out[s] = group_metrics(sub, ks)
            continue
        out[s] = {
            "queryCount": 0,
            "splitAbsent": True,
            "missingQueryCount": coverage["missingBySplit"].get(s, 0),
            "missingQueries": [iid for iid in coverage["missingQueries"]
                               if items[iid]["split"] == s],
            "note": f"{s} 划分的题在本次产物里全部缺席（子集出分），故不产出任何指标——这是「没有数据」，不是「0 分」",
        }
    return out


def rank_of_first_hit(hits: list[dict], gold: set[str]) -> int | None:
    for h in hits:
        if h["chunkId"] in gold:
            return int(h["rank"])
    return None


def group_metrics(rows: list[dict], ks: tuple[int, ...]) -> dict:
    """rows: [{'gold': set, 'accept': set, 'same_article': set, 'hits': [...]}]，返回该组全部指标。

    ⚠️ 口径纪律（2026-09-21 起，数据集 v2）：
      · HitRate@k / Recall@k / Precision@k / Precision@m / MRR@k **定义一字未改**，一律只对 `gold` 计分
        —— 严格口径，读作**下界**（语料存在跨文档复述，另有同样正确的块，见 accept）。
      · `answerableRate@k` / `answerableTop1` 是**新增指标**，对 `gold ∪ accept` 计分；
        与严格口径之差 = **跨文档复述造成的低估幅度**。不要混用这两族数字。
      · `siblingExemptPrecision@k` 是**新增的对照口径**：「同一条款的正文块与表格块」属同一出处
        （切分产物），命中同 (doc_id, article_no) 的兄弟块不计为假正例。原 Precision@k 同时保留。
    """
    if not rows:
        return {"queryCount": 0}
    out: dict = {"queryCount": len(rows)}
    for k in ks:
        hit_rates, recalls, precisions, mrrs = [], [], [], []
        ans_hits, sib_precisions = [], []
        for r in rows:
            topk = [h["chunkId"] for h in r["hits"][:k]]
            m = len(r["gold"])
            inter = len(set(topk) & r["gold"])
            hit_rates.append(1.0 if inter > 0 else 0.0)
            recalls.append(inter / m)
            precisions.append(inter / k)
            rank = rank_of_first_hit(r["hits"][:k], r["gold"])
            mrrs.append(1.0 / rank if rank else 0.0)
            ans = r["gold"] | r.get("accept", set())
            ans_hits.append(1.0 if set(topk) & ans else 0.0)
            lenient = ans | {c for c in r.get("same_article", set())}
            sib_precisions.append(len(set(topk) & lenient) / k)
        out[f"HitRate@{k}"] = round(statistics.fmean(hit_rates), 6)
        out[f"Recall@{k}"] = round(statistics.fmean(recalls), 6)
        out[f"Precision@{k}"] = round(statistics.fmean(precisions), 6)
        out[f"MRR@{k}"] = round(statistics.fmean(mrrs), 6)
        out[f"answerableRate@{k}"] = round(statistics.fmean(ans_hits), 6)
        out[f"siblingExemptPrecision@{k}"] = round(statistics.fmean(sib_precisions), 6)
    # R-Precision（k = 该题 |G|）
    out["Precision@m"] = round(statistics.fmean(
        len(set(h["chunkId"] for h in r["hits"][:len(r["gold"])]) & r["gold"]) / len(r["gold"])
        for r in rows), 6)
    # 整集完整命中率：gold 全部被召回（m=1 时等于 HitRate@topK）
    out["allGoldRecalledAtTopK"] = round(statistics.fmean(
        1.0 if r["gold"] <= {h["chunkId"] for h in r["hits"]} else 0.0 for r in rows), 6)
    # 可答 Top-1：榜首落在 gold ∪ accept 内（等价正确块被排到第一也算对）
    out["answerableTop1"] = round(statistics.fmean(
        1.0 if r["hits"] and r["hits"][0]["chunkId"] in (r["gold"] | r.get("accept", set())) else 0.0
        for r in rows), 6)
    return out


def topk_absent_note(ks: tuple[int, ...], top_k: int) -> str | None:
    """`--k` 不含产物 `topK` 时给出那句说明；含则返回 None（此时一个字都不多说）。"""
    if top_k in ks:
        return None
    return f"MRR@{top_k} 未出：--k 未包含 {top_k}（MRR@k 依赖 k，不同 k 不可比）"


def topk_metric_cell(group: dict, prefix: str, top_k: int, width: int = 8) -> str:
    """取**锚在产物 `topK` 上**的单元格（`MRR@{topK}` / `HitRate@{topK}` …）。

    ⛔ 该键不存在时（`--k` 未含 `topK`）返回占位 `—`：
      · 不返回别的 k 的值——那是**换口径**，而 MRR / HitRate 都依赖 k，不同 k 不可比；
      · 不返回 `0`——那会被读成"得了 0 分"，而真相是"这一档没算"。
    渲染成 `—` 也与下游 `compare_runs.py` 对缺失键的处理一致（它同样渲染 `—`）。
    """
    key = f"{prefix}@{top_k}"
    if key not in group:
        return f"{'—':>{width}}"
    return f"{group[key]:>{width}.3f}"


def hitrate_row(name: str, group: dict, ks: tuple[int, ...], top_k: int) -> str:
    """`HitRate@k` 表的一行（`MRR@topK` 那列锚在产物 topK 上，见 topk_metric_cell）。"""
    cells = "".join(f"{group[f'HitRate@{k}']:>10.3f}" for k in ks)
    return (f"  {name:<12}{cells}  |  {group['Precision@m']:>10.3f}  "
            f"{topk_metric_cell(group, 'MRR', top_k)}")


def split_row(split: str, group: dict, ks: tuple[int, ...], top_k: int) -> str:
    """按划分表的一行。⚠️ 只用于题数非零的划分；缺席划分由调用方另行渲染。"""
    cells = "".join(f"{group[f'HitRate@{k}']:>10.3f}" for k in ks)
    return (f"  {split:<12}{cells}  |  {group['Precision@m']:>10.3f}  "
            f"{topk_metric_cell(group, 'MRR', top_k)}   (n={group['queryCount']})")


def answerable_block(results: dict, ks: tuple[int, ...], top_k: int, all_label: str,
                     accept_count: int) -> list[str]:
    """「严格口径 vs 可答口径」对照块。**整块锚在产物 `topK` 上**，故 `--k` 不含 `topK` 时整块不出。

    为什么整块都要退：这一块比的是同一档 k 下"只算 gold"与"含等价块"两条口径，
    而 `HitRate@topK` / `answerableRate@topK` / `siblingExemptPrecision@topK` 全部依赖 topK。
    换一个 k 去凑，等于**悄悄换了比较的档位**——两个数的差（可答率溢价）会跟着变，
    读的人却以为看的还是原来那一档。故宁可整块不显示，也不给一个不同档的数字。
    `answerableTop1` 是唯一的非 k 指标，故它在退场时仍单独给出。
    """
    ga, excl = results["all"], results["exclKnownLimitation"]
    if top_k not in ks:
        return [
            f"⚠️ 严格口径 vs 可答口径对照块**本次不出**：它锚在产物 topK={top_k} 上，"
            f"而 --k 未包含 {top_k}。",
            f"  ⛔ 不拿别的 k 顶替（HitRate / answerableRate / siblingExemptPrecision 都依赖 k，"
            f"不同档的数字不可比）。",
            f"  需要该块时把 {top_k} 加进 --k，例如：--k "
            f"{','.join(str(k) for k in sorted(set(ks) | {top_k}))}",
            f"  answerableTop1（唯一不依赖 k 的指标）= {ga['answerableTop1']:.3f}",
        ]
    gap = ga[f"answerableRate@{top_k}"] - ga[f"HitRate@{top_k}"]
    lines = [f"⚠️ 严格口径（只算 gold，读作**下界**）vs 可答口径（含等价块）——两族数字不得混用：",
             f"  {'指标':<28}{all_label:>12}{'剔除自报缺陷题':>16}"]
    for key in (f"HitRate@{top_k}", f"answerableRate@{top_k}", "answerableTop1"):
        lines.append(f"  {key:<28}{ga[key]:>12.3f}{excl[key]:>16.3f}")
    lines.append(f"  {f'siblingExemptPrecision@{top_k}':<28}"
                 f"{ga[f'siblingExemptPrecision@{top_k}']:>12.3f}"
                 f"{excl[f'siblingExemptPrecision@{top_k}']:>16.3f}")
    lines.append(f"  → 可答率比严格命中率高出 {gap:+.3f}（= 语料跨文档复述造成的低估幅度，"
                 f"{accept_count} 条题带等价块）")
    return lines


def selftest_subset() -> list[str]:
    """用**合成输入**校验「子集出分」这条新路径：不连库、不调模型、不读磁盘。

    为什么必须自检这条路径：`--subset` 的全部风险都在"它放松了什么"上。
    子集允许缺席，但一旦放松过头（例如把"不认识的题"也放行），
    指标就会算在别的问题上、**数值照常好看且不报错**——正是本项目最怕的静默错数族。
    故这里逐个用例钉死：默认模式下缺席必须失败；子集模式下只放松缺席这一条。

    合成金标集 5 题：dev 3 题（D1/D2/D3）+ holdout 2 题（H1/H2），gold 各 1 块、均在 rank 1。
    """
    bad: list[str] = []
    spec = [("D1", "dev", "合成问题一"), ("D2", "dev", "合成问题二"), ("D3", "dev", "合成问题三"),
            ("H1", "holdout", "合成问题四"), ("H2", "holdout", "合成问题五")]
    items = {iid: {"id": iid, "question": q, "split": sp, "questionType": "T1-合成",
                   "topicClass": "A", "goldChunkIds": [f"g{iid}"]}
             for iid, sp, q in spec}
    dev_ids = ["D1", "D2", "D3"]
    holdout_ids = ["H1", "H2"]
    ks = (1, 3)
    splits = ["dev", "holdout"]

    def hits_for(ids: list[str], extra: list[str] | None = None, top_k: int = 3) -> dict:
        results = []
        for iid in list(ids):
            hh = [{"rank": 1, "chunkId": f"g{iid}", "score": 1.0}]
            hh += [{"rank": i, "chunkId": f"x{iid}-{i}", "score": 0.0} for i in range(2, top_k + 1)]
            results.append({"query": items[iid]["question"], "hits": hh})
        for q in (extra or []):
            results.append({"query": q, "hits": [{"rank": i, "chunkId": f"z{i}", "score": 0.0}
                                                 for i in range(1, top_k + 1)]})
        return {"channel": "synthetic", "topK": top_k, "queryCount": len(results), "results": results}

    def rows_from(by_question: dict) -> list[dict]:
        rows = []
        for iid, it in items.items():
            q = it["question"].strip()
            if q in by_question:
                rows.append({"id": iid, "question": it["question"], "gold": set(it["goldChunkIds"]),
                             "accept": set(), "same_article": set(), "hits": by_question[q]["hits"],
                             "split": it["split"], "questionType": it["questionType"],
                             "topicClass": it["topicClass"], "flagged": False})
        return rows

    def expect_fail(fn, *a, **kw) -> str:
        """必须失败（SystemExit 或返回非空 problems）；失败信息里须含 want 片段。"""
        try:
            got = fn(*a, **kw)
        except SystemExit as e:
            return str(e)
        if isinstance(got, tuple) and len(got) == 3 and got[2]:
            return "；".join(got[2])
        return ""

    # ── 用例①：全量产物 + 不加开关 → 必须通过，且覆盖信息如实写明是全量 ──
    ok1, cov1, pr1 = resolve_coverage(items, hits_for(dev_ids + holdout_ids), subset=False)
    if pr1:
        bad.append(f"[用例①全量+不加开关] 期望通过，实际失败：{pr1}")
    elif cov1["subset"] is not False or cov1["evaluatedQueries"] != 5 or cov1["missingQueries"] != []:
        bad.append(f"[用例①全量+不加开关] 覆盖信息不对：{cov1}")
    else:
        rows1 = rows_from(ok1)
        g1 = group_metrics(rows1, ks)
        if abs(g1["HitRate@1"] - 1.0) > 1e-9 or g1["queryCount"] != 5:
            bad.append(f"[用例①全量+不加开关] 指标不对：{g1.get('HitRate@1')} / {g1.get('queryCount')}")

    # ── 用例②：dev-only 产物 + 不加开关 → **必须失败**（默认行为一字不变）──
    msg2 = expect_fail(resolve_coverage, items, hits_for(dev_ids), False)
    if "缺少题目 H1" not in msg2 or "缺少题目 H2" not in msg2:
        bad.append(f"[用例②dev-only+不加开关] 期望因缺席而失败，实际：{msg2 or '没有失败'}")

    # ── 用例③：dev-only 产物 + 加开关 → 必须通过，且产物里的覆盖信息如实 ──
    ok3, cov3, pr3 = resolve_coverage(items, hits_for(dev_ids), subset=True)
    if pr3:
        bad.append(f"[用例③dev-only+加开关] 期望通过，实际失败：{pr3}")
    else:
        # 产物身份不能吃默认值：这里断言的是**逐字节写进 JSON 的那个节点**
        node = json.loads(json.dumps(cov3, ensure_ascii=False, sort_keys=True))
        want = {"subset": True, "evaluatedQueries": 3, "goldenQueries": 5,
                "missingQueries": holdout_ids, "missingBySplit": {"dev": 0, "holdout": 2}}
        for key, w in want.items():
            if node.get(key) != w:
                bad.append(f"[用例③dev-only+加开关] 产物 subset.{key} 期望 {w}，实际 {node.get(key)}")
        if node.get("evaluatedBySplit") != {"dev": 3, "holdout": 0}:
            bad.append(f"[用例③dev-only+加开关] evaluatedBySplit 不对：{node.get('evaluatedBySplit')}")
        by_split = build_by_split(rows_from(ok3), ks, splits, cov3, items)
        hd = by_split.get("holdout", {})
        # ⛔ 缺席的 split 不得出现任何指标键（"没有数据"绝不能被渲染成 0.0000）
        if not hd.get("splitAbsent") or any(k.startswith(("HitRate@", "Recall@", "MRR@", "Precision@"))
                                            for k in hd):
            bad.append(f"[用例③dev-only+加开关] bySplit.holdout 未如实标注缺席，且疑似产出指标：{hd}")
        elif hd.get("missingQueryCount") != 2 or hd.get("missingQueries") != holdout_ids:
            bad.append(f"[用例③dev-only+加开关] bySplit.holdout 缺席信息不对：{hd}")
        if by_split.get("dev", {}).get("queryCount") != 3:
            bad.append(f"[用例③dev-only+加开关] bySplit.dev.queryCount 期望 3，实际 "
                       f"{by_split.get('dev', {}).get('queryCount')}")

    # ── 用例④：子集模式 + 混入不认识的 query（且同时有缺席）→ **必须失败** ──
    msg4 = expect_fail(resolve_coverage, items, hits_for(dev_ids, ["金标集之外的问题"]), True)
    if "多余问题" not in msg4:
        bad.append(f"[用例④子集+不认识的题] 期望因不认识的题而失败，实际：{msg4 or '没有失败'}")

    # ── 用例⑤：子集模式 + 空产物 → **必须失败** ──
    empty = {"channel": "synthetic", "topK": 3, "queryCount": 0, "results": []}
    msg5 = expect_fail(check_declared_hit_counts, empty)
    if "0 条检索结果" not in msg5:
        bad.append(f"[用例⑤子集+空产物] 条数守卫未拦下空产物，实际：{msg5 or '没有失败'}")
    msg5b = expect_fail(resolve_coverage, items, empty, True)
    if "没有任何一条题" not in msg5b:
        bad.append(f"[用例⑤子集+空产物] 对齐层未拦下空产物，实际：{msg5b or '没有失败'}")

    # ── 用例⑥（附加）：子集模式 + 声明条数 ≠ 实际条数 → 既有守卫必须照旧失败 ──
    skewed = hits_for(dev_ids)
    skewed["results"][0]["hits"] = skewed["results"][0]["hits"][:2]   # 声明 3 条、实际 2 条
    msg6 = expect_fail(check_declared_hit_counts, skewed)
    if "声明 topK=3" not in msg6 or "不一致" not in msg6:
        bad.append(f"[用例⑥子集+声明≠实际] 既有守卫未触发，实际：{msg6 or '没有失败'}")

    print("子集出分（--subset）自检（合成 5 题：dev 3 / holdout 2，不连库不调模型）：")
    print(f"  ①全量产物+不加开关        → {'通过' if not any('用例①' in b for b in bad) else '失败'}"
          f"（subset={cov1.get('subset')}，出分 {cov1.get('evaluatedQueries')}/{cov1.get('goldenQueries')}）")
    print(f"  ②dev-only+不加开关        → {'[FAIL] 如期失败' if not any('用例②' in b for b in bad) else '未失败'}")
    print(f"  ③dev-only+加开关          → {'通过' if not any('用例③' in b for b in bad) else '失败'}"
          f"（missingBySplit={cov3.get('missingBySplit')}，missingQueries={cov3.get('missingQueries')}）")
    print(f"  ④子集+不认识的题          → {'[FAIL] 如期失败' if not any('用例④' in b for b in bad) else '未失败'}")
    print(f"  ⑤子集+空产物              → {'[FAIL] 如期失败' if not any('用例⑤' in b for b in bad) else '未失败'}")
    print(f"  ⑥子集+声明≠实际           → {'[FAIL] 如期失败' if not any('用例⑥' in b for b in bad) else '未失败'}")
    return bad


def selftest_k_set() -> list[str]:
    """校验「`--k` 不含产物 `topK`」这条路径：**不抛异常**、缺的格子是 `—`、说明文案到位。

    为什么必须自检：这条路径原先会抛 `KeyError: 'MRR@30'`——渲染层直接取 `MRR@{topK}`，
    而**只有 `--k` 恰好不含 topK 时才触发**。真实触发组合：`topK=30` 的产物配
    `--k 1,3,5,8,10`（本次真实工作场景）；同一组 `--k` 配 `topK=10` 的产物却完全正常。
    这类"换个参数组合就崩"的缺陷，常规跑法（`--k` 总是包含 topK）永远发现不了，
    只能靠显式用例钉住——而且要**断言它不再抛**，否则修完仍然只靠人肉试。

    另两层必须同时钉死，免得"修好崩溃"顺手把口径改坏：
      · `--k` 里已有的档照旧出数（断言 `MRR@10` 仍是原值，没有被换成别的数）；
      · `--k` 含 topK 时**不留任何多余说明**（`topk_absent_note` 返回 None），保证既有输出逐字节不变。
    """
    bad: list[str] = []
    ks_no_topk = (1, 3, 5, 8, 10)
    ks_with_topk = (1, 3, 5, 8, 10, 20, 30)
    top_k = 30

    def row(rank: int | None) -> dict:
        hits = [{"rank": i, "chunkId": f"c{i:03d}", "score": 0.0} for i in range(1, 31)]
        if rank is not None:
            hits[rank - 1]["chunkId"] = "c999"
        return {"gold": {"c999"}, "accept": set(), "same_article": set(), "hits": hits}

    rows = [row(1), row(5), row(30)]
    ga = group_metrics(rows, ks_no_topk)

    # 前提：这份合成指标里确实没有 MRR@30（否则本用例测的是个不存在的问题）
    if "MRR@30" in ga:
        bad.append(f"[用例⑦k不含topK] 前提不成立：合成指标里竟然有 MRR@30 → {sorted(ga)}")
    # 旧写法（直接取键）必须真的会抛 —— 这条断言是"这个 bug 真实存在"的存证
    try:
        _ = ga["MRR@30"]
        bad.append("[用例⑦k不含topK] 旧写法竟然没抛 KeyError：说明用例已失去意义")
    except KeyError:
        pass

    # ⑴ 新写法不再抛，且渲染成占位符 —（不是 0、也不是别的 k 的值）
    try:
        cell = topk_metric_cell(ga, "MRR", top_k)
    except Exception as e:                                   # noqa: BLE001 - 自检要报出任何异常
        bad.append(f"[用例⑦k不含topK] topk_metric_cell 仍然抛异常：{type(e).__name__}: {e}")
        cell = ""
    if cell != f"{'—':>8}":
        bad.append(f"[用例⑦k不含topK] 缺档单元格期望占位 —，实际 {cell!r}")
    # ⑵ 已有的档照旧出数（没被换口径）
    try:
        cell10 = topk_metric_cell(ga, "MRR", 10)
    except Exception as e:                                   # noqa: BLE001
        bad.append(f"[用例⑦k不含topK] MRR@10 取值抛异常：{type(e).__name__}: {e}")
        cell10 = ""
    if cell10 != f"{ga['MRR@10']:>8.3f}":
        bad.append(f"[用例⑦k不含topK] MRR@10 应原样输出，实际 {cell10!r} vs {ga['MRR@10']!r}")
    # ⑶ 说明文案：缺档时给全，含档时一个字都不多说
    want_note = f"MRR@{top_k} 未出：--k 未包含 {top_k}（MRR@k 依赖 k，不同 k 不可比）"
    if topk_absent_note(ks_no_topk, top_k) != want_note:
        bad.append(f"[用例⑦k不含topK] 说明文案不对：{topk_absent_note(ks_no_topk, top_k)!r}")
    if topk_absent_note(ks_with_topk, top_k) is not None:
        bad.append("[用例⑦k不含topK] --k 含 topK 时不该有任何缺档说明（会改变既有输出）")
    # ⑷ 整块对照表（原先在 gap 那一行就 KeyError）也必须不抛，并改为说明 + 非 k 指标
    ga30 = group_metrics(rows, ks_with_topk)
    block_absent = answerable_block({"all": ga, "exclKnownLimitation": ga},
                                    ks_no_topk, top_k, "全部 3 条", 0)
    if not block_absent or "本次不出" not in block_absent[0]:
        bad.append(f"[用例⑦k不含topK] 对照块退场说明缺失：{block_absent}")
    if not any("answerableTop1" in line for line in block_absent):
        bad.append("[用例⑦k不含topK] 对照块退场时应保留 answerableTop1（不依赖 k）")
    block_present = answerable_block({"all": ga30, "exclKnownLimitation": ga30},
                                     ks_with_topk, top_k, "全部 3 条", 0)
    if "本次不出" in "".join(block_present) or not any(
            f"HitRate@{top_k}" in line for line in block_present):
        bad.append(f"[用例⑦k不含topK] --k 含 topK 时对照块应照常出：{block_present}")
    # ⑸ 行渲染器本身不抛（两处原先直接取 MRR@topK 的打印点）
    for fn, args in ((hitrate_row, ("全部 3 条", ga, ks_no_topk, top_k)),
                     (split_row, ("dev", ga, ks_no_topk, top_k))):
        try:
            line = fn(*args)
        except Exception as e:                               # noqa: BLE001
            bad.append(f"[用例⑦k不含topK] {fn.__name__} 仍然抛异常：{type(e).__name__}: {e}")
            continue
        if "—" not in line:
            bad.append(f"[用例⑦k不含topK] {fn.__name__} 应渲染占位 —：{line!r}")

    print("k 档未覆盖产物 topK 的自检（合成 3 题，不连库不调模型；真实组合 topK=30 + --k 1,3,5,8,10）：")
    print(f"  ⑦旧写法 ga['MRR@30']      → 确实抛 KeyError（复现原 bug，证明用例有效）")
    print(f"    新写法 MRR@30 单元格     → {cell!r}（占位 —，不换口径、不写 0）")
    print(f"    MRR@10（--k 内的档）     → {cell10.strip()}（原样输出）")
    print(f"    说明文案               → {topk_absent_note(ks_no_topk, top_k)}")
    print(f"    --k 含 topK 时          → 说明为 None（既有 40 题输出逐字节不变）")
    return bad


def selftest() -> int:
    """用手算期望值校验**指标实现本身**（不是校验数据集，也不是校验检索）。

    为什么值得写：指标算错**不会报错**，只会让"增益"看起来合理。本项目已经吃过
    "测的东西自己错了"的亏（见 `docs/工程复盘/诚实记录/`），所以度量工具也要有自证。
    构造 4 条题，gold 均为单个 chunk，命中排名分别为 1 / 5 / 30 / 未命中：

        HitRate@1  = 1/4 = 0.25      HitRate@5  = 2/4 = 0.5      HitRate@30 = 3/4 = 0.75
        Precision@1 = 0.25           Precision@5 = (1/5+1/5)/4 = 0.1
        Precision@30 = 3/(30*4) = 0.025
        MRR@30 = (1 + 1/5 + 1/30 + 0)/4 = 0.308333…
        Precision@m（m=1） = 0.25
    """
    def row(rank: int | None) -> dict:
        hits = [{"rank": i, "chunkId": f"c{i:03d}", "score": 0.0} for i in range(1, 31)]
        gold = {"c999"}          # 默认不出现 = 未命中
        if rank is not None:
            hits[rank - 1]["chunkId"] = "c999"
            gold = {"c999"}
        return {"gold": gold, "accept": set(), "same_article": set(), "hits": hits}

    rows = [row(1), row(5), row(30), row(None)]
    g = group_metrics(rows, (1, 5, 30))
    expect = {
        "HitRate@1": 0.25, "HitRate@5": 0.5, "HitRate@30": 0.75,
        "Recall@1": 0.25, "Recall@5": 0.5, "Recall@30": 0.75,
        "Precision@1": 0.25, "Precision@5": 0.1, "Precision@30": 0.025,
        "MRR@30": round((1 + 1 / 5 + 1 / 30 + 0) / 4, 6),
        "Precision@m": 0.25,
        "allGoldRecalledAtTopK": 0.75,
        # accept 为空时，新增口径必须**退化**回严格口径（这是它"没改定义"的证明）
        "answerableRate@30": 0.75,
        "answerableTop1": 0.25,
        "siblingExemptPrecision@30": 0.025,
    }
    bad = []
    for key, want in expect.items():
        got = g.get(key)
        if got is None or abs(got - want) > 1e-9:
            bad.append(f"{key}: 期望 {want}，实际 {got}")

    # ⑵ 新增口径的**独立手算**自证（accept / 同条兄弟块真的被算进了不同的数）
    #    单条题：gold g1 全程未命中；accept d002 在第 2 位；同条兄弟 d003 在第 3 位。
    #      严格口径：HitRate@30 = 0、MRR@30 = 0、Precision@30 = 0
    #      可答口径：answerableRate@30 = 1（accept 命中）、answerableTop1 = 0（榜首非可答）
    #      同条豁免：Precision@30 = 2/30（accept d002 与兄弟 d003 都算对）
    rows2 = [{
        "gold": {"g1"},
        "accept": {"d002"},
        "same_article": {"g1", "d003"},
        "hits": [{"rank": i, "chunkId": f"d{i:03d}", "score": 0.0} for i in range(1, 31)],
    }]
    g2 = group_metrics(rows2, (30,))
    expect2 = {
        "HitRate@30": 0.0, "MRR@30": 0.0, "Precision@30": 0.0, "Recall@30": 0.0,
        "answerableRate@30": 1.0, "answerableTop1": 0.0,
        "siblingExemptPrecision@30": round(2 / 30, 6),
    }
    for key, want in expect2.items():
        got = g2.get(key)
        if got is None or abs(got - want) > 1e-9:
            bad.append(f"[可答/同条口径] {key}: 期望 {want}，实际 {got}")

    # ⑶ 同条豁免必须**真的抬高** Precision（而不是恒等）
    rows3 = [{
        "gold": {"e001"}, "accept": set(), "same_article": {"e001", "e002"},
        "hits": [{"rank": 1, "chunkId": "e001", "score": 0.0},
                 {"rank": 2, "chunkId": "e002", "score": 0.0},
                 {"rank": 3, "chunkId": "e003", "score": 0.0}],
    }]
    g3 = group_metrics(rows3, (2,))
    if abs(g3["Precision@2"] - 0.5) > 1e-9 or abs(g3["siblingExemptPrecision@2"] - 1.0) > 1e-9:
        bad.append(f"[同条豁免] 期望 Precision@2=0.5 / siblingExempt=1.0，实际 "
                   f"{g3['Precision@2']} / {g3['siblingExemptPrecision@2']}")

    print("指标实现自检（4 条构造题，命中排名 1/5/30/未命中）+ 新增口径独立手算：")
    for key in sorted(expect):
        print(f"  {key:<26} {g[key]:.6f}")
    for key in sorted(expect2):
        print(f"  [可答/同条] {key:<18} {g2[key]:.6f}")
    print(f"  [同条豁免] Precision@2 = {g3['Precision@2']:.6f} → "
          f"siblingExemptPrecision@2 = {g3['siblingExemptPrecision@2']:.6f}")

    bad += selftest_subset()
    bad += selftest_k_set()
    if bad:
        for b in bad:
            print(f"  [错误] {b}")
        print("[FAIL] 指标实现自检未通过")
        return 1
    print("[PASS] 指标实现自检通过（与手算期望值一致）")
    print("[PASS] 子集出分（--subset）自检通过：默认模式缺席即失败；子集模式只放松「缺席即失败」"
          "这一条，不认识的题 / 声明条数≠实际条数 / 空产物 三条底线仍在")
    print("[PASS] k 档未覆盖产物 topK 的自检通过：不再抛 KeyError；缺的档渲染成 — 并写明原因"
          "（不拿别的 k 顶替、不写 0）；--k 含 topK 时不留任何多余输出")
    return 0


def emit_queries(dataset: dict, path: Path) -> int:
    """把数据集里的问句按顺序写成"一行一问"的查询文件，供 Java CLI 的 `--query-file` 使用。

    为什么要由脚本生成而不是手工维护：**两份文件一旦各写一遍就会漂移**
    （改题面忘了改查询文件 → 检索跑的还是旧问题 → 指标看着正常却对不上题）。
    写成推导产物就不存在"不同步"这种状态。
    """
    questions = [it["question"].strip() for it in dataset["items"]]
    for q in questions:
        if "\n" in q or "\r" in q or not q:
            raise SystemExit(f"[FAIL] 题面不能含换行或为空（会破坏一行一问的格式）：{q!r}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(("\n".join(questions) + "\n").encode("utf-8"))
    print(f"已写出查询文件：{path}（{len(questions)} 行，UTF-8 无 BOM / 仅 LF）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Golden Set L2 检索指标计算（纯规则口径）")
    ap.add_argument("--dataset", default=str(DEFAULT_DATASET))
    ap.add_argument("--hits", default=None, help="VectorQueryCli --out 产出的命中 JSON")
    ap.add_argument("--k", default=",".join(str(k) for k in DEFAULT_KS),
                    help="截断位，逗号分隔。⚠️ --k 不含产物 topK 时 MRR@topK 不出：只对已有的档出 MRR@k，"
                         "那一格渲染成 — 并在 stdout 写明原因（⛔ 不拿别的 k 顶替、不写 0）；"
                         "锚在 topK 上的「严格 vs 可答」对照块整块不出。"
                         "需要时把 topK 加进 --k，例如 --k 1,3,5,8,10,20,30")
    ap.add_argument("--json", dest="json_out", default=None, help="确定性汇总输出路径")
    ap.add_argument("--label", default="vector-baseline", help="本次运行的标签（写进产物）")
    ap.add_argument("--psql", default=str(DEFAULT_PSQL))
    ap.add_argument("--subset", action="store_true",
                    help="子集出分：只对产物里【实际存在】的题目出分，不因缺席而失败（默认关闭时缺席任一题仍 [FAIL]）。"
                         "① 用途：调参只许用开发集（dev），本开关让「只用 dev 产物出分」成为被工具支持的正规操作，"
                         "不必为了出分而跑全 40 题、顺手看保留集；"
                         "② ⛔ 子集出分的数字不得与全量数字并列比较——分母不同（如 32 vs 40），并列即误读，"
                         "本项目已吃过「旁证一样、结论不同」的教训。")
    ap.add_argument("--selftest", action="store_true", help="只跑指标实现自检，不读数据集")
    ap.add_argument("--emit-queries", default=None,
                    help="只把题面导出成一行一问的查询文件（供检索 CLI 用），不算指标")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if args.emit_queries:
        ds = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
        return emit_queries(ds, Path(args.emit_queries))
    if not args.hits:
        print("[FAIL] 必须给 --hits（或用 --selftest / --emit-queries）")
        return 2

    ks = tuple(sorted({int(x) for x in args.k.split(",") if x.strip()}))
    ds_path, hits_path = Path(args.dataset), Path(args.hits)
    dataset = json.loads(ds_path.read_text(encoding="utf-8"))
    items = {it["id"]: it for it in dataset["items"]}
    hits_data = load_hits(hits_path)
    top_k = int(hits_data["topK"])
    if max(ks) > top_k:
        raise SystemExit(f"[FAIL] 请求的 k={max(ks)} 超过命中文件的 topK={top_k}，无法计算")

    # ---------- 声明条数 vs 实际条数：必须一致（否则指标是静默错的）----------
    # 语义与措辞见 check_declared_hit_counts()；⚠️ 子集模式（--subset）**不豁免**这一条。
    check_declared_hit_counts(hits_data)

    # ---------- 题目对齐与覆盖信息 ----------
    # 默认（不加 --subset）：必须一一对应，缺一不可（不做"部分算分"）。
    # 子集模式（--subset）：只对产物里**实际存在**的题出分，缺席不再算失败；
    #   ⛔ 但"产物里有金标集之外的题"仍必须失败，否则指标会算在别的问题上而毫无异常。
    by_question, coverage, problems = resolve_coverage(items, hits_data, args.subset)
    if problems:
        for p in problems[:10]:
            print(f"[FAIL] {p}")
        return 2
    missing_list = coverage["missingQueries"]
    splits = sorted({it["split"] for it in items.values()})

    # ---------- 同条分组（「同条豁免 Precision」口径用）----------
    # 为什么需要：语料把"正文条 + 内嵌表格"切成两个 chunk，两者属**同一条款**。
    # 按"最小充分集"只标表格块时，同条正文块在检索里与 gold 高度同分，
    # 会构成 Precision@k 的固定假正例分母（实测：BM25 探针 3 的 rank 1 是 gold TABLE、
    # rank 4 就是同条 BODY）。它是**切分产物**，不是检索错误，故单独给一个对照口径。
    env = load_env(ROOT)
    db = Db(Path(args.psql), env, "127.0.0.1", "5432", "canalmind", "postgres")
    art_rows = db.rows(
        "SELECT chunk_id, doc_id, coalesce(article_no, '') AS article_no FROM rag_chunk "
        "WHERE coalesce(article_no, '') <> ''",
        name="article_groups",
    )
    article_groups: dict[tuple[str, str], set[str]] = defaultdict(set)
    chunk_article: dict[str, tuple[str, str]] = {}
    for a in art_rows:
        key = (a["doc_id"], a["article_no"])
        article_groups[key].add(a["chunk_id"])
        chunk_article[a["chunk_id"]] = key

    rows = []
    for iid, it in sorted(items.items()):
        # 子集出分时，缺席的题没有命中结果可算，直接跳过（此时 coverage 已逐条记录了缺席题）
        r = by_question.get(it["question"].strip())
        if r is None:
            continue
        gold = set(it["goldChunkIds"])
        accept = set(it.get("acceptableChunkIds", []))
        sib: set[str] = set()
        for cid in gold | accept:
            key = chunk_article.get(cid)
            if key:
                sib |= article_groups[key]
        rows.append({
            "id": iid, "question": it["question"], "gold": gold, "accept": accept,
            "same_article": sib,
            "hits": r["hits"], "split": it["split"], "questionType": it["questionType"],
            "topicClass": it["topicClass"],
            # ⚠️ 这个标志只表示"数据集自己声明了该题有缺陷/限制"，**不代表缺陷性质**。
            # 2026-09-21 的教训：L2-034 原先被写成"已知实现限制"（检索取不到文号），
            # 而对抗式审计 + 独立查库证明**是 gold 标错**（文号在 4 个外部 chunk 里、
            # BM25 探针 4 的 rank 1–4 正是它们）。故此处刻意用中性名，
            # 别让"剔除后指标变好"被读成"排除了一项实现限制"。
            "flagged": bool(it.get("knownLimitation")),
        })

    # ---------- 总体与分组指标 ----------
    def subset(pred) -> list[dict]:
        return [r for r in rows if pred(r)]

    results: dict = {}
    results["all"] = group_metrics(rows, ks)
    # 键名保持历史拼写以免破坏既有引用，含义见上面注释：剔除"数据集自报有缺陷/限制"的题。
    results["exclKnownLimitation"] = group_metrics(subset(lambda r: not r["flagged"]), ks)
    results["exclFlaggedNote"] = ("剔除的是数据集里带 knownLimitation 字段的题；该字段只说明"
                                 "'本题有已知问题'，不区分是标注缺陷还是实现限制")
    # bySplit：子集出分时，缺席的 split（例如 holdout）只写缺席信息、不产出任何指标键
    # ——⛔ 绝不把"没有数据"渲染成 0.0000 那样看起来是"得了 0 分"的数（见 build_by_split 注释）。
    results["bySplit"] = build_by_split(rows, ks, splits, coverage, items)
    # 覆盖信息同时挂到 results 下（与顶层 summary["subset"] 是**同一个对象**，
    # 故不存在两处漂移；放在这里是为了让只读 results 的人也能一眼看出"这不是全量"）。
    results["subset"] = coverage
    results["byQuestionType"] = {t: group_metrics(subset(lambda r, t=t: r["questionType"] == t), ks)
                                 for t in sorted({r["questionType"] for r in rows})}
    results["byTopicClass"] = {c: group_metrics(subset(lambda r, c=c: r["topicClass"] == c), ks)
                               for c in sorted({r["topicClass"] for r in rows})}

    # ---------- 退化自证：m=1 时 Recall@k 是否恒等于 HitRate@k ----------
    m_values = sorted({len(r["gold"]) for r in rows})
    recall_equals_hit = all(
        results["all"][f"Recall@{k}"] == results["all"][f"HitRate@{k}"] for k in ks
    ) and m_values == [1]

    # ---------- badcase：topK 内 0 命中的题 ----------
    misses = []
    for r in rows:
        rank = rank_of_first_hit(r["hits"], r["gold"])
        if rank is None:
            misses.append({
                "id": r["id"], "question": r["question"], "split": r["split"],
                "questionType": r["questionType"], "topicClass": r["topicClass"],
                "goldChunkIds": sorted(r["gold"]),
                "top3": [{"rank": h["rank"], "chunkId": h["chunkId"], "score": h["score"]}
                         for h in r["hits"][:3]],
            })

    # ---------- 得分裕度：gold 赢得有多勉强 ----------
    # 为什么值得单独算：**"命中"与"稳稳命中"是两回事**。本项目已实测到两类近分现象：
    # ①BM25 侧 12 个条款**完全同分**（12.520430），真答案被挤到 rank 14；
    # ②本项实测某版本对照题里 2026 版与 2027 版费率表得分只差 0.0014（0.775314 vs 0.773919）。
    # 这类题在 `HitRate@k` 上可能"恰好命中"，但换一个问法、换一次模型就会翻转——
    # 只看命中率会把这种脆弱性完全掩盖掉。
    # ⚠️ 阈值 0.01 是**启发式**，只为把"值得人工看一眼的题"捞出来，不具备统计含义。
    NEAR_TIE = 0.01
    margins = []
    for r in rows:
        gold_scores = [h["score"] for h in r["hits"] if h["chunkId"] in r["gold"]]
        non_gold = [h["score"] for h in r["hits"] if h["chunkId"] not in r["gold"]]
        gold_score = max(gold_scores) if gold_scores else None
        best_non_gold = max(non_gold) if non_gold else None
        margin = (gold_score - best_non_gold) if (gold_score is not None and best_non_gold is not None) else None
        top2 = [h["score"] for h in r["hits"][:2]]
        margins.append({
            "id": r["id"], "goldRank": rank_of_first_hit(r["hits"], r["gold"]),
            "goldScore": gold_score, "bestNonGoldScore": best_non_gold,
            "margin": margin,
            "top1Margin": (top2[0] - top2[1]) if len(top2) == 2 else None,
            "nearTie": (margin is not None and abs(margin) < NEAR_TIE),
        })
    near_tie_items = [m for m in margins if m["nearTie"]]
    known_margins = [m["margin"] for m in margins if m["margin"] is not None]
    rank_hist = Counter(str(rank_of_first_hit(r["hits"], r["gold"]) or "未命中") for r in rows)

    # ---------- 占位兄弟分析：正文条里的 [TABLE:id] 占位块 vs 表格本体 ----------
    # 语料把"正文条 + 内嵌表格"切成两个 chunk：BODY 只留 [TABLE:<id>] 占位标记，
    # 数字在 TABLE chunk 里。若检索把占位 BODY 排在 TABLE 本体之前，下游拿到的正文
    # 是"…如下表所示"却没有表——这是本项目特有的、值得单独看的失败模式。
    # （env / db 已在上文「同条分组」处建立，此处复用，避免重复建连）
    all_gold = sorted({cid for r in rows for cid in r["gold"]})
    type_rows = db.rows(
        "SELECT chunk_id, chunk_type FROM rag_chunk WHERE chunk_id IN ("
        + ", ".join(f"'{c}'" for c in all_gold) + ")",
        name="gold_types",
    )
    gold_types = {t["chunk_id"]: t["chunk_type"] for t in type_rows}
    unknown_gold = [c for c in all_gold if c not in gold_types]
    if unknown_gold:
        print(f"[FAIL] gold chunk 在库里不存在：{unknown_gold[:5]}")
        return 2
    retrieved_ids = {h["chunkId"] for r in rows for h in r["hits"]}
    gold_table_ids = sorted({cid for r in rows for cid in r["gold"]
                             if gold_types.get(cid) == "TABLE" and cid in retrieved_ids})
    placeholder_rows = []
    if gold_table_ids:
        # ⚠️ 这里曾经写成 `", ".join(...)` 再用 `" OR content LIKE ".join(那个字符串)`——
        # 第二次 join 是在**字符串的字符**上做的，拼出一条 `LIKE % OR LIKE ' OR LIKE ,`
        # 的语法错误 SQL。合成命中空跑时立刻暴露（这就是"先空跑 I/O 路径"的价值）。
        # ⚠️ 匹配串必须以 `[TABLE:<id>` 开头即可，**不能**要求 id 后紧跟 `]`——
        # 真实标记带行列信息，写作 `[TABLE:<id> rows=N cols=M]`。
        # 第一版写成 `'%[TABLE:<id>]%'`，于是**一条都匹配不上**、占位分析静默为空；
        # 合成命中空跑时发现（这是空跑第二次抓到真 bug）。
        or_clauses = " OR ".join(f"content LIKE '%[TABLE:{cid}%'" for cid in gold_table_ids)
        got = db.rows(
            "SELECT chunk_id, doc_id, article_no, content FROM rag_chunk WHERE " + or_clauses,
            name="placeholder_siblings",
        )
        siblings: dict[str, list[dict]] = defaultdict(list)
        for row in got:
            for cid in gold_table_ids:
                if f"[TABLE:{cid}" in row["content"]:
                    siblings[cid].append({"chunkId": row["chunk_id"], "docId": row["doc_id"],
                                          "articleNo": row["article_no"]})

        for r in rows:
            for cid in sorted(r["gold"]):
                if cid not in siblings:
                    continue
                ranks = {h["chunkId"]: h["rank"] for h in r["hits"]}
                for sib in siblings[cid]:
                    placeholder_rows.append({
                        "itemId": r["id"], "goldTableChunkId": cid,
                        "goldTableRank": ranks.get(cid),
                        "placeholderChunkId": sib["chunkId"],
                        "placeholderRank": ranks.get(sib["chunkId"]),
                        "placeholderRankedHigher": (
                            ranks.get(sib["chunkId"]) is not None
                            and (ranks.get(cid) is None or ranks[sib["chunkId"]] < ranks[cid])
                        ),
                    })
    ph_higher = [p for p in placeholder_rows if p["placeholderRankedHigher"]]

    # ---------- 输出 ----------
    print("=" * 78)
    if args.subset:
        # ⚠️ 这一行必须在**最顶上**：产物身份不能吃默认值——本次不是全量出分，
        # 而下面的表格（含"全部 N 条"那一行）与全量出分的表长得几乎一样。
        print(f"⚠️⚠️ 本次为【子集出分】：只对产物里实际存在的 {len(rows)}/{len(items)} 题出分，"
              f"缺席 {len(missing_list)} 题不参与计算。")
        print("      ⛔ 本表各指标的分母与全量出分不同，**不得与全量数字并列比较**"
              "（本项目已吃过「旁证一样、结论不同」的教训）。")
        print("=" * 78)
    print(f"检索指标（纯规则口径）  标签={args.label}  通道={hits_data['channel']}")
    print(f"  数据集 {ds_path.name}（sha256 {sha256_file(ds_path)[:16]}…）")
    print(f"  命中文件 {hits_path.name}（sha256 {sha256_file(hits_path)[:16]}…）  topK={top_k}")
    if args.subset:
        print(f"  题目对齐：子集出分，产物 {len(rows)} 题 / 金标集 {len(items)} 题；"
              f"缺席 {len(missing_list)} 题；gold 规模 m 取值 {m_values}")
        if missing_list:
            print("  缺席题按划分：" + "；".join(
                f"{s} {coverage['missingBySplit'].get(s, 0)} 题" for s in splits))
            for s in splits:
                ids = [iid for iid in missing_list if items[iid]["split"] == s]
                if ids:
                    print(f"    {s:<10} {len(ids)} 题：{'、'.join(ids)}")
    else:
        print(f"  题目对齐：{len(rows)} 条全部一一对应；gold 规模 m 取值 {m_values}")
    print("-" * 78)
    hdr = "  组别".ljust(14) + "".join(f"{'@'+str(k):>10}" for k in ks) + "  |  Precision@m  MRR@topK"
    print("HitRate@k：" + hdr)
    # ⚠️ 组别名必须随实际题数走：子集出分时写死"全部 40 条"会让 32 题的表被当成 40 题的表读。
    all_label = f"全部 {len(rows)} 条" + ("（子集）" if args.subset else "")
    for name, g in [(all_label, results["all"]),
                    ("剔除数据集自报缺陷的题", results["exclKnownLimitation"])]:
        print(hitrate_row(name, g, ks, top_k))
    # ⚠️ `--k` 不含产物 topK 时，`MRR@topK` 那一格没有数据：此处明说原因，
    #    而**不是**拿别的 k 顶上、也不是写 0（见 topk_metric_cell / topk_absent_note）。
    topk_note = topk_absent_note(ks, top_k)
    if topk_note:
        print(f"  ⚠️ {topk_note}")
    print("-" * 78)
    for line in answerable_block(results, ks, top_k, all_label,
                                 sum(1 for r in rows if r["accept"])):
        print(line)
    print("-" * 78)
    print("按划分（⚠️ 保留集不参与调参，只用于最后确认）：")
    for s in splits:
        g = results["bySplit"][s]
        if g.get("splitAbsent"):
            # ⛔ 一个数字都不打印：缺席 ≠ 0 分。打印 0.000 会被读成"该划分得了 0 分"。
            print(f"  {s:<12}本次缺席 {g['missingQueryCount']} 题  "
                  f"（子集出分，该划分不产出任何指标——这是「没有数据」，不是「0 分」）")
            continue
        print(split_row(s, g, ks, top_k))
    print("-" * 78)
    print("按题型（HitRate@k）：")
    for t, g in results["byQuestionType"].items():
        cells = "".join(f"{g[f'HitRate@{k}']:>10.3f}" for k in ks)
        print(f"  {t:<12}{cells}  |  {g['Precision@m']:>10.3f}   (n={g['queryCount']})")
    print("按主题类（HitRate@k）：")
    for c, g in results["byTopicClass"].items():
        cells = "".join(f"{g[f'HitRate@{k}']:>10.3f}" for k in ks)
        print(f"  {c:<12}{cells}  |  {g['Precision@m']:>10.3f}   (n={g['queryCount']})")
    print("-" * 78)
    type_counts = Counter(gold_types[cid] for r in rows for cid in r["gold"])
    print(f"gold 落在表格块的题数：{type_counts.get('TABLE', 0)}/{len(rows)}"
          f"（BODY {type_counts.get('BODY', 0)}）——表格类答案需要跨 chunk 取数，"
          f"是本项目特有的检索难点")
    print(f"首个 gold 命中排名分布：{dict(sorted(rank_hist.items(), key=lambda kv: (kv[0] == '未命中', kv[0].zfill(3))))}")
    if misses:
        print(f"topK 内完全未命中 {len(misses)} 条：")
        for m in misses:
            print(f"  {m['id']} [{m['questionType']}/{m['topicClass']}/{m['split']}] {m['question']}")
    else:
        print(f"topK 内无完全未命中的题（{top_k} 位内均可召回 gold）")
    if placeholder_rows:
        print(f"占位兄弟分析：涉及 {len({p['itemId'] for p in placeholder_rows})} 条题，"
              f"其中占位 BODY 排在表格本体之前 {len(ph_higher)} 处")
        for p in ph_higher:
            print(f"  {p['itemId']}：占位 BODY rank={p['placeholderRank']} "
                  f"先于表格本体 rank={p['goldTableRank']}")
    print("-" * 78)
    if known_margins:
        print(f"得分裕度（gold 最高分 − 最强非 gold 分，>0 表示 gold 稳居第一）："
              f"最小 {min(known_margins):+.5f} / 中位 {statistics.median(known_margins):+.5f} / "
              f"最大 {max(known_margins):+.5f}")
        if near_tie_items:
            print(f"  近分并列题（|裕度| < {NEAR_TIE}，命中很脆弱）{len(near_tie_items)} 条：")
            for m in sorted(near_tie_items, key=lambda x: x["margin"]):
                print(f"    {m['id']} gold 排名 {m['goldRank']}，裕度 {m['margin']:+.5f}"
                      f"（gold {m['goldScore']:.6f} vs 非 gold 最高 {m['bestNonGoldScore']:.6f}）")
        else:
            print(f"  无近分并列题（阈值 {NEAR_TIE}）")
    print("-" * 78)
    if recall_equals_hit:
        print("⚠️ 退化自证：m≡1，故 Recall@k 与 HitRate@k 恒等（已实测确认，报告需说明）")
    else:
        print("Recall@k 与 HitRate@k 已分离（存在多 gold 题）")

    summary = {
        "label": args.label,
        "channel": hits_data["channel"],
        "dataset": ds_path.name,
        "datasetSha256": sha256_file(ds_path),
        "datasetVersion": dataset["datasetVersion"],
        "hitsFile": hits_path.name,
        "hitsFileSha256": sha256_file(hits_path),
        "topK": top_k,
        "kValues": list(ks),
        # 覆盖信息（与 results["subset"] 是**同一个对象**，不存在两处漂移）：
        # 出分题数 / 金标题数 / 缺席题清单 / 缺席题按划分计数。
        # ⛔ 这是产物身份的一部分，**不得靠默认值**：读产物的人必须先看到它，
        # 才知道这张表的数字能不能与全量数字并列比较（本项目今天的教训）。
        "subset": coverage,
        "metricDefinitions": METRIC_DEFINITIONS,
        "goldSizeValues": m_values,
        "goldChunkTypeCounts": dict(sorted(type_counts.items())),
        "recallEqualsHitRate": recall_equals_hit,
        "results": results,
        "rankOfFirstGoldHistogram": dict(rank_hist),
        "misses": misses,
        "scoreMarginAnalysis": {
            "nearTieThreshold": NEAR_TIE,
            "nearTieThresholdNote": "启发式阈值，只为把值得人工复核的脆弱命中捞出来，不具备统计含义",
            "nearTieCount": len(near_tie_items),
            "minMargin": min(known_margins) if known_margins else None,
            "medianMargin": statistics.median(known_margins) if known_margins else None,
            "maxMargin": max(known_margins) if known_margins else None,
            "detail": margins,
        },
        "placeholderSiblingAnalysis": {
            "itemsInvolved": len({p["itemId"] for p in placeholder_rows}),
            "placeholderRankedHigherCount": len(ph_higher),
            "detail": placeholder_rows,
        },
    }
    if args.json_out:
        out = Path(args.json_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes((json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
                        .encode("utf-8"))
        print(f"机读产物已写入：{out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
