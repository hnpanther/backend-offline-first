package com.hnp.backendofflinefirst.ui;

import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.dto.RecordSubmitResult;

import java.util.List;

/** Localizes English service messages to Persian for API responses. */
public final class ApiResponseSupport {

    private ApiResponseSupport() {}

    public static List<LogSheetSubmitResult> localizeLogSheetSubmitResults(List<LogSheetSubmitResult> results) {
        if (results == null) {
            return List.of();
        }
        return results.stream().map(ApiResponseSupport::localize).toList();
    }

    public static LogSheetSubmitResult localize(LogSheetSubmitResult result) {
        if (result == null || result.getError() == null) {
            return result;
        }
        return new LogSheetSubmitResult(
                result.getLocalId(),
                result.getServerId(),
                ErrorTranslator.toFa(result.getError()),
                result.getOutcome());
    }

    public static List<RecordSubmitResult> localizeRecordSubmitResults(List<RecordSubmitResult> results) {
        if (results == null) {
            return List.of();
        }
        return results.stream().map(ApiResponseSupport::localize).toList();
    }

    public static RecordSubmitResult localize(RecordSubmitResult result) {
        if (result == null || result.getError() == null) {
            return result;
        }
        return new RecordSubmitResult(
                result.getLocalId(),
                result.getServerId(),
                ErrorTranslator.toFa(result.getError()));
    }
}
