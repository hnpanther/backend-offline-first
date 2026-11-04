package com.hnp.backendofflinefirst.domain;

/**
 * How an operator is required to identify an asset before recording a reading against it.
 *
 * <pre>
 * NFC        — scan the tag (the behaviour this system has always had, and the default)
 * QR         — scan a QR code carrying the same value the tag would have carried
 * NFC_OR_QR  — either; whichever the position actually has
 * NONE       — no scan at all: the operator picks the asset from the round's own list
 * </pre>
 *
 * <h2>Why the value scanned is the same either way</h2>
 *
 * <p>A QR code carries {@code asset_entries.nfc_tag_id} — the identifier the tag already holds.
 * No second column, no second identity to keep in step: a position labelled with both a tag and
 * a sticker answers with one value, and every query, report and fault report that names an asset
 * by its tag id keeps working untouched.
 *
 * <h2>What {@code QR} drops, and why that is correct rather than a weakening</h2>
 *
 * <p>Strict serial matching ({@code nfcStrictSerialMatch}) compares the chip's hardware UID with
 * the one recorded on the asset, which is what makes a cloned payload detectable. <b>A QR code
 * has no hardware serial.</b> There is nothing to compare, so the check does not apply — and
 * pretending it does, by failing every QR read on a plant with strict mode on, would make the
 * two modes silently incompatible. A site that needs the clone-resistance of a chip should
 * choose {@code NFC}; that is exactly the choice this enum exists to offer.
 *
 * <h2>And what the server can actually promise</h2>
 *
 * <p><b>This is an instruction to the device, not a guarantee the server enforces</b> — the same
 * standing as {@code nfcStrictSerialMatch}, which has always been a bootstrap setting the tablet
 * applies. The server never witnessed the scan: it stores the {@code entry_source} the client
 * reports, as it always has. Writing a server-side refusal here would read like verification and
 * provide none, since a client that lies about scanning can equally lie about which kind of scan
 * it performed. What the server does guarantee is that the mode is <b>frozen onto the sheet</b>,
 * so a round already in an operator's hands cannot change the rules under them.
 */
public enum AssetScanMode {
    NFC,
    QR,
    NFC_OR_QR,
    NONE;

    /** Whether a tag read identifies an asset on a round in this mode. */
    public boolean allowsNfc() {
        return this == NFC || this == NFC_OR_QR;
    }

    /** Whether a QR read identifies an asset on a round in this mode. */
    public boolean allowsQr() {
        return this == QR || this == NFC_OR_QR;
    }

    /** Whether the operator may simply pick the asset, with nothing to scan. */
    public boolean allowsPicking() {
        return this == NONE;
    }

    /**
     * The stored value as a mode, defaulting to {@link #NFC}.
     *
     * <p>Null is the pre-V11 world, where every round was an NFC round. Treating it as anything
     * else would silently change how a historic sheet is described.
     */
    public static AssetScanMode orDefault(AssetScanMode mode) {
        return mode == null ? NFC : mode;
    }
}
