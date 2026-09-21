#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语料费率表 ↔ 数据库 `tariff_rule` **逐格一致性**校验
================================================================================

对应决策：`docs/DECISIONS.md` **C-31**（语料为费率口径的唯一权威源）第 ⑤ 条。

为什么必须有这个脚本（这是整件事的起点）
--------------------------------------------------------------------------------
`docs/规则语料/README.md` §4 早就写着"语料中的费率表取值**必须**与数据库
`tariff_rule` 表逐字一致，否则会**同时污染**检索评测与第 3 周的结构化查询评测"，
但 `validate_corpus.py` 的 11 项检查里**没有一项校验数值** ——
**规则写了、检查没做**，于是漂移长期无人发现：

    | 维度     | 语料                  | 库内旧值（公式生成） |
    |----------|-----------------------|----------------------|
    | 吨位档位 | 0/500/2000/5000       | 0/1000/3000          |
    | 费率数值 | 建材 2026 版 2.55 元/吨 | 1.0200 元/吨       |
    | 分档依据 | 引航/停泊按船舶总吨位 | 按货类               |
    | 计费单位 | 港杂费 元/次          | 元/吨                |

这与 `工程复盘/数据一致性/钢材归类跨来源冲突.md` 是同一类坑：**跨来源的口径必须由检查守住**，
不能靠"写文档时记得对齐"。

本脚本做四件事
--------------------------------------------------------------------------------
1. **跨来源逐格比对**（核心）：把语料费率表**重新解析一遍**（独立于
   `corpus_tariff.py` 的第二套解析，专门用来暴露解析器的 bug），
   与库内 `tariff_rule` 逐格比对：费种 / 货类 / 档位上下限 / 单价 / 单位 /
   计费吨位口径 / 生效期。**双向**比对——库里多出语料没有的规则也算错。
2. **费率命中正确性**（全量 10.9 万行）：按语料口径在 SQL 里重新做一次
   "（费种 + 货类 + 吨位口径 + 吨位档 + 业务日）→ 唯一规则"的匹配，
   断言每条 `fee_record.rule_id` 都等于应命中的规则 id。
3. **金额自洽**（全量）：`amount` 必须等于按该规则的单位/单价/吨位/天数
   （含危险品 +30%、重点保供 70%）复算并四舍五入到分的结果；
   `waybill.freight_amount` 必须等于该运单费用之和。
4. **覆盖与未实现清单**（诚实记录，不是错误）：哪些费率档位**没有任何仿真数据触发**、
   语料里哪些条款**不可实现**（附原因与锚点）。

⚠️ 本脚本**会自证**：`--selftest` 会故意把库内一条费率改坏，断言本检查**确实报错**，
然后回滚并复核库已复原。**不做这一步的校验器不值得信**——
"永远 PASS 的检查"与"没有检查"等价（见
`工程复盘/工程方法/检查有效性必须被验证.md` 与 `工程方法/验证工具本身也要被验证/`）。

用法
--------------------------------------------------------------------------------
    python backend\\scripts\\verify_corpus_db_rates.py              # 全量校验
    python backend\\scripts\\verify_corpus_db_rates.py --selftest   # 额外证明检查会红
    python backend\\scripts\\verify_corpus_db_rates.py --json out.json

⚠️ SQL 一律走 `psql -f`（中文经命令行参数会被控制台代码页改坏，改坏后静默查不到行）。
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import os
import re
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # pragma: no cover
        pass

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "docs" / "规则语料"
DEFAULT_PSQL = Path(r"D:\APP\PostgreSQL\18\bin\psql.exe")
SCRATCH = ROOT / "backend" / "target" / "corpus-db-verify"

