package com.hnp.backendofflinefirst.security;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The cryptography of device enrolment, kept pure so every rule here can be tested without a
 * server, a database or a tablet.
 *
 * <h2>The one detail that decides whether any of this works</h2>
 *
 * <p>WebCrypto's {@code sign({name: 'ECDSA', hash: 'SHA-256'})} returns the signature as the raw
 * concatenation {@code r ‖ s} — 64 bytes for P-256, the IEEE P1363 format. Java's familiar
 * {@code SHA256withECDSA} expects ASN.1 DER instead. Pair the two and <b>every</b> signature is
 * refused, with nothing in the message to say why. So verification uses
 * {@code SHA256withECDSAinP1363Format}, and {@code DeviceKeysTest} pins it with a vector produced
 * by WebCrypto itself rather than by Java — a vector Java made for itself would agree with
 * whatever Java expects and prove nothing.
 *
 * <h2>What a device signs</h2>
 *
 * <p>Each message names its purpose on the first line, so a signature made for one step can
 * never be replayed as the other: an enrolment signature is not a login signature even if the
 * remaining fields happened to coincide.
 */
public final class DeviceKeys {

    /** A P-256 SPKI is 91 bytes, 124 characters of base64. Anything far larger is not a key. */
    public static final int MAX_PUBLIC_KEY_BASE64_LENGTH = 512;

    /** P1363 for P-256: two 32-byte integers. Base64 of 64 bytes is 88 characters. */
    private static final int MAX_SIGNATURE_BASE64_LENGTH = 256;

    static final String LOGIN_PURPOSE = "mfdcs-login";
    static final String ENROLL_PURPOSE = "mfdcs-enroll";

    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSAinP1363Format";

    private static final ECParameterSpec P256 = loadP256();

    private DeviceKeys() {
    }

    /** A device's public key, in the canonical encoding this server stores and fingerprints. */
    public record ParsedKey(ECPublicKey key, String canonicalBase64, String fingerprint) {
    }

    /**
     * Parses a base64 X.509 SubjectPublicKeyInfo and accepts it only if it is an EC key on
     * P-256.
     *
     * <p>Re-encoded through Java before it is stored or fingerprinted, so two different byte
     * encodings of one key cannot register as two devices.
     *
     * @return empty for anything that is not exactly that — no exception text reaches a caller,
     *         because the only honest message for a malformed key is "not a valid key".
     */
    public static Optional<ParsedKey> parsePublicKey(String base64Spki) {
        if (base64Spki == null || base64Spki.isBlank()
                || base64Spki.length() > MAX_PUBLIC_KEY_BASE64_LENGTH) {
            return Optional.empty();
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64Spki.trim());
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof ECPublicKey ec) || !isP256(ec.getParams())) {
                return Optional.empty();
            }
            byte[] canonical = ec.getEncoded();
            return Optional.of(new ParsedKey(ec, Base64.getEncoder().encodeToString(canonical),
                    sha256Hex(canonical)));
        } catch (Exception e) {
            // IllegalArgumentException (not base64), InvalidKeySpecException (not SPKI), and
            // anything a malformed point throws on its way through the provider.
            return Optional.empty();
        }
    }

    /**
     * Verifies a WebCrypto ECDSA P-256 / SHA-256 signature (P1363, base64) over a UTF-8 message.
     *
     * @return false for every failure, including a malformed signature: callers must not be able
     *         to tell "badly encoded" from "wrong", because both mean the same thing to them.
     */
    public static boolean verify(ECPublicKey key, String message, String base64Signature) {
        if (key == null || message == null || base64Signature == null
                || base64Signature.isBlank() || base64Signature.length() > MAX_SIGNATURE_BASE64_LENGTH) {
            return false;
        }
        try {
            byte[] signature = Base64.getDecoder().decode(base64Signature.trim());
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * What a device signs to log in: a server nonce, so the proof cannot be replayed, and the
     * username, so a captured proof cannot be attached to a different account.
     */
    public static String loginMessage(String nonce, String username) {
        return LOGIN_PURPOSE + "\n" + nonce + "\n" + (username == null ? "" : username);
    }

    /**
     * What a device signs to complete its own enrolment: the request it is completing and the
     * code it was given. Signing the code is what makes an overheard code useless on any other
     * device — only the key that opened this request can produce this signature.
     */
    public static String enrollMessage(long requestId, String normalizedCode) {
        return ENROLL_PURPOSE + "\n" + requestId + "\n" + normalizedCode;
    }

    /**
     * The code as typed, reduced to ASCII digits.
     *
     * <p>A Persian keyboard types «۱۲۳۴» and an Arabic one «١٢٣٤», and either is the operator
     * typing the right code. Spaces and dashes are the grouping the panel shows («1234 5678»).
     * Anything else is left in place, so it cannot match — never silently dropped, because a
     * code reduced to its digits by deleting a stray letter would be a different code.
     */
    public static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '۰' && c <= '۹') {
                out.append((char) ('0' + (c - '۰')));
            } else if (c >= '٠' && c <= '٩') {
                out.append((char) ('0' + (c - '٠')));
            } else if (c == ' ' || c == '-' || c == '‐' || c == '‑' || c == ' '
                    || c == '‌' || c == '\t') {
                // grouping and stray whitespace (including the zero-width non-joiner a Persian
                // keyboard inserts) — not part of the code
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Equality that takes the same time however early the two strings differ. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isP256(ECParameterSpec params) {
        return params != null
                && params.getCurve().equals(P256.getCurve())
                && params.getGenerator().equals(P256.getGenerator())
                && params.getOrder().equals(P256.getOrder())
                && params.getCofactor() == P256.getCofactor();
    }

    private static ECParameterSpec loadP256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new IllegalStateException("P-256 parameters unavailable", e);
        }
    }
}
