package com.hnp.backendofflinefirst.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTranslatorTest {

    @Test
    void translatesForeignLogSheetAssetErrorToPersian() {
        String fa = ErrorTranslator.toFa("Asset(s) not part of this log sheet (ids: 15, 48).");

        assertThat(fa).contains("15");
        assertThat(fa).contains("48");
        assertThat(fa).contains("لاگ");
    }
}
