package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Lightweight mobile bootstrap payload: user context and accessible operational
 * units only — no plant hierarchy or asset registry.
 */
@Data
@Builder
public class BootstrapResponse {
    private Long serverTime;
    private Long userId;
    private List<OperationalUnit> operationalUnits;
    private Set<Long> accessibleUnitIds;
    private Set<Long> supervisorScopeUnitIds;
    private Long primaryUnitId;
    /**
     * Attachment ceilings, so a tablet enforces the same rules as the server.
     *
     * <p>Carried on bootstrap rather than fetched separately because bootstrap is the one call
     * the app already makes on every reconnect — which is exactly when a limit an administrator
     * changed in the panel should take effect on the device. The device never edits these.
     */
    private AttachmentLimitsDto attachmentLimits;

    @Data
    @Builder
    public static class AttachmentLimitsDto {
        private int maxImagesPerField;
        private int maxAudiosPerField;
        private int maxVideosPerField;
        private int maxAudioSeconds;
        private int maxVideoSeconds;
    }
}
