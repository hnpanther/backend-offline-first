package com.hnp.backendofflinefirst.domain;

import java.util.Set;

/**
 * Where an alarm stands.
 *
 * <pre>
 * OPEN          — raised, and nobody has said they have seen it. «تعیین تکلیف نشده».
 * ACKNOWLEDGED  — a supervisor has seen it and said what is being done. Still live: the
 *                 condition has not cleared, so it stays in the queue until it does.
 * CLOSED        — over, with a reason ({@link AlarmCloseReason}).
 * </pre>
 *
 * <p>The two live states are the ISA-18.2 «unacknowledged» and «acknowledged» active states.
 * An OPEN alarm whose condition has already cleared carries {@code returned_to_normal_at} and
 * stays OPEN: a breach nobody saw must not disappear on its own just because the next reading
 * was fine. Acknowledging it then closes it.
 */
public enum AlarmState {
    OPEN,
    ACKNOWLEDGED,
    CLOSED;

    /** The states in which an alarm is still somebody's business. */
    public static final Set<AlarmState> LIVE = Set.of(OPEN, ACKNOWLEDGED);

    public boolean isLive() {
        return this != CLOSED;
    }
}
