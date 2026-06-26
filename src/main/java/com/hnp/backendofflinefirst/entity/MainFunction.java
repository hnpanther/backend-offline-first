package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "main_functions")
@Data
public class MainFunction {
    @Id
    private String id;
    private String code;
    private String name;
    private String systemId;
    private String locationId;
    private Long createdAt;
    private Long updatedAt;
}
