package com.canalmind.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C-55 缓存机制的测试：键隔离、归一化、开关默认值、内存实现、以及**真实 Redis** 一趟。
 *
 * <p>⚠️ 判断"某个字段有没有真的入键"的办法是**逐个改一个字段、断言键变了**——
 * ⛔ 不是"看代码里有这个字段"（那正是本项目记录过的"改了但其实没生效"）。
 */
class AnswerCacheTest {

    private static final String V = "rules:abc:chunks:12";

    // ------------------------------------------------------------------ 键隔离（KB 第 310 行）

    @Test
    @DisplayName("知识库第 310 行要求的五个边界逐个入键：改任一个，键都必须变")
    void everyBoundaryIsInTheKey() {
        CacheKey base = new CacheKey("t1", "s1", V, "L4", "v1", "过闸费怎么收");

        assertNotEquals(base.canonical(), new CacheKey("t2", "s1", V, "L4", "v1", "过闸费怎么收").canonical(),
                "租户必须入键");
        assertNotEquals(base.canonical(), new CacheKey("t1", "s2", V, "L4", "v1", "过闸费怎么收").canonical(),
                "权限范围必须入键");
        assertNotEquals(base.canonical(), new CacheKey("t1", "s1", "rules:def:chunks:12", "L4", "v1",
                "过闸费怎么收").canonical(), "数据版本必须入键");
        assertNotEquals(base.canonical(), new CacheKey("t1", "s1", V, "L5", "v1", "过闸费怎么收").canonical(),
                "场景必须入键");
        assertNotEquals(base.canonical(), new CacheKey("t1", "s1", V, "L4", "v2", "过闸费怎么收").canonical(),
                "Prompt 版本必须入键");
        assertNotEquals(base.canonical(), new CacheKey("t1", "s1", V, "L4", "v1", "过闸费怎么算").canonical(),
                "问句当然必须入键");
    }

    @Test
    @DisplayName("归一化只做字面归一：空白/全角差异同键；⛔ 不做语义或标点归一")
    void normalizationIsLiteralOnly() {
        // ① 首尾空白 + 连写空白 ⇒ 同键
        assertEquals(CacheKey.normalize("  过闸费  怎么收 "), CacheKey.normalize("过闸费 怎么收"));
        // ② 全角空格（U+3000）也算空白
        assertEquals("过闸费 怎么收", CacheKey.normalize("过闸费\u3000怎么收"));
        // ③ 全角 ASCII ⇒ 半角：问号同键
        assertEquals(CacheKey.normalize("过闸费怎么收?"), CacheKey.normalize("过闸费怎么收？"));
        // ④ ⛔ 刻意**不**删标点：这就是"少命中但绝不串答"的安全方向
        assertNotEquals(CacheKey.normalize("过闸费怎么收"), CacheKey.normalize("过闸费怎么收?"));
        // ⑤ ⛔ 不做同义替换
        assertNotEquals(CacheKey.normalize("过闸费怎么收"), CacheKey.normalize("过闸费如何收取"));
        // ⑥ 空/空白问句
        assertEquals("", CacheKey.normalize("   "));
        assertEquals("", CacheKey.normalize(null));
    }

    @Test
    @DisplayName("of(...) 用单租户/单权限常量占位，⛔ 不是省略字段")
    void ofUsesExplicitPlaceholders() {
        CacheKey k = CacheKey.of(V, "L4", "v1", "过闸费怎么收");
        assertEquals(CacheKey.DEFAULT_TENANT, k.tenant());
        assertEquals(CacheKey.DEFAULT_SCOPE, k.scope());
        assertEquals("过闸费怎么收", k.normalizedQuestion());
    }

    // ------------------------------------------------------------------ 内存实现 + TTL

    @Test
    @DisplayName("内存实现：命中/未命中计数正确，TTL 到点即失效（⛔ 不真睡）")
    void inMemoryHonoursTtlWithoutSleeping() {
        AtomicLong clock = new AtomicLong(0L);
        InMemoryCache cache = new InMemoryCache(8, clock::get);
        CacheKey key = CacheKey.of(V, "L4", "v1", "过闸费怎么收");

        assertTrue(cache.lookup(key).isEmpty());
        cache.store(key, "答案是 12 元", Duration.ofSeconds(10));

        assertEquals("答案是 12 元", cache.lookup(key).orElseThrow());
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());

