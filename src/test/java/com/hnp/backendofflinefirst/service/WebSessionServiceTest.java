package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.WebSessionMetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin view over live web sessions: registry rows are joined
 * with login metadata, raw session ids never leak to the UI, and expiring a row
 * flips the underlying {@link SessionInformation}.
 */
@ExtendWith(MockitoExtension.class)
class WebSessionServiceTest {

    @Mock
    private SessionRegistry sessionRegistry;

    private WebSessionMetadataStore metadataStore;
    private WebSessionService service;

    @BeforeEach
    void setUp() {
        metadataStore = new WebSessionMetadataStore();
        service = new WebSessionService(sessionRegistry, metadataStore);
    }

    @Test
    void listJoinsRegistryRowsWithLoginMetadata() {
        AppUserDetails principal = principal("op-1", "Operator One");
        SessionInformation info = new SessionInformation(principal, "sid-1", new Date(5_000L));
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of((Object) principal));
        when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(info));
        recordLogin("sid-1", "Firefox on Windows", "10.1.1.5", null);

        List<WebSessionService.WebSessionView> rows = service.listActiveSessions("sid-1");

        assertThat(rows).hasSize(1);
        WebSessionService.WebSessionView row = rows.getFirst();
        assertThat(row.username()).isEqualTo("op-1");
        assertThat(row.fullName()).isEqualTo("Operator One");
        assertThat(row.ipAddress()).isEqualTo("10.1.1.5");
        assertThat(row.userAgent()).isEqualTo("Firefox on Windows");
        assertThat(row.loginAt()).isNotNull();
        assertThat(row.lastRequestAt()).isEqualTo(5_000L);
        assertThat(row.currentSession()).isTrue();
    }

    @Test
    void rawSessionIdNeverAppearsInTheView() {
        AppUserDetails principal = principal("op-1", "Operator One");
        SessionInformation info = new SessionInformation(principal, "sid-secret-1", new Date());
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of((Object) principal));
        when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(info));

        WebSessionService.WebSessionView row = service.listActiveSessions(null).getFirst();

        assertThat(row.key()).isNotBlank().doesNotContain("sid-secret-1");
        assertThat(row.key()).isEqualTo(WebSessionService.sessionKey("sid-secret-1"));
        assertThat(row.currentSession()).isFalse();
    }

    @Test
    void nonAppUserPrincipalsAreSkipped() {
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of((Object) "anonymous-string-principal"));

        assertThat(service.listActiveSessions(null)).isEmpty();
    }

    @Test
    void rowWithoutMetadataStillListsWithNullDeviceDetails() {
        // Metadata store is in-memory; after a restart sessions may exist without it.
        AppUserDetails principal = principal("op-1", "Operator One");
        SessionInformation info = new SessionInformation(principal, "sid-1", new Date(5_000L));
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of((Object) principal));
        when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(info));

        WebSessionService.WebSessionView row = service.listActiveSessions(null).getFirst();

        assertThat(row.ipAddress()).isNull();
        assertThat(row.userAgent()).isNull();
        assertThat(row.loginAt()).isNull();
        assertThat(row.lastRequestAt()).isEqualTo(5_000L);
    }

    @Test
    void expireByKeyMarksTheMatchingSessionExpired() {
        AppUserDetails principal = principal("op-1", "Operator One");
        SessionInformation first = new SessionInformation(principal, "sid-1", new Date());
        SessionInformation second = new SessionInformation(principal, "sid-2", new Date());
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of((Object) principal));
        when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(first, second));

        service.expireByKey(WebSessionService.sessionKey("sid-2"), 1L);

        assertThat(second.isExpired()).isTrue();
        assertThat(first.isExpired()).isFalse();
    }

    @Test
    void expireByKeyThrowsWhenNoSessionMatches() {
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of());

        assertThatThrownBy(() -> service.expireByKey("no-such-key", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Web session not found.");
    }

    @Test
    void countActiveSessionsSumsAcrossPrincipals() {
        AppUserDetails p1 = principal("op-1", "One");
        AppUserDetails p2 = principal("op-2", "Two");
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of((Object) p1, p2));
        when(sessionRegistry.getAllSessions(p1, false))
                .thenReturn(List.of(new SessionInformation(p1, "s1", new Date())));
        when(sessionRegistry.getAllSessions(p2, false))
                .thenReturn(List.of(new SessionInformation(p2, "s2", new Date()),
                        new SessionInformation(p2, "s3", new Date())));

        assertThat(service.countActiveSessions()).isEqualTo(3);
    }

    @Test
    void metadataPrefersForwardedClientIpOverRemoteAddress() {
        recordLogin("sid-fw", "UA", "10.0.0.1", "203.0.113.9, 10.0.0.1");

        assertThat(metadataStore.get("sid-fw").ipAddress()).isEqualTo("203.0.113.9");
    }

    @Test
    void metadataIsDroppedWhenTheSessionIsDestroyed() {
        MockHttpSession session = recordLogin("sid-gone", "UA", "10.0.0.1", null);
        assertThat(metadataStore.get("sid-gone")).isNotNull();

        metadataStore.onSessionDestroyed(new HttpSessionDestroyedEvent(session));

        assertThat(metadataStore.get("sid-gone")).isNull();
    }

    private static AppUserDetails principal(String username, String fullName) {
        User user = new User();
        user.setUsername(username);
        user.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        user.setFullName(fullName);
        return new AppUserDetails(user, Set.of("OPERATOR"), Set.of());
    }

    private MockHttpSession recordLogin(String sessionId, String userAgent, String remoteAddr,
                                        String forwardedFor) {
        MockHttpSession session = new MockHttpSession(null, sessionId);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        request.addHeader("User-Agent", userAgent);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        metadataStore.recordLogin(request);
        return session;
    }
}
