#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CanalMind 仿真数据生成脚本
==========================

对应规格书 §8「第 1 周」：造 3–5 万行仿真数据（费率表带生效期）

设计要点
--------
1. **确定性**：固定随机种子，任何人任何机器跑出的数据完全一致。
   评测报告要可复现，数据集就不能每次不同。
2. **费率带生效期**：tariff_rule 每条规则有 effective_from / effective_to，
   且用「吨位段 × 生效期」双重切分保证任意两条规则不重叠
   （对应 V1 迁移里的 tariff_no_overlap EXCLUDE 约束）。
   这是「同一问题在不同日期有不同答案」的物理载体——
   没有它，Text-to-SQL 类任务就退化成单表查询。
3. **仅输出 CSV + 装载脚本，不入库**：遵守 DECISIONS.md C-09，
   仿真数据只提交生成脚本，csv 产物在 .gitignore 中。

时间窗说明（决策 P-04 方案 B）
------------------------------
平陆运河 2026-09-16 正式通航。业务窗口取「通航后一年」
（2026-09-16 ~ 2027-09-15），系统「今天」为 2027-09-15。

为什么不是「截至真实今天」：运河 2026-09-16 才通航，若窗口覆盖通航前，
运河业务在数据中占比仅 0.92%、过闸费仅 0.30%，运河专项查询无法评测。
窗口整体后移后，运河成为数据主体。

⚠️ 代价与纪律：本数据集是**前瞻模拟**（时间点在真实今天之后），
不是历史真实数据。README 必须主动交底，且不得对外宣称任何指标。

用法
----
    python generate_seed_data.py                 # 输出到 ./seed_output
    python generate_seed_data.py --out D:\\tmp\\seed
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import random
import sys
from datetime import date, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP

# 语料费率表解析器（C-31：费率的**唯一事实来源**）。
# 同目录模块，故直接把脚本所在目录放进 sys.path——这样无论从哪个工作目录
# 调用本脚本（setup_local_db.ps1 / 手工 python backend\scripts\…）都能导入。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import corpus_tariff  # noqa: E402  (必须在 sys.path 调整之后导入)

# ---------------------------------------------------------------------------
# 全局参数
# ---------------------------------------------------------------------------

SEED = 20260918

# 平陆运河正式通航日（真实事件：2026-09-16）
CANAL_OPEN_DATE = date(2026, 9, 16)

# 业务数据时间窗（决策 P-04 方案 B：通航后一年）
#
# 为什么从通航日起算：运河通航前不存在运河业务，若窗口覆盖通航前，
# 「过闸费」「运河吞吐量」等运河专项查询会因数据稀薄而无法评测
# （实测：窗口截至 2026-09-18 时，运河业务仅占 0.92%、过闸费仅占 0.30%）。
# 故窗口整体后移到通航之后，使运河成为数据主体。
#
# ⚠️ README 须主动交底：本数据集为「前瞻模拟」，非历史真实数据。
BUSINESS_START = CANAL_OPEN_DATE
BUSINESS_END = date(2027, 9, 15)

# 系统「今天」：仿真数据的时间原点。
# 用于推断航次/运单状态（远早于此日期的已完成，近期的在途/计划）。
# 问答中若涉及「现在 / 目前」，语义指向此日期。
SYSTEM_TODAY = BUSINESS_END

# 规模目标：核心事实表 3–5 万行
N_VESSEL = 240
N_CUSTOMER = 320
N_VOYAGE = 2400
N_WAYBILL = 32000          # 核心事实表
DOCS_PER_WAYBILL_AVG = 1.2
FEES_PER_WAYBILL_AVG = 1.5

rng = random.Random(SEED)


def daterange_days(start: date, end: date):
    """[start, end] 内的所有日期。"""
    d = start
    while d <= end:
        yield d
        d += timedelta(days=1)


def random_date(start: date, end: date) -> date:
    span = (end - start).days
    return start + timedelta(days=rng.randint(0, span))


def random_dt(start: date, end: date) -> datetime:
    """在日期区间内取一个带时间点的 datetime。"""
    d = random_date(start, end)
    return datetime(d.year, d.month, d.day,
                    rng.randint(0, 23), rng.randint(0, 59), rng.randint(0, 59))


def q(v, places: int = 2) -> str:
    """格式化为固定小数位的字符串（避免 float 科学计数法，CSV 直接可用）。"""
    return str(Decimal(str(v)).quantize(Decimal("1." + "0" * places)))


# ---------------------------------------------------------------------------
# 静态基础数据
# ---------------------------------------------------------------------------

