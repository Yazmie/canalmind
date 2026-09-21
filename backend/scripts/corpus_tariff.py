#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语料费率表解析器 —— **费率口径的唯一事实来源**

================================================================================
决策依据
================================================================================
`docs/DECISIONS.md` **C-31**（2026-09-22，用户确认）：
**以语料为费率口径的权威源，数据库 `tariff_rule` / `fee_record` 由语料派生。**

为什么要有这个文件（而不是继续在 `generate_seed_data.py` 里用公式造费率）：

  实测两侧在**四个维度上同时不同**（2026-09-22 查库 + 读语料原点）：

  | 维度 | 语料（`06`/`09` 等） | 库内旧值（公式生成） |
  |---|---|---|
  | 吨位档位 | 过闸费 `0/500/2000/5000` | `0/1000/3000` |
  | 费率数值 | 建材 2026 版 `2.55` 元/吨 | `1.0200` 元/吨 |
  | 分档依据 | 引航费/停泊费按**船舶总吨位** | 按**货类** |
  | 计费单位 | 港杂费 `元/次` | `元/吨` |

  旧生成器用 `BASE_UNIT_PRICE × 货类系数 × 档位折扣 × 1.015^期数` 造数，
  与语料**从未做过数值级对齐**——而 `docs/规则语料/README.md` §4 早已声称
  「费率表取值必须与 `tariff_rule` 一致」。**规则写了、检查没做**，
  于是漂移长期无人发现（与 `工程复盘/数据一致性/钢材归类跨来源冲突.md` 同一类坑）。

  本文件的作用：**解析语料 → 生成 `tariff_rule` 行**，让漂移在结构上不可能发生；
  配套的独立复核见 `verify_corpus_db_rates.py`（它**重新解析一遍**再与库比对，
  不复用本文件的输出，避免"自己证明自己"）。

================================================================================
解析口径（每条都有语料锚点，不得凭记忆改动）
================================================================================
1. **只解析"费率表"**：表头必须是
   `费种 | 货类 | 吨位下限（吨） | 吨位上限（吨） | 费率 | 计费单位`
   ⇒ `05` 第四条的「费种/计费单位/计费对象/说明」表与 `20` 第十一/十二条的
   「计费方式」表**结构不同，自动不参与解析**（`20` 的两张表属未实现清单，
   见 `docs/规则语料/README.md` 与费用引擎的「已实现/未实现」清单）。
2. **货类 `—` 表示"不区分货类"**（`08` 第二条：*"引航费与停泊费均按船舶总吨位分段，
   与货物所属货类无关，不得因货类不同而调整"*）⇒ 落库为哨兵值 `不区分`。
3. **吨位上限 `（不封顶）` ⇒ NULL**，且**左闭右开** `[min, max)`：
   依据 `25` 第二十九条与 `docs/规则语料/README.md` §4（C-36① 把 `06` 的
   生效期字段也统一到这条口径上）。
4. **生效期 `[effective_from, effective_to)`**：取自每篇文档顶部元数据块的
   「生效期」字段；`长期有效` ⇒ NULL。`06`（`2026-09-16 至 2027-01-01`）
   与 `07`（`2027-01-01` 起）必须**无缝衔接、无空档无重叠**（断言 F）。
5. **计费单位与费种的对应是完整枚举**（`25` 第二十七条）：
   过闸费/装卸费 `元/吨`；引航费/港杂费 `元/次`；停泊费 `元/吨天`（断言 C）。
6. **计费吨位口径**（`25` 第十二条）：过闸费、装卸费、港杂费取**载货吨位**；
   引航费、停泊费取**船舶总吨位**（断言 D）。

================================================================================
断言（全部为"解析期即失败"，不给静默通道）
================================================================================
A. 表头必须逐字一致，且费率行必须能解析成 6 列
B. 档位必须**从 0 开始、严格衔接、末档不封顶**（`[0,500) [500,2000) [2000,5000) [5000,∞)`）
C. 计费单位必须落在该费种的枚举内
D. 计费吨位口径必须与 `25` 第十二条一致（只有引航费/停泊费是船舶总吨位）
E. 货类必须落在七个大类内，或为哨兵 `不区分`（且只有引航费/停泊费可为 `不区分`）
F. 同一（费种 + 货类 + 档位 + 吨位口径）下**生效期必须首尾相接、无空档无重叠**
G. 解析结果非空，且每个（费种 + 货类）至少有一条规则
================================================================================
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import re
import sys
from dataclasses import dataclass, asdict

