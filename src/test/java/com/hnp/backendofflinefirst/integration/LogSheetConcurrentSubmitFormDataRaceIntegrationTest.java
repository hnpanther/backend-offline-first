package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.dto.LogSheetSubmitResult;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent mobile submits must not let the losing request overwrite the winner's entry formData.
 */
class LogSheetConcurrentSubmitFormDataRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void losingConcurrentSubmitDoesNotOverwriteWinnerFormData() throws Exception {
        Fixture fixture = seedInProgressSheetWithEntry();
        long completedAt = System.currentTimeMillis();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<String> outcomeA = new AtomicReference<>();
        AtomicReference<String> outcomeB = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> submitA = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                authenticate(fixture.operatorId());
                try {
                    outcomeA.set(submitWithTemp(fixture, completedAt, 10, "race-a").getOutcome());
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            });
            Future<?> submitB = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                authenticate(fixture.operatorId());
                try {
                    outcomeB.set(submitWithTemp(fixture, completedAt, 20, "race-b").getOutcome());
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            submitA.get(20, TimeUnit.SECONDS);
            submitB.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }

        assertThat(List.of(outcomeA.get(), outcomeB.get()))
                .containsExactlyInAnyOrder("SUBMITTED", "DUPLICATE");

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(sheet.getCompletedByUserId()).isEqualTo(fixture.operatorId());

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(fixture.sheetId()).getFirst();
        Object expectedTemp = "SUBMITTED".equals(outcomeA.get()) ? 10 : 20;
        Object loserTemp = "SUBMITTED".equals(outcomeA.get()) ? 20 : 10;

        assertThat(entry.getFormData()).containsEntry("temp", expectedTemp);
        assertThat(entry.getFormData()).doesNotContainEntry("temp", loserTemp);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lateDuplicateSubmitAfterWinnerDoesNotOverwriteFormData() {
        Fixture fixture = seedInProgressSheetWithEntry();
        long completedAt = System.currentTimeMillis();

        authenticate(fixture.operatorId());
        try {
            assertThat(submitWithTemp(fixture, completedAt, 10, "winner").getOutcome())
                    .isEqualTo("SUBMITTED");
            assertThat(submitWithTemp(fixture, completedAt + 1, 20, "loser").getOutcome())
                    .isEqualTo("DUPLICATE");
        } finally {
            SecurityContextHolder.clearContext();
        }

        LogSheet sheet = logSheetRepository.findById(fixture.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(sheet.getCompletedByUserId()).isEqualTo(fixture.operatorId());

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(fixture.sheetId()).getFirst();
        assertThat(entry.getFormData()).containsEntry("temp", 10);
        assertThat(entry.getFormData()).doesNotContainEntry("temp", 20);
    }

    private LogSheetSubmitResult submitWithTemp(
            Fixture fixture, long completedAt, int temp, String actionSuffix) {
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

    private void authenticate(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        AppUserDetails principal = new AppUserDetails(user, Set.of("OPERATOR"), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Fixture seedInProgressSheetWithEntry() {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("FORM-RACE-BU-" + now);
        unit.setName("Form Race Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("FORM-RACE-LOC-" + now);
        location.setName("Form Race Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("FORM-RACE-SF-" + now);
        subFunction.setName("Form Race Sub");
        subFunction.setTag("NFC-FORM-RACE-" + now);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Form Race Pump " + now);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);
        saveTempField(assetClass.getId(), now);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("FORM-RACE-A-" + now);
        asset.setAssetName("Race Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        User operator = createOperator(unit.getId(), "form-race-op-" + now, "op12345");

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Form Race");
        sheet.setScopeSummary("location:" + location.getId());
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SELF_CLAIMED);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setClaimedAt(now - 60_000L);
        sheet.setStartedAt(now - 60_000L);
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

        return new Fixture(sheet.getId(), operator.getId(), asset.getId());
    }

    private void saveTempField(Long classId, long now) {
        FieldDefinition temp = new FieldDefinition();
        temp.setClassId(classId);
        temp.setKey("temp");
        temp.setLabel("Temperature");
        temp.setDataType("number");
        temp.setRequired(false);
        temp.setOrder(1);
        temp.setVersion(1);
        temp.setDeleted(false);
        temp.setSynced(false);
        temp.setCreatedAt(now);
        temp.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(temp);
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setFullName("Form Race Operator");
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

    private record Fixture(Long sheetId, Long operatorId, Long assetId) {}
}