        clock.addAndGet(Duration.ofSeconds(10).toNanos());   // 正好到期 ⇒ 失效（边界取 <=）
        assertTrue(cache.lookup(key).isEmpty());
        assertEquals(0, cache.size(), "过期条目必须被清掉，⛔ 不能攒在内存里");
    }

    @Test
    @DisplayName("内存实现容量有限：超限按访问序淘汰（⛔ 不能被问句撑爆）")
    void inMemoryEvictsBeyondCapacity() {
        InMemoryCache cache = new InMemoryCache(2, () -> 0L);
        cache.store(CacheKey.of(V, "L4", "v1", "问1"), "a", Duration.ofHours(1));
        cache.store(CacheKey.of(V, "L4", "v1", "问2"), "b", Duration.ofHours(1));
        cache.store(CacheKey.of(V, "L4", "v1", "问3"), "c", Duration.ofHours(1));
        assertEquals(2, cache.size());
        assertTrue(cache.lookup(CacheKey.of(V, "L4", "v1", "问1")).isEmpty(), "最老的应已被淘汰");
    }

    // ------------------------------------------------------------------ 开关：默认关 / 评测强制关

    @Test
    @DisplayName("默认关：没配开关就是 noop，且 **noop 不写**（否则会偷偷污染 Redis）")
    void disabledByDefaultAndNoopNeverWrites() {
        Map<String, String> env = Map.of();                    // 什么都没配
        assertFalse(AnswerCacheFactory.enabled(env));

        AnswerCache cache = AnswerCacheFactory.fromEnvironment(env);
        assertFalse(cache.enabled());
        assertEquals("noop", cache.backend());

        cache.store(CacheKey.of(V, "L4", "v1", "过闸费怎么收"), "不该被写进去", Duration.ofHours(1));
        assertTrue(cache.lookup(CacheKey.of(V, "L4", "v1", "过闸费怎么收")).isEmpty());
        assertEquals(1L, cache.misses(), "关着也要计数：这样「默认关」在产物里是可见的");
    }

    @Test
    @DisplayName("评测强制关：开关是开的，但 forceDisabled=true ⇒ 仍然 noop")
    void evaluationForcesOff() {
        Map<String, String> on = new HashMap<>();
        on.put(AnswerCacheFactory.ENABLED_ENV, "on");
        assertTrue(AnswerCacheFactory.enabled(on));
        assertFalse(AnswerCacheFactory.enabled(on, true), "评测路径必须能强制关掉");
        assertFalse(AnswerCacheFactory.fromEnvironment(on, true).enabled());
    }

    @Test
    @DisplayName("开关取值只认少数写法；TTL 非正/解析失败都退回默认值")
    void switchAndTtlParsing() {
        for (String yes : new String[]{"on", "ON", "true", "1", "yes"}) {
            assertTrue(AnswerCacheFactory.enabled(Map.of(AnswerCacheFactory.ENABLED_ENV, yes)), yes);
        }
        for (String no : new String[]{"", "  ", "off", "false", "0", "no", "oui"}) {
            assertFalse(AnswerCacheFactory.enabled(Map.of(AnswerCacheFactory.ENABLED_ENV, no)), "[" + no + "]");
        }
        assertEquals(Duration.ofSeconds(30),
                AnswerCacheFactory.ttl(Map.of(AnswerCacheFactory.TTL_ENV, "30")));
        assertEquals(Duration.ofSeconds(AnswerCacheFactory.DEFAULT_TTL_SECONDS),
                AnswerCacheFactory.ttl(Map.of(AnswerCacheFactory.TTL_ENV, "0")), "0 不是「永不过期」");
        assertEquals(Duration.ofSeconds(AnswerCacheFactory.DEFAULT_TTL_SECONDS),
                AnswerCacheFactory.ttl(Map.of(AnswerCacheFactory.TTL_ENV, "abc")));
    }

    @Test
    @DisplayName("数据版本哨兵：unavailable/空白都不可用 ⇒ 调用方必须据此放弃缓存")
    void dataVersionSentinel() {
        assertTrue(AnswerCacheFactory.DataVersion.usable(V));
        assertFalse(AnswerCacheFactory.DataVersion.usable(null));
        assertFalse(AnswerCacheFactory.DataVersion.usable(""));
        assertFalse(AnswerCacheFactory.DataVersion.usable(AnswerCacheFactory.DataVersion.UNAVAILABLE));
        assertEquals("rules:h:chunks:3", AnswerCacheFactory.DataVersion.format("h", 3L));
    }

    // ------------------------------------------------------------------ 真实 Redis（连不上就跳过）

    @Test
    @DisplayName("真实 Redis：写读、TTL、按前缀清理（Redis 未启动则跳过，⛔ 不假装通过）")
    void redisRoundTrip() {
        String host = "localhost";
        int port = 6379;
        assumeTrue(RedisAnswerCache.reachable(host, port),
                "本机 Redis 未启动 ⇒ 跳过（这是环境前置，不是代码失败）");

        String prefix = "canalmind:test:cache:" + System.nanoTime() + ":";
        try (RedisAnswerCache cache = RedisAnswerCache.connect(host, port, null, prefix)) {
            CacheKey key = CacheKey.of(V, "L4", "v1", "过闸费怎么收");
            assertTrue(cache.lookup(key).isEmpty());
            cache.store(key, "答案是 12 元", Duration.ofSeconds(60));
            assertEquals("答案是 12 元", cache.lookup(key).orElseThrow());
            assertEquals(1L, cache.hits());
            assertEquals(1L, cache.misses());

            // 键隔离在真实存储上也要成立：换个数据版本必须查不到
            assertTrue(cache.lookup(CacheKey.of("rules:other:chunks:12", "L4", "v1", "过闸费怎么收")).isEmpty());

            // TTL 非正 ⇒ 不写（⛔ 不能变成永不过期）
            CacheKey noTtl = CacheKey.of(V, "L4", "v1", "TTL 非正的问句");
            cache.store(noTtl, "不该存在", Duration.ZERO);
            assertTrue(cache.lookup(noTtl).isEmpty());

            assertEquals(1L, cache.clearByPrefix(), "按前缀清理应删掉 1 个键");
        }
    }
}
