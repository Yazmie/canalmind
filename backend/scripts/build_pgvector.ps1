<#
.SYNOPSIS
    在 Windows 上从源码编译安装 pgvector（PostgreSQL 18）

.DESCRIPTION
    对应决策 C-15：本项目使用本机 PostgreSQL 18.4，需 pgvector 0.8.x。
    ⚠️ PostgreSQL 18 **不兼容 pgvector 0.7 及以下**，必须用 0.8.x。

    为什么源码编译而不是 Docker：环境未安装 Docker（决策 C-12），
    且本机已具备 MSVC C++ 工具链（Visual Studio 2022）。

.PARAMETER PgHome
    PostgreSQL 安装目录，需包含 bin\ 与 share\extension\。

.PARAMETER SourceDir
    已下载并解压的 pgvector 源码目录（含 Makefile）。

.PARAMETER PgvectorVersion
    期望版本（仅用于校验提示）。必须是 0.8.x。

.EXAMPLE
    # 1) 先下载 pgvector 0.8.x 源码
    #    https://github.com/pgvector/pgvector/releases
    #    （项目沙箱无外网，需手动下载）
    # 2) 解压后执行
    pwsh -File build_pgvector.ps1 -SourceDir D:\src\pgvector-0.8.1

.NOTES
    编译产物需**管理员权限**才能写入 PostgreSQL 的 lib\ 与 share\extension\。
    本脚本会检测权限并给出提示，不会自动提权。
#>

[CmdletBinding()]
param(
    [string]$PgHome = "D:\APP\PostgreSQL\18",
    [Parameter(Mandatory = $true)]
    [string]$SourceDir,
    [string]$PgvectorVersion = "0.8"
)

$ErrorActionPreference = "Stop"

function Step($n, $msg) {
    Write-Host ""
    Write-Host "[$n] " -NoNewline -ForegroundColor Cyan
    Write-Host $msg -ForegroundColor White
}
function Good($msg) { Write-Host "    OK   " -NoNewline -ForegroundColor Green; Write-Host $msg }
function Bad($msg)  { Write-Host "    FAIL " -NoNewline -ForegroundColor Red;   Write-Host $msg }
function Info($msg) { Write-Host "    ..   " -NoNewline -ForegroundColor DarkGray; Write-Host $msg -ForegroundColor Gray }

Write-Host ""
Write-Host "pgvector 源码编译安装" -ForegroundColor White
Write-Host "  PostgreSQL : $PgHome"
Write-Host "  源码目录   : $SourceDir"
Write-Host "  目标版本   : $PgvectorVersion.x"

# ---------------------------------------------------------------------------
Step 1 "环境前置检查"
# ---------------------------------------------------------------------------

# PostgreSQL
$pgConfig = Join-Path $PgHome "bin\pg_config.exe"
if (-not (Test-Path $pgConfig)) {
    Bad "未找到 $pgConfig"
    exit 1
}
$pgVer = (& $pgConfig --version 2>&1 | Out-String).Trim()
Good $pgVer

# PG 主版本：18 需 pgvector 0.8+
$pgMajor = 0
if ($pgVer -match 'PostgreSQL\s+(\d+)') { $pgMajor = [int]$Matches[1] }
if ($pgMajor -ge 18) {
    if ($PgvectorVersion -notmatch '^0\.(8|9|[1-9]\d)') {
        Bad "PostgreSQL $pgMajor 需 pgvector 0.8.x 及以上，当前指定 $PgvectorVersion（决策 C-15）"
        exit 1
    }
    Good "版本兼容性检查通过（PG $pgMajor 需 pgvector 0.8+）"
}

# 源码目录
if (-not (Test-Path $SourceDir)) {
    Bad "源码目录不存在: $SourceDir"
    Info "请先从 https://github.com/pgvector/pgvector/releases 下载 0.8.x 源码并解压"
    exit 1
}
$makefile = Join-Path $SourceDir "Makefile"
if (-not (Test-Path $makefile)) {
    Bad "源码目录缺少 Makefile，确认是否下载了完整源码"
    exit 1
}
Good "源码目录有效"

# 源码版本提示
$control = Get-ChildItem $SourceDir -Filter "vector.control" -Recurse -ErrorAction SilentlyContinue |
           Select-Object -First 1