# ----------------------------------------------------------------------------
# 常量：语料位置与枚举
# ----------------------------------------------------------------------------

_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(_THIS_DIR, "..", ".."))
CORPUS_DIR = os.path.join(REPO_ROOT, "docs", "规则语料")

# 参与解析的语料文件（按文件名前缀排序无关，解析顺序固定为下列顺序）
RATE_TABLE_FILES = [
    "06-过闸费费率表（2026版）.md",
    "07-过闸费费率表（2027版）.md",
    "08-引航费与停泊费费率表.md",
    "09-港杂费与装卸费费率表.md",
]

# 费率表表头（逐字，依据语料实际写法）
RATE_TABLE_HEADER = ["费种", "货类", "吨位下限（吨）", "吨位上限（吨）", "费率", "计费单位"]

# `25` 第二十七条：计费单位完整枚举
UNIT_BY_FEE_TYPE = {
    "过闸费": "元/吨",
    "装卸费": "元/吨",
    "引航费": "元/次",
    "港杂费": "元/次",
    "停泊费": "元/吨天",
}

# `25` 第十二条：计费吨位口径
TONNAGE_BASIS = {
    "过闸费": "载货吨位",
    "装卸费": "载货吨位",
    "港杂费": "载货吨位",
    "引航费": "船舶总吨位",
    "停泊费": "船舶总吨位",
}

CARGO_CATEGORIES = ["建材", "能源", "金属", "农资", "化工", "集装箱", "其他"]
NO_CATEGORY = "不区分"          # 哨兵：语料中的 `—`
UNBOUNDED_MARK = "（不封顶）"
NO_CATEGORY_MARK = "—"
LONG_TERM_MARK = "长期有效"


class CorpusTariffError(RuntimeError):
    """语料费率表解析失败（含断言失败）。"""


@dataclass(frozen=True)
class TariffRule:
    fee_type: str
    cargo_category: str
    min_ton: str            # 用字符串保留原始精度（NUMERIC 落库口径一致）
    max_ton: str | None
    unit_price: str
    unit: str
    effective_from: str
    effective_to: str | None
    tonnage_basis: str
    source_file: str
    source_line: int
    rule_code: str = ""

    def period_range(self):
        return (self.effective_from, self.effective_to or "9999-12-31")


# ----------------------------------------------------------------------------
# 解析
# ----------------------------------------------------------------------------

_NUM_RE = re.compile(r"^-?\d+(?:\.\d+)?$")
_DATE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})")


def _split_pipe_row(line: str):
    s = line.strip()
    if not (s.startswith("|") and s.endswith("|")):
        return None
    # 去掉首尾竖线后按竖线切分；语料表格不含转义竖线（校验器已保证标准管道表）
    return [c.strip() for c in s[1:-1].split("|")]


def _norm_num(token: str, where: str) -> str:
    """把语料里的数值规范成落库字符串（去千分位、统一 0 为 '0'）。"""
    t = token.replace(",", "").strip()
    if not _NUM_RE.match(t):
        raise CorpusTariffError(f"{where}：费率/吨位不是合法数字：{token!r}")
    return t


def parse_metadata_period(text: str, where: str):
    """从元数据块解析「生效期」→ (from, to|None)，左闭右开（C-36①）。"""
    for line in text.splitlines():
        cells = _split_pipe_row(line)
        if not cells or len(cells) != 2 or cells[0] != "生效期":
            continue
        raw = cells[1]
        left, _, right = raw.partition("至")
        if not left or not right:
            raise CorpusTariffError(f"{where}：生效期字段无法解析：{raw!r}")
        m_from = _DATE_RE.search(left)
        if not m_from:
            raise CorpusTariffError(f"{where}：生效期起始日无法解析：{raw!r}")
        eff_from = m_from.group(1)
        if LONG_TERM_MARK in right:
            return eff_from, None
        m_to = _DATE_RE.search(right)
        if not m_to:
            raise CorpusTariffError(f"{where}：生效期终止日无法解析：{raw!r}")
        eff_to = m_to.group(1)
        if eff_to <= eff_from:
            raise CorpusTariffError(
                f"{where}：生效期终止日不晚于起始日（{eff_from} → {eff_to}）；"
                f"注意本项目口径是左闭右开，写\"至 X 日\"时 X 必须是不含的那一天")
        return eff_from, eff_to
    raise CorpusTariffError(f"{where}：未找到元数据「生效期」字段")


