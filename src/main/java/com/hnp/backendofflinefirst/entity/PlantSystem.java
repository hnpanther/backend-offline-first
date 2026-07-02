package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "plant_systems")
@Data
public class PlantSystem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private Long locationId;
    private Long createdAt;
    private Long updatedAt;
}