if ($control) {
    $ctrl = Get-Content $control.FullName -Raw
    if ($ctrl -match "default_version\s*=\s*'([^']+)'") {
        $srcVer = $Matches[1]
        Good "源码版本: $srcVer"
        if ($srcVer -match '^0\.([0-7])\.' -or $srcVer -match '^0\.([0-7])$') {
            Bad "源码版本 $srcVer 低于 0.8，**不兼容 PostgreSQL 18**（决策 C-15）"
            exit 1
        }
    }
}

# MSVC 工具链
$vswhere = "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
$vcPath = $null
if (Test-Path $vswhere) {
    $vcPath = (& $vswhere -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>&1 | Out-String).Trim()
}
if (-not $vcPath) {
    Bad "未找到 MSVC C++ 工具链"
    Info "请安装 Visual Studio 的「使用 C++ 的桌面开发」工作负载"
    exit 1
}
$vcvars = Join-Path $vcPath "VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path $vcvars)) {
    Bad "未找到 vcvars64.bat: $vcvars"
    exit 1
}
Good "MSVC: $vcPath"

# ---------------------------------------------------------------------------
Step 2 "写入权限检查"
# ---------------------------------------------------------------------------
$libDir = Join-Path $PgHome "lib"
$extDir = Join-Path $PgHome "share\extension"

$isAdmin = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($isAdmin) {
    Good "当前以管理员运行"
} else {
    # 非管理员也可能因 ACL 而可写，实测一下更可靠
    $probe = Join-Path $extDir ".write_probe"
    try {
        [System.IO.File]::WriteAllText($probe, "x")
        Remove-Item $probe -Force -ErrorAction SilentlyContinue
        Good "当前用户可写入扩展目录"
    } catch {
        Bad "无权限写入 $extDir"
        Info "请以**管理员身份**重新打开终端后重跑本脚本"
        exit 1
    }
}

# ---------------------------------------------------------------------------
Step 3 "编译"
# ---------------------------------------------------------------------------
Info "使用 nmake 编译（需在 vcvars64 环境下）"

# 生成一个批处理，在 vcvars64 环境下执行 nmake
$batPath = Join-Path $env:TEMP "build_pgvector_canalmind.bat"
$bat = @"
@echo off
call "$vcvars"
if errorlevel 1 exit /b 1
set "PGROOT=$PgHome"
cd /d "$SourceDir"
nmake /F Makefile.win clean
nmake /F Makefile.win
if errorlevel 1 exit /b 1
nmake /F Makefile.win install
if errorlevel 1 exit /b 1
exit /b 0
"@
Set-Content -Path $batPath -Value $bat -Encoding ASCII

& cmd.exe /c $batPath
if ($LASTEXITCODE -ne 0) {
    Bad "编译或安装失败（退出码 $LASTEXITCODE）"
    Info "常见原因：PostgreSQL 服务正在运行占用 DLL；或源码版本与 PG 主版本不匹配"
    exit 1
}
Good "编译并安装完成"

# ---------------------------------------------------------------------------
Step 4 "验证安装产物"
# ---------------------------------------------------------------------------
$installedControl = Join-Path $extDir "vector.control"
if (Test-Path $installedControl) {
    $c = Get-Content $installedControl -Raw
    if ($c -match "default_version\s*=\s*'([^']+)'") { Good "已安装扩展，default_version = $($Matches[1])" }
} else {
    Bad "扩展控制文件未就位: $installedControl"
    exit 1
}

$dll = Get-ChildItem $libDir -Filter "vector.dll" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($dll) { Good "DLL: $($dll.FullName)" } else { Bad "未找到 vector.dll" ; exit 1 }

# ---------------------------------------------------------------------------
Step 5 "在数据库中创建扩展"
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "  下一步：在目标数据库中启用扩展" -ForegroundColor Yellow
Write-Host ""
Write-Host "    psql -U postgres -d canalmind -c 'CREATE EXTENSION IF NOT EXISTS vector;'" -ForegroundColor Gray
Write-Host "    psql -U postgres -d canalmind -c 'SELECT extversion FROM pg_extension WHERE extname=''vector'';'" -ForegroundColor Gray
Write-Host ""
Write-Host "  验证向量运算（余弦距离 <=>，注意 PgVector 返回的是**距离**非相似度）：" -ForegroundColor Gray
Write-Host "    SELECT '[1,2,3]'::vector <=> '[4,5,6]'::vector;" -ForegroundColor Gray
Write-Host ""

Remove-Item $batPath -Force -ErrorAction SilentlyContinue
Write-Host "完成。" -ForegroundColor Green
Write-Host ""
