package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies concurrent claim is first-wins at the database: only one UPDATE WHERE status=PENDING succeeds.
 */
class LogSheetConcurrentClaimIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentClaimsAllowExactlyOneWinner() throws Exception {
        Fixture fixture = seedPendingSheetWithTwoOperators();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Long> winners = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> attemptClaim(
                    fixture.sheetId(), fixture.operatorAId(), ready, start, successes, failures, winners));
            Future<?> b = pool.submit(() -> attemptClaim(
                    fixture.sheetId(), fixture.operatorBId(), ready, start, successes, failures, winners));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
        assertThat(winners).hasSize(1);

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(sheet.getAssigneeUserId()).isEqualTo(winners.getFirst());
        assertThat(sheet.getAssigneeUserId())
                .isIn(fixture.operatorAId(), fixture.operatorBId());
    }

    private void attemptClaim(Long sheetId,
                              Long operatorId,
                              CountDownLatch ready,
                              CountDownLatch start,
                              AtomicInteger successes,
                              AtomicInteger failures,
                              List<Long> winners) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
            assignmentService.claim(sheetId, operatorId, ActionSource.MOBILE);
            successes.incrementAndGet();
            synchronized (winners) {
                winners.add(operatorId);
            }
        } catch (IllegalStateException ex) {
            failures.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Fixture seedPendingSheetWithTwoOperators() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("CLAIM-BU-" + now);
        unit.setName("Claim Race Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        User operatorA = createOperator(unit.getId(), "claim-a-" + now, "op12345");
        User operatorB = createOperator(unit.getId(), "claim-b-" + now, "op12345");

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Claim Race Template");
        sheet.setScopeSummary("location:1");
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.PENDING);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.saveAndFlush(sheet);

        return new Fixture(sheet.getId(), operatorA.getId(), operatorB.getId());
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setFullName("Claim Operator");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.saveAndFlush(link);
        return user;
    }

    private record Fixture(Long sheetId, Long operatorAId, Long operatorBId) {}
}
