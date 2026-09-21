package com.canalmind.rag.tokenize;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 查询侧的分词档案校验闸门——<b>{@code EmbeddingModelGuard} 在关键词通道上的对应物</b>。
 *
 * <p><b>它挡的是什么</b>：词元由"词典 A + 模式 A"产出，查询却用"词典 B + 模式 B"切词。
 * 此时检索<b>不会报错</b>：照样返回一批结果，分数照样算得出来，
 * 只是<b>部分词永远召不回</b>（新词典切出来的词元在库里根本没有倒排项），
 * 表现为"召回莫名其妙变低"。这和 C-22 的 embedding 模型不一致是同一类问题：
 * 排序依据已经变了，但没有任何技术错误提示（知识库《RAG 知识库文档如何更新》
 * 第 33 行称这类问题为"整个排序基础已经坏了"）。
 *
 * <p>为什么做成独立小类（抄 {@code EmbeddingModelGuard} 的理由）：
 * 校验必须在<b>每一条</b>检索路径上执行。散着写，早晚有一条新入口忘记校验，
 * 而忘记校验不会有任何提示。集中成一个对象，新入口只要拿到它就自动受约束。
 *
 * <h2>比对的字段，以及刻意不比对的字段</h2>
 *
 * <p>比对：分词器名、分词器版本、切分模式、<b>词典 sha256</b>、<b>停用词 sha256</b>。
 * 用 sha256 而不是版本号，是因为版本号靠人记得改（V4 迁移文件头已记录该理由）。
 * 词条数/停用词条数不再单独比对——它们是文件内容的函数，sha 相同则必然相同。
 *
 * <p>刻意<b>不</b>比对 BM25 的 k1/b：它们不是"分词口径"，而是打分参数，
 * 且查询侧一律从档案行读（{@code Bm25Searcher.profileFromDb()}），
 * 不存在"本地默认值与库里不一致"的路径。若将来出现本地传参的用法，
 * 应在这里补一条比对，而不是各自在调用点判断。
 */
public final class TokenizerProfileGuard {

    private final TokenizerProfile indexed;
    private final TokenizerProfile querying;

    /**
     * @param indexed  索引侧档案（来自 {@code rag_token_profile}，即数据库里的<b>事实</b>）
     * @param querying 查询侧档案（来自当前加载的分词器与词典文件）
     */
    public TokenizerProfileGuard(TokenizerProfile indexed, TokenizerProfile querying) {
        this.indexed = indexed;
        this.querying = querying;
    }

    /** 是否一致（一致才允许检索）。 */
    public boolean isConsistent() {
        return describeMismatch(indexed, querying).isEmpty();
    }

    /**
     * 一致性校验；不一致则抛异常拒绝查询。
     *
     * <p>刻意<b>拒绝</b>而不是"警告后继续"：继续下去会得到一份看起来正常、
     * 实际部分召不回的结果，比报错危险得多。
     */
    public void verify() throws TokenizerProfileMismatchException {
        if (indexed == null) {
            throw new TokenizerProfileMismatchException(
                    "索引侧分词档案缺失（rag_token_profile 里没有该档案）。"
                            + "无法确认词元由哪版词典/哪种模式产出，拒绝查询。"
                            + "请先运行 Bm25IngestCli 建档案并灌词元。");
        }
        String mismatch = describeMismatch(indexed, querying);
        if (!mismatch.isEmpty()) {
            throw new TokenizerProfileMismatchException(
                    "索引侧分词档案与查询侧口径不一致，拒绝查询（不降级）：" + mismatch
                            + "。｜索引侧：" + indexed.summary()
                            + "｜查询侧：" + querying.summary()
                            + "。处理方式：新建档案名并全量重灌词元（Bm25IngestCli），"
                            + "不要就地改档案——数据库守卫 {@code rag_assert_token_profile} 同样会拒绝。");
        }
    }

    /**
     * 列出不一致的字段（一致时返回空串）。
     *
     * <p>做成静态纯函数是为了让"差异描述"能被单测直接断言——
     * 否则要断言它就必须造一个数据库连接（本项目无 Docker、无测试库，见 C-12）。
     */
    public static String describeMismatch(TokenizerProfile indexed, TokenizerProfile querying) {
        if (indexed == null || querying == null) {
            return "档案缺失";
        }
        List<String> diffs = new ArrayList<>();
        addIfDiffers(diffs, "分词器", indexed.tokenizerName(), querying.tokenizerName());
        addIfDiffers(diffs, "分词器版本", indexed.tokenizerVersion(), querying.tokenizerVersion());
        addIfDiffers(diffs, "切分模式", indexed.segmentMode(), querying.segmentMode());
        addIfDiffers(diffs, "词典 sha256", indexed.dictionarySha256(), querying.dictionarySha256());
        addIfDiffers(diffs, "停用词表 sha256", indexed.stopwordsSha256(), querying.stopwordsSha256());
        return String.join("；", diffs);
    }

    private static void addIfDiffers(List<String> diffs, String label, String indexed, String querying) {
        if (!Objects.equals(indexed, querying)) {
            diffs.add(label + "（索引 " + indexed + " ≠ 查询 " + querying + "）");
        }
    }

    /** 档案不一致时抛出。属受检异常：调用方必须显式处理，不能默默吞掉。 */
    public static class TokenizerProfileMismatchException extends Exception {
        public TokenizerProfileMismatchException(String message) {
            super(message);
        }
    }
}
