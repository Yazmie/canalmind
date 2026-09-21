#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""构建第 3 周 Golden Set v3（L1 40 条 + L3/L4/L5/L6 80 条 = 120 条新增）。

设计要点（决策 C-34 / C-35）：

1. **L1 的金标准来自数据库执行结果**，不是手写答案：
   - 费率题 → 按（费种 + 货类 + 吨位口径 + 吨位 + 业务日）查 `tariff_rule`；
   - 测算题 → **在 SQL 里**用 `round(... , 2)` 按语料口径算（与 Java 费用引擎是两套独立实现）；
   - 统计题 → 在 SQL 里聚合（并列规则见 C-35：值降序 + 名称升序）；
   - 运单题 → 取 `waybill` 的字段值（`freight_amount` 已由 `verify_corpus_db_rates.py`
     验证等于该运单各费种金额之和，故它就是"数据库执行结果"）。
2. **问题由人写，参数由题面唯一确定**：题面里写清费种/货类/吨位/日期，金标准参数与之逐字对应。
   ⛔ 不允许"答案靠猜"的题（那会让 Execution Accuracy 变成统计噪声）。
3. L3/L4/L5/L6 由 `backend/scripts/data/golden-extra-items.json` 提供（人工构造，入库留档），
   本脚本负责**结构校验 + 合并 + 分布断言**。
4. 产物确定性：键排序、LF、无 BOM、条目顺序固定（同输入必然字节相同）。

用法：
    python backend\\scripts\\build_golden_set_v3.py            # 构建并校验
    python backend\\scripts\\build_golden_set_v3.py --check    # 只校验已有产物
