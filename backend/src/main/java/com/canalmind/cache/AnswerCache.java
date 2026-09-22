package com.canalmind.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 作答缓存接口（**C-55** / D16）。
 *
 * <p>做成接口而不是直接上注解（`@Cacheable`）的原因：**缓存必须能在没有 Redis、没有 Spring 容器
 * 的情况下被测试**。本项目的评测/CLI 路径**没有 Spring 容器**（依赖注入根是
 * {@code QuestionPipeline.create}），注解式缓存只能靠容器生效 ⇒ 那条路径要么用不上、
 * 要么得引入一个"测试里也不存在"的代理，**于是缓存的正确性就没法被断言**。
 *
 * <p>⚠️ 计数（{@link #hits()}/{@link #misses()}）是**对外可核对的量**：
 * "缓存到底有没有生效"必须能被一条命令/一个断言回答，⛔ 不能只靠"我接上了"这句话。
 */
public interface AnswerCache {

    /** 查一次；未命中返回 {@link Optional#empty()}。 */
    Optional<String> lookup(CacheKey key);

    /** 存一次（覆写同名键）。 */
    void store(CacheKey key, String answer, Duration ttl);

    /** 存储后端名字（写进日志/产物，便于核对"这次到底走没走缓存"）。 */
    String backend();

    /**
     * 这个缓存是不是**真的开着**（{@link #noop()} 返回 false）。
     *
     * <p>⚠️ 调用方必须用它当开关，⛔ 不要拿 {@link #backend()} 的字符串去判断——
     * 那是给人看的标签，不是判据。
     */
    default boolean enabled() {
        return true;
    }

    /** 命中次数。 */
    long hits();

    /** 未命中次数。 */
    long misses();

    /** 关闭（Redis 连接等）；无状态的实现什么都不做。 */
    default void close() {
    }

    /**
     * **永不命中的缓存** = 功能关闭（**默认状态**）。
     *
     * <p>⚠️ 它**照样计数**（每次 lookup 记一次 miss）：这样"默认关"这件事在产物里是**可见**的，
     * 而不是"没有参照物、看起来和没接一样"。
     */
    static AnswerCache noop() {
        return new AnswerCache() {
            private long misses;

            @Override
            public Optional<String> lookup(CacheKey key) {
                misses++;
                return Optional.empty();
            }

            @Override
            public void store(CacheKey key, String answer, Duration ttl) {
                // 关闭时**不写**：⛔ 关闭必须是彻底不写，否则"默认关"会悄悄污染 Redis
            }

            @Override
            public String backend() {
                return "noop";
            }

            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public long hits() {
                return 0L;
            }

            @Override
            public long misses() {
                return misses;
            }
        };
    }
}
