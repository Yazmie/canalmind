package com.canalmind.rag.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VectorCodec} 与 {@link EmbeddingModelInfo} 的测试。
 *
 * <p>重点不在"happy path 能跑"，而在**把静默失效挡在编码阶段**：
 * NaN/Infinity 能被 pgvector 接受，却会让距离计算变成 NaN，
 * 于是那一行永远检索不到——不报错、只是结果莫名变少。
 */
class VectorCodecTest {

    @Nested
    @DisplayName("pgvector 字面量编码")
    class Literal {

        @Test
        void encodesSmallVectorInPgVectorFormat() {
            String s = VectorCodec.toPgVectorLiteral(new float[] {1.5f, -2f, 0f});
            assertThat(s).isEqualTo("[1.5,-2.0,0.0]");
        }

        @Test
        void encodesFullDimension1024WithCorrectShape() {
            float[] v = new float[1024];
            for (int i = 0; i < v.length; i++) {
                v[i] = i / 1024f;
            }
            String s = VectorCodec.toPgVectorLiteral(v);
            // 方括号首尾 + 1023 个逗号 + 1024 个分量
            assertThat(s).startsWith("[").endsWith("]");
            assertThat(s.chars().filter(c -> c == ',').count()).isEqualTo(1023);
        }

        @Test
        void roundTripsThroughParseFloat() {
            // 编码必须无损：Float.toString 的输出可被 parseFloat 精确还原
            float[] v = {0.1f, 1f / 3f, -1e-7f, 123456.78f};
            String s = VectorCodec.toPgVectorLiteral(v);
            String inner = s.substring(1, s.length() - 1);
            String[] parts = inner.split(",");
            assertThat(parts).hasSize(v.length);
            for (int i = 0; i < v.length; i++) {
                assertThat(Float.parseFloat(parts[i])).isEqualTo(v[i]);
            }
        }

        @Test
        void rejectsEmptyVector() {
            assertThatThrownBy(() -> VectorCodec.toPgVectorLiteral(new float[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("向量为空");
            assertThatThrownBy(() -> VectorCodec.toPgVectorLiteral(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNaNWithExplainableMessage() {
            float[] v = {1f, Float.NaN, 3f};
            assertThatThrownBy(() -> VectorCodec.toPgVectorLiteral(v))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NaN")
                    .hasMessageContaining("第 1 个");
        }

        @Test
        void rejectsInfinityBecauseItPoisonsDistancesSilently() {
            float[] v = {1f, Float.POSITIVE_INFINITY};
            assertThatThrownBy(() -> VectorCodec.toPgVectorLiteral(v))
                    .isInstanceOf(IllegalArgumentException.class)
                    // 消息里必须点名 NaN/Infinity 这两类非法值，而不是只说"非法"
                    .hasMessageContaining("NaN/Infinity")
                    .hasMessageContaining("永远检索不到");
        }
    }

    @Nested
    @DisplayName("范数校验（内积排序的前提）")
    class Norm {

        @Test
        void unitVectorHasNormOne() {
            assertThat(VectorCodec.norm(new float[] {1f, 0f, 0f})).isEqualTo(1.0);
        }

        @Test
        void diagonalVectorHasExpectedNorm() {
            // 1/√2 分量 → 范数 1
            double c = 1.0 / Math.sqrt(2);
            float[] v = {(float) c, (float) c};
            // ⚠️ 容差取 1e-6 而不是 1e-9：分量是 float，(1/√2) 存成 float 后平方求和的
            //    误差量级是 1e-8（实测 0.999999982885729，偏差 1.7e-8）。
            //    1e-9 会把"浮点表示误差"误判成"实现错误"——这个断言失败过一次，
            //    当时的第一反应是去查 VectorCodec.norm，其实 norm 是对的。
            //    真正该断言的是"范数在 1 附近"，而不是"double 级精确等于 1"；
            //    入库侧的归一化判定用的也是 1e-3 这种量级的容差（见 VectorCodec.isL2Normalized）。
            assertThat(VectorCodec.norm(v)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
            assertThat(VectorCodec.isL2Normalized(v, 1e-3)).isTrue();
        }

        @Test
        void threeFourFiveTriangleIsNotNormalized() {
            assertThat(VectorCodec.norm(new float[] {3f, 4f})).isEqualTo(5.0);
            assertThat(VectorCodec.isL2Normalized(new float[] {3f, 4f}, 1e-3)).isFalse();
        }
    }

    @Nested
    @DisplayName("模型身份（C-22 配套硬约束的基础）")
    class ModelIdentity {

        @Test
        void profileNameEncodesAllThreeIdentityDimensions() {
            EmbeddingModelInfo info = new EmbeddingModelInfo("ollama", "bge-m3", 1024);
            assertThat(info.profileName()).isEqualTo("ollama:bge-m3:1024");
        }

        @Test
        void sameTripleIsCompatible() {
            EmbeddingModelInfo a = new EmbeddingModelInfo("ollama", "bge-m3", 1024);
            EmbeddingModelInfo b = new EmbeddingModelInfo("ollama", "bge-m3", 1024);
            assertThat(a.isCompatibleWith(b)).isTrue();
        }

        @Test
        void sameDimensionButDifferentModelIsIncompatible() {
            // 这正是知识库第 33 行点名的场景：维度相同 → 能放进同一索引，
            // 但相似度分数不可比，召回结果不能信。
            EmbeddingModelInfo indexed = new EmbeddingModelInfo("ollama", "bge-m3", 1024);
            EmbeddingModelInfo querying = new EmbeddingModelInfo("openai", "text-embedding-3-small", 1024);
            assertThat(querying.isCompatibleWith(indexed)).isFalse();

            String msg = querying.describeMismatch(indexed);
            assertThat(msg).contains("不可比");
            assertThat(msg).contains("全量重建索引");
        }

        @Test
        void differentDimensionExplainsItCannotShareAnIndex() {
            EmbeddingModelInfo indexed = new EmbeddingModelInfo("ollama", "bge-m3", 1024);
            EmbeddingModelInfo querying = new EmbeddingModelInfo("ollama", "nomic-embed-text", 768);
            String msg = querying.describeMismatch(indexed);
            assertThat(msg).contains("维度不同").contains("768").contains("1024");
        }

        @Test
        void nullOtherIsNotCompatible() {
            assertThat(new EmbeddingModelInfo("ollama", "bge-m3", 1024).isCompatibleWith(null)).isFalse();
        }
    }
}
