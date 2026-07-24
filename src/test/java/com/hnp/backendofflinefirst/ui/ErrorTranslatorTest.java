package com.hnp.backendofflinefirst.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTranslatorTest {

    @Test
    void translatesFormDataValidationErrorsWithAssetIdFallback() {
        String fa = ErrorTranslator.toFa(
                "Form data validation failed (assetId=50): field 'temp': required field is missing");

        assertThat(fa).startsWith("داده‌های فرم معتبر نیست");
        assertThat(fa).contains("50");
        assertThat(fa).contains("اجباری");
    }

    @Test
    void translatesFormDataValidationErrorsWithAssetNameAndCode() {
        String fa = ErrorTranslator.toFa(
                "Form data validation failed (asset 'Pump A' / PUMP-01): field 'Temperature': required field is missing");

        assertThat(fa).startsWith("داده‌های فرم معتبر نیست");
        assertThat(fa).contains("Pump A");
        assertThat(fa).contains("PUMP-01");
        assertThat(fa).contains("Temperature");
        assertThat(fa).contains("اجباری");
        assertThat(fa).doesNotContain("assetId");
    }

    @Test
    void translatesJoinedFormDataValidationErrors() {
        String fa = ErrorTranslator.toFa(
                "Form data validation failed (asset 'Bar Asset' / B-2): field 'Bar': must be a number"
                        + " | Form data validation failed (asset 'Pump' / P-6): field 'Temperature': required field is missing");

        assertThat(fa).startsWith("داده‌های فرم معتبر نیست");
        assertThat(fa).contains("Bar Asset");
        assertThat(fa).contains("باید عدد باشد");
        assertThat(fa).contains("Pump");
        assertThat(fa).contains("اجباری");
    }

    @Test
    void translatesForeignLogSheetAssetErrorToPersian() {
        String fa = ErrorTranslator.toFa("Asset(s) not part of this log sheet (ids: 15, 48).");

        assertThat(fa).contains("15");
        assertThat(fa).contains("48");
        assertThat(fa).contains("لاگ");
    }

    @Test
    void translatesUserContactFieldLengthErrors() {
        assertThat(ErrorTranslator.toFa("National code must be at most 15 characters."))
                .contains("کد ملی");
        assertThat(ErrorTranslator.toFa("Phone number must be at most 15 characters."))
                .contains("شماره تماس");
        assertThat(ErrorTranslator.toFa("NFC tag must be at most 50 characters."))
                .contains("NFC");
    }
}
