package com.canalmind.eval.judge;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Judge 提示词的测试（第 4 周第 3 项，C-39）。
 *
 * <p><b>为什么一半的断言是在测"提示词里有没有某段话"</b>：Judge 的行为**就是**提示词决定的，
 * 它没有别的可测面。而本项目最怕的不是判得松，是**判反**——L3 要求"如实说无此数据"、
 * L6 要求"拒绝越权"，通用三维 Judge 在这两层会把"编出一个数"判得比"如实说没有"更完整。
 * 所以那几段"按类型补充判据"必须被测试固定住，⛔ 不允许后来者当成啰嗦话删掉。
 */
class JudgePromptTest {

    private static JudgeInput sample() {
        List<String> facts = new ArrayList<>();
        facts.add("确定性费用引擎算出：2839.20 元（基础 3120.00 × 0.70 × 1.30）");
        return new JudgeInput("L5-001", "L5", "1200 吨成品油 2026 年 10 月 15 号过闸，过闸费要交多少？",
                "【06-过闸费费率表（2026版）】第五条 ……", "金额 2839.20 元",
                "金额必须为 2839.20 元且写明是 0.70 与 1.30 两个系数复合的结果", facts,
                "要交 2839.20 元。");
    }

    @Test
    @DisplayName("四个输入部分齐全：用户问题 / 参考资料 / 参考答案与评分要点 / AI 回答")
    void allFourSectionsPresent() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains("【用户问题】").contains("【参考资料】")
                .contains("【参考答案与评分要点】").contains("【AI 回答】");
        assertThat(p).contains(sample().question());
    }

    @Test
    @DisplayName("三个维度名与 5/3/1 分档锚点全部在提示词里（缺锚点 Judge 只能凭感觉打分）")
    void dimensionsAndAnchorsPresent() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains("事实忠实度（Faithfulness）")
                .contains("答案相关性（Answer Relevance）")
                .contains("完整性（Completeness）");
        // 每档锚点各出现一次以上
        assertThat(p).contains("5 分：").contains("3 分：").contains("1 分：");
        assertThat(p).contains("1-5 的整数");
    }

    @Test
    @DisplayName("要求只输出 JSON，且给出确切的 JSON 形状（含 needs_human_review）")
    void outputContractIsExplicit() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains("最终只输出 JSON");
        assertThat(p).contains("\"faithfulness\"").contains("\"relevance\"")
                .contains("\"completeness\"").contains("\"needs_human_review\"")
                .contains("\"reasoning\"");
        assertThat(JudgePrompt.DIMENSIONS)
                .containsExactly("faithfulness", "relevance", "completeness");
    }

    @Test
    @DisplayName("⛔ 禁令：Judge 不得自己重算数字（知识库第 196 行——数学/SQL 不能只交给 Judge）")
    void judgeIsForbiddenFromDoingArithmetic() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains("服务端已核对的事实");
        assertThat(p).contains("只以「服务端已核对的事实」为准");
        assertThat(p).contains("自己另算一个数并据此作答，判 1 分");
        // 事实为空时也必须显式写"无"，⛔ 不能留空让 Judge 以为有事实可依
        JudgeInput noFacts = new JudgeInput("L3-001", "L3", "水深多少米？", "", "无固定水深",
                "必须说明没有固定水深刻度", List.of(), "语料未规定固定水深。");
        assertThat(JudgePrompt.userPrompt(noFacts)).contains("服务端已核对的事实（数字类一律以此为准）：无");
    }

    @Test
    @DisplayName("三类题面各有专门的补充判据：拒答类与对抗类的「正确」是反向的")
    void threeItemKindsHaveTheirOwnRules() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains(JudgePrompt.KIND_FACT)
                .contains(JudgePrompt.KIND_REFUSE)
                .contains(JudgePrompt.KIND_GUARD);
        // 拒答类：如实说无数据 = 满分；给出具体数值 = 1 分
        assertThat(p).contains("如实说明即为满分");
        // 对抗类：做到拒绝/纠错/提示 = 满分，且**不许倒扣相关性**
        assertThat(p).contains("做到即为满分");
        assertThat(p).contains("**不要**因为「回答没有给出用户想要的数字」而扣相关性分");
    }

    @Test
    @DisplayName("保留 needs_human_review 出口，并点名本项目领域（知识库第 212 行的财务/赔付类）")
    void humanReviewEscapeHatchKept() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains("如果证据不足以判断");
        assertThat(p).contains("不要硬判");
        assertThat(p).contains("赔付口径").contains("费率定价合规性").contains("责任归属");
        assertThat(p).contains("needs_human_review 设为 true");
    }

    @Test
    @DisplayName("题目专属评分要点被写进提示词（⛔ 不允许退化成只看通用三维）")
    void itemRubricIsIncluded() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).contains("本题专属评分要点");
        assertThat(p).contains("0.70 与 1.30 两个系数复合");
    }

    @Test
    @DisplayName("提示词不得出现任何覆盖范围宣称（Judge 只覆盖 55/161 条，见 C-39）")
    void promptMakesNoCoverageClaim() {
        String p = JudgePrompt.userPrompt(sample());
        assertThat(p).doesNotContain("全部 160").doesNotContain("全部 161")
                .doesNotContain("覆盖全部").doesNotContain("100%");
    }
}
