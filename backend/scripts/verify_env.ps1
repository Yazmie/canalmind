<#
.SYNOPSIS
    CanalMind 开发环境自检脚本

.DESCRIPTION
    逐项检查本项目所需的开发环境，输出可用/不可用与版本号。
    对应规格书 §8「第 1 周」的环境就绪目标，以及 DECISIONS.md C-11 ~ C-15。

    设计意图：把"环境是否就绪"变成可重复执行的检查，而不是靠人工记忆。
    换机器或重装后先跑本脚本，能立刻定位缺什么。

.EXAMPLE
    pwsh -File verify_env.ps1
    pwsh -File verify_env.ps1 -PgPort 55432 -RedisPort 6379
#>

[CmdletBinding()]
param(
    # 本机 PostgreSQL 服务端口（决策 C-12：使用本机 PG18.4）
    [int]$PgPort = 5432,
    [string]$PgHost = "127.0.0.1",
    [string]$PgUser = "postgres",
    # Redis 端口
    [int]$RedisPort = 6379,
    # 本机 PostgreSQL 安装目录（用于定位 bin 下的工具）
    [string]$PgHome = "D:\APP\PostgreSQL\18",
    [string]$RedisHome = "D:\APP\Redis-x64-5.0.14.1",
    # 要求的 JDK 主版本（决策 C-11）
    [int]$RequiredJavaMajor = 21,
    # JDK 候选路径（按顺序探测）
    [string[]]$JdkCandidates = @(
        "D:\JDKmove\JDK21",
        $env:JAVA_HOME,
        "D:\APP\IDEA\IntelliJ IDEA 2025.1.7.1\jbr"
    )
)

$ErrorActionPreference = "Continue"
$script:Pass = 0
$script:Fail = 0

function Section($title) {
    Write-Host ""
    Write-Host "── $title " -NoNewline -ForegroundColor Cyan
    Write-Host ("─" * [Math]::Max(0, 58 - $title.Length)) -ForegroundColor DarkGray
}

function Ok($label, $detail) {
    $script:Pass++
    Write-Host "  [OK]   " -NoNewline -ForegroundColor Green
    Write-Host ("{0,-14}" -f $label) -NoNewline
    Write-Host $detail -ForegroundColor Gray
}

function Bad($label, $detail) {
    $script:Fail++
    Write-Host "  [FAIL] " -NoNewline -ForegroundColor Red
    Write-Host ("{0,-14}" -f $label) -NoNewline
    Write-Host $detail -ForegroundColor Yellow
}

function Warn($label, $detail) {
    Write-Host "  [WARN] " -NoNewline -ForegroundColor Yellow
    Write-Host ("{0,-14}" -f $label) -NoNewline
    Write-Host $detail -ForegroundColor Gray
}

function Invoke-Quiet([scriptblock]$cmd) {
    # 外部程序常把版本写到 stderr，这里统一合并捕获
    try { return (& $cmd 2>&1 | Out-String).Trim() } catch { return "" }
}

Write-Host ""
Write-Host "CanalMind 环境自检" -ForegroundColor White
Write-Host "  时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "  主机: $env:COMPUTERNAME"

# ---------------------------------------------------------------------------
Section "JDK（决策 C-11：需 JDK 21）"
# ---------------------------------------------------------------------------
$jdkPath = $null
$jdkVer = $null
foreach ($cand in $JdkCandidates) {
    if (-not $cand) { continue }
    $exe = Join-Path $cand "bin\java.exe"
    if (-not (Test-Path $exe)) { continue }
    $raw = Invoke-Quiet { & $exe -version }
    # java -version 输出形如: openjdk version "21.0.1" 2023-10-17 LTS
    # 注意 Java 8 及更早写作 "1.8.0_482"，此时主版本在第二段
    if ($raw -match 'version "([\d._]+)"') {
        $full = $Matches[1]
        $parts = $full.Split('.')
        $major = if ($parts[0] -eq '1' -and $parts.Count -gt 1) { [int]$parts[1] } else { [int]$parts[0] }
        if ($major -ge $RequiredJavaMajor) {
            $jdkPath = $cand
            $jdkVer = "Java $full"
            break
        }
        if (-not $jdkVer) { $jdkVer = "Java $full（主版本 $major，低于要求的 $RequiredJavaMajor）" }
    }
}
if ($jdkPath) {
    Ok "JDK" "$jdkVer  @ $jdkPath"
} else {
    Bad "JDK" "未找到 >= Java $RequiredJavaMajor 的 JDK（探测: $($JdkCandidates -join ', ')）"
}

# 提醒：JAVA_HOME 若仍指向旧版本，Maven / IDEA 可能用错
$jh = $env:JAVA_HOME
if ($jh) {
    $jhexe = Join-Path $jh "bin\java.exe"
    if (Test-Path $jhexe) {
        $raw = Invoke-Quiet { & $jhexe -version }
        if ($raw -match 'version "([\d._]+)"') {
            $full = $Matches[1]
            $parts = $full.Split('.')
            $jmajor = if ($parts[0] -eq '1' -and $parts.Count -gt 1) { [int]$parts[1] } else { [int]$parts[0] }
            if ($jmajor -lt $RequiredJavaMajor) {
                Warn "JAVA_HOME" "指向 Java $full（主版本 $jmajor），构建前需改为 JDK $RequiredJavaMajor"
            } else {
                Ok "JAVA_HOME" "Java $full  @ $jh"
            }
        }
    }
} else {
    Warn "JAVA_HOME" "未设置，Maven 可能找不到 JDK"
}

