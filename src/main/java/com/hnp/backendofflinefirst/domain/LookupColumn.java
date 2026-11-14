package com.hnp.backendofflinefirst.domain;

import lombok.Data;

/**
 * One column a lookup list declares (roadmap §31: «the list declares its own columns»), stored as
 * an element of {@code lookup_lists.columns}. Each item carries its value under
 * {@link #key} in {@code lookup_items.attributes}.
 *
 * <p>The {@code key} is an identity, exactly as a field key is (AGENTS.md #157): it names the
 * value in every item and the header in every import file, so it is fixed once created. The label
 * may change freely.
 */
@Data
public class LookupColumn {

    private String key;
    private String label;
    /** Folded into {@code search_text}: a search on the list finds an item by this value. */
    private boolean searchable;
    /** Shown beside the label wherever an item is picked. */
    private boolean display;
    /**
     * May this value leave the server for a device? Off by default. The device payload of the
     * next phase is built from flagged columns only, on the server — a national id or a phone
     * number never reaches a tablet unless an administrator ticks this deliberately.
     */
    private boolean offline;

    public static LookupColumn of(String key, String label, boolean searchable, boolean display, boolean offline) {
        LookupColumn c = new LookupColumn();
        c.setKey(key);
        c.setLabel(label);
        c.setSearchable(searchable);
        c.setDisplay(display);
        c.setOffline(offline);
        return c;
    }
}
