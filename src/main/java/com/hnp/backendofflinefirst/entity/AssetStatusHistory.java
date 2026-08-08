package com.hnp.backendofflinefirst.entity;

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

import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;

/**
 * One change to an asset's operational status.
 *
 * <p>Append-only. The row records both the old and the new value, which is what makes a reversal
 * deterministic: undoing a completion restores {@code oldStatus} exactly, rather than trying to
 * re-derive "what it was before" from earlier history — a derivation that would be both slower
 * and wrong as soon as anything else touched the column in between.
 *
 * <p>{@code revertedAt} is stamped on an APPLIED row when its reversal happens, so the revert
 * pass finds its work in one indexed query rather than walking an asset's whole history. That
 * matters on a 50-asset sheet, where the alternative is 50 history scans.
 */
@Entity
@Table(name = "asset_status_history")
@Getter
@Setter
public class AssetStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "old_status")
    private String oldStatus;

    @Column(name = "new_status")
    private String newStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private AssetStatusChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private AssetStatusSource source = AssetStatusSource.LOG_SHEET;

    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Column(name = "log_sheet_entry_id")
    private Long logSheetEntryId;

    /** The field key that drove the change — "status", "Status", whatever the class declared. */
    @Column(name = "field_key")
    private String fieldKey;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "changed_at", nullable = false)
    private Long changedAt;

    /** Set once this APPLIED row has been undone; {@code null} means still in effect. */
    @Column(name = "reverted_at")
    private Long revertedAt;
}
