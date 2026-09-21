"""由多轮改写子集派生 Query Rewrite 消融的 A/B 两臂输入（C-50，零付费）。

两臂：
  A-noRewrite   —— 末轮原始省略问句（不做改写）
  B-humanRewrite—— 人工标注的 standaloneQuery（改写的上界）

产出（都落在 `backend/target/eval/rewrite/`，**可由本脚本随时重生成**）：
  armA-queries.txt / armB-queries.txt     —— RetrievalQueryCli --query-file 用（UTF-8、每行一条）
  golden-rewrite-armA.json / armB.json    —— 派生数据集，`question` 换成该臂的查询，
                                             其余（gold / 题型 / 主题 / split）原样，
                                             供 `eval_retrieval.py --dataset` 复用其**既有口径**，
                                             ⛔ 不另写一套指标代码。

⛔ 只读子集，不改它；⛔ 不调用任何付费 API。
用法：python backend/scripts/emit_rewrite_arms.py [--print]
"""
import argparse
import io
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RW = os.path.join(ROOT, "docs", "评测报告", "golden-rewrite-dev-v1.json")
OUT = os.path.join(ROOT, "backend", "target", "eval", "rewrite")

# 派生数据集里只保留 eval_retrieval.py 需要的字段 —— 少放字段就少一处漂移来源
KEEP = ("id", "questionType", "topicClass", "split", "goldChunkIds", "acceptableChunkIds")


def arm_query(it, arm):
    return it["turns"][1]["question"] if arm == "A" else it["standaloneQuery"]


def write_text(path, lines):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--print", dest="show", action="store_true", help="打印两臂查询对照")
    a = ap.parse_args()

    rw = json.load(io.open(RW, encoding="utf-8"))
    items = rw["items"]
    os.makedirs(OUT, exist_ok=True)

    for arm in ("A", "B"):
        queries = [arm_query(it, arm) for it in items]

        # 查询文件：每行一条；# 开头为注释（RetrievalQueryCli 的约定）
        write_text(os.path.join(OUT, "arm%s-queries.txt" % arm),
                   ["# C-50 Query Rewrite 消融 %s 臂：%d 条" % (arm, len(queries))] + queries)

        derived = []
        for it, q in zip(items, queries):
            row = {k: it[k] for k in KEEP if k in it}
            row["id"] = "%s-%s" % (it["id"], arm)
            row["question"] = q
            derived.append(row)

        gold = {
            "datasetId": "canalmind-golden-rewrite-derived",
            "datasetVersion": "v1-派生-%s臂" % arm,
            "layer": "L2-RW-%s" % arm,
            "layerName": "多轮改写消融 %s 臂" % arm,
            "derivedFrom": "docs/评测报告/golden-rewrite-dev-v1.json",
            "derivedBy": "backend/scripts/emit_rewrite_arms.py",
            "derivationRule": ("question := turns[1].question（A 臂，未改写）" if arm == "A"
                               else "question := standaloneQuery（B 臂，人工改写上界）"),
            "warning": ("⚠️ 派生文件，⛔ 不是数据集本身：gold 原样来自多轮改写子集（再上溯到 L2 已核验标注）；"
                        "改子集后必须重跑本脚本，⛔ 不得手工编辑本文件"),
            "items": derived,
        }
        p = os.path.join(OUT, "golden-rewrite-arm%s.json" % arm)
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            json.dump(gold, f, ensure_ascii=False, indent=2)
            f.write("\n")

        print("%s 臂：%d 条 → %s" % (arm, len(queries), os.path.relpath(p, ROOT)))
        print("     查询文件 → %s" % os.path.relpath(os.path.join(OUT, "arm%s-queries.txt" % arm), ROOT))

    if a.show:
        print("=" * 90)
        for it in items:
            print("%s  [%s/%s]  goldFrom=%s" % (it["id"], it["questionType"], it["topicClass"], it["goldFrom"]))
            print("   A 未改写 : %s" % it["turns"][1]["question"])
            print("   B 改写后 : %s" % it["standaloneQuery"])
            print("   被省略   : %s" % "、".join(it["ellipsis"]["dropped"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
