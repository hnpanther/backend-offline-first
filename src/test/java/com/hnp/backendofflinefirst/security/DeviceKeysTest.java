package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cryptography of device enrolment, against what a browser actually produces.
 *
 * <h2>Why the vector below came from WebCrypto and not from Java</h2>
 *
 * <p>WebCrypto signs ECDSA as raw {@code r ‖ s} (IEEE P1363, 64 bytes for P-256); Java's usual
 * {@code SHA256withECDSA} expects DER. A vector Java generated for itself would agree with
 * whichever format Java was asked for and prove nothing about a tablet. These values were made by
 * Node's WebCrypto — the same API, the same output format as Chrome and the Android WebView —
 * with a key created {@code extractable: false}, exactly as the PWA creates it.
 */
class DeviceKeysTest {

    /** ECDSA P-256 public key exported by WebCrypto as SPKI. */
    private static final String WEBCRYPTO_SPKI =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELanBegxM3l6R0J9mLcrWhBpiOYtCZawLsG5pFREoDMjMKMn5YvJGtMYBrPHw3PuBsD7b5VWALQ9clhlfdt7QHg==";

    private static final String LOGIN_MESSAGE = "mfdcs-login\nNONCE-abc_123\nali.rezaei";
    private static final String LOGIN_SIGNATURE =
            "z/VxOhZJ6qAtjBbdtKUyd1NAD9hprA4fGjKoLbhRMBTJDb84EQwW+CE0X7hbW4LkbgIumH1M7RDDGpCER/6usA==";

    private static final String ENROLL_MESSAGE = "mfdcs-enroll\n42\n12345678";
    private static final String ENROLL_SIGNATURE =
            "ilyCEKn8z2qzGWPtittUzxMU2NZeMRazwgnSM51PierlMhZ05gr/U2lbbAMAPh27bkMFLLfK7GDjMBvRseBdiA==";

    /** A Persian username: the message is UTF-8 on both sides, and this is where that is proven. */
    private static final String PERSIAN_USER_MESSAGE = "mfdcs-login\nN2\nعلی";
    private static final String PERSIAN_USER_SIGNATURE =
            "Rl79lGZZ9lJwSecalcOki4O58oZav6dfVgyvnuAjtQylOghdOIHYyvOw9SImcXQSb6RuHVA0hihKPb9bJorlVA==";

    // ------------------------------------------------------------------ the format

    @Test
    void aWebCryptoSignatureVerifies() {
        ECPublicKey key = webCryptoKey();

        assertThat(DeviceKeys.verify(key, LOGIN_MESSAGE, LOGIN_SIGNATURE)).isTrue();
        assertThat(DeviceKeys.verify(key, ENROLL_MESSAGE, ENROLL_SIGNATURE)).isTrue();
        assertThat(DeviceKeys.verify(key, PERSIAN_USER_MESSAGE, PERSIAN_USER_SIGNATURE)).isTrue();
    }

    @Test
    void theMessageBuildersProduceExactlyWhatTheTabletSigns() {
        // The PWA builds these strings itself (deviceIdentity.ts). If either side changes the
        // layout, this test and its twin on the PWA side are where it shows.
        assertThat(DeviceKeys.loginMessage("NONCE-abc_123", "ali.rezaei")).isEqualTo(LOGIN_MESSAGE);
        assertThat(DeviceKeys.enrollMessage(42, "12345678")).isEqualTo(ENROLL_MESSAGE);
        assertThat(DeviceKeys.loginMessage("N2", "علی")).isEqualTo(PERSIAN_USER_MESSAGE);
    }

    @Test
    void aDerSignatureIsRefused() throws Exception {
        // THE TRAP. Java's default ECDSA encoding is DER; the browser's is P1363. This is the
        // failure the whole format choice exists to avoid, pinned so nobody "simplifies" the
        // algorithm name back to the familiar one.
        KeyPair pair = javaP256();
        Signature der = Signature.getInstance("SHA256withECDSA");
        der.initSign(pair.getPrivate());
        der.update(LOGIN_MESSAGE.getBytes(StandardCharsets.UTF_8));
        String derSignature = Base64.getEncoder().encodeToString(der.sign());

        assertThat(DeviceKeys.verify((ECPublicKey) pair.getPublic(), LOGIN_MESSAGE, derSignature)).isFalse();

        Signature p1363 = Signature.getInstance("SHA256withECDSAinP1363Format");
        p1363.initSign(pair.getPrivate());
        p1363.update(LOGIN_MESSAGE.getBytes(StandardCharsets.UTF_8));
        String rawSignature = Base64.getEncoder().encodeToString(p1363.sign());
        assertThat(DeviceKeys.verify((ECPublicKey) pair.getPublic(), LOGIN_MESSAGE, rawSignature)).isTrue();
    }

    // ------------------------------------------------------------------ what must fail

    @Test
    void aSignatureDoesNotCarryOverToAnyOtherMessage() {
        ECPublicKey key = webCryptoKey();

        // Another username, another nonce, another code: each is a different message.
        assertThat(DeviceKeys.verify(key, "mfdcs-login\nNONCE-abc_123\nother.user", LOGIN_SIGNATURE)).isFalse();
        assertThat(DeviceKeys.verify(key, "mfdcs-login\nNONCE-abc_124\nali.rezaei", LOGIN_SIGNATURE)).isFalse();
        assertThat(DeviceKeys.verify(key, "mfdcs-enroll\n42\n12345679", ENROLL_SIGNATURE)).isFalse();
        // And a signature for one purpose is not a signature for the other.
        assertThat(DeviceKeys.verify(key, ENROLL_MESSAGE, LOGIN_SIGNATURE)).isFalse();
    }

