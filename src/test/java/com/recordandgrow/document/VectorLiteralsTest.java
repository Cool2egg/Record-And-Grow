package com.recordandgrow.document;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量字面量：小数分隔符与 Locale 无关，非有限值直接拒绝。
 */
class VectorLiteralsTest {

    @Test
    void shouldFormatWithDotInAnyLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(VectorLiterals.toLiteral(new float[]{0.5f, 1.25f, -3f}))
                    .isEqualTo("[0.5,1.25,-3.0]");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void emptyVectorShouldFail() {
        assertThatThrownBy(() -> VectorLiterals.toLiteral(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonFiniteValueShouldFail() {
        assertThatThrownBy(() -> VectorLiterals.toLiteral(new float[]{Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorLiterals.toLiteral(new float[]{Float.POSITIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
