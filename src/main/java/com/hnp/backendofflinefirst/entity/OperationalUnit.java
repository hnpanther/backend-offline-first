package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "operational_units")
@Data
public class OperationalUnit {
    @Id
    private String id;
    private String code;
    private String name;
    private String parentId;
    private Long createdAt;
    private Long updatedAt;
}