def _assert_header(cells, where):
    if cells != RATE_TABLE_HEADER:                                     # 断言 A
        raise CorpusTariffError(
            f"{where}：费率表表头不符合约定。\n  期望：{RATE_TABLE_HEADER}\n  实际：{cells}")


def _parse_cells(cells, where_line, source_file, line_no, eff_from, eff_to):
    if len(cells) != 6:
        raise CorpusTariffError(f"{where_line}：费率行应有 6 列，实际 {len(cells)} 列：{cells}")
    fee_type, cargo_raw, min_raw, max_raw, price_raw, unit = cells

    if fee_type not in UNIT_BY_FEE_TYPE:
        raise CorpusTariffError(f"{where_line}：未知费种 {fee_type!r}（五费种为完整枚举）")

    cargo = NO_CATEGORY if cargo_raw == NO_CATEGORY_MARK else cargo_raw
    if cargo != NO_CATEGORY and cargo not in CARGO_CATEGORIES:          # 断言 E
        raise CorpusTariffError(f"{where_line}：未知货类 {cargo_raw!r}")
    if cargo == NO_CATEGORY and fee_type not in ("引航费", "停泊费"):
        raise CorpusTariffError(
            f"{where_line}：只有引航费/停泊费可以不区分货类（`25` 第十二条），"
            f"但 {fee_type} 写成了 {NO_CATEGORY_MARK}")

    if unit != UNIT_BY_FEE_TYPE[fee_type]:                              # 断言 C
        raise CorpusTariffError(
            f"{where_line}：{fee_type} 的计费单位应为 {UNIT_BY_FEE_TYPE[fee_type]}，"
            f"语料写成 {unit}（口径依据 `25` 第二十七条）")

    min_ton = _norm_num(min_raw, where_line)
    if max_raw == UNBOUNDED_MARK:
        max_ton = None
    else:
        max_ton = _norm_num(max_raw, where_line)
        if float(max_ton) <= float(min_ton):
            raise CorpusTariffError(f"{where_line}：吨位上限不大于下限（{min_ton} → {max_ton}）")
    unit_price = _norm_num(price_raw, where_line)

    return TariffRule(
        fee_type=fee_type,
        cargo_category=cargo,
        min_ton=min_ton,
        max_ton=max_ton,
        unit_price=unit_price,
        unit=unit,
        effective_from=eff_from,
        effective_to=eff_to,
        tonnage_basis=TONNAGE_BASIS[fee_type],                           # 断言 D
        source_file=source_file,
        source_line=line_no,
    )


def parse_file(path: str):
    """解析单个语料文件中的所有费率表行。"""
    source_file = os.path.basename(path)
    with open(path, "r", encoding="utf-8", newline="") as fh:
        lines = fh.read().split("\n")

    eff_from, eff_to = parse_metadata_period("\n".join(lines), source_file)

    rules = []
    expect_header = False
    for idx, line in enumerate(lines, start=1):
        cells = _split_pipe_row(line)
        if cells is None:
            expect_header = False
            continue
        # 分隔行（|---|---|）
        if all(set(c) <= set("-: ") and c for c in cells):
            continue
        # 费率表表头
        if "吨位下限（吨）" in cells:
            _assert_header(cells, f"{source_file} 第 {idx} 行")
            expect_header = True
            continue
        if not expect_header:
            continue
        where = f"{source_file} 第 {idx} 行"
        rules.append(_parse_cells(cells, where, source_file, idx, eff_from, eff_to))

    if not rules:
        raise CorpusTariffError(f"{source_file}：未解析到任何费率行")
    return rules


