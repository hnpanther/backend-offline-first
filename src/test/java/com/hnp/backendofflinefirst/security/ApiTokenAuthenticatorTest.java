package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every condition that stands between a bearer token and an authenticated request, each failing
 * on its own.
 *
 * <p>The four are listed on {@link ApiTokenAuthenticator}. They are tested separately because
 * they defend against different things and because the bug this class exists to prevent was
 * precisely a missing one: the filter used to check the signature and that the {@code jti} was
 * live, and nothing else — so a valid session plus a re-signed payload was full escalation.
 *
 * <p>Mocked rather than wired: the point here is that each condition is asked at all, and in an
 * order where a later one cannot be reached without the earlier ones passing.
 * {@code JwtForgeryIntegrationTest} then proves the same rules against a real HTTP request with
 * real forged tokens.
 */
@ExtendWith(MockitoExtension.class)
class ApiTokenAuthenticatorTest {

    private static final String TOKEN = "a.b.c";
    private static final String JTI = "jti-1";
    private static final long NOW = 1_700_000_000_000L;
    private static final Long USER_ID = 42L;

    @Mock JwtService jwtService;
    @Mock ApiSessionService apiSessionService;
    @Mock AppUserDetailsService userDetailsService;

    @InjectMocks ApiTokenAuthenticator authenticator;

    JwtService.VerifiedToken verified;

    @BeforeEach
    void setUp() {
        verified = new JwtService.VerifiedToken(JTI, USER_ID, "operator1");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The happy path, and where the authorities come from
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aLiveTokenAuthenticatesAsTheUserItNames() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(true);
        when(userDetailsService.loadById(USER_ID))
                .thenReturn(Optional.of(principal(true, "GET:/api/bootstrap")));

        Authentication auth = authenticator.authenticate(TOKEN, NOW).orElseThrow();

        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(((AppUserDetails) auth.getPrincipal()).getUserId()).isEqualTo(USER_ID);
        assertThat(auth.getCredentials()).as("no credential is carried past authentication").isNull();
    }

    /**
     * The authorities are the ones the database holds right now.
     *
     * <p>This is the whole fix in one assertion: whatever a token claims, the permissions on the
     * resulting {@code Authentication} are the ones {@code AppUserDetailsService} just read.
     */
    @Test
    void theAuthoritiesComeFromTheDatabaseAndNotFromTheToken() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(true);
        when(userDetailsService.loadById(USER_ID))
                .thenReturn(Optional.of(principal(true, "GET:/api/bootstrap")));

        Authentication auth = authenticator.authenticate(TOKEN, NOW).orElseThrow();

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("GET:/api/bootstrap");
    }

    /** A permission removed between two requests is gone on the second one. */
    @Test
    void aPermissionRemovedBetweenRequestsIsGoneOnTheNextOne() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(true);
        when(userDetailsService.loadById(USER_ID))
                .thenReturn(Optional.of(principal(true, "GET:/api/bootstrap", "POST:/api/log-sheets/batch")))
                .thenReturn(Optional.of(principal(true, "GET:/api/bootstrap")));

        assertThat(authenticator.authenticate(TOKEN, NOW).orElseThrow().getAuthorities()).hasSize(2);
        assertThat(authenticator.authenticate(TOKEN, NOW).orElseThrow().getAuthorities())
                .as("the same token, one revocation later")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Each condition, failing alone
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void aTokenThatDoesNotVerifyIsRejectedWithoutTouchingTheDatabase() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN, NOW)).isEmpty();

        verify(apiSessionService, never()).isSessionActive(any(), any(), anyLong());
        verify(userDetailsService, never()).loadById(any());
    }

    /**
     * A token whose {@code jti} is not a live session of that user is rejected, and the user is
     * never loaded.
     *
     * <p>The ordering matters as much as the outcome. {@code isSessionActive} is where the
     * jti-to-user binding is enforced, so reaching {@code loadById} first would mean resolving —
     * and logging — a user named by an unverified pairing.
     */
    @Test
    void aTokenWhoseSessionIsNotLiveForThatUserIsRejected() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(false);

        assertThat(authenticator.authenticate(TOKEN, NOW)).isEmpty();

        verify(userDetailsService, never()).loadById(any());
    }

    /** The session check is asked about THIS token's user, not merely about the jti. */
    @Test
    void theSessionCheckIsGivenTheUserIdTheTokenClaims() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(false);

        authenticator.authenticate(TOKEN, NOW);

        verify(apiSessionService).isSessionActive(eq(JTI), eq(USER_ID), eq(NOW));
    }

    /** The account was deleted after the token was issued. */
    @Test
    void aTokenNamingAUserWhoNoLongerExistsIsRejected() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(true);
        when(userDetailsService.loadById(USER_ID)).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate(TOKEN, NOW)).isEmpty();
    }

    /**
     * The account is deactivated.
     *
     * <p>Deactivation already revokes the user's sessions, so in practice the previous condition
     * catches this first. It is checked anyway on purpose: the two are independent, and this one
     * still holds if the revocation did not run.
     */
    @Test
    void aTokenNamingADeactivatedUserIsRejected() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(true);
        when(userDetailsService.loadById(USER_ID))
                .thenReturn(Optional.of(principal(false, "GET:/api/bootstrap")));

        assertThat(authenticator.authenticate(TOKEN, NOW)).isEmpty();
    }

    /** A user with no permissions authenticates and is authorised for nothing. */
    @Test
    void aUserWithNoPermissionsStillAuthenticates() {
        when(jwtService.verify(TOKEN)).thenReturn(Optional.of(verified));
        when(apiSessionService.isSessionActive(JTI, USER_ID, NOW)).thenReturn(true);
        when(userDetailsService.loadById(USER_ID)).thenReturn(Optional.of(principal(true)));

        Authentication auth = authenticator.authenticate(TOKEN, NOW).orElseThrow();

        assertThat(auth.getAuthorities()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private AppUserDetails principal(boolean active, String... permissions) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("operator1");
        user.setActive(active);
        return new AppUserDetails(user, Set.of("OPERATOR"), Set.of(permissions));
    }
}
