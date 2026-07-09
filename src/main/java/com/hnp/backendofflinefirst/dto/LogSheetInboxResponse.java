package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.LogSheet;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Mobile kartabl payload: assigned sheets include full offline bundles; the pick-up
 * pool and supervisor team list remain metadata-only until claimed or opened.
 */
@Data
@AllArgsConstructor
public class LogSheetInboxResponse {
    private long serverTime;
    /** Sheets assigned to the current user (open), with entries and scoped context. */
    private List<LogSheetBundleDto> assigned;
    /** Pending sheets in the user's units (pick-up pool) — metadata only. */
    private List<LogSheet> available;
    /**
     * Supervisor only: open sheets in supervised units assigned to other operators
     * (release / reassign while online) — metadata only.
     */
    private List<LogSheet> teamOpen;
}
