#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验 docs/规则语料/ 下的仿真语料是否符合写作格式约定。

依据决策：
  - C-20  语料执行规格（28 篇 / 7 主题类 / 头部 8 字段元数据块）
  - C-21  结构化切分（以「条」为主切分单元，层级必须严格可解析）

校验项：
  1. 文件数 = 28
  2. UTF-8 可解码、LF 换行（无 CRLF）
  3. 头部元数据块：8 字段齐全（文号/标题/发布机构/版本/生效期/替代关系/说明 + 仿真声明）
  4. 正文含 `## 第X章`、`### 第X条 【标题】`
  5. 条号连续、不跳号、不重号
  6. 每条紧跟【标题】，且同文件内条标题不重复
  7. 表格为标准 Markdown 管道表（检查表头分隔行）
  8. ★ 货类/费种/计费单位取值必须落在数据库允许枚举内
  9. 反模式：不得出现「危险品」作为货类、不得出现水深字段

用法：
    python validate_corpus.py            # 校验
    python validate_corpus.py --verbose  # 额外打印每篇统计
退出码：0 = 全部通过；1 = 有错误
"""

import re
import sys
import unicodedata
from pathlib import Path

# Windows 控制台默认 GBK，中文与符号会 UnicodeEncodeError；显式切到 UTF-8。
# 若终端仍显示乱码，是终端代码页问题（需 chcp 65001），不影响校验结果。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

CORPUS_DIR = Path(__file__).resolve().parents[2] / "docs" / "规则语料"

# ⚠️ 初版误写为 28——那是「篇目编号最大值」而非文件数。
#    实际布局为 7 个主题类、编号 01–26 连续（见 规则语料/README.md §2）。
EXPECTED_FILES = 26

# 跨文件一致性：闸室尺度必须处处相同（这些数字在 03/12/14/18/25 多篇出现）
CHAMBER_LENGTH_M = 300.0
CHAMBER_WIDTH_M = 34.0

# 单船限值（C-20 语料内部口径，须与 03 第七条/第八条一致）
SINGLE_VESSEL_MAX_LOA_M = 280.0
SINGLE_VESSEL_MAX_BEAM_M = 32.5

# ── 数据库允许枚举（来源：V1__init_business_schema.sql 第 232–234 行）──────────
# ⚠️ 三个集合为**完整枚举**，语料中不得出现集合外的取值。
ALLOWED_FEE_TYPES = {"过闸费", "港杂费", "装卸费", "引航费", "停泊费"}
ALLOWED_CARGO = {"建材", "能源", "金属", "农资", "化工", "集装箱", "其他"}
ALLOWED_UNITS = {"元/吨", "元/次", "元/吨天"}

# 元数据块必须出现的字段名（前 7 个在表格里，"仿真语料声明"在引用行）
META_FIELDS = ["文号", "标题", "发布机构", "版本", "生效期", "替代关系", "说明"]

# 中文数字（覆盖到九十九，足够本项目使用）
CN_DIGITS = "零一二三四五六七八九"


def cn_to_int(s: str):
    """把中文数字转成 int；无法解析返回 None。"""
    s = s.strip()
    if not s:
        return None
    if s == "十":
        return 10
    if "十" in s:
        head, _, tail = s.partition("十")
        tens = CN_DIGITS.index(head) if head else 1
        ones = CN_DIGITS.index(tail) if tail else 0
        if head and head not in CN_DIGITS:
            return None
        if tail and tail not in CN_DIGITS:
            return None
        return tens * 10 + ones
    if all(ch in CN_DIGITS for ch in s):
        return int("".join(str(CN_DIGITS.index(ch)) for ch in s))
    return None


class Doc:
    def __init__(self, path: Path):
        self.path = path
        self.errors = []
        self.warnings = []
        self.articles = []      # [(条号int, 标题str, 行号int)]
        self.chapters = 0
        self.fee_types = set()
        self.cargo = set()
        self.units = set()

    @property
    def name(self):
        return self.path.name

    def err(self, msg):
        self.errors.append(msg)

    def warn(self, msg):
        self.warnings.append(msg)


def check_structure(doc: Doc, raw_bytes: bytes):
    # ── 编码与换行 ──────────────────────────────────────────────
    if b"\r\n" in raw_bytes:
        doc.err("存在 CRLF 换行，约定要求全文件 LF（见规则语料/README.md §3.2）")
    try:
        text = raw_bytes.decode("utf-8")
    except UnicodeDecodeError as e:
        doc.err(f"不是合法 UTF-8：{e}")
        return None
    if text.startswith("\ufeff"):
        doc.err("存在 UTF-8 BOM，建议去除")

    lines = text.split("\n")

    # ── 元数据块 ────────────────────────────────────────────────
    head = "\n".join(lines[:20])
    if "仿真语料声明" not in head:
        doc.err("头部缺少【仿真语料声明】（C-19/C-20 强制要求）")
    if "非官方文件" not in head:
        doc.err("头部声明未写明「非官方文件」字样")
    for f in META_FIELDS:
        # 允许 "| 文号 | xxx |" 形式
        if not re.search(rf"\|\s*{f}\s*\|", head):
            doc.err(f"元数据块缺少字段「{f}」")

    # 文号格式
    m = re.search(r"\|\s*文号\s*\|\s*([^|]+?)\s*\|", head)
    if m and not re.match(r"^PLYH-[A-Z]{2}-\d{4}-\d{3}$", m.group(1).strip()):
        doc.warn(f"文号格式不常见：{m.group(1).strip()}（期望 PLYH-XX-YYYY-NNN）")

    # 生效期
    m = re.search(r"\|\s*生效期\s*\|\s*([^|]+?)\s*\|", head)
    if m:
        period = m.group(1).strip()
        if not re.search(r"\d{4}-\d{2}-\d{2}", period):
            doc.err(f"生效期未含具体日期：{period}")
    # 版本
    m = re.search(r"\|\s*版本\s*\|\s*([^|]+?)\s*\|", head)
    if m and not re.match(r"^V\d+\.\d+$", m.group(1).strip()):
        doc.warn(f"版本号格式不常见：{m.group(1).strip()}（期望 V主.次）")

    # ── 章节与条文 ──────────────────────────────────────────────
    art_re = re.compile(r"^###\s+第([零一二三四五六七八九十]+)条\s*(【.*?】)?\s*$")
    chap_re = re.compile(r"^##\s+第[零一二三四五六七八九十]+章\s+.+$")

    seen_titles = {}
    for i, line in enumerate(lines, start=1):
        if chap_re.match(line):
            doc.chapters += 1
        m = art_re.match(line)
        if m:
            num = cn_to_int(m.group(1))
            raw_title = m.group(2)
            if raw_title is None:
                doc.err(f"第 {i} 行：第{m.group(1)}条 缺少【标题】（C-21 要求每条紧跟标题）")
                title = ""
            else:
                title = raw_title.strip("【】")
            if num is None:
                doc.err(f"第 {i} 行：条号「{m.group(1)}」无法解析")
                continue
            doc.articles.append((num, title, i))
            if title:
                if title in seen_titles:
                    doc.err(f"第 {i} 行：条标题「{title}」与第 {seen_titles[title]} 行重复")
                else:
                    seen_titles[title] = i

    if not doc.articles:
        doc.err("未发现任何 `### 第X条` 结构，无法按条切分（C-21）")
        return text

    # 条号连续性
    nums = [a[0] for a in doc.articles]
    expected = list(range(1, len(nums) + 1))
    if nums != expected:
        # 找出第一处不符
        for idx, (got, exp) in enumerate(zip(nums, expected), start=1):
            if got != exp:
                doc.err(
                    f"条号不连续：第 {idx} 条应为「{exp}」，实际为「{got}」"
                    f"（C-21 要求条号连续不跳号）"
                )
                break
        else:
            doc.err(f"条号数量异常：共 {len(nums)} 条")

    if doc.chapters == 0:
        doc.err("缺少 `## 第X章` 章节标题")

    return text


def check_tables(doc: Doc, text: str):
    """检查 Markdown 管道表是否为标准形式（含分隔行）。"""
    lines = text.split("\n")
    sep_re = re.compile(r"^\|[\s:\-|]+\|$")
    pipe_blocks = 0
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("|") and lines[i].strip().endswith("|"):
            # 收集连续表格块
            block = []
            j = i
            while j < len(lines) and lines[j].strip().startswith("|"):
                block.append((j + 1, lines[j]))
                j += 1
            pipe_blocks += 1
            if len(block) < 2:
                doc.err(f"第 {block[0][0]} 行：表格只有 1 行，缺少分隔行")
            else:
                sep_line = block[1][1].strip()
                if not sep_re.match(sep_line):
                    doc.err(
                        f"第 {block[1][0]} 行：表格缺少标准分隔行"
                        f"（如 `|---|---|`），当前为 `{sep_line[:40]}`"
                    )
                # 列数一致性
                ncol = block[0][1].count("|")
                for ln, content in block:
                    if content.count("|") != ncol:
                        doc.err(
                            f"第 {ln} 行：表格列数与表头不一致"
                            f"（表头 {ncol-1} 列，本行 {content.count('|')-1} 列）"
                        )
            i = j
        else:
            i += 1
    if pipe_blocks == 0:
        doc.warn("未发现任何表格（本项目语料预期应含费率/清单类表格）")


def check_enums(doc: Doc, text: str):
    """★ 检查货类/费种/计费单位取值是否越界。"""
    # 收集文档中出现的「费率表」行：以 费种 开头且含单位
    for m in re.finditer(r"\|\s*(过闸费|港杂费|装卸费|引航费|停泊费)\s*\|", text):
        doc.fee_types.add(m.group(1))

    # 单位
    for u in ALLOWED_UNITS:
        if u in text:
            doc.units.add(u)

    # 货类：从费率表行中提取货类列
    for line in text.split("\n"):
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2:
            continue
        # 费率表形态：费种 | 货类 | ...
        if cells[0] in ALLOWED_FEE_TYPES and cells[1]:
            cand = cells[1]
            if cand in ALLOWED_CARGO:
                doc.cargo.add(cand)

    # ── 反模式检查 ──────────────────────────────────────────────
    # 「危险品」作为货类大类（费率表第二列）是禁止的
    for line in text.split("\n"):
        if not line.strip().startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 2 and cells[1] in {"危险品", "危险货物", "危化品"}:
            doc.err(
                f"把「{cells[1]}」当作货类大类使用；"
                f"危险货物须按其性质归入 7 类之一（见规则语料/README.md §4）"
            )

    # 水深字段（本项目 lock 表无水深，语料不得**给定**水深数值）
    # ⚠️ 初版规则过宽，把「本标准不规定固定的闸室水深刻度」这类*否认性*表述也判成错误。
    #    这里只捕获"给出具体水深数值"或"断言闸室水深为某值"的写法。
    #
    # ★ 概念边界（详见 docs/规则语料/README.md §4.2）：
    #   - 「闸室水深」= 结构尺寸 → 本项目**不存在**，不得给数值
    #   - 「运行水位」「允许最大吃水」「富余水深」= 动态运行条件 / 操作余量 → **合理存在，放行**
    #   故 03 篇的「允许最大吃水 4.20 米」「闸室水位」「富余水深 0.30 米」均为有意保留，不报错。
    for m in re.finditer(
        r"(?:闸室|船闸|航道|引航道)?水深\s*(?:为|是|按|=|:|：)?\s*\d+(?:\.\d+)?\s*米"
        r"|\d+(?:\.\d+)?\s*米的?\s*(?:闸室|船闸|航道)?水深"
        r"|水深\s*(?:限值|刻度)\s*(?:为|是|=|:|：)\s*\d+",
        text,
    ):
        doc.err(
            f"出现具体水深数值「{m.group(0).strip()}」；本项目无水深数据，"
            f"写了会与其他文档及数据库矛盾（见规则语料/README.md §4）"
        )

    # 富余水深属于操作余量而非闸室水深，允许；但不得借此变相给出闸室水深
    for m in re.finditer(r"富余水深\s*(?:不少于|不低于)\s*(\d+(?:\.\d+)?)\s*米", text):
        if float(m.group(1)) > 1.0:
            doc.warn(f"富余水深 {m.group(1)} 米偏大，确认是否为笔误")

    # 疑似自造费种
    for bad in ["船闸费", "通行费", "港务费", "仓储费", "堆存费", "手续费", "服务费"]:
        if re.search(rf"\|\s*{bad}\s*\|", text) or f"费种：{bad}" in text:
            doc.err(f"疑似自造费种「{bad}」；费种仅限 {sorted(ALLOWED_FEE_TYPES)}")


def check_cross_file(docs):
    """
    跨文件一致性检查。

    背景：同一事实（闸室尺度）在多篇中重复出现，单文件校验无法发现它们互相矛盾。
    实测踩过：`03` 曾写「单船总长不超过 135 米、船宽不超过 16 米」，
    而 `12`/`14`/`18`/`25` 一致写「闸室长 300 米、宽 34 米」——
    34 米宽的闸室不可能有 16 米船宽上限，两份口径互斥。
    该矛盾分布在不同文件里，只有跨文件比对才能查到。
    """
    global_errors = []

    # ── 0. 审计：把所有"疑似尺度数值"都找出来 ────────────────────
    # 仅用「长X、宽Y」合并写法会漏掉表格里分行的写法（实测 `03` 就是
    # `| 闸室有效长度 | 300.0 米 |` 与 `| 闸室有效宽度 | 34.0 米 |` 两行），
    # 故这里做一次穷举审计。
    dimension_audit = []   # (doc, line_no, 语义, 数值)
    for d in docs:
        try:
            text = d.path.read_text(encoding="utf-8")
        except OSError:
            continue
        for i, line in enumerate(text.split("\n"), start=1):
            # 「闸室…长度/宽度」关键词 + 数值
            for m in re.finditer(
                r"(闸室|船闸|单线闸室)[^|\n]{0,12}?"
                r"(有效长度|有效宽度|长度|宽度|长|宽)"
                r"[^|\n]{0,12}?(\d+(?:\.\d+)?)\s*米",
                line,
            ):
                dimension_audit.append((d.name, i, f"{m.group(1)}{m.group(2)}", float(m.group(3))))
            # 反向写法：「300 米」在闸室语境行
            if "闸室" in line:
                for m in re.finditer(r"(\d+(?:\.\d+)?)\s*米", line):
                    v = float(m.group(1))
                    if v in (300.0, 34.0, 280.0, 32.5, 135.0, 16.0, 290.0, 32.0):
                        dimension_audit.append((d.name, i, "闸室语境数值", v))

    # 闸室长/宽只允许 300 / 34
    for name, ln, semantic, v in dimension_audit:
        if "长度" in semantic or semantic.endswith("长"):
            if v not in (CHAMBER_LENGTH_M, SINGLE_VESSEL_MAX_LOA_M):
                global_errors.append(
                    f"{name} 第 {ln} 行：{semantic} 出现 {v} 米，"
                    f"全语料闸室长应为 {CHAMBER_LENGTH_M} 米（单船总长上限 {SINGLE_VESSEL_MAX_LOA_M} 米）"
                )
        if "宽度" in semantic or semantic.endswith("宽"):
            if v not in (CHAMBER_WIDTH_M, SINGLE_VESSEL_MAX_BEAM_M):
                global_errors.append(
                    f"{name} 第 {ln} 行：{semantic} 出现 {v} 米，"
                    f"全语料闸室宽应为 {CHAMBER_WIDTH_M} 米（单船船宽上限 {SINGLE_VESSEL_MAX_BEAM_M} 米）"
                )

    # 已废弃的旧限值不得复活
    for name, ln, semantic, v in dimension_audit:
        if v in (135.0, 16.0):
            global_errors.append(
                f"{name} 第 {ln} 行：出现已废弃限值 {v} 米"
                f"（曾与闸室 {CHAMBER_LENGTH_M}×{CHAMBER_WIDTH_M} 米互斥，已于 2026-09-21 统一）"
            )

    # ── 1. 闸室尺度必须处处一致 ──────────────────────────────────
    # 匹配「长 300 米、宽 34 米」各式写法
    pat = re.compile(
        r"长\s*\*{0,2}(\d+(?:\.\d+)?)\s*米\*{0,2}\s*[、,，]\s*宽\s*\*{0,2}(\d+(?:\.\d+)?)\s*米"
    )
    chamber_claims = []   # (doc_name, line_no, length, width)
    for d in docs:
        try:
            text = d.path.read_text(encoding="utf-8")
        except OSError:
            continue
        for i, line in enumerate(text.split("\n"), start=1):
            for m in pat.finditer(line):
                chamber_claims.append((d.name, i, float(m.group(1)), float(m.group(2))))

    for name, ln, length, width in chamber_claims:
        if length != CHAMBER_LENGTH_M or width != CHAMBER_WIDTH_M:
            global_errors.append(
                f"{name} 第 {ln} 行：闸室尺度写作 长{length}米×宽{width}米，"
                f"但全语料统一为 长{CHAMBER_LENGTH_M}米×宽{CHAMBER_WIDTH_M}米"
            )

    # ── 2. 单船尺度上限不得与闸室尺度互斥 ────────────────────────
    # 匹配「船舶总长不超过 280.0 米」「单船船宽不超过 32.5 米」
    loa_pat = re.compile(r"(?:单船|船舶)总长不超过\s*\*{0,2}(\d+(?:\.\d+)?)\s*米")
    beam_pat = re.compile(r"(?:单船)?船(?:舶)?(?:总)?宽不超过\s*\*{0,2}(\d+(?:\.\d+)?)\s*米")
    for d in docs:
        try:
            text = d.path.read_text(encoding="utf-8")
        except OSError:
            continue
        for i, line in enumerate(text.split("\n"), start=1):
            m = loa_pat.search(line)
            if m:
                v = float(m.group(1))
                if v > CHAMBER_LENGTH_M:
                    global_errors.append(
                        f"{d.name} 第 {i} 行：单船总长上限 {v} 米 > 闸室长 {CHAMBER_LENGTH_M} 米，物理上不可行"
                    )
                elif abs(v - SINGLE_VESSEL_MAX_LOA_M) > 0.01:
                    global_errors.append(
                        f"{d.name} 第 {i} 行：单船总长上限 {v} 米，"
                        f"与 `03` 统一口径 {SINGLE_VESSEL_MAX_LOA_M} 米不一致"
                    )
            m = beam_pat.search(line)
            if m:
                v = float(m.group(1))
                if v > CHAMBER_WIDTH_M:
                    global_errors.append(
                        f"{d.name} 第 {i} 行：单船船宽上限 {v} 米 > 闸室宽 {CHAMBER_WIDTH_M} 米，物理上不可行"
                    )
                elif abs(v - SINGLE_VESSEL_MAX_BEAM_M) > 0.01:
                    global_errors.append(
                        f"{d.name} 第 {i} 行：单船船宽上限 {v} 米，"
                        f"与 `03` 统一口径 {SINGLE_VESSEL_MAX_BEAM_M} 米不一致"
                    )

    return global_errors, chamber_claims, dimension_audit


def load_seed_cargo_map():
    """
    从 generate_seed_data.py 的 CARGO_TYPES 读出「货种 -> 货类大类」权威映射。

    这是与仿真数据对齐的**唯一权威来源**：语料对某货种的归类必须与之一致，
    否则文档检索通道与结构化查询通道会对同一问题给出不同答案。
    实测踩过：语料曾把「钢材制品」列入 `建材`，而种子数据是 `钢材 -> 金属`。
    """
    seed = CORPUS_DIR.parents[1] / "backend" / "scripts" / "generate_seed_data.py"
    mapping = {}
    if not seed.is_file():
        return mapping
    pat = re.compile(r'\(\s*"[A-Z0-9]+"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,')
    for line in seed.read_text(encoding="utf-8").split("\n"):
        m = pat.search(line)
        if m and m.group(2) in ALLOWED_CARGO:
            mapping[m.group(1)] = m.group(2)
    return mapping


# 语料举例中可能使用的简称 -> 种子数据标准名
CARGO_ALIASES = {
    "钢材制品": "钢材",
    "钢制品": "钢材",
    "铁矿石": "铁矿石",
    "铁矿砂": "铁矿石",
    "铝土矿": "铝土矿",
    "混凝土预制件": "混凝土预制件",
    "水泥熟料": "水泥熟料",
    "液体化工品": "液体化工品",
    "集装箱货物": "集装箱货物",
}


def check_cargo_examples(doc: Doc, text: str):
    """核对语料「货类大类 -> 举例货种」是否与种子数据映射一致。"""
    mapping = load_seed_cargo_map()
    if not mapping:
        doc.warn("未能读取 generate_seed_data.py 的 CARGO_TYPES，跳过货种归类核对")
        return

    # 形如：| 建材 | 水泥、砂石、钢材制品、玻璃、瓷砖 |
    row_pat = re.compile(r"^\|\s*([^|]+?)\s*\|\s*([^|]*、[^|]*)\|\s*$")
    for i, line in enumerate(text.split("\n"), start=1):
        m = row_pat.match(line.strip())
        if not m:
            continue
        declared = m.group(1).strip()
        if declared not in ALLOWED_CARGO:
            continue
        items = [x.strip().strip("*") for x in re.split(r"[、,，]", m.group(2)) if x.strip()]
        for item in items:
            std = CARGO_ALIASES.get(item, item)
            actual = mapping.get(std)
            if actual and actual != declared:
                doc.err(
                    f"第 {i} 行：把「{item}」列在 `{declared}` 下，"
                    f"但种子数据（generate_seed_data.py CARGO_TYPES）中「{std}」属 `{actual}`"
                )


def check_rate_table_headers(docs):
    """
    费率表列头必须全语料统一。

    背景：表格结构化抽取（规格书第 627 行）依赖列头定位字段。
    实测踩过：`07` 曾写 `费率（元/吨）` 而 `06`/`08`/`09`/`20` 写 `费率` + 独立 `计费单位` 列——
    同一语料内列头不同名，抽取器要么写两套分支，要么静默取错值。
    """
    errors = []
    signature_of = {}
    for d in docs:
        try:
            text = d.path.read_text(encoding="utf-8")
        except OSError:
            continue
        for i, line in enumerate(text.split("\n"), start=1):
            s = line.strip()
            if not (s.startswith("|") and "费种" in s and "费率" in s):
                continue
            cells = [c.strip() for c in s.strip("|").split("|")]
            # 只规范"费率表"（含吨位下限/吨位上限），不含计费方式类表
            if "吨位下限（吨）" not in cells:
                continue
            signature_of.setdefault(tuple(cells), []).append(f"{d.name}:{i}")

    if len(signature_of) > 1:
        errors.append(
            f"费率表列头存在 {len(signature_of)} 种不同写法，须统一为一种："
        )
        for sig, where in signature_of.items():
            errors.append(f"    {list(sig)}  ← {len(where)} 处，如 {where[0]}")
    return errors


def load_seed_ports():
    """从 generate_seed_data.py 的 PORTS 读出港口名单与 is_canal_node 标记。"""
    seed = CORPUS_DIR.parents[1] / "backend" / "scripts" / "generate_seed_data.py"
    ports = []
    if not seed.is_file():
        return ports
    # ("NN01", "南宁港六景作业区", "郁江", "内河港", False),
    pat = re.compile(
        r'\(\s*"([A-Z]{2}\d{2})"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*(True|False)\s*\)'
    )
    for line in seed.read_text(encoding="utf-8").split("\n"):
        m = pat.search(line)
        if m:
            ports.append({
                "code": m.group(1), "name": m.group(2), "section": m.group(3),
                "type": m.group(4), "is_node": m.group(5) == "True",
            })
    return ports


def check_canal_node_ports(docs):
    """
    语料中的「运河节点港」名单必须与 generate_seed_data.py 的 PORTS 完全一致。

    为什么重要：决策 C-18 的 `voyage.via_canal` 取值口径就是
    「起点港或终点港 ∈ is_canal_node=True 的集合」。
    若语料名单与种子数据不符，则第 3 周「费用测算 / 是否经运河」类 L1 题目
    会得到与文档冲突的答案。
    """
    errors = []
    ports = load_seed_ports()
    if not ports:
        return ["未能读取 generate_seed_data.py 的 PORTS，无法核对运河节点港"]

    db_nodes = {p["name"]: p for p in ports if p["is_node"]}

    # 在 01 篇里定位运河节点港表
    for d in docs:
        if "过闸管理办法" not in d.name:
            continue
        try:
            lines = d.path.read_text(encoding="utf-8").split("\n")
        except OSError:
            continue
        for i, line in enumerate(lines, start=1):
            s = line.strip()
            if not s.startswith("|"):
                continue
            cells = [c.strip() for c in s.strip("|").split("|")]
            if len(cells) != 4 or cells[0] in ("港区名称",) or set(cells[0]) <= set("-: "):
                continue
            name, section, ptype, node = cells
            if node not in ("是", "否"):
                continue
            dbp = next((p for p in ports if p["name"] == name), None)
            if dbp is None:
                errors.append(
                    f"{d.name} 第 {i} 行：港口「{name}」不在 generate_seed_data.py 的 PORTS 中"
                )
                continue
            if dbp["section"] != section:
                errors.append(
                    f"{d.name} 第 {i} 行：「{name}」江段写作 `{section}`，"
                    f"种子数据为 `{dbp['section']}`"
                )
            if dbp["type"] != ptype:
                errors.append(
                    f"{d.name} 第 {i} 行：「{name}」类型写作 `{ptype}`，"
                    f"种子数据为 `{dbp['type']}`"
                )
            expected_node = "是" if dbp["is_node"] else "否"
            if node != expected_node:
                errors.append(
                    f"{d.name} 第 {i} 行：「{name}」运河节点标记为 `{node}`，"
                    f"种子数据 is_canal_node={dbp['is_node']}（应为 `{expected_node}`）"
                )

    # 反向：语料是否覆盖了全部运河节点港
    for d in docs:
        if "过闸管理办法" not in d.name:
            continue
        text = d.path.read_text(encoding="utf-8")
        missing = [n for n in db_nodes if n not in text]
        if missing:
            errors.append(
                f"{d.name} 未列出全部运河节点港，缺少：{sorted(missing)}"
            )
    return errors


def main():
    verbose = "--verbose" in sys.argv

    if not CORPUS_DIR.is_dir():
        print(f"[FAIL] 语料目录不存在：{CORPUS_DIR}")
        return 1

    files = sorted(p for p in CORPUS_DIR.glob("*.md") if p.name != "README.md")
    docs = []
    for p in files:
        d = Doc(p)
        try:
            raw = p.read_bytes()
        except OSError as e:
            d.err(f"无法读取：{e}")
            docs.append(d)
            continue
        text = check_structure(d, raw)
        if text:
            check_tables(d, text)
            check_enums(d, text)
            check_cargo_examples(d, text)
        docs.append(d)

    # ── 汇总输出 ────────────────────────────────────────────────
    print("=" * 68)
    print("CanalMind 规则语料格式校验")
    print("=" * 68)
    print(f"目录：{CORPUS_DIR}")
    print(f"文件数：{len(files)}（期望 {EXPECTED_FILES}）")
    print()

    total_err = 0
    total_warn = 0
    for d in docs:
        status = "OK  " if not d.errors else "ERR "
        n_art = len(d.articles)
        line = f"  [{status}] {d.name:<42} 章{d.chapters:>2} 条{n_art:>3}"
        if d.warnings:
            line += f"  警告{len(d.warnings)}"
        print(line)
        for e in d.errors:
            print(f"         ✗ {e}")
        for w in d.warnings:
            print(f"         ! {w}")
        total_err += len(d.errors)
        total_warn += len(d.warnings)

    # 全局：枚举覆盖度
    all_fee, all_cargo, all_units = set(), set(), set()
    for d in docs:
        all_fee |= d.fee_types
        all_cargo |= d.cargo
        all_units |= d.units

    print()
    print("-" * 68)
    print("枚举覆盖（来源 V1__init_business_schema.sql 第 232–234 行）")
    print(f"  费种    {len(all_fee)}/5  {sorted(all_fee)}")
    print(f"  货类    {len(all_cargo)}/7  {sorted(all_cargo)}")
    print(f"  计费单位 {len(all_units)}/3  {sorted(all_units)}")
    missing_cargo = ALLOWED_CARGO - all_cargo
    if missing_cargo:
        print(f"  ! 未在费率表中出现的货类：{sorted(missing_cargo)}")
    print("-" * 68)

    print()
    print("-" * 68)
    print("文件数与跨文件一致性")
    if len(files) != EXPECTED_FILES:
        print(f"  ✗ 文件数不符：{len(files)} != {EXPECTED_FILES}")
        total_err += 1
    else:
        print(f"  OK：文件数 {len(files)} = {EXPECTED_FILES}")
    print("-" * 68)
    print("跨文件一致性（闸室尺度 / 单船限值）")
    global_errors, chamber_claims, dimension_audit = check_cross_file(docs)
    header_errors = check_rate_table_headers(docs)
    if chamber_claims:
        print(f"  合并写法「长X米、宽Y米」声明 {len(chamber_claims)} 处：")
        for name, ln, length, width in chamber_claims:
            print(f"    {name} 第 {ln} 行 -> 长 {length} 米 × 宽 {width} 米")
    else:
        print("  ! 未发现任何「长 X 米、宽 Y 米」形式的闸室尺度声明")
    print(f"  尺度数值穷举审计（含表格分行写法）：共 {len(dimension_audit)} 处")
    bucket = {}
    for name, ln, semantic, v in dimension_audit:
        bucket.setdefault(v, []).append(f"{name}:{ln}")
    for v in sorted(bucket):
        tag = "OK"
        if v in (135.0, 16.0):
            tag = "已废弃-不应出现"
        print(f"    {v:>7} 米 × {len(bucket[v]):>2} 处  [{tag}]")
    for e in global_errors:
        print(f"  ✗ {e}")
    total_err += len(global_errors)
    print("-" * 68)

    print("费率表列头一致性")
    if header_errors:
        for e in header_errors:
            print(f"  ✗ {e}")
    else:
        print("  OK：全语料费率表列头统一")
    total_err += len(header_errors)
    print("-" * 68)

    print("运河节点港名单一致性（C-18 口径基础）")
    port_errors = check_canal_node_ports(docs)
    if port_errors:
        for e in port_errors:
            print(f"  ✗ {e}")
    else:
        print("  OK：语料运河节点港名单与 generate_seed_data.py 的 PORTS 一致")
    total_err += len(port_errors)
    print("-" * 68)

    print(f"错误 {total_err} 项，警告 {total_warn} 项")
    if total_err == 0:
        print("[PASS] 全部校验通过")
        return 0
    print("[FAIL] 存在错误，请修正后重跑")
    return 1


if __name__ == "__main__":
    sys.exit(main())
