package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class FormDefinitionUnitId implements Serializable {
    private Long formDefinitionId;
    private Long operationalUnitId;
}
