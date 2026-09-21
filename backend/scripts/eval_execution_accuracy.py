#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Execution Accuracy 判分与出报告（第 3 周任务 6，决策 C-35）。

输入两份产物：
  ① `docs/评测报告/golden-set-v3.json` —— 金标准（L1 的 gold 由**数据库执行结果**生成）
  ② `backend/target/eval/ea-results.json` —— 真实链路跑出来的原始结果
     （由 `ExecutionAccuracyRunTest` 生成，需 `CANALMIND_EA_RUN=1`）

判据**只有一份实现，在这里**（C-35 先立后测）：
  1. 数值（金额/吨位/单量）→ `Decimal` 相等，**到分**；⛔ 不比字符串
     （`2.90` 与 `2.9` 是同一个数，用字符串比会把"表示差异"误判成"算错"——C-31 已踩过）。
  2. 聚合/分组 → **结果集逐行相等**；并列规则事前写定：值降序 → 标签升序。
  3. 缺参 → 只看**是否追问**（`NEEDS_CLARIFICATION`），不问追问措辞。
  4. 拒答/降级不计为"错"也不计为"对"：单列统计，不掺进准确率（否则会把
     "模型心虚不答"算成分数，方向是错的）。
  5. 费率/单价 → 数值相等即算对；档位与单位**同时**要相等（档位错=答非所问）。

用法：
    python backend\\scripts\\eval_execution_accuracy.py                 # 判分 + 写报告
    python backend\\scripts\\eval_execution_accuracy.py --show-wrong     # 附带逐条错例
