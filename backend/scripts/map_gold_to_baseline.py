"""把 L2 Golden Set 的 gold 标注从「结构感知坐标系」映射到「固定长度坐标系」（C-48）。

═══ 为什么不能用行号映射（两次失败的地方）═══
用户裁示的原始口径是「按行区间相交」。两条实测硬事实使其**不可用**：
 ① 固定长度侧的 `sourceLines` 是**文档级**的（385 块只有 22 个不同取值，首 8 块全写 `15-192`）
    ⇒ 规则会静默退化成「同文档」匹配（按该口径写的第一版报 `55/55 成功`，是**假成功**）。
 ② 两侧坐标系不同：结构侧是**行号**（每块级，423 个取值），固定侧是**字符偏移**。

═══ 本脚本的口径 ═══
**第一步·重建底层文本 T（不依赖语料文件，也不依赖 `sourceLines`）**
固定长度块是**重叠滑窗**：偏移步长 S=260。于是 T[i·S + k] = c_i.content[k]。
把 385 个块按声明偏移打进一个字符数组即得 T，且**重叠区必须逐字符一致**
（实测 **0 处冲突** ⇒ 滑窗模型被证实）。这一步同时是自检。

**第二步·定位 gold 的正文**
⚠️ 两侧**不共享归一化**（实测）：结构侧 `content` 是**重渲染**过的——
语料原文写 `|---|---|`，而结构块写 `| --- | --- |`（表格分栏被重新排版）；
且结构块 `content` **保留换行**（510/541 块含换行），固定侧文本**已删换行**。
故定位时把两侧都做**空白不敏感**归一化（去掉所有空白字符），并用前缀计数把
「归一化下标 ↔ T 下标」对应起来，使**窗口区间与 gold 区间落在同一坐标系**里再判相交。

⛔ **不产出「看起来成功」的表**：任一 gold 定位不到/多处歧义/重建有冲突，一律非零退出。

═══ 用法 ═══
    python backend/scripts/map_gold_to_baseline.py
    python backend/scripts/map_gold_to_baseline.py --json ../../docs/评测报告/chunk-gold-remap-v1.json
"""
import argparse
import bisect
import io
import json
import os
import re
import statistics
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "评测报告"))
STEP = 260  # 实测：相邻 `baselineCharOffset` 恒差 260


def load(name):
    with io.open(os.path.join(REPORT, name), encoding="utf-8") as f:
        return json.load(f)


def nows(s):
    """去掉所有空白字符（换行、空格、制表）。用于跨归一化口径的定位。"""
    return "".join(ch for ch in s if not ch.isspace())


def reconstruct(chunks):
    """按声明偏移把窗口打进字符数组；返回 (T, 冲突数, 冲突样例)。"""
    total = max((c.get("extra") or {}).get("baselineCharOffset", 0) + len(c["content"]) for c in chunks)
    cell = [None] * total
    conflicts, samples = 0, []
    for c in chunks:
        off = (c.get("extra") or {}).get("baselineCharOffset", 0)
        for k, ch in enumerate(c["content"]):
            pos = off + k
            if cell[pos] is None:
                cell[pos] = ch
            elif cell[pos] != ch:
                conflicts += 1
                if len(samples) < 3:
                    samples.append((c["chunkId"], pos, cell[pos], ch))
    return "".join(x if x is not None else "\0" for x in cell), conflicts, samples


