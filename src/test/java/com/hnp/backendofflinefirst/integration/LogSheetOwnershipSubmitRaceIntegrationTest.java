package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Takeover / reassign / release must not let a stale operator submit win ownership races.
 * Mobile outcomes stay within existing SUBMITTED / SUPERSEDED contracts (no frontend change).
 */
class LogSheetOwnershipSubmitRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentTakeoverDoesNotAllowStaleOperatorSubmitToWin() throws Exception {
        Fixture fixture = seedInProgressSheet();
        long completedAt = System.currentTimeMillis();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> submitOutcome = new AtomicReference<>();
        AtomicBoolean takeoverSucceeded = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> submitter = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                authenticate(fixture.operatorId(), "OPERATOR");
                try {
                    submitOutcome.set(submitWithTemp(fixture, completedAt, 10, "own-race-submit").getOutcome());
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            });
            Future<?> taker = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    assignmentService.takeover(fixture.sheetId(), fixture.supervisorId(), ActionSource.WEB);
                    takeoverSucceeded.set(true);
                } catch (IllegalStateException ignored) {
                    takeoverSucceeded.set(false);
                }
                return null;
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            submitter.get(20, TimeUnit.SECONDS);
            taker.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        String outcome = submitOutcome.get();

        assertThat(outcome).isIn("SUBMITTED", "SUPERSEDED");

        if ("SUBMITTED".equals(outcome)) {
            assertThat(takeoverSucceeded.get()).isFalse();
            assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
            assertThat(sheet.getCompletedByUserId()).isEqualTo(fixture.operatorId());
            assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.operatorId());
            LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(fixture.sheetId()).getFirst();
            assertThat(entry.getFormData()).containsEntry("temp", 10);
        } else {
            assertThat(takeoverSucceeded.get()).isTrue();
            assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
            assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.supervisorId());
            assertThat(sheet.getCompletedByUserId()).isNull();
            assertThat(allEntriesEmpty(fixture.sheetId())).isTrue();
        }

        // Forbidden: supervisor owns the sheet but operator still became completer.
        assertThat(sheet.getStatus() == LogSheetStatus.SUBMITTED
                && fixture.supervisorId().equals(sheet.getAssigneeUserId())).isFalse();
        // Forbidden reverse lost-update: completion metadata with a reopened status.
        assertThat(sheet.getStatus() == LogSheetStatus.IN_PROGRESS
                && sheet.getCompletedByUserId() != null).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void takeoverAfterSuccessfulSubmitFailsAndDoesNotReopenSheet() {
        Fixture fixture = seedInProgressSheet();

        authenticate(fixture.operatorId(), "OPERATOR");
        try {
            assertThat(submitWithTemp(fixture, System.currentTimeMillis(), 75, "before-takeover").getOutcome())
                    .isEqualTo("SUBMITTED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assignmentService.takeover(fixture.sheetId(), fixture.supervisorId(), ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be taken over");

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(sheet.getCompletedByUserId()).isEqualTo(fixture.operatorId());
        assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.operatorId());
        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(fixture.sheetId()).getFirst();
        assertThat(entry.getFormData()).containsEntry("temp", 75);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reassignAfterSuccessfulSubmitFailsAndDoesNotReopenSheet() {
        Fixture fixture = seedSupervisorAssignedSheet();

        authenticate(fixture.operatorId(), "OPERATOR");
        try {
            assertThat(submitWithTemp(fixture, System.currentTimeMillis(), 61, "before-reassign").getOutcome())
                    .isEqualTo("SUBMITTED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assignmentService.reassign(
                                fixture.sheetId(), fixture.otherOperatorId(), fixture.supervisorId(), ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reassigned");

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(sheet.getCompletedByUserId()).isEqualTo(fixture.operatorId());
        assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.operatorId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void releaseAfterSuccessfulSubmitFailsAndDoesNotReturnSheetToPool() {
        Fixture fixture = seedInProgressSheet();

        authenticate(fixture.operatorId(), "OPERATOR");
        try {
            assertThat(submitWithTemp(fixture, System.currentTimeMillis(), 44, "before-release").getOutcome())
                    .isEqualTo("SUBMITTED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        assignmentService.release(fixture.sheetId(), fixture.operatorId(), ActionSource.MOBILE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be released");

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(sheet.getCompletedByUserId()).isEqualTo(fixture.operatorId());
        assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.operatorId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void submitAfterTakeoverIsSupersededAndDoesNotWriteEntries() {
        Fixture fixture = seedInProgressSheet();

        assignmentService.takeover(fixture.sheetId(), fixture.supervisorId(), ActionSource.WEB);

        authenticate(fixture.operatorId(), "OPERATOR");
        try {
            assertThat(submitWithTemp(fixture, System.currentTimeMillis(), 99, "after-takeover").getOutcome())
                    .isEqualTo("SUPERSEDED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.supervisorId());
        assertThat(sheet.getCompletedByUserId()).isNull();
        assertThat(allEntriesEmpty(fixture.sheetId())).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void submitAfterReassignIsSupersededAndDoesNotWriteEntries() {
        Fixture fixture = seedSupervisorAssignedSheet();

        assignmentService.reassign(
                fixture.sheetId(), fixture.otherOperatorId(), fixture.supervisorId(), ActionSource.WEB);

        authenticate(fixture.operatorId(), "OPERATOR");
        try {
            assertThat(submitWithTemp(fixture, System.currentTimeMillis(), 77, "after-reassign").getOutcome())
                    .isEqualTo("SUPERSEDED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getAssigneeUserId()).isEqualTo(fixture.otherOperatorId());
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.ASSIGNED);
        assertThat(sheet.getCompletedByUserId()).isNull();
        assertThat(allEntriesEmpty(fixture.sheetId())).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void submitAfterReleaseIsSupersededAndDoesNotWriteEntries() {
        Fixture fixture = seedInProgressSheet();

        assignmentService.release(fixture.sheetId(), fixture.operatorId(), ActionSource.MOBILE);

        authenticate(fixture.operatorId(), "OPERATOR");
        try {
            assertThat(submitWithTemp(fixture, System.currentTimeMillis(), 55, "after-release").getOutcome())
                    .isEqualTo("SUPERSEDED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.PENDING);
        assertThat(sheet.getAssigneeUserId()).isNull();
        assertThat(sheet.getCompletedByUserId()).isNull();
        assertThat(allEntriesEmpty(fixture.sheetId())).isTrue();
    }

    private boolean allEntriesEmpty(Long sheetId) {
        return logSheetEntryRepository.findByLogSheetId(sheetId).stream()
                .allMatch(entry -> entry.getFormData() == null || entry.getFormData().isEmpty());
    }

    private LogSheetSubmitResult submitWithTemp(Fixture fixture, long completedAt, int temp, String actionSuffix) {
        LogSheetEntryDto entryDto = new LogSheetEntryDto();
        entryDto.setAssetId(fixture.assetId());
        entryDto.setFormData(Map.of("temp", temp));

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(fixture.sheetId());
        dto.setLocalId("local-" + actionSuffix);
        dto.setCompletedAt(completedAt);
        dto.setClientActionId("client-" + actionSuffix + "-" + fixture.sheetId());
        dto.setEntries(List.of(entryDto));
        return logSheetService.submitBatch(List.of(dto)).getFirst();
    }

    private void authenticate(Long userId, String role) {
        User user = userRepository.findById(userId).orElseThrow();
        AppUserDetails principal = new AppUserDetails(user, Set.of(role), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Fixture seedInProgressSheet() {
        return seedSheet(LogSheetStatus.IN_PROGRESS, AssignmentType.SELF_CLAIMED, true);
    }

    private Fixture seedSupervisorAssignedSheet() {
        return seedSheet(LogSheetStatus.ASSIGNED, AssignmentType.SUPERVISOR_ASSIGNED, true);
    }

    private Fixture seedSheet(LogSheetStatus status, AssignmentType assignmentType, boolean withOtherOperator) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OWN-RACE-BU-" + now + "-" + status);
        unit.setName("Ownership Race Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("OWN-RACE-LOC-" + now + "-" + status);
        location.setName("Ownership Race Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("OWN-RACE-SF-" + now + "-" + status);
        subFunction.setName("Ownership Race Sub");
        subFunction.setTag("NFC-OWN-" + now + "-" + status.name());
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Ownership Race Pump " + now + status);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("OWN-RACE-A-" + now + "-" + status);
        asset.setAssetName("Ownership Race Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        User operator = createUser(unit.getId(), "own-op-" + now + "-" + status, "OPERATOR", true);
        User supervisor = createUser(unit.getId(), "own-sv-" + now + "-" + status, "SUPERVISOR", false);
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unit.getId());
        link.setUserId(supervisor.getId());
        unitSupervisorRepository.saveAndFlush(link);

        Long otherOperatorId = null;
        if (withOtherOperator) {
            otherOperatorId = createUser(unit.getId(), "own-op2-" + now + "-" + status, "OPERATOR", true).getId();
        }

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Ownership Race");
        sheet.setScopeSummary("location:" + location.getId());
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(status);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(assignmentType);
        sheet.setAssignedByUserId(assignmentType == AssignmentType.SUPERVISOR_ASSIGNED ? supervisor.getId() : null);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setClaimedAt(status == LogSheetStatus.IN_PROGRESS ? now - 60_000L : null);
        sheet.setStartedAt(status == LogSheetStatus.IN_PROGRESS ? now - 60_000L : null);
        sheet.setAssignedAt(assignmentType == AssignmentType.SUPERVISOR_ASSIGNED ? now - 60_000L : null);
        sheet.setCreatedAt(now - 60_000L);
        sheet.setUpdatedAt(now - 60_000L);
        sheet = logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheet.getId());
        entry.setAssetId(asset.getId());
        entry.setAssetName(asset.getAssetName());
        entry.setClassId(assetClass.getId());
        entry.setNfcTagId(subFunction.getTag());
        entry.setSubFunctionCode(subFunction.getCode());
        entry.setSubFunctionTag(subFunction.getTag());
        entry.setFormData(new HashMap<>());
        logSheetEntryRepository.saveAndFlush(entry);

        return new Fixture(sheet.getId(), operator.getId(), supervisor.getId(), otherOperatorId, asset.getId());
    }

    private User createUser(Long unitId, String username, String roleCode, boolean asOperator) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setFullName("Ownership Race " + roleCode);
        user.setPasswordHash(passwordEncoder.encode("op12345"));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode(roleCode).orElseThrow().getId());
        userRoleRepository.saveAndFlush(userRole);

        if (asOperator) {
            UnitOperator link = new UnitOperator();
            link.setUnitId(unitId);
            link.setUserId(user.getId());
            unitOperatorRepository.saveAndFlush(link);
        }
        return user;
    }

    private record Fixture(
            Long sheetId,
            Long operatorId,
            Long supervisorId,
            Long otherOperatorId,
            Long assetId) {}
}
