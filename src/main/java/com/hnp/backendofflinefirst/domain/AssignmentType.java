package com.hnp.backendofflinefirst.domain;

/**
 * How a log sheet became owned by its current assignee — this drives who may
 * release/reassign it.
 * <pre>
 * SELF_CLAIMED        — an operator picked it up themselves; only that operator
 *                       (the assignee) may return it to the pool.
 * SUPERVISOR_ASSIGNED — a supervisor pushed it to an operator's inbox; only a
 *                       supervisor of the unit may release or reassign it.
 * </pre>
 */
public enum AssignmentType {
    SELF_CLAIMED,
    SUPERVISOR_ASSIGNED
}
