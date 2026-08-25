package com.hnp.backendofflinefirst.dto;

/**
 * How far one round has got: assets recorded, out of assets on the sheet.
 *
 * <p>"Recorded" is {@code max_severity IS NOT NULL}, the same has-a-reading test the data-quality
 * report uses. A sheet is raised with one entry per asset whether or not anybody reaches them, so
 * counting entry rows would show every round as complete the moment it was generated — the exact
 * error that once made the data-quality report claim a 2% manual-entry rate when the truth was
 * 67%.
 *
 * @param filled how many assets carry a reading
 * @param total  how many assets are on the round
 */
public record LogSheetProgressSummary(long filled, long total) {

    public static final LogSheetProgressSummary EMPTY = new LogSheetProgressSummary(0, 0);

    /** Whole percent, 0 when the sheet has no assets — never a division by zero in a template. */
    public int percent() {
        if (total <= 0) return 0;
        return (int) Math.round((filled * 100.0) / total);
    }

    /** True once at least one asset has been read — what turns the bar from grey to blue. */
    public boolean started() {
        return filled > 0;
    }

    /** True when every asset on the round carries a reading. */
    public boolean complete() {
        return total > 0 && filled >= total;
    }
}
