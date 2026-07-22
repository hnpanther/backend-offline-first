package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
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
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complete vs expire race: on-time completion must win; overdue expiry must not overwrite SUBMITTED.
 */
class LogSheetCompleteExpireRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onTimeCompleteWinsAgainstConcurrentExpiry() throws Exception {
        long now = System.currentTimeMillis();
        long dueAt = now - 60_000L; // already past wall-clock
        long completedAt = dueAt - 30_000L; // finished before due (offline)
        Fixture fixture = seedInProgressSheet(dueAt);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> submitOutcome = new AtomicReference<>();
        AtomicReference<Boolean> expired = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> submitter = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                authenticate(fixture.operatorId());
                try {
                    LogSheetDto dto = new LogSheetDto();
                    dto.setServerId(fixture.sheetId());
                    dto.setLocalId("local-race-complete");
                    dto.setCompletedAt(completedAt);
                    dto.setClientActionId("client-race-complete-" + fixture.sheetId());
                    submitOutcome.set(logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome());
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            });
            Future<?> expirer = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                expired.set(logSheetService.tryExpireOverdue(fixture.sheetId(), now));
                return null;
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            submitter.get(20, TimeUnit.SECONDS);
            expirer.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(submitOutcome.get()).isEqualTo("SUBMITTED");
        // Expiry may or may not have "won" the race attempt, but must not leave EXPIRED.
        if (Boolean.TRUE.equals(expired.get())) {
            // Extremely unlikely if complete committed first; if expire ran first, complete must recover.
            assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        }
        assertThat(sheet.getCompletedAt()).isEqualTo(completedAt);
        assertThat(sheet.getExpiredAt()).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiryDoesNotOverwriteAlreadySubmittedSheet() {
        long now = System.currentTimeMillis();
        long dueAt = now - 60_000L;
        Fixture fixture = seedInProgressSheet(dueAt);

        authenticate(fixture.operatorId());
        try {
            LogSheetDto dto = new LogSheetDto();
            dto.setServerId(fixture.sheetId());
            dto.setLocalId("local-already-submitted");
            dto.setCompletedAt(dueAt - 1_000L);
            dto.setClientActionId("client-already-submitted-" + fixture.sheetId());
            assertThat(logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome())
                    .isEqualTo("SUBMITTED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(logSheetService.tryExpireOverdue(fixture.sheetId(), now)).isFalse();
        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(sheet.getExpiredAt()).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateCompletionAfterExpiryStaysExpired() {
        long now = System.currentTimeMillis();
        long dueAt = now - 60_000L;
        Fixture fixture = seedInProgressSheet(dueAt);

        assertThat(logSheetService.tryExpireOverdue(fixture.sheetId(), now)).isTrue();

        authenticate(fixture.operatorId());
        try {
            LogSheetDto dto = new LogSheetDto();
            dto.setServerId(fixture.sheetId());
            dto.setLocalId("local-late");
            dto.setCompletedAt(dueAt + 5_000L); // after due
            dto.setClientActionId("client-late-" + fixture.sheetId());
            assertThat(logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome())
                    .isEqualTo("EXPIRED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
    }

    private void authenticate(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        AppUserDetails principal = new AppUserDetails(user, Set.of("OPERATOR"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Fixture seedInProgressSheet(long dueAt) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("EXP-BU-" + now + "-" + dueAt);
        unit.setName("Expire Race Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        User operator = createOperator(unit.getId(), "exp-op-" + now + "-" + Math.abs(dueAt % 10_000), "op12345");

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Expire Race");
        sheet.setScopeSummary("location:1");
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SELF_CLAIMED);
        sheet.setDueAt(dueAt);
        sheet.setClaimedAt(now - 120_000L);
        sheet.setStartedAt(now - 120_000L);
        sheet.setCreatedAt(now - 120_000L);
        sheet.setUpdatedAt(now - 120_000L);
        sheet = logSheetRepository.saveAndFlush(sheet);
        return new Fixture(sheet.getId(), operator.getId());
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setFullName("Expire Race Operator");
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

    private record Fixture(Long sheetId, Long operatorId) {}
}
