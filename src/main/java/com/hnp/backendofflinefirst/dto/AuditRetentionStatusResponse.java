package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.ui.AuditRetentionViewHelper;

/** JSON view of audit retention progress with Persian user message. */
public record AuditRetentionStatusResponse(
        String status,
        long deletedCount,
        String message
) {
    public static AuditRetentionStatusResponse from(AuditRetentionProgress progress) {
        return new AuditRetentionStatusResponse(
                progress.getStatus().name(),
                progress.getDeletedCount(),
                AuditRetentionViewHelper.messageFa(progress));
    }
}