def load_rules(corpus_dir: str = CORPUS_DIR):
    """解析全部费率表文件并做跨文件断言（B/F/G）。"""
    all_rules = []
    for fname in RATE_TABLE_FILES:
        path = os.path.join(corpus_dir, fname)
        if not os.path.exists(path):
            raise CorpusTariffError(f"费率表文件不存在：{path}")
        all_rules.extend(parse_file(path))

    _assert_bands_contiguous(all_rules)                                  # 断言 B
    _assert_periods_contiguous(all_rules)                                # 断言 F
    _assert_non_empty(all_rules)                                         # 断言 G
    return _with_rule_codes(all_rules)


def _assert_bands_contiguous(rules):
    """断言 B：同一（费种 + 货类 + 生效期）下，档位从 0 开始、严格衔接、末档不封顶。"""
    groups = {}
    for r in rules:
        key = (r.fee_type, r.cargo_category, r.effective_from, r.effective_to)
        groups.setdefault(key, []).append(r)
    for key, group in sorted(groups.items()):
        banded = sorted(group, key=lambda r: float(r.min_ton))
        where = f"{banded[0].source_file} 第 {banded[0].source_line} 行（{key[0]}/{key[1]}）"
        if float(banded[0].min_ton) != 0.0:
            raise CorpusTariffError(f"{where}：档位未从 0 开始，首档下限 = {banded[0].min_ton}")
        for prev, cur in zip(banded, banded[1:]):
            if prev.max_ton is None:
                raise CorpusTariffError(f"{where}：不封顶档之后仍有更大档位（{cur.min_ton}）")
            if float(prev.max_ton) != float(cur.min_ton):
                raise CorpusTariffError(
                    f"{where}：档位不衔接或重叠（{prev.min_ton}~{prev.max_ton} 之后是 "
                    f"{cur.min_ton}）；左闭右开区间必须首尾相接")
        if banded[-1].max_ton is not None:
            raise CorpusTariffError(f"{where}：末档未不封顶（上限 = {banded[-1].max_ton}）")


def _assert_periods_contiguous(rules):
    """断言 F：同一（费种 + 货类 + 档位 + 吨位口径）下生效期首尾相接、无空档无重叠。"""
    groups = {}
    for r in rules:
        key = (r.fee_type, r.cargo_category, r.tonnage_basis, r.min_ton, r.max_ton)
        groups.setdefault(key, []).append(r)
    for key, group in sorted(groups.items(), key=lambda kv: str(kv[0])):
        banded = sorted(group, key=lambda r: r.effective_from)
        where = f"{key[0]}/{key[1]}/档位[{banded[0].min_ton}, {banded[0].max_ton})"
        if len(banded) == 1:
            continue
        for prev, cur in zip(banded, banded[1:]):
            if prev.effective_to is None:
                raise CorpusTariffError(f"{where}：长期有效档之后仍有后续生效期（{cur.effective_from}）")
            if prev.effective_to != cur.effective_from:
                raise CorpusTariffError(
                    f"{where}：生效期不衔接（{prev.effective_from}~{prev.effective_to} 之后是 "
                    f"{cur.effective_from}）；按左闭右开口径必须前者的终止日 == 后者的起始日")


def _assert_non_empty(rules):
    """断言 G：每个（费种 + 货类）至少有一条规则，且每类档位覆盖 [0, ∞)。"""
    seen = {(r.fee_type, r.cargo_category) for r in rules}
    for fee_type, basis in TONNAGE_BASIS.items():
        expected = {NO_CATEGORY} if basis == "船舶总吨位" else set(CARGO_CATEGORIES)
        missing = expected - {c for f, c in seen if f == fee_type}
        if missing:
            raise CorpusTariffError(f"{fee_type}：缺少以下货类的费率规则 {sorted(missing)}")


def _band_label(min_ton: str) -> str:
    """把档位下限转成 rule_code 里的短标签。

    ⚠️ 不能直接用 `rstrip("0")`——它会把 `1000` 剥成 `1`、`5000` 剥成 `5`，
    于是 `[0,500)` 与 `[5000,∞)` 会撞进同一个 code（实测踩到，见下方 dupes 断言）。
    只有**小数部分**的尾随 0 才可剥。
    """
    s = min_ton.strip()
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s or "0"


