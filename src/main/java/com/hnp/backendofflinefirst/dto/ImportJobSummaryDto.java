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
        boolean active,
        boolean stalled
) {
    /**
     * How long a RUNNING job may go without a progress tick before the UI offers to abandon it.
     * <p>
     * Deliberately far shorter than {@code app.import.stale-timeout-minutes}: the watchdog's
     * job is to be certain before it writes anything off, while this only decides whether to
     * show a button an administrator still has to confirm. Making the person wait fifteen
     * minutes for the automatic sweep — the very wait this whole feature exists to remove —
     * would be the wrong trade in the other direction. A healthy import ticks every 25 rows,
     * so two minutes of silence is already well outside normal.
     */
    private static final long STALLED_AFTER_MS = 120_000L;

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
                job.getStatus().isActive(),
                isStalled(job)
        );
    }

    private static boolean isStalled(ImportJob job) {
        if (job.getStatus() != ImportJobStatus.RUNNING) {
            return false;
        }
        // heartbeatAt is null for jobs from before that column existed and for one that died before its first
        // tick — startedAt then answers "has it been quiet too long?" just as well.
        long lastSign = job.getHeartbeatAt() != null ? job.getHeartbeatAt()
                : (job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt());
        return System.currentTimeMillis() - lastSign > STALLED_AFTER_MS;
    }
}
