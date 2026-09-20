<#
.SYNOPSIS
    初始化 CanalMind 本地开发数据库

.DESCRIPTION
    对已运行的 PostgreSQL 执行：建库 → 建表（DDL）→ 装载仿真数据 → 校验行数。

    设计意图：把「数据库怎么从零建起来」固化成可重复执行的脚本，
    而不是散落在文档里靠手工敲。

    前置条件：PostgreSQL 服务已运行，且当前用户可连接。

.PARAMETER PgHome
    PostgreSQL 安装目录（含 bin\psql.exe）。

.PARAMETER PgPort
    服务端口。默认 5432（本机 PG18 服务，决策 C-12）。

.PARAMETER Credential
    数据库口令。不传则依赖 trust 认证或 .pgpass。

.EXAMPLE
    # 用本机 PG18 服务（默认）
    powershell -File setup_local_db.ps1 -Credential (Read-Host -AsSecureString)

.EXAMPLE
    # 先重建（小心：会 DROP 现有库）
    powershell -File setup_local_db.ps1 -Recreate

.NOTES
    数据库口令也可通过环境变量 PGPASSWORD 传入，避免出现在命令行历史里。
#>

[CmdletBinding()]
param(
    [string]$PgHome = "D:\APP\PostgreSQL\18",
    [string]$PgHost = "127.0.0.1",
    [int]$PgPort = 5432,
    [string]$PgUser = "postgres",
    [string]$Database = "canalmind",
    # 仓库根目录；默认由脚本位置推断（scripts 的上两级）
    [string]$RepoRoot = "",
    # 跳过仿真数据装载（只建表）
    [switch]$SkipSeed,
    # 库已存在时先删除重建
    [switch]$Recreate
)

$ErrorActionPreference = "Stop"

function Say($msg, $color = "Gray") { Write-Host $msg -ForegroundColor $color }
function Step($n, $msg) { Write-Host ""; Write-Host "[$n] " -NoNewline -ForegroundColor Cyan; Write-Host $msg -ForegroundColor White }
function Good($m) { Write-Host "    OK   " -NoNewline -ForegroundColor Green; Write-Host $m }
function Bad($m)  { Write-Host "    FAIL " -NoNewline -ForegroundColor Red;   Write-Host $m }
function Info($m) { Write-Host "    ..   " -NoNewline -ForegroundColor DarkGray; Write-Host $m }

# 运行原生命令并安全捕获输出与退出码。
#
# 为什么要这个包装：本脚本设了 $ErrorActionPreference = "Stop"，而 PowerShell 会把
# 原生命令写入 stderr 的内容转成 ErrorRecord —— 于是 psql 的一句普通报错
# （如"未提供口令"）会被当成终止性错误，脚本在打印友好提示前就直接崩掉。
# 这里临时把 ErrorActionPreference 降为 Continue，把 stderr 与退出码
# 显式取回来交给调用方判断。
function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$Exe,
        [Parameter(Mandatory)][string[]]$Args
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $Exe @Args 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    $text = ($out | Where-Object { $_ -notmatch '^\s*$' }) -join "`n"
    return [pscustomobject]@{ ExitCode = $code; Output = $text.Trim() }
}

