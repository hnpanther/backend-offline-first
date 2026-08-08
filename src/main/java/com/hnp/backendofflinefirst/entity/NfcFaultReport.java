package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A reported NFC scan failure for one asset within one log sheet (tag missing,
 * broken, or the device's NFC hardware itself unusable). Insert-only — never
 * edited or deleted except by ADMIN via the web panel. Its mere existence for a
 * given {@code (logSheetId, assetId)} pair is what unlocks the manual-entry
 * fallback; multiple reports for the same pair are allowed.
 */
@Entity
@Table(name = "nfc_fault_reports")
@Data
public class NfcFaultReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_id")
    private Long logSheetId;
    @Column(name = "asset_id")
    private Long assetId;
    @Column(name = "operational_unit_id")
    private Long operationalUnitId;

    @Column(name = "reported_by_user_id")
    private Long reportedByUserId;
    @Column(name = "reported_by_name")
    private String reportedByName;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ActionSource source;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NfcFaultReportStatus status = NfcFaultReportStatus.OPEN;

    /** Who marked it reviewed — recorded so "handled" is attributable, not anonymous. */
    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private Long reviewedAt;

    /** When the report was actually filed (device time offline, request time on web). */
    @Column(name = "created_at", nullable = false)
    private Long createdAt;
    /** Server receive time; equals {@code createdAt} for web-originated reports. */
    @Column(name = "synced_at")
    private Long syncedAt;

    /** Mobile idempotency key for replayed offline batch submits. */
    @Column(name = "client_action_id")
    private String clientActionId;
    /** Mobile-generated correlation id for offline-created reports. */
    @Column(name = "local_id")
    private String localId;
}
