package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.dto.RecordSubmitResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseSupportTest {

    @Test
    void localizesLogSheetSubmitError() {
        LogSheetSubmitResult raw = new LogSheetSubmitResult(
                "local-1", 1L, "This log sheet completion deadline has passed.", "EXPIRED");

        LogSheetSubmitResult localized = ApiResponseSupport.localize(raw);

        assertThat(localized.getError()).isEqualTo("مهلت تکمیل این لاگ‌شیت به پایان رسیده است.");
        assertThat(localized.getOutcome()).isEqualTo("EXPIRED");
    }

    @Test
    void leavesSuccessfulLogSheetSubmitUntouched() {
        LogSheetSubmitResult raw = new LogSheetSubmitResult("local-1", 1L, null, "SUBMITTED");

        LogSheetSubmitResult localized = ApiResponseSupport.localize(raw);

        assertThat(localized.getError()).isNull();
        assertThat(localized.getOutcome()).isEqualTo("SUBMITTED");
    }

    @Test
    void localizesRecordSubmitError() {
        RecordSubmitResult raw = new RecordSubmitResult("rec-1", null, "User not found.");

        RecordSubmitResult localized = ApiResponseSupport.localize(raw);

        assertThat(localized.getError()).isEqualTo("کاربر یافت نشد.");
    }

    @Test
    void localizesBatchResults() {
        List<LogSheetSubmitResult> localized = ApiResponseSupport.localizeLogSheetSubmitResults(List.of(
                new LogSheetSubmitResult("a", 1L, "This log sheet cannot be claimed.", "ERROR")));

        assertThat(localized.get(0).getError()).isEqualTo("این لاگ‌شیت قابل پیک‌آپ نیست.");
    }
}