"""

from __future__ import annotations

import argparse
import csv
import datetime
import io
import json
import os
import subprocess
import sys
from decimal import Decimal
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent                      # canalmind/
sys.path.insert(0, str(SCRIPT_DIR))

from verify_corpus_db_rates import Db, load_env  # noqa: E402  (复用既定 psql 调用方式)

OUT_PATH = ROOT / "docs" / "评测报告" / "golden-set-v3.json"
EXTRA_PATH = SCRIPT_DIR / "golden-extra-items.json"
SCRATCH = ROOT / "backend" / "target" / "eval"
BUSINESS_TODAY = "2027-09-15"
BUSINESS_START = "2026-09-16"
BUSINESS_END = "2027-09-15"

DEFAULT_PSQL = Path(r"D:\APP\PostgreSQL\18\bin\psql.exe")

CARGO_CATEGORIES = ["建材", "能源", "金属", "农资", "化工", "集装箱", "其他"]
FEE_TYPES = ["过闸费", "港杂费", "装卸费", "引航费", "停泊费"]
CONTRACT_PATH = ROOT / "backend" / "src" / "main" / "resources" / "structured" / "tool-contracts.json"


def _load_contract() -> tuple[set, dict, dict]:
    """工具名、参数名、参数约束**从契约读**，不在这里再写一份——
    否则契约加了参数而评测集脚本不知道，会得到"评测集自己报错、被测代码其实没问题"的假失败。
    """
    contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
    names = set()
    args: dict = {}
    schemas: dict = {}
    for tool in contract["tools"]:
        names.add(tool["name"])
        props = tool["parameters"]["properties"]
        args[tool["name"]] = set(props.keys())
        schemas[tool["name"]] = props          # 用于反向校验枚举值与日期格式
    return names, args, schemas


TOOLS, TOOL_ARGS, TOOL_SCHEMAS = _load_contract()
PER_TIME_FEE_TYPES = {"引航费", "停泊费"}


def _check_args_against_contract(item_id: str, tool: str, values: dict, errors: list) -> None:
    """把参数值**反向校验回契约**：枚举必须在 enum 内、日期必须真能解析。

    ⚠️ 为什么要有这一步：L3–L6 是人工构造的题，参数写错**不会报错**，
    只会让"评测集自己错了"伪装成"系统答错了"（本项目已有一次同类教训：
    `数据一致性/评测集自身的错误不会报错`）。日期尤其危险——
    `2027-02-30` 这种手写笔误只有真去 parse 才发现。
    """
    props = TOOL_SCHEMAS.get(tool, {})
    for key, value in values.items():
        spec = props.get(key)
        if spec is None:
            continue                            # 参数名合法性由调用方另行检查
        enum = spec.get("enum")
        if enum is not None and isinstance(value, str) and value not in enum:
            errors.append(f"{item_id}：{tool}.{key} 的值「{value}」不在契约枚举内")
        if spec.get("pattern", "").startswith("^\\d{4}-\\d{2}-\\d{2}$") or "date" in key:
            if isinstance(value, str):
                try:
                    datetime.date.fromisoformat(value)
                except ValueError:
                    errors.append(f"{item_id}：{tool}.{key} 的日期「{value}」无法解析")


# ── L1 题面表 ────────────────────────────────────────────────────────────────
# kind: tariff / quote / traffic / waybill
# 每条：id、kind、question、args（金标准参数化）、可选 gold_fields / pick

L1_ITEMS: list[dict] = [
    # ── 费率查询 12 条（覆盖 5 个费种、两套吨位口径、2026/2027 两版）
    dict(id="L1-001", kind="tariff", question="2026 年 10 月 1 日，1200 吨建材走平陆运河，过闸费的单价是多少？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=1200, biz_date="2026-10-01")),
    dict(id="L1-002", kind="tariff", question="2026 年 10 月 1 日，建材过闸费对 300 吨的船货是什么费率？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=300, biz_date="2026-10-01")),
    dict(id="L1-003", kind="tariff", question="2026 年 9 月 20 日，6000 吨建材的过闸费费率是多少？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=6000, biz_date="2026-09-20")),
    dict(id="L1-004", kind="tariff", question="2027 年 3 月 1 日建材过闸费每吨多少钱（按 1200 吨算）？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=1200, biz_date="2027-03-01")),
    dict(id="L1-005", kind="tariff", question="2027 年 3 月 1 日，6000 吨建材的过闸费单价是多少？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=6000, biz_date="2027-03-01")),
    dict(id="L1-006", kind="tariff", question="2026 年 11 月 10 日，800 吨能源类货物的过闸费费率是多少？",
         args=dict(fee_type="过闸费", cargo_category="能源", tonnage=800, biz_date="2026-11-10")),
    dict(id="L1-007", kind="tariff", question="2027 年 5 月 1 日，2500 吨化工品走运河的过闸费单价是多少？",
         args=dict(fee_type="过闸费", cargo_category="化工", tonnage=2500, biz_date="2027-05-01")),
    dict(id="L1-008", kind="tariff", question="2026 年 10 月 1 日，1500 吨集装箱货物的港杂费怎么收（单价与单位）？",
         args=dict(fee_type="港杂费", cargo_category="集装箱", tonnage=1500, biz_date="2026-10-01")),
    dict(id="L1-009", kind="tariff", question="2026 年 10 月 1 日建材的港杂费费率是多少（按 800 吨）？",
         args=dict(fee_type="港杂费", cargo_category="建材", tonnage=800, biz_date="2026-10-01")),
    dict(id="L1-010", kind="tariff", question="2027 年 6 月 1 日，3000 吨金属类货物的装卸费单价是多少？",
         args=dict(fee_type="装卸费", cargo_category="金属", tonnage=3000, biz_date="2027-06-01")),
    dict(id="L1-011", kind="tariff", question="2027 年 3 月 1 日，船舶总吨位 1500 的船进港，引航费多少一次？",
         args=dict(fee_type="引航费", tonnage=1500, biz_date="2027-03-01")),
    dict(id="L1-012", kind="tariff", question="2026 年 10 月 1 日，总吨位 2500 的船在港区停泊，停泊费按什么单价计收？",
         args=dict(fee_type="停泊费", tonnage=2500, biz_date="2026-10-01")),

    # ── 费用测算 16 条（含 2026/2027 两版、危险品加收、重点保供折扣、三种计费单位）
    dict(id="L1-013", kind="quote", question="2026 年 10 月 1 日，1200 吨建材过闸要交多少过闸费？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=1200, biz_date="2026-10-01")),
    dict(id="L1-014", kind="quote", question="2026 年 10 月 1 日，6000 吨建材过闸费是多少钱？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=6000, biz_date="2026-10-01")),
    dict(id="L1-015", kind="quote", question="2027 年 3 月 1 日，1200 吨建材过闸费要交多少钱？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=1200, biz_date="2027-03-01")),
    dict(id="L1-016", kind="quote", question="2026 年 9 月 16 日，6000 吨建材过闸费是多少？",
         args=dict(fee_type="过闸费", cargo_category="建材", tonnage=6000, biz_date="2026-09-16")),
    dict(id="L1-017", kind="quote", question="2027 年 4 月 10 日，2500 吨化工品过闸费是多少（按重点保供算）？",
         args=dict(fee_type="过闸费", cargo_category="化工", tonnage=2500, biz_date="2027-04-10")),
    dict(id="L1-018", kind="quote", question="2026 年 12 月 1 日，1200 吨农资过闸费多少？",
         args=dict(fee_type="过闸费", cargo_category="农资", tonnage=1200, biz_date="2026-12-01")),
    dict(id="L1-019", kind="quote", question="2026 年 10 月 1 日，1500 吨成品油过闸费是多少？（要算上加收）",
         args=dict(fee_type="过闸费", cargo_name="成品油", tonnage=1500, biz_date="2026-10-01")),
    dict(id="L1-020", kind="quote", question="2027 年 5 月 20 日，1200 吨硫酸过闸费多少钱？",
         args=dict(fee_type="过闸费", cargo_name="硫酸", tonnage=1200, biz_date="2027-05-20")),
    dict(id="L1-021", kind="quote", question="2026 年 10 月 1 日，1500 吨集装箱货物的港杂费是多少？",
         args=dict(fee_type="港杂费", cargo_category="集装箱", tonnage=1500, biz_date="2026-10-01")),
    dict(id="L1-022", kind="quote", question="2026 年 11 月 1 日，800 吨烧碱的港杂费要交多少（含危险品加收）？",
         args=dict(fee_type="港杂费", cargo_name="烧碱", tonnage=800, biz_date="2026-11-01")),
    dict(id="L1-023", kind="quote", question="2026 年 10 月 1 日，3000 吨建材的装卸费是多少？",
         args=dict(fee_type="装卸费", cargo_category="建材", tonnage=3000, biz_date="2026-10-01")),
    dict(id="L1-024", kind="quote", question="2027 年 6 月 1 日，3000 吨金属类货物的装卸费是多少？",
         args=dict(fee_type="装卸费", cargo_category="金属", tonnage=3000, biz_date="2027-06-01")),
    dict(id="L1-025", kind="quote", question="2027 年 3 月 1 日，总吨位 2500 的船进港，引航费是多少？",
         args=dict(fee_type="引航费", tonnage=2500, biz_date="2027-03-01")),
    dict(id="L1-026", kind="quote", question="2026 年 10 月 1 日，总吨位 2000 的船停泊 3 天，停泊费要交多少？",
         args=dict(fee_type="停泊费", tonnage=2000, days=3, biz_date="2026-10-01")),
    dict(id="L1-027", kind="quote", question="2026 年 10 月 1 日，总吨位 800 的船停泊 1 天，停泊费多少？",
         args=dict(fee_type="停泊费", tonnage=800, days=1, biz_date="2026-10-01")),
    dict(id="L1-028", kind="quote", question="2026 年 10 月 1 日，总吨位 6000 的船停泊 2 天，停泊费是多少？",
         args=dict(fee_type="停泊费", tonnage=6000, days=2, biz_date="2026-10-01")),

    # ── 运量统计 8 条（三种口径 × 多种分组；并列规则见 C-35）
    dict(id="L1-029", kind="traffic", question="整个业务窗口内（2026-09-16 到 2027-09-15）一共运了多少吨货？",
         args=dict(date_from=BUSINESS_START, date_to=BUSINESS_END, metric="吨位", group_by="无")),
    dict(id="L1-030", kind="traffic", question="2026-09-16 到 2027-09-15 期间，按货类看运量最大的前 5 名分别是多少吨？",
         args=dict(date_from=BUSINESS_START, date_to=BUSINESS_END, metric="吨位", group_by="货类", top_n=5)),
    dict(id="L1-031", kind="traffic", question="2026 年 10 月到 2027 年 3 月，各月份的运单数分别是多少（前 5 名）？",
         args=dict(date_from="2026-10-01", date_to="2027-03-31", metric="运单数", group_by="月份", top_n=5)),
    dict(id="L1-032", kind="traffic", question="2027 年上半年按货类统计的过闸费金额，前 5 名是多少？",
         args=dict(date_from="2027-01-01", date_to="2027-06-30", metric="费用金额", group_by="货类",
                   fee_type="过闸费", top_n=5)),
    dict(id="L1-033", kind="traffic", question="只算经运河的航次，2026-09-16 到 2027-09-15 总运量是多少吨？",
         args=dict(date_from=BUSINESS_START, date_to=BUSINESS_END, metric="吨位", group_by="无",
                   via_canal_only=True)),
    dict(id="L1-034", kind="traffic", question="2026-09-16 到 2027-09-15 期间，起运港按运量排前 5 名是哪几个港？",
         args=dict(date_from=BUSINESS_START, date_to=BUSINESS_END, metric="吨位", group_by="起运港", top_n=5)),
    dict(id="L1-035", kind="traffic", question="2026 年 10 月 1 日到 2026 年 12 月 31 日，按货种统计运量前 5 名",
         args=dict(date_from="2026-10-01", date_to="2026-12-31", metric="吨位", group_by="货种", top_n=5)),
    dict(id="L1-036", kind="traffic", question="2026-09-16 到 2027-09-15，全部费用的金额按货类前 5 名是多少？",
         args=dict(date_from=BUSINESS_START, date_to=BUSINESS_END, metric="费用金额", group_by="货类", top_n=5)),

    # ── 运单与单证 4 条（金标准取 waybill 字段；freight_amount 已证等于各费种金额之和）
    dict(id="L1-037", kind="waybill", question="运单 WB20270000001 的运费多少钱？",
         args=dict(waybill_no="WB20270000001"), gold_fields=["freight_amount", "weight_ton", "status"]),
    dict(id="L1-038", kind="waybill", question="WB20270000002 这票货多重、什么状态？",
         args=dict(waybill_no="WB20270000002"), gold_fields=["weight_ton", "status", "cargo_category"]),
    dict(id="L1-039", kind="waybill", question="2027 年 6 月 1 日到 2027 年 6 月 10 日之间，状态为已结算的运单有哪些（最多 10 票）？",
         args=dict(date_from="2027-06-01", date_to="2027-06-10", status="已结算", limit=10),
         gold_fields=["waybill_nos"]),
    dict(id="L1-040", kind="waybill", question="2027 年 6 月 1 日到 2027 年 6 月 5 日，化工类的运单有几票、都有哪些（最多 10 票）？",
         args=dict(date_from="2027-06-01", date_to="2027-06-05", cargo_category="化工", limit=10),
         gold_fields=["waybill_nos"]),
]


# ── SQL 金标准 ───────────────────────────────────────────────────────────────

def _lit(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float, Decimal)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def _basis_of(fee_type: str) -> str:
    return "船舶总吨位" if fee_type in PER_TIME_FEE_TYPES else "载货吨位"


def _category_of(item: dict) -> str:
    """费种决定是否需要货类；引航费/停泊费落库为哨兵值「不区分」。"""
    fee_type = item["args"]["fee_type"]
    if fee_type in PER_TIME_FEE_TYPES:
        return "不区分"
    if "cargo_name" in item["args"]:
        return None  # 由库内 cargo_type 反查（下面单独处理）
    return item["args"]["cargo_category"]


def _rule_predicate(fee_type: str, category: str, tonnage, biz_date: str) -> str:
    return (
        f"r.fee_type = {_lit(fee_type)} AND r.cargo_category = {_lit(category)} "
        f"AND r.tonnage_basis = {_lit(_basis_of(fee_type))} "
        f"AND {_lit(tonnage)} >= r.min_ton AND (r.max_ton IS NULL OR {_lit(tonnage)} < r.max_ton) "
        f"AND DATE {_lit(biz_date)} >= r.effective_from "
        f"AND (r.effective_to IS NULL OR DATE {_lit(biz_date)} < r.effective_to)"
    )


def gold_for(db: Db, item: dict) -> dict:
    """按题面参数从**数据库执行结果**取金标准（⛔ 不手写答案）。"""
    args = item["args"]
    kind = item["kind"]
    fee_type = args.get("fee_type")

    if kind == "waybill":
        return _gold_waybill(db, item)

    if kind == "traffic":
        return _gold_traffic(db, item)

    # 费率 / 测算：先把货种归一到货类（库内 cargo_type 是权威）
    cargo_pred = ""
    if "cargo_name" in args:
        rows = db.rows(
            f"SELECT category, is_dangerous FROM cargo_type WHERE name = {_lit(args['cargo_name'])};",
            f"gt-cargo-{item['id']}")
        if len(rows) != 1:
            raise RuntimeError(f"{item['id']}：货种 {args['cargo_name']} 在库内不是唯一命中（{len(rows)} 条）")
        category = rows[0]["category"]
        dangerous = rows[0]["is_dangerous"] in ("t", "true", "True")
    else:
        category = _category_of(item)
        dangerous = None
        if kind == "quote" and category not in (None, "不区分"):
            # 只给货类时，危险品属性**查库推导**；库内属性不一致（能源）就无法确定 ⇒ 判为数据/题面错误。
            # ⚠️ 只有测算（quote）才需要危险品属性：费率查询（tariff）只按货类取价，与危险品无关。
            rows = db.rows(
                "SELECT count(DISTINCT is_dangerous) AS kinds, bool_and(is_dangerous) AS uniform "
                f"FROM cargo_type WHERE category = {_lit(category)};",
                f"gt-cat-{item['id']}")
            kinds = int(rows[0]["kinds"] or 0)
            if kinds != 1:
                raise RuntimeError(
                    f"{item['id']}：货类 {category} 内部危险品属性不一致（{kinds} 种），题面必须给出货种")
            dangerous = rows[0]["uniform"] in ("t", "true", "True")

    if kind == "tariff":
        sql = f"""
