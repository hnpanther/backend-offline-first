package com.hnp.backendofflinefirst.domain;

/** Result of checking a numeric field value against configured ranges. */
public enum FieldValidationSeverity {
    /** Within all configured ranges. */
    OK,
    /** Outside the warning range — display a yellow alert later. */
    WARNING,
    /** Outside the danger range — abnormal condition; follow-up action later. */
    DANGER
}
