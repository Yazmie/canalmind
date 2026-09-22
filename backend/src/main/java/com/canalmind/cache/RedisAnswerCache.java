package com.canalmind.cache;

import redis.clients.jedis.JedisPooled;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 存储的作答缓存（**C-55**；本机 Redis 5.0.14.1）。
 *
 * <p>⚠️ <b>为什么是 Jedis 而不是 Lettuce / Spring Data Redis</b>（**实测，不是偏好**）：
 * `spring-boot-starter-data-redis` 经 BOM 传递要求 {@code io.lettuce:lettuce-core:6.6.0.RELEASE}，
 * 而本机**离线仓库**里只有 6.1.10 / 6.3.2 / 6.5.5 ⇒ `mvn -o` 直接失败
 * （{@code Cannot access huaweicloud … in offline mode}），而本项目**必须能离线构建**。
 * Jedis 在仓库里 jar 齐备（6.0.0）且**不经过 Spring 容器**——本项目的评测 / CLI 路径没有容器，
 * 这一点与 {@link AnswerCache} 的设计一致。
 *
 * <p>⚠️ <b>键前缀</b>：所有键都带 {@code prefix}，便于按前缀清理，也避免踩到同一实例里别人的键。
 *
 * <p>⚠️ <b>TTL 由存储层兜底</b>：用 {@code SETEX} 而不是 {@code SET}——
 * 数据版本隔离只保证**不串答**，不保证**不涨**；条目必须会自己消失。
 * ⚠️ TTL 非正时**不写**（写进去就是永不过期，比不缓存更糟）。
 */
public final class RedisAnswerCache implements AnswerCache, AutoCloseable {

    /** 默认键前缀。 */
    public static final String DEFAULT_PREFIX = "canalmind:cache:answer:";
    /** 探活的默认超时（毫秒）——⛔ 缓存绝不能把主链路拖住。 */
    public static final int DEFAULT_TIMEOUT_MILLIS = 500;

    private final JedisPooled jedis;
    private final String prefix;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private RedisAnswerCache(JedisPooled jedis, String prefix) {
        this.jedis = jedis;
        this.prefix = prefix;
    }

    /** 建连并**立刻 ping 一次**：连不上要在这里就暴露，⛔ 不把错误推迟到第一次查询。 */
    public static RedisAnswerCache connect(String host, int port, String password, String prefix) {
        // Jedis 的构造是懒连接，故显式 ping 一次当"握手"
        // ⚠️ ACL 的默认用户名就叫 default；本项目 .env 里 REDIS_PASSWORD 为空 ⇒ 走无口令分支
        JedisPooled jedis = password == null || password.isBlank()
                ? new JedisPooled(host, port)
                : new JedisPooled(host, port, "default", password);
        jedis.ping();
        return new RedisAnswerCache(jedis, prefix == null ? DEFAULT_PREFIX : prefix);
    }

    /**
     * 探活（测试用 `assumeTrue`；⛔ 不抛异常，只回答"能不能连上"）。
     * ⚠️ 用 Jedis 默认的连接/读超时（2 秒），⛔ 不在这里实现自定义超时——
     * 一个探活函数不值得再引入一套配置面。
     */
    public static boolean reachable(String host, int port) {
        try (JedisPooled probe = new JedisPooled(host, port)) {
            return "PONG".equalsIgnoreCase(probe.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Optional<String> lookup(CacheKey key) {
        String v = jedis.get(prefix + key.canonical());
        if (v == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(v);
    }

    @Override
    public void store(CacheKey key, String answer, Duration ttl) {
        long seconds = ttl.toSeconds();
        if (seconds <= 0) {
            // ⚠️ TTL 非正 ⇒ **不写**：写进去就是"永不过期的缓存"，比不缓存更糟
            return;
        }
        jedis.setex(prefix + key.canonical(), seconds, answer);
    }

    @Override
    public String backend() {
        return "redis";
    }

    @Override
    public long hits() {
        return hits.get();
    }

    @Override
    public long misses() {
        return misses.get();
    }

    /** 按前缀清空（测试与运维用）；返回删掉的键数。 */
    public long clearByPrefix() {
        long n = 0L;
        for (String k : jedis.keys(prefix + "*")) {
            n += jedis.del(k);
        }
        return n;
    }

    @Override
    public void close() {
        jedis.close();
    }

    /** 供日志用：写出"我连的是哪台、用的什么前缀"。 */
    public String describe() {
        return "redis prefix=" + prefix + " builtAt=" + Instant.now();
    }
}
