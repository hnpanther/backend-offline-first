package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.service.AttachmentReferences;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The {@code signature} field type (backend roadmap §32): a drawing that vouches for answers.
 *
 * <h2>The stored shape</h2>
 *
 * <pre>{@code
 * "supervisorSign": { "type": "signature",
 *                     "ids": ["b94ecdd6-…"],
 *                     "signedByUserId": 42,
 *                     "signedAt": 1790000000000,
 *                     "answersHash": "sha256:…",
 *                     "hashAlgo": "v1" }
 * }</pre>
 *
 * <p>The drawing is an ordinary attachment of kind {@code IMAGE} (a PNG), carried by the same
 * upload queue, ceiling logic, access rules, sweep and backups as a photograph — no migration.
 * The ids live under {@code "ids"}, the key every attachment reader already uses, so
 * {@link AttachmentReferences#idsOf} and the «is this answered» rule need no second case; only
 * the {@code type} differs, and {@link AttachmentReferences#looksLikeAttachmentValue} accepts
 * both, so a blind scan (cleanup protection, revision snapshots, the integration feed) never
 * mistakes a signature's drawing for an unreferenced file.
 *
 * <h2>The hash is computed by the server, and only by the server</h2>
 *
 * <p>This is the decision that makes it a signature rather than a picture. The hash covers the
 * answers the signer was shown, so an answer edited afterwards no longer matches and the
 * signature is shown as «امضا پیش از تغییر پاسخ‌ها گرفته شده» instead of quietly vouching for
 * values nobody saw. A hash needs a canonical form, and two of them — one in TypeScript, one here
 * — would have to agree bit for bit for ever; the first divergence would make every signature
 * taken offline read as stale with nothing to point at. So a client sends ids and, optionally, the
 * device time it was drawn; everything else is <b>ignored if sent</b> and stamped here, on the
 * write path, from what was just stored.
 *
 * <h2>What a signature covers</h2>
 *
 * <p><b>The answers of the form it is on</b>: an asset's signature field covers that entry's other
 * answers; a round-level one covers the round's own questions. Not the whole round — a tablet
 * pushes entries and the round's form in separate requests and in no fixed order, so a hash over
 * «everything» would be computed against whatever had arrived first and read as stale for a round
 * signed after it was complete. Signing off a whole round is what approval is for.
 *
 * <h2>When it is stamped</h2>
 *
 * <ul>
 *   <li>A <b>new or changed drawing</b> (different ids) is stamped with the session's user — never a
 *       user named in the payload — the device time if one was sent and plausible, and the hash.</li>
 *   <li>An <b>unchanged drawing</b> keeps the stamp it already has, exactly, so a resubmit of the
 *       same round compares equal and creates no revision and moves no authorship (AGENTS.md #20).
 *       That is also what makes a later edit of another answer show up as stale.</li>
 *   <li>A drawing with no stamp at all (only reachable through a hand edit) is stamped as new.</li>
 * </ul>
 *
 * <p>What it proves: that <em>the logged-in account</em> confirmed <em>these values</em> at
 * <em>this time</em>. Not a qualified electronic signature — that is a certificate-based project.
 */
public final class SignatureValues {

    public static final String DATA_TYPE = "signature";
    public static final String TYPE_VALUE = "signature";
    public static final String KEY_SIGNED_BY = "signedByUserId";
    public static final String KEY_SIGNED_AT = "signedAt";
    public static final String KEY_HASH = "answersHash";
    public static final String KEY_ALGO = "hashAlgo";
    public static final String ALGO_V1 = "v1";
    /** The {@link SignatureScope} a signature was taken under — recorded, so a later edit of the field cannot re-judge it. */
    public static final String KEY_SCOPE = "scope";

    /**
     * Upload rows one signature field may hold. A signature value names exactly one drawing; the
     * rows are allowed a little room because a re-signed field keeps its earlier drawing alive in
     * the revision history (the reclamation in {@code AttachmentService.enforceCount} never takes a
     * file a revision names), so a ceiling of one would refuse the second re-sign for ever. Ten
     * bounds what a misbehaving client can store while no honest round ever reaches it.
     */
    public static final int MAX_UPLOADS_PER_FIELD = 10;

    /** How far in the future a device's «signed at» may be before it is read as a wrong clock. */
    private static final long MAX_CLOCK_LEAD_MS = 24L * 60 * 60 * 1000;

    private SignatureValues() {}

    public static boolean isSignatureField(String dataType) {
        return dataType != null && DATA_TYPE.equalsIgnoreCase(dataType.trim());
    }

    /** Whether a stored value is shaped like a signature (whatever it holds). */
    public static boolean isSignatureValue(Object value) {
        return value instanceof Map<?, ?> map && TYPE_VALUE.equals(String.valueOf(map.get(AttachmentReferences.TYPE_KEY)));
    }

    /**
     * What a client's value may contribute: the drawing's ids and the device time. Every other key
     * — a signer, a hash — is dropped here, so a payload cannot assert who signed or what was
     * covered. Accepts the shapes an attachment value accepts (the web form posts repeated ids).
     *
     * @return the canonical unstamped value, or null when it names no drawing
     */
    public static Map<String, Object> canonicalIncoming(Object raw) {
        List<String> ids = AttachmentReferences.idsOf(raw);
        if (ids.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(AttachmentReferences.TYPE_KEY, TYPE_VALUE);
        out.put(AttachmentReferences.IDS_KEY, List.copyOf(ids));
        if (raw instanceof Map<?, ?> map) {
            Long signedAt = asLong(map.get(KEY_SIGNED_AT));
            if (signedAt != null) {
                out.put(KEY_SIGNED_AT, signedAt);
            }
        }
        return out;
    }

    /**
     * One asset's answers and the definitions they are judged by — what a {@link SignatureScope#ROUND}
     * signature covers besides the round's own questions.
     */
    public record EntryAnswers(Long assetId, Map<String, Object> formData, Collection<FieldDefinition> defs) {}

    /**
     * Finalises every signature in {@code next} — see the class comment for the rules.
     *
     * @param next         the answers about to be stored, already through {@code retainKnownKeys}
     * @param previous     what is stored now, or null
     * @param defs         the owner's field definitions (this asset's, or the round's questions)
     * @param actorUserId  the session's user: the only source of «who signed»
     * @param now          server time, used when the device sent no plausible signing time
     * @param roundEntries every asset's answers as they now stand, asked for only when a
     *                     {@link SignatureScope#ROUND} signature is being stamped; null on an
     *                     asset's own form, where that scope cannot be declared
     * @return a new map; {@code next} itself is not modified
     */
    public static Map<String, Object> stamp(Map<String, Object> next, Map<String, Object> previous,
                                            Collection<FieldDefinition> defs, Long actorUserId, long now,
                                            Supplier<List<EntryAnswers>> roundEntries) {
        if (next == null || next.isEmpty() || defs == null) {
            return next;
        }
        Map<String, FieldDefinition> signatureFields = signatureFields(defs);
        if (signatureFields.isEmpty()) {
            return next;
        }
        Map<String, Object> out = new LinkedHashMap<>(next);
        for (Map.Entry<String, FieldDefinition> field : signatureFields.entrySet()) {
            String key = field.getKey();
            if (!out.containsKey(key)) {
                continue;
            }
            Object incoming = out.get(key);
            List<String> ids = AttachmentReferences.idsOf(incoming);
            if (ids.isEmpty()) {
                out.remove(key);
                continue;
            }
            Object prev = previous == null ? null : previous.get(key);
            if (isStamped(prev) && AttachmentReferences.idsOf(prev).equals(ids)) {
                // Unchanged drawing: the stamp it had, byte for byte, so the comparison the write
                // path makes next calls this «unchanged».
                out.put(key, copy(prev));
                continue;
            }
            SignatureScope scope = effectiveScope(
                    FieldValidationSupport.signatureScope(field.getValue().getValidation()), roundEntries);
            Map<String, Object> stamped = new LinkedHashMap<>();
            stamped.put(AttachmentReferences.TYPE_KEY, TYPE_VALUE);
            stamped.put(AttachmentReferences.IDS_KEY, List.copyOf(ids));
            stamped.put(KEY_SIGNED_BY, actorUserId);
            stamped.put(KEY_SIGNED_AT, signedAt(incoming, now));
            stamped.put(KEY_SCOPE, scope.name());
            if (scope != SignatureScope.NONE) {
                stamped.put(KEY_HASH, coverageHash(scope, out, defs, roundEntries));
                stamped.put(KEY_ALGO, ALGO_V1);
            }
            out.put(key, stamped);
        }
        return out;
    }

    /** {@link #stamp(Map, Map, Collection, Long, long, Supplier)} on an asset's own form. */
    public static Map<String, Object> stamp(Map<String, Object> next, Map<String, Object> previous,
                                            Collection<FieldDefinition> defs, Long actorUserId, long now) {
        return stamp(next, previous, defs, actorUserId, now, null);
    }

    /** What a reader should be told about one stored signature. */
    public enum State {
        /** The answers it covers are exactly what it was given over. */
        VALID,
        /** An answer it covers changed after it was drawn. */
        STALE,
        /** Signed under {@link SignatureScope#NONE}: who and when, deliberately bound to no answers. */
        NOT_BOUND,
        /** It carries no stamp (a hand edit, or a hash algorithm this build does not know). */
        UNVERIFIED
    }

    /**
     * The verdict on a stored signature, judged against the answers as they are <b>now</b> and
     * the scope it was <b>taken under</b> — never today's setting of the field.
     *
     * @param roundEntries every asset's answers, for a signature taken under
     *                     {@link SignatureScope#ROUND}; when the caller cannot supply them (null),
     *                     such a signature is {@link State#UNVERIFIED} rather than guessed at
     */
    public static State state(Object value, Map<String, Object> formData, Collection<FieldDefinition> defs,
                              Supplier<List<EntryAnswers>> roundEntries) {
        if (!isStamped(value)) {
            return State.UNVERIFIED;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        SignatureScope scope = scopeOf(value);
        if (scope == SignatureScope.NONE) {
            return State.NOT_BOUND;
        }
        if (!ALGO_V1.equals(String.valueOf(map.get(KEY_ALGO)))) {
            return State.UNVERIFIED;
        }
        if (scope == SignatureScope.ROUND && roundEntries == null) {
            return State.UNVERIFIED;
        }
        String stored = String.valueOf(map.get(KEY_HASH));
        return stored.equals(coverageHash(scope, formData, defs, roundEntries)) ? State.VALID : State.STALE;
    }

    /** {@link #state(Object, Map, Collection, Supplier)} for a signature on an asset's own form. */
    public static State state(Object value, Map<String, Object> formData, Collection<FieldDefinition> defs) {
        return state(value, formData, defs, null);
    }

    /** The scope a stored signature was taken under; {@link SignatureScope#FORM} when it predates the key. */
    public static SignatureScope scopeOf(Object value) {
        return value instanceof Map<?, ?> map ? SignatureScope.fromStored(map.get(KEY_SCOPE)) : SignatureScope.DEFAULT;
    }

    public static Long signedByUserId(Object value) {
        return value instanceof Map<?, ?> map ? asLong(map.get(KEY_SIGNED_BY)) : null;
    }

    public static Long signedAt(Object value) {
        return value instanceof Map<?, ?> map ? asLong(map.get(KEY_SIGNED_AT)) : null;
    }

    /**
     * The fingerprint of one form's answers — every answered field of the owner except the
     * signatures themselves, by key. What a {@link SignatureScope#FORM} signature covers.
     */
    public static String answersHash(Map<String, Object> formData, Collection<FieldDefinition> defs) {
        return "sha256:" + sha256Hex(canonicalAnswers(formData, defs));
    }

    /**
     * The hash a signature taken under {@code scope} is compared against.
     *
     * <p>{@code ROUND} appends every asset's answers to the round's own, ordered by asset id so the
     * order the database returns rows in cannot change the hash, and each prefixed with its asset id
     * so «pump A read 12, pump B read 13» and the reverse are different rounds.
     */
    static String coverageHash(SignatureScope scope, Map<String, Object> formData, Collection<FieldDefinition> defs,
                               Supplier<List<EntryAnswers>> roundEntries) {
        if (scope != SignatureScope.ROUND || roundEntries == null) {
            return answersHash(formData, defs);
        }
        StringBuilder canonical = new StringBuilder(canonicalAnswers(formData, defs));
        List<EntryAnswers> entries = new ArrayList<>(roundEntries.get());
        entries.sort(Comparator.comparing(EntryAnswers::assetId, Comparator.nullsFirst(Long::compareTo)));
        for (EntryAnswers entry : entries) {
            canonical.append('\u001D').append('a').append(entry.assetId()).append('\u001F')
                    .append(canonicalAnswers(entry.formData(), entry.defs()));
        }
        return "sha256:" + sha256Hex(canonical.toString());
    }

    /**
     * Canonical so that the same answers always give the same text whichever client wrote them:
     * keys sorted; a number by its value ({@code "12"}, {@code 12}, {@code 12.0} are one reading); a
     * checkbox as a boolean; a list (a multiselect, attachment ids) as a sorted set; text trimmed;
     * any other object by its sorted keys. A key the definitions do not name is ignored — the write
     * path drops those anyway — and so is an unanswered one, so a blank and an absent key agree.
     */
    private static String canonicalAnswers(Map<String, Object> formData, Collection<FieldDefinition> defs) {
        Map<String, String> typeByKey = new TreeMap<>();
        if (defs != null) {
            for (FieldDefinition fd : defs) {
                if (fd != null && fd.getKey() != null && !isSignatureField(fd.getDataType())) {
                    typeByKey.put(fd.getKey(), fd.getDataType() == null ? "" : fd.getDataType().trim().toLowerCase());
                }
            }
        }
        StringBuilder canonical = new StringBuilder();
        if (formData != null) {
            for (Map.Entry<String, String> e : typeByKey.entrySet()) {
                Object value = formData.get(e.getKey());
                if (!FormDataValidationSupport.isAnswered(value)) {
                    continue;
                }
                canonical.append(e.getKey()).append('\u001F')
                        .append(canonicalValue(value, e.getValue())).append('\u001E');
            }
        }
        return canonical.toString();
    }

    /** ROUND without the round's answers to hand is stamped as FORM — and recorded as FORM. */
    private static SignatureScope effectiveScope(SignatureScope declared, Supplier<List<EntryAnswers>> roundEntries) {
        return declared == SignatureScope.ROUND && roundEntries == null ? SignatureScope.FORM : declared;
    }

    // ------------------------------------------------------------------ internals

    private static Map<String, FieldDefinition> signatureFields(Collection<FieldDefinition> defs) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        for (FieldDefinition fd : defs) {
            if (fd != null && fd.getKey() != null && isSignatureField(fd.getDataType())) {
                fields.put(fd.getKey(), fd);
            }
        }
        return fields;
    }

    /**
     * Stamped by the server: a signer, and either a hash or the explicit decision to take none.
     * A stamp is never accepted from a client ({@link #canonicalIncoming}), so this is the server's
     * own mark.
     */
    private static boolean isStamped(Object value) {
        if (!isSignatureValue(value)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return map.get(KEY_SIGNED_BY) != null
                && (map.get(KEY_HASH) != null || scopeOf(value) == SignatureScope.NONE && map.containsKey(KEY_SCOPE));
    }

    private static Map<String, Object> copy(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /** The device's signing time when it is plausible; otherwise the server's. */
    private static long signedAt(Object incoming, long now) {
        Long device = incoming instanceof Map<?, ?> map ? asLong(map.get(KEY_SIGNED_AT)) : null;
        if (device == null || device <= 0 || device > now + MAX_CLOCK_LEAD_MS) {
            return now;
        }
        return device;
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String canonicalValue(Object value, String dataType) {
        if ("number".equals(dataType)) {
            BigDecimal number = asDecimal(value);
            if (number != null) {
                return "n:" + number.stripTrailingZeros().toPlainString();
            }
        }
        if ("checkbox".equals(dataType)) {
            String text = String.valueOf(value).trim().toLowerCase();
            boolean on = Boolean.TRUE.equals(value) || "true".equals(text) || "1".equals(text) || "on".equals(text);
            return "b:" + on;
        }
        return generic(value);
    }

    private static String generic(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            BigDecimal number = asDecimal(value);
            return number == null ? "s:" + value : "n:" + number.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean b) {
            return "b:" + b;
        }
        if (value instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object item : c) {
                parts.add(generic(item));
            }
            parts.sort(String::compareTo);
            return "[" + String.join(",", parts) + "]";
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, String> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put(String.valueOf(k), generic(v)));
            StringBuilder sb = new StringBuilder("{");
            sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            return sb.append('}').toString();
        }
        return "s:" + String.valueOf(value).trim();
    }

    private static BigDecimal asDecimal(Object value) {
        try {
            if (value instanceof BigDecimal d) {
                return d;
            }
            if (value instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (value instanceof String s && !s.isBlank()) {
                return new BigDecimal(s.trim());
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