# ---------------------------------------------------------------------------
Section "Maven"
# ---------------------------------------------------------------------------
$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    $raw = Invoke-Quiet { & $mvn.Source -version }
    if ($raw -match 'Apache Maven ([\d.]+)') { Ok "Maven" "$($Matches[1])  @ $($mvn.Source)" }
    else { Ok "Maven" $mvn.Source }
} else {
    Bad "Maven" "未找到 mvn 命令"
}

# ---------------------------------------------------------------------------
Section "PostgreSQL（决策 C-12）"
# ---------------------------------------------------------------------------
$psql = Join-Path $PgHome "bin\psql.exe"
if (Test-Path $psql) {
    $raw = Invoke-Quiet { & $psql --version }
    Ok "客户端" $raw
} else {
    Bad "客户端" "未找到 $psql"
}

$portOpen = Test-NetConnection -ComputerName $PgHost -Port $PgPort -InformationLevel Quiet -WarningAction SilentlyContinue
if ($portOpen) {
    Ok "服务端口" "$PgHost`:$PgPort 可连接"
} else {
    Bad "服务端口" "$PgHost`:$PgPort 不可连接（服务未启动？）"
}

# 扩展检查（只在本机 PG 可连且免密时可行）
if ($portOpen -and (Test-Path $psql)) {
    $extDir = Join-Path $PgHome "share\extension"
    if (Test-Path $extDir) {
        if (Test-Path (Join-Path $extDir "vector.control")) {
            $vecFiles = Get-ChildItem $extDir -Filter "vector--*" -ErrorAction SilentlyContinue |
                        Sort-Object Name | Select-Object -Last 1
            Ok "pgvector" "已安装（$($vecFiles.Name)）"
        } else {
            Bad "pgvector" "未安装。需源码编译 0.8.x（PG18 不兼容 0.7 及以下，决策 C-15）"
        }
        if (Test-Path (Join-Path $extDir "btree_gist.control")) {
            Ok "btree_gist" "可用（tariff_rule 防重叠约束依赖）"
        } else {
            Bad "btree_gist" "不可用，tariff_no_overlap 约束将无法创建"
        }
    }
}

# ---------------------------------------------------------------------------
Section "Redis（决策 C-12）"
# ---------------------------------------------------------------------------
$redisCli = Join-Path $RedisHome "redis-cli.exe"
$redisOpen = Test-NetConnection -ComputerName 127.0.0.1 -Port $RedisPort -InformationLevel Quiet -WarningAction SilentlyContinue
if ($redisOpen -and (Test-Path $redisCli)) {
    $pong = Invoke-Quiet { & $redisCli -p $RedisPort PING }
    if ($pong -match "PONG") {
        $ver = Invoke-Quiet { & $redisCli -p $RedisPort INFO server }
        if ($ver -match "redis_version:([\d.]+)") { Ok "Redis" "$($Matches[1])  端口 $RedisPort 响应 PONG" }
        else { Ok "Redis" "端口 $RedisPort 响应 PONG" }
    } else {
        Bad "Redis" "端口开放但 PING 未返回 PONG"
    }
} elseif (Test-Path $redisCli) {
    Warn "Redis" "已安装但未启动。启动: & '$RedisHome\redis-server.exe' --port $RedisPort"
} else {
    Bad "Redis" "未找到 $redisCli"
}

# ---------------------------------------------------------------------------
Section "MSVC 编译器（pgvector 源码编译前置，决策 C-15）"
# ---------------------------------------------------------------------------
$vswhere = "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
    $vc = Invoke-Quiet { & $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath }
    if ($vc) {
        $cl = Get-ChildItem $vc -Recurse -Filter "cl.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cl) { Ok "MSVC" "cl.exe @ $($cl.FullName)" }
        else { Ok "MSVC" "VC++ 工具链已装 @ $vc" }
    } else {
        Warn "MSVC" "未找到 VC++ 工具链，pgvector 需改用预编译包或 Docker"
    }
} else {
    Warn "MSVC" "未找到 vswhere，无法确认 C++ 编译能力"
}

# ---------------------------------------------------------------------------
Section "Python（仿真数据 / 评测脚本）"
# ---------------------------------------------------------------------------
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py) {
    $raw = Invoke-Quiet { & $py.Source --version }
    Ok "Python" "$raw  @ $($py.Source)"
} else {
    Bad "Python" "未找到 python 命令"
}

# ---------------------------------------------------------------------------
Section "其他"
# ---------------------------------------------------------------------------
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    $raw = Invoke-Quiet { & docker --version }
    Ok "Docker" "$raw（本项目主路径不使用，决策 C-12）"
} else {
    Write-Host "  [----] " -NoNewline -ForegroundColor DarkGray
    Write-Host ("{0,-14}" -f "Docker") -NoNewline
    Write-Host "未安装（符合决策 C-12，本项目不依赖）" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------------------
Write-Host ""
Write-Host ("─" * 62) -ForegroundColor DarkGray
$color = if ($script:Fail -eq 0) { "Green" } else { "Red" }
Write-Host "  通过 $script:Pass 项，失败 $script:Fail 项" -ForegroundColor $color
Write-Host ""
if ($script:Fail -gt 0) {
    Write-Host "  请先解决上述 [FAIL] 项，再继续开发。" -ForegroundColor Yellow
    Write-Host "  环境决策依据见 docs/DECISIONS.md C-11 ~ C-15。" -ForegroundColor DarkGray
    exit 1
} else {
    Write-Host "  环境就绪。" -ForegroundColor Green
    exit 0
}
