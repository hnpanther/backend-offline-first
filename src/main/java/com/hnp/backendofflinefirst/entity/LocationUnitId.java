package com.hnp.backendofflinefirst.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class LocationUnitId implements Serializable {
    private Long locationId;
    private Long unitId;
}
