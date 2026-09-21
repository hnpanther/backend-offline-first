package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.security.LdapAuthenticationService.BindResult;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppAuthenticationProviderAuthTest {

    @Mock UserDetailsService userDetailsService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock LdapAuthenticationService ldapAuthenticationService;
    @Mock LoginAttemptService loginAttemptService;

    AppAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AppAuthenticationProvider(
                userDetailsService, passwordEncoder, ldapAuthenticationService, loginAttemptService);
    }

    @Test
    void localUserAuthenticatesWithBcrypt() {
        User user = user("alice", UserAuthType.LOCAL, "hash");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(principal(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        var auth = provider.authenticate(token("alice", "secret"));

        assertThat(auth.getPrincipal()).isInstanceOf(AppUserDetails.class);
        verify(ldapAuthenticationService, never()).bind(anyString(), any());
    }

    @Test
    void activeDirectoryUserAuthenticatesViaLdap() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.bind("h.nikouei", "ad-pass")).thenReturn(BindResult.AUTHENTICATED);

        provider.authenticate(token("h.nikouei", "ad-pass"));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(loginAttemptService).recordSuccess("h.nikouei");
    }

    @Test
    void hybridUserPrefersLocalPassword() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("local", "hash")).thenReturn(true);

        provider.authenticate(token("bob", "local"));

        verify(ldapAuthenticationService, never()).bind(anyString(), any());
    }

    @Test
    void hybridUserFallsBackToLdapWhenLocalFails() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("ad-pass", "hash")).thenReturn(false);
        when(ldapAuthenticationService.bind("bob", "ad-pass")).thenReturn(BindResult.AUTHENTICATED);

        provider.authenticate(token("bob", "ad-pass"));

        verify(ldapAuthenticationService).bind("bob", "ad-pass");
        verify(loginAttemptService).recordSuccess("bob");
    }

    @Test
    void loginNormalizesUsernameWithDomain() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.bind(eq("h.nikouei"), anyString())).thenReturn(BindResult.AUTHENTICATED);

        provider.authenticate(token("h.nikouei@site.local", "pw"));

        verify(userDetailsService).loadUserByUsername("h.nikouei");
    }

    @Test
    void badCredentialsWhenAllChecksFail() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(ldapAuthenticationService.bind("bob", "wrong")).thenReturn(BindResult.REJECTED);

        assertThatThrownBy(() -> provider.authenticate(token("bob", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verify(loginAttemptService).recordFailure("bob");
    }

    @Test
    void failedLoginRecordsAttemptOnThrottle() {
        User user = user("bob", UserAuthType.LOCAL, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> provider.authenticate(token("bob", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginAttemptService).recordFailure("bob");
    }

    @Test
    void successfulLoginResetsThrottle() {
        User user = user("bob", UserAuthType.LOCAL, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        provider.authenticate(token("bob", "secret"));

        verify(loginAttemptService).recordSuccess("bob");
    }

    @Test
    void lockedUsernameIsRejectedBeforeTouchingLdapOrPasswordEncoder() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(loginAttemptService.remainingLockSeconds("h.nikouei")).thenReturn(300L);

        assertThatThrownBy(() -> provider.authenticate(token("h.nikouei", "ad-pass")))
                .isInstanceOf(LockedException.class);

        verify(ldapAuthenticationService, never()).bind(anyString(), any());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    // -- the directory being down ----------------------------------------------------------

    /**
     * The case the whole design protects: a HYBRID account with a correct local password signs
     * in while Active Directory is unreachable — and without the directory ever being asked,
     * because the local password is checked first. Stubbed to say "unavailable" so the test
     * would notice if the order were ever reversed.
     */
    @Test
    void hybridLocalPasswordWorksWhileTheDirectoryIsDown() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("local", "hash")).thenReturn(true);
        lenient().when(ldapAuthenticationService.bind(anyString(), any())).thenReturn(BindResult.UNAVAILABLE);

        var auth = provider.authenticate(token("bob", "local"));

        assertThat(auth.isAuthenticated()).isTrue();
        verify(ldapAuthenticationService, never()).bind(anyString(), any());
        verify(loginAttemptService).recordSuccess("bob");
    }

    /**
     * Stronger than "unavailable": the directory client <em>throws</em>. The service is written
     * never to, but a defect there must still not cost an administrator their local login, and
     * for an account that does need the directory it must surface as an outage, not as a 500.
     */
    @Test
    void hybridLocalPasswordWorksEvenIfTheDirectoryClientThrows() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("local", "hash")).thenReturn(true);
        lenient().when(ldapAuthenticationService.bind(anyString(), any()))
                .thenThrow(new IllegalStateException("socket factory exploded"));

        var auth = provider.authenticate(token("bob", "local"));

        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    void aDirectoryClientThatThrowsIsAnOutageNotAServerError() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.bind("h.nikouei", "ad-pass"))
                .thenThrow(new IllegalStateException("socket factory exploded"));

        assertThatThrownBy(() -> provider.authenticate(token("h.nikouei", "ad-pass")))
                .isInstanceOf(AuthenticationServiceException.class)
                .isNotInstanceOf(InternalAuthenticationServiceException.class)
                .hasMessage(AppAuthenticationProvider.DIRECTORY_UNAVAILABLE);
    }

    /**
     * An ACTIVE_DIRECTORY account during an outage: told the directory is unreachable, not that
     * the password is wrong — and not charged a failed attempt, because no password was tested.
     */
    @Test
    void activeDirectoryUserGetsAnOutageNotBadCredentialsAndIsNotThrottled() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.bind("h.nikouei", "ad-pass")).thenReturn(BindResult.UNAVAILABLE);

        assertThatThrownBy(() -> provider.authenticate(token("h.nikouei", "ad-pass")))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage(AppAuthenticationProvider.DIRECTORY_UNAVAILABLE);

        verify(loginAttemptService, never()).recordFailure(anyString());
        verify(loginAttemptService, never()).recordSuccess(anyString());
    }

    /**
     * A HYBRID account whose local password did not match, during an outage: the same message,
     * so the operator knows to use the local password — but the local hash <em>was</em> tested
     * and refused, which is exactly what an attacker probing through the outage would be doing,
     * so that attempt still counts.
     */
    @Test
    void hybridWrongLocalPasswordDuringAnOutageIsReportedAsOutageButStillCounted() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("not-the-local-one", "hash")).thenReturn(false);
        when(ldapAuthenticationService.bind("bob", "not-the-local-one")).thenReturn(BindResult.UNAVAILABLE);

        assertThatThrownBy(() -> provider.authenticate(token("bob", "not-the-local-one")))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage(AppAuthenticationProvider.DIRECTORY_UNAVAILABLE);

        verify(loginAttemptService).recordFailure("bob");
    }

    /** The directory answering "no" is a wrong password, whatever the sub-code was. */
    @Test
    void activeDirectoryRefusalIsBadCredentialsAndCounted() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.bind("h.nikouei", "wrong")).thenReturn(BindResult.REJECTED);

        assertThatThrownBy(() -> provider.authenticate(token("h.nikouei", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginAttemptService).recordFailure("h.nikouei");
    }

    /**
     * A row with no local hash at all — possible after an auth-type change by hand — must not
     * reach the encoder, whose answer to a null is encoder-specific, and must simply not match.
     */
    @Test
    void aMissingLocalHashNeverMatchesAndNeverReachesTheEncoder() {
        User user = user("bob", UserAuthType.HYBRID, null);
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(ldapAuthenticationService.bind("bob", "anything")).thenReturn(BindResult.REJECTED);

        assertThatThrownBy(() -> provider.authenticate(token("bob", "anything")))
                .isInstanceOf(BadCredentialsException.class);

        verify(passwordEncoder, never()).matches(anyString(), any());
    }

    private static UsernamePasswordAuthenticationToken token(String username, String password) {
        return new UsernamePasswordAuthenticationToken(username, password);
    }

    private static User user(String username, UserAuthType authType, String hash) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setAuthType(authType);
        user.setPasswordHash(hash);
        user.setActive(true);
        return user;
    }

    private static AppUserDetails principal(User user) {
        return TestPrincipals.of(user, Set.of("OPERATOR"), Set.of());
    }
}
