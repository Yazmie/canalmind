package com.canalmind.rag.retrieve;

import com.canalmind.rag.retrieve.LlmReranker.Completion;
import com.canalmind.rag.retrieve.LlmReranker.PromptCompleter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --dump-rerank} 诊断落盘的口径测试（全部用桩，**不发网络请求**）。
 *
 * <p>这个开关的价值全在"事后能不能拿它定性"，所以测试盯的不是"文件非空"，
 * 而是三件容易在实现里悄悄错掉的事：
 * <ol>
 *   <li>落盘的必须是<b>真实发出去的那份提示词与原始应答</b>（截断处能看出来）；</li>
 *   <li>失败路径（空正文这类<b>异常</b>）也必须有证据——真实那次降级恰恰走的是异常/半截路；</li>
 *   <li>它<b>不许改变行为</b>：开关前后返回值与降级原因必须逐字一致。</li>
 * </ol>
 */
class RerankDumpTest {

    private static final String LF = String.valueOf((char) 10);

    @TempDir
    Path tmp;

    @Test
    @DisplayName("落盘内容：提示词全文 + 原始应答全文 + finishReason + token 用量（含推理 token）")
    void dumpsPromptResponseAndEvidence() throws Exception {
        Path file = tmp.resolve("dump.txt");
        String prompt = "候选（共 10 条）：" + LF + "chunk-0 …";
        Completion completion = new Completion("{\"ranking\":[{\"chunk_id\":\"chunk-0\"", "length",
                9213, 8192, 7100);
        RerankDump dump = new RerankDump(file, evidence(completion), "模型 : deepseek-v4-flash");

        assertThat(dump.completeWithEvidence(prompt)).isSameAs(completion);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text)
                .as("提示词必须**全文**落盘：截断过的提示词会让判断方向整个偏掉")
                .contains(prompt)
                .as("原始应答必须全文落盘：要看得出它断在哪")
                .contains("{\"ranking\":[{\"chunk_id\":\"chunk-0\"")
                .contains("finishReason      : length")
                .contains("completionTokens  : 8192")
                .contains("reasoningTokens   : 7100")
                .as("文件头写清「这次是在什么条件下发生的」，否则事后判不出该调哪个旋钮")
                .contains("模型 : deepseek-v4-flash");
        assertThat(dump.callCount()).isEqualTo(1);
        assertThat(dump.path()).isEqualTo(file);
    }

    @Test
    @DisplayName("★ 失败路径也留证：空正文（异常）必须落盘，且异常原样抛出（不吞）")
    void dumpsFailuresAndRethrows() {
        Path file = tmp.resolve("dump-fail.txt");
        PromptCompleter failing = p -> {
            throw new LlmReranker.BlankCompletionException(
                    "重排模型返回了空正文（finish_reason=length，prompt 9213 tokens / completion 8192 tokens"
                            + "（其中推理 7100 tokens））");
        };
        RerankDump dump = new RerankDump(file, failing, "模型 : deepseek-v4-flash");

        assertThatThrownBy(() -> dump.completeWithEvidence("提示词原文"))
                .as("装饰器只旁听，不许把一次真实故障变成一次静默成功")
                .isInstanceOf(LlmReranker.BlankCompletionException.class);

        String text = readUtf8(file);
        assertThat(text)
                .as("异常消息里带着 finish_reason 与 token 用量，必须全文落盘；"
                        + "否则「被截断」这件事在这份证据里又消失了")
                .contains("重排模型返回了空正文")
                .contains("finish_reason=length")
                .contains("推理 7100");
        assertThat(dump.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 诊断开关不许改变行为：同一份桩，开关前后的降级原因必须逐字一致")
    void dumpDoesNotChangeOutcome() {
        List<RetrievalCandidate> candidates = List.of(RetrievalCandidate.of("chunk-0"),
                RetrievalCandidate.of("chunk-1"));
        Completion truncated = new Completion("{\"ranking\":[{\"chunk_id\":\"chunk-0\"", "length",
                9213, 8192, 7100);

        LlmReranker plain = new LlmReranker(evidence(truncated), snippets(),
                "deepseek-v4-flash").withMaxRetries(0);
        RerankDump dump = new RerankDump(tmp.resolve("dump-plain.txt"), evidence(truncated), "头");
        LlmReranker wrapped = new LlmReranker(dump, snippets(), "deepseek-v4-flash").withMaxRetries(0);

        RerankOutcome a = plain.rerank("一艘 3000 总吨的船要靠码头", candidates, 10);
        RerankOutcome b = wrapped.rerank("一艘 3000 总吨的船要靠码头", candidates, 10);

        assertThat(b.degradeReason())
                .as("开关只该影响「是否留证」，不该影响排序与降级原因")
                .isEqualTo(a.degradeReason());
        assertThat(b.candidates()).isEqualTo(a.candidates());
        assertThat(dump.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 路径不可写要在**构造时**失败：那时还没调过模型，失败不花钱")
    void failsAtConstructionOnBadPath() throws Exception {
        // 用一个"目录"当落盘路径：写它必然失败，且不需要依赖文件权限（沙箱里权限不可靠）
        Path asDirectory = Files.createDirectory(tmp.resolve("i-am-a-directory"));

        assertThatThrownBy(() -> new RerankDump(asDirectory, p -> null, "头"))
                .as("静默失败会让人以为「这次没有降级细节可看」，正是本开关要消灭的那种情况")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("诊断落盘失败");
    }

    @Test
    @DisplayName("每次构造都清空重建：一份文件对应一次运行，不会把两次运行混在一起")
    void truncatesExistingFileOnConstruction() throws Exception {
        Path file = tmp.resolve("dump-twice.txt");
        Files.writeString(file, "上一次运行的残留内容", StandardCharsets.UTF_8);

        new RerankDump(file, p -> null, "第二次运行的头");

        String text = readUtf8(file);
        assertThat(text).doesNotContain("上一次运行的残留内容").contains("第二次运行的头");
    }

    @Test
    @DisplayName("纯 LF、UTF-8 无 BOM：诊断文件也不该带平台换行与 BOM")
    void writesLfOnlyWithoutBom() throws Exception {
        Path file = tmp.resolve("dump-encoding.txt");
        RerankDump dump = new RerankDump(file, evidence(new Completion("正文" + LF + "第二行", "stop", 1, 2, null)), "头");
        dump.completeWithEvidence("提示词" + LF + "第二行");

        byte[] raw = Files.readAllBytes(file);
        assertThat(raw.length).isGreaterThan(3);
        assertThat(raw[0] & 0xFF).as("无 BOM").isNotEqualTo(0xEF);
        String text = new String(raw, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(String.valueOf((char) 13));
        assertThat(text).contains("提示词" + LF + "第二行");
    }


    /**
     * 返回固定 {@link Completion} 的桩。
     *
     * <p>必须用匿名类而不是 lambda：{@code PromptCompleter} 的抽象方法是
     * {@code complete(String) -> String}，而 {@code completeWithEvidence} 是 default 方法，
     * 故"返回 Completion 的 lambda"根本不是这个接口的实例。
     */
    private static PromptCompleter evidence(Completion completion) {
        return new PromptCompleter() {

            @Override
            public String complete(String prompt) {
                return completion.text();
            }

            @Override
            public Completion completeWithEvidence(String prompt) {
                return completion;
            }
        };
    }

    /**
     * 可用的片段提供者。
     *
     * <p>⛔ 别用 {@link LlmReranker.ChunkSnippetProvider#NONE}：它会让重排在
     * <b>调用模型之前</b>就降级（正文不可得），于是开关根本没机会旁听，
     * 测试会以"callCount 竟然是 0"的形式失败——这个坑本轮真的踩到过。
     */
    private static LlmReranker.ChunkSnippetProvider snippets() {
        return chunkId -> "正文（" + chunkId + "）";
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
