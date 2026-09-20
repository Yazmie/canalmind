"""把工作区本地 Maven 仓库的 _remote.repositories 归一化到当前 mirror id。

背景（本项目真实踩过的坑，见 AGENTS.md §7.2）
--------------------------------------------
本机 `_remote.repositories` 里记录的来源 id 为 `aliyunmaven`，
而 `settings.xml` 的 mirror id 为 `huaweicloud`。两者不一致时，Maven 判为
"present, but unavailable"，随后尝试**联网重下并改写 tracker**——
在受限沙箱里改写会失败（D:\\MavenRepo 不可写），于是构建在读取 POM 阶段就中断。

已知最早触发点：`io.netty:netty-bom:4.1.130.Final` 的父 POM
`org.sonatype.oss:oss-parent:7` 在缺失 mirror 条目时无法解析，
而它由 `spring-boot-dependencies` 以 import 方式引入，**必然被读取**。

本脚本做的事
------------
Maven 联网成功抓到构件后，会往 `_remote.repositories` 追加一行
`<文件名>><mirrorId>=`。本脚本**等价地补上这一行**，使离线构建不再需要回源验证。
只处理 `.pom` / `.jar`（Maven 依赖解析只关心这两类的来源），
`.lastUpdated` 等失败标记一并删除，避免残留导致继续判为缺失。

用法
----
    python backend/scripts/fix_repo_tracking.py --repo backend/.m2repo --mirror-id huaweicloud
    python backend/scripts/fix_repo_tracking.py --repo backend/.m2repo --mirror-id huaweicloud --check
"""

import argparse
import sys
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

TRACKED_SUFFIXES = (".pom", ".jar")


def normalize(repo: Path, mirror_id: str, check_only: bool) -> tuple[int, int, int]:
    """返回 (扫描的 tracker 数, 需修补/已修补 数, 删除的 .lastUpdated 数)。"""
    scanned = 0
    fixed = 0
    removed = 0

    for tracker in repo.rglob("_remote.repositories"):
        scanned += 1
        try:
            text = tracker.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            print(f"  ! 读取失败 {tracker}: {e}")
            continue

        lines = text.splitlines()
        present = set()
        for ln in lines:
            if ">" in ln and ln.endswith("="):
                # 形如：oss-parent-7.pom>aliyunmaven=
                left, _, right = ln.partition(">")
                present.add((left, right.rstrip("=")))

        need = []
        for ln in lines:
            if ">" not in ln or not ln.endswith("="):
                continue
            left, _, right = ln.partition(">")
            rid = right.rstrip("=")
            if rid == mirror_id:
                continue
            if left.endswith(TRACKED_SUFFIXES):
                if (left, mirror_id) not in present:
                    need.append(f"{left}>{mirror_id}=")

        if need:
            fixed += 1
            if not check_only:
                new_text = text
                if not new_text.endswith("\n"):
                    new_text += "\n"
                new_text += "\n".join(need) + "\n"
                tracker.write_text(new_text, encoding="utf-8")

    # 清理失败标记
    for stale in repo.rglob("*.lastUpdated"):
        removed += 1
        if not check_only:
            try:
                stale.unlink()
            except OSError as e:
                print(f"  ! 删除失败 {stale}: {e}")

    return scanned, fixed, removed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="本地 Maven 仓库目录")
    ap.add_argument("--mirror-id", default="huaweicloud", help="settings.xml 中的 mirror id")
    ap.add_argument("--check", action="store_true", help="只检查不修改")
    args = ap.parse_args()

    repo = Path(args.repo).resolve()
    if not repo.is_dir():
        print(f"✗ 仓库目录不存在: {repo}")
        return 1

    print("=" * 68)
    print(f"归一化本地仓库来源标记")
    print(f"  仓库    : {repo}")
    print(f"  mirror  : {args.mirror_id}")
    print(f"  模式    : {'只检查' if args.check else '写入'}")
    print("=" * 68)

    scanned, fixed, removed = normalize(repo, args.mirror_id, args.check)

    print(f"扫描 tracker 文件 : {scanned}")
    print(f"需补 mirror 条目  : {fixed}")
    print(f"清除 .lastUpdated : {removed}")
    if args.check and fixed:
        print("\n[FAIL] 存在需修补的来源标记，去掉 --check 再跑一次")
        return 1
    print("\n[PASS] 本地仓库来源标记与当前 mirror 一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
