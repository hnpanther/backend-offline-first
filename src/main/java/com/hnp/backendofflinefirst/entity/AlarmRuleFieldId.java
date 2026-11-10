package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class AlarmRuleFieldId implements Serializable {
    private Long alarmRuleId;
    private Long fieldDefinitionId;
}
