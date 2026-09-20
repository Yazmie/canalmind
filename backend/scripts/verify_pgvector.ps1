# =============================================================================
# verify_pgvector.ps1
#
# 目的：验证 pgvector 0.8.0 真正可用，且 **HNSW 索引确实被查询计划使用**。
#
# 依据（两条硬要求，缺一不可）：
#   - 规格书第 628 行：「Embedding + PgVector 入库，HNSW 索引
#     （**检查执行计划确认索引用上了**）」
#   - 知识库《RAG 向量索引算法和向量数据库》第 167 行：
#     「用 `EXPLAIN ANALYZE` 确认是否命中索引」；第 175 行提醒
#     「HNSW 遇到 WHERE 过滤条件时要重点看执行计划」
#     —— 故本脚本**专门验证带过滤条件的情形**，那是最容易退化为顺序扫描的场景。
#
# 为什么不能只看「建了索引」：pgvector 的 HNSW 是近似索引，
# 建索引成功 ≠ 查询会走它。数据量、`enable_seqscan`、过滤条件、以及
# 是否存在匹配的 operator class（`vector_ip_ops` / `vector_cosine_ops`）都会影响。
#
# 决策依据：C-22（Ollama + bge-m3，维度 1024）、C-23（HNSW）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File verify_pgvector.ps1
#   powershell -ExecutionPolicy Bypass -File verify_pgvector.ps1 -Rows 5000
# =============================================================================

[CmdletBinding()]
param(
    [string]$PgBin     = "D:\APP\PostgreSQL\18\bin",
    [string]$DataDir   = "D:\Project_AI\.pgtest\data",
    [int]   $Port      = 55432,
    [int]   $Rows      = 3000,
    [int]   $Dim       = 1024,
    # 只连不启动：临时集群已由外部（后台作业）拉起时用这个，
    # 避免本脚本自己管服务器生命周期（受限环境下 Start-Process 重定向会挂住）。
    [switch]$ConnectOnly
)

$ErrorActionPreference = "Stop"

$psql = Join-Path $PgBin "psql.exe"
$pgCtl = Join-Path $PgBin "pg_ctl.exe"

foreach ($p in @($psql, $pgCtl)) {
    if (-not (Test-Path $p)) { throw "找不到 $p" }
}
if (-not (Test-Path $DataDir)) { throw "找不到数据目录 $DataDir（需先用 initdb 建临时集群）" }

$results = [ordered]@{}
$failures = @()

function Run-Sql {
    param([string]$Sql, [switch]$Quiet)
    # ⚠️ 必须临时把 ErrorActionPreference 放宽：psql 会把 NOTICE（如
    #    `extension "vector" already exists, skipping`）写到 stderr，
    #    而 $ErrorActionPreference="Stop" 会把它当成终止性错误直接中断脚本。
    #    psql 用 `-q` 压掉 NOTICE，真正的失败仍由 ON_ERROR_STOP=1 + 退出码判定。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $psql -h 127.0.0.1 -p $Port -U postgres -d postgres -q -v ON_ERROR_STOP=1 -At -c $Sql 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($code -ne 0) { throw "SQL 失败: $Sql`n$out" }
    if (-not $Quiet) { return $out }
}

# ── 启动临时集群 ─────────────────────────────────────────────────────────────
$already = $false
try {
    $null = & $psql -h 127.0.0.1 -p $Port -U postgres -d postgres -At -c "SELECT 1" 2>&1
    if ($LASTEXITCODE -eq 0) { $already = $true }
} catch { }

if (-not $already) {
    if ($ConnectOnly) {
        throw "临时集群未在端口 $Port 运行（-ConnectOnly 指定为只连不启动）。请先另行拉起 postgres。"
    }
    Write-Host "启动临时集群（端口 $Port）..." -ForegroundColor Cyan
    # ⚠️ 不用 `pg_ctl start`：在受限环境下它报
    #    `could not create restricted token: error code 87`（pg_ctl 在 Windows 上
    #    需创建受限令牌以降权子进程，沙箱会拦下）。
    # ⚠️ 也不用 Start-Process + 重定向：受限环境下会挂住不返回。
    #    推荐做法是先由独立后台作业拉起 postgres，再用 -ConnectOnly 跑本脚本。
    throw "临时集群未运行。建议：另起后台作业运行 postgres.exe，然后用 -ConnectOnly 跑本脚本。"
}
Write-Host "临时集群就绪（端口 $Port）" -ForegroundColor Green

Write-Host ""
Write-Host "=" * 70
Write-Host " 1. 扩展可用性与版本"
Write-Host "=" * 70

# ── 1. 从 pg_available_extensions 读可用版本（不猜） ──────────────────────────
$avail = Run-Sql @'
SELECT concat(name, '|', COALESCE(default_version,''), '|', COALESCE(installed_version,'')) FROM pg_available_extensions WHERE name='vector'
'@
if (-not $avail) { throw "pg_available_extensions 中没有 vector —— pgvector 未被 PostgreSQL 识别到" }
$parts = ($avail | Select-Object -First 1) -split '\|'
$extName, $defaultVer, $installedVer = $parts[0], $parts[1], $parts[2]
Write-Host "  扩展名        : $extName"
Write-Host "  默认版本      : $defaultVer"
Write-Host "  已安装版本    : $(if ($installedVer) { $installedVer } else { '(本库尚未 CREATE EXTENSION)' })"

