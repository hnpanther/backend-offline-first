package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.entity.LogSheet;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Mobile kartabl payload: sheets already assigned to the operator plus the pool
 * of pending sheets they may pick up.
 */
@Data
@AllArgsConstructor
public class LogSheetInboxResponse {
    private long serverTime;
    /** Sheets assigned to the current user (open). */
    private List<LogSheet> assigned;
    /** Pending sheets in the user's units (pick-up pool). */
    private List<LogSheet> available;
    /**
     * Supervisor only: open sheets in supervised units assigned to other operators
     * (release / reassign while online).
     */
    private List<LogSheet> teamOpen;
}
