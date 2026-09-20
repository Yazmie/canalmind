#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""工程复盘目录自检：链接可达性 + 结构完整性 + 编码规范。

为什么需要它
------------
本目录**靠相对链接导航**（分类目录 / 语义化文件名），
而且刻意**不使用编号**——因为编号会随重组位移、导致交叉引用失效。

代价是：**文件一旦改名或移动，引用它的链接就会静默失效**。
Markdown 不会报错，读者只会看到一个点不开的链接、或者干脆跳到 404。

所以「重组后必须跑一遍链接校验」不是可选项，而是这套方案的**必要配套**。
（这条本身就是本项目的一贯做法：把约定变成机器检查，而不是靠人记得。）

用法
----
    python docs/工程复盘/check_links.py

退出码 0 = 通过；1 = 存在问题。
"""

import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK，中文与符号会 UnicodeEncodeError；显式切到 UTF-8。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

REVIEW_DIR = Path(__file__).resolve().parent

# 期望存在的分类目录（每个都必须有 README.md 作索引）
EXPECTED_CATEGORIES = ["数据一致性", "工程方法", "检索工程", "诚实记录", "环境坑"]

# 顶层必须存在的文件
REQUIRED_TOP = ["README.md", "面试速查卡.md"]

# Markdown 链接：[文本](目标.md)  —— 只关心 .md 目标，跳过 锚点/外链
MD_LINK = re.compile(r"\]\(([^)\s#]+?\.md)(?:#[^)]*)?\)")

# 行内代码 `...`（其中的链接是"示例文本"，不是真链接，必须跳过）
INLINE_CODE = re.compile(r"`[^`]*`")


def strip_inline_code(text: str) -> str:
    """把行内代码替换成等长空白，避免误把示例链接当成真链接。

    注意：只处理**单反引号**行内代码；``` 围栏代码块里的内容也在此处理掉
    （围栏内的内容同样不是真链接），保持行数不变以便行号仍然准确。
    """
    def blank(m):
        return " " * len(m.group(0))

    text = INLINE_CODE.sub(blank, text)
    # 围栏代码块 ``` ... ```
    lines = text.split("\n")
    in_fence = False
    for i, ln in enumerate(lines):
        if ln.lstrip().startswith("```"):
            in_fence = not in_fence
            lines[i] = " " * len(ln)
            continue
        if in_fence:
            lines[i] = " " * len(ln)
    return "\n".join(lines)


def check_links(md_files):
    """校验所有 .md 相对链接可达。返回 (检查数, 问题列表)。"""
    checked = 0
    problems = []
    for f in md_files:
        raw = f.read_text(encoding="utf-8")
        # 先去掉代码（行号保持不变），再找链接
        text = strip_inline_code(raw)
        for lineno, line in enumerate(text.split("\n"), start=1):
            for m in MD_LINK.finditer(line):
                target = m.group(1)
                checked += 1
                resolved = (f.parent / target).resolve()
                if not resolved.exists():
                    problems.append(
                        f"{f.relative_to(REVIEW_DIR)}:{lineno}  链接失效 -> {target}"
                    )
    return checked, problems


def check_structure(md_files):
    """校验目录结构完整：分类目录齐全、每类有 README、顶层文件存在。"""
    problems = []

    for name in REQUIRED_TOP:
        if not (REVIEW_DIR / name).exists():
            problems.append(f"缺少顶层文件：{name}")

    for cat in EXPECTED_CATEGORIES:
        d = REVIEW_DIR / cat
        if not d.is_dir():
            problems.append(f"缺少分类目录：{cat}/")
        elif not (d / "README.md").exists():
            problems.append(f"分类目录缺少索引：{cat}/README.md")

    # 不应存在编号式文件名（本目录刻意不用编号）
    for f in md_files:
        if re.match(r"^\d{2}-", f.name):
            problems.append(
                f"发现编号式文件名：{f.relative_to(REVIEW_DIR)}"
                f"（本目录用语义化路径名，不用编号——编号会随重组失效）"
            )

    return problems


def check_encoding(md_files):
    """校验编码：本目录 .md 一律 无 BOM + LF。"""
    problems = []
    for f in md_files:
        raw = f.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"):
            problems.append(f"{f.relative_to(REVIEW_DIR)}  存在 UTF-8 BOM（应为无 BOM）")
        if b"\r\n" in raw:
            problems.append(f"{f.relative_to(REVIEW_DIR)}  存在 CRLF（应为 LF）")
    return problems


def main() -> int:
    md_files = sorted(REVIEW_DIR.rglob("*.md"))

    if not md_files:
        print(f"✗ 未找到任何 .md 文件：{REVIEW_DIR}")
        return 1

    print("=" * 68)
    print(f"工程复盘目录自检：{REVIEW_DIR}")
    print("=" * 68)
    print(f"扫描到 {len(md_files)} 个 Markdown 文件")
    print("-" * 68)

    total = 0

    print("链接可达性（本目录靠相对链接导航，改名/移动后必查）")
    checked, link_problems = check_links(md_files)
    for p in link_problems:
        print(f"  ✗ {p}")
    if not link_problems:
        print(f"  OK：{checked} 个相对链接全部可达")
    total += len(link_problems)
    print("-" * 68)

    print("目录结构完整性")
    struct_problems = check_structure(md_files)
    for p in struct_problems:
        print(f"  ✗ {p}")
    if not struct_problems:
        print(f"  OK：{len(EXPECTED_CATEGORIES)} 个分类目录齐全，每类均有 README.md 索引")
    total += len(struct_problems)
    print("-" * 68)

    print("编码规范（无 BOM + LF）")
    enc_problems = check_encoding(md_files)
    for p in enc_problems:
        print(f"  ✗ {p}")
    if not enc_problems:
        print(f"  OK：{len(md_files)} 个文件均为 无 BOM + LF")
    total += len(enc_problems)
    print("-" * 68)

    print(f"问题 {total} 项")
    if total == 0:
        print("[PASS] 工程复盘目录自检通过")
        return 0
    print("[FAIL] 存在问题，请修正后重跑")
    return 1


if __name__ == "__main__":
    sys.exit(main())
