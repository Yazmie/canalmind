"""把「固定长度索引」的命中 id 翻译成「结构感知 gold id」，供 `eval_retrieval.py` 出分（C-48）。

═══ 为什么必须有这一步（⛔ 不是多余的一层）═══
`eval_retrieval.py` 是拿 `golden-l2-v2.json` 里的 **gold chunk_id** 去比对命中列表的。
而固定长度索引返回的是 **`base-*` 自己的 id**（两套 id 交集为 **0**）。
⇒ 直接把它喂给出分脚本，**每一题都会 0 命中**——这是**假失败**，
  会被误读成「固定长度切分策略更差」，其实只是**没做 id 翻译**。

翻译依据 = `chunk-gold-remap-v1.json`（重建法 + 空白不敏感定位，55/55，自检 0 冲突）。
本脚本把它**求逆**：`base-* id → 结构 gold id`。

═══ 两个刻意的口径选择（都会在输出里明说，⛔ 不静默）═══
① **不去重**：一条 gold 平均被 **~1.93** 个窗口覆盖，故同一个 gold id 可能在同一题里出现 2 次。
   保留重复 = **忠实反映索引真实返回了什么**（真返回了 2 条），也让命中条数仍是 30，
   不会触发 `eval_retrieval.py` 的「声明条数 vs 实际条数」守卫。
   ⚠️ 代价：`Precision@k` 会被**重复计数抬高**，⛔ **不可与结构感知侧横比**；
   `HitRate@k` / `Recall@k`（集合口径）与 `MRR@k`（取首次出现的名次）不受影响。
② **未命中的窗口原样保留**：它映射不到任何 gold，就是**真失配**，⛔ 不要替换成别的 id 充数。

═══ 用法 ═══
    python backend/scripts/translate_baseline_hits.py \
        --hits   backend/target/eval/item5/corpus0921-vec-fixedlen.json \
        --out    backend/target/eval/item5/corpus0921-vec-fixedlen-asstruct.json
"""
import argparse
import io
import json
import os
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "评测报告"))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mapping", default=os.path.join(REPORT, "chunk-gold-remap-v1.json"))
    ap.add_argument("--hits", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    with io.open(args.mapping, encoding="utf-8") as f:
        m = json.load(f)
    if m.get("reconstructConflicts") or m.get("notFound") or m.get("unmapped"):
        print("⛔ 映射产物自身未通过自检（%s），拒绝翻译："
              "conflicts=%s notFound=%s unmapped=%s"
              % (args.mapping, m.get("reconstructConflicts"), m.get("notFound"), m.get("unmapped")))
        return 1

    # 求逆：base id → 结构 gold id。⚠️ 若一个窗口同时属于两条 gold，必须报出来（歧义）
    inv = {}
    ambiguous = []
    for gold, targets in m["mapping"].items():
        for t in targets:
            if t in inv and inv[t] != gold:
                ambiguous.append((t, inv[t], gold))
            inv[t] = gold

    with io.open(args.hits, encoding="utf-8") as f:
        hits_doc = json.load(f)

    total = mapped = dup = 0
    per_query_dups = Counter()
    for r in hits_doc["results"]:
        for h in r["hits"]:
            total += 1
            g = inv.get(h["chunkId"])
            if g is not None:
                h["chunkId"] = g
                mapped += 1
        ids = [h["chunkId"] for h in r["hits"]]
        d = len(ids) - len(set(ids))
        if d:
            per_query_dups[r["query"]] = d
            dup += d

    print("命中条目总数       : %d" % total)
    print("成功翻译为结构 gold : %d（%.1f%%）" % (mapped, 100.0 * mapped / total if total else 0.0))
    print("未命中窗口（原样保留，算失配）: %d" % (total - mapped))
    print("同一题内出现重复 gold id 的次数: %d（涉及 %d 题）" % (dup, len(per_query_dups)))
    if ambiguous:
        print("⚠️ 一个窗口同属多条 gold 的歧义: %d 处 %s" % (len(ambiguous), ambiguous[:3]))
    if per_query_dups:
        worst = per_query_dups.most_common(3)
        print("  重复最多的题: %s" % "; ".join("%s…×%d" % (q[:16], c) for q, c in worst))
        print("  ⚠️ 故 `Precision@k` 被重复计数抬高，⛔ 不可与结构感知侧横比；"
              "HitRate/Recall（集合口径）与 MRR（首次名次）不受影响。")

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(hits_doc, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    print("\n已写出翻译后的命中：%s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
