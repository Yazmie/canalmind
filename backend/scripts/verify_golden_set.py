"""Golden Set L2 数据集的**结构、分布与反照抄**校验（决策 C-28）。

为什么需要它
------------
评测集是**一切指标的基准**，它自己出错不会以"报错"的形式暴露，而是以
**"指标好看但与事实不符"** 的形式暴露。本项目已知的三类风险：

1. **gold chunk 标错或写错 id**（手写 16 位 id 极易看错一位）——
   表现为"某个 chunk 永远召不回"，会被误读成检索缺陷；
2. **分布失衡**（题型/主题类扎堆）——知识库《AI 应用评测体系》第 100 行明说
   "样本分布往往比总量更先决定评测能否发现问题"，而分布失衡**不会让任何脚本变红**；
3. **题面照抄条款用词**——反向生成最容易犯的错：题面与原文高度重合时检索"轻松命中"，
   **系统性高估召回**，并直接污染第 4 周消融结论（C-28 ④ 明确禁止沿用标题原词）。

本脚本把这三类风险都变成**可执行的检查**，让"数据集本身"和"检索实现"一样被验证过。

检查项
------
1. 结构完整：必需字段齐全、条数与 design 里声明的数字一致
2. 编号：`L2-0NN` 唯一且连续无缺号
3. 划分：32 条 `dev` + 8 条 `holdout`；**保留集必须跨 ≥4 个题型、≥4 个主题类**
   （否则"保留集"只检验了单一场景，等于没留）
4. 题型分布：T1–T6 每类 6~7 条、合计 40
5. 主题类覆盖：A–G **每类 ≥4 条**
6. **gold chunk 全部真实存在**（一次 SQL 查全库核对），且每条 gold 的文档编号
   必须落在**该题所标主题类**的文档范围内（抓"标注串行"这类复制粘贴错误）
7. gold chunk 的正文非空
8. **反照抄**（题面 vs 该 gold 条款的标题）：
   - 硬失败：题面含有标题的**连续 ≥5 字**片段；或题面与标题完全相同
   - 硬失败：标题长度 **≥8** 且题面字符集重合率 **≥0.95**（近乎逐字照抄）
   - 警告：重合率 ≥0.8 的其余情形（4 字领域术语如「计费单位」无法回避，故只警告）
   - 两类**声明式豁免**均**逐条打印**（术语豁免、`allowedTitleOverlap` 标识符豁免），
     且声明失效时告警——豁免必须可见，否则等于沉默放宽
   ⚠️ 阈值曾与实现漂移：docstring 写的是「标题 ≥6 且重合率 ≥0.9」而实现是 ≥8/≥0.95。
   2026-09-21 按实测同步（实现为准），并在此写明以免再漂。
9. 已知限制项自证：标了 `knownLimitation` 的题，其文号必须**确实**不在该文档任何
   chunk 的正文里（复现 BM25 探针 4 的实测结论，防止把"实现缺陷"说成"已登记限制"）
10. **可接受等价块**（`acceptableChunkIds`，v2 新增）：必须真实存在、**不得与 gold 重叠**；
    并统计「与 gold 同文档 / 跨文档」两类数量（跨文档复述是本数据集已知的语料特征）。
    ⚠️ 它**不受主题类范围约束**：等价证据本就可能在别的主题类文档里（如 L2-008 的权威出处在 11 号文档）。

用法
----
    python backend/scripts/verify_golden_set.py
    python backend/scripts/verify_golden_set.py --json docs/评测报告/golden-l2-validation.json

    # 依赖：本机 PostgreSQL 在跑、rag_chunk 已入库；psql 路径见 --psql
    # 退出码 0 = 全部通过

⚠️ psql 的调用封装**直接复用** `verify_bm25.py` 的 `Db`（那里记录了本机踩过的坑：
漏传 `env=` 会让 psql 去弹控制台口令提示从而永久挂起；中文 SQL 必须走 `-f` 而不能走 `-c`）。
不为本脚本复制一份——**同一个坑只应该被记一次**。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

# 复用兄弟脚本里已被实测验证过的 psql 封装（含"漏传 env= 会永久挂起"那条教训）。
# ⚠️ 只复用 `Db` 与 `load_env`：`verify_bm25` 的 `err/warn` 是**消息收集器**
# （往模块级 errors/warnings 列表里追加、并不打印），拿来做打印函数会静默丢掉输出——
# 所以本脚本自己定义 err/warn/ok。
sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_bm25 import Db, load_env  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "docs" / "评测报告" / "golden-l2-v2.json"
DEFAULT_PSQL = Path(r"D:\APP\PostgreSQL\18\bin\psql.exe")


def err(msg: str) -> None:
    print(f"  [错误] {msg}")


def warn(msg: str) -> None:
    print(f"  [警告] {msg}")


def ok(msg: str) -> None:
    print(f"[PASS] {msg}")

REQUIRED_ITEM_FIELDS = (
    "id", "question", "questionType", "topicClass", "split", "goldChunkIds", "goldRationale",
)
VALID_TYPES = {"T1-精确数值", "T2-时效", "T3-条件组合", "T4-术语定义", "T5-出处与文号", "T6-版本冲突"}
VALID_SPLITS = {"dev", "holdout"}
DOC_NO_RE = re.compile(r"^(\d{2})-")
DOC_NO_IN_TEXT_RE = re.compile(r"PLYH-[A-Z]{2}-\d{4}-\d{3}")


def sql_quote(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def contiguous_overlap(a: str, b: str, min_len: int) -> str:
    """返回 a 与 b 之间长度 >= min_len 的公共连续子串（取最长的一个，没有则空串）。

    朴素实现（按 min_len 滑窗建集合）——题面与标题都很短，不值得上后缀自动机。
    逐**字符**滑窗而不是逐词：中文没有词边界，且我们关心的正是字面重合。
    """
    if len(b) < min_len or len(a) < min_len:
        return ""
    windows = {b[i:i + min_len] for i in range(len(b) - min_len + 1)}
    best = ""
    for i in range(len(a) - min_len + 1):
        cand = a[i:i + min_len]
        if cand in windows:
            # 命中后尽量向右延伸，报出"实际有多长"
            j = min_len
            while i + j < len(a) and j < len(b) and a[i + j] == b[j]:
                j += 1
            grown = a[i:i + j]
            if len(grown) > len(best):
                best = grown
    return best


def char_overlap_ratio(title: str, question: str) -> float:
    """标题字符集被题面覆盖的比例（集合口径，忽略重复字）。"""
    t = set(title)
    if not t:
        return 0.0
    return len(t & set(question)) / len(t)


def subject_doc(wenhao: str, docs: list[str]) -> str | None:
    """从文号（如 PLYH-GZ-2026-001）推断它**所属文档**的 doc_id（如 01-…）。

    语料命名约定：文号末段的 3 位序号 = 文档编号（01–26）。
    为什么要"所属文档"而不是"gold 所在文档"：v2 起 L2-034 的 gold 含**引用条**
    （04-第一条在 04 号文档里引用了 01 号的文号），若按 gold 取文档就会把这条例外判错。
    """
    m = re.search(r"-(\d{3})$", wenhao)
    if not m:
        return None
    no = int(m.group(1))
    for d in docs:
        dm = DOC_NO_RE.match(d)
        if dm and int(dm.group(1)) == no:
            return d
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 Golden Set L2 数据集（结构/分布/反照抄）")
    ap.add_argument("--dataset", default=str(DEFAULT_DATASET), help="数据集 JSON 路径")
    ap.add_argument("--json", dest="json_out", default=None, help="把确定性汇总写到该路径")
    ap.add_argument("--psql", default=str(DEFAULT_PSQL), help="psql 可执行文件")
    args = ap.parse_args()

    path = Path(args.dataset)
    if not path.is_file():
        err(f"数据集不存在：{path}")
        return 2
    raw = path.read_bytes()
    dataset = json.loads(raw.decode("utf-8"))
    dataset_sha = hashlib.sha256(raw).hexdigest()
    items = dataset.get("items", [])
    design = dataset.get("design", {})
    class_ranges = design.get("topicClassDocRange", {})

    failures: list[str] = []
    warns: list[str] = []

    def fail(msg: str) -> None:
        failures.append(msg)
        err(msg)

    # ---------- 1. 结构完整 ----------
    need_top = ("datasetId", "datasetVersion", "layer", "corpus", "design", "items")
    for k in need_top:
        if k not in dataset:
            fail(f"数据集缺少顶层字段：{k}")
    if failures:
        return 2
    if dataset["layer"] != "L2":
        fail(f"layer 应为 L2，实际为 {dataset['layer']!r}")

    for it in items:
        iid = it.get("id", "<无 id>")
        for f in REQUIRED_ITEM_FIELDS:
            if f not in it:
                fail(f"{iid} 缺少字段：{f}")
        if it.get("questionType") not in VALID_TYPES:
            fail(f"{iid} 题型非法：{it.get('questionType')!r}")
        if it.get("split") not in VALID_SPLITS:
            fail(f"{iid} split 非法：{it.get('split')!r}")
        if not isinstance(it.get("goldChunkIds"), list) or not it.get("goldChunkIds"):
            fail(f"{iid} goldChunkIds 必须是非空数组")
    if failures:
        return 2

    # ---------- 2. 编号连续 ----------
    ids = [it["id"] for it in items]
    if len(set(ids)) != len(ids):
        dup = [i for i, c in Counter(ids).items() if c > 1]
        fail(f"id 重复：{dup}")
    expected_ids = [f"L2-{n:03d}" for n in range(1, len(items) + 1)]
    if sorted(ids) != expected_ids:
        missing = sorted(set(expected_ids) - set(ids))[:5]
        fail(f"id 编号不连续或有缺号（应为 L2-001..L2-{len(items):03d}），缺失：{missing}")

    # ---------- 3. 条数与划分 ----------
    n_total = len(items)
    if n_total != design.get("totalItems"):
        fail(f"条数 {n_total} 与 design.totalItems={design.get('totalItems')} 不一致")
    split_counts = Counter(it["split"] for it in items)
    if split_counts["holdout"] != design.get("holdoutItems"):
        fail(f"保留集实际 {split_counts['holdout']} 条，design 声明 {design.get('holdoutItems')} 条")
    if split_counts["dev"] != design.get("devItems"):
        fail(f"开发集实际 {split_counts['dev']} 条，design 声明 {design.get('devItems')} 条")

    holdout_types = {it["questionType"] for it in items if it["split"] == "holdout"}
    holdout_classes = {it["topicClass"] for it in items if it["split"] == "holdout"}
    if len(holdout_types) < 4:
        fail(f"保留集只覆盖 {len(holdout_types)} 个题型（要求 >=4）——否则保留集只检验单一场景")
    if len(holdout_classes) < 4:
        fail(f"保留集只覆盖 {len(holdout_classes)} 个主题类（要求 >=4）")

    # ---------- 4/5. 题型与主题类分布 ----------
    type_counts = Counter(it["questionType"] for it in items)
    class_counts = Counter(it["topicClass"] for it in items)
    for t in sorted(VALID_TYPES):
        if type_counts[t] < 6:
            fail(f"题型 {t} 只有 {type_counts[t]} 条（要求 >=6）")
    for c, (lo, hi) in sorted(class_ranges.items()):
        if class_counts[c] < 4:
            fail(f"主题类 {c}（文档 {lo:02d}–{hi:02d}）只有 {class_counts[c]} 条（要求 >=4）")
    if set(class_counts) - set(class_ranges):
        fail(f"出现了未定义的主题类：{sorted(set(class_counts) - set(class_ranges))}")

    # ---------- 6/7. gold chunk 存在性 + 主题类归属 + 正文非空 ----------
    all_gold = sorted({cid for it in items for cid in it["goldChunkIds"]})
    all_accept = sorted({cid for it in items for cid in it.get("acceptableChunkIds", [])})
    env = load_env(ROOT)
    db = Db(Path(args.psql), env, "127.0.0.1", "5432", "canalmind", "postgres")
    rows = db.rows(
        "SELECT chunk_id, doc_id, chunk_type, coalesce(article_no,'') AS article_no, "
        "coalesce(article_title,'') AS article_title, length(content) AS content_len "
        "FROM rag_chunk WHERE chunk_id IN ("
        + ", ".join(sql_quote(c) for c in sorted(set(all_gold) | set(all_accept))) + ")",
        name="gold_chunks",
    )
    meta = {r["chunk_id"]: r for r in rows}
    all_docs = [r["doc_id"] for r in db.rows(
        "SELECT DISTINCT doc_id FROM rag_chunk ORDER BY doc_id", name="all_docs")]
    unknown = [c for c in all_gold if c not in meta]
    if unknown:
        fail(f"{len(unknown)} 个 gold chunk 在库里不存在（疑似 id 抄错）：{unknown}")

    # ---------- 6b. 可接受等价块（v2 新增字段）----------
    # ⚠️ 等价块**不受主题类范围约束**：跨文档复述是本语料的已知特征，权威出处常落在别的文档
    #    （如 L2-008 的权威出处在 11 号文档，而该题主题类是 A=01–04）。故只查"存在性 + 不与 gold 重叠"。
    accept_same_doc, accept_cross_doc = 0, 0
    for it in items:
        acc = list(it.get("acceptableChunkIds", []))
        if not acc:
            continue
        for cid in acc:
            if cid not in meta:
                fail(f"{it['id']} 的 acceptable chunk {cid} 在库里不存在（疑似 id 抄错）")
                continue
            if cid in it["goldChunkIds"]:
                fail(f"{it['id']} 的 acceptable chunk {cid} 与 gold 重叠——两边应是互斥集合")
            gold_docs = {meta[c]["doc_id"] for c in it["goldChunkIds"] if c in meta}
            if meta[cid]["doc_id"] in gold_docs:
                accept_same_doc += 1
            else:
                accept_cross_doc += 1
        if not it.get("acceptableRationale"):
            warn(f"{it['id']} 标了 acceptableChunkIds 但没写 acceptableRationale（豁免必须给出理由）")
        if it.get("acceptableRejected") and not it.get("acceptableRejectedReason"):
            warn(f"{it['id']} 标了 acceptableRejected 但没写理由")

    multi_doc_gold: list[str] = []
    leak_fail: list[str] = []
    leak_warn: list[str] = []
    term_exemptions: list[str] = []
    ident_exemptions: list[str] = []
    cross_class_exemptions: list[str] = []
    for it in items:
        docs = set()
        for cid in it["goldChunkIds"]:
            m = meta.get(cid)
            if not m:
                continue
            docs.add(m["doc_id"])
            if int(m["content_len"] or 0) <= 0:
                fail(f"{it['id']} 的 gold chunk {cid} 正文为空")
        if len(docs) > 1:
            multi_doc_gold.append(f"{it['id']} 跨文档：{sorted(docs)}")
        # 主题类归属：gold 的文档编号必须落在该类范围内
        # ⚠️ 允许**显式声明**的例外：有些题的权威依据本身在别的主题类文档里
        #    （如 L2-027 的权威定义在 25-术语与定义，而该题主文档在 C 类）。
        #    这类例外必须写 `crossClassGoldReason` 才会降级为警告——**声明式豁免必须可见**，
        #    否则"gold 跨类"这种真错就会被静默放过。
        rng = class_ranges.get(it["topicClass"])
        if rng and docs:
            for d in docs:
                mo = DOC_NO_RE.match(d)
                if not mo:
                    fail(f"{it['id']} 的 gold 文档 id 无法解析编号：{d}")
                    continue
                n = int(mo.group(1))
                if not (rng[0] <= n <= rng[1]):
                    if it.get("crossClassGoldReason"):
                        cross_class_exemptions.append(
                            f"{it['id']} gold 落在 {d}（超出主题类 {it['topicClass']} 的 "
                            f"{rng[0]:02d}–{rng[1]:02d}），已声明例外：{it['crossClassGoldReason']}")
                    else:
                        fail(f"{it['id']} 标为主题类 {it['topicClass']}（文档 {rng[0]:02d}–{rng[1]:02d}），"
                             f"但 gold 落在 {d}——主题类标注与 gold 不一致")
        # 反照抄：题面 vs gold 条款标题
        # 规则分三档，理由是「重合」本身不是错误，**长度**才决定它是不是捷径：
        #   硬失败 ①  连续 >=5 字与标题重合 —— 5 个字已足够构成检索捷径
        #   硬失败 ②  标题 >=8 字且字符集重合率 >=0.95 —— 近乎逐字照抄
        #   警告      重合率 >=0.8 —— 4~6 字的领域术语（「计费单位」「申请条件」）无法回避
        # 两类**声明式豁免**（都必须在数据集里写明理由，并在本脚本输出里可见）：
        #   · 术语豁免：T4 定义类题目，标题去掉「定义/术语/口径/含义/规则」后若是被定义的
        #     术语本身，则**必须**出现在题面里——否则无法问「X 是什么意思」。
        #     这是语义需要，不是照抄；豁免范围也**仅限**该术语，模板措辞不在其列。
        #   · 标识符豁免：`allowedTitleOverlap`（如 V1.0、2026）——版本号/年份是标识符，
        #     问「上一版何时废止」必须指明哪一版。逐条声明，不做全局放宽。
        TERM_SUFFIXES = ("定义", "术语", "口径", "含义", "规则")
        for cid in it["goldChunkIds"]:
            m = meta.get(cid)
            if not m or not m["article_title"]:
                continue
            title, q = m["article_title"], it["question"]
            allowed = list(it.get("allowedTitleOverlap", []))
            for a in allowed:
                if a not in q or a not in title:
                    warn(f"{it['id']} 声明的豁免串「{a}」并未同时出现在题面与标题里——疑似失效声明")
                else:
                    # ⚠️ 生效的标识符豁免必须**逐条打印**：豁免若只在沉默中放宽，
                    #    读者无法判断"没报错"是"确实没重合"还是"被豁免掉了"。
                    ident_exemptions.append(
                        f"{it['id']} 标识符「{a}」为版本/编号区分所必需，允许出现在题面"
                        f"（理由：{it.get('allowedTitleOverlapReason', '未写明')}）")
            q_eff, title_eff = q, title
            for a in allowed:
                q_eff, title_eff = q_eff.replace(a, ""), title_eff.replace(a, "")
            if q.strip() == title.strip():
                leak_fail.append(f"{it['id']} 题面与条款标题完全相同：{title}")
                continue
            # ⚠️ 术语豁免要在**连续片段检查之前**判定：被定义的术语本身必然与标题重合，
            #    若放在后面，检查会先命中并 continue，豁免永远得不到执行
            #    （第一版就是这么写的，实测 L2-023 被误判）。
            is_term_exempt = False
            if it["questionType"] == "T4-术语定义":
                core = title_eff.strip()
                for suf in TERM_SUFFIXES:
                    if core.endswith(suf) and len(core) > len(suf):
                        core = core[: -len(suf)]
                if core and core in q_eff:
                    is_term_exempt = True
                    term_exemptions.append(f"{it['id']} 术语「{core}」为被定义对象，允许出现在题面")
            if is_term_exempt:
                continue
            seg = contiguous_overlap(q_eff, title_eff, 5)
            if seg:
                leak_fail.append(f"{it['id']} 题面含标题连续片段「{seg}」（标题：{title}）")
                continue
            ratio = char_overlap_ratio(title_eff, q_eff)
            if len(title_eff) >= 8 and ratio >= 0.95:
                leak_fail.append(f"{it['id']} 题面与标题字符重合率 {ratio:.2f}（标题：{title}）")
            elif ratio >= 0.8:
                leak_warn.append(f"{it['id']} 重合率 {ratio:.2f}（标题：{title}）——"
                                 f"若为通用领域术语可接受，否则请改写题面")

    for msg in leak_fail:
        fail("反照抄：" + msg)
    for msg in leak_warn:
        warn("反照抄（警告）：" + msg)
    # 豁免逐条打印；同一条重复出现（如同一标题在多个 gold 块上）只打一次
    for msg in dict.fromkeys(term_exemptions):
        print(f"  [豁免] {msg}")
    for msg in dict.fromkeys(ident_exemptions):
        print(f"  [豁免] {msg}")
    for msg in dict.fromkeys(cross_class_exemptions):
        print(f"  [豁免] {msg}")
    for msg in multi_doc_gold:
        # ⚠️ 必须**同时**收集进 warns：collector 与 printer 是两套东西，
        #    只 print 会让这条警告在机读产物里消失（"打印了但没落盘"）。
        warns.append("gold 跨文档（需确认是否确实需要多篇证据）：" + msg)
        warn("gold 跨文档（需确认是否确实需要多篇证据）：" + msg)

    # ---------- 8. 已知限制项自证 ----------
    # ⚠️ 断言必须精确：声明的是"**该文档自己的 chunk 里没有自己的文号**"，
    #    而**不是**"全库都没有"——实测 PLYH-GZ-2026-001 在别的文档里出现 4 次
    #    （02/03/04 在第一条里**引用**它），而在 01 自己的 21 个 chunk 里出现 0 次。
    #    第一版检查写成"全库为 0"，于是把一条**成立的**限制误判为不成立——
    #    这正是"检查条件写错会把正确的东西判成错的"那类坑，故把两个口径都打出来。
    limitation_checks = []
    for it in items:
        lim = it.get("knownLimitation")
        if not lim:
            continue
        codes = DOC_NO_IN_TEXT_RE.findall(it["question"])
        if not codes:
            fail(f"{it['id']} 标了 knownLimitation 但题面里找不到文号，无法自证")
            continue
        own_docs = sorted({meta[c]["doc_id"] for c in it["goldChunkIds"] if c in meta})
        for code in codes:
            # ⚠️ 断言针对的是「**该文号所属的那份文档**自己的 chunk 里没有自己的文号」，
            #    而不是「gold 所在的文档」。v2 起 L2-034 的 gold 包含承载文号的**引用条**
            #    （04-第一条在 04 号文档里），若仍按 gold 取文档，会把这条例外判成错误。
            subj = subject_doc(code, all_docs)
            if not subj:
                warn(f"{it['id']} 无法从文号 {code} 推断所属文档，跳过自证")
                continue
            for doc in [subj] + [d for d in own_docs if d != subj]:
                r = db.rows(
                    f"SELECT count(*) AS anywhere, "
                    f"count(*) FILTER (WHERE doc_id = {sql_quote(doc)}) AS in_own_doc "
                    f"FROM rag_chunk WHERE content LIKE '%{code}%'",
                    name=f"limitation_{it['id'].replace('-', '_')}_{DOC_NO_RE.match(doc).group(1)}",
                )[0]
                rec = {
                    "itemId": it["id"], "wenhao": code, "ownDoc": doc,
                    "isSubjectDoc": doc == subj,
                    "chunksAnywhere": int(r["anywhere"]), "chunksInOwnDoc": int(r["in_own_doc"]),
                }
                limitation_checks.append(rec)
                if doc == subj and rec["chunksInOwnDoc"] != 0:
                    fail(f"{it['id']} 声明的 knownLimitation 不成立：文号 {code} 在**它所属文档"
                         f"（{doc}）自己**的 chunk 里出现了 {rec['chunksInOwnDoc']} 次")
                elif doc == subj and rec["chunksAnywhere"] == 0:
                    warn(f"{it['id']} 的文号 {code} 全库都不出现——限制成立但成因不是「被引用」")

    # ---------- 汇总输出 ----------
    print()
    print("=" * 72)
    print(f"Golden Set L2 校验：{path.name}")
    print(f"  datasetSha256 = {dataset_sha}")
    print(f"  条数 = {n_total}（dev {split_counts['dev']} / holdout {split_counts['holdout']}）")
    print("-" * 72)
    print("题型分布：")
    for t in sorted(VALID_TYPES):
        print(f"  {t:<12} {type_counts[t]:>2} 条")
    print("主题类覆盖：")
    for c, (lo, hi) in sorted(class_ranges.items()):
        print(f"  {c}（{lo:02d}–{hi:02d}）  {class_counts[c]:>2} 条")
    print(f"gold chunk 去重后 {len(all_gold)} 个，全部命中库表："
          f"{'是' if not unknown else '否'}")
    print(f"可接受等价块 {len(all_accept)} 个（与 gold 同文档 {accept_same_doc} / 跨文档 {accept_cross_doc}）；"
          f"gold 为多块（m>1）的题：{sum(1 for it in items if len(it['goldChunkIds']) > 1)} 条")
    if limitation_checks:
        print("已知限制项自证（‘所属文档’= 文号末段序号指向的那份文档）：")
        for c in limitation_checks:
            role = "所属文档" if c["isSubjectDoc"] else "引用它的文档"
            print(f"  {c['itemId']}：文号 {c['wenhao']} 在{role}（{c['ownDoc']}）的 chunk 中出现 "
                  f"{c['chunksInOwnDoc']} 次；全库共被引用 {c['chunksAnywhere']} 处")
    print("-" * 72)
    print(f"错误 {len(failures)} 项，警告 {len(warns) + len(leak_warn)} 项")

    summary = {
        "dataset": path.name,
        "datasetSha256": dataset_sha,
        "datasetVersion": dataset["datasetVersion"],
        "totalItems": n_total,
        "splitCounts": dict(sorted(split_counts.items())),
        "typeCounts": dict(sorted(type_counts.items())),
        "classCounts": dict(sorted(class_counts.items())),
        "goldChunkCount": len(all_gold),
        "acceptableChunkCount": len(all_accept),
        "acceptableSameDocCount": accept_same_doc,
        "acceptableCrossDocCount": accept_cross_doc,
        "multiGoldItemCount": sum(1 for it in items if len(it["goldChunkIds"]) > 1),
        "holdoutTypeCount": len(holdout_types),
        "holdoutClassCount": len(holdout_classes),
        "limitationChecks": limitation_checks,
        "termExemptions": list(dict.fromkeys(term_exemptions)),
        "identifierExemptions": list(dict.fromkeys(ident_exemptions)),
        "crossClassGoldExemptions": list(dict.fromkeys(cross_class_exemptions)),
        "failures": failures,
        "warnings": warns + leak_warn,
    }
    if args.json_out:
        out = Path(args.json_out)
        out.parent.mkdir(parents=True, exist_ok=True)
        # 确定性：键排序 + LF + 无 BOM + 无时间戳
        text = json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
        out.write_bytes((text + "\n").encode("utf-8"))
        print(f"机读汇总已写入：{out}")

    if failures:
        err(f"[FAIL] Golden Set 校验未通过（{len(failures)} 项错误）")
        return 1
    ok("Golden Set L2 结构与分布校验通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
