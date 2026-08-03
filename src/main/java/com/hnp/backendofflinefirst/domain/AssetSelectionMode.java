package com.hnp.backendofflinefirst.domain;

/**
 * How a log-sheet template picks the assets of each generated sheet.
 * <pre>
 * SCOPE    — re-resolved on EVERY generation from scopeType + scopeId + classId, so
 *            assets later added to that scope are picked up automatically.
 * EXPLICIT — a frozen, hand-picked set (log_sheet_template_assets). The list never
 *            changes on its own; the only filter applied at generation time is that
 *            inactive assets are skipped. May span several asset classes, so classId
 *            is optional in this mode.
 * </pre>
 */
public enum AssetSelectionMode {
    SCOPE,
    EXPLICIT
}
