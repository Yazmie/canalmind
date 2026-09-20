<#
.SYNOPSIS
    Reset PostgreSQL postgres user password (local dev)

.DESCRIPTION
    1) Backup data\pg_hba.conf
    2) Temporarily switch scram-sha-256 -> trust
    3) Restart PostgreSQL
    4) ALTER USER postgres PASSWORD
    5) Restore pg_hba.conf
    6) Restart PostgreSQL
    7) Verify with new password

    Must run as Administrator (data dir ACL restricts to Administrators).
#>

[CmdletBinding()]
param(
    [string]$PgHome = "D:\APP\PostgreSQL\18",

    # ⚠️ 口令**刻意不给默认值**：默认值会把真实开发库口令写进 git 历史，
    #    而本项目的约定是"口令只放 .env，.env 不入 git"（见 .gitignore 第 12 行）。
    #    调用时显式传入，例如：
    #      .\reset_pg_password.ps1 -NewPassword 'your-new-password'
    #    或从 .env 读取后传入：
    #      $pw = (Get-Content ..\..\.env | Select-String '^POSTGRES_PASSWORD=') -replace '^POSTGRES_PASSWORD=',''
    #      .\reset_pg_password.ps1 -NewPassword $pw
    [Parameter(Mandatory = $true)]
    [string]$NewPassword
)

$ErrorActionPreference = "Stop"

$hba   = Join-Path $PgHome "data\pg_hba.conf"
$bak   = Join-Path $PgHome "data\pg_hba.conf.bak"
$psql  = Join-Path $PgHome "bin\psql.exe"
$svc   = "postgresql-x64-18"

$isAdmin = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[FAIL] Must run as Administrator" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $hba)) { Write-Host "[FAIL] Not found: $hba" -ForegroundColor Red; exit 1 }

Write-Host "[1/7] Backup pg_hba.conf"
Copy-Item $hba $bak -Force
Write-Host "      -> $bak"

Write-Host "[2/7] Switch to trust auth"
$content = [System.IO.File]::ReadAllText($hba)
$trust = $content -replace 'scram-sha-256', 'trust'
[System.IO.File]::WriteAllText($hba, $trust, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[3/7] Restart PostgreSQL"
Restart-Service $svc
Start-Sleep -Seconds 4
$st = (Get-Service $svc).Status
Write-Host "      status: $st"
if ($st -ne 'Running') { Write-Host "[FAIL] service not running, restoring hba"; Copy-Item $bak $hba -Force; exit 1 }

Write-Host "[4/7] Set new password"
& $psql -h 127.0.0.1 -U postgres -d postgres -w -c "ALTER USER postgres PASSWORD '$NewPassword';"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] ALTER USER failed, restoring hba"
    Copy-Item $bak $hba -Force
    Restart-Service $svc
    exit 1
}
Write-Host "      new password: (set, not echoed)"

Write-Host "[5/7] Restore pg_hba.conf (back to scram-sha-256)"
Copy-Item $bak $hba -Force

Write-Host "[6/7] Restart PostgreSQL"
Restart-Service $svc
Start-Sleep -Seconds 4
$st = (Get-Service $svc).Status
Write-Host "      status: $st"

Write-Host "[7/7] Verify with new password"
$env:PGPASSWORD = $NewPassword
& $psql -h 127.0.0.1 -U postgres -d postgres -w -tAc "SELECT 'OK: connection successful';"
if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "[OK] Done. Connection info:" -ForegroundColor Green
    Write-Host "     host=127.0.0.1  port=5432  user=postgres"
    Write-Host "     password = the value you passed via -NewPassword (also ensure .env matches)"
} else {
    Write-Host "[FAIL] Verify failed. Diff $bak vs $hba" -ForegroundColor Red
}