# 带硬超时的原生命令执行。
#
# 为什么需要它：某些 PostgreSQL 操作会**无限挂起**而不是报错。
# 最典型的是 DROP DATABASE —— 它需要给所有后端发 ProcSignalBarrier，
# 若有进程无法被投递信号，则该语句永久卡在 wait_event=ProcSignalBarrier，
# 而且 **statement_timeout 无法生效**（超时检查本身也依赖同一套信号机制）。
#
# 挂起是最糟的失败模式：用户只看到「卡住」，既没有错误也没有提示，
# 无法判断是自己环境的问题还是脚本的问题。
# 因此这里用带超时的进程启动，保证「最坏情况 = 明确失败」。
function Invoke-NativeTimeout {
    param(
        [Parameter(Mandatory)][string]$Exe,
        [Parameter(Mandatory)][string[]]$Args,
        [int]$TimeoutSeconds = 30
    )
    # ⚠️ 用 System.Diagnostics.Process 而不是 Start-Process -PassThru。
    # 实测 Windows PowerShell 5.1 下 Start-Process -PassThru 的 $p.ExitCode
    # 会返回 $null（进程对象已被回收），导致「命令其实成功了」被误判为失败。
    # System.Diagnostics.Process 的 ExitCode 稳定可靠。
    #
    # ⚠️ .Arguments 是**一个字符串**而非数组；含空格的参数（如内联 SQL）
    # 必须自己加引号，否则会被拆成多个参数 —— 表现为 psql 报
    # 'extra command-line argument "..." ignored' 加 "syntax error at end of input"。
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = ($Args | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true

    $p = $null
    try {
        $p = [System.Diagnostics.Process]::Start($psi)
        # 必须先异步读完两个流再 WaitForExit：否则管道缓冲区写满会互相死锁
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $errTask = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($TimeoutSeconds * 1000)) {
            try { $p.Kill() } catch { }
            return [pscustomobject]@{
                ExitCode = -1
                TimedOut = $true
                Output   = "命令超过 $TimeoutSeconds 秒未返回，已强制终止"
            }
        }
        $text = (@($outTask.Result, $errTask.Result) |
                 Where-Object { $_ -and $_ -notmatch '^\s*$' }) -join "`n"
        return [pscustomobject]@{
            ExitCode = $p.ExitCode
            TimedOut = $false
            Output   = $text.Trim()
        }
    } finally {
        if ($p) { $p.Dispose() }
    }
}

# 推断仓库根
if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$psql = Join-Path $PgHome "bin\psql.exe"
$createdb = Join-Path $PgHome "bin\createdb.exe"
# 不使用 dropdb.exe：本脚本的重建走「删 schema」而非「删库」，原因见 Step 2。
$migrationDir = Join-Path $RepoRoot "backend\src\main\resources\db\migration"
# 按文件名升序执行全部迁移（V1、V2、…）。
# 升序很重要：V2 依赖 V1 建好的 voyage 表。
$migrations = @(Get-ChildItem -Path $migrationDir -Filter "V*.sql" -ErrorAction SilentlyContinue |
                Sort-Object Name)
$loadSql = Join-Path $RepoRoot "backend\scripts\seed_output\load_seed.sql"

# 统一客户端编码，避免中文注释/数据乱码
$env:PGCLIENTENCODING = "UTF8"

Say ""
Say "CanalMind 本地数据库初始化" "White"
Say "  目标库    : $Database @ ${PgHost}:${PgPort}"
Say "  PG 安装   : $PgHome"
Say "  仓库根    : $RepoRoot"

# ---------------------------------------------------------------------------
Step 1 "前置检查"
# ---------------------------------------------------------------------------
foreach ($exe in @($psql, $createdb)) {
    if (-not (Test-Path $exe)) { Bad "缺少 $(Split-Path $exe -Leaf)"; exit 1 }
}
Good "PostgreSQL 工具齐备"

if ($migrations.Count -eq 0) {
    Bad "在 $migrationDir 找不到任何 V*.sql 迁移文件"
    exit 1
}
Good "DDL 就位"

# 用 psql 探测连接与认证。
#
# 这里刻意不用 Test-NetConnection 做端口预检：
#   1) 它在受限环境下可能因权限拿不到结果，误报"端口不通"，而实际服务正常；
#   2) 裸 TCP 探测只能判断"有没有监听"，无法区分"服务未启动"与"口令错误"。
# 交给 psql 直接报错，信息更准，也少一次连接。
#
# -w：绝不弹出交互式口令提示。否则认证失败时脚本会挂起等待输入，
# 在自动化场景下表现为「无输出卡死」，极难定位。宁可立刻失败并给出提示。
$probe = Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser,
    '-d', 'postgres', '-tAc', 'SELECT 1;')
