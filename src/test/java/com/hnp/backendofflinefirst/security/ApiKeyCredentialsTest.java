package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The presented-key format and its verification.
 *
 * <p>The case that earns this file is {@link #parsesAKeyWhoseSecretContainsUnderscores()}. The
 * secret is base64url, whose alphabet includes {@code _}, so a naive {@code split("_")} works
 * on most generated keys and truncates roughly one in five — an intermittent "invalid key"
 * that looks like a server fault and reproduces on nobody's machine.
 */
class ApiKeyCredentialsTest {

    @Test
    void generatesAKeyThatParsesBackToItself() {
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();

        Optional<ApiKeyCredentials> parsed = ApiKeyCredentials.parse(credentials.presentedKey());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().keyId()).isEqualTo(credentials.keyId());
        assertThat(parsed.get().secret()).isEqualTo(credentials.secret());
    }

    @Test
    void parsesAKeyWhoseSecretContainsUnderscores() {
        // A secret from the base64url alphabet, containing the very character that separates
        // the two halves. Splitting on every underscore would return "aa" and reject the key.
        ApiKeyCredentials original = new ApiKeyCredentials("0123456789abcdef", "aa_bb_cc-dd");

        Optional<ApiKeyCredentials> parsed = ApiKeyCredentials.parse(original.presentedKey());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().keyId()).isEqualTo("0123456789abcdef");
        assertThat(parsed.get().secret()).isEqualTo("aa_bb_cc-dd");
    }

    @Test
    void aGeneratedSecretIsWideEnoughToBeUnguessable() {
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();

        // 32 random bytes, base64url without padding.
        assertThat(credentials.secret()).hasSize(43);
        assertThat(credentials.keyId()).hasSize(16).matches("[0-9a-f]+");
        assertThat(credentials.presentedKey()).startsWith("lsk_");
    }

    @Test
    void everyGeneratedKeyIsDistinct() {
        Set<String> keyIds = new HashSet<>();
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ApiKeyCredentials credentials = ApiKeyCredentials.generate();
            keyIds.add(credentials.keyId());
            secrets.add(credentials.secret());
        }
        assertThat(keyIds).hasSize(500);
        assertThat(secrets).hasSize(500);
    }

    @Test
    void matchesItsOwnHashAndNothingElse() {
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();
        ApiKeyCredentials other = ApiKeyCredentials.generate();

        assertThat(credentials.matches(credentials.secretHash())).isTrue();
        assertThat(credentials.matches(other.secretHash())).isFalse();
    }

    @Test
    void aMalformedStoredHashIsARefusalAndNotACrash() {
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();

        // A hand-edited or truncated column must deny access, not throw out of the filter and
        // turn a bad credential into a 500.
        assertThat(credentials.matches(null)).isFalse();
        assertThat(credentials.matches("")).isFalse();
        assertThat(credentials.matches("not-hex")).isFalse();
        assertThat(credentials.matches("abc")).isFalse();
    }

    @Test
    void theDisplayPrefixCarriesNoSecret() {
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();

        assertThat(credentials.displayPrefix()).isEqualTo("lsk_" + credentials.keyId());
        assertThat(credentials.displayPrefix()).doesNotContain(credentials.secret());
    }

    @Test
    void toStringNeverCarriesTheSecret() {
        // This object can reach a log line through an exception message or a debugger dump.
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();

        assertThat(credentials.toString())
                .contains(credentials.keyId())
                .doesNotContain(credentials.secret())
                .contains("***");
    }

    @Test
    void refusesEveryShapeThatIsNotAKey() {
        assertThat(ApiKeyCredentials.parse(null)).isEmpty();
        assertThat(ApiKeyCredentials.parse("")).isEmpty();
        assertThat(ApiKeyCredentials.parse("   ")).isEmpty();
        assertThat(ApiKeyCredentials.parse("secret-without-prefix")).isEmpty();
        // Right prefix, no separator: there is no secret half at all.
        assertThat(ApiKeyCredentials.parse("lsk_abcdef")).isEmpty();
        // Separator present but one half empty.
        assertThat(ApiKeyCredentials.parse("lsk__secret")).isEmpty();
        assertThat(ApiKeyCredentials.parse("lsk_keyid_")).isEmpty();
        // A JWT is the other credential this system uses; it must not be mistaken for a key.
        assertThat(ApiKeyCredentials.parse("Bearer eyJhbGciOiJIUzI1NiJ9.e30.abc")).isEmpty();
    }

    @Test
    void surroundingWhitespaceIsForgiven() {
        // Keys get pasted into configuration files. A trailing newline is not a wrong key.
        ApiKeyCredentials credentials = ApiKeyCredentials.generate();

        assertThat(ApiKeyCredentials.parse("  " + credentials.presentedKey() + "\n"))
                .contains(credentials);
    }

    @Test
    void theHashIsSha256Hex() {
        // Pinned against a known vector so a change of algorithm cannot slip in unnoticed —
        // it would invalidate every key already issued to every integrator.
        assertThat(ApiKeyCredentials.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
