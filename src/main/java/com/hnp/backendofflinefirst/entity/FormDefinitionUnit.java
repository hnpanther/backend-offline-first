package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Data;

import java.io.Serializable;

/**
 * A unit that may file this form ({@code reporter_unit_id} / mirrored {@code subject_unit_id}).
 *
 * <p>Observation location, when needed, is an optional lookup field on the form definition —
 * not a second unit column on the submission.
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
