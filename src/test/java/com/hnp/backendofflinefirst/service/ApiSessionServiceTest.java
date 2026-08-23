package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiSessionServiceTest {

    @Mock ApiSessionRepository apiSessionRepository;

    @InjectMocks ApiSessionService service;

    private static final long NOW = 1_700_000_000_000L;

    private AppUserDetails user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        u.setActive(true);
        return TestPrincipals.of(u, Set.of("OPERATOR"), Set.of());
    }

    private JwtService.JwtToken token(String jti) {
        return new JwtService.JwtToken("header.payload.signature", jti, NOW, NOW + 60_000);
    }

    private ApiSession session(String jti, long userId, long expiresAt) {
        ApiSession s = new ApiSession();
        s.setId(1L);
        s.setJti(jti);
        s.setUserId(userId);
        s.setExpiresAt(expiresAt);
        s.setIssuedAt(NOW - 1000);
        return s;
    }

    @Test
    void registerPersistsSessionWithDeviceMetadata() {
        service.register(user(5L, "operator1"), token("jti-1"),
                "Tablet A", "Mozilla/5.0", "10.0.0.7");

        ArgumentCaptor<ApiSession> captor = ArgumentCaptor.forClass(ApiSession.class);
        verify(apiSessionRepository).save(captor.capture());
        ApiSession saved = captor.getValue();
        assertThat(saved.getJti()).isEqualTo("jti-1");
        assertThat(saved.getUserId()).isEqualTo(5L);
        assertThat(saved.getUsername()).isEqualTo("operator1");
        assertThat(saved.getDeviceLabel()).isEqualTo("Tablet A");
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.7");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW + 60_000);
        assertThat(saved.getRevokedAt()).isNull();
    }

    @Test
    void registerSupersedesPreviousSessionSoOnlyOneDeviceStaysLoggedIn() {
        when(apiSessionRepository.supersedeAllForUser(5L, NOW, ApiSessionRevokeReason.SUPERSEDED))
                .thenReturn(1);

        service.register(user(5L, "operator1"), token("new-jti"), "Tablet B", null, null);

        // One bulk statement rather than load-revoke-save, and that is load-bearing: the new row
        // is an IDENTITY insert that fires immediately, so entities marked dirty would still be
        // unrevoked at that moment and the unique index would reject every ordinary re-login.
        verify(apiSessionRepository).supersedeAllForUser(5L, NOW, ApiSessionRevokeReason.SUPERSEDED);
        verify(apiSessionRepository, never()).saveAll(any());
    }

    @Test
    void registerTakesThePerUserLockBeforeReadingOrWritingAnything() {
        // Without it two concurrent logins both read "nothing active" under READ COMMITTED and
        // both insert, which is the race the one-device rule was losing to.
        service.register(user(5L, "operator1"), token("jti-1"), "Tablet A", null, null);

        InOrder order = inOrder(apiSessionRepository);
        order.verify(apiSessionRepository).lockUserForSessionChange(anyInt(), eq(5));
        order.verify(apiSessionRepository).supersedeAllForUser(eq(5L), eq(NOW), any());
        order.verify(apiSessionRepository).save(any(ApiSession.class));
    }

    @Test
    void supersedingRevokesEveryUnrevokedRowNotOnlyTheUnexpiredOnes() {
        // `ux_api_sessions_one_active` is `WHERE revoked_at IS NULL`, and a partial index cannot
        // read the clock — so an expired-but-unrevoked row is still in it. Leaving one behind
        // would block the user's next login. `findActiveByUserId` filters on expiry, which is
        // exactly why register must not use it.
        service.register(user(5L, "operator1"), token("jti-1"), null, null, null);

        verify(apiSessionRepository).supersedeAllForUser(eq(5L), eq(NOW), any());
        verify(apiSessionRepository, never()).findActiveByUserId(eq(5L), anyLong());
    }

    @Test
    void blankDeviceMetadataIsStoredAsNull() {
        service.register(user(5L, "operator1"), token("jti-1"), "   ", "", null);

        ArgumentCaptor<ApiSession> captor = ArgumentCaptor.forClass(ApiSession.class);
        verify(apiSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getDeviceLabel()).isNull();
        assertThat(captor.getValue().getUserAgent()).isNull();
    }

    @Test
    void overlongUserAgentIsTruncatedToColumnLength() {
        service.register(user(5L, "operator1"), token("jti-1"), null, "x".repeat(900), null);

        ArgumentCaptor<ApiSession> captor = ArgumentCaptor.forClass(ApiSession.class);
        verify(apiSessionRepository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(512);
    }

    private static final Long OWNER = 5L;

    @Test
    void isSessionActiveRejectsUnknownJti() {
        when(apiSessionRepository.findByJti("ghost")).thenReturn(Optional.empty());

        assertThat(service.isSessionActive("ghost", OWNER, NOW)).isFalse();
    }

    @Test
    void isSessionActiveRejectsTokenWithoutJti() {
        assertThat(service.isSessionActive(null, OWNER, NOW)).isFalse();
        assertThat(service.isSessionActive("  ", OWNER, NOW)).isFalse();
        verify(apiSessionRepository, never()).findByJti(any());
    }

    /**
     * A token with no user id names nobody, and a session belongs to somebody. Rejected before
     * the lookup, so there is no path on which the binding below is skipped for a null.
     */
    @Test
    void isSessionActiveRejectsTokenWithoutAUserId() {
        assertThat(service.isSessionActive("jti-1", null, NOW)).isFalse();
        verify(apiSessionRepository, never()).findByJti(any());
    }

    /**
     * <b>The binding.</b> A live session belonging to user 5 does not authenticate a token
     * claiming to be user 6.
     *
     * <p>This is the escalation that used to work. The signing key ships as a working default, so
     * an ordinary operator could log in, take their own genuine {@code jti} — a real, live
     * session — and re-sign the payload with somebody else's {@code uid}. The old check asked
     * only whether the {@code jti} was live. It was.
     */
    @Test
    void isSessionActiveRejectsAJtiThatBelongsToAnotherUser() {
        when(apiSessionRepository.findByJti("jti-1"))
                .thenReturn(Optional.of(session("jti-1", OWNER, NOW + 500_000)));

        assertThat(service.isSessionActive("jti-1", 6L, NOW))
                .as("a session is only ever valid for the user it was issued to")
                .isFalse();
        verify(apiSessionRepository, never()).save(any());
    }

    /** And the mismatch is refused before liveness, so a revoked-and-stolen jti says nothing. */
    @Test
    void theOwnerCheckHappensEvenWhenTheSessionIsAlsoExpired() {
        when(apiSessionRepository.findByJti("jti-1"))
                .thenReturn(Optional.of(session("jti-1", OWNER, NOW - 1)));

        assertThat(service.isSessionActive("jti-1", 6L, NOW)).isFalse();
    }

    @Test
    void isSessionActiveRejectsRevokedSession() {
        ApiSession revoked = session("jti-1", OWNER, NOW + 500_000);
        revoked.setRevokedAt(NOW - 10);
        when(apiSessionRepository.findByJti("jti-1")).thenReturn(Optional.of(revoked));

        assertThat(service.isSessionActive("jti-1", OWNER, NOW)).isFalse();
    }

    @Test
    void isSessionActiveRejectsExpiredSession() {
        when(apiSessionRepository.findByJti("jti-1"))
                .thenReturn(Optional.of(session("jti-1", OWNER, NOW - 1)));

        assertThat(service.isSessionActive("jti-1", OWNER, NOW)).isFalse();
    }

    @Test
    void isSessionActiveAcceptsLiveSessionAndTouchesLastSeen() {
        ApiSession live = session("jti-1", OWNER, NOW + 500_000);
        live.setLastSeenAt(NOW - ApiSessionService.LAST_SEEN_THROTTLE_MS);
        when(apiSessionRepository.findByJti("jti-1")).thenReturn(Optional.of(live));

        assertThat(service.isSessionActive("jti-1", OWNER, NOW)).isTrue();
        assertThat(live.getLastSeenAt()).isEqualTo(NOW);
        verify(apiSessionRepository).save(live);
    }

    @Test
    void lastSeenIsNotRewrittenOnEveryRequest() {
        ApiSession live = session("jti-1", OWNER, NOW + 500_000);
        live.setLastSeenAt(NOW - 1_000);
        when(apiSessionRepository.findByJti("jti-1")).thenReturn(Optional.of(live));

        assertThat(service.isSessionActive("jti-1", OWNER, NOW)).isTrue();
        verify(apiSessionRepository, never()).save(any());
    }

    @Test
    void revokeMarksSessionAsAdminRevoked() {
        ApiSession live = session("jti-1", 5L, NOW + 500_000);
        when(apiSessionRepository.findById(1L)).thenReturn(Optional.of(live));

        service.revoke(1L, 99L, NOW);

        assertThat(live.getRevokedAt()).isEqualTo(NOW);
        assertThat(live.getRevokedBy()).isEqualTo(99L);
        assertThat(live.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.ADMIN);
        verify(apiSessionRepository).save(live);
    }

    @Test
    void revokeRejectsUnknownSession() {
        when(apiSessionRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(404L, 99L, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("API session not found.");
    }

    @Test
    void revokeRejectsAlreadyRevokedSession() {
        ApiSession revoked = session("jti-1", 5L, NOW + 500_000);
        revoked.setRevokedAt(NOW - 10);
        when(apiSessionRepository.findById(1L)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.revoke(1L, 99L, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This API session is already revoked.");
    }

    @Test
    void revokeAllForUserReturnsCountAndMarksEachSession() {
        ApiSession first = session("jti-1", 5L, NOW + 500_000);
        ApiSession second = session("jti-2", 5L, NOW + 500_000);
        when(apiSessionRepository.findActiveByUserId(5L, NOW)).thenReturn(List.of(first, second));

        assertThat(service.revokeAllForUser(5L, 99L, NOW)).isEqualTo(2);
        assertThat(first.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.ADMIN);
        assertThat(second.getRevokedBy()).isEqualTo(99L);
    }

    @Test
    void revokeAllForUserIsNoOpWhenNothingActive() {
        when(apiSessionRepository.findActiveByUserId(5L, NOW)).thenReturn(List.of());

        assertThat(service.revokeAllForUser(5L, 99L, NOW)).isZero();
        verify(apiSessionRepository, never()).saveAll(any());
    }

    @Test
    void listUsesActiveOnlyQueryWhenRequested() {
        service.list("  tablet  ", true, NOW, org.springframework.data.domain.PageRequest.of(0, 25));

        verify(apiSessionRepository).searchActive("tablet", NOW,
                org.springframework.data.domain.PageRequest.of(0, 25));
    }

    @Test
    void blankQueryUsesTheTermlessActiveQuery() {
        // A null bind inside the search JPQL makes PostgreSQL resolve lower(bytea) — hence
        // a dedicated query rather than an "IS NULL OR ..." clause.
        service.list(null, true, NOW, org.springframework.data.domain.PageRequest.of(0, 25));

        verify(apiSessionRepository).findActive(NOW, org.springframework.data.domain.PageRequest.of(0, 25));
        verify(apiSessionRepository, never()).searchActive(any(), anyLong(), any());
    }

    @Test
    void listFallsBackToFindAllWhenNoQueryAndAllStatuses() {
        service.list("   ", false, NOW, org.springframework.data.domain.PageRequest.of(0, 25));

        verify(apiSessionRepository).findAll(org.springframework.data.domain.PageRequest.of(0, 25));
        verify(apiSessionRepository, never()).search(any(), any());
    }

    @Test
    void countActiveDelegatesToRepository() {
        when(apiSessionRepository.countByRevokedAtIsNullAndExpiresAtGreaterThan(anyLong())).thenReturn(3L);

        assertThat(service.countActive(NOW)).isEqualTo(3L);
    }
}
