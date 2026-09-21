#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 BM25 通道的「自定义航运词典」（第 2 周第 4 项，规格书第 629 行）。

产出：backend/src/main/resources/rag/tokenize/shipping-dictionary.txt
      （jieba-analysis 的用户词典，由 JiebaShippingTokenizer 在构造时加载）

为什么用脚本生成、而不是手写这个词典文件
--------------------------------------------------------------------------
词典里的**专有名词必须与权威来源逐字一致**：
  港口名   ← backend/scripts/generate_seed_data.py 的 PORTS
  船闸名   ← 同文件 LOCKS
  货种     ← 同文件 CARGO_TYPES
  船型     ← 同文件 VESSEL_TYPES
  费种 / 货类大类 / 计费单位 ← V1__init_business_schema.sql 第 232–234 行的 CHECK 枚举

本项目已经吃过一次「跨来源冲突」的亏：语料初稿把「钢材」列在 `建材` 下，
而种子数据是 `钢材 -> 金属`，且**单文件校验查不出来**
（见 docs/规则语料/README.md §4.1 与 docs/工程复盘/数据一致性/）。
故这里不手抄：词典**由来源推导**，手写内容只允许出现在下面的 CURATED_TERMS 表里，
再用 `--check` 保证「文件 == 脚本会生成的内容」。

⚠️ 输出格式是硬约束（由 jieba-analysis 的 loadUserDict 实现决定，实测读源码确认）
--------------------------------------------------------------------------
每行**只有两列** `词 词频`，且：

  * **不得有第三列 tag**。`WordDictionary.loadUserDict` 只在 `tokens.length == 2` 时
    才取第二列做词频；写成标准 jieba 词典的 `词 词频 tag`（3 列）时，
    词频会**静默退回默认值 3.0**。实测基础词典 total = 60101878，
    log(3/60101878) = **-16.81**，比基础词典里任何词都低（常见词约 -7 ~ -11），
    于是自定义词**几乎永远不会被切出来**——不报错，只是词典形同不存在。
  * **不得有注释行**。以 `#` 开头的行会被解析成「词 = `#`、词频 = 注释正文」，
    随后 `Double.valueOf(注释正文)` 抛 NumberFormatException；
    该异常**不被捕获**（loadUserDict 只 catch IOException）。
  * **不得有空行**。空行会走进 `freqs.put(null, ...)`（HashMap 允许 null 键，不报错），
    属于"能跑但语义不明"的脏数据。

词频取 100000 的依据
--------------------------------------------------------------------------
实测 jieba 基础词典：349045 个词、词频总和 60101878、最高词频 883634（"的"）。
  log(100000 / 60101878) = **-6.40**
对照基础词典实测值：「通过」-7.45、「管理」-7.70、「规定」-7.80、「船」-8.95、「闸」-10.85。
即领域词的概率被抬到"比中文最常用的实词还高"的位置，DAG 最优路径必然整词成词。
**这不是靠推导保证的**，而由 JUnit 自检兜住：`JiebaShippingTokenizerTest` 断言
词典里**每一条**都能被切成恰好一个 token（不通过就说明词频或词形有问题）。

实测的领域缺口（这就是词典存在的理由）
--------------------------------------------------------------------------
以下词**根本不在 jieba 基础词典里**：`南宁港`、`过闸`、`闸室`。
`危险品` 在基础词典里词频仅 19（log = -14.97），实测会被切成「危险 / 品」。

用法
--------------------------------------------------------------------------
    python build_shipping_dictionary.py            # 生成（覆盖）词典文件
    python build_shipping_dictionary.py --check     # 只校验文件与来源是否一致
