package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.List;

@Data
@Entity
@Table(name = "form_schema_versions")
public class FormSchemaVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "form_definition_id", nullable = false)
    private Long formDefinitionId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<FieldDefinitionSnapshot> fields;
    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
