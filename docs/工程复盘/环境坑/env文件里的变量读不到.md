# `.env` 里的变量"填了但读不到"

## 现象

第 3 周跑结构化通道的取数工具，连库直接失败：

```
org.postgresql.util.PSQLException: The server requested SCRAM-based authentication,
but the password is an empty string.
```

而口令**明明填在 `canalmind/.env` 里**（`POSTGRES_PASSWORD` 长度 13，`.env` 是有效文件、
`application.yml` 也按 AGENTS §7.2 写了双路径导入）。当时的直觉是"是不是 `.env` 没被加载"，
但更值得注意的是**报错形式**。

## 根因

新写的 CLI 里，数据源是从**已合并的 env map**（含 `.env`）装配的，但取值时用的是既有助手：

```java
ds.setPassword(CliConsole.envOr("POSTGRES_PASSWORD", ""));   // ← 只读 System.getenv
```

`CliConsole.envOr(name, fallback)` 的实现只查 `System.getenv`，**看不到 `.env` 文件**——
`.env` 是社区惯例、不是操作系统特性，它只存在于"我们手动读进来的那个 map"里。
于是 `env` map 传进来却没被用来取口令 ⇒ 空串。

## 为什么这次"会炸"，而有些环境"不会炸"

本机 PostgreSQL 用 `scram-sha-256`，**空口令一定鉴权失败** ⇒ 问题立刻以异常暴露。
若换成一个空口令可连的环境（trust/peer 认证），程序会**用空口令连上**，
于是"`.env` 没生效"就**永远不会被发现**，直到某天换机器。

⇒ **配置读取失败最好让它以"连不上"的形式炸掉**，而不是被默认值吸收。
这里 `default ""` 就是那个吸收器：它把"没读到"变成了一个"能用的值"。

## 修法

取值先查合并后的 env，再退回进程环境变量：

```java
private static String envOr(Map<String, String> env, String name, String fallback) {
    String value = env == null ? null : env.get(name);
    if (value == null || value.isBlank()) {
        value = CliConsole.envOr(name, fallback);     // 兼容"直接设了环境变量"的用法
    }
    return value;
}
```

⚠️ **同类隐患仍在别处**：同一个仓库里 `Bm25QueryCli` / `VectorQueryCli` / `Bm25IngestCli` /
`PgVectorIngestCli` 也用 `CliConsole.envOr` 取 `POSTGRES_PASSWORD`，而 `RetrievalQueryCli`
用的是"查 map"的版本（`envOr(cfg, name, fallback)`）。
⇒ 这些 CLI 在"只靠 `.env` 提供口令"的场景下**同样会拿到空口令**；它们此前能跑通，
是因为调用时进程环境里恰好也有这些变量（或用了 `-D` 参数）。

## 教训（可迁移）

1. **"配置存在" ≠ "配置生效"**：`.env` 被加载进 map，并不意味着所有读取点都从 map 取。
   新增读取点时，要**顺着数据流确认读到的是哪个来源**。
2. **默认值会吸收故障**：`default ""` 让"没读到"变成了"读到了空"。
   涉及凭据/地址的取值，宁可**不设默认值直接抛错**，或至少在值为空时明确报"未配置"。
3. **报错信息要能定位到来源**：`password is an empty string` 比"connection refused"
   信息量大得多——它直接指向"取值来源"而不是"网络/服务"。

## 锚点

- 修复：`backend/src/main/java/com/canalmind/structured/StructuredQueryCli.java` 的 `dataSource(...)`
  与新增的私有 `envOr(env, name, fallback)`
- 旧助手：`backend/src/main/java/com/canalmind/rag/cli/CliConsole.java` 的 `envOr(String, String)`（只读 `System.getenv`）
- 同类读取点（未改，已登记风险）：`Bm25QueryCli` 第 199–203 行、`VectorQueryCli` 第 389–393 行、
  `Bm25IngestCli` 第 290–294 行、`PgVectorIngestCli` 第 299–329 行
- 验证方式：`StructuredQueryCli --verify-engine-vs-db 200` 从"空口令鉴权失败"变为
  **200/200 逐分一致**（若无此修复，根本连不上库）
