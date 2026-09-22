# 编译错误被静默放过：构建绿了，产物却是一颗运行时炸弹

> **一句话**：源码里有**编译错误**，构建却**不失败**——它生成一个能进 classpath、
> 但**一被调用就抛 `java.lang.Error`** 的类。于是测试"跑到了"、却全数报错，
> 而人看到的第一个信号是**运行期的 Error**，不是"你根本没编译过"。
>
> 返回本类索引：[../README.md](../README.md)

**本主题拆成三条**（本条是主篇）：

| 子条目 | 讲什么 |
|---|---|
| [机制与证据分级](机制与证据分级.md) | 机制 = **Maven 之外的一条编译路径往 `target/classes` 写了带伤类 + Maven 按时间戳跳过它**；逐环节标出**实测 / 反证 / 推断 / 无证据**，⛔ 不把"是哪个 IDE"写成结论 |
| [我误判带伤字节码的来历](../../诚实记录/我误判带伤字节码的来历.md) | ★ **主因在「诚实记录」类**：我把"文件被跳过"与"跑的是旧字节码"**捆成一个判断**，证伪了后半，就把**成立的前半也一起扔了**；另外还拿"仓库里没有 ecj"去质疑对方的实测 ⇒ **两次判断错 + 一次结论外溢** |

## 1 现象与证据（实测，2026-09-22）

埋 `ai.tool.param.extract` 的重试 Span 时，`observe(() -> attemptOnce(…, errors, attempt, …))`
捕获了两个**不是 effectively final** 的局部量（`errors` 在循环末尾被重新赋值、`attempt` 是循环变量）。

- **`javac` 全树编译**（110 个文件）：`exit=1`，直接报
  `local variables referenced from a lambda expression must be final or effectively final`（2 处）。
- **同一份源码走 Maven 构建**：**不失败**，surefire 照常出报告
  `Tests run: 5, Failures: 0, Errors: 5`，每条用例的堆栈是：

  ```
  java.lang.Error:
  Unresolved compilation problems:
      Local variable attempt is required to be final or effectively final based on its usage
      Local variable errors is required to be final or effectively final based on its usage
      at com.canalmind.structured.ToolArgumentExtractor.extract(ToolArgumentExtractor.java:99)
  ```

- **字节码旁证**：`javap` 当时那份 `target/classes/…/ToolArgumentExtractor.class`，
  **有 `attemptOnce`、却没有对应的 `lambda$extract`** ⇒ 它是从**出错源码带伤生成**的，
  ⛔ 不是"没重编的陈旧字节码"（见 [../诚实记录/我误判带伤字节码的来历.md](../../诚实记录/我误判带伤字节码的来历.md)）。

## 2 为什么危险

1. **失败信号被搬到了最贵的位置**：编译期的问题变成运行期 `Error`，离"我改了哪一行"最远。
2. **假绿很有说服力**：有报告、有 `Tests run` 行、测试类确实"执行过"——只有 `Errors: 5`
   一处不同。**只看"跑完了"就会当成功。**
3. **它只污染改动的部分**：没改的类照常通过，于是同一份报告**大部分是绿的**，
   红的几条像是"这几条测试自己有问题"。
4. **它专挑重构下手**：本例正是"把循环体抽成方法、再包进 `observe(...)`"这类
   **语义等价、但局部变量可见性变差**的重构。

## 3 防御（与机制无关，照做即可）

1. **改完 Java 源码，独立跑一次全树 `javac`**：它与构建是两条独立编译路径，
   而 javac 对这类错误**立刻报错**。（应与 `AGENTS.md` §7.2 的"改完跑转义审计"并列执行。）
2. **跑测试前先删 `target/classes` 与 `target/test-classes`**：唯一能排除"陈旧/带伤字节码"的做法。
3. **⚠️ 别把"有 surefire 报告"当成"编译过"**：`Tests run` 行里 **`Failures` 与 `Errors`
   是两件事**——`Errors` 往往意味着"断言根本没跑到"。
4. 这类错误**人工评审看不出来**：源码里只少两个 `final` 副本，结构与注释都很正常。

## 4 锚点

- 现象证据：`backend/target/surefire-reports/com.canalmind.observability.AiSpanTreeTest.txt`
  （2026-09-22 10:42:31 那一次：`Errors: 5`，堆栈含 `ToolArgumentExtractor.java:99`）。
- 对照证据：同一份源码全树 `javac` 得 `exit=1`（`-encoding UTF-8 -parameters -cp <cp.txt>`）。
- 修复：循环内先取 `final` 副本（`attemptNo` / `errorsSoFar`）再进 lambda，
  ⛔ 不改回手工 `start()/stop()`（那会引出 [../观测埋点的父指针会静默丢失.md](../观测埋点的父指针会静默丢失.md) 那个静默失败）。
