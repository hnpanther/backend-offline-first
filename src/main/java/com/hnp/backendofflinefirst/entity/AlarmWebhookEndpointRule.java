package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One alarm rule a SELECTED webhook receiver is for (V21, {@code alarm_webhook_endpoint_rules}).
 *
 * <p>A plain row rather than a collection on the endpoint (AGENTS.md #192), written row by row so
 * every change is audited (#193): which alarms leave the plant, and to whom, is exactly the kind
 * of decision {@code audit_log} is for.
 */
@Entity
@Table(name = "alarm_webhook_endpoint_rules")
@IdClass(AlarmWebhookEndpointRuleId.class)
@Data
@NoArgsConstructor
public class AlarmWebhookEndpointRule {

    @Id
    @Column(name = "endpoint_id")
    private Long endpointId;

    @Id
    @Column(name = "alarm_rule_id")
    private Long alarmRuleId;

    public AlarmWebhookEndpointRule(Long endpointId, Long alarmRuleId) {
        this.endpointId = endpointId;
        this.alarmRuleId = alarmRuleId;
    }
}
