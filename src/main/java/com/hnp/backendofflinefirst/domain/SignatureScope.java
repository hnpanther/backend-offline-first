package com.hnp.backendofflinefirst.domain;

/**
 * What a {@code signature} field vouches for (roadmap §32) — stored on the field as
 * {@code validation.signatureScope} and frozen with the sheet's snapshot, then recorded on each
 * signature as the {@code scope} it was taken under.
 *
 * <ul>
 *   <li>{@link #NONE} — who signed and when, nothing more. No hash is taken and the signature is
 *       never reported as out of date: for a sign-off that is about presence («بازدید انجام شد»),
 *       not about particular values.</li>
 *   <li>{@link #FORM} — the answers of the form the field is on: an asset's signature its own
 *       readings, a round-level one the round's own questions. The default.</li>
 *   <li>{@link #ROUND} — a round-level signature that also covers <b>every asset's readings</b>, so
 *       a reading corrected after the round was signed off shows the signature as taken before
 *       that change. Only a round-level question may carry it: an asset's form cannot vouch for
 *       the other assets.</li>
 * </ul>
 *
 * <p>Why {@code ROUND} is safe to compute on the server: every write path applies a request's
 * entries <b>before</b> its round-level answers (the tablet's submit and progress push, and both
 * web saves), so a round signed after its last reading is stamped against the readings that
 * arrived with it.
 */
public enum SignatureScope {
    NONE,
    FORM,
    ROUND;

    /** The scope when nothing is stored — {@link #FORM}, which is what every signature was before. */
    public static final SignatureScope DEFAULT = FORM;

    /**
     * Reads a stored or posted value. Blank means the default; an unknown name is refused, because
     * guessing at what a signature should vouch for is the one thing this setting must not do.
     */
    public static SignatureScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown signature scope: " + raw);
        }
    }

    /** Tolerant read of a stored value: anything unreadable is the default, never an exception. */
    public static SignatureScope fromStored(Object raw) {
        if (raw == null) {
            return DEFAULT;
        }
        try {
            return valueOf(String.valueOf(raw).trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return DEFAULT;
        }
    }
}
