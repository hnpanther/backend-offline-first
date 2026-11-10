package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * «This round changed; re-judge its assets.» Written inside the transaction that changed it,
 * processed after that transaction has committed — see V17 for why the two are separated.
 */
@Entity
@Table(name = "alarm_evaluation_requests")
@Data
public class AlarmEvaluationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_id", nullable = false)
    private Long logSheetId;

    /**
     * COMPLETED, VOIDED or RESTORED. Decides which of the round's readings can still change an
     * alarm: a void can raise one only by taking out a normal reading (AlarmEvaluationService).
     */
    @Column(name = "reason", nullable = false, length = 30)
    private String reason;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Long nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
