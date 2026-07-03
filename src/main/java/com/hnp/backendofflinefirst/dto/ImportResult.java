package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ImportResult {
    private int successCount;
    private int errorCount;
    private final List<ImportError> errors = new ArrayList<>();

    public void addError(int row, String englishMessage) {
        errors.add(new ImportError(row, englishMessage));
        errorCount++;
    }

    public void addSuccess() {
        successCount++;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public void clearSuccessCount() {
        successCount = 0;
    }
}
