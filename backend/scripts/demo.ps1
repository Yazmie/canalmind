<#
.SYNOPSIS
  CanalMind 一键演示脚本。

.DESCRIPTION
  逐段演示本项目的可验证能力，并在每段打印「这一步花了什么、结论是什么」。

  ⚠️ 默认【零付费】：所有段都用本地计算或库内 SQL，不产生任何 API 花费。
     - 本地嵌入走 Ollama bge-m3，⛔ 不付费；
     - 路由 / Schema 门 / 检索 / 分层通过率均不调用 DeepSeek。
  ⚠️ -IncludePaid 会追加一次**真实的端到端问答**（走完整「路由→通道→作答」链路），
     这一步**会调用付费模型**，请先确认再做。

.PARAMETER IncludePaid
  追加一次真实端到端问答（付费）。默认关闭。

.PARAMETER WithTests
  追加全量单元/集成测试（约 40 秒，离线）。默认关闭。

.PARAMETER SkipDocCheck
  跳过文档自检（链接/结构/编码）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File backend\scripts\demo.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File backend\scripts\demo.ps1 -IncludePaid
#>
[CmdletBinding()]
param(
    [switch]$IncludePaid,
    [switch]$WithTests,
    [switch]$SkipDocCheck
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONUTF8 = '1'
$env:PGCLIENTENCODING = 'UTF8'

# ---------------------------------------------------------------------------
# 路径与工具解析
# ---------------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path      # backend\scripts
$BackendDir = Split-Path -Parent $ScriptDir                        # backend
$RepoRoot = Split-Path -Parent $BackendDir                         # 仓库根

function Resolve-Tool {
    param([string]$Name, [string[]]$Candidates)
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($c in $Candidates) { if (Test-Path $c) { return $c } }
    return $null
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    foreach ($cand in @('D:\JDKmove\ms-21.0.12.1', 'D:\JDKmove\JDK21')) {
        if (Test-Path (Join-Path $cand 'bin\java.exe')) { $env:JAVA_HOME = $cand; break }
    }
}
$JavaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
$PythonExe = Resolve-Tool -Name 'python' -Candidates @('D:\APP\Python\python.exe')
$MvnExe = Resolve-Tool -Name 'mvn' -Candidates @('D:\APP\apache-maven-3.9.12\bin\mvn.cmd')
$PsqlExe = Resolve-Tool -Name 'psql' -Candidates @('D:\APP\PostgreSQL\18\bin\psql.exe')
$script:Cp = $null

# ---------------------------------------------------------------------------
# 输出与状态
# ---------------------------------------------------------------------------
$script:Failures = @()
$script:Warnings = @()
$StepNo = 0

function Write-Head {
    param([string]$Title, [string]$Cost)
    $script:StepNo++
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor DarkCyan
    Write-Host ("[{0}] {1}   [{2}]" -f $script:StepNo, $Title, $Cost) -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor DarkCyan
}

function Write-Ok   { param([string]$m) Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Warn { param([string]$m) Write-Host "  [警告] $m" -ForegroundColor Yellow; $script:Warnings += $m }
function Write-Bad  { param([string]$m) Write-Host "  [失败] $m" -ForegroundColor Red;    $script:Failures += $m }
function Write-Note { param([string]$m) Write-Host "  $m" -ForegroundColor Gray }

<#
  运行外部命令。
  ⚠️ 退出码必须**紧接在调用之后**取——本项目已记录过 "$LASTEXITCODE 接在管道之后取会假绿"，
     故这里先存码、再打印输出。
#>
function Invoke-Step {
    param(
        [string]$Display,
        [string]$Exe,
        [string[]]$Arguments,
        [switch]$AllowFail
    )
    Write-Host "  > $Display" -ForegroundColor DarkGray
    $out = & $Exe @Arguments 2>&1
    $code = $LASTEXITCODE
    foreach ($line in $out) { Write-Host "    $line" }
    if ($code -ne 0 -and -not $AllowFail) {
        Write-Bad "$Display（退出码 $code）"
        return $false
    }
    return ($code -eq 0)
}

<#
  运行一个 Java CLI。
  ⚠️ 必须显式传 -Dstdout.encoding / -Dstderr.encoding：
     JDK 18+ 的 System.out 默认按**控制台代码页**编码，而本项目多数 CLI 的中文提示词很多
     ⇒ 在 GBK 控制台下会输出乱码（RAG 侧 CLI 因自带 CliConsole 才幸免）。
     只写 -Dfile.encoding=UTF-8 不够，那只管文件 IO 的默认字符集。
#>
function Invoke-Java {
    param(
        [string]$Display,
        [string]$MainClass,
        [string[]]$MainArgs,
        [switch]$AllowFail
    )
    $common = @(
        '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-cp', $script:Cp, $MainClass
    )
    return Invoke-Step -Display $Display -Exe $JavaExe -Arguments ($common + $MainArgs) -AllowFail:$AllowFail
}

# ---------------------------------------------------------------------------
# 开场
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host 'CanalMind（运河智答）演示' -ForegroundColor White
Write-Host "  仓库根   : $RepoRoot"
Write-Host "  JDK      : $(if ($JavaExe) { $JavaExe } else { '(未找到！)' })"
Write-Host "  Python   : $(if ($PythonExe) { $PythonExe } else { '(未找到)' })"
Write-Host "  Maven    : $(if ($MvnExe) { $MvnExe } else { '(未找到)' })"
Write-Host "  psql     : $(if ($PsqlExe) { $PsqlExe } else { '(未找到)' })"
Write-Host "  模式     : $(if ($IncludePaid) { '含付费段（-IncludePaid）' } else { '零付费（默认）' })"

$Missing = @()
foreach ($pair in @(@('Java', $JavaExe), @('Python', $PythonExe), @('Maven', $MvnExe))) {
    if (-not $pair[1]) { $Missing += $pair[0] }
}
if ($Missing.Count -gt 0) {
    Write-Host ''
    Write-Host "⛔ 缺少必需工具：$($Missing -join '、')。请参照 README「快速开始」配置后重跑。" -ForegroundColor Red
    exit 2
}

# ---------------------------------------------------------------------------
# 载入 .env 到进程环境（⚠️ 所有 *Cli 的 DB / Ollama 参数都从进程环境读）
# ---------------------------------------------------------------------------
$EnvFile = Join-Path $RepoRoot '.env'
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    Write-Host ''
    Write-Ok "已载入 .env 到进程环境（$(Split-Path -Leaf $EnvFile)）——CLI 的 DB/Ollama 参数由此读取"
} else {
    Write-Host ''
    Write-Warn "未找到 $EnvFile：将退回各 CLI 的默认值（可能连错库）"
}
if ($env:POSTGRES_PASSWORD) { $env:PGPASSWORD = $env:POSTGRES_PASSWORD }
$env:PGHOST = if ($env:POSTGRES_HOST) { $env:POSTGRES_HOST } else { '127.0.0.1' }
$env:PGPORT = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { '5432' }
$env:PGUSER = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'postgres' }
$env:PGDATABASE = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'canalmind' }

