package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "plant_systems")
@Data
public class PlantSystem {
    @Id
    private String id;
    private String code;
    private String name;
    private String locationId;
    private Long createdAt;
    private Long updatedAt;
}
