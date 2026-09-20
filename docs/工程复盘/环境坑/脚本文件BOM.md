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
| **相关** | [PowerShell版本差异](PowerShell版本差异.md) |
