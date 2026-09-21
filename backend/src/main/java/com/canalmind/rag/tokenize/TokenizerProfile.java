package com.canalmind.rag.tokenize;

/**
 * 分词档案（第 2 周第 4 项）：<b>这批词元是谁、用哪版词典、按哪种切分模式产的</b>。
 *
 * <p>与 V3 的 embedding 档案（{@code rag_index_profile}）同构，理由也一样：
 * 让"检索结果为什么是这样"能追到具体版本，而不是靠人记得（知识库《RAG 优化》
 * 第 266–274 行：每一轮只改少量变量，并记录解析器 / Chunk / Embedding / 索引 /
 * Query Rewrite / Rerank / Prompt 的版本）。
 *
 * <p><b>字段全部进 {@code rag_token_profile}，并由 {@code rag_assert_token_profile}
 * 校验</b>：身份任一字段变化 → 拒绝就地重灌（必须先新建档案）。校验放在数据库侧，
 * 是因为"忘了新建档案"这件事<b>不会报错，只会让同一档案里混入两种口径的词元</b>，
 * 而查询侧无法分辨——分数照样算得出来，只是不再可比。
 *
 * <p>用 record 并**在紧凑构造器里钉死校验**（与 {@code CorpusDocument} 同一手法）：
 * 这些不变量一旦漏掉就是静默错误，放在构造器里比放在每个使用点各自检查更可靠。
 *
 * @param profileName      档案名，也是 {@code rag_chunk_term.profile_name} 的取值
 * @param tokenizerName    分词器名（如 {@code jieba-analysis}）
 * @param tokenizerVersion 分词器版本（与被依赖的 jar 版本一致）
 * @param segmentMode      jieba 切分模式：{@code INDEX} / {@code SEARCH}
 * @param dictionaryVersion 词典版本（人读标识）
 * @param dictionarySha256 词典文件字节的 sha256（机器校验用）
 * @param dictionaryTerms  词条数
 * @param stopwordsSha256  停用词表字节的 sha256
 * @param stopwordsCount   停用词条数
 * @param bm25K1           BM25 词频饱和参数 k1
 * @param bm25B            BM25 文档长度归一化参数 b
 */
public record TokenizerProfile(
        String profileName,
        String tokenizerName,
        String tokenizerVersion,
        String segmentMode,
        String dictionaryVersion,
        String dictionarySha256,
        int dictionaryTerms,
        String stopwordsSha256,
        int stopwordsCount,
        double bm25K1,
        double bm25B) {

    /** sha256 的形状（与 V4 迁移的 CHECK 约束一致，两处对不上就会在建档案时才失败）。 */
    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";

    public TokenizerProfile {
        requireNonBlank(profileName, "profileName");
        requireNonBlank(tokenizerName, "tokenizerName");
        requireNonBlank(tokenizerVersion, "tokenizerVersion");
        requireNonBlank(dictionaryVersion, "dictionaryVersion");
        if (!"INDEX".equals(segmentMode) && !"SEARCH".equals(segmentMode)) {
            throw new IllegalArgumentException(
                    "segmentMode 只能是 INDEX 或 SEARCH（jieba 的 SegMode），实际：" + segmentMode);
        }
        if (dictionarySha256 == null || !dictionarySha256.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("dictionarySha256 必须是 64 位小写十六进制，实际：" + dictionarySha256);
        }
        if (stopwordsSha256 == null || !stopwordsSha256.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("stopwordsSha256 必须是 64 位小写十六进制，实际：" + stopwordsSha256);
        }
        if (dictionaryTerms <= 0) {
            throw new IllegalArgumentException("dictionaryTerms 必须 > 0，实际：" + dictionaryTerms);
        }
        if (stopwordsCount < 0) {
            throw new IllegalArgumentException("stopwordsCount 必须 >= 0，实际：" + stopwordsCount);
        }
        // 与 V4 迁移的 rag_token_profile_k1_ck / _b_ck 保持一致
        if (!(bm25K1 > 0 && bm25K1 <= 100)) {
            throw new IllegalArgumentException("bm25K1 必须在 (0, 100]，实际：" + bm25K1);
        }
        if (!(bm25B >= 0 && bm25B <= 1)) {
            throw new IllegalArgumentException("bm25B 必须在 [0, 1]，实际：" + bm25B);
        }
    }

    /**
     * 档案名构造：{@code <分词器简称>:<词典版本>:rules-<规则版本>:<切分模式>}
     * （风格同 V3 的 {@code ollama:bge-m3:1024}）。
     *
     * <p>为什么把**规则版本**也放进名字：词元集合由"词典 + 本类规则"共同决定。
     * 词典有 sha256 兜底，规则只有一个人工常量；把规则版本写进名字，
     * 至少让"两个档案为什么不同"在 {@code rag_chunk_term.profile_name} 里一眼可见，
     * 也让 rules-v1 / rules-v2 能**共存对照**（实测就这么用过一次，见
     * {@code JiebaShippingTokenizer.RULES_VERSION} 的说明）。
     */
    public static String buildProfileName(String tokenizerName, String dictionaryVersion,
                                          String rulesVersion, String segmentMode) {
        requireNonBlank(tokenizerName, "tokenizerName");
        requireNonBlank(dictionaryVersion, "dictionaryVersion");
        requireNonBlank(rulesVersion, "rulesVersion");
        // 分词器名取连字符前的部分（jieba-analysis -> jieba），避免档案名里出现连字符造成误读
        String shortName = tokenizerName.split("-", 2)[0];
        return shortName + ":" + dictionaryVersion + ":rules-" + rulesVersion + ":" + segmentMode;
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }

    /** 一行摘要，供 CLI 与日志打印（口径统一，便于两次运行比对）。 */
    public String summary() {
        return String.format(
                "档案 %s ｜ 分词器 %s %s ｜ 模式 %s ｜ 词典 %s(%d 词, sha %s…) ｜ 停用词 %d 条(sha %s…) ｜ k1=%.2f b=%.2f",
                profileName, tokenizerName, tokenizerVersion, segmentMode,
                dictionaryVersion, dictionaryTerms, dictionarySha256.substring(0, 8),
                stopwordsCount, stopwordsSha256.substring(0, 8), bm25K1, bm25B);
    }
}