if ($probe.ExitCode -ne 0) {
    if ($probe.Output -match 'no password supplied|password authentication failed') {
        Bad "认证失败：需要口令"
        Info "请先设置环境变量后重跑，例如："
        Say '        $env:PGPASSWORD = "你的口令"' "DarkYellow"
        Say "        powershell -File backend\scripts\setup_local_db.ps1" "DarkYellow"
    } elseif ($probe.Output -match 'could not connect|Connection refused|server not running') {
        Bad "无法连接 ${PgHost}:${PgPort}"
        Info "请先启动 PostgreSQL 服务，或用 -PgPort 指定实际端口"
    } else {
        Bad "连接失败"
        Info $probe.Output
    }
    exit 1
}
Good "连接与认证正常"

# ---------------------------------------------------------------------------
Step 2 "建库"
# ---------------------------------------------------------------------------
$exists = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser,
    '-d', 'postgres', '-tAc', "SELECT 1 FROM pg_database WHERE datname='$Database';")).Output

if ($exists -eq "1" -and $Recreate) {
    Info "库已存在，按 -Recreate 重置业务数据"

    # ⚠️ 这里刻意**不删库**，而是删 schema 再重建。
    #
    # 为什么不用 DROP DATABASE：该语句需要向**所有后端**投递 ProcSignalBarrier，
    # 只要有一个进程收不到信号，语句就永久卡在 wait_event=ProcSignalBarrier。
    # 实测在本机受限环境下即使库上**零连接**也会无限挂起（2 分钟以上不返回），
    # 而且 **statement_timeout 也拦不住** —— 超时检查本身依赖同一套信号机制。
    # 这会把「重建」变成一个静默卡死的操作，非常难排查。
    #
    # DROP SCHEMA public CASCADE 不涉及跨后端信号，实测 0.4 秒完成，
    # 效果等价（业务对象全在 public 下），且天然幂等。
    #
    # 注意：CASCADE 会连带删掉 btree_gist 扩展 —— 这是预期的，
    # DDL 里有 CREATE EXTENSION IF NOT EXISTS，重建时会自动装回。
    #
    # 外层仍套硬超时作兜底，保证最坏情况是「明确失败」而不是「无限等待」。
    $r = Invoke-NativeTimeout $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
        '-v', 'ON_ERROR_STOP=1',
        '-c', 'DROP SCHEMA IF EXISTS public CASCADE;',
        '-c', 'CREATE SCHEMA public;') -TimeoutSeconds 30

    if ($r.TimedOut) {
        Bad "重置 schema 超时（30 秒未返回），已强制终止"
        Info "通常是有客户端正持有该库的锁，请关闭 IDE 的 DB 面板 / 正在运行的服务后重试"
        exit 1
    }
    if ($r.ExitCode -ne 0) {
        Bad "重置 schema 失败"
        Info $r.Output
        exit 1
    }
    Good "已清空 public schema，业务对象将重建"
}

if ($exists -ne "1") {
    $r = Invoke-Native $createdb @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, $Database)
    if ($r.ExitCode -ne 0) { Bad "建库失败：$($r.Output)"; exit 1 }
    Good "已创建库 $Database"
} elseif (-not $Recreate) {
    Info "库 $Database 已存在（未加 -Recreate，保留现有数据）"
}
# 注意：-Recreate 分支已在上面重置 schema，这里不再重复提示，
# 否则会出现「已重置」紧跟「未加 -Recreate」的自相矛盾输出。

