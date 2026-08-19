package com.hnp.backendofflinefirst.dto.integration;

/**
 * The small shared shapes of the integration API: the identifiers it hands out for a unit, a
 * person and an asset.
 *
 * <p>Grouped in one file because they are one decision — <b>what an external system is allowed
 * to know about our rows</b> — and splitting them across three files makes that decision look
 * like three unrelated ones.
 */
public final class IntegrationReferences {

    private IntegrationReferences() {}

    /**
     * An operational unit.
     *
     * <p>{@code id} is exposed because the list endpoint's {@code unitId} filter needs a value
     * the caller can send back. {@code code} is what an ERP will actually join on.
     */
    public record Unit(Long id, String code, String name) {}

    /**
     * A person, as an external system may see them.
     *
     * <p><b>No internal user id, and that is the point.</b> An {@code id} column is meaningful
     * only inside this database; publishing it invites an integrator to store it and turns a
     * private key into a shared one that can never be changed. {@code personnelCode} is the
     * plant's own identifier for the same human and is what an HR or ERP system already holds,
     * so it is the correct join key. {@code username} follows only because a support
     * conversation is impossible without it.
     *
     * <p>Deliberately absent: national code, phone number, NFC tag, shift, org unit, org
     * position, auth type, active flag. None of them are needed to identify who completed a
     * round, and several are personal data whose export nobody asked for.
     */
    public record Person(String username, String fullName, String personnelCode) {}

    /**
     * One asset row on a log sheet, identified the way the plant identifies it.
     *
     * <p>{@code nfcTagId} is the tag of the functional position, which an external maintenance
     * system may legitimately hold. The chip's hardware serial ({@code nfc_serial}) is
     * <b>not</b> exposed: it is an anti-cloning control, and a control that has been published
     * is not one.
     */
    public record Asset(Long id, String code, String name, String className,
                        String subFunctionCode, String subFunctionTag, String nfcTagId) {}
}
