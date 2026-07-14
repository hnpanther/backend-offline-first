package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "main_functions")
@Data
public class MainFunction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private Long systemId;
    private Long locationId;
    private Long createdAt;
    private Long updatedAt;
}
