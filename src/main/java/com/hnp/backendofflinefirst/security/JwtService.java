package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.service.AppSettingsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates HMAC JWT access tokens for mobile API clients.
 *
 * <h2>The token says WHO, and nothing about what they may do</h2>
 *
 * <p>It used to carry {@code roles} and {@code perms} claims, and
 * {@link com.hnp.backendofflinefirst.security.JwtAuthenticationFilter} built the request's
 * authorities straight out of them. That is the shape of the classic JWT privilege-escalation
 * bug, and it had two distinct consequences here.
 *
 * <p><b>Anyone holding the signing key chose their own permissions.</b> The key ships in
 * {@code application.properties} as a working default, so a deployment that never replaced it
 * gave every reader of this repository the ability to mint a token granting anything. The session
 * registry did not stop it: it only asked whether the {@code jti} was live, so an ordinary
 * operator could log in, take their own perfectly valid {@code jti}, and re-sign it with
 * {@code perms} listing every endpoint in the system.
 *
 * <p><b>And permissions were frozen at login.</b> Revoking a permission from a role, or a role
 * from a user, changed nothing until the token expired — hours later — because no request ever
 * consulted the database about what the holder could do.
 *
 * <p>So the token now carries {@code sub}, {@code uid} and {@code jti} and stops there.
 * Authorities are resolved from the database on every request by
 * {@link ApiTokenAuthenticator}, which is also what makes a permission change take effect on
 * the next request rather than at expiry. The client is unaffected: the PWA reads roles and
 * permissions from the <em>login response body</em>, never from the token — nothing decodes the
 * JWT on the device.
 *
 * <p>{@code fullName} is gone for a smaller reason: a bearer token sits in a tablet's storage and
 * in every request header, and there was no reason for a person's name to be in it. The login
 * response already carries it.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    /**
     * The only application claim. Identity, not authority — see the class javadoc.
     *
     * <p>{@code sub} carries the username as well, but only for readability in a log or a
     * decoder: the username can change, so {@code uid} is what everything resolves against.
     */
    public static final String CLAIM_UID = "uid";

    private final AppSettingsService appSettingsService;

    @Value("${app.auth.jwt.secret}")
    private String secret;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.auth.jwt.secret must be at least 32 bytes.");
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public JwtToken issueToken(AppUserDetails user) {
        long now = System.currentTimeMillis();
        long expiryMillis = appSettingsService.getJwtExpiryMinutes() * 60_000L;
        long expiresAt = now + expiryMillis;

        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .id(jti)
                .subject(user.getUsername())
                .claim(CLAIM_UID, user.getUserId())
                .issuedAt(new Date(now))
                .expiration(new Date(expiresAt))
                .signWith(signingKey)
                .compact();

        return new JwtToken(token, jti, now, expiresAt);
    }

    /**
     * Checks the signature and expiry, and returns the identity the token claims — nothing more.
     *
     * <p>A token that passes here is <b>cryptographically valid, not accepted</b>. Two further
     * conditions apply before a request is authenticated, and both live in
     * {@link ApiTokenAuthenticator}: the {@code jti} must be a live {@code api_sessions} row
     * <em>belonging to this {@code uid}</em>, and the authorities are read from the database
     * rather than from anything this method returns.
     *
     * <p>A token with no {@code uid} is rejected outright. There is no useful default for it —
     * treating it as anonymous would let a signature-only forgery through to the session lookup.
     */
    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = claims.get(CLAIM_UID, Long.class);
            if (userId == null) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(claims.getId(), userId, claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** A freshly minted token plus the metadata needed to register it in {@code api_sessions}. */
    public record JwtToken(String accessToken, String jti, long issuedAt, long expiresAt) {}

    /**
     * What a cryptographically valid token asserts about itself.
     *
     * <p>Deliberately not an {@code Authentication}: everything here is attacker-chosen if the
     * signing key ever leaks, so it is a claim to be checked, not a principal to be trusted.
     * {@code username} is carried for logging only — {@code userId} is what is resolved against.
     */
    public record VerifiedToken(String jti, Long userId, String username) {}
}