RATE_FILES = [
    "06-过闸费费率表（2026版）.md",
    "07-过闸费费率表（2027版）.md",
    "08-引航费与停泊费费率表.md",
    "09-港杂费与装卸费费率表.md",
]
CARGO_CATEGORIES = {"建材", "能源", "金属", "农资", "化工", "集装箱", "其他"}
NO_CATEGORY = "不区分"
UNIT_BY_FEE_TYPE = {
    "过闸费": "元/吨", "装卸费": "元/吨", "引航费": "元/次",
    "港杂费": "元/次", "停泊费": "元/吨天",
}
TONNAGE_BASIS_BY_FEE_TYPE = {
    "过闸费": "载货吨位", "装卸费": "载货吨位", "港杂费": "载货吨位",
    "引航费": "船舶总吨位", "停泊费": "船舶总吨位",
}

errors: list[str] = []
warnings: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


# ── 第二套解析器：**刻意不复用 corpus_tariff.py** ────────────────────────────
# 用同一份代码解析再比对，等于"自己证明自己"——若解析器本身有 bug（如把
# `（不封顶）` 当成数字、把左闭右开读成闭区间），比对会一致地错下去。
# 这里按语料原文重新实现一遍最小解析，任何一处不一致都会在比对里暴露。

_DATE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})")


def _cells(line: str):
    s = line.strip()
    if not (s.startswith("|") and s.endswith("|")):
        return None
    return [c.strip() for c in s[1:-1].split("|")]


def read_corpus_expectations():
    """从语料原文解析出**应然**的费率行（含来源行号，便于定位）。"""
    expected = []
    for fname in RATE_FILES:
        path = CORPUS / fname
        text = path.read_text(encoding="utf-8")
        lines = text.split("\n")

        # 生效期（元数据块）→ 左闭右开
        eff_from = eff_to = None
        for line in lines:
            cells = _cells(line)
            if cells and len(cells) == 2 and cells[0] == "生效期":
                left, _, right = cells[1].partition("至")
                m = _DATE_RE.search(left)
                if not m:
                    err(f"{fname}：生效期起始日解析失败：{cells[1]!r}")
                    continue
                eff_from = m.group(1)
                eff_to = None if "长期有效" in right else (
                    _DATE_RE.search(right).group(1) if _DATE_RE.search(right) else None)
                if eff_to is None and "长期有效" not in right:
                    err(f"{fname}：生效期终止日解析失败：{cells[1]!r}")
                break
        if eff_from is None:
            err(f"{fname}：未找到生效期字段")
            continue

        header_seen = False
        for lineno, line in enumerate(lines, start=1):
            cells = _cells(line)
            if cells is None:
                header_seen = False
                continue
            if all(set(c) <= set("-: ") and c for c in cells):
                continue
            if "吨位下限（吨）" in cells:
                header_seen = True
                continue
            if not header_seen:
                continue

            fee_type, cargo_raw, mn, mx, price, unit = cells
            cargo = NO_CATEGORY if cargo_raw == "—" else cargo_raw
            expected.append({
                "source": f"{fname}:{lineno}",
                "fee_type": fee_type,
                "cargo_category": cargo,
                "min_ton": Decimal(mn),
                "max_ton": None if mx == "（不封顶）" else Decimal(mx),
                "unit_price": Decimal(price),
                "unit": unit,
                "effective_from": eff_from,
                "effective_to": eff_to,
                # 口径由 25-第十二条 推出（不抄语料费率表，因为它不写这一列）
                "tonnage_basis": TONNAGE_BASIS_BY_FEE_TYPE.get(fee_type, "?"),
            })
    return expected


def _num_key(x) -> str:
    """把吨位下限规范成**数值等价**的字符串，供做字典键。

    ⚠️ 不能直接 `str(Decimal(x))`：库内是 `NUMERIC(12,2)`，`min_ton` 读回来是
    `"0.00"` ⇒ `str(Decimal("0.00")) == "0.00"`，而语料原文写的是 `0`
    ⇒ `"0"`。两者**数值相等但字符串不等**，于是比对会**全量误报"库内缺少规则"**
    （本脚本第一次运行就踩到：99 条全部"缺失" + 99 条"未授权" = 198 条假错误）。
    这正是"跨来源比对必须先对齐表示"的例子。
    """
    d = Decimal(str(x))
    s = format(d, "f")
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s or "0"


