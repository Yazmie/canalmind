package com.canalmind.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重排结论类型的<b>自相矛盾检查</b>（2026-09-21，对应 ②「一次成功」与降级警告打架那一类毛病）。
 *
 * <p>为什么这些不变量值得单测：{@code RerankOutcome} 是产物与 stdout 的<b>唯一数据来源</b>，
 * 而它的字段是"关联事实"——"降级"必须配"原因 + 分类"，"调模型之前就失败"必须配
 * {@code attempts=0}。这类字段一旦对不上，产物会同时给出两句话、彼此矛盾，
 * 而<b>不会抛任何异常</b>：读者只能凭信念挑一句信。故把它们钉成构造期的硬校验，
 * 让"对不上"在<b>造出对象的那一刻</b>就失败。
 */
class RerankOutcomeTest {

    @Test
    @DisplayName("★ 降级必须写明分类：degraded=true 配 NONE 直接拒绝")
    void degradedRequiresAFailureKind() {
        assertThatThrownBy(() -> RerankOutcome.degraded(two(), "重排失败", 1L, "m",
                RerankOutcome.FailureKind.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureKind");

        // 降级而不写原因（既有校验，别被本次改动带坏）
        assertThatThrownBy(() -> RerankOutcome.degraded(two(), "  ", 1L, "m",
                RerankOutcome.FailureKind.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("degradeReason");
    }

    @Test
    @DisplayName("★ 「调模型之前失败」必须 attempts=0；「之后失败」必须 attempts>=1")
    void failureKindAndAttemptsMustAgree() {
        assertThatThrownBy(() -> RerankOutcome.degraded(two(), "输出被截断", 5L, "m", 0,
                List.of(), RerankOutcome.FailureKind.UNPARSABLE))
                .as("解析失败不可能一次模型都没调过：次数与分类来自两次不同的统计")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");

        assertThatThrownBy(() -> RerankOutcome.degraded(two(), "候选超上限", 0L, "m", 2,
                List.of("第一次"), RerankOutcome.FailureKind.TOO_MANY_CANDIDATES))
                .as("候选超上限是在调模型之前被拒的：它不可能「试过 2 次」")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("调模型之前");

        // 合法的两侧都必须通过
        assertThat(RerankOutcome.degraded(two(), "候选超上限", 0L, "m",
                RerankOutcome.FailureKind.TOO_MANY_CANDIDATES).attempts()).isZero();
        assertThat(RerankOutcome.degraded(two(), "输出被截断", 5L, "m", 1, List.of(),
                RerankOutcome.FailureKind.UNPARSABLE).attempts()).isEqualTo(1);
        assertThat(RerankOutcome.degraded(two(), "输出被截断", 5L, "m", 3,
                List.of("第 1 次坏", "第 2 次坏"), RerankOutcome.FailureKind.UNPARSABLE).attempts())
                .as("重试耗尽：3 次尝试 + 2 条失败记录，合法")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★ 未降级时分类归一成 NONE（避免 degraded=false 却带一个失败分类的怪组合）")
    void successNormalizesFailureKind() {
        RerankOutcome out = new RerankOutcome(two(), false, null, 3L, "m", 2,
                List.of("第一次：输出无法解析"), RerankOutcome.FailureKind.UNPARSABLE);

        assertThat(out.failureKind()).isEqualTo(RerankOutcome.FailureKind.NONE);
        assertThat(out.degradeReason()).isNull();
        assertThat(out.previousDegradeReasons())
                .as("⛔ 但前几次失败不清空：重试后成功恰恰要靠它表达「不是一次成功」")
                .hasSize(1);
        assertThat(out.recoveredByRetry()).isTrue();
    }

    @Test
    @DisplayName("★ retried / recoveredByRetry 的语义：attempts=1 不等于一次成功")
    void retrySemantics() {
        RerankOutcome oneShot = RerankOutcome.ok(two(), 1L, "m", 1, List.of());
        assertThat(oneShot.retried()).isFalse();
        assertThat(oneShot.recoveredByRetry()).as("一次成功").isFalse();
        assertThat(oneShot.failureKind()).isEqualTo(RerankOutcome.FailureKind.NONE);

        RerankOutcome retriedOk = RerankOutcome.ok(two(), 1L, "m", 3,
                List.of("第 1 次坏", "第 2 次坏"));
        assertThat(retriedOk.retried()).isTrue();
        assertThat(retriedOk.recoveredByRetry()).as("重试后才成功").isTrue();

        RerankOutcome stillDegraded = RerankOutcome.degraded(two(), "均失败", 1L, "m", 3,
                List.of("第 1 次坏", "第 2 次坏"), RerankOutcome.FailureKind.UNPARSABLE);
        assertThat(stillDegraded.retried()).isTrue();
        assertThat(stillDegraded.recoveredByRetry())
                .as("降级时不许说「重试后成功」")
                .isFalse();

        RerankOutcome neverCalled = RerankOutcome.ok(two(), 0L, RerankOutcome.NO_MODEL);
        assertThat(neverCalled.attempts()).as("0 = 一次都没调模型").isZero();
        assertThat(neverCalled.recoveredByRetry()).isFalse();
    }

    @Test
    @DisplayName("兼容：旧的 4 参 / 6 参 degraded 工厂仍然可用，分类记作「未标注」")
    void legacyFactoriesStayUsable() {
        RerankOutcome four = RerankOutcome.degraded(two(), "超时", 7L, "m");
        assertThat(four.failureKind()).as("外部调用方（测试夹具 / 自定义实现）可以不给分类")
                .isEqualTo(RerankOutcome.FailureKind.UNSPECIFIED);
        assertThat(four.attempts()).isZero();

        RerankOutcome six = RerankOutcome.degraded(two(), "超时", 7L, "m", 2,
                List.of("第 1 次超时"));
        assertThat(six.failureKind()).isEqualTo(RerankOutcome.FailureKind.UNSPECIFIED);
        assertThat(six.attempts()).isEqualTo(2);
        assertThat(six.failureKind().retryNote()).as("「未标注」也要能回答为什么没重试")
                .isNotBlank();
    }

    @Test
    @DisplayName("既往不变量仍在：attempts 不得为负、失败记录条数不得超过 attempts")
    void existingInvariantsSurvive() {
        assertThatThrownBy(() -> new RerankOutcome(two(), false, null, 0L, "m", -1, List.of(),
                RerankOutcome.FailureKind.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts");
        assertThatThrownBy(() -> RerankOutcome.ok(two(), 0L, "m", 1,
                List.of("多余的第一条", "多余的第二条")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("previousDegradeReasons");
    }

    private static List<RetrievalCandidate> two() {
        return List.of(RetrievalCandidate.of("a"), RetrievalCandidate.of("b"));
    }
}
