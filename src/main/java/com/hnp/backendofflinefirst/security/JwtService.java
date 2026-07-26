package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Issues and validates HMAC JWT access tokens for mobile API clients. */
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String CLAIM_UID = "uid";
    public static final String CLAIM_FULL_NAME = "fullName";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_PERMS = "perms";

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

        List<String> roles = List.copyOf(user.getRoleCodes());
        List<String> perms = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        String jti = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .id(jti)
                .subject(user.getUsername())
                .claim(CLAIM_UID, user.getUserId())
                .claim(CLAIM_FULL_NAME, user.getUser().getFullName())
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_PERMS, perms)
                .issuedAt(new Date(now))
                .expiration(new Date(expiresAt))
                .signWith(signingKey)
                .compact();

        return new JwtToken(token, jti, now, expiresAt);
    }

    /**
     * Verifies the signature and expiry only. Whether the token is still <em>accepted</em>
     * also depends on its {@code jti} being an live row in {@code api_sessions} — that
     * check lives in {@link JwtAuthenticationFilter} so revocation takes effect immediately.
     */
    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            AppUserDetails user = toUserDetails(claims);
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    user, null, user.getAuthorities());
            return Optional.of(new VerifiedToken(auth, claims.getId(), user.getUserId()));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private AppUserDetails toUserDetails(Claims claims) {
        User user = new User();
        user.setId(claims.get(CLAIM_UID, Long.class));
        user.setUsername(claims.getSubject());
        user.setFullName(claims.get(CLAIM_FULL_NAME, String.class));
        user.setActive(true);

        Set<String> roles = new HashSet<>();
        Object rolesClaim = claims.get(CLAIM_ROLES);
        if (rolesClaim instanceof List<?> list) {
            list.forEach(r -> roles.add(String.valueOf(r)));
        }

        Set<String> perms = new HashSet<>();
        Object permsClaim = claims.get(CLAIM_PERMS);
        if (permsClaim instanceof List<?> list) {
            list.forEach(p -> perms.add(String.valueOf(p)));
        }

        return new AppUserDetails(user, roles, perms);
    }

    /** A freshly minted token plus the metadata needed to register it in {@code api_sessions}. */
    public record JwtToken(String accessToken, String jti, long issuedAt, long expiresAt) {}

    /** A cryptographically valid token; still subject to the session-registry check. */
    public record VerifiedToken(Authentication authentication, String jti, Long userId) {}
}
