package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "locations")
@Data
public class Location {
    @Id
    private String id;
    private String code;
    private String name;
    private String parentId;
    private String unitId;
    private Long createdAt;
    private Long updatedAt;
}
