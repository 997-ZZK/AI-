package com.aiflow.engine.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorsTest {

    @Test
    void cosineOfIdenticalVectorsIsOne() {
        float[] vector = {0.2f, -0.5f, 0.8f};
        assertThat(Vectors.cosine(vector, vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void cosineOfOrthogonalVectorsIsZero() {
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{0, 1})).isZero();
    }

    @Test
    void cosineHandlesDimensionMismatchAndZeroVectors() {
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{1, 0, 0})).isZero();
        assertThat(Vectors.cosine(new float[]{0, 0}, new float[]{1, 1})).isZero();
        assertThat(Vectors.cosine(null, new float[]{1})).isZero();
    }

    @Test
    void normalizeProducesUnitLength() {
        float[] normalized = Vectors.normalize(new float[]{3, 4});
        assertThat(Math.hypot(normalized[0], normalized[1])).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void literalRoundTripKeepsValues() {
        float[] vector = {0.125f, -0.5f, 1f};
        String literal = Vectors.toLiteral(vector);
        assertThat(literal).startsWith("[").endsWith("]");
        float[] parsed = Vectors.parse(literal);
        assertThat(parsed).hasSize(3);
        assertThat(parsed[1]).isCloseTo(-0.5f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    @Test
    void parseRejectsBrokenLiteral() {
        assertThat(Vectors.parse("not-a-vector")).isNull();
        assertThat(Vectors.parse("[1,abc]")).isNull();
        assertThat(Vectors.parse(null)).isNull();
    }

    @Test
    void requireDimensionsFailsFastWithClearMessage() {
        assertThatThrownBy(() -> Vectors.requireDimensions(new float[8], 1024, "mock-embedding"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度不符")
                .hasMessageContaining("1024");
    }
}
