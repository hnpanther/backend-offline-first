package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BulkDeleteResult {
    private int successCount;
    private int errorCount;
    private final List<BulkDeleteError> errors = new ArrayList<>();

    public void addSuccess() {
        successCount++;
    }

    public void addError(Long id, String message) {
        errors.add(new BulkDeleteError(id, message));
        errorCount++;
    }

    public record BulkDeleteError(Long id, String message) {}
}
