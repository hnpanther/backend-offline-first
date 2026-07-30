package com.hnp.backendofflinefirst.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private static final long START = 1_000_000_000L;

    AtomicLong now;
    LoginAttemptService service;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(START);
        service = new LoginAttemptService(now::get);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "lockMinutes", 15);
    }

    private void advance(long millis) {
        now.addAndGet(millis);
    }

    @Test
    void notLockedBeforeThreshold() {
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertThat(service.isLocked("alice")).isFalse();
        assertThat(service.remainingLockSeconds("alice")).isZero();
    }

    @Test
    void locksAfterMaxAttempts() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertThat(service.isLocked("alice")).isTrue();
        assertThat(service.remainingLockSeconds("alice")).isGreaterThan(0);
    }

    @Test
    void lockIsKeyedByUsernameCaseAndWhitespaceInsensitively() {
        service.recordFailure("Alice");
        service.recordFailure(" ALICE ");
        service.recordFailure("alice");

        assertThat(service.isLocked("alice")).isTrue();
        assertThat(service.isLocked("ALICE")).isTrue();
    }

    @Test
    void successResetsCounter() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.isLocked("alice")).isTrue();

        service.recordSuccess("alice");

        assertThat(service.isLocked("alice")).isFalse();
    }

    @Test
    void otherUsernamesAreNotAffected() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertThat(service.isLocked("bob")).isFalse();
    }

    @Test
    void lockClearsOnItsOwnOnceTheWindowElapses() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.isLocked("alice")).isTrue();

        advance(15 * 60_000L + 1);

        assertThat(service.isLocked("alice")).isFalse();
        assertThat(service.remainingLockSeconds("alice")).isZero();
    }

    @Test
    void unlockClearsAnActiveLockImmediately() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.isLocked("alice")).isTrue();

        service.unlock("alice");

        assertThat(service.isLocked("alice")).isFalse();
    }

    /**
     * Admin clicks "unlock" on a username after its lock had already expired naturally in
     * the meantime (e.g. loaded the page when 1 minute remained, clicked 2 minutes later).
     * Must not throw and must leave the username unlocked — exactly the scenario the
     * admin-page feature was built to handle safely without special-casing.
     */
    @Test
    void unlockAfterNaturalExpiryIsANoOpAndDoesNotThrow() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.isLocked("alice")).isTrue();

        advance(15 * 60_000L + 1); // lock already expired naturally
        assertThat(service.isLocked("alice")).isFalse();

        service.unlock("alice"); // admin's stale click arrives after expiry

        assertThat(service.isLocked("alice")).isFalse();
        assertThat(service.remainingLockSeconds("alice")).isZero();
    }

    @Test
    void unlockOnAUsernameWithNoRecordDoesNotThrow() {
        service.unlock("never-attempted");

        assertThat(service.isLocked("never-attempted")).isFalse();
    }

    @Test
    void snapshotIncludesBothLockedAndNearLockoutUsers() {
        service.recordFailure("bob"); // near lockout: 1/3
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice"); // locked: 3/3

        var statuses = service.snapshot();

        assertThat(statuses).hasSize(2);
        var alice = statuses.stream().filter(s -> s.username().equals("alice")).findFirst().orElseThrow();
        var bob = statuses.stream().filter(s -> s.username().equals("bob")).findFirst().orElseThrow();

        assertThat(alice.locked()).isTrue();
        assertThat(alice.failureCount()).isEqualTo(3);
        assertThat(alice.remainingLockSeconds()).isGreaterThan(0);

        assertThat(bob.locked()).isFalse();
        assertThat(bob.failureCount()).isEqualTo(1);
        assertThat(bob.remainingLockSeconds()).isZero();
    }

    @Test
    void snapshotPreservesOriginalUsernameCasing() {
        service.recordFailure("Admin");

        var statuses = service.snapshot();

        assertThat(statuses).extracting(LoginAttemptService.Status::username).containsExactly("Admin");
    }

    @Test
    void snapshotExcludesExpiredEntries() {
        service.recordFailure("alice");
        advance(15 * 60_000L + 1);

        assertThat(service.snapshot()).isEmpty();
    }

    @Test
    void unlockRemovesUserFromSnapshot() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.snapshot()).hasSize(1);

        service.unlock("alice");

        assertThat(service.snapshot()).isEmpty();
    }
}
