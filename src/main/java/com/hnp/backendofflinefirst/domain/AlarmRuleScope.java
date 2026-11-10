package com.hnp.backendofflinefirst.domain;

/**
 * What an alarm rule covers (V18).
 *
 * <pre>
 * FIELDS      — the parameters it lists, one or many. A parameter is listed by at most one rule.
 * ALL_FIELDS  — the default: every parameter that no FIELDS rule lists. At most one exists.
 * </pre>
 *
 * <p>Which rule governs a parameter is therefore always one answer: the FIELDS rule listing it —
 * <b>active or not</b>, so switching a specific rule off silences its parameters rather than
 * handing them back to the default — and otherwise the default rule, when it is active.
 */
public enum AlarmRuleScope {
    ALL_FIELDS,
    FIELDS
}
