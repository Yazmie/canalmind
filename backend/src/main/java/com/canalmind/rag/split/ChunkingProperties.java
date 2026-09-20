package com.canalmind.rag.split;

/**
 * 切分参数。
 *
 * <p><b>取值的依据与实测</b>（不凭感觉设默认值）：
 *
 * <h3>1. {@code maxArticleChars = 800}（条内再拆阈值）</h3>
 * 语料实测（{@code backend/scripts/measure_chunks.py}，2026-09 实测 454 条）：
 * 正文中位 <b>149 字</b>、p90 = 229 字、p99 = 323 字、<b>最大仅 528 字</b>。
 * 故 <b>800 字阈值在真实语料上不会触发</b>——即当前所有条都独立成块。
 *
 * <p>⚠️ <b>这一点必须如实记录，不能宣称"实现了条过长拆分"</b>：
 * 阈值规则当前是<strong>防御性</strong>的（语料将来变长、或接入外部长条文时生效），
 * 而不是已经带来收益的优化。它有单测覆盖（注入超长合成条文），
 * 因此"没被真实数据触发"不等于"没被验证过"。
 *
 * <p>该阈值与知识库给出的量级一致：《RAG 文档处理与切分策略》第 47 行给出通用文本
 * 建议 400–512 Token；第 83 行给出法规合同"按条、款、项保留法律效力单元"。
 * 中文里 1 Token 通常对应 1–2 个汉字，故 800 字已是偏保守的上界。
 *
 * <h3>2. {@code baselineChunkChars = 320} / {@code baselineOverlapChars = 60}（固定长度基线）</h3>
 * 基线需具备<strong>可比性</strong>：结构化切分的块中位数为 149 字，
 * 基线若用 512 字会产生"块数差 3 倍以上"的悬殊对比，那样的对比是块长差异而非策略差异。
 * 故基线取 <b>320 字</b>（约为条中位数的 2 倍，同量级）+ <b>60 字重叠</b>
 * （知识库第 83 行建议 50–100 Token 重叠，中文按字取下沿）。
 *
 * <p><b>单位选择的如实说明</b>：知识库以 Token 为单位给建议，本项目以<strong>字符</strong>
 * 为单位实现。原因：切分器不持有 bge-m3 的 tokenizer（Java 侧暂不具备），
 * 用估算的 Token 数会引入一个无法验证的换算系数，反而不如用可精确复现的字符数。
 * <b>这是有意的取舍，不是疏忽</b>——代价是它与知识库的 Token 建议不能逐字对应。
 *
 * <h3>3. 条 / 款 / 项 的语义</h3>
 * {@code 条} 是<b>唯一主切分单元</b>（用户确认的口径）；{@code 款}（{@code #### 一、}）
 * 与 {@code 项}（{@code 1.}）仅在条超过阈值时才升格为独立 chunk。
 * 依据 {@code docs/规则语料/README.md} §3.2「款：条的子结构（条过长时才用）」。
 */
public record ChunkingProperties(
        int maxArticleChars,
        int baselineChunkChars,
        int baselineOverlapChars) {

    /** 当前生效参数：实测语料下的取值。 */
    public static ChunkingProperties defaults() {
        return new ChunkingProperties(800, 320, 60);
    }

    public ChunkingProperties {
        if (maxArticleChars <= 0) {
            throw new IllegalArgumentException("maxArticleChars 必须为正: " + maxArticleChars);
        }
        if (baselineChunkChars <= 0) {
            throw new IllegalArgumentException("baselineChunkChars 必须为正: " + baselineChunkChars);
        }
        if (baselineOverlapChars < 0 || baselineOverlapChars >= baselineChunkChars) {
            throw new IllegalArgumentException(
                    "baselineOverlapChars 必须落在 [0, baselineChunkChars): " + baselineOverlapChars);
        }
    }
}
