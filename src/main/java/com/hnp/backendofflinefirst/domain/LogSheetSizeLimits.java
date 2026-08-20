package com.hnp.backendofflinefirst.domain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How many assets one log sheet may contain.
 *
 * <h2>Why there is a limit at all</h2>
 *
 * <p>Nothing used to bound this. A template whose scope is a whole plant area resolves to
 * however many assets the hierarchy holds, and {@code prepopulateEntries} creates one row for
 * each. The count only ever grows: a {@code SCOPE} template re-resolves on every run, so a
 * template validated at 140 assets becomes 400 the day somebody registers a new plant system,
 * with nobody editing the template.
 *
 * <p>What breaks first is not the database. In order:
 *
 * <ol>
 *   <li><b>The web fill page.</b> It renders every entry in one form and resubmits all of them
 *       on every save. Past Tomcat's {@code max-parameter-count} the extra parameters are
 *       dropped <em>silently</em> — and a dropped parameter reaches the save path as an entry
 *       with no answers.</li>
 *   <li><b>The tablet.</b> Saving one asset rewrites the whole entries array into IndexedDB, so
 *       the cost of every save grows with the sheet.</li>
 *   <li><b>The round itself.</b> A sheet is one operator's claim. A round that cannot be
 *       finished in a shift cannot be split either — it just expires.</li>
 * </ol>
 *
 * <h2>Two thresholds, because one is not enough</h2>
 *
 * <p>{@link #max()} is a refusal and {@link #warnAt()} is a warning, and which one applies
 * depends on <b>who is standing there</b>:
 *
 * <ul>
 *   <li>Saving a template, or creating a custom sheet, <b>refuses</b> above the maximum. A human
 *       is at the screen and can narrow the scope; telling them now is the whole point.</li>
 *   <li>The scheduler <b>generates anyway and warns</b>. Refusing there would mean the round
 *       simply does not happen because the plant grew, and nobody is told — a worse failure than
 *       a large sheet, and one that is invisible until somebody asks why a shift has no work.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 * app.log-sheets.max-assets-per-sheet=300
 * app.log-sheets.warn-assets-per-sheet=150
 * </pre>
 *
 * <p>A maximum of {@code 0} or less disables the refusal entirely — the escape hatch for a site
 * that genuinely wants one enormous sheet and accepts what comes with it. The warning still
 * fires, because "you asked for this" and "nobody should ever hear about it" are different
 * statements. A warning threshold above the maximum is pulled down to it, since a warning that
 * can only fire after a refusal never fires.
 */
@Component
public class LogSheetSizeLimits {

    /** Message prefix the Persian translator matches on. Changing it changes the UI text. */
    public static final String EXCEEDED_MESSAGE_PREFIX = "Log sheet asset count exceeds the limit:";

    private final int max;
    private final int warnAt;

    public LogSheetSizeLimits(
            @Value("${app.log-sheets.max-assets-per-sheet:300}") int max,
            @Value("${app.log-sheets.warn-assets-per-sheet:150}") int warnAt) {
        this.max = max;
        this.warnAt = max > 0 && warnAt > max ? max : warnAt;
    }

    /** Assets per sheet above which creation is refused; {@code <= 0} means no ceiling. */
    public int max() {
        return max;
    }

    /** Assets per sheet above which a sheet is worth mentioning; {@code <= 0} means never. */
    public int warnAt() {
        return warnAt;
    }

    public boolean isUnlimited() {
        return max <= 0;
    }

    /** Whether this count is over the refusal threshold. Always false when unlimited. */
    public boolean exceedsMax(int assetCount) {
        return !isUnlimited() && assetCount > max;
    }

    /** Whether this count deserves a log line, whether or not it is also over the maximum. */
    public boolean deservesWarning(int assetCount) {
        return warnAt > 0 && assetCount > warnAt;
    }

    /**
     * Refuses a count above the maximum.
     *
     * <p>The message carries both numbers because the person reading it needs to know how far
     * over they are — "too many" alone does not say whether to drop one asset or rethink the
     * scope. {@code ErrorTranslator} turns it into Persian by matching
     * {@link #EXCEEDED_MESSAGE_PREFIX}.
     *
     * @throws IllegalArgumentException when {@code assetCount} is over {@link #max()}
     */
    public void requireWithinMax(int assetCount) {
        if (exceedsMax(assetCount)) {
            throw new IllegalArgumentException(
                    EXCEEDED_MESSAGE_PREFIX + " " + assetCount + " assets, maximum is " + max + ".");
        }
    }
}