def key_of(row):
    """比对主键：语料侧的（费种 + 货类 + 档位下限 + 生效起始日）。"""
    return (row["fee_type"], row["cargo_category"], _num_key(row["min_ton"]),
            row["effective_from"])


# ── psql 访问（照抄 verify_bm25.py 的既有写法与理由）────────────────────────
# ⚠️ 从 Python 调 psql 的两个坑（本项目都实际踩过，见
#    `docs/工程复盘/环境坑/psql从子进程调用会挂住.md`）：
#    ① 必须把 `env=` 传进去，否则子进程拿不到 PGPASSWORD，psql 转去**弹口令提示**，
#       而该提示读的是控制台不是 stdin ⇒ `stdin=DEVNULL` 挡不住，表现为永久挂起；
#    ② 输出写**文件**而不是走管道（本沙箱对管道捕获子进程输出有限制）。
#    再补 `-w`（never prompt）与 `timeout`，使最坏情况是"明确失败"而非"静默等待"。

def load_env(root: Path) -> dict:
    env = {}
    env_file = root / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def psql_env(env: dict) -> dict:
    proc_env = dict(os.environ)
    proc_env["PGCLIENTENCODING"] = "UTF8"
    password = env.get("POSTGRES_PASSWORD") or os.environ.get("PGPASSWORD", "")
    if password:
        proc_env["PGPASSWORD"] = password
    return proc_env


class Db:
    def __init__(self, psql: Path, env: dict, host: str, port: str, dbname: str, user: str):
        self.psql = psql
        self.env = psql_env(env)
        self.invocation = [str(psql), "-h", host, "-p", port, "-U", user, "-d", dbname,
                           "-X", "-q", "-w", "--csv", "-v", "ON_ERROR_STOP=1"]
        SCRATCH.mkdir(parents=True, exist_ok=True)

    def run_file(self, sql: str, name: str, out_csv: str | None = None):
        """执行 SQL（写文件后 `psql -f`）。返回 (是否成功, stderr 文本, 输出 csv 路径)。"""
        sql_path = SCRATCH / f"{name}.sql"
        out_path = SCRATCH / (out_csv or f"{name}.out.csv")
        err_path = SCRATCH / f"{name}.err.txt"
        sql_path.write_bytes(sql.encode("utf-8"))       # 无 BOM 的 UTF-8
        with open(out_path, "wb") as out, open(err_path, "wb") as errh:
            try:
                proc = subprocess.run(self.invocation + ["-f", str(sql_path)],
                                      stdout=out, stderr=errh, env=self.env,
                                      stdin=subprocess.DEVNULL, timeout=300, cwd=str(ROOT))
                code = proc.returncode
            except subprocess.TimeoutExpired:
                return False, f"psql 超时（{name}）", out_path
        stderr_text = err_path.read_bytes().decode("utf-8", "replace").strip()
        return code == 0, stderr_text, out_path

    def rows(self, sql: str, name: str) -> list:
        ok, stderr_text, out_path = self.run_file(sql, name)
        if not ok:
            raise RuntimeError(f"psql 执行失败（{name}）：\n{stderr_text[:1500]}")
        if stderr_text:
            warn(f"{name}：psql 有 stderr 输出（已继续）：{stderr_text[:300]}")
        text = out_path.read_bytes().decode("utf-8", "replace")
        return list(csv.DictReader(io.StringIO(text)))


def sql_literal(s: str) -> str:
    return "'" + str(s).replace("'", "''") + "'"


# ── 检查 1：跨来源逐格比对 ──────────────────────────────────────────────────

