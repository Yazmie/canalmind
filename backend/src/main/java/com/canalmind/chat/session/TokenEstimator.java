package com.canalmind.chat.session;

/**
 * 估算一段文本占多少 token——**只是估算，不是分词器结果**。
 *
 * <p><b>为什么做成函数式接口</b>：与 {@code JsonChatInvoker} 同一理由——
 * "是否超预算"这条分支是本模块最需要被测的部分，而它不该依赖真实模型或真实分词器。
 * 测试可以把估算器换成"每字符 1 token"的桩，让"超预算"变成完全可控的条件；
 * 生产侧则注入 {@link #heuristic()}。
 *
 * <p><b>⚠️ {@link #heuristic()} 的返回值不得当作真实 token 数引用</b>：它**没有被本项目的
 * provider 分词器校验过**。本机 {@code .env} 里 {@code DEEPSEEK_BASE_URL=https://fastmodel.top/v1}
 * 是第三方中转端点、模型名是 {@code deepseek-v4-flash}，其真实分词口径与本仓库无任何可核对出处，
 * 故本仓库**没有任何一次实测**能证明这些系数成立（`AGENTS.md` §6 第 1 条：没有出处的数字不许写）。
 * 它只用于"要不要压缩"这类**阈值比较**；任何要写进文档或评测报告的 token 数必须另有出处。
 */
@FunctionalInterface
public interface TokenEstimator {

    /**
     * 估算 token 数。
     *
     * @param text 待估算文本；{@code null} 与空串一律返回 0
     * @return **估计值**（⛔ 不是真实 token 数，见类注释）
     */
    int estimate(String text);

    /**
     * 启发式估算器：**每个 CJK 字符算 1 个 token；每 4 个非 CJK 字符算 1 个（向上取整）**。
     *
     * <p>两条系数的来路如实说明：中文常见口径是"一字约一 token"，西文常见口径是
     * "约四字符一 token"，二者都是**经验系数**，本仓库无实测支撑（见类注释）。
     * 因此这里只承诺**单调性**：文本更长、汉字更多、ASCII 更多时，估出的数只会更大或相等。
     * 压缩决策依赖的正是这个单调性（"超预算"这件事不会因为文本明确变长而反转为"没超"），
     * 而不是绝对值的准确度。
     *
     * <p>CJK 的判定含汉字（含扩展 A）与中日韩标点 / 全角区：中文标点几乎总与汉字同现，
     * 若把它们按"非 CJK"折算（一个标点只算 0.25 个 token），会系统性低估中文文本，
     * 而低估的后果是**压缩不触发**——恰恰是 C-38 想防的那一侧。
     * 代理对（如 emoji）按一个码点计一个非 CJK 字符。
     */
    static TokenEstimator heuristic() {
        return TokenEstimator::heuristicEstimate;
    }

    private static int heuristicEstimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 范围写成本地常量：接口字段只能是 public static final，没必要为此扩大对外 API 面。
        final int cjkPunctFrom = 0x3000;
        final int cjkPunctTo = 0x303F;
        final int cjkExtAFrom = 0x3400;
        final int cjkExtATo = 0x4DBF;
        final int cjkUnifiedFrom = 0x4E00;
        final int cjkUnifiedTo = 0x9FFF;
        final int fullWidthFrom = 0xFF00;
        final int fullWidthTo = 0xFFEF;

        int tokens = 0;
        int nonCjkRun = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            boolean cjk = (codePoint >= cjkUnifiedFrom && codePoint <= cjkUnifiedTo)
                    || (codePoint >= cjkExtAFrom && codePoint <= cjkExtATo)
                    || (codePoint >= cjkPunctFrom && codePoint <= cjkPunctTo)
                    || (codePoint >= fullWidthFrom && codePoint <= fullWidthTo);
            if (cjk) {
                tokens += (nonCjkRun + 3) / 4;
                nonCjkRun = 0;
                tokens += 1;
            } else {
                nonCjkRun++;
            }
        }
        tokens += (nonCjkRun + 3) / 4;
        return tokens;
    }
}
