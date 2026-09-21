package com.canalmind.chat.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个会话的上下文状态：**摘要 + 摘要覆盖边界 + 边界之后仍是原文的轮次**（决策 C-38）。
 *
 * <p><b>本类的全部约定就是这两条</b>：
 * <ul>
 *   <li>{@code index < summarizedUpTo} 的轮次**已由 {@code summary} 代表**，
 *       允许从 {@link #turns()} 里去掉；</li>
 *   <li>{@code index >= summarizedUpTo} 的轮次**必须保持原文**，
 *       它们的 {@code structuredParamsJson} 一个字都不许动。</li>
 * </ul>
 * 把"覆盖边界"做成数据的一部分（而不是藏在压缩器的局部变量里），
 * 折叠之后才可能**自证**没有吃掉近期参数——这正是 C-38③ 硬约束需要的形态。
 *
 * <p><b>为什么取原文轮次只有一个入口 {@link #rawTurns()}</b>：边界条件写在两处以上，
 * 就总有一条调用路径拿到错误的一侧。本项目已为此吃过一次亏：同一段文本在 LF 与 CRLF 下
 * 得到两个不同的下标（`AGENTS.md` §7.2 第 4 条记录了这个坑）。
 * 故 {@code index >= summarizedUpTo} 只在这里实现一次，所有调用方都用它。
 *
 * <p><b>为什么"没有摘要"用空串而不是 null</b>：{@link ContextAssembler} 每回合都要判
 * "要不要发摘要块"，null 与空串两种"没有"会让判空写法在各调用点分叉；
 * 本层统一用空串（与 {@code ToolResult} 把非成功态显式化的取向一致）。
 *
 * @param sessionId      会话 id；**不得为空**——空 id 会让所有匿名会话共用同一个桶，
 *                       上下文互相串味，且这种错误在单会话测试里完全看不出来
 * @param turns          轮次；**已被摘要代表的那些允许残留**，{@link #rawTurns()} 会滤掉它们
 * @param summary        已经生成过的摘要；没有时为空串
 * @param summarizedUpTo 摘要覆盖到（不含）第几轮；{@code 0} 表示尚未摘要过任何轮次
 */
public record SessionState(String sessionId, List<SessionTurn> turns, String summary, int summarizedUpTo) {

    public SessionState {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不得为空");
        }
        turns = turns == null ? List.of() : List.copyOf(turns);
        summary = summary == null ? "" : summary;
        if (summarizedUpTo < 0) {
            throw new IllegalArgumentException("summarizedUpTo 不得为负，收到：" + summarizedUpTo);
        }
    }

    /** 新会话的初始状态（无轮次、无摘要、边界为 0）。 */
    public static SessionState empty(String sessionId) {
        return new SessionState(sessionId, List.of(), "", 0);
    }

    /**
     * 追加一轮。
     *
     * <p>⚠️ 这里**不校验索引单调递增**：状态对象是无条件的快照，逐条校验会让
     * {@link SessionStore} 的任何一次反序列化（未来的 Redis 实现）都可能因历史数据而失败。
     * 真正需要拦的是"追加一轮会被摘要吃掉"的情形，那道闸在
     * {@link ContextAssembler#appendTurn(String, SessionTurn)} 上。
     */
    public SessionState withTurn(SessionTurn turn) {
        ArrayList<SessionTurn> next = new ArrayList<>(turns);
        next.add(turn);
        return new SessionState(sessionId, next, summary, summarizedUpTo);
    }

    /**
     * 换上新的摘要并推进覆盖边界；**已被代表（{@code index < summarizedUpTo}）的轮次在这里一并去掉**。
     *
     * <p>为什么把"删掉已折叠的原文"放在这里而不是让压缩器自己拼新列表：
     * 若只推进边界、不删原文，{@code turns} 里就同时存在"已被摘要代表"与"仍是原文"两类，
     * 于是"上下文里到底有哪些原始轮次"这件事取决于谁先谁后——又会分叉出第二个边界判断。
     *
     * @param summarizedUpTo **只允许前进**；回退会让已经折进摘要的轮次既不在原文、
     *                       也不在摘要的覆盖范围内（静默丢历史），故直接拒绝
     */
    public SessionState withSummary(String summary, int summarizedUpTo) {
        if (summarizedUpTo < this.summarizedUpTo) {
            throw new IllegalArgumentException("summarizedUpTo 只允许前进（当前 " + this.summarizedUpTo
                    + "，收到 " + summarizedUpTo + "）：回退会让已折叠的轮次既不在原文、也不在摘要覆盖范围内");
        }
        ArrayList<SessionTurn> kept = new ArrayList<>();
        for (SessionTurn turn : turns) {
            if (turn.index() >= summarizedUpTo) {
                kept.add(turn);
            }
        }
        return new SessionState(sessionId, kept, summary, summarizedUpTo);
    }

    /** 仍是原文、⛔ 不许被摘要替代的轮次（{@code index >= summarizedUpTo}），顺序与 {@link #turns()} 一致。 */
    public List<SessionTurn> rawTurns() {
        ArrayList<SessionTurn> raw = new ArrayList<>();
        for (SessionTurn turn : turns) {
            if (turn.index() >= summarizedUpTo) {
                raw.add(turn);
            }
        }
        return List.copyOf(raw);
    }

    /** 是否已有摘要（决定上下文里要不要出现摘要块）。 */
    public boolean hasSummary() {
        return !summary.isBlank();
    }
}