DB_RULES_SQL = """
\\pset footer off
SELECT rule_code, fee_type, cargo_category, tonnage_basis, min_ton, max_ton,
       unit_price, unit, effective_from, effective_to
  FROM tariff_rule
 ORDER BY fee_type, cargo_category, tonnage_basis, min_ton, effective_from;
"""


def compare_rules(db_rows: list, expected: list, where: str = "库内 tariff_rule"):
    """双向逐格比对。返回 (比对行数, 不一致清单)。"""
    mismatches = []
    db_by_key = {}
    for r in db_rows:
        k = (r["fee_type"], r["cargo_category"],
             _num_key(r["min_ton"]), r["effective_from"])
        if k in db_by_key:
            mismatches.append(f"{where}：主键重复 {k}（违反 tariff_no_overlap 的意图）")
        db_by_key[k] = r

    for exp in expected:
        k = key_of(exp)
        got = db_by_key.pop(k, None)
        if got is None:
            mismatches.append(
                f"库内缺少语料规则（{exp['source']}）：{k[0]}/{k[1]}/下限 {k[2]}/生效 {k[3]}")
            continue
        checks = [
            ("档位上限", exp["max_ton"],
             None if got["max_ton"] == "" else Decimal(got["max_ton"])),
            ("单价", exp["unit_price"], Decimal(got["unit_price"])),
            ("计费单位", exp["unit"], got["unit"]),
            ("计费吨位口径", exp["tonnage_basis"], got["tonnage_basis"]),
            ("生效终止日", exp["effective_to"],
             None if got["effective_to"] == "" else got["effective_to"]),
        ]
        for field, want, have in checks:
            if want != have:
                mismatches.append(
                    f"{where} 与语料不符（{exp['source']} → {got['rule_code']}）："
                    f"{field} 语料={want!r} 库内={have!r}")

    for k, got in db_by_key.items():
        mismatches.append(
            f"{where} 存在语料未授权的规则：{k[0]}/{k[1]}/下限 {k[2]}/生效 {k[3]}"
            f"（{got['rule_code']}）—— 库侧不得自行发明费率")
    return len(expected), mismatches


# ── 检查 2：费率命中正确性（全量）────────────────────────────────────────────

MATCH_AUDIT_SQL = """
\\pset footer off
WITH fee_ctx AS (
  SELECT f.id AS fee_id, f.rule_id, f.fee_type,
         w.created_at::date                          AS biz_date,
         ct.category                                 AS cargo_category,
         w.weight_ton                                AS cargo_tonnage,
         v.gross_tonnage                             AS gross_tonnage,
         f.calc_basis->>'tonnage_basis'              AS tonnage_basis,
         (f.calc_basis->>'tonnage')::numeric         AS recorded_tonnage,
         CASE WHEN f.calc_basis->>'tonnage_basis' = '船舶总吨位'
              THEN v.gross_tonnage::numeric ELSE w.weight_ton END AS expect_tonnage
    FROM fee_record f
    JOIN waybill    w  ON w.waybill_no   = f.waybill_no
    JOIN cargo_type ct ON ct.cargo_code  = w.cargo_code
    JOIN voyage     vo ON vo.id          = w.voyage_id
    JOIN vessel     v  ON v.id           = vo.vessel_id
)
SELECT
  (SELECT count(*) FROM fee_ctx) AS total,
  (SELECT count(*) FROM fee_ctx c
    WHERE c.recorded_tonnage <> c.expect_tonnage) AS tonnage_basis_wrong,
  (SELECT count(*) FROM fee_ctx c
    WHERE NOT EXISTS (
      SELECT 1 FROM tariff_rule t
       WHERE t.id = c.rule_id
         AND t.fee_type      = c.fee_type
         AND t.tonnage_basis = c.tonnage_basis
         AND t.cargo_category = CASE WHEN c.tonnage_basis = '船舶总吨位'
                                     THEN '不区分' ELSE c.cargo_category END
         AND c.expect_tonnage >= t.min_ton
         AND (t.max_ton IS NULL OR c.expect_tonnage < t.max_ton)
         AND c.biz_date >= t.effective_from
         AND (t.effective_to IS NULL OR c.biz_date < t.effective_to))) AS rule_match_wrong;
"""

