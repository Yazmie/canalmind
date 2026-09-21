package com.canalmind.rag.retrieve;

import com.canalmind.rag.retrieve.LlmReranker.Completion;
import com.canalmind.rag.retrieve.LlmReranker.PromptCompleter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 重排调用的<b>诊断落盘</b>：把「实际发出的提示词全文 / 原始应答全文 / finishReason / token 用量」
 * 逐次追加到文件（{@code --dump-rerank <路径>} 或 {@code CANALMIND_DUMP_RERANK}）。
 *
 * <h2>为什么必须有它（2026-09-21 真实降级查不下去）</h2>
 * 真实链路里观察到一次降级：{@code --channels vector,bm25} + 重排、1 条查询，
 * 重排耗时 23.8 s 后报「输出无法解析为约定 JSON：Unexpected end-of-input」。
 * 但产物里只有一句降级原因，<b>看不到提示词到底多长、应答断在哪里、finishReason 是什么</b>——
 * 于是"是额度被思考吃光"与"是模型不听话"只能靠猜，而这两者的处置完全相反。
 * 该文件的用途就是把那次调用<b>原样留证</b>。
 *
 * <h2>纪律</h2>
 * <ul>
 *   <li><b>不吞异常</b>：装饰器只旁听，成功与失败都照原样继续抛。任何"为了让日志好看而吞掉"
 *       的写法都会把一次真实故障变成一次静默成功；</li>
 *   <li><b>不在构造时才失败</b>——正好相反：路径不可写要<b>在构造时立刻失败</b>。
 *       构造发生在<b>任何一次模型调用之前</b>，此时失败不花钱；等到第 30 题才报磁盘错，
 *       前面已经付过钱了；</li>
 *   <li><b>不做任何归一/截断</b>：提示词与应答按原样落盘（含可能的 CR）。
 *       它是<b>证据</b>，不是评测基线——所以也刻意<b>不保证逐字节可复现</b>（含耗时）。</li>
 * </ul>
 *
 * <h2>⛔ 它不是评测产物</h2>
 * 评测基线的三条纪律（UTF-8 无 BOM、纯 LF、无时间戳）这里只守前两条：
 * 耗时是定位"网关抖动"的必要信息，故保留 {@code elapsedMs}。
 * 谁要拿它当基线，先看这句警告。
 */
public final class RerankDump implements PromptCompleter {

    /** 换行用计算字符，不用转义字面量（AGENTS §7.2，编辑工具折不坏）。 */
    private static final String LF = String.valueOf((char) 10);

    private static final String SEP = "----------------------------------------";

    private final Path path;

    private final PromptCompleter delegate;

    /** 调用序号从 1 起（与产物里的 {@code rerankAttempts} 口径一致，便于对上）。 */
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * @param path     诊断文件路径；<b>每次构造都会清空重建</b>（一份产物对应一次运行）
     * @param delegate 真实的重排调用实现（本类只旁听）
     * @param header   文件头（由装配处给出：模型 / base-url / maxTokens / 超时 / 重试上限 /
     *                 四个 Top-K / 通道）。这些量决定了"这次调用是在什么条件下发生的"，
     *                 缺了它们，事后看提示词也判不出该调哪个旋钮
     */
    public RerankDump(Path path, PromptCompleter delegate, String header) {
        this.path = path;
        this.delegate = delegate;
        // 构造即建目录 + 清空 + 写文件头：路径不可写要在**花钱之前**就报出来
        write(new StringBuilder()
                .append("======== 重排诊断落盘（--dump-rerank）========").append(LF)
                .append(header == null ? "" : header + LF)
                .append("说明：每次重排调用追加一段；提示词与应答按原样落盘，不做归一或截断。").append(LF)
                .append("⚠️ 本文件是**诊断证据**，不是评测基线：含耗时，故不保证逐字节可复现。").append(LF)
                .append(LF)
                .toString(), false);
    }

    /** 仅供测试与自检：已经落盘了几次调用。 */
    public int callCount() {
        return calls.get();
    }

    /** 诊断文件路径（用于装配处打印"落盘到哪"，避免写了一个没人找得到的文件）。 */
    public Path path() {
        return path;
    }

