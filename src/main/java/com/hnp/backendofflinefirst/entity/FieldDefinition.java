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
    private Long id;
    private Long classId;

    @Column(name = "field_key")
    private String key;

    private String label;
    private String dataType;
    private String unit;
    private boolean required;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> validation;

    @Column(name = "sort_order")
    private Integer order;

    private Integer version;
    private boolean deleted;
    private boolean synced;
    private Long createdAt;
    private Long updatedAt;
}
