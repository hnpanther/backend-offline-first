package com.hnp.backendofflinefirst.domain;

import java.util.List;
import java.util.Map;

public record IssuedEntrySchema(int version, boolean filtered, List<List<FieldDefinitionSnapshot>> definitions,
                                Map<String, Integer> byAsset) {
    public List<FieldDefinitionSnapshot> fields(Long assetId) {
        Integer index = byAsset.get(String.valueOf(assetId));
        if (version != 1 || index == null || index < 0 || index >= definitions.size()) {
            throw new IllegalStateException("Invalid issued parameter schema for asset " + assetId);
        }
        return definitions.get(index);
    }
}