# 港区：平陆运河沿线 + 西江干线 + 北部湾沿海
PORTS = [
    # (port_code, name, river_section, type, is_canal_node)
    ("NN01", "南宁港六景作业区", "郁江",     "内河港", False),
    ("NN02", "南宁港牛湾作业区", "郁江",     "内河港", False),
    ("NN03", "南宁港沙井作业区", "郁江",     "内河港", False),
    ("HZ01", "横州港",           "郁江",     "内河港", False),
    ("HZ02", "平塘江口港点",     "平陆运河", "枢纽",   True),
    ("GG01", "贵港港",           "郁江",     "内河港", False),
    ("GP01", "桂平港",           "郁江",     "中转港", False),
    ("WZ01", "梧州港",           "西江",     "中转港", False),
    ("LW01", "陆屋港",           "平陆运河", "枢纽",   True),
    ("QZ01", "钦州内河港",       "钦江",     "内河港", True),
    ("QZ02", "钦州港",           "沿海",     "海港",   True),
    ("QZ03", "钦州港保税港区",   "沿海",     "海港",   True),
    ("BH01", "北海港",           "沿海",     "海港",   False),
    ("FC01", "防城港",           "沿海",     "海港",   False),
]

# 运河节点港集合（决策 C-18）：voyage.via_canal 的取值口径即
# 「起点港或终点港属于本集合」。口径来源见 docs/DECISIONS.md C-18。
CANAL_NODES = frozenset(p[0] for p in PORTS if p[4])

# 船闸：平陆运河三大梯级枢纽，各双线（两个闸室）
LOCKS = [
    # (lock_id, name, chamber_no, capacity_ton, length, width, is_double_line)
    ("MD01", "马道枢纽船闸", 1, 3000, 300.0, 34.0, True),
    ("MD01", "马道枢纽船闸", 2, 3000, 300.0, 34.0, True),
    ("QS01", "企石枢纽船闸", 1, 3000, 300.0, 34.0, True),
    ("QS01", "企石枢纽船闸", 2, 3000, 300.0, 34.0, True),
    ("QN01", "青年枢纽船闸", 1, 3000, 300.0, 34.0, True),
    ("QN01", "青年枢纽船闸", 2, 3000, 300.0, 34.0, True),
]

# 货类
CARGO_TYPES = [
    # (cargo_code, name, category, is_dangerous, hazard_class)
    ("JZ01", "机制砂",       "建材",   False, None),
    ("JZ02", "碎石",         "建材",   False, None),
    ("JZ03", "水泥",         "建材",   False, None),
    ("JZ04", "水泥熟料",     "建材",   False, None),
    ("JZ05", "混凝土预制件", "建材",   False, None),
    ("NY01", "动力煤",       "能源",   False, None),
    ("NY02", "焦炭",         "能源",   False, None),
    ("NY03", "成品油",       "能源",   True,  "3"),
    ("JS01", "铁矿石",       "金属",   False, None),
    ("JS02", "铝土矿",       "金属",   False, None),
    ("JS03", "钢材",         "金属",   False, None),
    ("JS04", "锰矿",         "金属",   False, None),
    ("NZ01", "化肥",         "农资",   False, None),
    ("NZ02", "粮食",         "农资",   False, None),
    ("NZ03", "食糖",         "农资",   False, None),
    ("HG01", "烧碱",         "化工",   True,  "8"),
    ("HG02", "硫酸",         "化工",   True,  "8"),
    ("HG03", "液体化工品",   "化工",   True,  "3"),
    ("JZX1", "集装箱货物",   "集装箱", False, None),
    ("QT01", "机械设备",     "其他",   False, None),
    ("QT02", "日用百货",     "其他",   False, None),
]

VESSEL_TYPES = ["干散货船", "集装箱船", "多用途船", "化学品船", "驳船"]
# 各船型的载重吨区间
VESSEL_DWT_RANGE = {
    "干散货船":  (1500, 5500),
    "集装箱船":  (2000, 5000),
    "多用途船":  (1500, 4000),
    "化学品船":  (1000, 3000),
    "驳船":      (800, 2000),
}

# vessel 的列序（**唯一真源**：CSV 表头、TABLES 声明、按下标取值的代码都引它）。
# 为什么要这样：gross_tonnage 是 C-31 新增的列，若各处各写一份列序，
# 下标就会漂移——"取到 built_year 当总吨位"这种错误不会报错，只会静默算错费率。
VESSEL_COLS = ["vessel_id", "name", "vessel_type", "deadweight_ton",
               "gross_tonnage", "draft_m", "owner_id", "built_year"]
VESSEL_IDX_GROSS_TON = VESSEL_COLS.index("gross_tonnage")

# 船舶总吨位与载重吨的换算（**数据生成约定，不是语料规则**）
# 语料 `25` 第十一条只规定"总吨位是容积指标、以检验证书载明为准、不等同于载重吨"，
# 并未给出换算关系；仿真数据取固定系数 0.55（干散货船的量级），
# 使总吨位与载重吨正相关但**不相等**——否则就与"不等同于载重吨"这一条相矛盾。
GROSS_TON_FACTOR = Decimal("0.55")

FEE_TYPES = ["过闸费", "港杂费", "装卸费", "引航费", "停泊费"]

