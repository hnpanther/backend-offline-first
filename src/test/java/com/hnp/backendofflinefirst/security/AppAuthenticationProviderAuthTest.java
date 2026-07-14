package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.config.LdapAuthProperties;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppAuthenticationProviderAuthTest {

    @Mock UserDetailsService userDetailsService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock LdapAuthenticationService ldapAuthenticationService;

    AppAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AppAuthenticationProvider(userDetailsService, passwordEncoder, ldapAuthenticationService);
    }

    @Test
    void localUserAuthenticatesWithBcrypt() {
        User user = user("alice", UserAuthType.LOCAL, "hash");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(principal(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        var auth = provider.authenticate(token("alice", "secret"));

        assertThat(auth.getPrincipal()).isInstanceOf(AppUserDetails.class);
        verify(ldapAuthenticationService, never()).authenticate(anyString(), anyString());
    }

    @Test
    void activeDirectoryUserAuthenticatesViaLdap() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.authenticate("h.nikouei", "ad-pass")).thenReturn(true);

        provider.authenticate(token("h.nikouei", "ad-pass"));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void hybridUserPrefersLocalPassword() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("local", "hash")).thenReturn(true);

        provider.authenticate(token("bob", "local"));

        verify(ldapAuthenticationService, never()).authenticate(anyString(), anyString());
    }

    @Test
    void hybridUserFallsBackToLdapWhenLocalFails() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("ad-pass", "hash")).thenReturn(false);
        when(ldapAuthenticationService.authenticate("bob", "ad-pass")).thenReturn(true);

        provider.authenticate(token("bob", "ad-pass"));

        verify(ldapAuthenticationService).authenticate("bob", "ad-pass");
    }

    @Test
    void loginNormalizesUsernameWithDomain() {
        User user = user("h.nikouei", UserAuthType.ACTIVE_DIRECTORY, "unused");
        when(userDetailsService.loadUserByUsername("h.nikouei")).thenReturn(principal(user));
        when(ldapAuthenticationService.authenticate(eq("h.nikouei"), anyString())).thenReturn(true);

        provider.authenticate(token("h.nikouei@site.local", "pw"));

        verify(userDetailsService).loadUserByUsername("h.nikouei");
    }

    @Test
    void badCredentialsWhenAllChecksFail() {
        User user = user("bob", UserAuthType.HYBRID, "hash");
        when(userDetailsService.loadUserByUsername("bob")).thenReturn(principal(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
        when(ldapAuthenticationService.authenticate("bob", "wrong")).thenReturn(false);

        assertThatThrownBy(() -> provider.authenticate(token("bob", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    private static UsernamePasswordAuthenticationToken token(String username, String password) {
        return new UsernamePasswordAuthenticationToken(username, password);
    }

    private static User user(String username, UserAuthType authType, String hash) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setAuthType(authType);
        user.setPasswordHash(hash);
        user.setActive(true);
        return user;
    }

    private static AppUserDetails principal(User user) {
        return new AppUserDetails(user, Set.of("OPERATOR"), Set.of());
    }
}