\\pset footer off
SELECT r.rule_code, r.unit_price::text AS unit_price, r.unit,
       r.min_ton::text AS min_ton, r.max_ton::text AS max_ton,
       r.effective_from::text AS effective_from, r.effective_to::text AS effective_to
  FROM tariff_rule r
 WHERE {_rule_predicate(fee_type, category, args['tonnage'], args['biz_date'])};
"""
        rows = db.rows(sql, f"gt-tariff-{item['id']}")
        if len(rows) != 1:
            raise RuntimeError(f"{item['id']}：费率命中 {len(rows)} 条（应恰好 1 条）")
        r = rows[0]
        return {
            "unit_price": r["unit_price"],
            "unit": r["unit"],
            "band": _band(r["min_ton"], r["max_ton"]),
            "rule_code": r["rule_code"],
            "source": "tariff_rule（数据库执行结果）",
        }

    if kind == "quote":
        days = int(args.get("days", 1))
        danger_sql = "true" if dangerous else "false"
        relief = ("CASE WHEN r.fee_type = '过闸费' AND r.cargo_category IN ('能源', '农资', '化工') "
                  "THEN 0.70 ELSE 1 END")
        surcharge = (f"CASE WHEN r.fee_type IN ('过闸费', '港杂费') AND {danger_sql} "
                     f"THEN 1.30 ELSE 1 END")
        base = (f"CASE r.unit WHEN '元/吨' THEN {_lit(args['tonnage'])} * r.unit_price "
                f"WHEN '元/次' THEN r.unit_price "
                f"WHEN '元/吨天' THEN {_lit(args['tonnage'])} * r.unit_price * {days} END")
        sql = f"""