def _with_rule_codes(rules):
    """生成稳定可读的 rule_code（标识"由语料派生"，并自带来源与档位）。

    形如 `CT-过闸费-建材-0-20260916`：
      - 前缀 `CT` = Corpus Tariff（表明"由语料派生"，与旧的公式生成码可区分）
      - 含生效起始日，便于人工核对是哪一版
      - ⛔ 不含随机数/序号：同输入同输出，重跑可复现
    """
    out = []
    for r in rules:
        code = "CT-{}-{}-{}-{}".format(
            r.fee_type, r.cargo_category, _band_label(r.min_ton),
            r.effective_from.replace("-", ""))
        out.append(TariffRule(**{**asdict(r), "rule_code": code}))
    codes = [r.rule_code for r in out]
    dupes = sorted({c for c in codes if codes.count(c) > 1})
    if dupes:
        raise CorpusTariffError(f"生成的 rule_code 有重复：{dupes}")
    return out


# ----------------------------------------------------------------------------
# 派生辅助：供 generate_seed_data 与复核脚本共用
# ----------------------------------------------------------------------------

def match_rule(rules, fee_type, cargo_category, tonnage, biz_date, tonnage_basis):
    """按（费种 + 货类 + 吨位口径 + 吨位档 + 业务发生日）命中**唯一**一条规则。

    左闭右开：`min <= tonnage < max`、`effective_from <= biz_date < effective_to`。
    命中 0 条或多条都视为语料缺陷 ⇒ 抛错（不给"静默取第一条"的通道）。
    """
    hits = []
    for r in rules:
        if r.fee_type != fee_type or r.cargo_category != cargo_category:
            continue
        if r.tonnage_basis != tonnage_basis:
            continue
        t = float(tonnage)
        if t < float(r.min_ton):
            continue
        if r.max_ton is not None and t >= float(r.max_ton):
            continue
        if biz_date < r.effective_from:
            continue
        if r.effective_to is not None and biz_date >= r.effective_to:
            continue
        hits.append(r)
    if len(hits) != 1:
        raise CorpusTariffError(
            f"费率命中数 != 1（实得 {len(hits)}）：{fee_type} / {cargo_category} / "
            f"吨位 {tonnage} / 口径 {tonnage_basis} / 业务日 {biz_date}")
    return hits[0]


def dump_json(rules, path: str | None = None):
    payload = [
        {
            "rule_code": r.rule_code,
            "fee_type": r.fee_type,
            "cargo_category": r.cargo_category,
            "min_ton": r.min_ton,
            "max_ton": r.max_ton,
            "unit_price": r.unit_price,
            "unit": r.unit,
            "tonnage_basis": r.tonnage_basis,
            "effective_from": r.effective_from,
            "effective_to": r.effective_to,
            "source": f"{r.source_file}:{r.source_line}",
        }
        for r in rules
    ]
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if path:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    return text


def main():
    ap = argparse.ArgumentParser(description="解析语料费率表（唯一事实来源）")
    ap.add_argument("--corpus", default=CORPUS_DIR)
    ap.add_argument("--dump", default=None, help="输出 JSON 证据文件路径")
    args = ap.parse_args()

    rules = load_rules(args.corpus)
    by_key = {}
    for r in rules:
        by_key.setdefault((r.fee_type, r.cargo_category), []).append(r)

    print("=" * 74)
    print(f"语料费率表解析：{len(rules)} 条规则")
    print("=" * 74)
    for (fee_type, cargo), group in sorted(by_key.items()):
        periods = sorted({(g.effective_from, g.effective_to) for g in group})
        bands = sorted({(g.min_ton, g.max_ton) for g in group}, key=lambda b: float(b[0]))
        price = " / ".join(g.unit_price for g in sorted(group, key=lambda g: float(g.min_ton)))
        print(f"  {fee_type:<4} {cargo:<5} 档位 {len(bands)} 个 生效期 {len(periods)} 段 "
              f"单位 {group[0].unit:<6} 口径 {group[0].tonnage_basis}")
        print(f"      费率序列：{price}")
    print("-" * 74)
    if args.dump:
        dump_json(rules, args.dump)
        print(f"  证据文件 → {args.dump}")
    print("[PASS] 语料费率表解析与断言全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
