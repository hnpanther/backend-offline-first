package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "field_definitions")
@Data
public class FieldDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "field_key", nullable = false)
    private String key;

    @Column(name = "label")
    private String label;
    @Column(name = "data_type")
    private String dataType;
    @Column(name = "unit")
    private String unit;
    @Column(name = "required")
    private boolean required;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation", columnDefinition = "jsonb")
    private Map<String, Object> validation;

    @Column(name = "sort_order")
    private Integer order;

    @Column(name = "version")
    private Integer version;
    @Column(name = "deleted")
    private boolean deleted;
    @Column(name = "synced")
    private boolean synced;
    @Column(name = "created_at")
    private Long createdAt;
    @Column(name = "updated_at")
    private Long updatedAt;
}
