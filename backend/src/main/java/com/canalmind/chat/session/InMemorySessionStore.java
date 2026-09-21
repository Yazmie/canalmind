package com.canalmind.chat.session;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内会话存储：{@link ConcurrentHashMap} + **有界** + **逐出最近最少触碰（LRU）**。
 *
 * <p><b>为什么必须有界</b>：会话是典型的"按 key 无限增长"内存泄漏面——
 * 每次请求都可能带一个新 {@code sessionId}（压测、爬虫、前端每次刷新都新生成 id），
 * 无界 Map 会一直涨到 OOM，而 OOM 与本模块要解决的问题（多轮上下文）在现象上毫无关联，
 * 排查成本极高。故上限做构造参数（默认 {@link #DEFAULT_MAX_SESSIONS}）。
 *
 * <p><b>为什么逐出策略是"最近最少触碰"而不是"最早创建"（FIFO）</b>：
 * 正在被使用的长会话必须比"来过一次就再也不来"的短会话活得久。
 * 若按创建时间逐出，一个聊了 20 轮的用户会因为他"开始得早"而被清掉，
 * 下一句突然丢失上文——**那恰恰是本模块要消灭的现象**。
 *
 * <p><b>触碰 = {@link #load} 或 {@link #save}</b>：{@link ContextAssembler} 每回合都 load，
 * 故"正在被使用的会话"会被不断推到最新。
 *
 * <p><b>并发语义（如实说明边界，⛔ 不夸大）</b>：
 * <ul>
 *   <li>单次读写是线程安全的（Map 为 ConcurrentHashMap，状态对象不可变）；</li>
 *   <li>容量收敛是**尽力而为**的：并发下某次逐出后可能暂时略超上限，下一次 {@code save}
 *       会继续收敛（测试里的做法是并发结束后再 save 一次再断言，而不是假装它时时精确）；</li>
 *   <li>⛔ **"读-改-写"不是原子的**：{@link ContextAssembler#appendTurn} 是
 *       load → withTurn → save，同一会话的并发追加会丢轮次。当前单机单会话满足串行前提；
 *       换成 Redis 实现时必须用原子操作解决——这正是 C-38④ 留接口的意义所在。</li>
 * </ul>
 */
public final class InMemorySessionStore implements SessionStore {

    /** 默认会话上限（见类注释：这个数只用于"不会无限涨"，⛔ 不是业务参数）。 */
    public static final int DEFAULT_MAX_SESSIONS = 200;

    /** 一条会话槽：状态本身不可变，"触碰时间"是唯一会变的部分。 */
    private record Slot(SessionState state, long lastTouched) {
    }

    private final int maxSessions;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong();

    /** 用默认上限构造。 */
    public InMemorySessionStore() {
        this(DEFAULT_MAX_SESSIONS);
    }

    /**
     * @param maxSessions 会话上限，**必须 ≥ 1**（⛔ 0 会让每一次 save 都立刻把自己逐出，
     *                    表现为"上下文永远为空"——这种配置错误必须当场报错，不能静默）
     */
    public InMemorySessionStore(int maxSessions) {
        if (maxSessions < 1) {
            throw new IllegalArgumentException("maxSessions 必须 >= 1，收到：" + maxSessions);
        }
        this.maxSessions = maxSessions;
    }

    /** 构造时配置的上限（供自证与测试读取）。 */
    public int maxSessions() {
        return maxSessions;
    }

    @Override
    public SessionState load(String sessionId) {
        requireSessionId(sessionId);
        Slot slot = slots.get(sessionId);
        if (slot == null) {
            return SessionState.empty(sessionId);
        }
        slots.put(sessionId, new Slot(slot.state(), clock.incrementAndGet()));
        return slot.state();
    }

    @Override
    public void save(SessionState state) {
        Objects.requireNonNull(state, "state 不得为 null");
        slots.put(state.sessionId(), new Slot(state, clock.incrementAndGet()));
        evictWhileOverCapacity();
    }

    @Override
    public void clear(String sessionId) {
        requireSessionId(sessionId);
        slots.remove(sessionId);
    }

    @Override
    public int sessionCount() {
        return slots.size();
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不得为空");
        }
    }

    /**
     * 逐出直到不超过上限。
     *
     * <p>两个刻意的细节：
     * <ol>
     *   <li>删除用 {@code remove(key, value)} 而不是 {@code remove(key)}——
     *       若该条在"挑出来"与"删掉"之间刚被触碰过（槽对象已换新），这次删除会失败，
     *       于是下一轮重新挑。这样**不会把刚刚被用过的会话删掉**；</li>
     *   <li>触碰时间并列时按键名升序决胜，使逐出**可复现**——⛔ 不依赖哈希迭代顺序，
     *       否则同样的用例在不同 JVM/不同 key 集合下会剔掉不同的会话（`AGENTS.md` §6 第 2 条
     *       要求每轮只变少数变量，产物与行为都要可比）。</li>
     * </ol>
     */
    private void evictWhileOverCapacity() {
        while (slots.size() > maxSessions) {
            String victimKey = null;
            Slot victim = null;
            for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                Slot current = entry.getValue();
                boolean older = victim == null || current.lastTouched() < victim.lastTouched();
                boolean tieAndSmallerKey = victim != null
                        && current.lastTouched() == victim.lastTouched()
                        && entry.getKey().compareTo(victimKey) < 0;
                if (older || tieAndSmallerKey) {
                    victimKey = entry.getKey();
                    victim = current;
                }
            }
            if (victim == null || !slots.remove(victimKey, victim)) {
                return;
            }
        }
    }
}
