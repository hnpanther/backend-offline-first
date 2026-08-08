package com.hnp.backendofflinefirst.domain;

/**
 * Review state of an {@code NfcFaultReport}.
 *
 * <p>A report starts {@link #OPEN} and only an <b>administrator</b> may move it to
 * {@link #REVIEWED}. The restriction is the point: a fault report is a claim that a tag needs
 * physical attention, and "someone has looked at this" is only meaningful if not everyone who
 * sees the list can assert it.
 */
public enum NfcFaultReportStatus {
    OPEN,
    REVIEWED
}