# ---------------------------------------------------------------------------
# ⚠️ 费率取值不再在这里定义 —— 唯一来源是语料（决策 C-31，2026-09-22）
# ---------------------------------------------------------------------------
# 原先此处有 BASE_UNIT_PRICE / CATEGORY_FACTOR / RATE_CHANGE_DATES / TON_BANDS
# 四组常量，用公式 `基准 × 货类系数 × 档位折扣 × 1.015^期数` **造**费率。
# 实测它与语料费率表在**四个维度上同时不同**（档位 0/1000/3000 vs 0/500/2000/5000、
# 建材 1.02 vs 2.55 元/吨、引航费按货类 vs 按船舶总吨位、港杂费 元/吨 vs 元/次），
# 而 docs/规则语料/README.md §4 早就声称两者必须一致 —— 规则写了、检查没做。
#
# 现在费率一律来自 `backend/scripts/corpus_tariff.py`（解析语料费率表），
# 并由 `backend/scripts/verify_corpus_db_rates.py` 独立复核，因此**结构上不可能再漂移**。

# 减免与附加（语料锚点，⛔ 不得凭记忆改动）
RELIEF_CATEGORIES = frozenset({"能源", "农资", "化工"})
#   重点保供物资 = 能源/农资/化工三个货类大类：`04-重点物资优先过闸规定` 第三条（完整枚举）
RELIEF_FACTOR = Decimal("0.70")
#   过闸费按 70% 计收：`06` 第十三条 / `07` 第十一条
RELIEF_FEE_TYPES = frozenset({"过闸费"})     # 减免条款只出现在过闸费费率表内
SURCHARGE_FACTOR = Decimal("1.30")
#   危险货物加收 30%：`06` 第十四条、`07` 第十二条（过闸费）、`09` 第六条 1（港杂费）
SURCHARGE_FEE_TYPES = frozenset({"过闸费", "港杂费"})
#   ⚠️ 两条系数同为乘法且**末尾统一四舍五入到分**（`06` 第十五条 3 / `07` 第十三条、
#      `09` 第六条 4），故"先减免后附加"与"先附加后减免"结果相同 —— 无顺序歧义。

# 弃用的旧常量（保留一行说明，避免后续会话"重新发明"）：
#   BASE_UNIT_PRICE / CATEGORY_FACTOR / RATE_CHANGE_DATES / TON_BANDS  → 见 C-31

SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜"
COMPANY_SUFFIX = ["物流有限公司", "航运有限公司", "贸易有限公司", "供应链管理有限公司",
                  "港务有限公司", "实业有限公司", "物资有限公司", "建材有限公司"]
COMPANY_PREFIX = ["广西", "南宁", "钦州", "贵港", "梧州", "北海", "防城港", "横州",
                  "桂平", "柳州", "玉林", "百色", "崇左", "来宾"]

DOC_TYPES = ["出口报关单", "水路运单", "舱单", "危险品申报单", "检验检疫证", "提货单"]


# ---------------------------------------------------------------------------
# 各表生成
# ---------------------------------------------------------------------------

def gen_port():
    rows = []
    # 粗略经纬度（广西境内），仅用于占位，不追求精确
    for i, (code, name, river, ptype, is_node) in enumerate(PORTS):
        lat = Decimal("21.4") + Decimal(rng.randint(0, 900)) / Decimal("1000")
        lon = Decimal("107.3") + Decimal(rng.randint(0, 1500)) / Decimal("1000")
        rows.append([code, name, river, ptype, "t" if is_node else "f", q(lat, 6), q(lon, 6)])
    return rows


def gen_lock():
    return [
        [lock_id, name, str(ch), str(cap), q(length, 1), q(width, 1), "24h", "t" if dbl else "f"]
        for (lock_id, name, ch, cap, length, width, dbl) in LOCKS
    ]


def gen_cargo_type():
    return [
        [code, name, cat, "t" if dang else "f", hazard if hazard else ""]
        for (code, name, cat, dang, hazard) in CARGO_TYPES
    ]


def gen_customer():
    rows = []
    used = set()
    for i in range(N_CUSTOMER):
        cid = f"C{i + 1:05d}"
        while True:
            name = rng.choice(COMPANY_PREFIX) + rng.choice(SURNAMES) + \
                   str(rng.randint(1, 99)) + rng.choice(COMPANY_SUFFIX)
            if name not in used:
                used.add(name)
                break
        ctype = rng.choices(["货主", "货代", "船运公司"], weights=[55, 30, 15])[0]
        credit = rng.choices(["A", "B", "C", "D"], weights=[20, 55, 20, 5])[0]
        contact = f"1{rng.choice('3578')}{rng.randint(10**8, 10**9 - 1)}"
        rows.append([cid, name, ctype, credit, contact])
    return rows


