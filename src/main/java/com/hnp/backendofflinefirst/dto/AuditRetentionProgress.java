package com.hnp.backendofflinefirst.dto;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory status of a manual audit retention purge (single job at a time). */
@Getter
public class AuditRetentionProgress {

    public enum Status {
        IDLE, RUNNING, COMPLETED, CANCELLED, FAILED
    }

    private final Status status;
    private final long deletedCount;
    private final int retentionDays;
    private final Long startedAt;
    private final Long finishedAt;
    private final String message;
    final AtomicBoolean cancelRequested;

    private AuditRetentionProgress(Status status, long deletedCount, int retentionDays,
                                   Long startedAt, Long finishedAt, String message,
                                   AtomicBoolean cancelRequested) {
        this.status = status;
        this.deletedCount = deletedCount;
        this.retentionDays = retentionDays;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
        this.cancelRequested = cancelRequested;
    }

    public static AuditRetentionProgress idle() {
        return new AuditRetentionProgress(Status.IDLE, 0, 0, null, null, null, new AtomicBoolean(false));
    }

    public static AuditRetentionProgress running(int retentionDays) {
        return new AuditRetentionProgress(Status.RUNNING, 0, retentionDays,
                System.currentTimeMillis(), null, null, new AtomicBoolean(false));
    }

    public static AuditRetentionProgress completed(long deleted, int retentionDays, long startedAt) {
        return new AuditRetentionProgress(Status.COMPLETED, deleted, retentionDays, startedAt,
                System.currentTimeMillis(),
                deleted + " audit rows older than " + retentionDays + " days were deleted.",
                new AtomicBoolean(false));
    }

    public static AuditRetentionProgress cancelled(long deleted, int retentionDays, long startedAt) {
        return new AuditRetentionProgress(Status.CANCELLED, deleted, retentionDays, startedAt,
                System.currentTimeMillis(),
                "Operation stopped — " + deleted + " rows deleted so far.",
                new AtomicBoolean(true));
    }

    public static AuditRetentionProgress failed(long deleted, int retentionDays, long startedAt, String error) {
        return new AuditRetentionProgress(Status.FAILED, deleted, retentionDays, startedAt,
                System.currentTimeMillis(), error, new AtomicBoolean(false));
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public AuditRetentionProgress withDeletedCount(long deleted) {
        return new AuditRetentionProgress(status, deleted, retentionDays, startedAt, finishedAt, message, cancelRequested);
    }
}
