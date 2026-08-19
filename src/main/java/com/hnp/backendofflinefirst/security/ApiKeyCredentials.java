package com.hnp.backendofflinefirst.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The presented-key format, and the only place that knows it.
 *
 * <pre>
 *   lsk_&lt;keyId&gt;_&lt;secret&gt;
 *        └ 16 hex   └ 43 chars of base64url (256 bits)
 * </pre>
 *
 * <p><b>Why the key has two halves.</b> {@code keyId} is public and indexed, so verifying a
 * request is one lookup on a unique index plus one hash comparison. Storing a hash of the
 * <em>whole</em> key instead would force either a table scan that hashes every row, or making
 * the hash the primary key — and then no administrator could ever be shown which key a usage
 * row belongs to.
 *
 * <p><b>Why the split is on {@code indexOf}, not {@code String.split("_")}.</b> The secret is
 * base64url, whose alphabet contains {@code _}. Splitting on every underscore would truncate
 * roughly one key in five at a character the caller cannot see, producing an intermittent
 * "invalid key" that looks like a server fault. {@code keyId} is hex and therefore never
 * contains an underscore, so the <em>second</em> underscore is unambiguously the separator and
 * everything after it is the secret, whatever it contains.
 *
 * <p><b>Why SHA-256 and not BCrypt.</b> A slow KDF exists to make guessing a low-entropy
 * password expensive. There is no password here: the secret is 256 bits from
 * {@link SecureRandom}, so brute force is not on the table and the only thing a slow hash buys
 * is ~100 ms added to every request of an integration that may poll once a minute. This is the
 * same reasoning behind GitHub's and Stripe's key formats.
 *
 * <p>Instances hold a live credential. {@link #toString()} is redacted so one cannot reach a
 * log line through a stack trace, an exception message, or a debugger dump.
 */
public record ApiKeyCredentials(String keyId, String secret) {

    public static final String PREFIX = "lsk_";

    private static final int KEY_ID_BYTES = 8;    // → 16 hex characters
    private static final int SECRET_BYTES = 32;   // → 256 bits, 43 base64url characters

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    /** Mints a fresh key. The caller must show the presented form once and then forget it. */
    public static ApiKeyCredentials generate() {
        byte[] idBytes = new byte[KEY_ID_BYTES];
        byte[] secretBytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(idBytes);
        RANDOM.nextBytes(secretBytes);
        return new ApiKeyCredentials(HexFormat.of().formatHex(idBytes), BASE64_URL.encodeToString(secretBytes));
    }

    /**
     * Parses what a client sent. Empty for anything that is not this format — an unparseable
     * key and an unknown key are the same answer to the caller on purpose, so the response
     * cannot be used to probe the format.
     */
    public static Optional<ApiKeyCredentials> parse(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        String trimmed = presented.trim();
        if (!trimmed.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int separator = trimmed.indexOf('_', PREFIX.length());
        if (separator < 0) {
            return Optional.empty();
        }
        String keyId = trimmed.substring(PREFIX.length(), separator);
        String secret = trimmed.substring(separator + 1);
        if (keyId.isEmpty() || secret.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ApiKeyCredentials(keyId, secret));
    }

    /** The single string handed to the integrator, exactly once. */
    public String presentedKey() {
        return PREFIX + keyId + "_" + secret;
    }

    /**
     * What the admin list shows: enough for a human to tell two keys apart, and nothing an
     * attacker can use — the secret is not in it.
     */
    public String displayPrefix() {
        return PREFIX + keyId;
    }

    public String secretHash() {
        return sha256Hex(secret);
    }

    /**
     * Constant-time comparison against a stored hash.
     *
     * <p>{@code String.equals} short-circuits on the first differing character, which leaks how
     * much of a guess was right. {@link MessageDigest#isEqual} does not. The cost of getting
     * this wrong is small here — an attacker would still need the {@code key_id} first — but it
     * is free to get right, and the next person to copy this method may not have that luxury.
     */
    public boolean matches(String storedHashHex) {
        if (storedHashHex == null) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(storedHashHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(sha256(secret), expected);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; if it is missing, nothing about this application is safe.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    @Override
    public String toString() {
        return "ApiKeyCredentials[keyId=" + keyId + ", secret=***]";
    }
}
