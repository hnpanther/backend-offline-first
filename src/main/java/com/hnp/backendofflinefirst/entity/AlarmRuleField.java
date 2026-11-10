package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One parameter listed by a specific {@link AlarmRule} (V18, {@code alarm_rule_fields}).
 *
 * <p>A plain row rather than an {@code @ElementCollection} on the rule: no entity in this codebase
 * declares a JPA association, because open-session-in-view is off and a lazy one is a
 * {@code LazyInitializationException} waiting for a template to touch it
 * ({@code OpenInViewDisabledIntegrationTest}). {@code AlarmRuleService} loads these beside the
 * rules and hands each rule its list.
 *
 * <p>Audited like the rule itself: adding a parameter to a rule, or taking one out, is a
 * deliberate decision about what raises an alarm.
 */
@Entity
@Table(name = "alarm_rule_fields")
@IdClass(AlarmRuleFieldId.class)
@Data
@NoArgsConstructor
public class AlarmRuleField {

    @Id
    @Column(name = "alarm_rule_id")
    private Long alarmRuleId;

    /** Unique across the table ({@code uq_alarm_rule_fields_field}): one rule per parameter. */
    @Id
    @Column(name = "field_definition_id")
    private Long fieldDefinitionId;

    public AlarmRuleField(Long alarmRuleId, Long fieldDefinitionId) {
        this.alarmRuleId = alarmRuleId;
        this.fieldDefinitionId = fieldDefinitionId;
    }
}
