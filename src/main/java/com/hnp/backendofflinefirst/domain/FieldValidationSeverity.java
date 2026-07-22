package com.hnp.backendofflinefirst.domain;

/** Result of checking a numeric field value against configured ranges. */
public enum FieldValidationSeverity {
    /** Within all configured ranges. */
    OK,
    /** Outside the warning range — shown as a yellow alert when viewing data. */
    WARNING,
    /** Outside the danger range — shown as a red alert when viewing data; does not block submit. */
    DANGER
}
