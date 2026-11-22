package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;

/**
 * A unit that may appear as {@code subject_unit_id} when filing this form.
 *
 * <p>Cross-unit filing is allowed: the reporter need not belong to the subject unit, only hold
 * FILL and pick a linked subject (decision 10).
 */
@Data
@Entity
@Table(name = "form_definition_units")
@IdClass(FormDefinitionUnitId.class)
public class FormDefinitionUnit {

    @Id
    @Column(name = "form_definition_id")
    private Long formDefinitionId;

    @Id
    @Column(name = "operational_unit_id")
    private Long operationalUnitId;
}