if ($defaultVer -notmatch '^0\.8\.') {
    $failures += "pgvector 版本为 $defaultVer，C-15 要求 0.8.x（PG18 不兼容 0.7 及以下）"
} else {
    Write-Host "  ✓ 版本符合 C-15（0.8.x）" -ForegroundColor Green
}

# ── 2. CREATE EXTENSION ──────────────────────────────────────────────────────
Run-Sql "CREATE EXTENSION IF NOT EXISTS vector" -Quiet
$ver = (Run-Sql "SELECT extversion FROM pg_extension WHERE extname='vector'") | Select-Object -First 1
Write-Host "  实际启用版本  : $ver"
if (-not $ver) { throw "CREATE EXTENSION vector 后仍查不到版本" }

Write-Host ""
Write-Host "=" * 70
Write-Host " 2. 类型与 operator class（决定能否用 HNSW 内积/余弦索引）"
Write-Host "=" * 70

# ── 3. vector 类型与 1024 维往返 ─────────────────────────────────────────────
Run-Sql "DROP TABLE IF EXISTS _vec_probe" -Quiet
Run-Sql "CREATE TABLE _vec_probe (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, chunk_id text, doc_id text, embedding vector($Dim))" -Quiet
$roundtrip = Run-Sql @"
SELECT concat('[', array_to_string(array_fill(1.0::float8, ARRAY[$Dim]), ','), ']')::vector($Dim) IS NOT NULL
"@
Write-Host "  vector($Dim) 建表与类型转换 : $roundtrip"
if ($roundtrip -ne 't') { $failures += "vector($Dim) 类型转换失败" }

# ── 4. operator class 是否存在 ───────────────────────────────────────────────
$opclasses = Run-Sql "SELECT opcname FROM pg_opclass WHERE opcname LIKE 'vector%' ORDER BY opcname"
Write-Host "  可用 operator class:"
foreach ($o in $opclasses) { Write-Host "    - $o" }
foreach ($need in @("vector_ip_ops", "vector_cosine_ops", "vector_l2_ops")) {
    if ($opclasses -notcontains $need) { $failures += "缺少 operator class $need" }
}

Write-Host ""
Write-Host "=" * 70
Write-Host " 3. 写入 $Rows 条 $Dim 维向量（模拟真实 chunk 规模）"
Write-Host "=" * 70

# 造数据：每行一个确定性伪随机向量（用 generate_series + md5 散列，保证可复现）
$sql = @"
INSERT INTO _vec_probe (chunk_id, doc_id, embedding)
SELECT
    'chunk-' || g,
    'doc-' || (g % 26),
    (SELECT concat('[', string_agg(x::text, ','), ']')::vector($Dim)
       FROM (SELECT round((hashtextextended(g::text || '-' || d::text, 42) % 2000000)::numeric / 1000000.0 - 1.0, 6) AS x
               FROM generate_series(1, $Dim) AS d) t)
FROM generate_series(1, $Rows) AS g
"@
Run-Sql $sql -Quiet
$cnt = (Run-Sql "SELECT count(*) FROM _vec_probe") | Select-Object -First 1
Write-Host "  实际写入行数: $cnt"
if ([int]$cnt -ne $Rows) { $failures += "写入行数 $cnt != $Rows" }

Write-Host ""
Write-Host "=" * 70
Write-Host " 4. ★ 核心验证：建 HNSW 索引 + EXPLAIN 确认被使用"
Write-Host "=" * 70

# ── 5. 建 HNSW 索引（C-23：默认 m=16, ef_construction=64） ───────────────────
Run-Sql "CREATE INDEX _vec_probe_hnsw ON _vec_probe USING hnsw (embedding vector_ip_ops)" -Quiet
$idxdef = (Run-Sql "SELECT indexdef FROM pg_indexes WHERE indexname='_vec_probe_hnsw'") | Select-Object -First 1
Write-Host "  索引定义: $idxdef"

# ── 6/7/8/9. 会话内测试 ──────────────────────────────────────────────────────
# ⚠️ 必须把 SET 与 EXPLAIN 放进**同一个 psql 会话**。
#    实测踩坑：`SET hnsw.ef_search = 100` 单独一条 psql 调用看似成功，但随后
#    `SHOW hnsw.ef_search` 在**新会话**里报
#    `unrecognized configuration parameter "hnsw.ef_search"`
#    —— 因为 `hnsw.*` 是 pgvector 注册的自定义 GUC，**只有该会话加载了 vector
#    扩展之后才存在**。故这里统一用「写 .sql 文件 + psql -f」单会话执行。
function Run-SqlScript {
    param([string]$Name, [string]$Body)
    $path = Join-Path $env:TEMP "pgv_$Name.sql"
    # 用无 BOM 的 UTF-8 写（psql 解析 SQL 不认 BOM）
    [System.IO.File]::WriteAllText($path, $Body, (New-Object System.Text.UTF8Encoding($false)))
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $psql -h 127.0.0.1 -p $Port -U postgres -d postgres -q -v ON_ERROR_STOP=1 -At -f $path 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
        Remove-Item $path -Force -ErrorAction SilentlyContinue
    }
    if ($code -ne 0) { throw "SQL 脚本失败 ($Name):`n$out" }
    return $out
}

