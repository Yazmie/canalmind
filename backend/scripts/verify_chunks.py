"""校验切分产物清单的内部一致性。

为什么需要它
------------
切分器输出 541 个 chunk，其中正文 chunk 里嵌入了指向表格 chunk 的
`[TABLE:<chunkId> rows=N cols=M]` 占位标记。这类"跨对象引用"最容易出现的问题不是报错，
而是**静默指向一个不存在的 id**——清单照样生成、照样是合法 JSON，
但入库后模型永远找不到那张表。

所以按本项目一贯做法：把约定做成机器检查，而不是靠人抽看。

检查项
------
1. 总数自洽：chunk 总数 == 正文数 + 表格数；逐文档求和 == 总数
2. chunkId 唯一、非空
3. 正文中的表格占位标记 **全部指向存在的表格 chunk**，且 rows/cols 与表格实际相符
4. 每个表格 chunk 至少被一个正文 chunk 引用（反向：不存在"孤儿表"）
5. 必填元数据齐全（docId/docNo/version/effectiveFrom/strategy/splitterVersion）
6. 生效期可解析；长期有效为 null；已废止有 effectiveTo
7. chunkType 合法；表格 chunk 必须有 tableHeaders/tableRows 且列数一致
8. charCount 与 content 的非空白字符数一致（口径审计）

用法
----
    python backend/scripts/verify_chunks.py docs/评测报告/chunks-structure.json
    python backend/scripts/verify_chunks.py docs/评测报告/chunks-baseline.json
"""

import json
import re
import sys
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

