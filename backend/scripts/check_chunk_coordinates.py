"""核验「Chunk 策略」消融臂所需的**坐标系统**是否具备（⛔ 不做映射，只做核验与拦截）。

═══ 这个脚本为什么存在（一次真实的假成功）═══
C-48 批准「补 Chunk 策略检索指标」后，我按用户裁示的口径「**按行区间把 gold 重映射**到固定长度块」
写了一个映射脚本，它报了 **55/55 全部映射成功**——**但那是假的**：
固定长度侧的 `sourceLines` 是**文档级**的（385 块只有 22 个不同取值，首 8 块全写着 `15-192`），
于是"同文档且行区间相交"这个规则**静默退化成了"同文档"**，把每条 gold 都映射到平均 **14.69** 个块。
⛔ **这是"假 PASS"的又一例：规则退化了，但输出长得完全正常。**

⇒ 本脚本的作用是**把这个退化变成硬失败**：先核验两侧到底有没有**每块各不相同**的坐标，
没有就**非零退出**并说清缺什么。⛔ 它**不产出任何映射表**——避免再次出现"看起来成功的半成品"。

═══ 用法 ═══
    python backend/scripts/check_chunk_coordinates.py
    退出码 0 = 两侧都有可用的 per-chunk 坐标；1 = 不可比（当前实测即此）
"""
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.normpath(os.path.join(HERE, "..", "..", "docs", "评测报告"))


def load(name):
    with open(os.path.join(REPORT, name), encoding="utf-8") as f:
        return json.load(f)


def summarize(name, chunks):
    distinct_lines = len({str(c.get("sourceLines")) for c in chunks})
    extra_keys = sorted({k for c in chunks for k in (c.get("extra") or {})})
    # 关键判据：坐标必须**每块各不相同**才可用于映射；退化值（如文档级行号）不可用
    per_chunk_lines = distinct_lines > len(chunks) * 0.5
    print("── %s" % name)
    print("   块数                      : %d" % len(chunks))
    print("   sourceLines 不同取值      : %d  ⇒ %s"
          % (distinct_lines, "✅ 每块级（可用于映射）" if per_chunk_lines else "⛔ 文档级（不可用于映射）"))
    print("   sourceLines 样例          : %s" % sorted({str(c.get('sourceLines')) for c in chunks})[:4])
    print("   extra 键                  : %s" % (extra_keys or "（空）"))
    return per_chunk_lines, extra_keys


def main():
    struct = load("chunks-structure.json")["chunks"]
    base = load("chunks-baseline.json")["chunks"]

    print("=" * 76)
    print("Chunk 消融臂｜两侧坐标系统核验（C-48 实施前置）")
    print("=" * 76)
    struct_ok, _ = summarize("结构感知（gold 标注所在侧）", struct)
    base_ok, base_extra = summarize("固定长度基线", base)

    print("-" * 76)
    print("判读：")
    if struct_ok and base_ok:
        print("  ✅ 两侧都有 per-chunk 行号 ⇒ 可**直接**按行区间相交映射。")
        return 0

    print("  ⛔ **两侧坐标系不同，不能直接按行区间映射**：")
    print("     · 结构感知侧 = **行号**（每块不同）")
    print("     · 固定长度侧 = **字符偏移**（`%s`；其 `sourceLines` 是**文档级**的，无用）"
          % (", ".join(base_extra) or "无"))
    print()
    print("  ⇒ 要做这条消融臂，必须先把结构感知侧的**行号换算成字符偏移**")
    print("     （拿 `docs/规则语料/*.md` 原文按行累加），即**引入第三次坐标换算**。")
    print("     ⚠️ 该换算不是纯机械操作：语料的换行/BOM/归一化口径会直接影响偏移量，")
    print("        而本项目在归一化上**已经踩过三次静默损坏**（见工程复盘）。")
    print()
    print("  ⛔ 本脚本**不产出映射表**：宁可让这一步显式失败，也不要再产出一张")
    print("     '看起来成功、其实退化成了同文档匹配' 的表。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