# ---------------------------------------------------------------------------
Step 3 "执行 DDL 迁移"
# ---------------------------------------------------------------------------
# 先看表是否已建。V1 里的 CREATE TABLE 不是幂等的（未加 IF NOT EXISTS），
# 重复执行必然报 "already exists"，这是预期行为而非错误。
# 因此这里显式判断：已建表就跳过迁移，让脚本可以安全重复运行，
# 而不是逼用户为了重跑一次就去 -Recreate 清库。
#
# ⚠️ 已知限制（记录而非隐藏）：本脚本**没有迁移账本**（类似 Flyway 的
#    schema_history），所以「已有表就跳过全部迁移」意味着——
#    对**已建库**新增的迁移（如 V2）不会自动应用，必须走 -Recreate 重建。
#    对本项目可接受：库里全是可再生的仿真数据。
#    正式项目应引入 Flyway 做增量迁移，此处记为后续改进项。
$preTbl = [int]((Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';")).Output)

if ($preTbl -gt 0) {
    Info "检测到已有 $preTbl 张表，跳过迁移（如需重建请加 -Recreate）"
} else {
    foreach ($mig in $migrations) {
        $r = Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser,
            '-d', $Database, '-q', '-v', 'ON_ERROR_STOP=1', '-f', $mig.FullName)
        if ($r.ExitCode -ne 0) {
            Bad "迁移执行失败: $($mig.Name)"
            Info $r.Output
            exit 1
        }
        Good "已应用迁移 $($mig.Name)"
    }
}

$tblCount = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';")).Output
Good "当前共 $tblCount 张表"

# 关键约束检查：EXCLUDE 约束是 tariff_rule 防重叠的保证
$excl = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT count(*) FROM pg_constraint WHERE contype='x';")).Output
if ($excl -eq "1") { Good "tariff_no_overlap EXCLUDE 约束已生效" }
else { Bad "期望 1 个 EXCLUDE 约束，实际 $excl 个" }

# ---------------------------------------------------------------------------
Step 4 "装载仿真数据"
# ---------------------------------------------------------------------------
# 先查 waybill 是否已有数据。装载脚本用 OVERRIDING SYSTEM VALUE 直接插主键，
# 重复执行会撞主键而非报错退出，产出难以理解的中途失败状态。
# 与 DDL 同理：有数据就跳过，让脚本可安全重复运行。
$preRows = [int]((Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT count(*) FROM waybill;")).Output)

if ($SkipSeed) {
    Info "按 -SkipSeed 跳过数据装载"
} elseif ($preRows -gt 0) {
    Info "检测到已有 $preRows 行运单，跳过装载（如需重装请加 -Recreate）"
} elseif (-not (Test-Path $loadSql)) {
    Bad "找不到装载脚本: $loadSql"
    Info "请先执行: python backend/scripts/generate_seed_data.py"
    exit 1
} else {
    Info "装载中（22 万行，约需 5-15 秒）…"
    $r = Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser,
        '-d', $Database, '-q', '-v', 'ON_ERROR_STOP=1', '-f', $loadSql)
    if ($r.ExitCode -ne 0) { Bad "数据装载失败：$($r.Output)"; exit 1 }
    Good "装载完成"
}

# ---------------------------------------------------------------------------
Step 5 "校验"
# ---------------------------------------------------------------------------
# 注意：pg_stat_user_tables.n_live_tup 依赖统计信息收集，刚装载完可能滞后，
# 因此额外用 count(*) 抽查核心表，给出确定的行数。
$verify = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT COALESCE(sum(n_live_tup),0) FROM pg_stat_user_tables WHERE schemaname='public';")).Output
Say ""
Say "    pg_stat 估算总行数: $verify" "Gray"

$golden = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT (SELECT count(*) FROM waybill) || ' waybill / ' || (SELECT count(*) FROM fee_record) || ' fee_record';")).Output
Say "    实测核心表行数  : $golden" "Gray"

# pgvector 是否可用（缺失不影响业务表，但影响向量检索通道）
$vec = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT 1 FROM pg_available_extensions WHERE name='vector';")).Output
Say ""
if ($vec -eq "1") {
    Good "pgvector 扩展可用"
    $r = Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
        '-tAc', 'CREATE EXTENSION IF NOT EXISTS vector;')
    if ($r.ExitCode -ne 0) { Bad "启用 vector 扩展失败：$($r.Output)"; exit 1 }
    $vv = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
        '-tAc', "SELECT extversion FROM pg_extension WHERE extname='vector';")).Output
    Good "vector 扩展已启用，版本 $vv"
} else {
    Bad "pgvector 未安装（向量检索通道需要，见决策 C-15）"
    Info "安装脚本: backend/scripts/build_pgvector.ps1"
}

Say ""
Say "──────────────────────────────────────────────────────────────" "DarkGray"
Say "  完成。业务表就绪，可用 probe_domain_model.sql 验证数据模型能力。" "Green"
Say ""
