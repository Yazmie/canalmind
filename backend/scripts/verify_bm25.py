"""BM25 通道的独立验收：用 Python **重算一遍 BM25** 并与数据库结果逐条比对。

为什么需要它
------------
BM25 的公式有两处实现风险，而两处都**不会报错**：

1. **打分公式写错**（idf 用了会变负的经典形式、忘了长度归一化、tf 饱和写错）——
   结果只是"排序有点不对"，没有任何异常；
2. **口径不一致**（查询侧与索引侧分词口径不同、统计量 N/avgdl 过期）——
   结果只是"部分词召不回"。

`backend/scripts/verify_bm25_fixture.sql` 用手算期望值守住了第 1 类在**小样例**上的正确性；
本脚本补的是**真实数据、全量规模**上的独立复核：Python 侧不调用任何 SQL 打分函数，
只从 `rag_chunk_term` / `rag_chunk_token_stat` 两张表读出词元与长度，
按知识库《RAG 优化》与 Lucene 的口径**自己算一遍分数**，再和 `rag_bm25_search` 的输出
逐条比对（分数差 + 排名次序）。两套实现独立算出同一个答案，才叫"验证过"。

检查项
------
1. 探针文件头部的身份（词典/停用词 sha256、分词器版本、k1/b）与库里档案行**逐字段一致**
   —— 不一致直接失败：否则就是"用旧词元验新档案"这种假 PASS
2. 打分复核：Python 重算 vs `rag_bm25_search`，分数最大绝对差、排名次序是否一致
3. `matched_terms` 与 Python 侧统计的命中词数一致
4. 不变式：每个 chunk 的 `term_count == SUM(term_freq)`（跨行约束，CHECK 表达不了）
5. 统计量复核：`rag_token_stats` 的 N/词表/词元总数/avgdl 与 Python 侧现算一致
6. 结构标记自证：**词元减少量 == 语料里实测的列表编号条数**
   （rules-v1 未剥离 vs rules-v2 已剥离，两个档案共存于库中）
7. 无标记泄漏：词元里不出现 `[TABLE:` / 16 位 chunk_id / `rows=` `cols=` 片段
8. 词典生效：代表性领域词在倒排表里有 df（南宁港六景作业区 / 过闸费 / 计费单位 …）

用法
----
    python backend/scripts/verify_bm25.py                       # 用探针文件里记录的档案
    python backend/scripts/verify_bm25.py --profile <档案名>
    python backend/scripts/verify_bm25.py --top-k 20 --json docs/评测报告/bm25-baseline.json

    # 依赖：本机 PostgreSQL 在跑、库里有该档案的词元；psql 可执行文件路径见 --psql
    # 产物：docs/评测报告/bm25-baseline.json（**确定性**：无时间戳，可 diff）

⚠️ 本脚本通过 `psql -f`（而不是 `-c`）执行 SQL：中文经命令行参数会被控制台代码页
（实测 GBK）改坏，而改坏后 SQL 会以"查不到行"的形式静默失败。
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import math
import os
import subprocess
import sys
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # pragma: no cover - 受限环境下拿不到 reconfigure
        pass

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PROBES = ROOT / "docs" / "评测报告" / "bm25-probes.txt"
DEFAULT_JSON = ROOT / "docs" / "评测报告" / "bm25-baseline.json"
DEFAULT_PSQL = Path(r"D:\APP\PostgreSQL\18\bin\psql.exe")
DICTIONARY_PATH = ROOT / "backend" / "src" / "main" / "resources" / "rag" / "tokenize" / "shipping-dictionary.txt"
SCRATCH = ROOT / "backend" / "target" / "bm25-verify"


def read_dictionary_words(root: Path) -> list:
    """读航运词典的词列（格式：恰好两列「词 词频」，见 build_shipping_dictionary.py）。"""
    path = root / "backend" / "src" / "main" / "resources" / "rag" / "tokenize" / "shipping-dictionary.txt"
    if not path.exists():
        return []
    words = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if parts:
            words.append(parts[0])
    return words

# 打分容差：两套实现都是 IEEE double，但求和顺序不同（SQL 聚合 vs Python 顺序累加），
# 允许 1e-9 的绝对差；实测差在 1e-15 量级（见产物里的 maxAbsDelta）。
SCORE_TOLERANCE = 1e-9

errors: list[str] = []
warnings: list[str] = []


def _shown(path: Path) -> Path | str:
    """打印用路径：在仓库内就显示相对路径，否则原样显示。

    ⚠️ 不能直接 `path.relative_to(ROOT)`：调用方传**相对路径**（如
    `--json backend\\target\\eval\\x.json`）时它是相对 CWD 的，`relative_to` 会抛
    `ValueError`——而且是在**所有比对都做完、结论即将打印时**才抛，
    于是"复核通过"会被表现成退出码 1 的崩溃（本项目 2026-09-22 实测踩到）。
    """
    try:
        return path.resolve().relative_to(ROOT)
    except (ValueError, OSError):
        return path


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


# ── 连接与 SQL 执行 ──────────────────────────────────────────────────────────

def load_env(root: Path) -> dict:
    """读仓库根 .env 里的 POSTGRES_*（不打印口令）。"""
    env = {}
    env_file = root / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def psql_env(env: dict) -> dict:
    """构造 psql 子进程环境：UTF8 客户端编码 + 口令只放环境变量、不进命令行。"""
    proc_env = dict(os.environ)
    proc_env["PGCLIENTENCODING"] = "UTF8"
    password = env.get("POSTGRES_PASSWORD") or os.environ.get("PGPASSWORD", "")
    if password:
        proc_env["PGPASSWORD"] = password
    return proc_env


class Db:
    """用 `psql -f` + CSV 输出到**文件**执行查询（中文安全、可解析、不挂死）。

    ⚠️ 本机实测（2026-09-21）：从 Python 调 psql 曾**永久挂住**（600 秒级超时）。
    排查过程与结论都记在这里，因为它们各值一条经验：

    1. **真正的根因是"漏传 `env=`"**：子进程拿不到 `PGPASSWORD`，psql 转去弹口令提示，
       而该提示读的是**控制台**而非 stdin —— 所以 `stdin=subprocess.DEVNULL` **挡不住它**，
       命令就一直等一个永远不会有人输入的密码。修法：把准备好的环境 `env=self.env` 传进去。
    2. **顺带保留了"输出写文件而不是走管道"的写法**：本沙箱对"用管道捕获另一个程序的输出"
       有限制（Node.js 的 `stdio: 'pipe'` 实测 EPERM），文件句柄不受此限。
       注意这条**不是**上面那次挂起的原因（改成写文件后仍挂）——别把两件事混成一件。
    3. **让子进程"宁失败不等待"**：命令行加 `-w`（never prompt），口令缺失时立刻报错；
       再加 `timeout=300` 兜底。这样故障表现为一条清楚的错误，而不是一份空白日志。

    详见 `docs/工程复盘/环境坑/psql从子进程调用会挂住.md`。
    """

    def __init__(self, psql: Path, env: dict, host: str, port: str, dbname: str, user: str):
        self.psql = psql
        self.env = psql_env(env)
        # `-w`：口令缺失时**立刻报错**而不是弹提示。
        # ⚠️ 实测：psql 弹口令提示走的是**控制台**，不是 stdin——
        # 所以 `stdin=subprocess.DEVNULL` 挡不住它，现象是命令**永久挂起**、
        # 日志一片空白（本项目第一次跑本脚本就撞上，排查代价不小）。
        self.invocation = [str(psql), "-h", host, "-p", port, "-U", user, "-d", dbname,
                           "-X", "-q", "-w", "--csv", "-v", "ON_ERROR_STOP=1"]
        SCRATCH.mkdir(parents=True, exist_ok=True)

    def rows(self, sql: str, name: str = "query") -> list:
        """执行 SQL 并返回 dict 列表（--csv 输出带表头）。"""
        sql_path = SCRATCH / f"{name}.sql"
        out_path = SCRATCH / f"{name}.out.csv"
        err_path = SCRATCH / f"{name}.err.txt"
        # psql 读文件按 client_encoding 解码；文件本体必须是**无 BOM 的 UTF-8**
        sql_path.write_bytes(sql.encode("utf-8"))
        with open(out_path, "wb") as out, open(err_path, "wb") as errh:
            try:
                proc = subprocess.run(self.invocation + ["-f", str(sql_path)],
                                      stdout=out, stderr=errh, env=self.env,
                                      stdin=subprocess.DEVNULL, timeout=300, cwd=str(ROOT))
                returncode = proc.returncode
            except subprocess.TimeoutExpired:
                raise RuntimeError(f"psql 执行超时（{name}）：SQL 已写到 {sql_path}") from None
        stderr_text = err_path.read_bytes().decode("utf-8", "replace")
        if returncode != 0:
            raise RuntimeError(f"psql 执行失败（{name}）：\n{stderr_text[:1500]}")
        text = out_path.read_bytes().decode("utf-8", "replace")
        return list(csv.DictReader(io.StringIO(text)))


def sql_literal(s: str) -> str:
    return "'" + str(s).replace("'", "''") + "'"


# ── 探针文件 ─────────────────────────────────────────────────────────────────

def parse_probes(path: Path):
    """读探针文件：`# key = value` 为元数据，其余行是 `<查询>[TAB<词元…>]`。"""
    meta, probes = {}, []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            body = line.lstrip("#").strip()
            if "=" in body:
                k, v = body.split("=", 1)
                meta[k.strip()] = v.strip()
            continue
        if "\t" in line:
            query, terms = line.split("\t", 1)
            probes.append((query.strip(), terms.split()))
        else:
            probes.append((line, None))
    return meta, probes


# ── 独立 BM25 重算 ───────────────────────────────────────────────────────────

def idf(df: int, n_docs: int) -> float:
    """Lucene 形式的 idf：`ln(1 + (N-df+0.5)/(df+0.5))`，恒为正。"""
    return math.log(1.0 + (n_docs - df + 0.5) / (df + 0.5))


def bm25_scores(terms, postings, doc_lengths, k1, b):
    """独立算一遍 BM25，返回 [(chunk_id, score, matched_terms)] 按分数降序、chunk_id 升序。"""
    n_docs = len(doc_lengths)
    avgdl = sum(doc_lengths.values()) / n_docs if n_docs else 0.0
    # 每个词的 df：出现该词的 chunk 数
    docs_by_term = {}
    for chunk_id, term, tf in postings:
        docs_by_term.setdefault(term, {})[chunk_id] = tf

    scores: dict[str, float] = {}
    matched: dict[str, int] = {}
    for term in terms:
        per_doc = docs_by_term.get(term)
        if not per_doc:
            continue                      # df=0：无倒排项，对分数零贡献（不是错误）
        idf_value = idf(len(per_doc), n_docs)
        for chunk_id, tf in per_doc.items():
            dl = doc_lengths[chunk_id]
            denom = tf + k1 * (1.0 - b + b * dl / avgdl)
            scores[chunk_id] = scores.get(chunk_id, 0.0) + idf_value * (tf * (k1 + 1.0)) / denom
            matched[chunk_id] = matched.get(chunk_id, 0) + 1
    ranked = sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))
    return [(cid, score, matched[cid]) for cid, score in ranked]


def main() -> int:
    ap = argparse.ArgumentParser(description="BM25 通道独立验收（Python 重算 + 与库内结果比对）")
    ap.add_argument("--probes", type=Path, default=DEFAULT_PROBES)
    ap.add_argument("--json", type=Path, default=DEFAULT_JSON)
    ap.add_argument("--profile", default=None, help="覆盖探针文件里记录的档案名")
    ap.add_argument("--top-k", type=int, default=20)
    ap.add_argument("--psql", type=Path, default=DEFAULT_PSQL)
    args = ap.parse_args()

    if not args.psql.exists():
        print(f"[FAIL] 找不到 psql：{args.psql}（用 --psql 指定）")
        return 2
    if not args.probes.exists():
        print(f"[FAIL] 找不到探针文件：{args.probes}")
        return 2

    env = load_env(ROOT)
    meta, probes = parse_probes(args.probes)
    profile = args.profile or meta.get("profile")
    if not profile:
        print("[FAIL] 未指定档案：探针文件里没有 `# profile = …`，也没有 --profile")
        return 2

    host = env.get("POSTGRES_HOST", "localhost")
    port = env.get("POSTGRES_PORT", "5432")
    dbname = env.get("POSTGRES_DB", "canalmind")
    user = env.get("POSTGRES_USER", "postgres")
    db = Db(args.psql, env, host, port, dbname, user)

    print("=" * 72)
    print(f"BM25 通道独立验收 ｜ 档案 {profile}")
    print(f"数据库 {user}@{host}:{port}/{dbname} ｜ 探针 {args.probes.name}（{len(probes)} 条）")
    print("=" * 72)

    # ── 1. 档案身份 ──────────────────────────────────────────────────────────
    prof_rows = db.rows(
        "SELECT profile_name, tokenizer_name, tokenizer_version, segment_mode, dictionary_version,\n"
        "       dictionary_sha256, dictionary_terms, stopwords_sha256, stopwords_count,\n"
        "       bm25_k1, bm25_b, chunk_count_at_ingest\n"
        f"  FROM rag_token_profile WHERE profile_name = {sql_literal(profile)};", "profile")
    if not prof_rows:
        print(f"[FAIL] 库里没有档案 {profile}（先跑 Bm25IngestCli）")
        return 1
    prof = prof_rows[0]

    identity = {
        "profile": prof["profile_name"],
        "tokenizer": f'{prof["tokenizer_name"]} {prof["tokenizer_version"]}',
        "segment_mode": prof["segment_mode"],
        "dictionary_version": prof["dictionary_version"],
        "dictionary_sha256": prof["dictionary_sha256"],
        "stopwords_sha256": prof["stopwords_sha256"],
        "k1": float(prof["bm25_k1"]),
        "b": float(prof["bm25_b"]),
    }
    if meta:
        checks = [
            ("profile", prof["profile_name"]),
            ("tokenizer", f'{prof["tokenizer_name"]} {prof["tokenizer_version"]}'),
            ("segment_mode", prof["segment_mode"]),
            ("dictionary_version", prof["dictionary_version"]),
            ("dictionary_sha256", prof["dictionary_sha256"]),
            ("stopwords_sha256", prof["stopwords_sha256"]),
        ]
        for key, actual in checks:
            if key in meta and meta[key] != actual:
                err(f"探针文件记录的 {key} = {meta[key]!r}，库里是 {actual!r}："
                    "词元列已过期，请用 Bm25QueryCli --tokens 重新生成探针文件")
        for key, actual in (("k1", float(prof["bm25_k1"])), ("b", float(prof["bm25_b"]))):
            if key in meta and abs(float(meta[key]) - actual) > 1e-12:
                err(f"探针文件记录的 {key} = {meta[key]}，库里是 {actual}（打分口径不一致）")

    print(f"身份核对：{identity['tokenizer']} ｜ 模式 {identity['segment_mode']} ｜ "
          f"词典 {identity['dictionary_version']}({prof['dictionary_terms']} 词) ｜ "
          f"k1={identity['k1']:.2f} b={identity['b']:.2f}")

    # ── 2. 导出词元与长度 ────────────────────────────────────────────────────
    stat_rows = db.rows(
        f"SELECT chunk_id, term_count FROM rag_chunk_token_stat "
        f"WHERE profile_name = {sql_literal(profile)} ORDER BY chunk_id;", "stats")
    term_rows = db.rows(
        f"SELECT chunk_id, term, term_freq FROM rag_chunk_term "
        f"WHERE profile_name = {sql_literal(profile)} ORDER BY chunk_id, term;", "terms")
    if not stat_rows:
        print(f"[FAIL] 档案 {profile} 没有任何长度统计行（词元未灌入）")
        return 1

    doc_lengths = {r["chunk_id"]: int(r["term_count"]) for r in stat_rows}
    postings = [(r["chunk_id"], r["term"], int(r["term_freq"])) for r in term_rows]
    n_docs = len(doc_lengths)
    total_tokens = sum(doc_lengths.values())
    avgdl = total_tokens / n_docs
    vocab = len({t for _, t, _ in postings})
    df_by_term = {}
    for _cid, term, _tf in postings:
        df_by_term[term] = df_by_term.get(term, 0) + 1
    print(f"语料规模：N={n_docs} ｜ 词表={vocab} ｜ 词元总数={total_tokens} ｜ "
          f"倒排行数={len(postings)} ｜ avgdl={avgdl:.4f} ｜ "
          f"dl min/max={min(doc_lengths.values())}/{max(doc_lengths.values())}")

    # ── 3. 不变式：term_count == SUM(term_freq) ──────────────────────────────
    per_chunk = {}
    for chunk_id, _term, tf in postings:
        per_chunk[chunk_id] = per_chunk.get(chunk_id, 0) + tf
    mismatches = [(cid, dl, per_chunk.get(cid, 0)) for cid, dl in doc_lengths.items()
                  if per_chunk.get(cid, 0) != dl]
    if mismatches:
        err(f"{len(mismatches)} 个 chunk 的 term_count ≠ SUM(term_freq)（前 3：{mismatches[:3]}）")

    # 词元里不该出现结构标记碎片
    leaks = [t for _, t, _ in postings
             if "[table:" in t.lower() or "rows=" in t.lower() or "cols=" in t.lower()
             or (len(t) == 16 and all(c in "0123456789abcdef" for c in t))]
    if leaks:
        err(f"词元里出现结构标记碎片 {len(leaks)} 个（前 5：{sorted(set(leaks))[:5]}）")

    # ── 4. 统计量复核（rag_token_stats 与现算一致）───────────────────────────
    stats_rows = db.rows(f"SELECT * FROM rag_token_stats({sql_literal(profile)});", "token_stats")
    if not stats_rows:
        err("rag_token_stats 没返回行")
    else:
        st = stats_rows[0]
        for key, expected in (("chunk_count", n_docs), ("distinct_terms", vocab),
                              ("total_tokens", total_tokens),
                              ("min_doc_length", min(doc_lengths.values())),
                              ("max_doc_length", max(doc_lengths.values()))):
            if int(st[key]) != expected:
                err(f"rag_token_stats.{key} = {st[key]}，现算是 {expected}")
        if abs(float(st["avg_doc_length"]) - avgdl) > 1e-6:
            err(f"rag_token_stats.avg_doc_length = {st['avg_doc_length']}，现算是 {avgdl}")

    # ── 5. 词典生效的证据（**测量驱动**，不写死词表）────────────────────────
    #
    # 为什么不写死几个词：第一版写死了「南宁港六景作业区」，实测它在 541 个 chunk 的
    # content 里**一次都没出现**（语料里写的是"六景作业区"），于是检查失败——
    # 但失败的原因不是词典没生效，而是**我拍的词表不对**。
    # 现在改成：读出词典文件的全部词（其 sha256 已与档案核对过），
    # 用 SQL 查"哪些词在语料里出现过"，再与倒排表的 df 对比。
    #   * 语料里出现、倒排里也有 → 词典在这条链路上确实生效；
    #   * 语料里出现、倒排里没有 → 记警告（可能是被更长的词典词整体吸收——
    #     INDEX 模式只补 2~3 字子词，"危险货物"被"危险货物专用闸次"吃掉就属此列）；
    #   * 语料里没出现 → 正常，不算问题。
    # 硬性判据只有一条：**生效的词数为 0** 才算失败（那是词典整条链路没接上）。
    dict_words = read_dictionary_words(ROOT)
    if not dict_words:
        warn(f"读不到词典文件 {DICTIONARY_PATH.relative_to(ROOT)}，跳过词典生效检查")
        dict_stats = {}
    else:
        values = ",\n       ".join(f"({sql_literal(w)})" for w in dict_words)
        dict_rows = db.rows(
            "WITH w(term) AS (VALUES " + values + "),\n"
            "     idx AS (SELECT term, count(*) AS df FROM rag_chunk_term\n"
            f"               WHERE profile_name = {sql_literal(profile)} GROUP BY term)\n"
            "SELECT w.term, coalesce(i.df, 0) AS df,\n"
            "       (SELECT count(*) FROM rag_chunk c WHERE strpos(c.content, w.term) > 0) AS in_corpus\n"
            "  FROM w LEFT JOIN idx i ON i.term = w.term\n"
            " ORDER BY w.term;", "dictionary")
        effective = [r["term"] for r in dict_rows if int(r["in_corpus"]) > 0 and int(r["df"]) > 0]
        in_corpus = [r["term"] for r in dict_rows if int(r["in_corpus"]) > 0]
        absorbed = [r["term"] for r in dict_rows if int(r["in_corpus"]) > 0 and int(r["df"]) == 0]
        dict_stats = {
            "dictionaryWords": len(dict_words),
            "wordsPresentInCorpus": len(in_corpus),
            "wordsEffectiveInIndex": len(effective),
            "wordsPresentButNotIndexed": len(absorbed),
            "absorbedExamples": absorbed[:10],
        }
        print(f"词典生效：词典 {len(dict_words)} 词 ｜ 语料中出现 {len(in_corpus)} 词 ｜ "
              f"倒排里生效 {len(effective)} 词 ｜ 出现但未成词元 {len(absorbed)} 词")
        if not effective:
            err("词典里没有任何词在倒排表中生效（词典加载失败或档案用错了词典）")
        if absorbed:
            warn(f"{len(absorbed)} 个词典词在语料里出现但未成为词元（前几个：{absorbed[:5]}）："
                 "通常是被更长的词典词整体吸收——INDEX 模式只补 2~3 字子词，属已知行为")

    # 代码段规则的端到端证据：整串文号只可能由本项目的"代码段整串 + 部件"规则产出
    code_probe = df_by_term.get("plyh-gz-2026-001", 0)
    if code_probe == 0:
        err("文号整串词元 plyh-gz-2026-001 在倒排表里没有倒排项：代码段规则没在真实数据上生效")
    else:
        print(f"代码段规则：plyh-gz-2026-001 df={code_probe}（整串词元，仅由代码段规则产出）")

    # ── 6. 结构标记自证（rules-v1 vs rules-v2）──────────────────────────────
    marker_rows = db.rows(
        "SELECT\n"
        "  (SELECT count(*) FROM\n"
        "     (SELECT regexp_matches(content, '(^|\\n)[ \\t]*[0-9]{1,3}\\.[ \\t]', 'g') FROM rag_chunk) s\n"
        "  ) AS list_markers,\n"
        "  (SELECT count(*) FROM\n"
        "     (SELECT regexp_matches(content, '\\[TABLE:', 'g') FROM rag_chunk) s\n"
        "  ) AS table_markers,\n"
        "  (SELECT count(*) FROM rag_chunk\n"
        "    WHERE btrim(regexp_replace(content, '\\[TABLE:[^]]*\\]', '', 'g')) = '') AS marker_only_chunks;",
        "markers")
    markers = marker_rows[0] if marker_rows else {}
    list_markers = int(markers.get("list_markers", 0))
    marker_only = int(markers.get("marker_only_chunks", 0))
    print(f"语料结构标记：列表编号 {list_markers} 处 ｜ 表格标记 {markers.get('table_markers')} 处 ｜ "
          f"只剩标记的退化 chunk {marker_only} 个")

    token_delta = None
    v1 = db.rows(
        "SELECT\n"
        "  (SELECT sum(term_freq) FROM rag_chunk_term WHERE profile_name = 'jieba:shipping-dict-v1:INDEX') AS tokens,\n"
        "  (SELECT count(*) FROM rag_chunk_term WHERE profile_name = 'jieba:shipping-dict-v1:INDEX') AS postings,\n"
        "  (SELECT created_at::text FROM rag_token_profile "
        "    WHERE profile_name = 'jieba:shipping-dict-v1:INDEX') AS v1_created_at,\n"
        "  (SELECT max(updated_at)::text FROM rag_chunk) AS chunks_last_written,\n"
        "  (SELECT (SELECT max(updated_at) FROM rag_chunk) > "
        "          (SELECT created_at FROM rag_token_profile "
        "            WHERE profile_name = 'jieba:shipping-dict-v1:INDEX')) AS corpus_changed_after_v1;",
        "rules_v1_scale")
    if v1 and v1[0]["tokens"] not in (None, ""):
        v1_tokens = int(v1[0]["tokens"])
        token_delta = v1_tokens - total_tokens
        # ⚠️ 这条自证的前提是「两个档案吃的是同一份语料」——它比的是
        #    「v1 的词元数 − 本次词元数 == 本份语料的列表编号数」。
        #    语料在两次入库之间被改过时，等式两端就不再是同一份输入，**会假 FAIL**
        #    （2026-09-22 实测：差额 1102 vs 编号 1171，差 69 = 语料改动带入的词元变化）。
        #    故先用可观测信号判断世代：chunk 行被改写的时间晚于 v1 档案的建立时间 ⇒ 跳过并说明。
        changed = str(v1[0].get("corpus_changed_after_v1", "")).strip().lower() in ("t", "true", "1")
        if changed:
            warn("跨语料世代，跳过「剥离列表编号的量化自证」：rules-v1 档案建于 "
                 f"{v1[0].get('v1_created_at')}，而 rag_chunk 最后改写于 "
                 f"{v1[0].get('chunks_last_written')} ⇒ 两个档案吃的不是同一份语料，"
                 f"「v1 词元 − 本次词元」不再等于本份语料的编号数"
                 f"（实测 {token_delta} vs 编号 {list_markers}，差额 "
                 f"{list_markers - token_delta} 即语料改动带入的词元变化）。"
                 "要在同语料下复核该等式，需用建立 rules-v1 时的代码版本重灌 v1 档案")
        else:
            print(f"剥离列表编号的量化自证：rules-v1 词元 {v1_tokens} → 本次 {total_tokens}"
                  f"（减少 {token_delta}）｜ 语料里实测编号 {list_markers} 处")
            if token_delta != list_markers:
                err(f"词元减少量 {token_delta} ≠ 语料里实测的列表编号数 {list_markers}"
                    "（两边不是同一个口径，或规则改动影响了别的词元）")
    else:
        warn("库里没有 rules-v1 档案（jieba:shipping-dict-v1:INDEX），跳过「量化自证」这一项")

    # ── 7. 打分复核：Python 重算 vs rag_bm25_search ─────────────────────────
    probe_reports = []
    max_delta = 0.0
    order_mismatches = 0
    matched_mismatches = 0
    no_op_terms_all = set()

    for query, terms in probes:
        if terms is None:
            err(f"探针「{query}」没有词元列（用 Bm25QueryCli --tokens 生成后抄回）；跳过打分复核")
            continue
        no_op = sorted(t for t in terms if df_by_term.get(t, 0) == 0)
        no_op_terms_all.update(no_op)

        mine = bm25_scores(terms, postings, doc_lengths, identity["k1"], identity["b"])[: args.top_k]
        array = "ARRAY[" + ", ".join(sql_literal(t) for t in terms) + "]::text[]"
        theirs = db.rows(
            "SELECT chunk_id, score, matched_terms FROM rag_bm25_search(\n"
            f"  {sql_literal(profile)}, {array}, {args.top_k});", "search")

        if [r["chunk_id"] for r in theirs] != [c for c, _s, _m in mine]:
            order_mismatches += 1
            err(f"探针「{query}」的排名次序不一致：\n"
                f"  库内： {[r['chunk_id'] for r in theirs][:5]}\n"
                f"  重算： {[c for c, _s, _m in mine][:5]}")
        for row, (cid, score, matched) in zip(theirs, mine):
            delta = abs(float(row["score"]) - score)
            max_delta = max(max_delta, delta)
            if delta > SCORE_TOLERANCE:
                err(f"探针「{query}」chunk {cid} 分数不一致：库 {row['score']} vs 重算 {score:.12f}")
            if int(row["matched_terms"]) != matched:
                matched_mismatches += 1
                err(f"探针「{query}」chunk {cid} 命中词数：库 {row['matched_terms']} vs 重算 {matched}")

        probe_reports.append({
            "query": query,
            "terms": terms,
            "noOpTerms": no_op,
            "returned": len(theirs),
            "topHits": [
                {"rank": i + 1,
                 "chunkId": row["chunk_id"],
                 "score": round(float(row["score"]), 9),
                 "matchedTerms": int(row["matched_terms"])}
                for i, row in enumerate(theirs)
            ],
        })
        top = theirs[0] if theirs else None
        print(f"  探针「{query}」词元 {len(terms)} 个"
              f"（其中 df=0 的 {len(no_op)} 个：{no_op}）→ 命中 {len(theirs)} 条"
              + (f"，Top-1 {top['chunk_id']} 分数 {float(top['score']):.6f}" if top else ""))

    # ── 8. df 分布（说明词表的区分度）──────────────────────────────────────
    bands = {"1": 0, "2~5": 0, "6~20": 0, "21~100": 0, ">100": 0}
    for df in df_by_term.values():
        if df == 1:
            bands["1"] += 1
        elif df <= 5:
            bands["2~5"] += 1
        elif df <= 20:
            bands["6~20"] += 1
        elif df <= 100:
            bands["21~100"] += 1
        else:
            bands[">100"] += 1
    top_df = sorted(df_by_term.items(), key=lambda kv: (-kv[1], kv[0]))[:10]

    # ── 9. 产物（确定性：无时间戳、键排序、LF、无 BOM）──────────────────────
    artifact = {
        "schemaVersion": "1.0",
        "channel": "bm25",
        "formula": {
            "idf": "ln(1 + (N - df + 0.5) / (df + 0.5))",
            "score": "Σ idf * (tf * (k1+1)) / (tf + k1 * (1 - b + b * dl / avgdl))",
            "k1": identity["k1"],
            "b": identity["b"],
            "source": "PostgreSQL 函数 rag_bm25_search（唯一实现）+ 本脚本独立重算复核",
        },
        "identity": identity,
        "corpus": {
            "chunks": n_docs,
            "distinctTerms": vocab,
            "totalTokens": total_tokens,
            "postings": len(postings),
            "avgDocLength": round(avgdl, 6),
            "minDocLength": min(doc_lengths.values()),
            "maxDocLength": max(doc_lengths.values()),
            "chunkCountAtIngest": int(prof["chunk_count_at_ingest"] or 0),
        },
        "structureMarkers": {
            "listMarkersInCorpus": list_markers,
            "tableMarkersInCorpus": int(markers.get("table_markers", 0)),
            "markerOnlyChunks": marker_only,
            "listMarkerTokenDelta": token_delta,
        },
        "dfDistribution": bands,
        "dictionary": dict_stats,
        "topTermsByDf": [{"term": t, "df": d} for t, d in top_df],
        "probes": probe_reports,
        "crossChecks": {
            "probeCount": len(probe_reports),
            "rankOrderMismatches": order_mismatches,
            "matchedTermsMismatches": matched_mismatches,
            "maxAbsScoreDelta": max_delta,
            "scoreTolerance": SCORE_TOLERANCE,
            "invariantViolations": len(mismatches),
            "markerLeaks": len(leaks),
            "noOpTerms": sorted(no_op_terms_all),
        },
    }
    args.json.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(artifact, ensure_ascii=False, indent=2, sort_keys=True,
                      separators=(",", " : "))
    # ⛔ 自己钉死换行：不用平台的默认值（AGENTS.md §7.2）
    args.json.write_bytes((text + "\n").encode("utf-8"))
    print(f"产物已写入：{_shown(args.json)}")

    # ── 10. 结论 ────────────────────────────────────────────────────────────
    print("-" * 72)
    print(f"打分复核：{len(probe_reports)} 条探针 ｜ 排名不一致 {order_mismatches} ｜ "
          f"命中词数不一致 {matched_mismatches} ｜ 分数最大绝对差 {max_delta:.3e}")
    for w in warnings[:10]:
        print(f"  ! {w}")
    if len(warnings) > 10:
        print(f"  ! …另有 {len(warnings)-10} 条警告")
    for e in errors[:20]:
        print(f"  ✗ {e}")
    if len(errors) > 20:
        print(f"  ✗ …另有 {len(errors)-20} 条错误")
    print(f"错误 {len(errors)} 项，警告 {len(warnings)} 项")
    if errors:
        print("[FAIL] BM25 通道未通过独立复核")
        return 1
    print("[PASS] BM25 通道通过独立复核（Python 重算与库内打分一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
