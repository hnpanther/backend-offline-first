package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AlarmEventType;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** One line of an alarm's timeline. Append-only. */
@Entity
@Table(name = "alarm_events")
@Data
public class AlarmEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "alarm_id", nullable = false)
    private Long alarmId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private AlarmEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Long occurredAt;

    /** Null for anything the system did on its own. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 10)
    private FieldValidationSeverity severity;

    @Column(name = "value_text", length = 1000)
    private String valueText;

    @Column(name = "comment", length = 1000)
    private String comment;
}
