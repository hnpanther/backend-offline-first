package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Reusable definition for round log-sheet inspections. Owned by an operational
 * unit (controls who may edit it). When {@code generationMode = SCHEDULED} and
 * {@code scheduleActive = true}, the scheduler generates a log sheet every
 * {@code recurrenceEvery} × {@code recurrenceUnit}, giving each sheet a
 * {@code completionWindowMinutes} deadline.
 */
@Entity
@Table(name = "log_sheet_templates")
@Data
public class LogSheetTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String description;
    private String scopeType;
    private Long scopeId;
    /** Assets must belong to this class (in addition to hierarchy scope). */
    private Long classId;
    private Long operationalUnitId;

    @Enumerated(EnumType.STRING)
    private GenerationMode generationMode;

    @Enumerated(EnumType.STRING)
    private RecurrenceUnit recurrenceUnit;

    private Integer recurrenceEvery;
    private Long scheduleStartAt;
    private Boolean scheduleActive;
    private Long nextRunAt;
    private Long lastRunAt;
    private Integer completionWindowMinutes;

    /** When false, no manual or scheduled log sheets may be generated from this template. */
    private Boolean active = true;

    private Long createdAt;
    private Long updatedAt;
}
