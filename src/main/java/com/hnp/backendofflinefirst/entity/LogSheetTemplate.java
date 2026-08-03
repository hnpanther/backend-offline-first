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
    @Column(name = "id")
    private Long id;
    @Column(name = "name", nullable = false)
    private String name;
    @Column(name = "description")
    private String description;
    @Column(name = "scope_type")
    private String scopeType;
    @Column(name = "scope_id")
    private Long scopeId;
    /** Assets must belong to this class (in addition to hierarchy scope). */
    @Column(name = "class_id", nullable = false)
    private Long classId;
    @Column(name = "operational_unit_id")
    private Long operationalUnitId;
    /**
     * Scope-picking rule, not an access rule. TRUE (default) restricts the scope to the
     * selected unit's own locations; FALSE lets it point anywhere in the plant, so a unit
     * can be made responsible for assets outside its locations. Either way the generated
     * work is reachable only through {@code log_sheets.operational_unit_id}.
     */
    @Column(name = "restrict_scope_to_unit", nullable = false)
    private Boolean restrictScopeToUnit = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_mode")
    private GenerationMode generationMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_unit")
    private RecurrenceUnit recurrenceUnit;

    @Column(name = "recurrence_every")
    private Integer recurrenceEvery;
    @Column(name = "schedule_start_at")
    private Long scheduleStartAt;
    @Column(name = "schedule_active")
    private Boolean scheduleActive;
    @Column(name = "next_run_at")
    private Long nextRunAt;
    @Column(name = "last_run_at")
    private Long lastRunAt;
    @Column(name = "completion_window_minutes")
    private Integer completionWindowMinutes;

    /** When false, no manual or scheduled log sheets may be generated from this template. */
    @Column(name = "active")
    private Boolean active = true;

    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
