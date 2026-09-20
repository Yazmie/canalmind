# `pg_ctl start` 报 error 87：受限令牌

> 类别：[环境坑](README.md) ｜ 返回总索引：[../README.md](../README.md)

| 项 | 内容 |
|---|---|
| **坑** | 用 `pg_ctl start` 起临时 PG 集群，报：`pg_ctl: could not create restricted token: error code 87` / `could not start server: error code 87` |
| **根因** | `pg_ctl` 在 Windows 上需要**创建受限令牌**来降权子进程以启动服务，**受限执行环境会拦下这个操作**。 |
| **解法** | **直接运行 `postgres.exe`**：`postgres.exe -D <datadir> -p <port>`，绕开 `pg_ctl` 的降权逻辑。 |
| **注意** | 直接跑 postgres 时**全程只用 TCP 连接**（`-h 127.0.0.1`），不要用命名管道——受限环境也禁止命名管道。 |
| **业务含义** | 这类"工具在受限环境下不可用"的问题，**解法通常是绕开它调用的那个特权系统调用**，而不是提权。判断依据：**先看报错说的是哪个能力被拒**（这里是"创建受限令牌"），再找不用该能力的等价路径。 |
| **相关** | [子进程随超时被回收](子进程随超时被回收.md)（直接跑 postgres 之后遇到的下一个问题） |
