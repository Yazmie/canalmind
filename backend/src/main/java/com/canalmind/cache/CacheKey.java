package com.canalmind.cache;

import java.util.Objects;

/**
 * 作答缓存的**严格隔离键**（**C-55** / D16）。
 *
 * <p><b>依据（知识库《大模型网关详解》）</b>：
 * 第 <b>310</b> 行——"语义缓存的键至少要隔离**租户、权限范围、数据版本、场景和 Prompt 版本**；
 * 向量相似度只能作为**候选**命中条件，**不能替代这些边界**"；
 * 第 <b>302</b> 行——"请求里一旦带有权限、实时状态、私密上下文或需要专业判断的内容，
 * 缓存必须**绕过**或使用严格隔离的键"。
 *
 * <p>⚠️ <b>本版刻意比知识库更保守</b>：知识库允许把向量相似度当**候选**命中条件，
 * 本项目**不用**它——确定性规则/费率类问句（"过闸费怎么收"）必须**逐字归一后精确命中**，
 * 否则"内河船过闸费"与"海船过闸费"这种向量上很近的句子会互相污染，
 * 而那正是第 310 行点名要防的事（"一个需要解释、一个需要…"）。
 *
 * <p>⚠️ <b>本版不含租户/权限的真实取值</b>：本项目是单租户、无权限模型，
 * 故两个字段**显式固定**为常量并**入键**（占位但不容缺）——将来接入多租户时，
 * 改的是这两个常量的来源，<b>不是键的结构</b>。
 *
 * @param tenant             租户（本项目固定 {@link #DEFAULT_TENANT}）
 * @param scope              权限范围（本项目固定 {@link #DEFAULT_SCOPE}）
 * @param dataVersion        数据版本（规则/语料指纹，见 {@code AnswerCacheFactory.DataVersion}）
 * @param scene              场景（当前用层名/通道名，如 {@code L4}）
 * @param promptVersion      作答提示词版本（v1/v2）
 * @param normalizedQuestion 归一化问句，见 {@link #normalize(String)}
 */
public record CacheKey(String tenant, String scope, String dataVersion, String scene,
                       String promptVersion, String normalizedQuestion) {

    /** 本项目单租户：显式占位，⛔ 不省字段。 */
    public static final String DEFAULT_TENANT = "canalmind";
    /** 本项目无权限模型：显式占位，⛔ 不省字段。 */
    public static final String DEFAULT_SCOPE = "public";

    /** 分隔符用**单元分隔符**（U+001F）：正常问句与版本号里不会出现，避免拼接歧义。 */
    private static final char SEP = (char) 31;

    public CacheKey {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(dataVersion, "dataVersion");
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(normalizedQuestion, "normalizedQuestion");
    }

    /** 拼成一个可存 Redis 的字符串键（前缀由存储层另加）。 */
    public String canonical() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(tenant).append(SEP).append(scope).append(SEP).append(dataVersion).append(SEP)
                .append(scene).append(SEP).append(promptVersion).append(SEP).append(normalizedQuestion);
        return sb.toString();
    }

    /**
     * 问句归一化：**只做"同义同形"的字面归一，不做任何语义推断**。
     *
     * <ol>
     *   <li>去掉首尾空白；</li>
     *   <li>所有空白字符（含全角空格 U+3000、制表、换行）折叠成**一个半角空格**；</li>
     *   <li>全角 ASCII（U+FF01–U+FF5E）转半角 —— 让"过闸费怎么收？"与"过闸费怎么收?"同键。</li>
     * </ol>
     *
     * <p>⚠️ <b>不做</b>的事（有意为之，宁可少命中也不串答）：不删标点、不去停用词、不排序、不同义替换。
     * 故"过闸费怎么收"与"过闸费怎么收？"仍是**两个键**——这是**安全方向**的漏命中。
     */
    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(question.length());
        boolean pendingSpace = false;
        for (int i = 0; i < question.length(); i++) {
            char c = question.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (c >= (char) 0xFF01 && c <= (char) 0xFF5E) {
                c = (char) (c - 0xFEE0);          // 全角 ASCII ⇒ 半角
            }
            if (pendingSpace) {
                sb.append(' ');
                pendingSpace = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 便捷构造：租户/权限用本项目常量。 */
    public static CacheKey of(String dataVersion, String scene, String promptVersion, String question) {
        return new CacheKey(DEFAULT_TENANT, DEFAULT_SCOPE, dataVersion, scene, promptVersion,
                normalize(question));
    }
}