\\pset footer off
SELECT r.rule_code,
       round(({base}) * ({relief}) * ({surcharge}), 2)::text AS amount,
       r.unit_price::text AS unit_price, r.unit,
       r.min_ton::text AS min_ton, r.max_ton::text AS max_ton
  FROM tariff_rule r
 WHERE {_rule_predicate(fee_type, category, args['tonnage'], args['biz_date'])};
"""
        rows = db.rows(sql, f"gt-quote-{item['id']}")
        if len(rows) != 1:
            raise RuntimeError(f"{item['id']}：费率命中 {len(rows)} 条（应恰好 1 条）")
        r = rows[0]
        return {
            "amount": r["amount"],
            "currency": "CNY",
            "unit_price": r["unit_price"],
            "unit": r["unit"],
            "band": _band(r["min_ton"], r["max_ton"]),
            "days": days,
            "rule_code": r["rule_code"],
            "source": "tariff_rule + SQL round(..., 2)（数据库执行结果，与 Java 费用引擎互为独立实现）",
        }

    raise RuntimeError(f"{item['id']}：未知 kind {kind}")


def _band(min_ton: str, max_ton: str | None) -> str:
    """档位文本（与 Java `TariffRuleRow.bandText()` 同规则：去掉小数末尾多余的 0）。

    ⚠️ `psql --csv` 把 NULL 输出为**空字符串**而不是 None——不认这一点会在这里直接抛
    Decimal 转换错误，或更糟：把空串当成一个正常的档位上界。
    """
    def plain(v: str) -> str:
        if v is None or str(v).strip() == "":
            return "∞"
        return format(Decimal(v).normalize(), "f")
    return f"[{plain(min_ton)}, {plain(max_ton)})"


def _gold_traffic(db: Db, item: dict) -> dict:
    args = item["args"]
    metric = args["metric"]
    group_by = args.get("group_by", "无")
    top_n = int(args.get("top_n", 1))
    fee_type = args.get("fee_type")
    via = args.get("via_canal_only", False)

    label_expr = {
        "货类": "ct.category",
        "货种": "ct.name",
        "起运港": "coalesce(po.name, v.origin_port)",
        "目的港": "coalesce(pd.name, v.dest_port)",
        "客户": "coalesce(cu.name, '未知客户')",
        "月份": "to_char(w.created_at, 'YYYY-MM')",
        "无": "'合计'",
    }[group_by]
    group_clause = "" if group_by == "无" else f"GROUP BY {label_expr}"

    if metric == "费用金额":
        value_expr = "coalesce(round(sum(f.amount), 2), 0)"
        join_fee = "LEFT JOIN fee_record f ON f.waybill_no = w.waybill_no"
        if fee_type:
            join_fee += f" AND f.fee_type = {_lit(fee_type)}"
    elif metric == "吨位":
        value_expr = "coalesce(round(sum(w.weight_ton), 2), 0)"
        join_fee = ""
    elif metric == "运单数":
        value_expr = "count(*)"
        join_fee = ""
    else:
        raise RuntimeError(f"{item['id']}：未知 metric {metric}")

    sql = f"""