def gen_vessel():
    rows = []
    used = set()
    for i in range(N_VESSEL):
        vid = f"GX{i + 1:05d}"
        while True:
            name = rng.choice(COMPANY_PREFIX) + "货" + str(rng.randint(1000, 9999)) + "号"
            if name not in used:
                used.add(name)
                break
        vtype = rng.choices(VESSEL_TYPES, weights=[45, 15, 20, 10, 10])[0]
        lo, hi = VESSEL_DWT_RANGE[vtype]
        dwt = rng.randint(lo, hi)
        # 吃水与载重吨正相关：约 dwt/1000 * 0.9 ~ +0.8 米，控制在 1.5~6.5
        draft = Decimal(str(round(min(6.5, max(1.5, dwt / 1000 * 0.9 + rng.uniform(0.2, 0.9))), 2)))
        owner = rng.randint(1, N_CUSTOMER)
        year = rng.randint(2005, 2026)
        # 船舶总吨位（C-31 新增）：语料规定引航费、停泊费按它分档
        # （`25` 第十二条 / `08` 第二条），缺了它这两类费用算不出来。
        gross_ton = int((Decimal(dwt) * GROSS_TON_FACTOR).to_integral_value(rounding=ROUND_HALF_UP))
        rows.append([vid, name, vtype, str(dwt), str(max(1, gross_ton)),
                     q(draft), str(owner), str(year)])
    return rows


def gen_voyage():
    """
    航次：船舶在起运港与目的港之间的一次运输。

    via_canal（决策 C-18）：起点港或终点港为运河节点即为真。
    该字段是后续 fee_record 是否收过闸费的依据，两者必须由同一口径驱动，
    否则又会回到「两端非运河节点却收过闸费」的矛盾。
    """
    rows = []
    # 主要航线（起运 → 目的），权重体现真实货流方向
    routes = [
        ("NN01", "QZ02", 22), ("NN02", "QZ02", 10), ("HZ01", "QZ02", 12),
        ("GG01", "QZ02", 14), ("WZ01", "QZ02", 8),  ("NN01", "QZ03", 8),
        ("HZ02", "QZ02", 10), ("LW01", "QZ02", 6),  ("QZ02", "NN01", 5),
        ("QZ02", "GG01", 3),  ("NN01", "BH01", 2),
    ]
    route_pairs = [(a, b) for (a, b, w) in routes]
    route_weights = [w for (_, _, w) in routes]

    for i in range(N_VOYAGE):
        origin, dest = rng.choices(route_pairs, weights=route_weights)[0]
        etd = random_dt(BUSINESS_START, BUSINESS_END)
        # 航程 1~5 天
        eta = etd + timedelta(hours=rng.randint(20, 120))
        # 航次号年份取实际离港年份，保证编号与日期一致
        vno = f"VY{etd.year}{i + 1:06d}"
        # 状态按系统「今天」推断：远早于今天的已完成，近期的在途，未来的计划
        if eta.date() < SYSTEM_TODAY - timedelta(days=20):
            status = rng.choices(["已完成", "已取消"], weights=[97, 3])[0]
        elif etd.date() <= SYSTEM_TODAY:
            status = rng.choices(["在途", "已完成"], weights=[40, 60])[0]
        else:
            status = "计划"
        vessel_idx = rng.randint(1, N_VESSEL)
        # C-18 口径：端点落在运河节点上即视为经运河
        via_canal = (origin in CANAL_NODES) or (dest in CANAL_NODES)
        rows.append([vno, str(vessel_idx), origin, dest,
                     etd.strftime("%Y-%m-%d %H:%M:%S"),
                     eta.strftime("%Y-%m-%d %H:%M:%S"), status,
                     via_canal])
    return rows


def gen_waybill(voyages):
    """运单：挂在航次上，是费用的计算对象。日期锚定所属航次的离港日。"""
    rows = []
    for i in range(N_WAYBILL):
        voyage_idx = rng.randint(1, N_VOYAGE)
        # 运单日期锚定其所属航次的离港日（前后 7 天内），保证业务时序自洽：
        # 运单不可能早于航次很久，也不可能在航次结束后才创建
        voz = voyages[voyage_idx - 1]
        etd = datetime.strptime(voz[4], "%Y-%m-%d %H:%M:%S")
        created = etd + timedelta(hours=rng.randint(-168, 72))
        # 夹回业务窗口（航次 etd 可能接近窗口末端，偏移后会越界）
        created = max(created, datetime.combine(BUSINESS_START, datetime.min.time()))
        created = min(created, datetime.combine(BUSINESS_END, datetime.max.time()))

        # 运单号年份取实际创建年份
        wno = f"WB{created.year}{i + 1:07d}"
        # 状态按相对系统「今天」的位置推断，避免"未来运单已结算"的矛盾
        age_days = (SYSTEM_TODAY - created.date()).days
        if age_days > 30:
            status = rng.choices(["已结算", "已卸船", "已取消"],
                                 weights=[95, 3, 2])[0]
        elif age_days > 3:
            status = rng.choices(["已卸船", "在途", "已结算"],
                                 weights=[55, 35, 10])[0]
        else:
            status = rng.choices(["待装船", "在途"], weights=[65, 35])[0]

        customer_idx = rng.randint(1, N_CUSTOMER)
        cargo_idx = rng.randint(1, len(CARGO_TYPES))
        # 货重与吨位段分布匹配（多数落在中小吨位）
        weight = Decimal(str(round(rng.choices(
            [rng.uniform(200, 1000), rng.uniform(1000, 3000), rng.uniform(3000, 5000)],
            weights=[30, 45, 25])[0], 2)))
        rows.append([wno, str(voyage_idx), str(customer_idx),
                     CARGO_TYPES[cargo_idx - 1][0], q(weight),
                     "",  # freight_amount 后续由 fee_record 汇总回填
                     status, created.strftime("%Y-%m-%d %H:%M:%S")])
    return rows


