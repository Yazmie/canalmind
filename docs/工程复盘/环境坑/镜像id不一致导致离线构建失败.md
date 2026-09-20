# 镜像 id 不一致导致离线构建失败

> 类别：[环境坑](README.md) ｜ 返回总索引：[../README.md](../README.md)

| 项 | 内容 |
|---|---|
| **现象** | 离线构建报 `Non-resolvable parent POM for io.netty:netty-bom:4.1.130.Final … org.sonatype.oss:oss-parent:pom:7 (present, but unavailable)`。**构件明明在本地仓库里**，Maven 却说"存在但不可用"。 |
| **根因** | 本地仓库里每个构件目录下的 `_remote.repositories` 记录了它**来自哪个仓库 id**。本机缓存记录的是 `huaweicloud`，而当时的 `settings.xml` 镜像 id 是另一个名字。**id 对不上，Maven 就认为这份缓存不是当前配置下载的，拒绝使用，转而去联网——离线环境于是失败。** |
| **踩坑点** | ⚠️ 本项目 `AGENTS.md` 曾把两侧的 id **写反**（把 `settings.xml` 里实际是 `huaweicloud` 说成 `aliyunmaven`）。**文档里的环境事实也会错**，遇到不一致时以本机实测为准（`Select-String` 读 `settings.xml` 的 `<id>`，再抽一个 `_remote.repositories` 对比）。该文档已更正。 |
| **修法** | ①把仓库复制进工作区（`backend/.m2repo`），用 `-Dmaven.repo.local=` 指向它，避免动到全局缓存；②跑 `backend/scripts/fix_repo_tracking.py` 把 `_remote.repositories` 的 id 统一成当前镜像、并删除 `*.lastUpdated`；③`-s` 与 `-gs` 都指定同一个 `settings.xml`。实测：修复后 15895 个文件、1011 MB 的本地仓库可完全离线构建。 |
| **另一处相关坑** | 直连全局仓库时还遇到 `AccessDeniedException: D:\MavenRepo\...\_remote.repositories`——因为沙箱只允许写工作区。**把仓库放进工作区**同时解决了"改不了"和"写不了"两个问题。 |
| **代价** | 本轮为了绕开它，手工拼过一个 Jackson 最小 classpath 来跑 CLI。回头看，**先把仓库搬进工作区 + 修 id 更省事**，不必逐个凑 jar。 |
| **业务含义** | "本地有构件"和"本地构件可用"是两件事。**缓存的元数据（来源 id）是可用性的一部分**，换镜像、换配置后，缓存可能需要重新归属。 |
| **⚠️ 不要把 `.m2repo/` 提交进 git** | 它约 1011 MB。必须写进 `.gitignore`。 |
| **相关** | [Maven增量编译留下过期类](Maven增量编译留下过期类.md)｜[知识库版本号不可照抄](知识库版本号不可照抄.md) |

