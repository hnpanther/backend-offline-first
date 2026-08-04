package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "sub_functions")
@Data
public class SubFunction {
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
    @Column(name = "tag", nullable = false)
    private String tag;
    @Column(name = "parent_id")
    private Long parentId;
    @Column(name = "main_function_id")
    private Long mainFunctionId;
    @Column(name = "system_id")
    private Long systemId;
    @Column(name = "location_id")
    private Long locationId;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