AMOUNT_AUDIT_SQL = """
\\pset footer off
SELECT
  (SELECT count(*) FROM fee_record) AS fee_rows,
  (SELECT count(*) FROM fee_record f JOIN tariff_rule t ON t.id = f.rule_id
    WHERE f.amount <> round(
            CASE t.unit
                 WHEN '元/吨'   THEN (f.calc_basis->>'tonnage')::numeric * t.unit_price
                 WHEN '元/次'   THEN t.unit_price
                 ELSE (f.calc_basis->>'tonnage')::numeric * t.unit_price
                      * (f.calc_basis->>'days')::numeric
            END
            * (f.calc_basis->>'relief')::numeric
            * (f.calc_basis->>'surcharge')::numeric, 2)) AS amount_wrong,
  (SELECT count(*) FROM (
      SELECT w.waybill_no
        FROM waybill w JOIN fee_record f ON f.waybill_no = w.waybill_no
       GROUP BY w.waybill_no, w.freight_amount
      HAVING w.freight_amount <> sum(f.amount)) x) AS freight_wrong,
  (SELECT count(*) FROM fee_record f JOIN tariff_rule t ON t.id = f.rule_id
    WHERE f.fee_type <> t.fee_type) AS fee_type_wrong;
"""

# ── 检查 4：覆盖与未实现清单 ────────────────────────────────────────────────

COVERAGE_SQL = """
\\pset footer off
SELECT t.fee_type, t.cargo_category, t.tonnage_basis,
       t.min_ton, COALESCE(t.max_ton::text, '∞') AS max_ton,
       count(f.id) AS used
  FROM tariff_rule t LEFT JOIN fee_record f ON f.rule_id = t.id
 GROUP BY t.fee_type, t.cargo_category, t.tonnage_basis, t.min_ton, t.max_ton
 ORDER BY t.fee_type, t.cargo_category, t.min_ton;
"""

# 语料中**不可实现**的条款：只登记，不实现（C-36④ 与 Q4-A 的决定）。
# 每条必须给出"为什么不可实现"与语料锚点——否则就成了"悄悄略过"。
UNIMPLEMENTED = [
    ("06-第十五条 2 / 07-第十三条 2", "核定最低计费吨位",
     "语料全文未定义该术语（grep 仅这两处出现），且库内无『核定』记录字段；"
     "擅自造定义等于发明业务规则，故不实现（C-36③）"),
    ("08-第六条", "引航中止按 50% 计收",
     "引航费记录无『是否中止』字段，无法触发"),
    ("09-第九条 2", "货主自备装卸设备按 50% 计收",
     "无『自备设备』标志字段，无法触发"),
    ("09-第八条 2", "装卸费装、卸各计一次",
     "fee_record 无『作业类型』列，本数据集每运单只记 1 次作业"),
    ("各费率表「免收情形」条款（如空载、公务船舶、抢险救灾等）",
     "免收",
     "库内无对应的业务标记字段，无法判定；仿真数据也不产生这类运单"),
    ("20-第十一条 / 第十二条", "堆存仓储费（元/吨天、元/次）",
     "无堆存/仓储记录表，起止日期与堆存量都无来源，不可计算"),
]


