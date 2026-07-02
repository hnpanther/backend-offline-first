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
    private List<LogSheet> assigned;
    private List<LogSheet> available;
}
