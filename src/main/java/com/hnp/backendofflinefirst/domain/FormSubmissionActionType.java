package com.hnp.backendofflinefirst.domain;

/** Review transitions recorded on {@code form_submission_action_log}. */
public enum FormSubmissionActionType {
    APPROVE,
    UNAPPROVE,
    VOID,
    UNVOID
}
