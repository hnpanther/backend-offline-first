package com.hnp.backendofflinefirst.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The arithmetic of an alarm condition, with no database in it.
 *
 * <p>Everything here takes one asset's readings of one parameter, <b>newest first</b>, ordered by
 * when the reading was taken — the round's {@code completed_at}, which is device time — and never
 * by when it reached the server. That ordering is the whole of the out-of-order rule: a round
 * finished on Thursday and delivered after Friday's is still Thursday's, so it is judged in
 * Thursday's place and cannot clear an alarm that Friday's reading raised.
 *
 * <h2>Three kinds of reading</h2>
 * <ul>
 *   <li><b>Breaching</b> — at or above the rule's trigger severity.</li>
 *   <li><b>Normal</b> — read, and below the trigger. For a {@code DANGER} rule a {@code WARNING}
 *       reading is normal: the rule asked about danger.</li>
 *   <li><b>Not read</b> ({@code severity == null}) — the asset was on the round and nobody
 *       answered this parameter. It <b>neither counts nor resets</b>: «we do not know» is not
 *       «it was fine», and treating it as fine would let a skipped asset clear an alarm.</li>
 * </ul>
 */
public final class AlarmCondition {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private AlarmCondition() {}

    /**
     * One reading of one parameter on one finished round.
     *
     * @param severity null when the parameter was not answered on that round
     * @param value    the stored value, as text, for display and the webhook
     */
    public record Reading(Long logSheetId, Long entryId, long readAt, Long unitId,
                          FieldValidationSeverity severity, String value) {

        public boolean isRead() {
            return severity != null;
        }
    }

    /**
     * Whether {@code severity} is at or above {@code trigger}. {@code OK} and an unanswered
     * parameter never breach.
     */
    public static boolean breaches(FieldValidationSeverity severity, FieldValidationSeverity trigger) {
        return severity != null
                && severity != FieldValidationSeverity.OK
                && trigger != null
                && severity.ordinal() >= trigger.ordinal();
    }

    /**
     * The breaching readings at the head of the history: the newest ones, consecutive, with the
     * unread ones skipped and a normal one ending the run.
     *
     * <p><b>The window</b> is anchored at the newest breaching reading: a reading older than
     * {@code windowDays} before it ends the run. So «three warnings within 7 days» on a daily
     * round is three days, and three warnings spread over a month never meet it. With no window
     * the run follows the round's own cadence — three readings of a monthly inspection is three
     * months, which is usually exactly what was meant.
     */
    public static List<Reading> leadingBreachRun(List<Reading> newestFirst,
                                                 FieldValidationSeverity trigger,
                                                 Integer windowDays) {
        List<Reading> run = new ArrayList<>();
        Long anchor = null;
        for (Reading reading : newestFirst) {
            if (!reading.isRead()) {
                continue;
            }
            if (!breaches(reading.severity(), trigger)) {
                break;
            }
            if (anchor == null) {
                anchor = reading.readAt();
            } else if (windowDays != null && reading.readAt() < anchor - windowDays * DAY_MS) {
                break;
            }
            run.add(reading);
        }
        return run;
    }

    /** The normal readings at the head of the history, unread ones skipped, a breach ending it. */
    public static List<Reading> leadingNormalRun(List<Reading> newestFirst,
                                                 FieldValidationSeverity trigger) {
        List<Reading> run = new ArrayList<>();
        for (Reading reading : newestFirst) {
            if (!reading.isRead()) {
                continue;
            }
            if (breaches(reading.severity(), trigger)) {
                break;
            }
            run.add(reading);
        }
        return run;
    }

    /**
     * The reading at which the condition was first satisfied, or null when it is not.
     *
     * <p>In a run of five breaching readings with {@code consecutive = 3}, that is the third
     * oldest — the moment there were three in a row — not the newest, which merely kept it true.
     */
    public static Reading conditionMetBy(List<Reading> breachRun, int consecutive) {
        if (consecutive < 1 || breachRun.size() < consecutive) {
            return null;
        }
        return breachRun.get(breachRun.size() - consecutive);
    }

    /** The reading at which the condition cleared, or null when it has not. Same arithmetic. */
    public static Reading clearedBy(List<Reading> normalRun, int clearAfter) {
        if (clearAfter < 1 || normalRun.size() < clearAfter) {
            return null;
        }
        return normalRun.get(normalRun.size() - clearAfter);
    }

    /** Every breaching reading in the list, newest first. */
    public static List<Reading> breaching(List<Reading> newestFirst, FieldValidationSeverity trigger) {
        List<Reading> out = new ArrayList<>();
        for (Reading reading : newestFirst) {
            if (breaches(reading.severity(), trigger)) {
                out.add(reading);
            }
        }
        return out;
    }

    /** The worst severity among the readings; null for none. */
    public static FieldValidationSeverity worst(Collection<Reading> readings) {
        FieldValidationSeverity worst = null;
        for (Reading reading : readings) {
            FieldValidationSeverity severity = reading.severity();
            if (severity != null && (worst == null || severity.ordinal() > worst.ordinal())) {
                worst = severity;
            }
        }
        return worst;
    }
}