\\pset footer off
SELECT {label_expr} AS label, ({value_expr})::text AS value
  FROM waybill w
  JOIN voyage v ON v.id = w.voyage_id
  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
  LEFT JOIN customer cu ON cu.id = w.customer_id
  LEFT JOIN port po ON po.port_code = v.origin_port
  LEFT JOIN port pd ON pd.port_code = v.dest_port
  {join_fee}
 WHERE w.created_at::date >= DATE {_lit(args['date_from'])}
   AND w.created_at::date <= DATE {_lit(args['date_to'])}
   {'AND v.via_canal = true' if via else ''}
 {group_clause}
 ORDER BY ({value_expr}) DESC NULLS LAST, label ASC
 LIMIT {top_n};
"""
    rows = db.rows(sql, f"gt-traffic-{item['id']}")
    return {
        "metric": metric,
        "group_by": group_by,
        "rows": [[r["label"], r["value"]] for r in rows],
        "row_order": "值降序；值相同时标签升序（C-35 事前写定）",
        "source": "waybill/voyage/cargo_type/port/customer 聚合（数据库执行结果）",
    }


def _gold_waybill(db: Db, item: dict) -> dict:
    args = item["args"]
    fields = item.get("gold_fields", [])
    where = ["1 = 1"]
    if args.get("waybill_no"):
        where.append(f"w.waybill_no = {_lit(args['waybill_no'])}")
    if args.get("date_from"):
        where.append(f"w.created_at::date >= DATE {_lit(args['date_from'])}")
    if args.get("date_to"):
        where.append(f"w.created_at::date <= DATE {_lit(args['date_to'])}")
    if args.get("status"):
        where.append(f"w.status = {_lit(args['status'])}")
    if args.get("cargo_category"):
        where.append(f"ct.category = {_lit(args['cargo_category'])}")
    if args.get("via_canal_only"):
        where.append("v.via_canal = true")
    limit = int(args.get("limit", 20))

    sql = f"""
