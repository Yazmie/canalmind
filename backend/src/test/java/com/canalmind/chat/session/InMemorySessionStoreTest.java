package com.canalmind.chat.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进程内会话存储的测试（C-38④）。
 *
 * <p><b>为什么逐出策略要专门测"不是 FIFO"</b>：{@code InMemorySessionStore} 选的是
 * **最近最少触碰**（LRU），而"最早创建先逐出"（FIFO）同样是常见实现、同样能通过
 * "数量不超过上限"这种粗断言。两者对用户的区别却是致命的：FIFO 会把聊得最久的会话
 * （开始得最早）清掉，而它恰恰是最可能被继续追问的那个。
 * 故本测试构造出"被创建最早、但刚被 load 过"的会话，断言它必须活下来——
 * 这一条 FIFO 实现会红（与 `docs/工程复盘/数据一致性/评测集自身的错误不会报错.md`
 * 同一取向：断言要能区分两种实现，而不是只断言"没崩"）。
 *
 * <p><b>并发那条用例的诚实边界</b>：逐出在并发下是**尽力而为**的（见实现类注释），
 * 故用例只在"所有写线程结束、再 save 一次收敛"之后断言不超上限，
 * ⛔ 不假装它时时精确，也不因为"难测"就不测并发——本模块会长期面对多请求。
 */
class InMemorySessionStoreTest {

    @Test
    @DisplayName("未知会话 load 返回空状态（⛔ 不是 null），且读取不会凭空造出一条会话")
    void loadUnknownReturnsEmptyState() {
        InMemorySessionStore store = new InMemorySessionStore();

        SessionState state = store.load("从没存过的会话");

        assertThat(state).as("返 null 会让每个调用点都要判空，漏一处就是 NPE").isNotNull();
        assertThat(state.sessionId()).isEqualTo("从没存过的会话");
        assertThat(state.turns()).isEmpty();
        assertThat(state.summary()).isEmpty();
        assertThat(state.summarizedUpTo()).isZero();
        assertThat(store.sessionCount()).as("读一次不该造出一条会话").isZero();
    }

    @Test
    @DisplayName("save/load 往返逐字段一致；同 id 覆盖保存取最新一版")
    void saveLoadRoundTripAndOverwrite() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionState first = new SessionState("s1", List.of(turn(1, "问一")), "旧摘要", 1);
        store.save(first);
        assertThat(store.load("s1")).isEqualTo(first);

        SessionState second = new SessionState("s1", List.of(turn(1, "问一"), turn(2, "问二")), "新摘要", 1);
        store.save(second);
        assertThat(store.load("s1")).isEqualTo(second);
        assertThat(store.load("s1").summary()).isEqualTo("新摘要");
        assertThat(store.sessionCount()).as("同 id 覆盖，不是新增").isEqualTo(1);
    }

    @Test
    @DisplayName("clear 只删指定会话；删不存在的会话是空操作；空 id 一律拒绝")
    void clearRemovesOnlyTargetSession() {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(state("a", 1));
        store.save(state("b", 2));

        store.clear("a");

        assertThat(store.load("a").turns()).isEmpty();
        assertThat(store.load("b").turns()).hasSize(1);
        assertThat(store.sessionCount()).isEqualTo(1);

        store.clear("没存过的");
        assertThat(store.sessionCount()).as("删不存在的会话不该有任何副作用").isEqualTo(1);

        assertThatThrownBy(() -> store.clear("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> store.load(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("★ 逐出的是「最近最少触碰」而不是「最早创建」：刚被 load 过的老会话必须活下来")
    void evictsLeastRecentlyTouchedNotOldestCreated() {
        InMemorySessionStore store = new InMemorySessionStore(3);
        store.save(state("a", 1));
        store.save(state("b", 2));
        store.save(state("c", 3));

        // 触碰 a：此刻触碰先后为 b < c < a
        assertThat(store.load("a").turns()).hasSize(1);

        store.save(state("d", 4));

        assertThat(store.sessionCount()).isEqualTo(3);
        assertThat(store.load("b").turns())
                .as("b 最久没被触碰，应被逐出")
                .isEmpty();
        assertThat(store.load("a").turns())
                .as("a 创建最早但刚被用过；FIFO 实现会在这里红")
                .hasSize(1);
        assertThat(store.load("c").turns()).hasSize(1);
        assertThat(store.load("d").turns()).hasSize(1);
    }

    @Test
    @DisplayName("maxSessions 非正一律拒绝；默认上限是 200 且可在构造处读到实际值")
    void rejectsNonPositiveMaxSessions() {
        assertThatThrownBy(() -> new InMemorySessionStore(0))
                .as("上限 0 会让每次 save 立刻把自己逐出，表现为「上下文永远为空」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSessions");
        assertThatThrownBy(() -> new InMemorySessionStore(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSessions");

        assertThat(new InMemorySessionStore().maxSessions()).isEqualTo(200);
        assertThat(InMemorySessionStore.DEFAULT_MAX_SESSIONS).isEqualTo(200);
    }

    @Test
    @DisplayName("并发 save/load 不抛异常；收敛后不得超过上限（逐出是尽力而为）")
    void concurrentAccessDoesNotThrowAndConvergesToLimit() throws Exception {
        int maxSessions = 8;
        InMemorySessionStore store = new InMemorySessionStore(maxSessions);
        int threads = 8;
        int opsPerThread = 200;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadNo = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                for (int i = 0; i < opsPerThread; i++) {
                    String sessionId = "s-" + threadNo + "-" + (i % 5);
                    store.save(new SessionState(sessionId, List.of(turn(1, sessionId)), "", 0));
                    assertThat(store.load(sessionId)).isNotNull();
                }
                return null;
            }));
        }
        start.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 逐出是尽力而为的：并发写入可能让上限短暂超出，故这里再 save 一次让它收敛，
        // 然后才断言（⛔ 不要假装它时时精确，也不要因为难测就不测并发）。
        store.save(state("final", 1));
        assertThat(store.sessionCount()).isLessThanOrEqualTo(maxSessions);
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static SessionState state(String sessionId, int index) {
        return SessionState.empty(sessionId).withTurn(turn(index, "问" + index));
    }

    private static SessionTurn turn(int index, String question) {
        return new SessionTurn(index, question, "答" + index, "structured", "");
    }
}
