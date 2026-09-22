# Java 控制台输出编码：同一个项目里一半 CLI 乱码、一半正常

> **一句话**：JDK 18+ 的 `System.out` 默认按**控制台代码页**编码（本机 = GBK/936），
> 而本项目多数 CLI 的中文提示词靠 `System.out.println` 输出 ⇒ **在 GBK 控制台下全是乱码**。
> 只写 `-Dfile.encoding=UTF-8` **不管用**——那只管文件 IO 的默认字符集。
> **要看中文，必须再给 `-Dstdout.encoding=UTF-8`（和 `-Dstderr.encoding=UTF-8`）。**

| 项 | 内容 |
|---|---|
| **发现场合** | 2026-09-22 第 5 周写 `backend/scripts/demo.ps1`（一键演示脚本）后首次实跑 |
| **症状** | 演示脚本里 9 段有 2 段中文全是乱码，另外几段中文**完全正常** |
| **根因（已核实，非猜测）** | 见下节 |

## 1. 症状：不是"全乱"，而是"**一部分乱**"

同一次演示、同一个 JVM、同一组 JVM 参数（当时只给了 `-Dfile.encoding=UTF-8`）：

| 段 | 用的 CLI | 中文显示 |
|---|---|---|
| 意图路由自检 | `IntentRouterCli` | ❌ **乱码** |
| 分层通过率报告 | `HarnessCli` | ❌ **乱码**（`=== ��ͼ·���Լ죨�� 4 �ܵ� 1 �...`） |
| 向量通道 | `VectorQueryCli` | ✅ 正常 |
| BM25 通道 | `Bm25QueryCli` | ✅ 正常 |
| 融合管线 | `RetrievalQueryCli` | ✅ 正常 |

**"同一个 JVM、同一组参数、同一台机器，只有一部分乱码"** 是最容易把人带偏的形态：
很容易误判成"某几个 CLI 的字符串写错了"或"终端的问题"，而不是**编码设置问题**。

## 2. 根因：两类 CLI 对 `System.out` 的处理**不一样**

| 侧 | 做法 | 后果 |
|---|---|---|
| **RAG 侧**（`rag/cli/CliConsole.java` 第 28–30 行） | **显式把 `System.out` 换掉**：`System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))` | 输出**恒定 UTF-8**，与控制台代码页无关 ⇒ ✅ 免疫 |
| **其余侧**（`intent` / `eval` / `structured` 等） | 直接用默认的 `System.out` | JDK 18+ 的 `System.out` **按控制台代码页编码** ⇒ 本机 GBK 控制台下 ❌ 乱码 |

⚠️ **反过来读更值得记**：RAG 侧之所以正常，**不是我配对了参数，而是那个类里恰好写着 `System.setOut`**——
**这是一次"碰巧没踩到"，不是"已经解决"**。若哪天把 `CliConsole` 抽走，那三个 CLI 会**同时**开始乱码。

## 3. 修法

```powershell
# 只写这一条不够（它管的是文件 IO 的默认字符集）
-Dfile.encoding=UTF-8

# 中文输出必须补这两条（JDK 19+ 支持的属性）
-Dstdout.encoding=UTF-8
-Dstderr.encoding=UTF-8
```

演示脚本里已把它固定成一个包装函数，⚠️ **所有 Java 调用都走它**，避免"某处漏写":

```powershell
$common = @(
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
    '-cp', $script:Cp, $MainClass
)
```

**PowerShell 侧也要配对**（否则是"Java 写对了、宿主读错了"，看起来一样是乱码）：

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

## 4. 怎么自证"修好了"（而不是"看着像好了"）

1. **挑一个原本乱码的段**单独跑（如 `HarnessCli --report`），确认中文逐字正确；
2. **两个方向都测**：`powershell.exe`（5.1）与 `pwsh`（7）各跑一次——
   本项目实测**两种宿主下现在都正常**；
3. ⛔ **不要只看"命令退出码 0"**：乱码时退出码同样是 0，**它不会报错**
   （同族教训：[包装器退出 0 不等于脚本跑了](脚本文件BOM.md)）。

## 5. 教训（可复用的部分）

1. **"输出编码"是三层叠加的**：Java 的 `System.out` 字符集 → 进程 stdout 字节 → **宿主的解码方式**。
   任一层错，现象都是"乱码"，但**修法完全不同** ⇒ 必须**逐层确认**，不能凭感觉调参数。
2. **中文字面量走命令行参数是另一个坑**（与本条不同层）：`psql -c "…中文…"` 会被控制台代码页改坏
   ⇒ 既定约定是 **SQL 一律走 `psql -f <UTF-8 无 BOM 文件>`**，见
   [psql从子进程调用会挂住](psql从子进程调用会挂住.md)。
   ⚠️ **我在写演示脚本时明知这条约定，却还是在 `psql -c` 里写了中文标签**，被自己的脚本当场暴露——
   **"知道约定"与"写的时候想起来"是两件事**，这也是要把约定沉进模板/包装函数的原因。
3. **演示脚本是这类问题的好探测器**：它把平时分散在十几条命令里的调用**集中跑一遍**，
   于是"只有一部分乱码"这种**平时不会同时出现**的对比才浮出来。

## 6. 相关条目

- [脚本文件BOM](脚本文件BOM.md) —— `.ps1` 必须 BOM、`.md` 必须无 BOM（⚠️ 已复发四次）
- [UTF8中文文件按ANSI读会丢换行](UTF8按ANSI读丢换行/README.md) —— 宿主**读**的一侧出错
- [psql从子进程调用会挂住](psql从子进程调用会挂住.md) —— 配套约定：SQL 走 `psql -f`
- 返回本类索引：[README.md](README.md)
