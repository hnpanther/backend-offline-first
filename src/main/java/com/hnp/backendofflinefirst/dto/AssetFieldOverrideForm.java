package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.AssetFieldResolver;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The asset parameter screen's form, as the browser posts it.
 *
 * <p>Deliberately all strings. The band inputs are {@code <input type="number">} fields that may
 * legitimately be left blank, and blank is not zero — a warning maximum of nothing means "no
 * upper bound", while a warning maximum of 0 means "anything above zero is a warning". Binding
 * these to {@code Double} would make Spring turn the first into the second, or into a binding
 * error, depending on the field's nullability. Parsing happens once, in
 * {@link #toOverrides()}, and the validation that matters happens after that in
 * {@code AssetFieldOverrideService}.
 */
@Data
public class AssetFieldOverrideForm {

    /** {@code INHERIT}, {@code RANGES_ONLY} or {@code FULL}; anything else reads as INHERIT. */
    private String fieldSource;

    private List<Row> rows = new ArrayList<>();

    @Data
    public static class Row {
        private String fieldKey;
        private String warningMin;
        private String warningMax;
        private String dangerMin;
        private String dangerMax;
        /** FULL only: drop this field for this asset. */
        private boolean removed;
        /** FULL only, and only for a row the admin added. */
        private String label;
        private String unit;
        private String dataType;
        /** True for a row the admin added that the class does not have. */
        private boolean custom;
    }

    public FieldSource source() {
        return FieldSource.from(fieldSource);
    }

    /**
     * The override map, with rows that say nothing left out.
     *
     * <p>"Says nothing" means every band blank and nothing structural — which is what a field
     * the admin simply did not touch looks like. Including those would store the class's own
     * values as the asset's, and the asset would stop following the class the next time
     * somebody re-tuned it, silently.
     */
    public Map<String, Object> toOverrides() {
        FieldSource source = source();
        Map<String, Object> out = new LinkedHashMap<>();
        if (source.inheritsEverything()) {
            return out;
        }
        for (Row row : rows) {
            if (row == null || row.getFieldKey() == null || row.getFieldKey().isBlank()) {
                continue;
            }
            Map<String, Object> value = new LinkedHashMap<>();

            if (source.ownsFieldList() && row.isRemoved()) {
                value.put(AssetFieldResolver.OVERRIDE_REMOVED, true);
                out.put(row.getFieldKey().trim(), value);
                continue;
            }

            putBand(value, FieldValidationSupport.KEY_WARNING, row.getWarningMin(), row.getWarningMax());
            putBand(value, FieldValidationSupport.KEY_DANGER, row.getDangerMin(), row.getDangerMax());

            if (source.ownsFieldList() && row.isCustom()) {
                // Only a row the admin added carries its own definition; an inherited row that
                // was merely re-banded must keep following the class for everything else.
                putIfPresent(value, "label", row.getLabel());
                putIfPresent(value, "unit", row.getUnit());
                putIfPresent(value, "dataType", row.getDataType());
            }

            if (!value.isEmpty()) {
                out.put(row.getFieldKey().trim(), value);
            }
        }
        return out;
    }

    private static void putBand(Map<String, Object> target, String key, String min, String max) {
        boolean hasMin = min != null && !min.isBlank();
        boolean hasMax = max != null && !max.isBlank();
        if (!hasMin && !hasMax) {
            return;
        }
        Map<String, Object> range = new LinkedHashMap<>();
        if (hasMin) {
            range.put(FieldValidationSupport.KEY_MIN, min.trim());
        }
        if (hasMax) {
            range.put(FieldValidationSupport.KEY_MAX, max.trim());
        }
        target.put(key, range);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }
}
