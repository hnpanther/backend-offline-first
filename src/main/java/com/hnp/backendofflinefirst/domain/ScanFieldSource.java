package com.hnp.backendofflinefirst.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A reader that can fill one {@code text} or {@code number} field from a label.
 *
 * <pre>
 * NFC      — the tag's payload, read the same way the asset path reads one
 * QR       — a QR label
 * BARCODE  — a 1-D barcode: Code 128, EAN-13, the kind printed on a nameplate
 * </pre>
 *
 * <h2>Not {@link AssetScanMode}, and deliberately a separate type</h2>
 *
 * <p>{@code AssetScanMode} answers "how does the operator reach an asset on this round". This
 * answers "how may this one question be answered". They share the words NFC and QR and nothing
 * else: a round that needs no scan at all to open an asset can still hold a field whose value
 * is a barcode, and a field scanned inside a manually-opened entry does not make that entry a
 * scanned one.
 *
 * <p>Modelling them as one enum would have invited exactly the collapse this comment exists to
 * prevent — one of them would eventually be passed where the other was meant, and the failure
 * (a nameplate serial checked against the round's asset list, and rejected) looks like a broken
 * scanner rather than a wrong type.
 *
 * <h2>Stored as strings inside a JSONB blob</h2>
 *
 * <p>So the valid set is enforced on write ({@link #retainKnown}) and read defensively. A value
 * this build does not recognise is dropped rather than refused: the field's other rules are
 * still good, and an operator should not lose a form because a newer panel wrote a source this
 * tablet has never heard of.
 */
public enum ScanFieldSource {
    NFC,
    QR,
    BARCODE;

    /**
     * The recognised names from an arbitrary collection, de-duplicated, order preserved.
     *
     * <p>Order is display order on the device, so it is the administrator's and not this enum's:
     * a plant whose labels are mostly QR wants that button first.
     */
    public static List<String> retainKnown(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                out.add(valueOf(candidate.trim().toUpperCase()).name());
            } catch (IllegalArgumentException ignored) {
                // Unknown source — see the class note.
            }
        }
        return List.copyOf(out);
    }
}
