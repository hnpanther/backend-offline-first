package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class AlarmWebhookEndpointRuleId implements Serializable {
    private Long endpointId;
    private Long alarmRuleId;
}