def gen_tariff_rule(rules):
    """
    费率规则：**由语料费率表派生**（决策 C-31），不再由公式生成。

    语料锚点：`docs/规则语料/06`、`07`（过闸费两版）、`08`（引航费/停泊费）、
    `09`（港杂费/装卸费）。解析与断言见 `corpus_tariff.py`：
      - 档位必须从 0 开始、严格衔接、末档不封顶；
      - 生效期必须按左闭右开 `[from, to)` 首尾相接（C-36① 已把 `06` 统一到这条口径）；
      - 计费单位与计费吨位口径必须符合 `25` 第二十七条 / 第十二条。

    产出列顺序须与下面 TABLES 里的 tariff_rule 声明一致。
    """
    rows = []
    for r in rules:
        rows.append([
            r.rule_code, r.fee_type, r.cargo_category, r.min_ton,
            r.max_ton if r.max_ton is not None else "",
            r.unit_price, r.unit, r.effective_from,
            r.effective_to if r.effective_to else "",
            r.tonnage_basis,
        ])
    return rows


def _fee_amount(unit, unit_price, tonnage, days):
    """按计费单位算金额（语料 `25` 第二十七条的三种单位）。

    元/吨   ⇒ 吨位 × 单价          （过闸费、装卸费）
    元/次   ⇒ 单价                 （引航费、港杂费）
    元/吨天 ⇒ 吨位 × 单价 × 天数    （停泊费）
    ⛔ 不用 float：金额是可审计字段，浮点误差会让"对账"失去意义。
    """
    if unit == "元/吨":
        return tonnage * unit_price, "吨位 × 单价"
    if unit == "元/次":
        return unit_price, "固定单次费率"
    if unit == "元/吨天":
        return tonnage * unit_price * days, "吨位 × 单价 × 天数"
    raise ValueError(f"未知计费单位：{unit}")


def gen_fee_record(rules, waybills, voyage_gross_ton, canal_voyage_ids):
    """
    费用明细：每条运单按语料口径生成 1~3 项费用。

    与旧实现的差别（都是 C-31 的直接后果，不是顺手改的）：
      1. **费率命中改为按语料的两套吨位口径**：
         过闸费/港杂费/装卸费 按**载货吨位**；引航费/停泊费 按**船舶总吨位**
         （`25` 第十二条）。旧实现一律用货重，故引航费/停泊费**从未按语料算过**。
      2. **港杂费由"货重 × 单价"改为"固定单次费率"**：`09` 第二条规定其计费单位是
         `元/次`（按进出港区次数），旧实现按 元/吨 × 货重，单位就错了。
      3. **施加语料规定的附加与减免**：危险货物 +30%（过闸费、港杂费）、
         重点保供物资（能源/农资/化工）过闸费 ×70%。
      4. **舍入改为四舍五入到分**（`ROUND_HALF_UP`）：旧实现用 `Decimal.quantize`
         的默认 `ROUND_HALF_EVEN`（银行家舍入），与语料"四舍五入"不符 —— 实测
         **216 行**金额与 PostgreSQL `round()` 差 0.01 元（过闸费 19 / 装卸费 40 /
         港杂费 157）。
      5. **业务发生日 == 运单创建日**（沿用旧口径；`25` 第二十八条按费种区分业务
         发生日，但仿真数据里各费种共用运单日期这一约定，属数据生成约定）。

    ⛔ 未实现项（已在 README 与《评测报告》登记为"已实现/未实现清单"，不静默略过）：
      - `06` 第十五条 2 / `07` 第十三条 2 的「核定最低计费吨位」：全语料无定义；
      - `08` 第六条 引航中止按 50%、`09` 第九条 自备设备装卸费半价、各免收情形：
        库内无相应记录字段，不可触发；
      - `09` 第八条 2「装、卸各计一次」：`fee_record` 无作业类型列，本数据集每运单
        只记 1 次装卸作业。

    canal_voyage_ids：经运河的航次 id 集合（voyage 表 id 即插入顺序 1..N）。
    """
    rows = []
    cargo_map = {c[0]: c for c in CARGO_TYPES}
    # 航次 → 船舶总吨位由 main() 用 VESSEL_IDX_GROSS_TON 统一构造后传入
    # （⛔ 不在本函数里按下标取 vessel 行：列序一变就会静默取错列）

    # 建索引：rule_code -> 行号（行号即 id，因为 tariff_rule 按插入顺序编号）
    rule_id_by_code = {r.rule_code: idx + 1 for idx, r in enumerate(rules)}
    cents = Decimal("0.01")

    for wb in waybills:
        wno, voyage_id = wb[0], wb[1]
        cargo_code, weight_str, created_str = wb[3], wb[4], wb[7]
        weight = Decimal(weight_str)
        created = datetime.strptime(created_str, "%Y-%m-%d %H:%M:%S")
        biz_date = created.date().isoformat()
        cargo = cargo_map[cargo_code]
        category = cargo[2]
        is_dangerous = cargo[3]

        # 基础费用组合（沿用旧约定，未在本轮改动）
        fee_types = ["港杂费", "装卸费"]
        if str(voyage_id) in canal_voyage_ids:
            fee_types.insert(0, "过闸费")
        if is_dangerous:
            fee_types.append("停泊费")
        if rng.random() < 0.25:
            fee_types.append("引航费")

        for ft in fee_types:
            basis = corpus_tariff.TONNAGE_BASIS[ft]
            if basis == "船舶总吨位":
                tonnage = voyage_gross_ton[str(voyage_id)]
                rule_category = corpus_tariff.NO_CATEGORY
            else:
                tonnage = weight
                rule_category = category

            rule = corpus_tariff.match_rule(
                rules, ft, rule_category, tonnage, biz_date, basis)
            unit_price = Decimal(rule.unit_price)

            days = 1
            if ft == "停泊费":
                days = rng.randint(1, 4)

            amount, formula = _fee_amount(rule.unit, unit_price, tonnage, days)

            # 减免与附加（锚点见文件头常量区的注释）
            relief = RELIEF_FACTOR if (ft in RELIEF_FEE_TYPES
                                       and category in RELIEF_CATEGORIES) else Decimal("1")
            surcharge = SURCHARGE_FACTOR if (ft in SURCHARGE_FEE_TYPES
                                             and is_dangerous) else Decimal("1")
            amount = (amount * relief * surcharge).quantize(cents, rounding=ROUND_HALF_UP)

            basis_obj = {
                "tonnage": str(tonnage),
                "tonnage_basis": basis,
                "weight": weight_str,
                "unit_price": rule.unit_price,
                "unit": rule.unit,
                "formula": formula,
                "days": days,
                "as_of": biz_date,
                "relief": "0.70" if relief != 1 else "1.00",
                "surcharge": "1.30" if surcharge != 1 else "1.00",
                "rule_code": rule.rule_code,
                "rule_source": f"{rule.source_file}:{rule.source_line}",
            }

            rows.append([
                wno, ft, str(rule_id_by_code[rule.rule_code]), q(amount),
                json.dumps(basis_obj, ensure_ascii=False, sort_keys=True),
                (created + timedelta(hours=rng.randint(2, 96))).strftime("%Y-%m-%d %H:%M:%S"),
            ])
    return rows


