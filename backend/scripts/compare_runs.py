#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""跨运行检索指标对照（第 2 周第 5/6 项用）。

**为什么单独一个脚本**：第 6 项要比较四路（纯向量 / 纯 BM25 / RRF 融合 / RRF+LLM Rerank）
在不同三档 Top-K 下的表现。若靠人工从几份指标产物里抄数字，会出现两类必然错误：
① 抄错（错位、串行）；② 抄的是不同数据集的运行（**跨数据集比较毫无意义**）。
本脚本因此把两件事钉死：**数字只从机读产物读**、**数据集 sha 不一致直接报错**。

⚠️ **它不做任何重算**——所有指标都由 `eval_retrieval.py` 计算并写进 `--json` 产物，
本脚本只做"读取 + 排版 + 差异"。重算会立刻制造"同一指标两个口径"的隐患。

用法：
    python backend\\scripts\\compare_runs.py ^
        --run "纯向量=docs/评测报告/retrieval-baseline-l2.json" ^
        --run "RRF=backend/target/eval/grid/metrics-rrf-rk30.json" ^
        --ks 1,5,10,30 --split all

退出码：0 = 对照成立；1 = 结构或口径问题（如数据集不一致、缺指标）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[2]

# 对照表里要看的指标（顺序 = 排版顺序）。⚠️ 严格族与可答族**分开列**，
# 因为我们明确禁止把两族混成一个数字（见 C-29）。
STRICT_METRICS = ("HitRate@{k}", "Recall@{k}")
ANSWERABLE_METRICS = ("answerableRate@{k}",)
SCALAR_METRICS = ("Precision@m", "MRR@{k}", "answerableTop1", "siblingExemptPrecision@{k}")