# ---------------------------------------------------------------------------
# 1) 环境与库内实况
# ---------------------------------------------------------------------------
Write-Head -Title '环境与库内实况' -Cost '零付费'
if (-not $PsqlExe) {
    Write-Warn '未找到 psql：跳过库内实况检查'
} else {
    Invoke-Step -Display 'PostgreSQL 版本' -Exe $PsqlExe -Arguments @('-t', '-A', '-c', 'SHOW server_version;') | Out-Null
    Invoke-Step -Display '已装扩展（需含 vector / btree_gist）' -Exe $PsqlExe `
        -Arguments @('-t', '-A', '-c', "SELECT extname||' '||extversion FROM pg_extension ORDER BY extname;") | Out-Null
    # ⚠️ 这里的 SQL 一律【纯 ASCII】：`psql -c` 传中文会被 GBK 控制台改坏
    #    （报 invalid byte sequence for encoding "UTF8"）。要中文标签就写 .sql 文件用 psql -f。
    Invoke-Step -Display '库内实况（语料块 / 向量 / 费用 / 费率 / 评测记录）' -Exe $PsqlExe -Arguments @(
        '-t', '-A', '-c',
        "SELECT 'rag_chunk='||(SELECT count(*) FROM rag_chunk)
             ||' vec_not_null='||(SELECT count(*) FROM rag_chunk WHERE embedding IS NOT NULL)
             ||' fee_record='||(SELECT count(*) FROM fee_record)
             ||' tariff_rule='||(SELECT count(*) FROM tariff_rule)
             ||' eval_record='||(SELECT count(*) FROM eval_record);"
    ) | Out-Null
}

$OllamaUrl = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { 'http://127.0.0.1:11434' }
try {
    $tags = Invoke-RestMethod -Uri "$OllamaUrl/api/tags" -TimeoutSec 10
    Write-Ok "Ollama 在线：$($tags.models.name -join ', ')"
    $model = if ($env:OLLAMA_EMBEDDING_MODEL) { $env:OLLAMA_EMBEDDING_MODEL } else { 'bge-m3:latest' }
    $body = @{ model = $model; input = 'canal' } | ConvertTo-Json -Compress
    $emb = Invoke-RestMethod -Uri "$OllamaUrl/api/embed" -Method Post -ContentType 'application/json' `
        -Body $body -TimeoutSec 60
    $dim = $emb.embeddings[0].Count
    if ($dim -eq 1024) { Write-Ok "嵌入维度实测 $dim（与库内 vector(1024) 一致）" }
    else { Write-Bad "嵌入维度 $dim 不等于 1024：与库内向量表不一致（C-22 硬约束）" }
} catch {
    Write-Bad "Ollama 不可用：$($_.Exception.Message)"
    Write-Note '→ 检索段无法演示；请先启动 Ollama 并确认已拉取 bge-m3'
}

