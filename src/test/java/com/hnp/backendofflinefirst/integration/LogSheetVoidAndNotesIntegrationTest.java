package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AssetParameterReportService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class LogSheetVoidAndNotesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired LogSheetService logSheetService;
    @Autowired AssetParameterReportService reportService;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UserRepository userRepository;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void voidExcludesReadingsFromParameterReportAndUnvoidRestores() {
        Fixture fx = seedSubmittedSheetWithReading();

        assertThat(reportService.countSubmittedReadings(fx.asset.getId(), null, null)).isEqualTo(1);

        authenticateAdmin(fx.admin);
        assignmentService.voidSubmitted(fx.sheet.getId(), fx.admin.getId(), ActionSource.WEB);

        LogSheet voided = logSheetRepository.findById(fx.sheet.getId()).orElseThrow();
        assertThat(voided.getStatus()).isEqualTo(LogSheetStatus.VOIDED);
        assertThat(voided.getCompletedAt()).isNotNull();
        assertThat(reportService.countSubmittedReadings(fx.asset.getId(), null, null)).isZero();
        assertThat(reportService.buildValueHistoryPage(
                fx.asset.getId(), null, null, null, PageRequest.of(0, 20)).getTotalElements()).isZero();

        assignmentService.restoreVoided(fx.sheet.getId(), fx.admin.getId(), ActionSource.WEB);
        assertThat(logSheetRepository.findById(fx.sheet.getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(reportService.countSubmittedReadings(fx.asset.getId(), null, null)).isEqualTo(1);
    }

    @Test
    void unitSupervisorCanVoidAndCannotReopenVoidedDirectly() {
        Fixture fx = seedSubmittedSheetWithReading();
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(fx.unit.getId());
        link.setUserId(fx.supervisor.getId());
        unitSupervisorRepository.save(link);

        authenticateUser(fx.supervisor, Set.of("SUPERVISOR"));
        assignmentService.voidSubmitted(fx.sheet.getId(), fx.supervisor.getId(), ActionSource.WEB);
        assertThat(logSheetRepository.findById(fx.sheet.getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.VOIDED);

        assertThatThrownBy(() -> assignmentService.reopenSubmittedWithExtend(
                fx.sheet.getId(), fx.supervisor.getId(), System.currentTimeMillis() + 60_000L, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only submitted");
    }

    @Test
    void webDraftPersistsNotes() {
        Fixture fx = seedOpenSheetForFill();
        authenticateAdmin(fx.admin);

        Map<String, Map<String, Object>> values = new HashMap<>();
        values.put(String.valueOf(fx.entryId), Map.of("temp", 11));
        logSheetService.saveDraftFromWeb(fx.sheet.getId(), values, "  web note  ");

        LogSheet saved = logSheetRepository.findById(fx.sheet.getId()).orElseThrow();
        assertThat(saved.getNotes()).isEqualTo("web note");
        assertThat(saved.getDraftSavedAt()).isNotNull();
    }

    private void authenticateAdmin(User admin) {
        authenticateUser(admin, Set.of("ADMIN"));
    }

    private void authenticateUser(User user, Set<String> roles) {
        AppUserDetails principal = new AppUserDetails(user, roles, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Fixture seedSubmittedSheetWithReading() {
        Fixture fx = seedOpenSheetForFill();
        long now = System.currentTimeMillis();
        LogSheet sheet = fx.sheet;
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedAt(now);
        sheet.setSubmittedAt(now);
        sheet.setSyncedAt(now);
        sheet.setCompletedByUserId(fx.admin.getId());
        logSheetRepository.save(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).getFirst();
        entry.setFormData(Map.of("temp", 42));
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        logSheetEntryRepository.save(entry);
        return fx;
    }

    private Fixture seedOpenSheetForFill() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("VD-U-" + now);
        unit.setName("Void Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        User admin = new User();
        admin.setUsername("vd-admin-" + now);
        admin.setPasswordHash("{noop}x");
        admin.setActive(true);
        admin.setAuthType(UserAuthType.LOCAL);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        admin = userRepository.save(admin);

        User supervisor = new User();
        supervisor.setUsername("vd-sup-" + now);
        supervisor.setPasswordHash("{noop}x");
        supervisor.setActive(true);
        supervisor.setAuthType(UserAuthType.LOCAL);
        supervisor.setCreatedAt(now);
        supervisor.setUpdatedAt(now);
        supervisor = userRepository.save(supervisor);

        Location location = new Location();
        location.setCode("VD-LOC-" + now);
        location.setName("Void Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of(unit.getId()));

        SubFunction sf = new SubFunction();
        sf.setCode("VD-SF-" + now);
        sf.setName("Void SF");
        sf.setTag("VD-NFC-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetClass cls = new AssetClass();
        cls.setName("Void Class " + now);
        cls.setCreatedAt(now);
        cls.setUpdatedAt(now);
        cls = assetClassRepository.save(cls);

        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(cls.getId());
        fd.setKey("temp");
        fd.setLabel("Temp");
        fd.setDataType("number");
        fd.setCreatedAt(now);
        fd.setUpdatedAt(now);
        fieldDefinitionRepository.save(fd);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("VD-A-" + now);
        asset.setAssetName("Void Asset");
        asset.setClassId(cls.getId());
        asset.setSubFunctionId(sf.getId());
        asset.setActive(true);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Void Sheet");
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(admin.getId());
        sheet.setDueAt(now + 3_600_000L);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.save(sheet);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheet.getId());
        entry.setAssetId(asset.getId());
        entry.setAssetName(asset.getAssetName());
        entry.setClassId(cls.getId());
        entry.setFormData(new HashMap<>());
        entry = logSheetEntryRepository.save(entry);

        return new Fixture(unit, admin, supervisor, asset, sheet, entry.getId());
    }

    private record Fixture(OperationalUnit unit, User admin, User supervisor, AssetEntry asset,
                           LogSheet sheet, Long entryId) {}
}
