package com.hnp.backendofflinefirst.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders formData JSON as labeled key/value rows for the UI. */
@Component("formDataView")
@RequiredArgsConstructor
public class FormDataViewHelper {

    private final ObjectMapper objectMapper;

    public record FormFieldRow(String label, String value, String unit) {}

    public List<FormFieldRow> rows(Object formData, List<FieldDefinition> fieldDefs) {
        Map<String, Object> data = asMap(formData);
        if (data.isEmpty()) return List.of();

        Map<String, FieldDefinition> defByKey = new LinkedHashMap<>();
        if (fieldDefs != null) {
            for (FieldDefinition fd : fieldDefs) {
                if (fd.getKey() != null) {
                    defByKey.put(fd.getKey(), fd);
                }
            }
        }

        List<FormFieldRow> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            FieldDefinition fd = defByKey.get(e.getKey());
            String label = fd != null && fd.getLabel() != null ? fd.getLabel() : e.getKey();
            String unit = fd != null ? fd.getUnit() : null;
            rows.add(new FormFieldRow(label, formatValue(e.getValue()), unit));
        }
        return rows;
    }

    public List<FormFieldRow> rowsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            List<FormFieldRow> rows = new ArrayList<>();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                rows.add(new FormFieldRow(e.getKey(), formatValue(e.getValue()), null));
            }
            return rows;
        } catch (Exception ex) {
            return List.of(new FormFieldRow("داده", json, null));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object formData) {
        if (formData == null) return Collections.emptyMap();
        if (formData instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (formData instanceof String s) {
            try {
                return objectMapper.readValue(s, new TypeReference<>() {});
            } catch (Exception ex) {
                return Map.of("raw", s);
            }
        }
        return Map.of("value", String.valueOf(formData));
    }

    private static String formatValue(Object v) {
        if (v == null) return "—";
        if (v instanceof Boolean b) return b ? "بله" : "خیر";
        return String.valueOf(v);
    }
}
