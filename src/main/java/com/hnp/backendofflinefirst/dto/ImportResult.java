package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ImportResult {
    private int successCount;
    private int errorCount;
    private final List<String> errors = new ArrayList<>();

    public void addError(int row, String message) {
        errors.add("ردیف " + row + ": " + message);
        errorCount++;
    }

    public void addSuccess() {
        successCount++;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public String summary() {
        if (!hasErrors()) {
            return successCount + " ردیف با موفقیت وارد شد.";
        }
        return "موفق: " + successCount + " | ناموفق: " + errorCount;
    }
}
