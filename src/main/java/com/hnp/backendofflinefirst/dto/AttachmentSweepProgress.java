package com.hnp.backendofflinefirst.dto;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory status of an orphan-file sweep (single job at a time). */
@Getter
public class AttachmentSweepProgress {

    public enum Status {
        IDLE, RUNNING, COMPLETED, CANCELLED, FAILED
    }

    private final Status status;
    /** Files walked under the storage root. */
    private final long scannedCount;
    /** Files with no matching row and old enough to delete. */
    private final long deletedCount;
    private final long reclaimedBytes;
    /**
     * Rows whose file is missing — reported, never "fixed".
     *
     * <p>This is the mirror-image problem and it is a genuine integrity signal: a row pointing
     * at nothing means the bytes were lost (a partial restore, a manual tidy-up). Deleting such
     * rows automatically would destroy the only remaining evidence that something went missing.
     */
    private final long missingFileRowCount;
    private final int graceHours;
    private final Long startedAt;
    private final Long finishedAt;
    private final String message;
    final AtomicBoolean cancelRequested;

    private AttachmentSweepProgress(Status status, long scannedCount, long deletedCount,
                                    long reclaimedBytes, long missingFileRowCount, int graceHours,
                                    Long startedAt, Long finishedAt, String message,
                                    AtomicBoolean cancelRequested) {
        this.status = status;
        this.scannedCount = scannedCount;
        this.deletedCount = deletedCount;
        this.reclaimedBytes = reclaimedBytes;
        this.missingFileRowCount = missingFileRowCount;
        this.graceHours = graceHours;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
        this.cancelRequested = cancelRequested;
    }

    public static AttachmentSweepProgress idle() {
        return new AttachmentSweepProgress(Status.IDLE, 0, 0, 0, 0, 0, null, null, null,
                new AtomicBoolean(false));
    }

    public static AttachmentSweepProgress running(int graceHours) {
        return new AttachmentSweepProgress(Status.RUNNING, 0, 0, 0, 0, graceHours,
                System.currentTimeMillis(), null, null, new AtomicBoolean(false));
    }

    public static AttachmentSweepProgress completed(AttachmentSweepProgress from) {
        return new AttachmentSweepProgress(Status.COMPLETED, from.scannedCount, from.deletedCount,
                from.reclaimedBytes, from.missingFileRowCount, from.graceHours, from.startedAt,
                System.currentTimeMillis(),
                from.deletedCount + " orphan file(s) removed, " + from.scannedCount + " scanned.",
                new AtomicBoolean(false));
    }

    public static AttachmentSweepProgress cancelled(AttachmentSweepProgress from) {
        return new AttachmentSweepProgress(Status.CANCELLED, from.scannedCount, from.deletedCount,
                from.reclaimedBytes, from.missingFileRowCount, from.graceHours, from.startedAt,
                System.currentTimeMillis(),
                "Operation stopped — " + from.deletedCount + " file(s) removed so far.",
                new AtomicBoolean(true));
    }

    public static AttachmentSweepProgress failed(AttachmentSweepProgress from, String error) {
        return new AttachmentSweepProgress(Status.FAILED, from.scannedCount, from.deletedCount,
                from.reclaimedBytes, from.missingFileRowCount, from.graceHours, from.startedAt,
                System.currentTimeMillis(), error, new AtomicBoolean(false));
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public AttachmentSweepProgress withCounts(long scanned, long deleted, long bytes, long missing) {
        return new AttachmentSweepProgress(status, scanned, deleted, bytes, missing, graceHours,
                startedAt, finishedAt, message, cancelRequested);
    }
}
