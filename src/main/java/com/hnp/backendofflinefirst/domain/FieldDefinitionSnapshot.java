package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import lombok.Data;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Immutable copy of a {@link FieldDefinition} captured when a log sheet is generated.
 * Stored as JSON on {@link com.hnp.backendofflinefirst.entity.LogSheet}.
 */
@Data
public class FieldDefinitionSnapshot {

    private Long classId;
    private String key;
    private String label;
    private String dataType;
    private String unit;
    private boolean required;
    private Map<String, Object> validation;
    private Integer order;
    private Integer version;

    public static FieldDefinitionSnapshot from(FieldDefinition source) {
        FieldDefinitionSnapshot snapshot = new FieldDefinitionSnapshot();
        snapshot.setClassId(source.getClassId());
        snapshot.setKey(source.getKey());
        snapshot.setLabel(source.getLabel());
        snapshot.setDataType(source.getDataType());
        snapshot.setUnit(source.getUnit());
        snapshot.setRequired(source.isRequired());
        snapshot.setValidation(source.getValidation());
        snapshot.setOrder(source.getOrder());
        snapshot.setVersion(source.getVersion());
        return snapshot;
    }

    public FieldDefinition toFieldDefinition() {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType(dataType);
        fd.setUnit(unit);
        fd.setRequired(required);
        fd.setValidation(validation);
        fd.setOrder(order);
        fd.setVersion(version);
        fd.setDeleted(false);
        return fd;
    }

    public static Comparator<FieldDefinitionSnapshot> displayOrder() {
        return Comparator
                .comparing((FieldDefinitionSnapshot s) -> s.getOrder() != null ? s.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinitionSnapshot::getKey, Comparator.nullsLast(String::compareTo));
    }

    public static List<FieldDefinition> toFieldDefinitions(List<FieldDefinitionSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        return snapshots.stream()
                .sorted(displayOrder())
                .map(FieldDefinitionSnapshot::toFieldDefinition)
                .toList();
    }
}
