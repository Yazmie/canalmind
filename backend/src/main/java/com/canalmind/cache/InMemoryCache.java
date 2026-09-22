package com.canalmind.cache;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 进程内缓存（**零依赖**）：测试用，也可作为 Redis 不可用时的显式降级目标。
 *
 * <p>⚠️ 时钟**可注入**（{@link LongSupplier}）：TTL 过期必须能**不真睡**地断言——
 * 本项目已有过"靠 sleep 测超时"导致测试又慢又偶发红的教训。
 *
 * <p>⚠️ 容量**有限**（默认 {@value #DEFAULT_MAX_ENTRIES} 条，LRU 淘汰）：
 * 单机内存不能被问句撑爆；淘汰顺序用访问序（{@link LinkedHashMap} 的 `accessOrder`）。
 */
public final class InMemoryCache implements AnswerCache {

    /** 默认容量：本项目 28 篇语料 + 有限问句集，远够用且不会失控。 */
    public static final int DEFAULT_MAX_ENTRIES = 512;

    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final Map<String, Entry> entries;

    private record Entry(String answer, long expiresAtNanos) {
    }

    public InMemoryCache() {
        this(DEFAULT_MAX_ENTRIES, System::nanoTime);
    }

    public InMemoryCache(int maxEntries, LongSupplier nanoClock) {
        this.maxEntries = maxEntries;
        this.nanoClock = nanoClock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > InMemoryCache.this.maxEntries;
            }
        };
    }

    @Override
    public synchronized Optional<String> lookup(CacheKey key) {
        Entry e = entries.get(key.canonical());
        if (e == null || e.expiresAtNanos() <= nanoClock.getAsLong()) {
            if (e != null) {
                entries.remove(key.canonical());   // 过期即删，⛔ 不返回过期值
            }
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(e.answer());
    }

    @Override
    public synchronized void store(CacheKey key, String answer, Duration ttl) {
        entries.put(key.canonical(), new Entry(answer, nanoClock.getAsLong() + ttl.toNanos()));
    }

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public long hits() {
        return hits.get();
    }

    @Override
    public long misses() {
        return misses.get();
    }

    /** 当前条目数（测试用：验证容量上限与过期清理真的生效）。 */
    public synchronized int size() {
        return entries.size();
    }
}
