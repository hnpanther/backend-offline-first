package com.hnp.backendofflinefirst.audit;

import java.util.LinkedHashMap;
import java.util.Map;

public record AuditFieldChange(String field, String oldValue, String newValue) {

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("oldValue", oldValue != null ? oldValue : "");
        map.put("newValue", newValue != null ? newValue : "");
        return map;
    }
}
