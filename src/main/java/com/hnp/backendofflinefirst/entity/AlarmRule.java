package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AlarmPriority;
import com.hnp.backendofflinefirst.domain.AlarmRuleScope;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which parameters raise an alarm, and when — the rationalisation step of ISA-18.2, written down.
 *
 * <p>Since V18 a rule covers one parameter, many, or — as the single {@link AlarmRuleScope#ALL_FIELDS}
 * default — every parameter no other rule lists. A parameter governed by no rule never raises an
 * alarm, however far out of range it reads: its breaches stay where they always were, in the
 * exception report.
 *
 * <p>The parameter list is <em>not</em> mapped: it lives in {@code alarm_rule_fields} as
 * {@link AlarmRuleField} rows, and {@code AlarmRuleService.all()} attaches it. No entity here
 * declares a JPA association (open-session-in-view is off; {@code OpenInViewDisabledIntegrationTest}),
 * and an element collection is one.
 */
@Entity
@Table(name = "alarm_rules")
@Data
public class AlarmRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private AlarmRuleScope scope;

    /**
     * The parameters of a {@code FIELDS} rule, as {@code AlarmRuleService} attached them; always
     * empty for the default rule. Not persisted from here — writing it changes nothing in the
     * database; {@code AlarmRuleService} writes the {@link AlarmRuleField} rows.
     */
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Set<Long> fieldDefinitionIds = new LinkedHashSet<>();

    /** {@code WARNING} (warning or worse) or {@code DANGER}. Never {@code OK}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_severity", nullable = false)
    private FieldValidationSeverity triggerSeverity;

    @Column(name = "consecutive_readings", nullable = false)
    private int consecutiveReadings = 1;

    @Column(name = "clear_after_readings", nullable = false)
    private int clearAfterReadings = 1;

    @Column(name = "window_days")
    private Integer windowDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private AlarmPriority priority = AlarmPriority.MEDIUM;

    @Column(name = "expected_action", nullable = false, length = 1000)
    private String expectedAction;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Column(name = "updated_at", nullable = false)
    private Long updatedAt;

    public boolean isDefaultRule() {
        return scope == AlarmRuleScope.ALL_FIELDS;
    }
}
