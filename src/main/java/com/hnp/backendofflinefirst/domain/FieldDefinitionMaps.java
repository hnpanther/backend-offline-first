package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts {@link FieldDefinition} rows into the embedded JSON shape used on
 * {@link AssetClass#getFields()} for mobile clients that still read class.fields.
 */
public final class FieldDefinitionMaps {

    private FieldDefinitionMaps() {}

    public static Map<String, Object> toEmbeddedField(FieldDefinition field) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (field.getId() != null) {
            map.put("id", field.getId());
        }
        if (field.getClassId() != null) {
            map.put("classId", field.getClassId());
        }
        map.put("key", field.getKey());
        map.put("label", field.getLabel());
        map.put("dataType", field.getDataType());
        map.put("unit", field.getUnit());
        map.put("required", field.isRequired());
        if (field.getValidation() != null) {
            map.put("validation", field.getValidation());
        }
        if (field.getOrder() != null) {
            map.put("order", field.getOrder());
        }
        if (field.getVersion() != null) {
            map.put("version", field.getVersion());
        }
        return map;
    }

    public static List<Map<String, Object>> toEmbeddedFields(List<FieldDefinition> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(fields.size());
        for (FieldDefinition field : fields) {
            if (field != null) {
                out.add(toEmbeddedField(field));
            }
        }
        return out;
    }

    /** Shallow copy for API payloads so hydrating fields does not dirty the persistence context. */
    public static AssetClass copyWithEmbeddedFields(AssetClass source, List<FieldDefinition> fields) {
        AssetClass copy = new AssetClass();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setFields(toEmbeddedFields(fields));
        return copy;
    }
}
