package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Which operational units are responsible for a location. A location may be owned by
 * several units (same shape as {@link UnitSupervisor} / {@link UnitOperator}), and this
 * is the root of every unit-scope walk down to assets.
 */
@Entity
@Table(name = "location_units")
@IdClass(LocationUnitId.class)
@Data
public class LocationUnit {
    @Id
    @Column(name = "location_id")
    private Long locationId;

    @Id
    @Column(name = "unit_id")
    private Long unitId;
}