    @Test
    void anotherKeysSignatureIsRefused() throws Exception {
        KeyPair stranger = javaP256();
        assertThat(DeviceKeys.verify((ECPublicKey) stranger.getPublic(), LOGIN_MESSAGE, LOGIN_SIGNATURE)).isFalse();
    }

    @Test
    void aMalformedSignatureIsSimplyFalse() {
        ECPublicKey key = webCryptoKey();

        assertThat(DeviceKeys.verify(key, LOGIN_MESSAGE, null)).isFalse();
        assertThat(DeviceKeys.verify(key, LOGIN_MESSAGE, "")).isFalse();
        assertThat(DeviceKeys.verify(key, LOGIN_MESSAGE, "not base64 !!")).isFalse();
        assertThat(DeviceKeys.verify(key, LOGIN_MESSAGE, Base64.getEncoder().encodeToString(new byte[64]))).isFalse();
        assertThat(DeviceKeys.verify(key, LOGIN_MESSAGE, "A".repeat(5000))).isFalse();
        assertThat(DeviceKeys.verify(null, LOGIN_MESSAGE, LOGIN_SIGNATURE)).isFalse();
    }

    // ------------------------------------------------------------------ keys

    @Test
    void aWebCryptoKeyParsesWithAStableFingerprint() {
        DeviceKeys.ParsedKey parsed = DeviceKeys.parsePublicKey(WEBCRYPTO_SPKI).orElseThrow();

        assertThat(parsed.fingerprint()).hasSize(64).matches("[0-9a-f]+");
        // WebCrypto's SPKI is already the canonical encoding, so storing it re-encoded changes
        // nothing — and parsing the stored form again yields the same identity.
        assertThat(parsed.canonicalBase64()).isEqualTo(WEBCRYPTO_SPKI);
        assertThat(DeviceKeys.parsePublicKey(parsed.canonicalBase64()).orElseThrow().fingerprint())
                .isEqualTo(parsed.fingerprint());
    }

    @Test
    void onlyAP256EcKeyIsAccepted() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        String rsaSpki = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded());

        KeyPairGenerator p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        String p384Spki = Base64.getEncoder().encodeToString(p384.generateKeyPair().getPublic().getEncoded());

        assertThat(DeviceKeys.parsePublicKey(rsaSpki)).isEmpty();
        assertThat(DeviceKeys.parsePublicKey(p384Spki)).isEmpty();
        assertThat(DeviceKeys.parsePublicKey(null)).isEmpty();
        assertThat(DeviceKeys.parsePublicKey("  ")).isEmpty();
        assertThat(DeviceKeys.parsePublicKey("@@@ not base64 @@@")).isEmpty();
        assertThat(DeviceKeys.parsePublicKey(Base64.getEncoder().encodeToString(new byte[91]))).isEmpty();
        assertThat(DeviceKeys.parsePublicKey("A".repeat(DeviceKeys.MAX_PUBLIC_KEY_BASE64_LENGTH + 4))).isEmpty();
    }

    // ------------------------------------------------------------------ the code as typed

    @Test
    void aCodeTypedOnAPersianOrArabicKeyboardIsTheSameCode() {
        assertThat(DeviceKeys.normalizeCode("۱۲۳۴۵۶۷۸")).isEqualTo("12345678");
        assertThat(DeviceKeys.normalizeCode("١٢٣٤٥٦٧٨")).isEqualTo("12345678");
        // The grouping the panel shows, and the stray characters a phone keyboard inserts.
        assertThat(DeviceKeys.normalizeCode("1234 5678")).isEqualTo("12345678");
        assertThat(DeviceKeys.normalizeCode(" 1234-5678 ")).isEqualTo("12345678");
        assertThat(DeviceKeys.normalizeCode("۱۲۳۴‌۵۶۷۸")).isEqualTo("12345678");
        assertThat(DeviceKeys.normalizeCode(null)).isEmpty();
    }

    @Test
    void anythingElseInACodeIsKeptSoItCannotMatch() {
        // Dropping a stray letter would turn a wrong code into a different, possibly right one.
        assertThat(DeviceKeys.normalizeCode("1234a5678")).isEqualTo("1234a5678");
        assertThat(DeviceKeys.normalizeCode("12345678.")).isEqualTo("12345678.");
    }

    @Test
    void constantTimeEqualsHandlesNull() {
        assertThat(DeviceKeys.constantTimeEquals("a", "a")).isTrue();
        assertThat(DeviceKeys.constantTimeEquals("a", "b")).isFalse();
        assertThat(DeviceKeys.constantTimeEquals(null, "a")).isFalse();
        assertThat(DeviceKeys.constantTimeEquals("a", null)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static ECPublicKey webCryptoKey() {
        return DeviceKeys.parsePublicKey(WEBCRYPTO_SPKI).orElseThrow().key();
    }

    private static KeyPair javaP256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