# ---------------------------------------------------------------------------
# 2) 评测资产自检
# ---------------------------------------------------------------------------
Write-Head -Title '评测资产自检（语料↔库内费率 / Golden Set）' -Cost '零付费'
if (-not $PythonExe) {
    Write-Warn '未找到 python：跳过资产自检'
} else {
    Invoke-Step -Display 'python backend\scripts\verify_corpus_db_rates.py --selftest' -Exe $PythonExe `
        -Arguments @((Join-Path $BackendDir 'scripts\verify_corpus_db_rates.py'), '--selftest') | Out-Null
    Invoke-Step -Display 'python backend\scripts\verify_golden_set.py' -Exe $PythonExe `
        -Arguments @((Join-Path $BackendDir 'scripts\verify_golden_set.py')) | Out-Null
}

if (-not $SkipDocCheck -and $PythonExe) {
    Write-Head -Title '文档自检（链接可达性 / 结构 / 编码）' -Cost '零付费'
    Invoke-Step -Display 'python docs\工程复盘\check_links.py' -Exe $PythonExe `
        -Arguments @((Join-Path $RepoRoot 'docs\工程复盘\check_links.py')) | Out-Null
}

# ---------------------------------------------------------------------------
# 3) 构建（classpath）
# ---------------------------------------------------------------------------
Write-Head -Title '构建与 classpath 准备' -Cost '零付费'
Push-Location $BackendDir
try {
    $CpFile = Join-Path $BackendDir 'target\cp.txt'
    if (-not (Test-Path $CpFile)) {
        Invoke-Step -Display 'mvn dependency:build-classpath（生成 target\cp.txt）' -Exe $MvnExe -Arguments @(
            '-o', '-q',
            '-s', (Join-Path $RepoRoot '.proto\settings.xml'),
            '-gs', (Join-Path $RepoRoot '.proto\settings.xml'),
            "-Dmaven.repo.local=$(Join-Path $BackendDir '.m2repo')",
            '-Daether.enhancedLocalRepository.trackingFilename=_none.repositories',
            'dependency:build-classpath', '-Dmdep.outputFile=target\cp.txt'
        ) | Out-Null
    }
    if (Test-Path $CpFile) { Write-Ok 'classpath 清单就绪：target\cp.txt' }
    else {
        Write-Bad '未生成 target\cp.txt，后续 Java 段无法运行'
        Write-Host ''
        Write-Host '演示中止：缺少 classpath 清单。' -ForegroundColor Red
        exit 1
    }

    $Classes = Join-Path $BackendDir 'target\classes'
    if (-not (Test-Path (Join-Path $Classes 'com\canalmind\chat\ChatStreamController.class'))) {
        Invoke-Step -Display 'mvn compile（首次需要）' -Exe $MvnExe -Arguments @(
            '-o', '-q',
            '-s', (Join-Path $RepoRoot '.proto\settings.xml'),
            '-gs', (Join-Path $RepoRoot '.proto\settings.xml'),
            "-Dmaven.repo.local=$(Join-Path $BackendDir '.m2repo')",
            '-Daether.enhancedLocalRepository.trackingFilename=_none.repositories',
            'compile'
        ) | Out-Null
    }
    $script:Cp = 'target\classes;' + (Get-Content $CpFile -Raw).Trim()

    # -----------------------------------------------------------------------
    # 4) 意图路由
    # -----------------------------------------------------------------------
    Write-Head -Title '意图路由（规则优先 + 分类器兜底 + 复合拆分）' -Cost '零付费'
    Write-Note '对真实 Golden Set 逐题断言路由结果，不调用任何模型。'
    Invoke-Java -Display 'IntentRouterCli --selftest' `
        -MainClass 'com.canalmind.intent.IntentRouterCli' -MainArgs @('--selftest') | Out-Null

    # -----------------------------------------------------------------------
    # 5) 结构化通道零付费自检
    # -----------------------------------------------------------------------
    Write-Head -Title '结构化查询通道自检（Schema 契约 + 费用引擎）' -Cost '零付费'
    Write-Note '校验 5 个工具的 JSON Schema 契约与纯代码费用引擎的手算对照；不调用模型。'
    Write-Note '（日志里那三条 WARN 是自检刻意注入的模型失败，用于验证"重试 2 次后降级"。）'
    Invoke-Java -Display 'StructuredQueryCli --selftest' `
        -MainClass 'com.canalmind.structured.StructuredQueryCli' -MainArgs @('--selftest') | Out-Null

    # -----------------------------------------------------------------------
    # 6) 检索演示
    # -----------------------------------------------------------------------
    Write-Head -Title '文档检索通道演示（向量 / BM25 / 融合）' -Cost '零付费'
    Write-Note '嵌入在本地 Ollama 上算，⛔ 不产生 API 花费；本段一律 --no-rerank（重排要调付费模型）。'
    $EvalDir = Join-Path $BackendDir 'target\eval'
    if (-not (Test-Path $EvalDir)) { New-Item -ItemType Directory -Path $EvalDir -Force | Out-Null }
    # ⚠️ 中文查询一律走 --query-file：走 argv 会被 Windows 控制台代码页改坏
    $DemoQueries = Join-Path $EvalDir 'demo-queries.txt'
    $DemoQuestions = @(
        '过闸费是怎么计算的？',
        '危险货物运输需要提前多久申报？',
        '船舶吃水超过多少米需要限制过闸？'
    )
    [System.IO.File]::WriteAllText($DemoQueries, (($DemoQuestions -join "`n") + "`n"),
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok "演示问句已写入 $DemoQueries（$($DemoQuestions.Count) 条）"

    Invoke-Java -Display '向量通道（VectorQueryCli，top-3）' `
        -MainClass 'com.canalmind.rag.vector.VectorQueryCli' `
        -MainArgs @('--query-file', $DemoQueries, '--top-k', '3') | Out-Null

    # ⚠️ 库里同时存在 rules-v1 与 rules-v2 两个 BM25 档案 ⇒ 必须显式指定，CLI 拒绝猜
    $Bm25Profile = if ($env:BM25_PROFILE) { $env:BM25_PROFILE } else { 'jieba:shipping-dict-v1:rules-v2:INDEX' }
    Invoke-Java -Display "BM25 通道（Bm25QueryCli，top-3，档案 $Bm25Profile）" `
        -MainClass 'com.canalmind.rag.bm25.Bm25QueryCli' `
        -MainArgs @('--query-file', $DemoQueries, '--top-k', '3', '--profile', $Bm25Profile) -AllowFail | Out-Null

    Invoke-Java -Display '融合管线（RetrievalQueryCli --channels vector,bm25 --no-rerank，top-5）' `
        -MainClass 'com.canalmind.rag.retrieve.RetrievalQueryCli' `
        -MainArgs @(
            '--query-file', $DemoQueries, '--channels', 'vector,bm25', '--no-rerank',
            '--recall-top-k', '30', '--fusion-top-k', '30', '--top-k', '5',
            '--token-profile', $Bm25Profile,
            '--out', (Join-Path $EvalDir 'demo-retrieval.json')
        ) | Out-Null
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------
# 7) 分层通过率（取自库内 SQL）
# ---------------------------------------------------------------------------
Write-Head -Title '分层通过率（数据取自库内 eval_record，不是内存变量）' -Cost '零付费'
Push-Location $BackendDir
try {
    $RunId = if ($env:CANALMIND_DEMO_RUN_ID) { $env:CANALMIND_DEMO_RUN_ID } else { 'w4-2026-09-22-all6-artifacts-v2' }
    Write-Note "run_id = $RunId"
    Write-Note '⚠️ 该 run 覆盖 L1/L2/L3/L5/L6 五层（L4 由规则判分，不在这个 run 的落库范围内）。'
    Invoke-Java -Display "HarnessCli --report --run-id $RunId" `
        -MainClass 'com.canalmind.eval.harness.HarnessCli' `
        -MainArgs @('--report', '--run-id', $RunId) | Out-Null
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------
# 8) 可选：真实端到端问答（付费）
# ---------------------------------------------------------------------------
if ($IncludePaid) {
    Write-Head -Title '真实端到端问答（⚠️ 付费段）' -Cost '付费'
    Write-Note '用真实数据集的第一道 L3 题走完整「路由 → 通道 → 作答」链路。'
    Write-Warn '本段会调用 DeepSeek（付费）。预估调用次数为个位数（约 1 次生成，可能含 1 次工具抽取）。'
    if (-not $env:DEEPSEEK_API_KEY) {
        Write-Bad '未设置 DEEPSEEK_API_KEY（.env 里也没有）⇒ 跳过付费段'
    } else {
        Push-Location $BackendDir
        try {
            Invoke-Java -Display 'LayerRunCli --smoke' `
                -MainClass 'com.canalmind.eval.pipeline.LayerRunCli' -MainArgs @('--smoke') -AllowFail | Out-Null
        } finally {
            Pop-Location
        }
    }
} else {
    Write-Head -Title '真实端到端问答（已跳过）' -Cost '零付费'
    Write-Note '默认不调用付费模型。如需演示真实问答，请加 -IncludePaid：'
    Write-Note '  powershell -ExecutionPolicy Bypass -File backend\scripts\demo.ps1 -IncludePaid'
}

