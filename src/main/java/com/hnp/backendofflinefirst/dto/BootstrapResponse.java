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
    /**
     * Policies the tablet must follow but does not own, carried on the same call and for the
     * same reason as the ceilings above: bootstrap runs on every reconnect, so a rule changed
     * centrally takes effect without anyone touching a device.
     *
     * <p>Kept separate from {@code attachmentLimits} because these are not ceilings — reading
     * "how many photos may I attach" and "must a scan verify the chip serial" as one object
     * invites the next change to land in the wrong half.
     */
    private MobilePolicyDto mobilePolicy;

    @Data
    @Builder
    public static class AttachmentLimitsDto {
        private int maxImagesPerField;
        private int maxAudiosPerField;
        private int maxVideosPerField;
        private int maxAudioSeconds;
        private int maxVideoSeconds;
    }

    @Data
    @Builder
    public static class MobilePolicyDto {
        /** Admin-editable in the Settings page. Off returns tablets to plain photo capture. */
        private boolean imageAnnotationEnabled;
        /**
         * Property-backed ({@code app.nfc.strict-serial-match}), never admin-editable — see the
         * reasoning next to that property. The device mirrors it and no longer decides for itself.
         */
        private boolean nfcStrictSerialMatch;
        /**
         * Whether typing a tag id by hand is available at all, site-wide.
         *
         * <p>An **AND** with the operator's own permission, never an OR: off means nobody may type
         * a tag however privileged, and the asset must be scanned or opened through an NFC fault
         * report. The device cannot decide this for itself — that is the point of carrying it here.
         */
        private boolean nfcManualEntryEnabled;
    }
}