def gen_documents(waybills):
    """单证：按运单生成，危险货类必带危险品申报单。"""
    rows = []
    cargo_map = {c[0]: c for c in CARGO_TYPES}
    for wb in waybills:
        wno, cargo_code, created_str = wb[0], wb[3], wb[7]
        created = datetime.strptime(created_str, "%Y-%m-%d %H:%M:%S")
        is_dangerous = cargo_map[cargo_code][3]

        types = ["水路运单"]
        if rng.random() < 0.75:
            types.append("舱单")
        if is_dangerous:
            types.append("危险品申报单")
        if rng.random() < 0.4:
            types.append("检验检疫证")
        if rng.random() < DOCS_PER_WAYBILL_AVG - 1:
            types.append("提货单")

        for dt in types:
            status = rng.choices(
                ["待提交", "已提交", "审核中", "已通过", "已驳回"],
                weights=[8, 12, 10, 65, 5])[0]
            submit = None
            audit = None
            if status != "待提交":
                submit = created + timedelta(hours=rng.randint(1, 48))
                if status in ("已通过", "已驳回"):
                    audit = submit + timedelta(hours=rng.randint(2, 72))
            remark = "资料不全，需补充" if status == "已驳回" else ""
            rows.append([
                wno, dt, status,
                submit.strftime("%Y-%m-%d %H:%M:%S") if submit else "",
                audit.strftime("%Y-%m-%d %H:%M:%S") if audit else "",
                remark,
            ])
    return rows


def _match_rule(rules_by_key, fee_type, category, weight, biz_date):
    """
    ⛔ **已删除**（决策 C-31，2026-09-22）。

    旧实现在这里按「费种 + 货类 + 货重 + 业务日」命中费率，存在两个真实缺陷：

      1. **吨位口径只有一种**：引航费、停泊费在语料里是按**船舶总吨位**分档的
         （`25` 第十二条），旧实现却拿**货重**去查，于是这两类费用的分档依据
         与语料不符；
      2. **生效期匹配含末日**（`biz_date <= effective_to`），而列注释与 EXCLUDE
         约束用的都是左闭右开 `[from, to)` —— 同一个库里有**两套端点语义**，
         且种子把 `effective_to` 写成"下期起始日前一天"，导致 EXCLUDE 区间
         在每期边界留下**一天空隙**（2026-12-31 无规则覆盖）。

    现在的实现是 `corpus_tariff.match_rule()`：左闭右开、带吨位口径维度、
    命中数不等于 1 即抛错（不给"静默取第一条"的通道）。
    """
    raise AssertionError(
        "已废弃：费率命中请用 corpus_tariff.match_rule（决策 C-31）")


