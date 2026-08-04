package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "locations")
@Data
public class Location {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "code", nullable = false)
    private String code;
    @Column(name = "name")
    private String name;
    /** Optional secondary (Persian) title; display-only, never used for lookups. */
    @Column(name = "name_fa")
    private String nameFa;
    @Column(name = "parent_id")
    private Long parentId;
    /** Responsible operational units live in {@code location_units} (many per location). */
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
