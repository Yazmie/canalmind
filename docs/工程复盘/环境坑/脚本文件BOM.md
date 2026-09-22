# 脚本文件 BOM：同一个问题咬了两次

> 类别：[环境坑](README.md) ｜ 返回总索引：[../README.md](../README.md)

| 项 | 内容 |
|---|---|
| **坑（方向一：读）** | **PowerShell 5.1 读无 BOM 的 `.ps1` 会按 GBK 解析** → 脚本里的中文全部乱码 → 报一堆莫名其妙的语法错误（`Unexpected token ...`）。 |
| **坑（方向二：写）** | **`Set-Content -Encoding UTF8` 会写入 BOM**（PS 5.1 里没有 `UTF8NoBOM`）→ 给要求"无 BOM"的文件（`.md`/`.py`/`.sql`）写内容时，BOM 被意外带入。 |
| **坑（方向三：编辑工具）** | 我用编辑工具改 `.ps1` 时，**它会写成无 BOM UTF-8** → 方向一的坑**立刻复发**。 |
| **结论：不同文件要求相反** | `.ps1` → **必须 BOM**；`.md`/`.py`/`.sql`/`.yml` → **必须无 BOM**（`.gitattributes` 亦有约定）。⛔ **别搞混，也不能靠记忆。** |
| **正确写法** | 写无 BOM：`[System.IO.File]::WriteAllText($f,$t,(New-Object System.Text.UTF8Encoding($false)))`；写有 BOM：`...UTF8Encoding($true)`。改完 `.ps1` **必须复查 BOM 是否还在**。 |
| **业务含义** | **规则必须由"文件类型"决定并写进仓库配置**，而不是留在个人记忆里。我为此反复踩了三次——**每次都是"以为记住了"**。这也正是把它写进 `.gitattributes` 和 `AGENTS.md` 的原因。 |
| **工具链兜底** | 语料校验器 `validate_corpus.py` 有 BOM 检查，**方向二的坑就是它当场抓出来的**（见[数据一致性/校验器抓出BOM](../数据一致性/校验器抓出BOM.md)）。 |
| **⚠️ 第四次复发（2026-09-22，第 4 周）** | 我又用编辑工具写了 `backend/scripts/run_d3_arm.ps1`，**方向三原样复发**：无 BOM + LF 的中文注释被按 GBK 解析 ⇒ 一执行就是一串 `Unexpected token`。**"写进 AGENTS.md / 写进本条目"并没有阻止它**——因为这两处都是**给人看的**，而错误发生在**机器读取的那一刻**。 |
| **⚠️ 同一次的额外教训：包装器退出 0 ≠ 脚本跑了** | 那次调用我打印的是 `D3_EXIT=$LASTEXITCODE`，结果是 **`D3_EXIT=`（空）**，而**后台 job 状态却是 `exit code 0`**。原因：脚本**解析期就死了**，内层 `$LASTEXITCODE` 从未被赋值。**只贴那个 0 就会得出"跑过了"的错误结论。** |
| **✅ 由此定下的机器化检查（⛔ 不再靠记忆）** | `.ps1` 写完必须做**静态自证**，两步零成本：<br>① 头三字节 = `EF BB BF`（`[System.IO.File]::ReadAllBytes($p)[0..2]`）；<br>② **用 PowerShell 自带 AST 解析器验证整份脚本**：<br>`[System.Management.Automation.Language.Parser]::ParseFile($p,[ref]$null,[ref]$err)` → `$err.Count` 必须为 **0**。<br>**为什么用解析器而不是"跑一下看"**：解析器能在**发起任何副作用之前**抓出编码与转义错误；而"跑一下看"对本次的两个坑里**恰有一个是执行成功但内容被改写**（见[PowerShell脚本的反引号陷阱](PowerShell脚本的反引号陷阱.md)）。 |
| **⚠️ 第五次复发（2026-09-22，第 5 周）——⚠️ 但这次被机器检查拦下，未入库** | 我用编辑工具改 `backend/scripts/demo.ps1`（410 行）之后：**换行是对的（`CRLF=410 / LF=0`），只有 BOM 丢了**。⇒ **"换行对就以为合规"是新的失手形态**：CRLF 不符时 `git add` 会警告，**而 BOM 丢了 git 一声不响**。<br>**这次没造成事故，全靠上面那条机器化检查**：①前三字节当场报 `BOM=False`；②AST 解析复现 **15 处语法错误**，且**报错信息自己就是乱码**（`Unexpected token '闆朵粯璐癸紙榛樿锛?'`、`Missing closing ')'`、`The Try statement is missing its Catch or Finally block`）——**错误位置离真实原因很远**（全挂在一处 `-match '…'` 附近，因为 GBK 解码把 UTF-8 双字节拆开、其中有字节恰好等于 `'`/`)`/`}` 的编码，**字符串提前闭合、括号配对被破坏**）。<br>补回 `EF BB BF` → **语法 0 错误** → 按 README 记录的原样命令实跑 → **退出码 0**。<br>⇒ **结论：这个坑靠记忆一定会复发（已 5 次）；唯一有效的是"写入后立即自证"，而它确实拦住了这一次。** |
| **⚠️ 同次新实测：判 BOM 要用 `git cat-file`，⛔ 不要用重定向** | 我用 `git show HEAD:<path> > file` 去查**已提交**版本，读出的头三字节是 **`FF FE`**——那是**重定向自己把它重编码成 UTF-16** 的结果，**不是仓库里的真实内容**。改用 `git cat-file -p HEAD:<path>` 直读 blob，才拿到真实值 **`EF BB BF`**（⇒ 说明 **HEAD 是好的、坏的只有工作副本**，从而判定这不需要回滚、只需补 BOM）。**拿被测量工具改变过的结果去判 BOM，必然误判方向。** |
| **相关** | [PowerShell版本差异](PowerShell版本差异.md)｜[PowerShell脚本的反引号陷阱](PowerShell脚本的反引号陷阱.md)｜[UTF8按ANSI读丢换行](UTF8按ANSI读丢换行/README.md)（**同一根因的另一面**）｜[转义与写入层的静默损坏](../工程方法/转义与写入层的静默损坏.md) |