def fill_waybill_freight(waybills, fee_rows):
    """按运单汇总费用，回填 waybill.freight_amount。"""
    # fee_record 行结构：[waybill_no, fee_type, rule_id, amount, calc_basis, created_at]
    totals = {}
    for f in fee_rows:
        wno = f[0]
        amount = Decimal(f[3])
        totals[wno] = totals.get(wno, Decimal("0")) + amount
    for wb in waybills:
        wb[5] = q(totals.get(wb[0], Decimal("0")))
    return waybills


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------

TABLES = [
    # (表名, 列名, 文件名) —— 列顺序与 V1__init_business_schema.sql 一致（不含 id 与 created_at）
    ("port", ["port_code", "name", "river_section", "type", "is_canal_node",
              "latitude", "longitude"], "port.csv"),
    ("lock", ["lock_id", "name", "chamber_no", "capacity_ton", "chamber_length_m",
              "chamber_width_m", "operating_hours", "is_double_line"], "lock.csv"),
    ("vessel", VESSEL_COLS, "vessel.csv"),
    ("cargo_type", ["cargo_code", "name", "category", "is_dangerous",
                    "hazard_class"], "cargo_type.csv"),
    ("customer", ["customer_id", "name", "type", "credit_level", "contact"],
     "customer.csv"),
    ("voyage", ["voyage_no", "vessel_id", "origin_port", "dest_port", "etd", "eta",
                "status", "via_canal"], "voyage.csv"),
    ("waybill", ["waybill_no", "voyage_id", "customer_id", "cargo_code",
                 "weight_ton", "freight_amount", "status", "created_at"],
     "waybill.csv"),
    ("documents", ["waybill_no", "doc_type", "status", "submit_time", "audit_time",
                   "remark"], "documents.csv"),
    ("tariff_rule", ["rule_code", "fee_type", "cargo_category", "min_ton", "max_ton",
                     "unit_price", "unit", "effective_from", "effective_to",
                     "tonnage_basis"],
     "tariff_rule.csv"),
    ("fee_record", ["waybill_no", "fee_type", "rule_id", "amount", "calc_basis",
                    "created_at"], "fee_record.csv"),
]

# 含 id 列（GENERATED ALWAYS AS IDENTITY）的表。
# port / cargo_type 用业务主键（port_code / cargo_code），没有 id 列。
# 装载脚本需据此决定哪些表要 setval 修正序列。
TABLES_WITH_ID = ["lock", "vessel", "customer", "voyage", "waybill",
                  "tariff_rule", "fee_record"]


def write_csv(path: str, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)