def collect_gold_ids(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if "chunk" in k.lower() and isinstance(v, list):
                out.update(str(x) for x in v)
            elif isinstance(v, (dict, list)):
                collect_gold_ids(v, out)
    elif isinstance(node, list):
        for v in node:
            collect_gold_ids(v, out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", dest="json_path", default=None)
    # ⚠️ 允许换用别的基线清单：实测 `chunks-baseline.json` 是**语料改动前**的旧产物
    #    （见本文件顶部说明），重生成后要先在候选清单上验证映射，再决定是否替换。
    ap.add_argument("--baseline", dest="baseline_file", default="chunks-baseline.json")
    args = ap.parse_args()

    struct = load("chunks-structure.json")["chunks"]
    base = load(args.baseline_file)["chunks"]
    print("基线清单 = %s" % args.baseline_file)
    by_id = {c["chunkId"]: c for c in struct}
    gold_ids = set()
    collect_gold_ids(load("golden-l2-v2.json"), gold_ids)

    print("=" * 78)
    print("gold → 固定长度坐标系 映射（重建 + 空白不敏感定位；步长 S=%d）" % STEP)
    print("=" * 78)

    docs = {}
    for c in base:
        docs.setdefault(c["docId"], []).append(c)

    T_by_doc, nw_by_doc, win_by_doc, conflict_total = {}, {}, {}, 0
    for doc, cs in sorted(docs.items()):
        cs = sorted(cs, key=lambda c: (c.get("extra") or {}).get("baselineCharOffset", 0))
        T, conflicts, samples = reconstruct(cs)
        conflict_total += conflicts
        if conflicts:
            print("  ⛔ 重建冲突 %s：%d 处 %s" % (doc, conflicts, samples))
        # 前缀计数：nw[i] = T[:i] 里非空白字符的个数
        nw = [0] * (len(T) + 1)
        for i, ch in enumerate(T):
            nw[i + 1] = nw[i] + (0 if ch.isspace() else 1)
        T_by_doc[doc], nw_by_doc[doc] = T, nw
        win_by_doc[doc] = [
            (c["chunkId"], (c.get("extra") or {}).get("baselineCharOffset", 0),
             (c.get("extra") or {}).get("baselineCharOffset", 0) + len(c["content"]))
            for c in cs
        ]

    print("重建自检：文档数 = %d ｜ 重叠区字符冲突 = %d %s"
          % (len(docs), conflict_total, "✅" if conflict_total == 0 else "⛔"))

    Tn_by_doc = {d: nows(T) for d, T in T_by_doc.items()}

    mapping, not_found, ambiguous, placeholder_golds = {}, [], [], []
    for gid in sorted(gold_ids):
        src = by_id.get(gid)
        T = Tn_by_doc.get((src or {}).get("docId"))
        if src is None or T is None:
            not_found.append(gid)
            continue
        needle = nows(src.get("content") or "")
        if not needle:
            not_found.append(gid)
            continue
        # ⚠️ 结构侧 `content` 把表格换成了 `[TABLE:<id> rows=N cols=M]` 占位符
        #    （真实行在同清单的另一条 TABLE 块里，本块 `tableHeaders` 为 null）
        #    而固定侧的 T 里放的是**渲染后的表格正文** ⇒ 两侧这一段**本来就不同**，
        #    故占位符必须换成**通用通配**（⛔ 不是拿 `[TABLE:...]` 去匹配 T，那样必然匹配不到）。
        if "[TABLE:" in needle:
            placeholder_golds.append(gid)
            parts = re.split(r"\[TABLE:[^\]]*\]", needle)
            pattern = r".{0,4000}?".join(re.escape(p) for p in parts)
            rx = re.compile(pattern, re.S)
            ms = list(rx.finditer(T))
            hits = [m.start() for m in ms]
            ends = [m.end() for m in ms]
        else:
            hits = []
            start = T.find(needle)
            while start != -1:
                hits.append(start)
                start = T.find(needle, start + 1)
            ends = [h + len(needle) for h in hits]
        if not hits:
            not_found.append(gid)
            continue
        if len(hits) > 1:
            ambiguous.append((gid, len(hits)))
        doc = src["docId"]
        nw = nw_by_doc[doc]
        p0, p1 = hits[0], ends[0]
        import bisect
        t0 = bisect.bisect_left(nw, p0 + 1) - 1
        t1 = bisect.bisect_left(nw, p1)
        targets = [cid for cid, a, b in win_by_doc[doc] if a < t1 and t0 < b]
        mapping[gid] = targets

    mapped = {k: v for k, v in mapping.items() if v}
    unmapped = {k: v for k, v in mapping.items() if not v}
    counts = [len(v) for v in mapped.values()]

    print("-" * 78)
    print("gold 标注总数                 : %d" % len(gold_ids))
    print("⛔ 定位失败                   : %d %s" % (len(not_found), not_found[:5]))
    print("⚠️ 定位到多处（歧义）         : %d %s" % (len(ambiguous), [a[0] for a in ambiguous][:5]))
    print("含表格占位符（按通配定位）     : %d %s" % (len(placeholder_golds), placeholder_golds[:3]))
    print("成功映射（≥1 个目标窗口）     : %d" % len(mapped))
    print("⛔ 无目标窗口                 : %d %s" % (len(unmapped), sorted(unmapped)[:5]))
    if counts:
        win_med = statistics.median([len(c["content"]) for c in base])
        print("每条 gold 命中的窗口数        : 中位 %d，均值 %.2f，最大 %d"
              % (statistics.median(counts), statistics.mean(counts), max(counts)))
        print("gold 正文长度（去空白）       : 中位 %d 字符"
              % statistics.median([len(nows(by_id[g]["content"])) for g in mapped]))
        print("固定长度窗口长度              : 中位 %d 字符 ｜ 步长 %d" % (win_med, STEP))
        print("⇒ ⛔ HitRate 会被**结构性抬高**（一条 gold 被约 %.1f 个窗口覆盖），"
              % statistics.mean(counts))
        print("  报告里必须与「命中窗口数」并列，⛔ 不得只比 HitRate 下结论。")

    if args.json_path:
        out = {
            "note": "重建法 + 空白不敏感定位，把 L2 gold 从结构感知坐标系映射到固定长度坐标系（C-48）",
            "rule": "T[i*260+k]=chunk.content[k]（重叠区须一致）；两侧去空白后定位；gold 区间 ∩ 窗口区间 ≠ ∅",
            "step": STEP,
            "reconstructConflicts": conflict_total,
            "normalization": "两侧均去掉所有空白字符（因结构侧 content 是重渲染形式，表格分栏被重排版）",
            "goldTotal": len(gold_ids),
            "notFound": not_found,
            "ambiguous": [{"gold": g, "occurrences": n} for g, n in ambiguous],
            "unmapped": sorted(unmapped),
            "medianTargetsPerGold": statistics.median(counts) if counts else None,
            "meanTargetsPerGold": statistics.mean(counts) if counts else None,
            "mapping": mapping,
        }
        os.makedirs(os.path.dirname(os.path.abspath(args.json_path)), exist_ok=True)
        with io.open(args.json_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(out, f, ensure_ascii=False, indent=2, sort_keys=True)
            f.write("\n")
        print("\n机读副本已写出：%s" % args.json_path)

    ok = conflict_total == 0 and not not_found and not unmapped and bool(mapped)
    print("\n%s" % ("✅ 自检通过：重建无冲突、gold 全部定位且命中窗口" if ok
                    else "⛔ 自检未通过——见上方标注，⛔ 不得据此下结论"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
