"""校验多轮改写子集 `golden-rewrite-dev-v1.json`（C-50）。

它守护四件**会静默出错**的事：
1. **gold 不是手抄的**——逐条比对 `goldFrom` 指向的 L2 条目，gold 与 acceptable **必须完全相同**。
   手抄 gold 是本项目出过事的地方（标注错误曾被写成系统的"已知限制"）。
2. **没有复用 L2 题面**——C-50 方案明确"题面自写"；若 turns 里出现 L2 原题面，这里直接报错。
3. **没有碰保留集**——本子集必须 100% dev，且 `goldFrom` 也不得指向 L2 的 holdout。
4. **省略是真省略**——末轮问句必须短于 `standaloneQuery` 且标注了被省略/指代的内容；
   否则"改写增益"这个实验测的东西就不存在。

用法：python backend/scripts/verify_rewrite_set.py [--selftest]
退出码 0 = 通过。
"""
import argparse
import io
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RW = os.path.join(ROOT, "docs", "评测报告", "golden-rewrite-dev-v1.json")
L2 = os.path.join(ROOT, "docs", "评测报告", "golden-l2-v2.json")

errors = []
warns = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warns.append(msg)


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    d = load(RW)
    l2 = load(L2)
    l2_items = {it["id"]: it for it in l2["items"]}
    l2_questions = {it["question"] for it in l2["items"]}

    items = d["items"]

    # ---- 0. 顶层
    if d.get("decision") != "C-50":
        err("顶层 decision 应为 C-50，实际 %r" % d.get("decision"))
    if d["design"].get("totalItems") != len(items):
        err("design.totalItems=%s 与实际条数=%d 不一致"
            % (d["design"].get("totalItems"), len(items)))
    if len(items) != 16:
        err("应为 16 条，实际 %d 条" % len(items))

    # ---- 1. id 连续唯一
    expect = ["RW-%03d" % i for i in range(1, len(items) + 1)]
    got = [it["id"] for it in items]
    if got != expect:
        err("id 不是 RW-001..RW-%03d 连续序列" % len(items))
    if len(set(got)) != len(got):
        err("id 有重复")

    # ---- 2. 逐条
    for it in items:
        i = it["id"]
        if it.get("split") != "dev":
            err("%s split=%r，本子集必须是 dev（⛔ 不碰保留集）" % (i, it.get("split")))

        turns = it.get("turns") or []
        if len(turns) != 2:
            err("%s 应有 2 轮，实际 %d 轮" % (i, len(turns)))
            continue
        if [t["turn"] for t in turns] != [1, 2]:
            err("%s turn 编号应为 1,2，实际 %s" % (i, [t["turn"] for t in turns]))

        raw = turns[1]["question"]
        sa = it.get("standaloneQuery") or ""

        # 3. 不得复用 L2 题面
        for t in turns:
            if t["question"] in l2_questions:
                err("%s turn%d 与某条 L2 题面完全相同 ⇒ 违反 C-50「题面自写」"
                    % (i, t["turn"]))
        if sa in l2_questions:
            warn("%s standaloneQuery 与某条 L2 题面完全相同（这是**标注**不是题面，"
                 "允许；但须在报告里声明）" % i)

        # 4. 省略必须是真的
        el = it.get("ellipsis") or {}
        if not el.get("dropped"):
            err("%s 未标注被省略/指代的内容 ⇒ 无法证明它是省略问句" % i)
        if el.get("kind") not in ("省略", "指代"):
            err("%s ellipsis.kind=%r 应为「省略」或「指代」" % (i, el.get("kind")))
        if len(sa) <= len(raw):
            err("%s standaloneQuery 未长于原始问句（%d vs %d）⇒ 该条测不出改写收益"
                % (i, len(sa), len(raw)))
        if sa == raw:
            err("%s standaloneQuery 与原始问句相同" % i)

        # 5. gold 必须与 L2 来源逐字一致（⛔ 不许手抄）
        src = it.get("goldFrom")
        lit = l2_items.get(src)
        if lit is None:
            err("%s goldFrom=%r 在 golden-l2-v2.json 里不存在" % (i, src))
            continue
        if lit.get("split") != "dev":
            err("%s goldFrom=%s 指向的是 %s ⇒ 等于借道碰了保留集"
                % (i, src, lit.get("split")))
        if list(it.get("goldChunkIds") or []) != list(lit.get("goldChunkIds") or []):
            err("%s goldChunkIds 与 %s 不一致 ⇒ gold 被手改过（本子集要求原样复用）"
                % (i, src))
        if list(it.get("acceptableChunkIds") or []) != list(lit.get("acceptableChunkIds") or []):
            err("%s acceptableChunkIds 与 %s 不一致" % (i, src))
        if not it.get("goldChunkIds"):
            err("%s goldChunkIds 为空" % i)

        # 6. 题型/主题应与来源一致（便于按题型分层报告）
        if it.get("questionType") != lit.get("questionType"):
            warn("%s questionType=%r 与其来源 %s 的 %r 不同（可接受，但分层报告要说明）"
                 % (i, it.get("questionType"), src, lit.get("questionType")))
        if it.get("topicClass") != lit.get("topicClass"):
            warn("%s topicClass=%r 与来源 %s 的 %r 不同"
                 % (i, it.get("topicClass"), src, lit.get("topicClass")))

    # ---- 汇总
    print("=" * 78)
    print("多轮改写子集校验（C-50）：%s" % os.path.relpath(RW, ROOT))
    print("条数 %d ｜ 全部 dev ｜ goldFrom 来源 %d 个不同 L2 条目"
          % (len(items), len({it.get("goldFrom") for it in items})))
    by_type = {}
    for it in items:
        by_type[it.get("questionType")] = by_type.get(it.get("questionType"), 0) + 1
    print("题型分布：" + "、".join("%s %d" % (k, v) for k, v in sorted(by_type.items())))
    kinds = {}
    for it in items:
        k = (it.get("ellipsis") or {}).get("kind")
        kinds[k] = kinds.get(k, 0) + 1
    print("省略类型：" + "、".join("%s %d" % (k, v) for k, v in sorted(kinds.items())))
    print("-" * 78)
    for w in warns:
        print("  [警告] " + w)
    for e in errors:
        print("  [错误] " + e)
    print("-" * 78)
    print("错误 %d 项 ｜ 警告 %d 项" % (len(errors), len(warns)))
    if errors:
        print("[FAIL] 存在问题，请修正后重跑")
        return 1
    print("[PASS] 多轮改写子集自检通过")
    return 0


def selftest():
    """自证校验器本身会报错：临时把一条 gold 改坏，必须被抓住。"""
    d = load(RW)
    orig = d["items"][0]["goldChunkIds"]
    d["items"][0]["goldChunkIds"] = ["deadbeefdeadbeef"]
    tmp = RW + ".selftest.json"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    try:
        globals()["RW"] = tmp
        rc = main()
    finally:
        os.remove(tmp)
    if rc == 0:
        print("[FAIL] 自证失败：gold 被改坏后校验器仍然通过")
        return 1
    print("[PASS] 自证通过：gold 被改坏时校验器确实报错")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="自证校验器会抓住被改坏的 gold")
    a = ap.parse_args()
    sys.exit(selftest() if a.selftest else main())
