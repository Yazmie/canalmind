package com.canalmind.chat.session;

import java.util.List;
import java.util.Objects;

/**
 * 把「更早的轮次」折进摘要并推进覆盖边界——**纯逻辑**：不发请求、不碰存储、不读时钟。
 *
 * <p><b>硬约束（决策 C-38③）：可折叠的下标范围只允许是"最后 {@code keepRecentTurns} 轮之前"，
 * 且边界之后的轮次既不删也不重排。</b>
 * 该约束的样本是 {@code docs/评测报告/golden-set-v3.json} 的三条第二轮题：
 * {@code L4-008}（第 1762 行 {@code min_gross_tonnage=1520}）、
 * {@code L4-019}（第 2157 行 {@code date_from=2026-11-26}）、
 * {@code L4-023}（第 2297 行 {@code tonnage=2774}）——它们第二轮的正确答案**必须取自第一轮的真实返回值**，
 * 而"更早的轮次"恰好就是第一轮。滑动窗口单独使用时，这三条会被**恰好打掉**，
 * 且症状伪装成"模型不会做指代改写"（与 `docs/工程复盘/检索工程/分档TopK不能只调一个数.md`
 * 记录的"失败被伪装"同类）。
 *
 * <p><b>为什么 {@code keepRecentTurns < 1} 抛异常而不是"按 1 处理"</b>：静默降级会让调用方
 * 以为自己配的是 0（"近期一轮也不保留"）却得到保留一轮的行为，两者相差一条
 * C-38③ 的硬约束——这类"配置说了不算"的开关本项目踩过一次
 * （`docs/工程复盘/数据一致性/保留集纪律依赖的开关其实是坏的/`）。
 *
 * <p><b>为什么按"轮次"折叠而不是按 token 裁剪</b>：C-38② 已把阈值定为可注入配置，
 * 且 C-38 的证据部分写明「本周数据集只有 2 轮样本、没有 3 轮以上、没有超窗样本」
 * ⇒ 任何"压缩率""压缩后提升 X"都会是无出处的数字（`AGENTS.md` §6 第 1 条）。
 * 按轮次折叠是**能被逐条钉死**的形态（哪几轮被折、边界走到哪儿都能断言）；
 * 按 token 裁剪则必须靠一次真实调用才能自证。
 */
public final class ContextCompactor {

    private final ContextSummarizer summarizer;

    public ContextCompactor(ContextSummarizer summarizer) {
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer 不得为 null");
    }

    /**
     * 折叠更早的轮次。
     *
     * @param state           当前状态；判定"仍是原文"的轮次用的是 {@link SessionState#rawTurns()}
     * @param keepRecentTurns **必须 ≥ 1**：最近多少轮的原文（含其 {@code structuredParamsJson}）不许动
     * @return 新状态；**无可折叠的更早轮次时原样返回 {@code state}**（同一实例）。
     *         调用方据此判断"压缩到底有没有发生"——比另设一个 boolean 更不容易与实际状态脱节
     * @throws IllegalStateException {@code keepRecentTurns < 1}（配置错误），
     *                               或摘要器返回空摘要（⛔ 折叠后原文会被丢弃，空摘要等于丢历史）
     * @throws Exception             摘要器抛出的任何异常，**原样上抛**：
     *                               本类不做兜底，因为"失败后要不要保留原文"是调用方的决定
     *                               （{@link ContextAssembler} 的选择是保留全部原文并记进 note）
     */
    public SessionState compact(SessionState state, int keepRecentTurns) throws Exception {
        Objects.requireNonNull(state, "state 不得为 null");
        if (keepRecentTurns < 1) {
            throw new IllegalStateException("keepRecentTurns 必须 >= 1，收到 " + keepRecentTurns
                    + "：它保证最近若干轮的 structuredParamsJson 逐字留在上下文里；"
                    + "取 0 会让 L4-008 / L4-019 / L4-023 这类「第二轮要用第一轮返回值」的题失去唯一数据来源"
                    + "（决策 C-38③）");
        }

        List<SessionTurn> raw = state.rawTurns();
        int foldCount = raw.size() - keepRecentTurns;
        if (foldCount <= 0) {
            return state;
        }

        List<SessionTurn> toCompact = List.copyOf(raw.subList(0, foldCount));
        String merged = summarizer.summarize(state.summary(), toCompact);
        if (merged == null || merged.isBlank()) {
            throw new IllegalStateException("摘要器返回了空摘要（待折叠 " + toCompact.size()
                    + " 轮）：折叠后这些轮次的原文会被丢弃，⛔ 宁可不压缩也不能把轮次折没");
        }
        // 边界取"最后一个被折叠轮次的 index + 1"：轮次可能不连续（未来按会话分片的实现会有空洞），
        // 用最大 index 推进才能保证边界之上的每一个轮次都确定属于"仍要保留原文"的一侧。
        int summarizedUpTo = toCompact.get(toCompact.size() - 1).index() + 1;
        return state.withSummary(merged, summarizedUpTo);
    }
}
