r"""审计 Java 源文件里的**非法转义**与**可疑写法**。

为什么要重写：先前那版审计脚本的判定模型是错的——它只看最后一个反斜杠的后继字符，
于是把 `'\n'` 被误改成 `'\\n'`（javac 会当成 `'\\'` + 游离的 n 而报错）
这种情况判成"合法"，得出"非法转义 0 处"的假结论。

本版模型：**逐字符扫描字符串/字符字面量**，遇到反斜杠就看下一个字符：
  - 合法转义首字符：b t n f r " ' \ 以及 0-7（八进制）与 u（Unicode）
  - 其它（含 `s`、`d`、`|`、`.`、`,` 等）→ **非法转义**，javac 必报错
同时报告"看起来想写正则、但反斜杠只有一个"的可疑处（不会编译报错，但语义错了）。

用法：
    python scripts/audit_java_escapes.py <文件或目录> [...]
退出码：发现非法转义 → 1，否则 0。
"""

import pathlib
import sys

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

BS = chr(92)
QUOTE = '"'
APOS = "'"
NL = chr(10)
CR = chr(13)

LEGAL_ESCAPE_HEADS = set('btnfr"\'\\') | set('01234567') | {'u'}

# 只在这些字符前出现、且只有一个反斜杠时值得怀疑（典型的正则元字符）
REGEX_HINT_CHARS = set('sdwSDWbBAZz|.,()[]{}^$*+?<>=')


def scan(path: pathlib.Path):
    """返回 (非法转义列表, 可疑正则列表, 可疑字面量列表, 行数)。

    可疑字面量：`String.replace(...)` / `String.split(...)` / `String.join(...)` 里
    用**双反斜杠**的换行（即运行时的"反斜杠+n"两字符）。
    这类写法**是合法 Java**，编译器不报错，但语义几乎总是错的：
      同一换行写单反斜杠时运行时 2 字符、能匹配真实 CRLF；
      写双反斜杠时 4 字符、**永远匹配不到**。
    本项目真实踩过——静默失效、不报错，整块表格因此消失。
    所以本脚本把它单独列出（warning，非 error）：靠"编辑工具折叠反斜杠"产生，
    人工评审看不出来，只有机器能稳定发现。
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    bad = []
    suspect = []
    literal_ws = []
    line_no = 1
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == NL:
            line_no += 1
            i += 1
            continue
        if ch not in (QUOTE, APOS):
            i += 1
            continue

        # 进入字面量
        quote = ch
        start_line = line_no
        lit_start = i
        i += 1
        while i < n:
            c = text[i]
            if c == NL:
                # Java 字符串/字符字面量不能跨行（文本块另算，这里不做处理）
                break
            if c == BS:
                if i + 1 >= n:
                    bad.append((start_line, '字符串以反斜杠结尾'))
                    break
                nxt = text[i + 1]
                if nxt == BS and i + 2 < n and text[i + 2] in ('n', 'r', 't'):
                    # 双反斜杠 + 换行类字母 → 运行时是"反斜杠+字母"两字符
                    literal_ws.append((line_no, f'{BS}{BS}{text[i + 2]}'))
                if nxt not in LEGAL_ESCAPE_HEADS:
                    bad.append((line_no, f'非法转义 {BS}{nxt}'))
                if nxt in REGEX_HINT_CHARS:
                    suspect.append((line_no, f'{BS}{nxt}'))
                i += 2
                continue
            if c == quote:
                i += 1
                break
            i += 1
    return bad, suspect, literal_ws, text.count(NL) + 1


def collect(targets):
    files = []
    for t in targets:
        p = pathlib.Path(t)
        if p.is_dir():
            files.extend(sorted(p.rglob('*.java')))
        elif p.suffix == '.java':
            files.append(p)
    return files


def main():
    targets = sys.argv[1:] or ['backend/src']
    files = collect(targets)
    if not files:
        print('没有找到 .java 文件')
        return 2

    total_bad = 0
    total_suspect = 0
    total_litws = 0
    print(f'审计 {len(files)} 个 .java 文件')
    print()
    for f in files:
        bad, suspect, literal_ws, _ = scan(f)
        if bad:
            total_bad += len(bad)
            print(f'[非法转义] {f}')
            for ln, msg in bad:
                print(f'    L{ln}: {msg}')
        if suspect:
            total_suspect += len(suspect)
            print(f'[可疑正则] {f}')
            for ln, msg in suspect:
                print(f'    L{ln}: {msg}')
        if literal_ws:
            total_litws += len(literal_ws)
            print(f'[可疑字面量换行] {f}')
            for ln, msg in literal_ws:
                print(f'    L{ln}: {msg}  ← 运行时的"反斜杠+字母"两字符（若在 split/join/replace '
                      f'的模式参数位上则几乎肯定是 bug；建议改用 (char) 10 / (char) 13）')
    print()
    print(f'非法转义总数: {total_bad}（javac 会直接报错）')
    print(f'可疑正则总数: {total_suspect}（单反斜杠后跟正则元字符，需人工判断是否为有意的 Java 转义）')
    print(f'可疑字面量换行总数: {total_litws}（**合法但语义容易错**，且失败时静默）')
    print()
    print('⚠️ 本脚本只把「非法转义」当作失败判据；其余两类是**需人工判断**的提示。')
    print('   "可疑字面量换行"里大量命中是**正确用法**——例如 SSE 编码要显式输出反斜杠+n，')
    print('   `chunk.replace("\\n", "\\\\n")` 这类就是对的（本项目 SseTextCodec 有 24 个测试守护）。')
    print('   真正要警惕的是它出现在 split/join/replace 的**模式参数**位置上。')
    if total_bad == 0:
        print()
        print('[PASS] 未发现非法转义')
        return 0
    print()
    print('[FAIL] 存在非法转义，javac 会报错')
    return 1


if __name__ == '__main__':
    sys.exit(main())
