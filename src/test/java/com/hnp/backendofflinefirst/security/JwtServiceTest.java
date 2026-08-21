package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The token says who the holder is. It must not say — and must not be able to be made to say —
 * what they are allowed to do.
 *
 * <p>{@code roles} and {@code perms} claims used to be written here and turned straight into the
 * request's authorities. With the signing key shipped as a working default, that let anybody who
 * could read this repository mint a token granting anything; and even with a good key it froze a
 * user's permissions until the token expired. {@link ApiTokenAuthenticator} now resolves
 * authorities from the database, and this class guards the other half: that the claims are not
 * there to be trusted in the first place.
 */
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-key-32bytes-min!!";

    @Mock AppSettingsService appSettingsService;

    JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(appSettingsService);
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        jwtService.init();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issuing and verifying
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void issueAndVerifyRoundTripCarriesTheIdentity() {
        when(appSettingsService.getJwtExpiryMinutes()).thenReturn(60);

        JwtService.JwtToken issued = jwtService.issueToken(operator(42L, "operator1"));
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.jti()).isNotBlank();
        assertThat(issued.expiresAt()).isGreaterThan(System.currentTimeMillis());

        JwtService.VerifiedToken verified = jwtService.verify(issued.accessToken()).orElseThrow();
        assertThat(verified.jti()).isEqualTo(issued.jti());
        assertThat(verified.userId()).isEqualTo(42L);
        assertThat(verified.username()).isEqualTo("operator1");
    }

    @Test
    void eachIssuedTokenGetsItsOwnJti() {
        when(appSettingsService.getJwtExpiryMinutes()).thenReturn(60);
        AppUserDetails details = operator(7L, "operator2");

        assertThat(jwtService.issueToken(details).jti())
                .isNotEqualTo(jwtService.issueToken(details).jti());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What the token must NOT contain
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The regression test for the escalation bug: no authority claim is written at all.
     *
     * <p>Asserted against the decoded payload rather than against {@code verify}, because the
     * point is what leaves this process and reaches a device — a claim that is written but
     * ignored is still a claim an attacker can rewrite, and the next person to read
     * {@code perms} in a payload may well decide to use it.
     */
    @Test
    void theTokenCarriesNoRolesOrPermissionsClaim() {
        when(appSettingsService.getJwtExpiryMinutes()).thenReturn(60);

        String payload = decodePayload(jwtService.issueToken(
                operator(42L, "operator1")).accessToken());

        assertThat(payload)
                .as("""
                    the token must carry identity only. Authorities in a token are chosen by \
                    whoever holds the signing key — which ships as a working default — and are \
                    stale the moment a role changes. ApiTokenAuthenticator reads them from the \
                    database instead.""")
                .doesNotContain("perms")
                .doesNotContain("roles");
    }

    /** A person's name has no business sitting in a bearer token on a tablet. */
    @Test
    void theTokenCarriesNoPersonalName() {
        when(appSettingsService.getJwtExpiryMinutes()).thenReturn(60);

        assertThat(decodePayload(jwtService.issueToken(operator(42L, "operator1")).accessToken()))
                .doesNotContain("fullName")
                .doesNotContain("Operator One");
    }

    /** What it does carry, so the shape is pinned rather than merely un-pinned. */
    @Test
    void theTokenCarriesTheUserIdAndTheUsername() {
        when(appSettingsService.getJwtExpiryMinutes()).thenReturn(60);

        assertThat(decodePayload(jwtService.issueToken(operator(42L, "operator1")).accessToken()))
                .contains("\"uid\":42")
                .contains("\"sub\":\"operator1\"");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // What verification must reject
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aMalformedTokenIsRejected() {
        assertThat(jwtService.verify("not.a.valid.jwt")).isEmpty();
        assertThat(jwtService.verify("")).isEmpty();
        assertThat(jwtService.verify("....")).isEmpty();
    }

    /** The signature is the first gate: a token minted with another key is not ours. */
    @Test
    void aTokenSignedWithADifferentKeyIsRejected() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "a-completely-different-secret-key-32b!!".getBytes(StandardCharsets.UTF_8));
        String foreign = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("operator1")
                .claim(JwtService.CLAIM_UID, 42L)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        assertThat(jwtService.verify(foreign)).isEmpty();
    }

    @Test
    void anExpiredTokenIsRejected() {
        long past = System.currentTimeMillis() - 120_000;
        String expired = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("operator1")
                .claim(JwtService.CLAIM_UID, 42L)
                .issuedAt(new Date(past))
                .expiration(new Date(past + 60_000))
                .signWith(signingKey())
                .compact();

        assertThat(jwtService.verify(expired)).isEmpty();
    }

    /**
     * A correctly signed token with no {@code uid} is rejected rather than treated as anonymous.
     *
     * <p>There is no safe default for "which user is this". Letting it through with a null id
     * would push the decision onto the session lookup, where a null would have to be handled
     * again — and one of those two places would eventually stop handling it.
     */
    @Test
    void aTokenWithNoUserIdClaimIsRejected() {
        String anonymous = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("operator1")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey())
                .compact();

        assertThat(jwtService.verify(anonymous)).isEmpty();
    }

    /** An unsigned token is not a token, however plausible its payload. */
    @Test
    void anUnsignedTokenIsRejected() {
        String unsigned = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("admin")
                .claim(JwtService.CLAIM_UID, 1L)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .compact();

        assertThat(jwtService.verify(unsigned)).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Startup
    // ─────────────────────────────────────────────────────────────────────────

    /** HS256 needs 256 bits of key; a short secret must stop the boot, not weaken the signature. */
    @Test
    void aSecretShorterThan32BytesRefusesToStart() {
        JwtService weak = new JwtService(appSettingsService);
        ReflectionTestUtils.setField(weak, "secret", "too-short");

        org.assertj.core.api.Assertions.assertThatThrownBy(weak::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private AppUserDetails operator(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Operator One");
        user.setActive(true);
        return TestPrincipals.of(user, Set.of("OPERATOR"),
                Set.of("GET:/api/bootstrap", "POST:/api/log-sheets/batch"));
    }

    private static SecretKey signingKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** The JWT's middle segment, decoded — what actually travels to the device. */
    private static String decodePayload(String token) {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }
}
