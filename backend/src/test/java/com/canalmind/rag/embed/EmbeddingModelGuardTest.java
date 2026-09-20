package com.canalmind.rag.embed;

import com.canalmind.rag.embed.EmbeddingModelGuard.EmbeddingModelMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EmbeddingModelGuard} 的测试——C-22 配套硬约束在**查询侧**的守门人。
 *
 * <p>这些用例的共同前提：拿错模型的向量去检索，**不会报错**，
 * 只会返回一份"看起来正常、排序全错"的结果。所以闸门必须**拒绝**查询，
 * 而不是打个警告继续跑。
 */
class EmbeddingModelGuardTest {

    private static final EmbeddingModelInfo INDEXED =
            new EmbeddingModelInfo("ollama", "bge-m3", 1024);

    @Test
    @DisplayName("两侧完全一致 → 放行")
    void consistentPasses() {
        EmbeddingModelGuard guard = new EmbeddingModelGuard(
                INDEXED, new EmbeddingModelInfo("ollama", "bge-m3", 1024));
        assertThat(guard.isConsistent()).isTrue();
        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⚠️ 维度相同但模型不同 → 拒绝（知识库第 33 行场景）")
    void sameDimensionDifferentModelRejected() {
        // 这类错误最阴险：能放进同一索引（维度相同），所以不会有任何技术错误提示，
        // 但两个模型的向量空间毫无关系，相似度分数不可比。
        EmbeddingModelGuard guard = new EmbeddingModelGuard(
                INDEXED, new EmbeddingModelInfo("openai", "text-embedding-3-small", 1024));
        assertThat(guard.isConsistent()).isFalse();
        assertThatThrownBy(guard::verify)
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining("不匹配")
                .hasMessageContaining("不可比")
                .hasMessageContaining("全量重建索引");
    }

    @Test
    @DisplayName("维度不同 → 拒绝，且说明无法放进同一索引")
    void differentDimensionRejected() {
        EmbeddingModelGuard guard = new EmbeddingModelGuard(
                INDEXED, new EmbeddingModelInfo("ollama", "nomic-embed-text", 768));
        assertThatThrownBy(guard::verify)
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining("维度不同");
    }

    @Test
    @DisplayName("提供方不同（本地模型 vs API 模型）→ 拒绝")
    void differentProviderRejected() {
        // 知识库第 39 行点名："测试用本地、上线用 API" → 召回率腰斩。
        EmbeddingModelGuard guard = new EmbeddingModelGuard(
                INDEXED, new EmbeddingModelInfo("azure-openai", "bge-m3", 1024));
        assertThatThrownBy(guard::verify).isInstanceOf(EmbeddingModelMismatchException.class);
    }

    @Test
    @DisplayName("索引侧信息缺失 → 拒绝（无法证明向量是谁产的）")
    void missingIndexedInfoRejected() {
        EmbeddingModelGuard guard = new EmbeddingModelGuard(null, INDEXED);
        assertThat(guard.isConsistent()).isFalse();
        assertThatThrownBy(guard::verify)
                .isInstanceOf(EmbeddingModelMismatchException.class)
                .hasMessageContaining("索引侧模型信息缺失");
    }

    @Test
    @DisplayName("异常是受检异常：调用方必须显式处理，无法默默吞掉")
    void mismatchIsCheckedException() {
        // 用 Java 语言层面的判据，而不是"读代码觉得它像受检异常"：
        // 受检异常的直接父类必须是 Exception（而非 RuntimeException）。
        assertThat(EmbeddingModelMismatchException.class.getSuperclass())
                .isEqualTo(Exception.class);
        assertThat(RuntimeException.class.isAssignableFrom(EmbeddingModelMismatchException.class))
                .as("若它是 RuntimeException，调用方就能不处理地绕过 C-22 校验")
                .isFalse();
    }
}
