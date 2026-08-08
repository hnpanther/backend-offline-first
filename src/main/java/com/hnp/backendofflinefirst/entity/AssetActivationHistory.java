package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AssetActivationChangeType;
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
 * One change to whether an asset takes part in log-sheet generation.
 *
 * <p>Append-only, and deliberately <b>separate from {@link AssetStatusHistory}</b>. The two
 * describe different things: {@code status} is a reading about the equipment that a log sheet
 * sets and a reversal can take back, while {@code active} is a registry decision that no sheet
 * ever touches. Keeping them in one table would put activation rows in front of the reversal
 * lookup, where a single mis-scoped query could make undoing a log sheet switch assets on and
 * off. They are merged only for display.
 */
@Entity
@Table(name = "asset_activation_history")
@Getter
@Setter
public class AssetActivationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** {@code null} on the CREATED row — the record began here, it was not switched off before. */
    @Column(name = "was_active")
    private Boolean wasActive;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private AssetActivationChangeType changeType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "changed_at", nullable = false)
    private Long changedAt;
}
