package com.hnp.backendofflinefirst.domain;

/**
 * What an Excel import does to the items its file does <b>not</b> name (roadmap §31, «The Excel
 * import is the riskiest writer»).
 */
public enum LookupImportMode {
    /** Rows in the file are added or updated; every item the file does not name is untouched. */
    ADD_UPDATE,
    /**
     * The file is the whole list: an active item it does not name is deactivated. Guarded by a
     * preview and, above a share of the list, an explicit confirmation — a partial file in this
     * mode would otherwise retire everybody it left out.
     */
    FULL_REPLACE;

    public static LookupImportMode parse(String value) {
        if (value == null || value.isBlank()) {
            return ADD_UPDATE;
        }
        for (LookupImportMode m : values()) {
            if (m.name().equalsIgnoreCase(value.trim())) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown lookup import mode.");
    }
}
