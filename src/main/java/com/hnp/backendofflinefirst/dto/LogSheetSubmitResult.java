package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LogSheetSubmitResult {
    private String localId;
    private Long serverId;
    private String error;
    /**
     * Outcome for the client: SUBMITTED | DUPLICATE | SUPERSEDED | CANCELLED | EXPIRED |
     * VALIDATION_ERROR | ERROR.
     */
    private String outcome;
    /**
     * {@code true} when the server refused to apply this submission but <b>kept</b> it for review
     * as a row in {@code log_sheet_void_submissions} (issue B-01). Null otherwise — never
     * {@code false}, so an older client that ignores the field and a newer one reading
     * {@code kept === true} agree.
     *
     * <p>Additive: an app older than V25 ignores it. A newer app uses it to tell "refused and
     * kept on the server" from "refused and only on this tablet" — the difference between a round
     * a supervisor can see and one that exists nowhere else.
     */
    private Boolean kept;

    public LogSheetSubmitResult(String localId, Long serverId, String error, String outcome) {
        this(localId, serverId, error, outcome, null);
    }

    public LogSheetSubmitResult(String localId, Long serverId, String error) {
        this(localId, serverId, error, error == null ? "SUBMITTED" : "ERROR");
    }
}
