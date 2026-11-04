package com.hnp.backendofflinefirst.domain;

/**
 * How a log sheet entry's form data was captured.
 *
 * <p>Reported by the client and stored as given — the server never witnessed the scan and has
 * never claimed to. It is a record of what the operator did, which is what a plant asks of it
 * ("was this reading taken at the equipment, or typed from somewhere else?"), not a credential.
 *
 * <p>{@code PWA_QR} joined in V11, when a round gained the ability to be identified by a QR code
 * instead of a tag. It is a separate value rather than being folded into {@code PWA_NFC} because
 * the two carry different evidence: a chip read can be checked against a hardware serial, a QR
 * read cannot, and a report that merged them would overstate what half its rows prove.
 */
public enum LogSheetEntrySource {
    WEB,
    PWA_NFC,
    PWA_QR,
    PWA_MANUAL;

    /** Whether this reading was taken by scanning something at the equipment. */
    public boolean isScanned() {
        return this == PWA_NFC || this == PWA_QR;
    }
}