    @Override
    public String complete(String prompt) throws Exception {
        long start = System.nanoTime();
        try {
            String text = delegate.complete(prompt);
            StringBuilder sb = startCall(prompt, start);
            // 只走 complete() 的实现拿不到 finishReason 与用量，这里如实写"未知"：
            // ⛔ 不猜成 stop / 不写 0——那会把"这一层没告诉我们"伪装成"确认没有截断"
            sb.append("原始应答(字符)    : ").append(text == null ? "null" : text.length()).append(LF)
                    .append("finishReason      : 未知（该实现只实现了 complete()，拿不到旁证）").append(LF)
                    .append(body("原始应答全文", text));
            endCall(sb);
            return text;
        } catch (Throwable t) {
            dumpFailure(prompt, start, t);
            throw t;
        }
    }

    @Override
    public Completion completeWithEvidence(String prompt) throws Exception {
        long start = System.nanoTime();
        try {
            Completion completion = delegate.completeWithEvidence(prompt);
            StringBuilder sb = startCall(prompt, start);
            String text = completion == null ? null : completion.text();
            sb.append("原始应答(字符)    : ").append(text == null ? "null" : text.length()).append(LF)
                    .append("finishReason      : ").append(completion == null ? "null" : completion.finishReason()).append(LF)
                    .append("promptTokens      : ").append(completion == null ? "null" : completion.promptTokens()).append(LF)
                    .append("completionTokens  : ").append(completion == null ? "null" : completion.completionTokens()).append(LF)
                    .append("reasoningTokens   : ").append(completion == null ? "null" : completion.reasoningTokens())
                    .append("（推理 token 与正文共享 maxTokens 预算；未知写 null）").append(LF)
                    .append(body("原始应答全文", text));
            endCall(sb);
            return completion;
        } catch (Throwable t) {
            dumpFailure(prompt, start, t);
            throw t;
        }
    }

    private void dumpFailure(String prompt, long start, Throwable t) {
        StringBuilder sb = startCall(prompt, start);
        sb.append("原始应答(字符)    : 无（调用本身失败，没有正文）").append(LF)
                .append(SEP).append(LF)
                .append("应答异常").append(LF)
                .append(SEP).append(LF)
                .append(t.getClass().getName()).append(": ").append(t.getMessage()).append(LF)
                // 空正文这一档把 finishReason 与用量写在异常消息里（见 BlankCompletionException），
                // 故这里必须把消息全文落盘——否则"被截断"这件事在这份证据里又会消失
                .append("⚠️ 上面这条消息里通常已带 finish_reason 与 token 用量，请看全文。").append(LF);
        endCall(sb);
    }

    private StringBuilder startCall(String prompt, long start) {
        int n = calls.incrementAndGet();
        // ⚠️ 提示词**全文**必须在这里落盘。第一版漏了它（只写了字符数），
        //    结果"实际发出去的是什么"这件事照样看不见——而那正是本开关存在的理由。
        //    这个漏写是被 RerankDumpTest 逮住的（断言"提示词必须全文落盘"）。
        return new StringBuilder()
                .append("======== 重排调用 第 ").append(n).append(" 次 ========").append(LF)
                .append("耗时(ms)          : ")
                .append((System.nanoTime() - start) / 1_000_000L).append(LF)
                .append("提示词(字符)      : ").append(prompt == null ? "null" : prompt.length()).append(LF)
                .append(body("提示词全文（实际发出去的就是这份，未做任何截断或归一）", prompt));
    }

    private void endCall(StringBuilder sb) {
        sb.append("======== 本次调用结束 ========").append(LF).append(LF);
        write(sb.toString(), true);
    }

    private static String body(String title, String text) {
        return SEP + LF + title + LF + SEP + LF + (text == null ? "null" + LF : text + LF);
    }

    /**
     * 落盘。{@code append=false} 时<b>清空重建</b>（每次构造一份新文件）。
     *
     * <p>用 {@link Files#write} 明确指定 {@link StandardCharsets#UTF_8}（无 BOM），
     * 换行由本类的 {@code LF} 常量自己拼——把编码与换行的决定权留在本类，
     * 不交给平台默认值（AGENTS §7.2）。
     */
    private void write(String text, boolean append) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (append) {
                Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            // 响亮失败：诊断开关坏掉却继续跑，等于让人以为"这次没有降级细节可看"
            throw new IllegalStateException(
                    "诊断落盘失败（" + path + "）：" + e.getMessage()
                            + "。⚠️ 该开关的用途是留下真实调用的证据，静默失败会让整次排查失去依据，"
                            + "故此处直接失败；如不想落盘，请去掉 --dump-rerank 或清空 "
                            + RerankDump.ENV_NAME + "。", e);
        }
    }

    /** 环境变量形式的开关（命令行 {@code --dump-rerank} 优先）。 */
    public static final String ENV_NAME = "CANALMIND_DUMP_RERANK";
}