def write_load_sql(out_dir: str, counts: dict):
    """
    生成装载脚本。

    ★ 关键点：id 列是 GENERATED ALWAYS AS IDENTITY，COPY 不允许直接写 id。
      因此走「临时表 → INSERT ... OVERRIDING SYSTEM VALUE」两步，
      既保住 DDL 的强约束，又能让仿真数据的 id 与 FK 引用完全可控。
    """
    out_dir_sql = out_dir.replace("\\", "/")
    lines = [
        "-- 自动生成：装载 CanalMind 仿真数据",
        "-- 用法： psql -U <user> -d canalmind -f load_seed.sql",
        "\\set ON_ERROR_STOP on",
        "BEGIN;",
        "",
        "-- 清空（按 FK 依赖倒序）",
        "TRUNCATE fee_record, documents, waybill, voyage, vessel, customer,",
        "         tariff_rule, cargo_type, lock, port RESTART IDENTITY CASCADE;",
        "",
    ]

    # 防止 TABLES_WITH_ID 与 TABLES 脱节（漏写会导致序列未修正，
    # 后续插入自增主键时撞车）
    known = {t for t, _c, _f in TABLES}
    unknown = [t for t in TABLES_WITH_ID if t not in known]
    assert not unknown, f"TABLES_WITH_ID 含未知表: {unknown}"

    for tbl, cols, fname in TABLES:
        if tbl == "documents":
            continue
        lines += [
            f"CREATE TEMP TABLE stg_{tbl} AS SELECT * FROM {tbl} WITH NO DATA;",
            f"\\copy stg_{tbl} ({', '.join(cols)}) FROM '{out_dir_sql}/{fname}' WITH (FORMAT csv, HEADER true, NULL '')",
        ]
    lines.append("")
    for tbl, cols, _fname in TABLES:
        if tbl == "documents":
            continue
        lines.append(
            f"INSERT INTO {tbl} ({', '.join(cols)}) "
            f"OVERRIDING SYSTEM VALUE SELECT {', '.join(cols)} FROM stg_{tbl};")
    lines.append("")

    lines += [
        "-- documents 无显式 id，直接入表",
        f"\\copy documents (waybill_no, doc_type, status, submit_time, audit_time, remark) FROM '{out_dir_sql}/documents.csv' WITH (FORMAT csv, HEADER true, NULL '')",
        "",
        "-- 修正 identity 序列，避免后续自增冲突",
        "-- 注意：只有含 id 列的表才可 setval。port / cargo_type 用业务主键，",
        "--       没有 id 列，对它们执行 setval 会报 column \"id\" does not exist。",
    ]
    # 必须显式列举"含 id 列"的表：不能靠运行时 WHERE EXISTS 兜底，
    # 因为 PostgreSQL 会对整条 SELECT 生成执行计划，MAX(id) 在解析期即报错。
    for tbl in TABLES_WITH_ID:
        lines.append(
            f"SELECT setval(pg_get_serial_sequence('{tbl}', 'id'), "
            f"COALESCE((SELECT MAX(id) FROM {tbl}), 1) + 1, false);")
    lines += ["", "COMMIT;", "", "-- 行数校验"]
    for tbl, _cols, _f in TABLES:
        lines.append(f"SELECT '{tbl}' AS tbl, count(*) FROM {tbl};")
    lines.append("")

    with open(os.path.join(out_dir, "load_seed.sql"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def main():
    ap = argparse.ArgumentParser(description="生成 CanalMind 仿真数据")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "seed_output"),
                    help="输出目录")
    args = ap.parse_args()
    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)

    print("=" * 62)
    print("CanalMind 仿真数据生成")
    print(f"  随机种子 : {SEED}（确定性，可复现）")
    print(f"  业务窗口 : {BUSINESS_START} ~ {BUSINESS_END}")
    print(f"  运河通航 : {CANAL_OPEN_DATE}")
    print(f"  输出目录 : {out_dir}")
    print("=" * 62)

    port_rows = gen_port()
    lock_rows = gen_lock()
    cargo_rows = gen_cargo_type()
    cust_rows = gen_customer()
    vessel_rows = gen_vessel()
    voyage_rows = gen_voyage()
    waybill_rows = gen_waybill(voyage_rows)

    # ★ 费率：**解析语料费率表**（C-31 的唯一事实来源），不再由公式生成。
    #   语料若与库侧结构不兼容，会在这一步直接抛错（如档位不衔接、单位不符），
    #   而不是等到评测时才发现两个通道答案不同。
    tariff_rules = corpus_tariff.load_rules()
    tariff_rows = gen_tariff_rule(tariff_rules)
    print(f"  费率来源 : docs/规则语料/06、07、08、09（解析出 {len(tariff_rows)} 条规则）")
    print(f"  计费口径 : "
          f"{len({r.cargo_category for r in tariff_rules})} 个货类键"
          f"（含哨兵「不区分」）、"
          f"{len({r.tonnage_basis for r in tariff_rules})} 种吨位口径")

    doc_rows = gen_documents(waybill_rows)

    # 航次 → 船舶总吨位（引航费/停泊费的分档依据，语料 `25` 第十二条）。
    # ⛔ 下标取自 VESSEL_COLS 的具名索引，避免列序变动后静默取错列。
    # voyage 第 2 列是 vessel_id（1-based），vessel_rows 为其插入顺序。
    voyage_gross_ton = {
        str(idx + 1): Decimal(vessel_rows[int(voy[1]) - 1][VESSEL_IDX_GROSS_TON])
        for idx, voy in enumerate(voyage_rows)
    }

    # 过闸费的收取依据必须与 voyage.via_canal 同源，否则又会出现
    # 「两端非运河节点却收过闸费」的矛盾（C-18 要消除的正是这个）。
    # voyage 的 id 即插入顺序（1..N），与 gen_waybill 里的 voyage_id 口径一致。
    canal_voyage_ids = {str(idx + 1) for idx, v in enumerate(voyage_rows) if v[7]}
    fee_rows = gen_fee_record(tariff_rules, waybill_rows,
                              voyage_gross_ton, canal_voyage_ids)
    fill_waybill_freight(waybill_rows, fee_rows)

    n_canal = len(canal_voyage_ids)
    print(f"  经运河航次 : {n_canal:,} / {len(voyage_rows):,} "
          f"({n_canal / len(voyage_rows) * 100:.2f}%)")

    data = {
        "port": port_rows, "lock": lock_rows, "cargo_type": cargo_rows,
        "customer": cust_rows, "vessel": vessel_rows, "voyage": voyage_rows,
        "waybill": waybill_rows, "tariff_rule": tariff_rows,
        "documents": doc_rows, "fee_record": fee_rows,
    }

    counts = {}
    total = 0
    for tbl, cols, fname in TABLES:
        rows = data[tbl]
        counts[tbl] = len(rows)
        total += len(rows)
        write_csv(os.path.join(out_dir, fname), cols, rows)
        print(f"  {tbl:<12} {len(rows):>7,} 行  → {fname}")

    write_load_sql(out_dir, counts)
    print("-" * 62)
    print(f"  {'合计':<12} {total:>7,} 行")
    print(f"  装载脚本 → load_seed.sql")
    print("=" * 62)


if __name__ == "__main__":
    main()
