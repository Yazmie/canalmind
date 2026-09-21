# D3 判决臂：纯向量 + LLM 重排（消融实验「有无 Rerank」在**纯向量**上的对照组）
#
# 背景（决策 C-30）：混合检索「暂不启用」，但当时**缺一个对照臂**——
#   既有的四路对照里，"开了重排"的那一路是 `vector,bm25`（RRF+重排），
#   于是**无法判断重排的增益是否依赖融合**。本脚本补上 `vector` + 重排这一路，
#   与既有的 `vector` + 无重排**只差一个变量**（符合规格书 §6.3 的"每轮只改少量变量"）。
#
# ⚠️ 成本：**真发 40 次 LLM 请求**（L2 全 40 条，每条 1 次重排）。
#    重试上限 2，故最坏情况约 80 次；实测历史为 40 次里 1 次降级。
#
# 用法：在 backend/ 下执行
#   & .\scripts\run_d3_arm.ps1

param(
    # 跳过付费运行，只用已有产物重算指标与对照（零成本，便于复核与改文档）
    [switch]$SkipPaidRun
)

$ErrorActionPreference = "Stop"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "D:\JDKmove\ms-21.0.12.1" }
$env:PYTHONIOENCODING = "utf-8"
# 与既有第四路保持一致（代码默认 8192，见 检索段-混合与重排.md §4 第 8 条）
$env:CANALMIND_RERANK_MAX_TOKENS = "16384"

# .env → 进程环境（不打印口令）
Get-Content "..\.env" -Encoding UTF8 |
    Where-Object { $_ -match '^\s*[A-Z_]+\s*=' -and $_ -notmatch '^\s*#' } |
    ForEach-Object { $kv = $_ -split '=', 2; [Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process') }

$cp   = "target\classes;" + (Get-Content "target\cp.txt" -Raw).Trim()
$java = "$env:JAVA_HOME\bin\java.exe"
$py   = "D:\APP\Python\python.exe"
$cli  = "com.canalmind.rag.retrieve.RetrievalQueryCli"
$qfile = "target\eval\l2-queries-v2.txt"
$outDir = "target\eval\item5"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$out = "$outDir\corpus0921-vec-rerank.json"

# 与既有三路**完全相同**的 k 参数；唯一差别是**不带 --no-rerank**（即开启重排）
$argv = @("-cp", $cp, $cli, "--query-file", $qfile, "--out", $out,
          "--channels", "vector", "--rerank-retry", "2",
          "--recall-top-k", "30", "--fusion-top-k", "30", "--rerank-top-n", "30", "--top-k", "30")

if ($SkipPaidRun) {
    # 零成本复核路径：产物已在则直接复用它，不调 LLM
    if (-not (Test-Path $out)) { Write-Host "⛔ -SkipPaidRun 但产物不存在：$out"; exit 2 }
    Write-Host "===== 跳过付费运行（-SkipPaidRun），复用已有产物：$out ====="
    $code = 0
} else {
    Write-Host "===== D3 判决臂：纯向量 + LLM 重排（真发 40 次 LLM 请求）====="
    Write-Host "命令：java $($argv -join ' ')"
    $t0 = Get-Date
    & $java @argv 2>&1 | Select-Object -Last 12 | ForEach-Object { "   " + $_ }
    $code = $LASTEXITCODE
    Write-Host ("退出码 $code ｜ 耗时 {0:N1} 秒" -f ((Get-Date) - $t0).TotalSeconds)
}
if ($code -ne 0) { Write-Host "⛔ 运行失败，先解决再谈指标"; exit $code }

Write-Host "`n===== 指标（严格族与可答族分列，不得混用）====="
& $py "..\backend\scripts\eval_retrieval.py" --hits $out --k 1,3,5,8,10,20,30 --json "$outDir\metrics-corpus0921-vec-rerank.json"

Write-Host "`n===== 与既有各路对照（只读既有产物，不再花钱）====="
# ⚠️ `compare_runs.py --run` 要的是**指标产物**（`eval_retrieval.py --json` 的输出），
#    ⛔ **不是 hits 产物**。实测踩到：误传 hits 会报「缺少键 label」，且**只在最后一步失败**——
#    前面 40 次付费调用已经花完了。故这里一律指向 `metrics-*.json`。
$runs = @()
foreach ($pair in @(
    @("纯向量(无重排)", "$outDir\metrics-corpus0921-vec.json"),
    @("纯向量+重排",    "$outDir\metrics-corpus0921-vec-rerank.json"),
    @("RRF(无重排)",    "$outDir\metrics-corpus0921-rrf.json"),
    @("纯BM25(无重排)", "$outDir\metrics-corpus0921-bm25.json")
)) {
    if (Test-Path $pair[1]) { $runs += "$($pair[0])=$($pair[1])" }
    else { Write-Host "   ⚠️ 缺产物，跳过：$($pair[0]) → $($pair[1])" }
}
if ($runs.Count -ge 2) {
    $a = @()
    foreach ($r in $runs) { $a += "--run"; $a += $r }
    & $py "..\backend\scripts\compare_runs.py" @a --ks 1,5,10,30 --json "$outDir\compare-d3-arm.json"
}

# ⚠️ 必须用**单引号** here-string `@'...'@`：双引号版会把不设防的 `` ` `` 当转义符，
#    实测把文档里的 v2-rrf-rerank.json 吃成 2-rrf-rerank.json、rerankDegraded 吃成 erankDegraded
#    （与 AGENTS.md §7.2「反引号是转义符」同一条陷阱）。
Write-Host @'

===== 决策读数（D3 判决臂的意义）=====
  □ 重排是否有效：比较「纯向量(无重排) vs 纯向量+重排」——**同版语料、只差重排一个变量**
  □ 混合是否有效：比较「纯向量(无重排) vs RRF(无重排)」——**同版语料、只差融合一个变量**
  □ ⚠️ **不得**把 v2-rrf-rerank.json（**改前语料**的旧产物）与本表并列解读增量；
     若要比较"重排层面融合是否还有收益"，须**另花 40 次调用**在新语料上重跑 RRF+重排
  □ ⚠️ 严格族（gold，下界）与可答族（含等价块）**必须并列**，不得只报高的那个
  □ 检查产物里的 rerankDegraded：**0 次降级**才是干净结论（本次实测 0）
'@