def main():
    ap = argparse.ArgumentParser(description="语料费率表 ↔ tariff_rule 逐格校验")
    ap.add_argument("--psql", default=str(DEFAULT_PSQL))
    ap.add_argument("--host", default=None)
    ap.add_argument("--port", default=None)
    ap.add_argument("--db", default=None)
    ap.add_argument("--user", default=None)
    ap.add_argument("--json", default=None, help="把结果写成 JSON 证据文件")
    ap.add_argument("--selftest", action="store_true",
                    help="故意改坏一条费率，断言本检查会报错（证明检查真的能失败）")
    args = ap.parse_args()

    env = load_env(ROOT)
    host = args.host or env.get("POSTGRES_HOST", "127.0.0.1")
    port = args.port or env.get("POSTGRES_PORT", "5432")
    dbname = args.db or env.get("POSTGRES_DB", "canalmind")
    user = args.user or env.get("POSTGRES_USER", "postgres")
    psql = Path(args.psql)
    if not psql.exists():
        print(f"[FAIL] 找不到 psql：{psql}")
        return 2

    db = Db(psql, env, host, port, dbname, user)
    expected = read_corpus_expectations()
    print("=" * 78)
    print("语料费率表 ↔ tariff_rule 一致性校验（决策 C-31）")
    print(f"  语料费率表 : {len(expected)} 条（{len(RATE_FILES)} 篇：06/07/08/09）")

    # ── 检查 1 ────────────────────────────────────────────────────────────
    db_rows = db.rows(DB_RULES_SQL, "db_rules")
    n_cmp, mismatches = compare_rules(db_rows, expected)
    print(f"  库内规则   : {len(db_rows)} 条")
    print("-" * 78)
    print("[1] 跨来源逐格比对（费种/货类/档位/单价/单位/吨位口径/生效期）")
    print(f"    双向比对 {n_cmp} 条语料规则 ↔ {len(db_rows)} 条库内规则")
    if mismatches:
        for m in mismatches:
            err(m)
    else:
        print(f"    OK：{n_cmp} 条逐格一致，且库内无未获语料授权的规则")

    # ── 检查 2 ────────────────────────────────────────────────────────────
    print("[2] 费率命中正确性（全量）")
    ms = db.rows(MATCH_AUDIT_SQL, "match_audit")[0]
    total = int(ms["total"])
    print(f"    复核 {total:,} 条费用明细的计费口径与命中规则")
    if int(ms["tonnage_basis_wrong"]) == 0:
        print("    OK：计费吨位记录与库内推导值一致（载货吨位=运单货重 / 船舶总吨位=船舶总吨位）")
    else:
        err(f"{ms['tonnage_basis_wrong']} 条费用明细的计费吨位与库内推导值不符")
    if int(ms["rule_match_wrong"]) == 0:
        print("    OK：每条费用命中的规则 id 都等于按语料口径应命中的规则")
    else:
        err(f"{ms['rule_match_wrong']} 条费用明细命中了错误的费率规则")

    # ── 检查 3 ────────────────────────────────────────────────────────────
    print("[3] 金额自洽（全量）")
    am = db.rows(AMOUNT_AUDIT_SQL, "amount_audit")[0]
    print(f"    复核 {int(am['fee_rows']):,} 条费用金额（含附加 30% / 减免 70%，四舍五入到分）")
    for label, field in (("金额 ≠ 按规则复算值", "amount_wrong"),
                         ("运单费用合计 ≠ freight_amount", "freight_wrong"),
                         ("费用类型与规则不一致", "fee_type_wrong")):
        n = int(am[field])
        if n == 0:
            print(f"    OK：{label}（0 条）")
        else:
            err(f"{n} 条：{label}")

    # ── 检查 4 ────────────────────────────────────────────────────────────
    print("[4] 覆盖与未实现清单（诚实记录，不计为错误）")
    cov = db.rows(COVERAGE_SQL, "coverage")
    unused = [c for c in cov if int(c["used"]) == 0]
    print(f"    费率档位共 {len(cov)} 个，其中 {len(unused)} 个**没有任何仿真数据触发**：")
    for c in unused:
        print(f"      - {c['fee_type']}/{c['cargo_category']}/{c['tonnage_basis']} "
              f"[{c['min_ton']}, {c['max_ton']}) 未被触发")
    print(f"    语料中登记为『不可实现』的条款 {len(UNIMPLEMENTED)} 项：")
    for anchor, title, why in UNIMPLEMENTED:
        print(f"      - {anchor}｜{title}：{why}")

    # ── 自证：检查本身能不能红 ─────────────────────────────────────────────
    selftest_ok = None
    if args.selftest:
        print("-" * 78)
        print("[5] 自证：故意改坏一条费率，断言本检查会报错")
        # ⚠️ `\copy` 必须写成**一整行**：psql 的 meta 命令按行解析，
        #    跨行的括号不会自动续行，实测报 "\copy: parse error at end of line"。
        copy_one_line = (
            "\\copy (SELECT rule_code, fee_type, cargo_category, tonnage_basis, min_ton,"
            " max_ton, unit_price, unit, effective_from, effective_to FROM tariff_rule"
            " ORDER BY fee_type, cargo_category, tonnage_basis, min_ton, effective_from)"
            " TO '" + str(SCRATCH / "selftest_mutated.csv").replace("\\", "/")
            + "' WITH (FORMAT csv, HEADER true)")
        mutate_sql = "\n".join([
            "BEGIN;",
            "UPDATE tariff_rule SET unit_price = unit_price + 1",
            " WHERE id = (SELECT min(id) FROM tariff_rule);",
            "\\pset footer off",
            copy_one_line,
            "ROLLBACK;",
            "",
        ])
        ok, stderr_text, _ = db.run_file(mutate_sql, "selftest_mutate")
        if not ok:
            err(f"自证步骤无法执行：{stderr_text[:300]}")
            selftest_ok = False
        else:
            mutated_path = SCRATCH / "selftest_mutated.csv"
            mutated = list(csv.DictReader(
                io.StringIO(mutated_path.read_bytes().decode("utf-8", "replace"))))
            _, mutated_mismatches = compare_rules(mutated, expected, "自证（改坏后）")
            print(f"    改坏 1 条费率后，比对报出 {len(mutated_mismatches)} 处不一致")
            if mutated_mismatches:
                print("    OK：检查确实会红（不是永远绿灯）")
                selftest_ok = True
            else:
                err("自证失败：把库内费率改坏之后，本检查**仍然报 0 处不一致** ⇒ 检查无效")
                selftest_ok = False
            # 复核库已回滚
            after = db.rows(DB_RULES_SQL, "db_rules_after_selftest")
            _, after_mismatches = compare_rules(after, expected, "自证（回滚后）")
            if after_mismatches:
                err("自证回滚后库内仍不一致 —— 数据库可能被留在改坏状态")
                selftest_ok = False
            else:
                print("    OK：回滚后库内恢复一致（未留下改坏状态）")

    # ── 结论 ──────────────────────────────────────────────────────────────
    print("=" * 78)
    if warnings:
        print(f"警告 {len(warnings)} 项：")
        for w in warnings[:10]:
            print(f"  ! {w}")
    if errors:
        print(f"[FAIL] 错误 {len(errors)} 项：")
        for e in errors[:20]:
            print(f"  x {e}")
        code = 1
    else:
        print("[PASS] 语料与库内费率逐格一致；费率命中、金额自洽、覆盖清单均已复核")
        code = 0

    if args.json:
        payload = {
            "corpusRules": len(expected),
            "dbRules": len(db_rows),
            "ruleMismatches": mismatches,
            "matchAudit": ms,
            "amountAudit": am,
            "unusedBands": unused,
            "unimplemented": [{"anchor": a, "title": t, "reason": r}
                              for a, t, r in UNIMPLEMENTED],
            "selftest": selftest_ok,
            "errors": errors,
            "warnings": warnings,
        }
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes((json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
                        .encode("utf-8"))
        print(f"证据文件 → {out}")
    return code


if __name__ == "__main__":
    sys.exit(main())
