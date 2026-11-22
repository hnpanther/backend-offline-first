package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * A question on an ad-hoc {@link FormDefinition}.
 *
 * <p>Same columns as {@link LogSheetField} / {@link FieldDefinition}, so
 * {@link #toFieldDefinition()} feeds the existing validators and renderers without a second DSL.
 */
@Data
@Entity
@Table(name = "form_fields")
public class FormField {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "form_definition_id", nullable = false)
    private Long formDefinitionId;

    @Column(name = "field_key", nullable = false)
    private String key;

    @Column(name = "label")
    private String label;

    @Column(name = "data_type")
    private String dataType;

    @Column(name = "unit")
    private String unit;

    @Column(name = "required", nullable = false)
    private boolean required;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation", columnDefinition = "jsonb")
    private Map<String, Object> validation;

    @Column(name = "sort_order")
    private Integer order;

    @Column(name = "version")
    private Integer version;

    /** Switched off: not offered on new submissions; frozen snapshots keep rendering it. */
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    /**
     * Owner-agnostic view for {@code FieldValidationSupport} / {@code FormDataValidationSupport}.
     * {@code classId} stays null — same reason as {@link LogSheetField#toFieldDefinition()}.
     */
    public FieldDefinition toFieldDefinition() {
        FieldDefinition fd = new FieldDefinition();
        fd.setId(id);
        fd.setClassId(null);
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType(dataType);
        fd.setUnit(unit);
        fd.setRequired(required);
        fd.setValidation(validation);
        fd.setOrder(order);
        fd.setVersion(version);
        fd.setDeleted(deleted);
        return fd;
    }
}
