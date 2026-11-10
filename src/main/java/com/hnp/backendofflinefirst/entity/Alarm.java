package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AlarmCloseReason;
import com.hnp.backendofflinefirst.domain.AlarmPriority;
import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * One alarm condition — one asset, one rule — and its lifecycle.
 *
 * <p><b>Optimistically locked.</b> Two writers touch a live alarm: the evaluation, when a new
 * round arrives, and a supervisor acknowledging or closing it. Without a version a supervisor's
 * «closed» could be overwritten by an evaluation that read the row a moment earlier and wrote it
 * back OPEN. With one, the loser fails cleanly: the supervisor is asked to look again, and the
 * evaluation is retried from its queue against the row as it now stands.
 *
 * <p>Labels ({@code fieldLabel}, {@code fieldUnit}, {@code assetName}) are frozen when the alarm is
 * raised, for the reason a log sheet freezes its schema: renaming a parameter next month must not
 * change what an alarm in last month's history appears to have been about.
 */
@Entity
@Table(name = "alarms")
@Data
public class Alarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "alarm_rule_id", nullable = false)
    private Long alarmRuleId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    /** With {@code assetId}, the alarm's identity (V18): one live alarm per parameter per asset. */
    @Column(name = "field_definition_id", nullable = false)
    private Long fieldDefinitionId;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Column(name = "field_label")
    private String fieldLabel;

    @Column(name = "field_unit", length = 100)
    private String fieldUnit;

    @Column(name = "asset_name")
    private String assetName;

    /** The unit responsible for the round whose reading most recently kept this alarm true. */
    @Column(name = "operational_unit_id")
    private Long operationalUnitId;

    /** That round. */
    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private AlarmState state;

    /** The worst severity among the readings behind it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private FieldValidationSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private AlarmPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_severity", nullable = false)
    private FieldValidationSeverity triggerSeverity;

    @Column(name = "consecutive_readings", nullable = false)
    private int consecutiveReadings;

    @Column(name = "clear_after_readings", nullable = false)
    private int clearAfterReadings;

    @Column(name = "expected_action", length = 1000)
    private String expectedAction;

    @Column(name = "occurrence_count", nullable = false)
    private int occurrenceCount;

    /** When the reading that satisfied the condition was taken — device time. */
    @Column(name = "condition_met_at", nullable = false)
    private Long conditionMetAt;

    @Column(name = "first_reading_at", nullable = false)
    private Long firstReadingAt;

    /** The newest breaching reading. For a closed alarm, the watermark: see AlarmCloseReason. */
    @Column(name = "last_reading_at", nullable = false)
    private Long lastReadingAt;

    @Column(name = "last_value", length = 1000)
    private String lastValue;

    @Column(name = "returned_to_normal_at")
    private Long returnedToNormalAt;

    @Column(name = "acknowledged_by_user_id")
    private Long acknowledgedByUserId;

    @Column(name = "acknowledged_at")
    private Long acknowledgedAt;

    @Column(name = "ack_comment", length = 1000)
    private String ackComment;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "closed_at")
    private Long closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 30)
    private AlarmCloseReason closeReason;

    @Column(name = "close_comment", length = 1000)
    private String closeComment;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isLive() {
        return state != null && state.isLive();
    }
}