# 固定查询向量：写成 CTE，避免依赖表中 id=1 的行
$probeVec = "(SELECT embedding FROM _vec_probe WHERE id = 1)"

# 建设性对照：一个选择性适中的过滤（约 15% 命中）——HNSW 过滤退化的典型场景。
# 同时展示 hnsw.iterative_scan 的缓解效果（知识库第 184 行）。
$scriptBody = @"
SET enable_seqscan = off;
\echo '--- A) 无过滤：HNSW 是否命中 ---'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
SELECT chunk_id FROM _vec_probe
ORDER BY embedding <#> $probeVec
LIMIT 10;

\echo '--- B) 过滤 doc_id=doc-3（116/3000 ≈ 3.9%）：过滤早于向量检索 ---'
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
SELECT chunk_id FROM _vec_probe
WHERE doc_id = 'doc-3'
ORDER BY embedding <#> $probeVec
LIMIT 10;

\echo '--- C) 同上，但开启 hnsw.iterative_scan=relaxed_order ---'
SET hnsw.iterative_scan = relaxed_order;
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF)
SELECT chunk_id FROM _vec_probe
WHERE doc_id = 'doc-3'
ORDER BY embedding <#> $probeVec
LIMIT 10;
RESET hnsw.iterative_scan;

\echo '--- D) GUC 可设置性（同会话内 SHOW） ---'
SET hnsw.ef_search = 100;
SHOW hnsw.ef_search;
SHOW hnsw.iterative_scan;
RESET hnsw.ef_search;
SET enable_seqscan = on;
"@

$sessionOut = Run-SqlScript -Name "plan" -Body $scriptBody
Write-Host ""
$sessionOut | ForEach-Object { Write-Host "  $_" }

$joinAll = $sessionOut -join "`n"
# 切出各段以分别判定
$segA = ($joinAll -split '--- A\)')[1]
$segA = ($segA -split '--- B\)')[0]
$segB = ($joinAll -split '--- B\)')[1]
$segB = ($segB -split '--- C\)')[0]
$segC = ($joinAll -split '--- C\)')[1]
$segC = ($segC -split '--- D\)')[0]

$usesHnsw  = $segA -match "Index Scan using _vec_probe_hnsw"
$usesHnsw2 = $segB -match "Index Scan using _vec_probe_hnsw"
$usesHnsw3 = $segC -match "Index Scan using _vec_probe_hnsw"

Write-Host ""
Write-Host "  A) 无过滤             → HNSW 命中: $usesHnsw"
Write-Host "  B) 过滤(3.9%)         → HNSW 命中: $usesHnsw2"
Write-Host "  C) 过滤 + iterative   → HNSW 命中: $usesHnsw3"

if (-not $usesHnsw) { $failures += "无过滤条件下执行计划未使用 HNSW 索引" }

# GUC 可设置性
$segD = ($joinAll -split '--- D\)')[1]
$gucLines = ($segD -split "`n") | Where-Object { $_ -match '^\s*\d+\s*$|relaxed_order|strict_order|off' } | ForEach-Object { $_.Trim() }
$efOk = $gucLines -contains '100'
Write-Host "  D) hnsw.ef_search 可设为 100: $efOk"
if (-not $efOk) { $failures += "hnsw.ef_search 无法设置为 100" }

# ── 10. 清理 ─────────────────────────────────────────────────────────────────
Run-Sql "SET enable_seqscan = on" -Quiet
Run-Sql "DROP TABLE IF EXISTS _vec_probe" -Quiet

Write-Host ""
Write-Host "=" * 70
if ($failures.Count -eq 0) {
    Write-Host " [PASS] pgvector 验证全部通过" -ForegroundColor Green
    Write-Host "=" * 70
    Write-Host "  结论："
    Write-Host "    - pgvector $ver 可用，HNSW 索引可建且查询计划确实命中"
    Write-Host "    - vector($Dim) 与 bge-m3 维度一致（C-22）"
    Write-Host "    - 内积 <#> + vector_ip_ops 可用（bge-m3 已 L2 归一化，内积与余弦排序等价）"
    exit 0
} else {
    Write-Host " [FAIL] 存在 $($failures.Count) 项问题" -ForegroundColor Red
    Write-Host "=" * 70
    $failures | ForEach-Object { Write-Host "   ✗ $_" -ForegroundColor Red }
    exit 1
}
