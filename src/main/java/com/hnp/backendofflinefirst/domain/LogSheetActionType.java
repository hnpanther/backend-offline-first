package com.hnp.backendofflinefirst.domain;

/** Auditable lifecycle actions recorded in {@code log_sheet_action_log}. */
public enum LogSheetActionType {
    GENERATE,
    CLAIM,
    RELEASE,
    ASSIGN,
    REASSIGN,
    TAKEOVER,
    EXTEND,
    ADMIN_REOPEN,
    START,
    COMPLETE,
    SUBMIT,
    EXPIRE,
    SUPERSEDE
}
