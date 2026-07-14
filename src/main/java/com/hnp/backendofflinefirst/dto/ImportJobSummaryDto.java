package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.ImportEntityType;
import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.entity.ImportJob;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;

public record ImportJobSummaryDto(
        String jobUuid,
        String entityType,
        String entityLabel,
        String status,
        String statusLabel,
        int progressPercent,
        int processedRows,
        int totalRows,
        int successCount,
        int errorCount,
        String fileName,
        String errorMessage,
        long createdAt,
        Long completedAt,
        boolean active
) {
    public static ImportJobSummaryDto from(ImportJob job) {
        String label = ImportEntityType.fromCode(job.getEntityType())
                .map(ImportEntityType::getFaLabel)
                .orElse(job.getEntityType());
        return new ImportJobSummaryDto(
                job.getJobUuid(),
                job.getEntityType(),
                label,
                job.getStatus().name(),
                job.getStatus().faLabel(),
                job.progressPercent(),
                job.getProcessedRows(),
                job.getTotalRows(),
                job.getSuccessCount(),
                job.getErrorCount(),
                job.getFileName(),
                job.getErrorMessage() != null ? ErrorTranslator.toFa(job.getErrorMessage()) : null,
                job.getCreatedAt(),
                job.getCompletedAt(),
                job.getStatus().isActive()
        );
    }
}