退出码：0 = 一致/已生成；1 = 不一致或来源读不到
"""

import argparse
import hashlib
import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK，中文会 UnicodeEncodeError；显式切到 UTF-8。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

SCRIPT_DIR = Path(__file__).resolve().parent          # canalmind/backend/scripts
REPO_ROOT = SCRIPT_DIR.parents[1]                     # canalmind/
SEED_PY = SCRIPT_DIR / "generate_seed_data.py"
V1_SQL = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration" / "V1__init_business_schema.sql"
OUTPUT = REPO_ROOT / "backend" / "src" / "main" / "resources" / "rag" / "tokenize" / "shipping-dictionary.txt"

# 见文件头「词频取 100000 的依据」
DOMAIN_FREQ = 100000

# ── 项目自建的航运业务术语（唯一允许手写的地方）──────────────────────────────
# 选取口径：① 领域专有名词（基础词典没有或切错的）；② 需要**钉死切分粒度**的复合词
#（如「危险货物专用闸次」若被切碎，BM25 的精确匹配优势就没了）。
# ⛔ 不要往这里塞通用词（"货物""规定"这类），基础词典已有，加了只会让词典难维护。
CURATED_TERMS = [
    # 过闸与船闸
    "过闸", "过闸费", "闸次", "闸室", "船闸", "双线船闸", "单闸次", "待闸", "待闸区",
    "优先过闸", "优先放行", "通航", "通航能力", "通过能力", "闸室有效长度", "引航道",
    "充泄水", "编组", "编队", "调度指令", "过闸申报", "过闸申请", "申报时限",
    # 船舶与尺度
    "载重吨", "总长", "船宽", "型深", "吃水", "满载吃水", "富余水深", "水位",
    "净空高度", "船舶检验", "适航", "适任证书", "船员", "配员", "船舶登记",
    "营运证", "营运资质", "技术状况", "单船", "超限船舶",
    # 货物
    "危险货物", "危险品", "危险化学品", "超限货物", "特种货物", "冷藏货物", "鲜活货物",
    "重点物资", "保供物资", "重点保供物资", "干散货", "散货", "件杂货", "集装箱",
    "标准箱", "危险货物申报", "专用闸次", "危险货物专用闸次",
    # 计费与费率
    "计费", "费率", "计费单位", "吨位区间", "吨位下限", "吨位上限", "计费总则",
    "生效期", "现行版本", "已废止", "退费", "复核", "费用争议", "差错更正",
    "过闸费费率表",
    # 港口与作业
    "靠离泊", "系泊", "码头", "泊位", "作业窗口", "堆存", "堆存期", "仓储",
    "装卸", "港区", "作业区", "理货", "装载", "港口作业",
    # 客服与争议
    "投诉", "受理", "争议处理", "赔付", "考核", "服务质量", "工单", "办结",
    # 单证与代号
    "单证", "提单", "运单", "提货单", "报关", "文号",
    # 运河本体
    "平陆运河", "运河节点港", "节点港", "航运服务中心",
]

# 从 PORTS 里派生「江段」时排除的通用词（不是专有名词，加进词典没有意义）
RIVER_SECTION_EXCLUDE = {"沿海"}


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_seed_py() -> dict:
    """从 generate_seed_data.py 解析出 PORTS / LOCKS / CARGO_TYPES / VESSEL_TYPES。

    用正则逐行匹配而不是 import：该脚本是**带副作用的生成器**，
    导入它会把整个仿真数据集重新生成一遍（几百毫秒 ~ 数秒，且会覆盖输出目录）。
    出错时返回空列表——调用方会把它当"来源读不到"处理并让 --check 失败，
    而不是静默生成一份缺了权威词的词典（那正是本项目最怕的静默损坏）。
    """
    if not SEED_PY.is_file():
        raise SystemExit(f"[FAIL] 找不到种子数据脚本：{SEED_PY}")
    text = read_text(SEED_PY)

    ports_pat = re.compile(
        r'\(\s*"([A-Z]{2}\d{2})"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(True|False)\s*\)'
    )
    lock_pat = re.compile(
        r'\(\s*"([A-Z]{2}\d{2})"\s*,\s*"([^"]+)"\s*,\s*\d+\s*,\s*\d+\s*,'
    )
    cargo_pat = re.compile(
        r'\(\s*"([A-Z]{2}\d{2}|JZX1)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(True|False)\s*,'
    )
    vessel_pat = re.compile(r'^VESSEL_TYPES\s*=\s*\[(.*?)\]', re.S | re.M)

    ports, locks, cargos, vessels = [], [], [], []
    for line in text.split("\n"):
        m = ports_pat.search(line)
        if m:
            ports.append({"code": m.group(1), "name": m.group(2), "river_section": m.group(3),
                          "type": m.group(4), "is_canal_node": m.group(5) == "True"})
            continue
        m = lock_pat.search(line)
        if m:
            locks.append({"lock_id": m.group(1), "name": m.group(2)})
            continue
        m = cargo_pat.search(line)
        if m:
            cargos.append({"code": m.group(1), "name": m.group(2), "category": m.group(3)})

    m = vessel_pat.search(text)
    if m:
        vessels = re.findall(r'"([^"]+)"', m.group(1))

    if not ports or not locks or not cargos or not vessels:
        raise SystemExit(
            f"[FAIL] 从 {SEED_PY.name} 解析来源不完整："
            f"PORTS {len(ports)} / LOCKS {len(locks)} / CARGO_TYPES {len(cargos)} / "
            f"VESSEL_TYPES {len(vessels)}。正则与源文件格式已不同步，请修本脚本再生成。"
        )
    return {"ports": ports, "locks": locks, "cargos": cargos, "vessels": vessels}


def parse_enum_from_sql(constraint_name: str, path: Path) -> list:
    """从 V1 迁移的某一 CHECK 约束里取出枚举字面量列表（保序）。

    例：CONSTRAINT tariff_fee_type_chk CHECK (fee_type IN ('过闸费','港杂费',...))
    """
    if not path.is_file():
        raise SystemExit(f"[FAIL] 找不到迁移文件：{path}")
    pat = re.compile(re.escape(constraint_name) + r"\s+CHECK\s*\([^)]*IN\s*\(([^)]*)\)")
    m = pat.search(read_text(path))
    if not m:
        raise SystemExit(
            f"[FAIL] 在 {path.name} 里找不到约束 {constraint_name} 的 IN 列表。"
            f"DDL 改了名字或写法，请同步本脚本（否则词典会缺掉权威费种/货类）。"
        )
    return re.findall(r"'([^']+)'", m.group(1))


def build_sections() -> list:
    """构造词典分段：[(段名, [(词, 词频), ...]), ...]，顺序即输出顺序（确定性）。"""
    seed = parse_seed_py()
    fee_types = parse_enum_from_sql("tariff_fee_type_chk", V1_SQL)
    cargo_categories = parse_enum_from_sql("tariff_category_chk", V1_SQL)

    # 江段去重保序（PORTS 里"郁江"出现多次）
    sections = []
    seen_rivers = []
    for p in seed["ports"]:
        if p["river_section"] not in seen_rivers and p["river_section"] not in RIVER_SECTION_EXCLUDE:
            seen_rivers.append(p["river_section"])

    def with_freq(words):
        return [(w, DOMAIN_FREQ) for w in words]

    sections.append(("港口名称（源：generate_seed_data.py PORTS）",
                     with_freq(pt["name"] for pt in seed["ports"])))
    sections.append(("江段（源：PORTS.river_section）", with_freq(seen_rivers)))
    sections.append(("船闸与枢纽（源：LOCKS）",
                     with_freq(dict.fromkeys(lk["name"] for lk in seed["locks"]))))
    sections.append(("货种（源：CARGO_TYPES）", with_freq(c["name"] for c in seed["cargos"])))
    sections.append(("货类大类（源：V1 tariff_category_chk）", with_freq(cargo_categories)))
    sections.append(("费种（源：V1 tariff_fee_type_chk）", with_freq(fee_types)))
    sections.append(("船型（源：VESSEL_TYPES）", with_freq(seed["vessels"])))
    sections.append(("项目自建业务术语（源：本脚本 CURATED_TERMS）", with_freq(CURATED_TERMS)))
    return sections


def render(sections: list) -> str:
    """渲染词典文件内容。

    ⚠️ 输出里**没有任何注释行**——见文件头：`#` 开头会让 loadUserDict 抛
    NumberFormatException，所以"来源与版本"只能放在本脚本的 docstring 与
    docs/ 文档里，**不能写进这个文件**。
    换行固定 LF、UTF-8 无 BOM（AGENTS.md §7.2「生成产物时换行/编码必须自己钉死」）。
    """
    seen = set()
    lines = []
    for _section_name, words in sections:
        for word, freq in words:
            w = word.strip()
            if w in seen:
                continue                      # 跨段去重，保留首次出现（段序即优先级）
            seen.add(w)
            assert " " not in w and "\t" not in w, f"词典词不得含空白：{w!r}"
            lines.append(f"{w} {freq}")
    return "\n".join(lines) + "\n"


def validate(content: str) -> list:
    """自校验：格式硬约束 + 与来源的覆盖关系。返回错误列表（空 = 通过）。"""
    errors = []
    lines = content.split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]

    for i, line in enumerate(lines, start=1):
        if not line.strip():
            errors.append(f"第 {i} 行：空行（loadUserDict 会 freqs.put(null, ...)）")
            continue
        if line.startswith("#"):
            errors.append(f"第 {i} 行：注释行（loadUserDict 会 NumberFormatException）")
            continue
        parts = re.split(r"[\t ]+", line)
        if len(parts) != 2:
            errors.append(f"第 {i} 行：字段数为 {len(parts)}，必须恰好 2（词 词频）；"
                          f"第三列 tag 会让词频静默退回 3.0")
            continue
        if not parts[1].isdigit() or int(parts[1]) <= 0:
            errors.append(f"第 {i} 行：词频不是正整数：{parts[1]!r}")

    words = [re.split(r"[\t ]+", ln)[0] for ln in lines if ln.strip()]
    dupes = {w for w in words if words.count(w) > 1}
    if dupes:
        errors.append(f"重复词 {len(dupes)} 个：{sorted(dupes)[:5]}")

    # 来源覆盖：权威词一个都不能少
    seed = parse_seed_py()
    required = {}
    for p in seed["ports"]:
        required[p["name"]] = "PORTS"
    for lk in seed["locks"]:
        required[lk["name"]] = "LOCKS"
    for c in seed["cargos"]:
        required[c["name"]] = "CARGO_TYPES"
    for v in seed["vessels"]:
        required[v] = "VESSEL_TYPES"
    for f in parse_enum_from_sql("tariff_fee_type_chk", V1_SQL):
        required[f] = "V1 tariff_fee_type_chk"
    for c in parse_enum_from_sql("tariff_category_chk", V1_SQL):
        required[c] = "V1 tariff_category_chk"
    missing = {w: src for w, src in required.items() if w not in set(words)}
    if missing:
        errors.append(f"权威来源词缺失 {len(missing)} 个：{missing}")

    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description="生成/校验 BM25 自定义航运词典")
    ap.add_argument("--check", action="store_true",
                    help="只校验：文件必须与脚本生成的内容逐字节一致")
    args = ap.parse_args()

    sections = build_sections()
    content = render(sections)
    errors = validate(content)
    if errors:
        print("[FAIL] 生成内容自校验未通过：")
        for e in errors:
            print("  -", e)
        return 1

    new_bytes = content.encode("utf-8")
    sha = hashlib.sha256(new_bytes).hexdigest()
    counts = ", ".join(f"{name.split('（')[0]} {len(ws)}" for name, ws in sections)
    total_words = len({w for _, ws in sections for w, _ in ws})

    if args.check:
        if not OUTPUT.is_file():
            print(f"[FAIL] 词典文件不存在：{OUTPUT}")
            return 1
        old_bytes = OUTPUT.read_bytes()
        old_sha = hashlib.sha256(old_bytes).hexdigest()
        if old_bytes != new_bytes:
            print(f"[FAIL] 词典文件与来源不一致：{OUTPUT}")
            print(f"  文件 sha256 = {old_sha}")
            print(f"  应为 sha256 = {sha}")
            print("  请运行：python build_shipping_dictionary.py  （来源变了就要重新生成，")
            print("  并注意：词典内容一变 sha256 就变，BM25 档案必须新建 + 全量重灌，")
            print("  见 V4 迁移的 rag_assert_token_profile）")
            return 1
        print(f"[PASS] 词典与来源一致（{total_words} 词，sha256 {sha[:16]}…）")
        print(f"  分段：{counts}")
        return 0

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(new_bytes)
    print(f"[OK] 已生成 {OUTPUT}")
    print(f"  词条 {total_words} 条，sha256 = {sha}")
    print(f"  分段：{counts}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
