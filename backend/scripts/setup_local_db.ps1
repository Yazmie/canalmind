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

# 推断仓库根
if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$psql = Join-Path $PgHome "bin\psql.exe"
$createdb = Join-Path $PgHome "bin\createdb.exe"
$dropdb = Join-Path $PgHome "bin\dropdb.exe"
$ddl = Join-Path $RepoRoot "backend\src\main\resources\db\migration\V1__init_business_schema.sql"
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
foreach ($exe in @($psql, $createdb, $dropdb)) {
    if (-not (Test-Path $exe)) { Bad "缺少 $(Split-Path $exe -Leaf)"; exit 1 }
}
Good "PostgreSQL 工具齐备"

if (-not (Test-Path $ddl)) {
    Bad "找不到 DDL: $ddl"
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
    Info "库已存在，按 -Recreate 删除重建"
    $r = Invoke-Native $dropdb @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '--if-exists', $Database)
    if ($r.ExitCode -ne 0) { Bad "删除失败：$($r.Output)"; exit 1 }
    $exists = ""
}

if ($exists -ne "1") {
    $r = Invoke-Native $createdb @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, $Database)
    if ($r.ExitCode -ne 0) { Bad "建库失败：$($r.Output)"; exit 1 }
    Good "已创建库 $Database"
} else {
    Info "库 $Database 已存在（未加 -Recreate，保留现有数据）"
}

# ---------------------------------------------------------------------------
Step 3 "执行 DDL"
# ---------------------------------------------------------------------------
$r = Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser,
    '-d', $Database, '-q', '-v', 'ON_ERROR_STOP=1', '-f', $ddl)
if ($r.ExitCode -ne 0) {
    Bad "DDL 执行失败"
    Info $r.Output
    Info "若表已存在，请加 -Recreate 重建（会清空现有数据）"
    exit 1
}

$tblCount = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE';")).Output
Good "建表完成，当前 $tblCount 张表"

# 关键约束检查：EXCLUDE 约束是 tariff_rule 防重叠的保证
$excl = (Invoke-Native $psql @('-w', '-h', $PgHost, '-p', "$PgPort", '-U', $PgUser, '-d', $Database,
    '-tAc', "SELECT count(*) FROM pg_constraint WHERE contype='x';")).Output
if ($excl -eq "1") { Good "tariff_no_overlap EXCLUDE 约束已生效" }
else { Bad "期望 1 个 EXCLUDE 约束，实际 $excl 个" }

# ---------------------------------------------------------------------------
Step 4 "装载仿真数据"
# ---------------------------------------------------------------------------
if ($SkipSeed) {
    Info "按 -SkipSeed 跳过数据装载"
} elseif (-not (Test-Path $loadSql)) {
    Bad "找不到装载脚本: $loadSql"
    Info "请先执行: python backend/scripts/generate_seed_data.py"
    exit 1
} else {
    Info "装载中（22 万行，约需 1-3 分钟）…"
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
