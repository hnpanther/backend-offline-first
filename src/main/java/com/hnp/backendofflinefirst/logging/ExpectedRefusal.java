package com.hnp.backendofflinefirst.logging;

/**
 * Marks an exception that is a normal, anticipated answer — not a fault.
 *
 * <p>{@code LoggingAspect} logs any throwable that leaves a service at ERROR, with its stack
 * trace, which is right for a fault and wrong for a refusal the system is designed to give. With
 * device enrolment on, every login from an unenrolled tablet and every mistyped code is such a
 * refusal; logging each one as an ERROR would bury real faults and teach whoever reads the log
 * to ignore the level. An exception carrying this marker is logged once at INFO, by its
 * {@link Throwable#getMessage() message}, and without a trace.
 *
 * <p>So the message of an implementing exception must be safe to log as-is — a stable code, not
 * a value the caller sent.
 */
public interface ExpectedRefusal {
}
