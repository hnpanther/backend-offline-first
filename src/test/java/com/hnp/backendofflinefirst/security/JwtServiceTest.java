package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @Mock AppSettingsService appSettingsService;

    JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(appSettingsService);
        ReflectionTestUtils.setField(jwtService, "secret",
                "unit-test-jwt-secret-key-32bytes-min!!");
        jwtService.init();
    }

    @Test
    void issueAndParseTokenRoundTrip() {
        when(appSettingsService.getJwtExpiryMinutes()).thenReturn(60);
        User user = new User();
        user.setId(42L);
        user.setUsername("operator1");
        user.setFullName("Operator One");
        user.setActive(true);

        AppUserDetails details = new AppUserDetails(
                user,
                Set.of("OPERATOR"),
                Set.of("GET:/api/master-data", "GET:/api/bootstrap", "POST:/api/log-sheets/batch"));

        JwtService.JwtToken issued = jwtService.issueToken(details);
        assertThat(issued.accessToken()).isNotBlank();
        assertThat(issued.expiresAt()).isGreaterThan(System.currentTimeMillis());

        Authentication parsed = jwtService.parseAuthentication(issued.accessToken()).orElseThrow();
        assertThat(parsed).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        AppUserDetails fromToken = (AppUserDetails) parsed.getPrincipal();
        assertThat(fromToken.getUserId()).isEqualTo(42L);
        assertThat(fromToken.getUsername()).isEqualTo("operator1");
        assertThat(fromToken.getRoleCodes()).containsExactly("OPERATOR");
        assertThat(fromToken.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder(
                        "GET:/api/master-data",
                        "GET:/api/bootstrap",
                        "POST:/api/log-sheets/batch");
    }

    @Test
    void parseInvalidTokenReturnsEmpty() {
        assertThat(jwtService.parseAuthentication("not.a.valid.jwt")).isEmpty();
    }
}