\\pset footer off
SELECT w.waybill_no, ct.name AS cargo_name, ct.category AS cargo_category,
       w.weight_ton::text AS weight_ton, round(w.freight_amount, 2)::text AS freight_amount,
       w.status, w.created_at::date::text AS biz_date
  FROM waybill w
  JOIN voyage v ON v.id = w.voyage_id
  JOIN cargo_type ct ON ct.cargo_code = w.cargo_code
 WHERE {' AND '.join(where)}
 ORDER BY w.created_at DESC, w.waybill_no ASC
 LIMIT {limit};
"""
    rows = db.rows(sql, f"gt-waybill-{item['id']}")
    gold: dict = {"source": "waybill/cargo_type（数据库执行结果；freight_amount 已证 = 各费种金额之和）"}
    if "waybill_nos" in fields:
        gold["count"] = len(rows)
        gold["waybill_nos"] = sorted(r["waybill_no"] for r in rows)
        gold["order_rule"] = "运单号升序（比对时按集合，顺序不计）"
    for f in fields:
        if f == "waybill_nos":
            continue
        if len(rows) != 1:
            raise RuntimeError(f"{item['id']}：字段级金标准要求唯一命中，实际 {len(rows)} 条")
        gold[f] = rows[0][f]
    return gold


# ── 组装与校验 ───────────────────────────────────────────────────────────────

def build(db: Db, l1_only: bool = False) -> dict:
    items = []
    for definition in L1_ITEMS:
        item = dict(definition)
        item["layer"] = "L1"
        item["split"] = "holdout" if item["id"] in L1_HOLDOUT else "dev"
        gold = gold_for(db, definition)
        expected = {"channel": "structured", "tool": tool_of(definition), "args": definition["args"], "gold": gold}
        items.append({
            "id": item["id"],
            "layer": "L1",
            "split": item["split"],
            "question": definition["question"],
            "expected": expected,
            "rubric": rubric_of(definition),
            "anchor": anchor_of(definition),
        })

    if not l1_only:
        if not EXTRA_PATH.exists():
            raise SystemExit(f"缺少 {EXTRA_PATH}（L3–L6 人工构造题，必须入库）；只想验证 L1 请加 --l1-only")
        extra = json.loads(EXTRA_PATH.read_text(encoding="utf-8"))
        for item in extra["items"]:
            items.append(item)

    doc = {
        "version": "v3",
        "builtFor": "第 3 周：Golden Set 扩充至 160 条（L1 40 + L2 40 + L3/L4/L5/L6 80）",
        "note": "L2 层 40 条见 golden-l2-v2.json（本轮未改）；本文件覆盖 L1/L3/L4/L5/L6。",
        "businessToday": BUSINESS_TODAY,
        "businessWindow": [BUSINESS_START, BUSINESS_END],
        "oracle": {
            "l1": "数据库执行结果（tariff_rule / waybill / voyage 等表；金额在 SQL 里 round(...,2)）",
            "compare": "数值按 Decimal 到分比较；聚合按结果集逐行比较；缺参按「是否追问」判定（C-35）",
        },
        "counts": _counts(items),
        "items": sorted(items, key=lambda it: it["id"]),
    }
    return doc


L1_HOLDOUT = {"L1-004", "L1-011", "L1-018", "L1-023", "L1-028", "L1-032", "L1-036", "L1-039"}


def tool_of(definition: dict) -> str:
    return {"tariff": "query_tariff", "quote": "quote_fee",
            "traffic": "query_traffic", "waybill": "query_waybill"}[definition["kind"]]


def rubric_of(definition: dict) -> str:
    kind = definition["kind"]
    if kind == "tariff":
        return "必须给出该费种在该业务日、该吨位档位下的**单价与单位**；查不到应说明无生效费率，不得编造"
    if kind == "quote":
        return "金额必须到分相等；须按口径施加危险货物加收 30% 与重点保供 70%（如适用），不得由模型自行算术"
    if kind == "traffic":
        return "数值按 Decimal 到分/到 0.01 相等；分组结果**逐行**相等（顺序按事前写定的并列规则）"
    return "所声明的字段值必须与数据库一致；列表类题按运单号集合比对（顺序不计）"


def anchor_of(definition: dict) -> str:
    kind = definition["kind"]
    if kind in ("tariff", "quote"):
        fee = definition["args"]["fee_type"]
        src = {"过闸费": "06/07-过闸费费率表", "港杂费": "09-港杂与装卸费",
               "装卸费": "09-港杂与装卸费", "引航费": "08-引航费", "停泊费": "08-引航费"}[fee]
        return f"tariff_rule 表 + 语料 {src}（计费口径见 25-第十二条/第二十七条/第二十九条）"
    if kind == "traffic":
        return "waybill.voyage_id/via_canal + cargo_type.category + fee_record.amount"
    return "waybill.waybill_no/weight_ton/freight_amount/status"


def _counts(items: list) -> dict:
    counts: dict = {"total": len(items), "byLayer": {}, "bySplit": {}, "holdoutTotal": 0}
    for it in items:
        counts["byLayer"][it["layer"]] = counts["byLayer"].get(it["layer"], 0) + 1
        counts["bySplit"][it["split"]] = counts["bySplit"].get(it["split"], 0) + 1
    counts["holdoutTotal"] = counts["bySplit"].get("holdout", 0)
    return counts


def check(doc: dict, errors: list, warnings: list, l1_only: bool = False) -> None:
    items = doc["items"]
    ids = [it["id"] for it in items]
    if len(ids) != len(set(ids)):
        dup = sorted({i for i in ids if ids.count(i) > 1})
        errors.append(f"id 重复：{dup}")

    by_layer: dict = {}
    for it in items:
        by_layer.setdefault(it["layer"], []).append(it)
    expected_layer_counts = ({"L1": 40} if l1_only
                             else {"L1": 40, "L3": 20, "L4": 25, "L5": 20, "L6": 15})
    for layer, want in expected_layer_counts.items():
        got = len(by_layer.get(layer, []))
        if got != want:
            errors.append(f"{layer} 条数应为 {want}，实际 {got}")

    expected_holdout = ({"L1": 8} if l1_only else {"L1": 8, "L3": 4, "L4": 4, "L5": 3, "L6": 3})
    for layer, want in expected_holdout.items():
        got = sum(1 for it in by_layer.get(layer, []) if it.get("split") == "holdout")
        if got != want:
            errors.append(f"{layer} 保留集应 {want} 条，实际 {got}")

    for it in items:
        for field in ("id", "layer", "split", "question", "expected", "rubric", "anchor"):
            if not it.get(field):
                errors.append(f"{it.get('id')}：缺字段 {field}")
        if it.get("split") not in ("dev", "holdout"):
            errors.append(f"{it['id']}：split 非法 {it.get('split')}")
        if len(it.get("question", "")) < 6:
            errors.append(f"{it['id']}：题面过短")
        exp = it.get("expected", {})
        layer = it.get("layer")
        if layer == "L1":
            tool = exp.get("tool")
            if tool not in TOOLS:
                errors.append(f"{it['id']}：未知工具 {tool}")
            else:
                bad = set(exp.get("args", {})) - TOOL_ARGS[tool]
                if bad:
                    errors.append(f"{it['id']}：{tool} 不支持参数 {sorted(bad)}")
                _check_args_against_contract(it["id"], tool, exp.get("args", {}), errors)
            if not exp.get("gold"):
                errors.append(f"{it['id']}：缺金标准 gold")
        elif layer == "L3":
            if not exp.get("mustSayNoData"):
                errors.append(f"{it['id']}：L3 必须标 mustSayNoData")
        elif layer == "L4":
            dlg = exp.get("dialogue") or []
            if len(dlg) < 2:
                errors.append(f"{it['id']}：L4 至少两轮")
            for turn in dlg:
                if turn.get("expectTool") not in TOOLS:
                    errors.append(f"{it['id']}：第 {turn.get('turn')} 轮工具非法")
                    continue
                bad = set(turn.get("expectArgs", {})) - TOOL_ARGS.get(turn.get("expectTool"), set())
                if bad:
                    errors.append(f"{it['id']}：第 {turn.get('turn')} 轮参数非法 {sorted(bad)}")
                _check_args_against_contract(
                    f"{it['id']}(第{turn.get('turn')}轮)", turn["expectTool"],
                    turn.get("expectArgs", {}), errors)
        elif layer == "L5":
            if not exp.get("needStructured") or not exp.get("needRetrieval"):
                errors.append(f"{it['id']}：L5 必须同时声明结构化与检索需求")
        elif layer == "L6":
            if exp.get("expectStatus") not in ("NEEDS_CLARIFICATION", "REJECTED", "DEGRADED"):
                errors.append(f"{it['id']}：L6 的 expectStatus 非法 {exp.get('expectStatus')}")
            # L6 的 missingParams 语义 = 「应向用户追问的参数」，故只校验它确实是**某个工具的合法参数名**，
            # ⛔ 不要求它落在该工具的 schema required 里（query_waybill 的 required 为空数组，
            # 靠 notes 做业务层约束——把它读成 schema required 会判错，见 进度看板 §0.3 口径问题 2）。
            known = set().union(*TOOL_ARGS.values()) if TOOL_ARGS else set()
            for p in exp.get("missingParams") or []:
                if p not in known:
                    errors.append(f"{it['id']}：missingParams 里的「{p}」不是任何工具的合法参数名")

    # 反照抄：题面不得直接出现语料条款标题式措辞（会让检索指标虚高）
    for it in items:
        q = it["question"]
        if q.count("第") >= 2 and q.count("条") >= 2:
            warnings.append(f"{it['id']}：题面像条款摘抄，请复核：{q[:40]}")

    counts = _counts(items)
    want_total = 40 if l1_only else 120
    if counts["total"] != want_total:
        errors.append(f"总条数应为 {want_total}（新增部分），实际 {counts['total']}")


def main() -> int:
    ap = argparse.ArgumentParser(description="构建 Golden Set v3（L1 由数据库反查 + L3–L6 人工构造）")
    ap.add_argument("--psql", default=str(DEFAULT_PSQL))
    ap.add_argument("--check", action="store_true", help="只校验已有产物，不重新构建")
    ap.add_argument("--l1-only", action="store_true", help="只构建 L1（L3–L6 文件尚未就绪时用于自查）")
    ap.add_argument("--out", default=str(OUT_PATH))
    args = ap.parse_args()

    out = Path(args.out)
    errors: list = []
    warnings: list = []

    if args.check:
        doc = json.loads(out.read_text(encoding="utf-8"))
    else:
        env = load_env(ROOT)
        db = Db(Path(args.psql), env,
                os.environ.get("POSTGRES_HOST", "127.0.0.1"),
                os.environ.get("POSTGRES_PORT", "5432"),
                os.environ.get("POSTGRES_DB", "canalmind"),
                os.environ.get("POSTGRES_USER", "postgres"))
        doc = build(db, l1_only=args.l1_only)

    check(doc, errors, warnings, l1_only=args.l1_only)

    text = json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if not args.check and not errors:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(text.encode("utf-8"))          # 无 BOM + LF

    counts = doc.get("counts", {})
    print("层分布：", counts.get("byLayer"))
    print("切分：", counts.get("bySplit"), "（保留集合计", counts.get("holdoutTotal"), "）")
    for w in warnings:
        print("  [WARN]", w)
    for e in errors:
        print("  [FAIL]", e)
    if errors:
        print(f"[FAIL] Golden Set v3 校验失败 {len(errors)} 项")
        return 1
    if args.check:
        print("[PASS] Golden Set v3 校验通过")
    else:
        print(f"[PASS] 已写出 {out}（{counts.get('total')} 条新增，L2 另见 golden-l2-v2.json）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
