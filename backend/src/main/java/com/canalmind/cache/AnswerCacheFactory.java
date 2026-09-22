package com.canalmind.cache;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 作答缓存的装配入口（**C-55** / D16）：开关、TTL、Redis 连接、数据版本。
 *
 * <p><b>默认关</b>（裁示）：只有 {@link #ENABLED_ENV} 显式写成"开"的值才会真的用 Redis，
 * 其余一律 {@link AnswerCache#noop()}。理由见裁示与知识库第 302/310 行的隔离要求——
 * 缓存错了的表现是"答案串了"，而它**不报错**。
 */
public final class AnswerCacheFactory {

    /** 开关键；取值 {@code on}/{@code true}/{@code 1}/{@code yes}（不分大小写）才算开。 */
    public static final String ENABLED_ENV = "CANALMIND_SEMANTIC_CACHE";
    /** TTL 秒数；默认 {@value #DEFAULT_TTL_SECONDS} 秒（6 小时）。 */
    public static final String TTL_ENV = "CANALMIND_SEMANTIC_CACHE_TTL_SECONDS";
    /** 键前缀覆盖（测试/多实例隔离用）。 */
    public static final String PREFIX_ENV = "CANALMIND_SEMANTIC_CACHE_PREFIX";
    /** 默认 TTL。 */
    public static final long DEFAULT_TTL_SECONDS = 6L * 3600L;

    private AnswerCacheFactory() {
    }

    /** 开关是否打开；缺省/空值一律 false（**默认关**）。 */
    public static boolean enabled(Map<String, String> env) {
        return enabled(env, false);
    }

    /**
     * 开关是否打开。
     *
     * @param forceDisabled {@code true} ⇒ **无条件返回 false**。
     *        这是"**评测强制关**"这条裁示的可执行形式：评测路径必须传 {@code true}，
     *        于是"评测时缓存被打开"这件事在代码上就不可能发生（并有测试钉住）。
     */
    public static boolean enabled(Map<String, String> env, boolean forceDisabled) {
        if (forceDisabled) {
            return false;
        }
        String v = env == null ? null : env.get(ENABLED_ENV);
        if (v == null) {
            return false;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        return s.equals("on") || s.equals("true") || s.equals("1") || s.equals("yes");
    }

    /** TTL；非正数或解析失败 ⇒ 默认值（⛔ 不退化成"永不过期"）。 */
    public static Duration ttl(Map<String, String> env) {
        String v = env == null ? null : env.get(TTL_ENV);
        if (v != null) {
            try {
                long s = Long.parseLong(v.strip());
                if (s > 0) {
                    return Duration.ofSeconds(s);
                }
            } catch (NumberFormatException ignored) {
                // 落回默认值：配置写坏了也应该还能跑
            }
        }
        return Duration.ofSeconds(DEFAULT_TTL_SECONDS);
    }

    /** 按环境变量装配：关（默认）⇒ noop；开 ⇒ 连 Redis，**连不上就降级成 noop 并说明原因**。 */
    public static AnswerCache fromEnvironment(Map<String, String> env) {
        return fromEnvironment(env, false);
    }

    /** 同上，但允许调用方强制关闭（评测路径）。 */
    public static AnswerCache fromEnvironment(Map<String, String> env, boolean forceDisabled) {
        if (!enabled(env, forceDisabled)) {
            return AnswerCache.noop();
        }
        String host = value(env, "REDIS_HOST", "localhost");
        int port = intValue(env, "REDIS_PORT", 6379);
        String password = env == null ? null : env.get("REDIS_PASSWORD");
        String prefix = value(env, PREFIX_ENV, RedisAnswerCache.DEFAULT_PREFIX);
        try {
            return RedisAnswerCache.connect(host, port, password, prefix);
        } catch (RuntimeException e) {
            // ⚠️ 缓存故障**不得**拖垮主链路：显式降级 + 把原因打出来（⛔ 不静默吞掉）
            System.err.println("[cache] 已开启但连不上 Redis(" + host + ":" + port + ")，降级为不缓存："
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return AnswerCache.noop();
        }
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String v = env == null ? null : env.get(key);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private static int intValue(Map<String, String> env, String key, int fallback) {
        try {
            return Integer.parseInt(value(env, key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * **数据版本**（知识库第 310 行要求入键的边界之一）。
     *
     * <p>⚠️ 取法**刻意不对称**，理由是"本版缓存只服务确定性规则/费率类作答"：
     * <ul>
     *   <li><b>{@code tariff_rule} 按内容哈希</b>（{@code md5} 整表行文本）——它是规则的**唯一真源**，
     *       改一个字就该换版本，必须精确；</li>
     *   <li><b>{@code rag_chunk} 只记行数</b>——它装了 1024 维向量，整表哈希既慢又无必要：
     *       检索语料变了**不该**影响"过闸费怎么收"这类由规则表决定的键。</li>
     * </ul>
     * ⚠️ 如实记边界：{@code rag_chunk} 行数不变而内容变化时，**数据版本不会变**——
     * 对本版缓存的作用域（规则/费率类）这不构成串答风险；若将来把缓存扩到检索类问句，
     * **必须先换掉这一半**。
     */
    public static final class DataVersion {

        private DataVersion() {
        }

        /** 纯函数部分（可单测，不需要数据库）。 */
        public static String format(String rulesHash, long chunkCount) {
            return "rules:" + rulesHash + ":chunks:" + chunkCount;
        }

        /** 查库取版本。⚠️ 失败**向上抛**——由调用方决定"不缓存"（安全方向），⛔ 不返回假版本。 */
        public static String of(JdbcTemplate jdbc) {
            String rulesHash = jdbc.queryForObject(
                    "SELECT md5(coalesce(string_agg(t::text, '' ORDER BY t::text), '')) FROM tariff_rule t",
                    String.class);
            Long chunks = jdbc.queryForObject("SELECT count(*) FROM rag_chunk", Long.class);
            return format(rulesHash == null ? "none" : rulesHash, chunks == null ? 0L : chunks);
        }

        /** 取不到时的**哨兵**：⛔ 不是"可用版本"，见到它必须当成"不许缓存"。 */
        public static final String UNAVAILABLE = "unavailable";

        /** 这个版本能不能用来缓存（{@link #UNAVAILABLE} 与空白都不行）。 */
        public static boolean usable(String version) {
            return version != null && !version.isBlank() && !UNAVAILABLE.equals(version);
        }
    }
}
