package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One active mobile session per user, under concurrent logins.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code register()} closed the user's live sessions and then inserted the new one — a
 * read-then-write with nothing holding the two together. Under READ COMMITTED two logins for the
 * same user both read "nothing active" (neither can see the other's uncommitted insert) and both
 * inserted, so one operator ended up holding two live tokens. The index that would have caught it,
 * {@code idx_api_sessions_active}, was not unique, so the database had no opinion either.
 *
 * <p>That made a documented rule quietly false: a tablet a supervisor believed had been signed out
 * by the next login kept working.
 *
 * <h2>Why both halves of the fix are needed</h2>
 *
 * <p>{@code ux_api_sessions_one_active} (V3) states the invariant where nothing can bypass it. On
 * its own, though, it converts the race into a <b>failed login</b> — the loser's insert violates
 * it. The per-user advisory lock is what makes the loser wait, observe the winner's row, supersede
 * it and succeed, so "last login wins" stays true. This file asserts both: never two rows, and
 * never a login that fails because somebody else logged in at the same moment.
 *
 * <h2>Real threads, real transactions</h2>
 *
 * <p>The point is contention between database transactions, so the logins run on separate threads
 * released together by a latch. A single-threaded test could not fail even with the fix reverted.
 */
class ApiSessionConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int CONCURRENT_LOGINS = 8;

    @Autowired ApiSessionService apiSessionService;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private User operator;

    /**
     * Unique per test method: {@code jti} is globally unique and this class is deliberately NOT
     * transactional — the race under test only exists between committed transactions, so rows
     * survive from one method to the next and a fixed label would collide on uk_api_sessions_jti.
     */
    private String run;

    @BeforeEach
    void setUp() {
        operator = newOperator();
        run = Long.toString(System.nanoTime());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The race
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void concurrentLoginsForOneUserLeaveExactlyOneActiveSession() throws Exception {
        List<Throwable> failures = runConcurrently(CONCURRENT_LOGINS, i ->
                apiSessionService.register(principal(operator), token("jti-" + run + "-" + i), "Tablet " + i,
                        "agent", "10.0.0." + i));

        assertThat(failures)
                .as("a login must never fail because another device logged in at the same moment")
                .isEmpty();
        assertThat(unrevokedRowsFor(operator.getId()))
                .as("one operator, one live token — whatever the interleaving")
                .hasSize(1);
    }

    @Test
    void theSurvivingSessionIsTheLastOneRegistered() throws Exception {
        // "Last login wins" is the behaviour the rule promises; serialising must not turn it into
        // "first login wins", which would leave the newly signed-in tablet dead on arrival.
        runConcurrently(CONCURRENT_LOGINS, i ->
                apiSessionService.register(principal(operator), token("jti-" + run + "-" + i), "Tablet " + i,
                        null, null));

        List<ApiSession> live = unrevokedRowsFor(operator.getId());
        Long maxId = apiSessionRepository.findAll().stream()
                .filter(s -> operator.getId().equals(s.getUserId()))
                .map(ApiSession::getId)
                .max(Long::compareTo)
                .orElseThrow();
        assertThat(live.get(0).getId()).isEqualTo(maxId);
    }

    @Test
    void everySupersededSessionSaysWhyItWasClosed() throws Exception {
        runConcurrently(CONCURRENT_LOGINS, i ->
                apiSessionService.register(principal(operator), token("jti-" + run + "-" + i), null, null, null));

        List<ApiSession> closed = apiSessionRepository.findAll().stream()
                .filter(s -> operator.getId().equals(s.getUserId()) && s.getRevokedAt() != null)
                .toList();
        assertThat(closed).hasSize(CONCURRENT_LOGINS - 1);
        assertThat(closed).allMatch(s -> s.getRevokeReason() == ApiSessionRevokeReason.SUPERSEDED);
    }

    @Test
    void loginsByDifferentUsersDoNotWaitOnEachOther() throws Exception {
        // The lock is namespaced per user. If it were global, one plant's morning shift would
        // serialise every tablet against every other.
        User second = newOperator();

        List<Throwable> failures = runConcurrently(CONCURRENT_LOGINS, i -> {
            User who = i % 2 == 0 ? operator : second;
            return apiSessionService.register(principal(who), token("jti-" + run + "-" + i), null, null, null);
        });

        assertThat(failures).isEmpty();
        assertThat(unrevokedRowsFor(operator.getId())).hasSize(1);
        assertThat(unrevokedRowsFor(second.getId())).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The invariant, independent of the service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void theDatabaseItselfRefusesASecondActiveSession() throws Exception {
        // The half that survives a future caller forgetting the lock. Inserted straight through
        // JDBC so the check is the index and nothing else.
        apiSessionService.register(principal(operator), token("jti-" + run + "-1"), null, null, null);

        assertThatThrownBy(() -> insertActiveSessionDirectly(operator.getId(), "jti-" + run + "-smuggled"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(unrevokedRowsFor(operator.getId())).hasSize(1);
    }

    @Test
    void anExpiredButUnrevokedRowDoesNotBlockTheNextLogin() throws Exception {
        // The index predicate is `revoked_at IS NULL` and cannot consult the clock, so an expired
        // row is still in it. Superseding only the *unexpired* sessions — which is what
        // `findActiveByUserId` returns — would leave this row behind and break every later login.
        apiSessionService.register(principal(operator), token("jti-" + run + "-old"), null, null, null);
        jdbcTemplate.update("UPDATE api_sessions SET expires_at = ? WHERE user_id = ?",
                System.currentTimeMillis() - 60_000, operator.getId());

        apiSessionService.register(principal(operator), token("jti-" + run + "-new"), null, null, null);

        List<ApiSession> live = unrevokedRowsFor(operator.getId());
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getJti()).isEqualTo("jti-" + run + "-new");
    }

    @Test
    void anOrdinaryReLoginStillSupersedesTheOldDevice() throws Exception {
        // The everyday path, and the one the unique index would break if the revoke did not run
        // before the insert: `id` is IDENTITY, so `save()` inserts immediately.
        apiSessionService.register(principal(operator), token("jti-" + run + "-a"), "Tablet A", null, null);
        apiSessionService.register(principal(operator), token("jti-" + run + "-b"), "Tablet B", null, null);

        List<ApiSession> live = unrevokedRowsFor(operator.getId());
        assertThat(live).hasSize(1);
        assertThat(live.get(0).getJti()).isEqualTo("jti-" + run + "-b");
        assertThat(live.get(0).getDeviceLabel()).isEqualTo("Tablet B");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Runs {@code n} logins on {@code n} threads released together; returns what blew up. */
    private List<Throwable> runConcurrently(int n, ThrowingIntFunction body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = IntStream.range(0, n)
                    .mapToObj(i -> pool.submit((Callable<Throwable>) () -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        try {
                            body.apply(i);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    }))
                    .toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Throwable> failures = new java.util.ArrayList<>();
            for (Future<Throwable> f : futures) {
                Throwable t = f.get(30, TimeUnit.SECONDS);
                if (t != null) {
                    failures.add(t);
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private interface ThrowingIntFunction {
        Object apply(int i) throws Exception;
    }

    /** Unrevoked rows — the set the unique index governs, expiry irrelevant. */
    private List<ApiSession> unrevokedRowsFor(Long userId) {
        return apiSessionRepository.findAll().stream()
                .filter(s -> userId.equals(s.getUserId()) && s.getRevokedAt() == null)
                .toList();
    }

    private void insertActiveSessionDirectly(Long userId, String jti) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("""
                INSERT INTO api_sessions (jti, user_id, username, issued_at, expires_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, jti, userId, "smuggler", now, now + 600_000, now);
    }

    private User newOperator() {
        long unique = System.nanoTime();
        User user = new User();
        user.setUsername("sess-" + unique);
        user.setPersonnelCode("SESS" + unique);
        user.setFullName("Session Race");
        user.setPasswordHash("{noop}irrelevant");
        user.setActive(true);
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());
        return userRepository.saveAndFlush(user);
    }

    private AppUserDetails principal(User user) {
        return new AppUserDetails(user, Set.of(), Set.of());
    }

    private JwtService.JwtToken token(String jti) {
        long now = System.currentTimeMillis();
        return new JwtService.JwtToken("token-" + jti, jti, now, now + 600_000);
    }
}
