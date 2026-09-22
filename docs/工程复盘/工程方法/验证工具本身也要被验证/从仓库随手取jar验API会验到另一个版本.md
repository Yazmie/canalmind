# 从仓库随手取 jar 验 API，会验到另一个版本

> 类别：[工程方法](../README.md) ｜ 主题：[验证工具本身也要被验证](README.md) ｜ 总索引：[../../README.md](../../README.md)
> 实测日期：2026-09-22（C-54 自写导出器）

## 现象

要做 Span 导出器，先得确认观测 API 的真实形状。我用 `javap` 去看
`io.micrometer.observation.Observation$Context` 的方法表——**这是本主题要求的正确动作**
（⛔ 不凭记忆猜框架 API）。取 jar 的命令是：

```powershell
Get-ChildItem -Recurse -Path 'backend\.m2repo' -Filter 'micrometer-observation-*.jar' | Select-Object -First 1
```

它给出 **`micrometer-observation-1.12.5.jar`**，`javap` 也就照着这一版回答我。

## 机制：探针的**输入源**取错了

`backend/.m2repo` 是**整个本地 Maven 仓库的副本**，里面**同时存在多个版本**
（`micrometer-observation` 实测有 1.12.5 与 1.15.7 两份；1.12.5 是别的依赖带进来的）。
`Select-Object -First 1` 取的是**目录枚举顺序**，与"本次构建到底用哪个"**毫无关系**。

⇒ 这是**探针本身错**，不是被测系统错：探针（`javap`）没坏，坏的是喂给它的那口输入。
它属于本主题开头说的那一族——**探针的语义 / 输入 / 判定模型各错一次**，这里是"输入"。

## 代价（这次没酿成，但是碰巧）

若照着 1.12.5 的接口写代码，有两种结局，**第二种更糟**：
① 编译期以真实类路径（1.15.7）为准 ⇒ 报错，代价只是返工；
② 两版接口**恰好兼容但语义不同** ⇒ **编译通过、测试通过、错误一路带进产物**——
这正是本项目反复吃亏的那类（`转义与写入层的静默损坏`）。

**这次为什么没中**：我在 `javap` 之后又把 `backend/target/cp.txt` 里的版本号读出来对了一遍，
发现 glob 给的是 1.12.5、而**真实编译类路径是 1.15.7**。
⇒ 同一件事**问了两个来源，答案不一致，才暴露出来**——不是因为我一开始就问对了。

## 正确姿势

**类路径的唯一权威 = 本次编译实际用的那个列表**，即项目根下的 `backend/target/cp.txt`
（由 `mvn dependency:build-classpath` 生成，与 `mvn -o` 用的是同一套解析结果）：

```powershell
$cp  = (Get-Content 'target\cp.txt' -Raw).Trim()
$jar = ($cp -split ';') | Where-Object { $_ -match 'micrometer-observation-.*\.jar$' } | Select-Object -First 1
javap -cp $jar 'io.micrometer.observation.Observation$Context'
```

⛔ **不要在 `.m2repo` / 全局 `.m2` 里 glob 一个 jar 来验 API**——那是在验"仓库里碰巧有哪个版本"。

## 可复用判据（一句话）

> **凡"我从缓存/仓库里取了个文件来验证"的动作，都要先回答一句：
> 这个文件是不是本次构建实际会用的那一个？**
> 症状信号是：**同一件事有两个来源，而它们给的答案不一样**——那说明你至少有一个来源是错的，
> 此时**不能挑顺眼的那个信**，要先判定谁是权威。

## 锚点

- `backend/target/cp.txt`：`micrometer-observation-1.15.7.jar`（真实编译类路径）
- `backend/.m2repo`：同目录下另有 `1.12.5`（glob 会先碰到它）
- 本轮两次 `javap` 的差异：`Observation.Context.getParentObservation()` 在两版都返回 `ObservationView`，
  但**方法表不同**——若只看 1.12.5 的方法表，会漏掉 1.15.7 上可用的重载
- 相关条目：[文本搜索统计落点会把注释算成代码](文本搜索统计落点会把注释算成代码.md)（同样是"探针给了一个顺眼但错的答案"）
