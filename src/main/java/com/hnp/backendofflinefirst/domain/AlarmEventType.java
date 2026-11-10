package com.hnp.backendofflinefirst.domain;

/** One line of an alarm's timeline. */
public enum AlarmEventType {
    /** The condition was met and the alarm was created. */
    RAISED,
    /** Another breaching reading of a live alarm. */
    OCCURRED,
    /** A worse severity arrived; an acknowledged alarm goes back to OPEN. */
    ESCALATED,
    /** The worst reading behind it was withdrawn, so its severity dropped. */
    DEESCALATED,
    /** The condition cleared. */
    RETURNED_TO_NORMAL,
    /** It had cleared, and then breached again before anybody acknowledged it. */
    REACTIVATED,
    ACKNOWLEDGED,
    CLOSED;

    public String labelFa() {
        return switch (this) {
            case RAISED -> "ایجاد آلارم";
            case OCCURRED -> "تکرار تخطی";
            case ESCALATED -> "تشدید";
            case DEESCALATED -> "کاهش شدت";
            case RETURNED_TO_NORMAL -> "بازگشت به حالت عادی";
            case REACTIVATED -> "تخطی دوباره";
            case ACKNOWLEDGED -> "تأیید دیده‌شدن";
            case CLOSED -> "بسته شد";
        };
    }
}
