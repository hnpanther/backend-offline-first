package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A proposed change to an asset's operational status, awaiting a supervisor's decision.
 *
 * <p>A reading taken in the field is a claim, not a decision: an operator noting a pump as
 * out of service should not silently retag the asset for everyone. A completed log sheet whose
 * status reading differs from the asset's current status raises one of these, and <b>only an
 * approval moves {@link AssetEntry#getStatus()}</b>.
 *
 * <p>{@link #previousStatus} is context — what the asset showed when the request was filed.
 * {@link #appliedOldStatus} is the value approval actually replaced, which can differ if
 * something moved in between, and it is what an undo restores. Storing it rather than
 * re-deriving it is what makes the undo exact.
 */
@Entity
@Table(name = "asset_status_change_requests")
@Getter
@Setter
public class AssetStatusChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "requested_status")
    private String requestedStatus;

    /** What the asset showed when this was filed. Context for the reader, never used to undo. */
    @Column(name = "previous_status")
    private String previousStatus;

    /** What approval replaced; null until approved. An undo restores exactly this. */
    @Column(name = "applied_old_status")
    private String appliedOldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AssetStatusRequestStatus status = AssetStatusRequestStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private AssetStatusSource source = AssetStatusSource.LOG_SHEET;

    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Column(name = "log_sheet_entry_id")
    private Long logSheetEntryId;

    /** The class field key that produced the reading — "status", "Status", whatever it was. */
    @Column(name = "field_key")
    private String fieldKey;

    /**
     * When the reading behind this request was actually taken (device time on the log sheet
     * entry), or the filing time for a manual request.
     *
     * <p>Approval uses this as the history row's {@code changedAt}, so the asset timeline shows
     * when the equipment was <em>observed</em> rather than when a supervisor got round to
     * signing it off. A status noted at 08:15 and approved at 16:40 belongs at 08:15.
     */
    @Column(name = "reading_recorded_at")
    private Long readingRecordedAt;

    /** Why the change is being asked for; supplied on a manual request. */
    @Column(name = "reason")
    private String reason;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Column(name = "requested_at", nullable = false)
    private Long requestedAt;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private Long decidedAt;

    /** The supervisor's note on approving, rejecting or undoing. */
    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;
}
