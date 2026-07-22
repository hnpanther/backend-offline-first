package com.hnp.backendofflinefirst.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelUtilsImportLimitTest {

    @Test
    void assertWithinImportRowLimitAllowsEqualToMax() {
        assertThatCode(() -> ExcelUtils.assertWithinImportRowLimit(10_000, 10_000))
                .doesNotThrowAnyException();
    }

    @Test
    void assertWithinImportRowLimitRejectsOverMax() {
        assertThatThrownBy(() -> ExcelUtils.assertWithinImportRowLimit(10_001, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10001")
                .hasMessageContaining("10000");
    }
}