def load_artifact(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"[FAIL] 找不到指标产物：{path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    for key in ("label", "datasetSha256", "hitsFileSha256", "results"):
        if key not in data:
            # 最常见的误用：把**命中文件**（检索产物）当成了**指标产物**——
            # 两者名字都像"结果 JSON"，但前者是 `hits/results[].hits` 的原始排序，
            # 后者才是 `eval_retrieval.py --json` 写出的指标。这里给出下一步命令，
            # 免得只报"缺少键 label"让人去猜（AGENTS.md §8.2 第 3 条：错误信息要能自证）。
            hint = ""
            keys = list(data.keys())
            if "results" in data and isinstance(data.get("results"), list) and data["results"] \
                    and "hits" in (data["results"][0] if isinstance(data["results"][0], dict) else {}):
                hint = ("\n       ⚠️ 这看起来是**命中文件**（含 results[].hits），不是指标产物。\n"
                        "       请先跑：python backend\\scripts\\eval_retrieval.py --hits <命中文件> "
                        "--k 1,3,5,8,10,20,30 --label \"<标签>\" --json <指标产物.json>\n"
                        "       再把 --json 写出的**指标产物**传给本脚本。")
            elif "label" not in data:
                hint = (f"\n       现有键：{keys}\n"
                        "       ⚠️ 缺 label 通常是跑 eval_retrieval.py 时漏了 --label，"
                        "请补上（标签会出现在对照表列头，无标签的表无法引用）。")
            raise SystemExit(f"[FAIL] {path.name} 缺少键 {key}——不是 eval_retrieval.py 的指标产物？{hint}")
    return data


def cell(group: dict, key: str, ks: tuple[int, ...]) -> str | None:
    """取单元格值。`{k}` 占位符对每个 k 展开成一个独立指标名。"""
    if "{k}" in key:
        return None  # 由调用方逐 k 处理
    return f"{group[key]:.4f}" if key in group else None


def disp_width(text: str) -> int:
    """终端显示宽度：CJK 与全角符号占 2 列。

    ⚠️ 用 `len()` 排版中文表头会**对不齐**（`len('指标') == 2` 但占 4 列），
    这是本项目文档里出现过多次的小坑；表格用 Markdown 渲染时无影响，但终端输出会歪。
    """
    return sum(2 if ord(ch) > 0x2E80 else 1 for ch in text)


def pad(text: str, width: int) -> str:
    return text + " " * max(0, width - disp_width(text))



def split_metrics(doc: dict, split: str) -> dict:
    """把 --split 的名字解析到正确的指标块。

    ⚠️ 这里曾经是错的（2026-09-21 实测修）：dev / holdout 两个选项**写在 --help 里**，
    代码却一律去取 `results[split]`，而 `results` 只有
    `all / byQuestionType / bySplit / byTopicClass / exclFlaggedNote / exclKnownLimitation` ——
    于是 `--split dev` 直接 KeyError 崩掉。
    这类"帮助里写着支持、实际不可用"的洞必须显式修：**保留集纪律要靠 `--split dev` 才守得住**，
    它一旦不可用，使用者就会退回看 `all`（含 holdout）的那张表，还以为自己在看 dev。
    """
    results = doc.get("results", {})
    if split in ("dev", "holdout"):
        by_split = results.get("bySplit") or {}
        if split not in by_split:
            raise SystemExit(f"[FAIL] 产物里没有 bySplit.{split}（该产物可能过旧或生成时未分片）")
        return by_split[split]
    if split not in results:
        raise SystemExit(f"[FAIL] 产物里没有 results.{split}（可选：{', '.join(sorted(results))}）")
    return results[split]


def render(runs: list[tuple[str, dict]], ks: tuple[int, ...], split: str) -> int:
    base_sha = runs[0][1]["datasetSha256"]
    bad = [(lbl, d["datasetSha256"]) for lbl, d in runs if d["datasetSha256"] != base_sha]
    if bad:
        print("[FAIL] 各运行用的**不是同一份数据集**，对照无意义（AGENTS.md §6 第 2 条「每轮只改少量变量」）：")
        for lbl, sha in [(runs[0][0], base_sha)] + bad:
            print(f"  {lbl}: {sha[:16]}…")
        return 1
    print(f"数据集 sha256 = {base_sha[:16]}…（{len(runs)} 个运行，全部同一数据集 ✓）")
    print("⚠️ 严格口径（只算 gold，**下界**）与可答口径（含等价块）分列，**不得混用**；")
    print("   保留集（holdout）不参与调参，只在定稿时看。\n")

    width = max(disp_width(lbl) for lbl, _ in runs) + 2
    print("| " + pad("指标", width + 4) + "| " + " | ".join(pad(lbl, width) for lbl, _ in runs) + " |")
    print("|" + "-" * (width + 6) + "|" + "|".join("-" * (width + 2) for _ in runs) + "|")

    def line(label: str, values: list[str]) -> None:
        print("| " + pad(label, width + 4) + "| " + " | ".join(v + " " * max(0, width - disp_width(v)) for v in values) + " |")

    for key in STRICT_METRICS:
        for k in ks:
            name = key.format(k=k)
            line(f"[严格] {name}", [cell(split_metrics(d, split), name, ks) or "—" for _, d in runs])
    for key in ANSWERABLE_METRICS:
        for k in ks:
            name = key.format(k=k)
            line(f"[可答] {name}", [cell(split_metrics(d, split), name, ks) or "—" for _, d in runs])
    for key in SCALAR_METRICS:
        name = key.format(k=max(ks))
        line(f"[{ '可答' if key.startswith(('answerable','sibling')) else '严格' }] {name}",
             [cell(split_metrics(d, split), name, ks) or "—" for _, d in runs])

    # 相对第一个运行的增量：第 6 项要回答的正是"融合/重排到底有没有用"
    print(f"\n增量（相对「{runs[0][0]}」，正数 = 更好）：")
    first = split_metrics(runs[0][1], split)
    # 产物 label 撞车 = 生成时没传 --label（吃了 eval_retrieval.py 的默认值）；按症状判，不抄默认值
    _inner = [d.get("label") for _, d in runs]
    dup_labels = {l for l in _inner if l and _inner.count(l) > 1}
    for cli_label, d in runs[1:]:
        cur = split_metrics(d, split)
        parts = []
        for k in ks:
            n = f"HitRate@{k}"
            if n in first and n in cur:
                parts.append(f"@{k} {cur[n] - first[n]:+.3f}")
        for n in ("answerableRate@" + str(max(ks)), "answerableTop1", "MRR@" + str(max(ks))):
            if n in first and n in cur:
                parts.append(f"{n} {cur[n] - first[n]:+.4f}")
        # ⚠️ 印「命令行标签」，并对"产物 label 撞车"的行点名——
        #    产物 label 由 eval_retrieval.py 的 --label 决定，缺省会**全部写死成同一个默认值**；
        #    若这里印产物 label，多路对照会**全部印成同一个名字**，把增量归到错误的行上（本轮已实测）。
        #    ⛔ 不用"与命令行标签不一致"判告警：命令行短标签与产物描述性标签不同是**正常的**，
        #       对所有差异告警会变成"狼来了"。⛔ 也不在本文件里抄一份默认值常量（两处会分叉）。
        #    ✅ 只按**症状**判：两路以上的产物 label **相同** ⇒ 只可能是没传 --label 吃了默认值。
        inner = d.get("label")
        note = ""
        if inner and inner in dup_labels:
            note = (f"  ⚠️ 产物内 label=「{inner}」与另一路的产物 label **相同** ⇒ "
                    f"生成时没传 --label（吃了默认值）；别用 label 认这份产物")
        print(f"  {cli_label}: " + " ｜ ".join(parts) + note)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="跨运行检索指标对照（只读机读产物，不重算）")
    ap.add_argument("--run", action="append", required=True, metavar="标签=指标产物.json",
                    help="可重复；第一个作为增量基准")
    ap.add_argument("--ks", default="5,10,20,30")
    ap.add_argument("--split", default="all", choices=["all", "exclKnownLimitation", "dev", "holdout"])
    ap.add_argument("--json", type=Path, default=None, help="同时写出对照的机读副本")
    args = ap.parse_args()

    ks = tuple(sorted({int(x) for x in args.ks.split(",") if x.strip()}))
    runs: list[tuple[str, dict]] = []
    for spec in args.run:
        if "=" not in spec:
            raise SystemExit(f"[FAIL] --run 需要「标签=路径」形式，收到：{spec}")
        label, path = spec.split("=", 1)
        runs.append((label, load_artifact(Path(path))))

    top_ks = {d["results"]["topK"] if "topK" in d["results"] else d.get("topK") for _, d in runs}
    if any(t is not None and max(ks) > t for t in top_ks):
        print(f"[警告] 请求的 k={max(ks)} 超过某个运行的 topK={sorted(t for t in top_ks if t)}，"
              f"缺的格子会显示 —（不是 0）")

    rc = render(runs, ks, args.split)
    if args.json:
        payload = {
            "datasetSha256": runs[0][1]["datasetSha256"],
            "split": args.split,
            "ks": list(ks),
            "runs": [{"label": lbl,
                      "channel": d.get("channel"),
                      "hitsFileSha256": d["hitsFileSha256"],
                      "labelRaw": d.get("label"),
                      "metrics": {k: v for k, v in split_metrics(d, args.split).items()}} for lbl, d in runs],
        }
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8", newline="\n")
        print(f"\n机读副本已写入：{args.json}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