MARKER = re.compile(r"\[TABLE:([0-9a-f]+) rows=(\d+) cols=(\d+)\]")
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def non_ws_len(s: str) -> int:
    return sum(1 for ch in s if not ch.isspace())


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--stats"]
    # 默认就打印字长分布——文档里引用的数字必须能由本命令复现（AGENTS.md §6 第 1 条）。
    # 保留 --stats 只是为了兼容旧写法。
    print_stats = True
    if not args:
        print("用法: verify_chunks.py <manifest.json> [--stats]")
        return 2

    path = Path(args[0])
    doc = json.loads(path.read_text(encoding="utf-8"))
    chunks = doc["chunks"]

    print("=" * 68)
    print(f"校验切分产物: {path}")
    print("=" * 68)

    # ── 1. 类型与索引 ────────────────────────────────────────────────
    ids: dict[str, dict] = {}
    tables: dict[str, dict] = {}
    bodies = []
    for c in chunks:
        cid = c.get("chunkId")
        ctype = c.get("chunkType")
        if not cid:
            err(f"{c.get('docId')} {c.get('articleNo')}: chunkId 为空")
            continue
        if cid in ids:
            err(f"chunkId 重复: {cid}")
        ids[cid] = c
        if ctype == "TABLE":
            tables[cid] = c
        elif ctype == "BODY":
            bodies.append(c)
        else:
            err(f"{cid}: 非法 chunkType {ctype!r}")

    # ── 2. 总数自洽 ──────────────────────────────────────────────────
    st = doc["statistics"]
    if st["chunkCount"] != len(chunks):
        err(f"statistics.chunkCount={st['chunkCount']} 与 chunks 实际 {len(chunks)} 不符")
    if st["bodyChunkCount"] != len(bodies):
        err(f"bodyChunkCount={st['bodyChunkCount']} 与实际正文 {len(bodies)} 不符")
    if st["tableChunkCount"] != len(tables):
        err(f"tableChunkCount={st['tableChunkCount']} 与实际表格 {len(tables)} 不符")
    if st["bodyChunkCount"] + st["tableChunkCount"] != st["chunkCount"]:
        err("正文数 + 表格数 != 总数")
    doc_sum = sum(d["bodyChunkCount"] + d["tableChunkCount"] for d in doc["documents"])
    if doc_sum != len(chunks):
        err(f"逐文档求和 {doc_sum} != chunk 总数 {len(chunks)}")
    print(f"  总数自洽        : {len(chunks)} = 正文 {len(bodies)} + 表格 {len(tables)}；逐文档求和 {doc_sum}")

    # ── 3. 占位标记 → 表格 chunk 引用 ────────────────────────────────
    referenced: set[str] = set()
    marker_count = 0
    for b in bodies:
        for m in MARKER.finditer(b.get("content") or ""):
            marker_count += 1
            tid, rows, cols = m.group(1), int(m.group(2)), int(m.group(3))
            if tid not in tables:
                err(f"{b['chunkId']}（{b.get('docNo')} {b.get('articleNo')}）的表格标记指向不存在的 chunk: {tid}")
                continue
            referenced.add(tid)
            t = tables[tid]
            actual_rows = len(t.get("tableRows") or [])
            actual_cols = len(t.get("tableHeaders") or [])
            if rows != actual_rows:
                err(f"标记 rows={rows} 与表格实际 {actual_rows} 不符 ({tid})")
            if cols != actual_cols:
                err(f"标记 cols={cols} 与表格实际 {actual_cols} 不符 ({tid})")
    print(f"  表格占位标记    : {marker_count} 处，覆盖 {len(referenced)}/{len(tables)} 个表格 chunk")

    # ── 3b. 字长分布（可复现：文档里引用的数字必须能由本命令重新算出）────
    # ⚠️ 这是刻意加的：`进度看板.md` 与《评测报告》里引用了均值/标准差等数字，
    # 而"引用了数字却给不出复现命令"违反 AGENTS.md §6 第 1 条（每个指标都要有出处）。
    if print_stats:
        lens = sorted(c.get("charCount") or 0 for c in bodies)
        if lens:
            n = len(lens)
            mean = sum(lens) / n
            var = sum((x - mean) ** 2 for x in lens) / n
            stdev = var ** 0.5
            median = lens[n // 2] if n % 2 else (lens[n // 2 - 1] + lens[n // 2]) / 2
            print(f"  正文字长分布    : n={n} 均值={mean:.2f} 中位={median:.0f} "
                  f"最小={lens[0]} 最大={lens[-1]} 标准差={stdev:.2f}")
            buckets = [(0, 50), (50, 100), (100, 200), (200, 400), (400, 800), (800, 10 ** 9)]
            hist = []
            for lo, hi in buckets:
                cnt = sum(1 for x in lens if lo <= x < hi)
                label = f"[{lo},{hi})" if hi < 10 ** 9 else f"[{lo},∞)"
                hist.append(f"{label}={cnt}")
            print(f"  直方图          : " + " ".join(hist))
            # 语料是否真的用到"超长条再拆分"的能力——没用到就该如实说明
            print(f"  超过 800 字的条 : {sum(1 for x in lens if x > 800)}"
                  f"（0 表示真实语料未触发阈值下切，该能力仅由合成用例覆盖）")

    # ── 4. 孤儿表格 ──────────────────────────────────────────────────
    for tid, t in tables.items():
        if tid not in referenced:
            err(f"表格 chunk 无任何正文引用（孤儿表）: {tid}  {t.get('docNo')} {t.get('articleNo')}")

    # ── 5/6. 必填元数据与生效期 ──────────────────────────────────────
    for c in chunks:
        for f in ("docId", "docTitle", "docNo", "version", "effectiveFrom",
                  "strategy", "splitterVersion", "content", "charCount"):
            if c.get(f) in (None, ""):
                err(f"{c.get('chunkId')}: 缺少必填字段 {f}")
        ef = c.get("effectiveFrom")
        if ef and not ISO_DATE.match(ef):
            err(f"{c.get('chunkId')}: effectiveFrom 非 ISO 日期: {ef}")
        et = c.get("effectiveTo")
        if et is not None and not ISO_DATE.match(et):
            err(f"{c.get('chunkId')}: effectiveTo 非 ISO 日期: {et}")
        if ef and et and et < ef:
            err(f"{c.get('chunkId')}: 生效期止 {et} 早于生效期起 {ef}")

    # 已废止文档必须全部带 effectiveTo
    for d in doc["documents"]:
        if d["superseded"] and not d["effectiveTo"]:
            err(f"文档 {d['docId']} 标记为已废止但没有 effectiveTo")

    # ── 7. 表格结构 ──────────────────────────────────────────────────
    for tid, t in tables.items():
        h = t.get("tableHeaders") or []
        rows = t.get("tableRows") or []
        if not h:
            err(f"表格 chunk 无表头: {tid}")
        for i, r in enumerate(rows):
            if len(r) != len(h):
                err(f"表格 {tid} 第 {i+1} 行列数 {len(r)} != 表头 {len(h)}")

    # ── 8. charCount 口径 ────────────────────────────────────────────
    for c in chunks:
        expect = non_ws_len(c.get("content") or "")
        if c.get("charCount") != expect:
            err(f"{c.get('chunkId')}: charCount={c.get('charCount')} 与实际 {expect} 不符")

    # ── 附加：条号与章节路径（结构化策略应有）────────────────────────
    no_article = [c for c in bodies if not c.get("articleNo")]
    if no_article:
        warn(f"{len(no_article)} 个正文 chunk 无 articleNo（基线策略属正常）")
    no_path = [c for c in chunks if not c.get("chapterPath")]
    if no_path:
        warn(f"{len(no_path)} 个 chunk 无 chapterPath（基线策略属正常）")

    print("-" * 68)
    for w in warnings[:10]:
        print(f"  ! {w}")
    if len(warnings) > 10:
        print(f"  ! …另有 {len(warnings)-10} 条警告")
    for e in errors[:25]:
        print(f"  ✗ {e}")
    if len(errors) > 25:
        print(f"  ✗ …另有 {len(errors)-25} 条错误")

    print("-" * 68)
    print(f"错误 {len(errors)} 项，警告 {len(warnings)} 项")
    if errors:
        print("[FAIL] 切分产物存在内部不一致")
        return 1
    print("[PASS] 切分产物内部一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
