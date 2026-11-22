package com.hnp.backendofflinefirst.domain;

/** Review transitions recorded on {@code form_submission_action_log}. */
public enum FormSubmissionActionType {
    APPROVE,
    UNAPPROVE,
    VOID,
    UNVOID,
    /** Subject supervisor returned a SUBMITTED filing to editable DRAFT (V31). */
    REOPEN
}
