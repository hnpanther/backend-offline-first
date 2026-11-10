package com.hnp.backendofflinefirst.domain;

/**
 * Why an alarm stopped being live.
 *
 * <p>{@link #consumesEvidence()} is the rule that stops a closed alarm from being raised again
 * out of the same readings. When a supervisor closes an alarm while the parameter is still out of
 * range, the very next evaluation would otherwise find the same breaching readings and raise it
 * straight back. So a closed alarm's {@code last_reading_at} is a watermark: only readings taken
 * after it can raise a new one. The one exception is evidence that was withdrawn — a voided
 * round — because those readings did not stop being evidence, they stopped being true; if the
 * round is restored they may raise an alarm again.
 */
public enum AlarmCloseReason {
    /** A person decided it — «تعیین تکلیف شد». */
    RESOLVED,
    /** The condition cleared after it had been acknowledged. */
    RETURNED_TO_NORMAL,
    /** Every reading behind it was withdrawn: its rounds were voided. */
    EVIDENCE_WITHDRAWN,
    /** Its rule was switched off, and nobody is to be bothered by it any more. */
    RULE_DISABLED;

    public boolean consumesEvidence() {
        return this != EVIDENCE_WITHDRAWN;
    }

    public String labelFa() {
        return switch (this) {
            case RESOLVED -> "تعیین تکلیف شد";
            case RETURNED_TO_NORMAL -> "بازگشت به حالت عادی";
            case EVIDENCE_WITHDRAWN -> "قرائت‌های پشتوانه باطل شدند";
            case RULE_DISABLED -> "قاعده غیرفعال شد";
        };
    }
}