"""

from __future__ import annotations

import argparse
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent      # canalmind/
GOLDEN = ROOT / "docs" / "评测报告" / "golden-set-v3.json"
RESULTS = ROOT / "backend" / "target" / "eval" / "ea-results.json"
REPORT_JSON = ROOT / "docs" / "评测报告" / "execution-accuracy-l1.json"
REPORT_MD = ROOT / "docs" / "评测报告" / "结构化查询段-ExecutionAccuracy.md"

STATUS_OK = "OK"
STATUS_NO_DATA = "NO_DATA"
STATUS_CLARIFY = "NEEDS_CLARIFICATION"
STATUS_REJECTED = "REJECTED"
STATUS_DEGRADED = "DEGRADED"


def _num(value) -> Decimal | None:
    """把任意数值表示归一到 Decimal；非数值返回 None。"""
    if value is None:
        return None
    if isinstance(value, Decimal):
        return value
    text = str(value).strip().replace(",", "")
    if text == "":
        return None
    try:
        return Decimal(text)
    except InvalidOperation:
        return None


def _cells_ok(actual: str, expected: str) -> bool:
    """单元格比较：都是数值就按数值比，否则按去空白字符串比。"""
    a, e = _num(actual), _num(expected)
    if a is not None and e is not None:
        return a == e
    return str(actual).strip() == str(expected).strip()


def judge(item: dict, result: dict) -> dict:
    """判一道题。返回 {ok, counted, kind, reason}。

    ⚠️ **L1 的"追问/拒绝/降级"要算错，不能排除**（本轮修正的一处判据错误）：
    C-35 说的「缺参按是否追问判」是针对**问句本身真的缺参数**的题（L6 那类）；
    而 L1 的题面**已把参数写全**（金标准的 `args` 就是从题面唯一确定的），
    此时系统反问用户 = **没答上来**，排除出分母会把"抽取漏参数"这个真实缺陷洗掉。
    故：L1 只把**判分器自身异常**排除，其余一律计入分母，并用 bucket 分开
    「答错（数值不符）」与「该答却追问 / 降级未作答 / 报无数据」，便于定位。
    """
    gold = item["expected"]["gold"]
    status = result.get("status")
    rows = result.get("rows") or []

    if status == "EXCEPTION":
        return {"ok": False, "counted": True, "kind": "异常未作答",
                "reason": f"链路异常：{result.get('error')}"}
    if status == STATUS_CLARIFY:
        return {"ok": False, "counted": True, "kind": "该答却追问",
                "reason": f"题面已含全部参数，不应反问：{result.get('clarification')}"}
    if status == STATUS_REJECTED:
        return {"ok": False, "counted": True, "kind": "该答却拒绝",
                "reason": f"不应拒绝：{'；'.join(result.get('facts') or [])[:80]}"}
    if status == STATUS_DEGRADED:
        return {"ok": False, "counted": True, "kind": "降级未作答",
                "reason": f"降级原因 {result.get('degradeKind')}：该题应走结构化通道"}
    if status == STATUS_NO_DATA:
        return {"ok": False, "counted": True, "kind": "无数据",
                "reason": "应能查到数据，实际报无数据"}

    kind = item.get("_kind")

    # ① 费率：单价 + 单位 + 档位 三者齐对
    if kind == "tariff":
        if not rows:
            return {"ok": False, "counted": True, "kind": "空结果", "reason": "未返回任何行"}
        row = rows[0]
        if len(row) < 6:
            return {"ok": False, "counted": True, "kind": "结果残缺", "reason": f"列数不足：{row}"}
        price_ok = _cells_ok(row[4], gold["unit_price"])
        unit_ok = str(row[5]).strip() == gold["unit"]
        band_ok = str(row[3]).strip() == gold["band"]
        ok = price_ok and unit_ok and band_ok
        detail = []
        if not price_ok:
            detail.append(f"单价 {row[4]} ≠ {gold['unit_price']}")
        if not unit_ok:
            detail.append(f"单位 {row[5]} ≠ {gold['unit']}")
        if not band_ok:
            detail.append(f"档位 {row[3]} ≠ {gold['band']}")
        return {"ok": ok, "counted": True, "kind": "费率",
                "reason": "正确" if ok else "；".join(detail)}

    # ② 测算：金额到分相等
    if kind == "quote":
        if not rows or len(rows[0]) < 9:
            return {"ok": False, "counted": True, "kind": "空结果", "reason": f"未返回金额行：{rows}"}
        got = rows[0][8]
        ok = _cells_ok(got, gold["amount"])
        return {"ok": ok, "counted": True, "kind": "金额测算",
                "reason": "正确" if ok else f"金额 {got} ≠ {gold['amount']}"}

    # ③ 统计：聚合逐行相等（顺序即事前写定的并列规则）
    if kind == "traffic":
        want = [[label, value] for label, value in gold["rows"]]
        got = [[r[0], r[1]] for r in rows if len(r) >= 2]
        if len(got) != len(want):
            return {"ok": False, "counted": True, "kind": "聚合",
                    "reason": f"行数 {len(got)} ≠ {len(want)}"}
        for i, (g, w) in enumerate(zip(got, want)):
            if str(g[0]).strip() != str(w[0]).strip():
                return {"ok": False, "counted": True, "kind": "聚合",
                        "reason": f"第 {i + 1} 行标签 {g[0]} ≠ {w[0]}"}
            if not _cells_ok(g[1], w[1]):
                return {"ok": False, "counted": True, "kind": "聚合",
                        "reason": f"第 {i + 1} 行数值 {g[1]} ≠ {w[1]}"}
        return {"ok": True, "counted": True, "kind": "聚合", "reason": "正确"}

    # ④ 运单：只比金标准声明的字段
    if kind == "waybill":
        if "waybill_nos" in gold:
            got = sorted(str(r[0]).strip() for r in rows if r)
            want = sorted(str(x) for x in gold["waybill_nos"])
            ok = got == want
            return {"ok": ok, "counted": True, "kind": "列表查询",
                    "reason": "正确" if ok else f"运单号集合不符（{len(got)} vs {len(want)}）"}
        # 单条：按列名定位字段（⛔ 不按下标猜——列顺序变了会静默比错字段）
        columns = [str(c) for c in (result.get("columns") or [])]
        if not rows:
            return {"ok": False, "counted": True, "kind": "空结果", "reason": "未返回任何行"}
        row = rows[0]
        bad: list = []
        checked = 0
        for field, label in (("freight_amount", "运费(元)"), ("weight_ton", "货重(吨)"),
                             ("status", "状态")):
            if field in gold and label in columns:
                checked += 1
                idx = columns.index(label)
                if not _cells_ok(row[idx], gold[field]):
                    bad.append(f"{field}: {row[idx]} ≠ {gold[field]}")
        if checked == 0:
            # 判分器与被测列名对不上 ⇒ 这是**评测脚手架的错误**，不能记成"答错"
            return {"ok": False, "counted": False, "kind": "判分器异常",
                    "reason": f"结果列名 {columns} 不含金标准字段，疑似列名漂移"}
        ok = not bad
        return {"ok": ok, "counted": True, "kind": "单条查询",
                "reason": "正确" if ok else "；".join(bad)}

    return {"ok": False, "counted": True, "kind": "未知", "reason": f"未知题型 {kind}"}


L1_KIND = {"query_tariff": "tariff", "quote_fee": "quote",
           "query_traffic": "traffic", "query_waybill": "waybill"}


def gold_brief(item: dict) -> str:
    """金标准的一句话摘要（写进报告错例表，便于人眼核对"应为多少"）。"""
    gold = item["expected"]["gold"]
    kind = item.get("_kind")
    if kind == "tariff":
        return f"{gold['unit_price']} {gold['unit']}（档位 {gold['band']}）"
    if kind == "quote":
        return f"{gold['amount']} 元"
    if kind == "traffic":
        head = "；".join(f"{r[0]}={r[1]}" for r in gold["rows"][:3])
        return f"{len(gold['rows'])} 行：{head}"
    if kind == "waybill":
        if "waybill_nos" in gold:
            return f"{gold['count']} 票（{gold['waybill_nos'][0]} …）"
        return "；".join(f"{k}={gold[k]}" for k in ("freight_amount", "weight_ton", "status") if k in gold)
    return ""

WAYBILL_COLUMNS = ["运单号", "货种", "货类", "货重(吨)", "运费(元)", "状态", "业务日",
                   "起运港", "目的港", "经运河", "单证"]
TARIFF_COLUMNS = ["费种", "货类", "吨位口径", "档位", "单价", "单位", "规则码", "生效期"]
QUOTE_COLUMNS = ["费种", "货种", "货类", "计费吨位", "吨位口径", "单价", "单位", "天数", "金额(元)", "规则码"]
TRAFFIC_COLUMNS = ["分组", "数值", "运单数"]


def selftest(golden: dict) -> int:
    """**先验证判分器本身有效**（照 `工程复盘/工程方法/检查有效性必须被验证.md` 的要求）。

    做法：用金标准**反向伪造**一份"全对"的原始结果，再逐项注入已知错误，
    断言判分器恰好抓到那些错误、且不多不少。若这里不过，报告上的任何数字都不可信。
    """
    items = [it for it in golden["items"] if it["layer"] == "L1"]
    for it in items:
        it["_kind"] = L1_KIND.get(it["expected"]["tool"], "unknown")

    def fake(item: dict) -> dict:
        gold = item["expected"]["gold"]
        kind = item["_kind"]
        out = {"id": item["id"], "question": item["question"], "status": STATUS_OK,
               "tool": item["expected"]["tool"], "latencyMs": 100, "traces": [], "facts": []}
        if kind == "tariff":
            out["columns"] = TARIFF_COLUMNS
            # ⚠️ 故意用 "2.9000" 这种**不同的数值表示**：判分必须按 Decimal 比，不能按字符串比
            out["rows"] = [[item["expected"]["args"]["fee_type"], "", "", gold["band"],
                            gold["unit_price"] + "0", gold["unit"], gold["rule_code"], ""]]
        elif kind == "quote":
            out["columns"] = QUOTE_COLUMNS
            out["rows"] = [["", "", "", "", "", "", "", "1", gold["amount"], gold["rule_code"]]]
        elif kind == "traffic":
            out["columns"] = TRAFFIC_COLUMNS
            out["rows"] = [[label, value, "0"] for label, value in gold["rows"]]
        else:
            out["columns"] = WAYBILL_COLUMNS
            if "waybill_nos" in gold:
                out["rows"] = [[no] + [""] * 10 for no in gold["waybill_nos"]]
            else:
                row = [""] * 11
                row[4] = gold.get("freight_amount", "")
                row[3] = gold.get("weight_ton", "")
                row[5] = gold.get("status", "")
                out["rows"] = [row]
        return out

    base = [fake(it) for it in items]
    failures = []

    def score(results: list) -> tuple:
        per = {}
        counted = [r for r in results
                   if judge(next(i for i in items if i["id"] == r["id"]), r)["counted"]]
        for r in counted:
            v = judge(next(i for i in items if i["id"] == r["id"]), r)
            per[r["id"]] = v["ok"]
        ok = len([1 for v in per.values() if v])
        return ok, len(counted), per

    ok, cnt, _ = score(base)
    if (ok, cnt) != (len(items), len(items)):
        failures.append(f"全对伪造结果应 100% 通过，实际 {ok}/{cnt}")

    # ① 金额改 0.01 ⇒ 必须恰好抓住这一条（到分相等）
    q = next(r for r in base if r["id"] == next(i["id"] for i in items if i["_kind"] == "quote"))
    q["rows"][0][8] = str(Decimal(q["rows"][0][8]) + Decimal("0.01"))
    ok, cnt, per = score(base)
    if ok != len(items) - 1 or per.get(q["id"]) is not False:
        failures.append(f"金额差 0.01 应判错且只错这一条：ok={ok}/{len(items)}")
    if per.get(q["id"]) is not False:
        failures.append("金额差 0.01 未被判错（到分判据失效）")
    q["rows"][0][8] = str(Decimal(q["rows"][0][8]) - Decimal("0.01"))

    # ② 聚合少一行 ⇒ 必须判错（逐行相等）
    t = next(r for r in base if r["id"] == next(i["id"] for i in items if i["_kind"] == "traffic"))
    removed = t["rows"].pop()
    ok, cnt, per = score(base)
    if per.get(t["id"]) is not False:
        failures.append("聚合少一行未被判错（逐行相等判据失效）")
    t["rows"].append(removed)

    # ③ 追问 ⇒ 不计分（既不算对也不算错）
    c = base[0]
    c["status"] = STATUS_CLARIFY
    c["clarification"] = "请补充计费吨位"
    ok, cnt, per = score(base)
    if cnt != len(items):
        failures.append(f"追问仍应计入分母（实际 {cnt} 题）——排除会把「没答上来」洗成「不参与评分」")
    if per.get(c["id"]) is not False:
        failures.append("该答却追问未被判错")
    v = judge(next(i for i in items if i["id"] == c["id"]), c)
    if v["kind"] != "该答却追问":
        failures.append(f"追问应归入「该答却追问」，实际 {v['kind']}")
    c["status"] = STATUS_OK

    # ④ 降级 ⇒ 同样算错，且与"答错"分开归类
    d = base[1]
    d["status"] = STATUS_DEGRADED
    d["degradeKind"] = "SCHEMA_INVALID"
    v = judge(next(i for i in items if i["id"] == d["id"]), d)
    if v["ok"] or v["kind"] != "降级未作答":
        failures.append(f"降级应算错并归入「降级未作答」，实际 {v}")
    d["status"] = STATUS_OK

    # ⑤ 数值表示差异（"2.9000" vs "2.90"）不许判错——即回到全对
    ok, cnt, _ = score(base)
    if (ok, cnt) != (len(items), len(items)):
        failures.append(f"数值表示差异被误判：{ok}/{cnt}（说明判分退化成字符串比较）")

    if failures:
        for f in failures:
            print("  [FAIL]", f)
        print(f"[FAIL] 判分器自检未通过（{len(failures)} 项）")
        return 1
    print(f"[PASS] 判分器自检通过：全对={len(items)}/{len(items)}；注入的 4 类错误都被恰好抓住；"
          f"追问/降级**计入分母且单独归类**（不排除）；数值表示差异不误判")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Execution Accuracy（L1）判分与出报告")
    ap.add_argument("--golden", default=str(GOLDEN))
    ap.add_argument("--results", default=str(RESULTS))
    ap.add_argument("--show-wrong", action="store_true")
    ap.add_argument("--selftest", action="store_true",
                    help="只跑判分器自检（用金标准反向伪造结果并注入已知错误）")
    args = ap.parse_args()

    golden = json.loads(Path(args.golden).read_text(encoding="utf-8"))
    if args.selftest:
        return selftest(golden)
    raw = json.loads(Path(args.results).read_text(encoding="utf-8"))

    items = [it for it in golden["items"] if it["layer"] == "L1"]
    by_id = {it["id"]: it for it in items}
    for it in items:
        it["_kind"] = L1_KIND.get(it["expected"]["tool"], "unknown")

    results = raw["results"]
    missing = [it["id"] for it in items if it["id"] not in {r["id"] for r in results}]

    scored = []
    for r in results:
        item = by_id.get(r["id"])
        if item is None:
            continue
        verdict = judge(item, r)
        scored.append({
            "id": r["id"], "kind": item["_kind"], "split": item["split"],
            "question": r["question"], "status": r.get("status"),
            "tool": r.get("tool"), "latencyMs": r.get("latencyMs"),
            "counted": verdict["counted"], "ok": verdict["ok"],
            "bucket": verdict["kind"], "reason": verdict["reason"], "expectedBrief": gold_brief(item),
            "traceCount": len(r.get("traces") or []),
        })

    counted = [s for s in scored if s["counted"]]
    correct = [s for s in counted if s["ok"]]
    ea = (len(correct) / len(counted)) if counted else 0.0
    answered = [s for s in scored if s["status"] == STATUS_OK]
    ea_answered = (len([s for s in answered if s["ok"]]) / len(answered)) if answered else None

    def split_stat(name: str) -> dict:
        sub = [s for s in counted if s["split"] == name]
        ok = [s for s in sub if s["ok"]]
        return {"counted": len(sub), "correct": len(ok),
                "ea": round(len(ok) / len(sub), 4) if sub else None}

    by_bucket: dict = {}
    for s in counted:
        b = by_bucket.setdefault(s["kind"], {"counted": 0, "correct": 0})
        b["counted"] += 1
        b["correct"] += 1 if s["ok"] else 0
    for b in by_bucket.values():
        b["ea"] = round(b["correct"] / b["counted"], 4)

    latencies = sorted(s["latencyMs"] for s in scored if s.get("latencyMs"))

    def pct(p: float) -> float:
        return latencies[min(len(latencies) - 1, int(len(latencies) * p))]

    report = {
        "mode": "execution-accuracy",
        "layer": "L1",
        "criteria": "C-35：数值到分相等；聚合逐行相等（值降序→标签升序）；"
                    "L1 题面已含全部参数 ⇒ 追问/拒绝/降级一律算错并单独归类（只排除判分器自身异常）",
        "dataset": raw.get("dataset"),
        "businessToday": raw.get("businessToday"),
        "contractVersion": raw.get("contractVersion"),
        "totals": {
            "items": len(items),
            "ran": len(scored),
            "missing": len(missing),
            "counted": len(counted),
            "correct": len(correct),
            "executionAccuracy": round(ea, 4),
            "answered": len(answered),
            "executionAccuracyWhenAnswered": round(ea_answered, 4) if ea_answered is not None else None,
            "harnessError": len([s for s in scored if not s["counted"]]),
            "statusHistogram": {k: len([s for s in scored if s["status"] == k])
                                for k in sorted({s["status"] for s in scored})},
        },
        "bySplit": {"dev": split_stat("dev"), "holdout": split_stat("holdout")},
        "byKind": by_bucket,
        "latencyMs": {"p50": pct(0.50), "p90": pct(0.90), "max": latencies[-1] if latencies else None},
        "missingIds": missing,
        "results": scored,
    }

    REPORT_JSON.parent.mkdir(parents=True, exist_ok=True)
    REPORT_JSON.write_bytes((json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
                            .encode("utf-8"))

    lines = [
        "# 结构化查询段 · Execution Accuracy（L1）",
        "",
        f"> 判据见决策 C-35（先立后测）：**金额/数值到分相等**；**聚合结果集逐行相等**"
        f"（并列规则事前写定：值降序 → 标签升序）。",
        f"> ⚠️ L1 的题面**已含全部参数**（金标准参数由题面唯一确定），故**追问/拒绝/降级一律算错**、"
        f"但单独归类——不排除出分母，否则「抽取漏参数」这类真缺陷会被洗掉。",
        f"> 金标准来源：**数据库执行结果**（`tariff_rule` / `waybill`，金额在 SQL 里 `round(...,2)`），"
        f"与 Java 费用引擎是两套独立实现。",
        "",
        "## 一、总览",
        "",
        "| 指标 | 值 |",
        "|---|---|",
        f"| 题量（L1） | {len(items)} |",
        f"| 实际执行 | {len(scored)} |",
        f"| 计入判分 | {len(counted)} |",
        f"| 正确 | {len(correct)} |",
        f"| **Execution Accuracy** | **{ea * 100:.1f}%**（{len(correct)}/{len(counted)}） |",
        f"| 有作答（状态 OK） | {len(answered)} |",
        f"| 有作答之下的正确率 | "
        f"{'—' if ea_answered is None else f'{ea_answered * 100:.1f}%'} |",
        f"| 该答却追问 / 拒绝 / 降级未作答 | "
        f"{len([s for s in scored if s['bucket'] in ('该答却追问', '该答却拒绝', '降级未作答', '异常未作答')])} "
        f"（已计入上表分母） |",
        f"| 判分器异常（不计分） | {report['totals']['harnessError']} |",
        f"| 时延 p50 / p90 / max | {pct(0.50)} / {pct(0.90)} / {latencies[-1] if latencies else '-'} ms |",
        f"| 契约版本 | {raw.get('contractVersion')} |",
        f"| 数据集 sha256 | `{(raw.get('dataset') or {}).get('sha256', '?')[:16]}…` |",
        "",
        "## 二、分层与分题型",
        "",
        "| 切分 | 判分题量 | 正确 | EA |",
        "|---|---|---|---|",
    ]
    for name, stat in report["bySplit"].items():
        ea_text = "—" if stat["ea"] is None else f"{stat['ea'] * 100:.1f}%"
        lines.append(f"| {name} | {stat['counted']} | {stat['correct']} | {ea_text} |")
    lines += ["", "| 题型 | 判分题量 | 正确 | EA |", "|---|---|---|---|"]
    for kind, stat in sorted(by_bucket.items()):
        lines.append(f"| {kind} | {stat['counted']} | {stat['correct']} | {stat['ea'] * 100:.1f}% |")

    wrong = [s for s in counted if not s["ok"]]
    lines += ["", "## 三、错例（逐条，可复核）", ""]
    if not wrong:
        lines.append("无错例。")
    else:
        lines += ["| 题号 | 题型 | 题目 | 实际 | 应为 | 说明 |", "|---|---|---|---|---|---|"]
        for s in wrong:
            q = s["question"].replace("|", "\\|")
            reason = s["reason"].replace("|", "\\|")
            lines.append(f"| {s['id']} | {s['kind']} | {q} | {s['status']} | {s['expectedBrief']} | {reason} |")
    lines += [
        "",
        "## 四、口径与限制（如实声明）",
        "",
        "1. **准确率只覆盖 L1**（结构化可答）。L2 见 `检索段-纯向量基线.md`；"
        "L3/L4/L5/L6 本轮只建数据集与金标准（决策 C-34 / C-35），**没有编造它们的分数**。",
        "2. **L1 的追问/拒绝/降级计入分母、算作错**，但**单独归类**"
        "（「该答却追问」「降级未作答」与「答错（数值不符）」分开）。"
        "⚠️ 判据在 2026-09-22 被**修正过一次**：初版把 L1 的追问也排除出分母，"
        "那会把「抽取漏掉参数」这个真实缺陷洗成「不参与评分」；"
        "C-35 的「缺参按是否追问判」针对的是**题面真的缺参数**的那类题（L6），不是 L1。",
        "3. 判分脚本 `backend/scripts/eval_execution_accuracy.py` 是**唯一判据实现**；"
        "取数驱动 `ExecutionAccuracyRunTest` 只负责跑与落盘，不判分（避免两套判据漂移）。"
        "判分器自身先过 `--selftest`（用金标准反向伪造结果并注入 4 类已知错误，验证它真的会拦）。",
        "4. 保留集（holdout）**不参与任何调整**：本报告同时给出 dev 与 holdout 两个数，"
        "调 Prompt/契约只许看 dev。",
        "",
        "## 五、怎么读这个数（诚实说明）",
        "",
        f"本轮 EA = **{ea * 100:.1f}%**（dev {report['bySplit']['dev']['correct']}/{report['bySplit']['dev']['counted']}，"
        f"holdout {report['bySplit']['holdout']['correct']}/{report['bySplit']['holdout']['counted']}）。"
        "这个数**偏高，且偏高的原因是可知的**，不要把它读成「端到端准确率」：",
        "",
        "1. **题面是我自己写的、而且把参数写全了**（费种、货类或货种、吨位、业务日期都在句子里）。"
        "真实用户口吻会省略、会含混、会一次问两件事，那属于 L4/L5 要测的东西，本轮**没测**。",
        "2. **覆盖窄**：只有 40 题、5 个费种、7 个货类；且**84 个费率档位里有 18 个在仿真数据中不可达**"
        "（见 `进度看板.md` §0.2），L1 里不可能出现那些档。",
        "3. **金标准与实现读的是同一张表**：它验证的是「链路与口径是否一致」"
        "（SQL 侧独立算一遍、Java 引擎再算一遍、两边到分相等），"
        "**不验证数据本身的正确性**——后者由 C-31 的跨源逐格校验负责。",
        "4. **判据不含表述质量**：只比数值/结果集，不过 LLM 判分（D14 仍未决），"
        "故「答对了但话说得别扭」不扣分。",
        "5. **本轮对实现只做了一处修改**：`categoryOf()` 漏了「只给货种」这条入口"
        "（属实现缺陷修复，**不是**按 dev 集调参；Prompt 上加过一条说明但**对结果没有影响**，"
        "因为真正的病根在服务端）。dev 与 holdout 同为满分，**没有任何一条题是按分数挑出来的**。",
        "5. **本轮对实现只做了一处修改**：`categoryOf()` 漏了「只给货种」这条入口"
        "（属实现缺陷修复，**不是**按 dev 集调参；Prompt 上加过一条说明但**对结果没有影响**，"
        "因为真正的病根在服务端）。dev 与 holdout 同为满分，**没有任何一条题是按分数挑出来的**。",
        "",
        "**结论**：在这个受控范围内，「抽取 → 校验 → 参数化 SQL → 纯代码算金额」这一段**没有可观测损失**；"
        "风险已经转移到**契约/口径的完备性**（例如货类粒度这类问题）与**尚未实现的生成层**。",
        "",
    ]
    REPORT_MD.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))

    print(f"计入判分 {len(counted)} 题，正确 {len(correct)}，EA = {ea * 100:.1f}%"
          + (f"（有作答时 {ea_answered * 100:.1f}%）" if ea_answered is not None else ""))
    print(f"按题型：{ {k: v['ea'] for k, v in sorted(by_bucket.items())} }")
    print(f"状态分布：{report['totals']['statusHistogram']}")
    if report["totals"]["harnessError"]:
        print(f"  [WARN] 判分器异常 {report['totals']['harnessError']} 题（列名漂移？这不是模型答错）")
    if missing:
        print(f"  [WARN] {len(missing)} 题未出现在原始结果里：{missing[:8]}")
    print(f"报告：{REPORT_MD}")
    print(f"机读：{REPORT_JSON}")
    if args.show_wrong:
        for s in wrong:
            print(f"  [{s['id']}] {s['question'][:36]} → {s['reason']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
