# PowerShell 版本差异：本机是 5.1，不是 7

> 类别：[环境坑](README.md) ｜ 返回总索引：[../README.md](../README.md)

| 项 | 内容 |
|---|---|
| **坑** | 本机 PowerShell 是 **5.1**，**不支持 PowerShell 7 才有的 `??`（空合并）与 `\|\|`（管道链）运算符**。 |
| **真实报错** | `??` → `Unexpected token '??' in expression or statement`<br>`\|\|` → `The token '\|\|' is not a valid statement separator in this version` |
| **解法** | 用 `if/else` 替代 `??`；**SQL 里的字符串拼接改用 `concat(...)` 而非 `\|\|`**。 |
| **⚠️ 为什么 `\|\|` 特别坑** | PostgreSQL 里 `\|\|` 是**合法的字符串拼接运算符**，所以写 SQL 时会自然而然用上——但它在 PowerShell 的双引号字符串里**仍会被解析**，于是**一条正确的 SQL 在 PS 里变成语法错误**。 |
| **相关** | [脚本文件BOM](脚本文件BOM.md)（同属"本机环境与常见教程不一致"类）、[pg_ctl受限令牌](pg_ctl受限令牌.md) |