# ---------------------------------------------------------------------------
# 9) 可选：全量测试
# ---------------------------------------------------------------------------
if ($WithTests) {
    Write-Head -Title '全量测试（离线，约 40 秒）' -Cost '零付费'
    Push-Location $BackendDir
    try {
        Remove-Item -Recurse -Force (Join-Path $BackendDir 'target\classes'),
            (Join-Path $BackendDir 'target\test-classes') -ErrorAction SilentlyContinue
        $out = & $MvnExe -o `
            -s (Join-Path $RepoRoot '.proto\settings.xml') `
            -gs (Join-Path $RepoRoot '.proto\settings.xml') `
            "-Dmaven.repo.local=$(Join-Path $BackendDir '.m2repo')" `
            "-DargLine=-javaagent:$(Join-Path $BackendDir '.m2repo\org\mockito\mockito-core\5.17.0\mockito-core-5.17.0.jar')" `
            test 2>&1
        $code = $LASTEXITCODE
        $out | Select-String -Pattern 'Tests run:|BUILD' | ForEach-Object { Write-Host "    $($_.Line)" }
        if ($code -eq 0) { Write-Ok '全量测试通过' } else { Write-Bad "全量测试失败（退出码 $code）" }
    } finally {
        Pop-Location
    }
}

# ---------------------------------------------------------------------------
# 收尾
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host ('=' * 78) -ForegroundColor DarkCyan
if ($script:Failures.Count -eq 0) {
    Write-Host '演示结束：全部检查通过 ✅' -ForegroundColor Green
    if ($script:Warnings.Count -gt 0) {
        Write-Host "  （$($script:Warnings.Count) 条警告，见上）" -ForegroundColor Yellow
    }
    Write-Host '  技术指标出处：docs\评测报告\评测报告.md' -ForegroundColor Gray
    exit 0
} else {
    Write-Host "演示结束：$($script:Failures.Count) 项失败 ❌" -ForegroundColor Red
    foreach ($f in $script:Failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
