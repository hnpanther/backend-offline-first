package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.OperationalUnitService;
import com.hnp.backendofflinefirst.service.UserService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end checks for master-data / asset / template uniqueness and user FK rules
 * introduced in the consolidated V1 schema.
 */
@Transactional
class SchemaConstraintsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetHierarchyService hierarchyService;
    @Autowired AssetEntryService assetEntryService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository logSheetTemplateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired OperationalUnitService operationalUnitService;
    @Autowired UserRepository userRepository;
    @Autowired UserService userService;

    @Test
    void hierarchyCodesAreCaseInsensitiveUnique() {
        long t = System.currentTimeMillis();
        Location loc = saveLocation("LOC-CI-" + t, t);
        PlantSystem system = saveSystem("SYS-CI-" + t, loc.getId(), t);
        MainFunction mf = saveMainFunction("MF-CI-" + t, system.getId(), t);
        saveSubFunction("SF-CI-" + t, "TAG-CI-" + t, mf.getId(), t);

        Location dupLoc = new Location();
        dupLoc.setCode("loc-ci-" + t);
        dupLoc.setName("Dup");
        dupLoc.setCreatedAt(t);
        dupLoc.setUpdatedAt(t);
        assertThatThrownBy(() -> hierarchyService.saveLocation(dupLoc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate location code");

        PlantSystem dupSys = new PlantSystem();
        dupSys.setCode("sys-ci-" + t);
        dupSys.setName("Dup");
        dupSys.setLocationId(loc.getId());
        dupSys.setCreatedAt(t);
        dupSys.setUpdatedAt(t);
        assertThatThrownBy(() -> hierarchyService.savePlantSystem(dupSys))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate plant system code");

        MainFunction dupMf = new MainFunction();
        dupMf.setCode("mf-ci-" + t);
        dupMf.setName("Dup");
        dupMf.setCreatedAt(t);
        dupMf.setUpdatedAt(t);
        hierarchyService.applyMainFunctionParent(dupMf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        assertThatThrownBy(() -> hierarchyService.saveMainFunction(dupMf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate main function code");

        SubFunction dupSf = new SubFunction();
        dupSf.setCode("sf-ci-" + t);
        dupSf.setName("Dup");
        dupSf.setTag("OTHER-TAG-" + t);
        dupSf.setCreatedAt(t);
        dupSf.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(dupSf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mf.getId());
        assertThatThrownBy(() -> hierarchyService.saveSubFunction(dupSf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate sub function code");
    }

    @Test
    void subFunctionTagAndAssetClassNameAreCaseInsensitiveUnique() {
        long t = System.currentTimeMillis();
        Location loc = saveLocation("LOC-TAG-" + t, t);
        SubFunction first = new SubFunction();
        first.setCode("SF-TAG-A-" + t);
        first.setName("A");
        first.setTag("SHARED-TAG-" + t);
        first.setCreatedAt(t);
        first.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(first, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        hierarchyService.saveSubFunction(first);

        SubFunction second = new SubFunction();
        second.setCode("SF-TAG-B-" + t);
        second.setName("B");
        second.setTag("shared-tag-" + t);
        second.setCreatedAt(t);
        second.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(second, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        assertThatThrownBy(() -> hierarchyService.saveSubFunction(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate sub function tag");

        AssetClass ac = new AssetClass();
        ac.setName("PumpClass-" + t);
        ac.setCreatedAt(t);
        ac.setUpdatedAt(t);
        assetClassRepository.saveAndFlush(ac);

        AssetClass dup = new AssetClass();
        dup.setName("pumpclass-" + t);
        dup.setCreatedAt(t);
        dup.setUpdatedAt(t);
        assertThatThrownBy(() -> assetClassRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void fieldDefinitionKeyUniquePerClassCaseInsensitive() {
        long t = System.currentTimeMillis();
        AssetClass ac = new AssetClass();
        ac.setName("Class-FD-" + t);
        ac.setCreatedAt(t);
        ac.setUpdatedAt(t);
        ac = assetClassRepository.saveAndFlush(ac);

        FieldDefinition first = field("temperature", ac.getId(), t);
        fieldDefinitionRepository.saveAndFlush(first);

        FieldDefinition dup = field("Temperature", ac.getId(), t);
        assertThatThrownBy(() -> fieldDefinitionRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void fieldDefinitionAllowsSameKeyInDifferentClasses() {
        long t = System.currentTimeMillis();
        AssetClass firstClass = saveAssetClass("Class-FD-A-" + t, t);
        AssetClass secondClass = saveAssetClass("Class-FD-B-" + t, t);

        fieldDefinitionRepository.saveAndFlush(field("temperature", firstClass.getId(), t));
        FieldDefinition otherClass = field("temperature", secondClass.getId(), t);
        assertThat(fieldDefinitionRepository.saveAndFlush(otherClass).getId()).isNotNull();
    }

    @Test
    void hierarchyDisplayNamesMayContainSpacesAndAreNotUnique() {
        long t = System.currentTimeMillis();
        Location first = new Location();
        first.setCode("LOC-NAME-A-" + t);
        first.setName("MAIN FUNCTION 1");
        first.setCreatedAt(t);
        first.setUpdatedAt(t);
        hierarchyService.saveLocation(first);

        Location second = new Location();
        second.setCode("LOC-NAME-B-" + t);
        second.setName("MAIN FUNCTION 1");
        second.setCreatedAt(t);
        second.setUpdatedAt(t);
        assertThat(hierarchyService.saveLocation(second).getId()).isNotNull();
    }

    @Test
    void operationalUnitCodeIsCaseInsensitiveUnique() {
        long t = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-CI-" + t);
        unit.setName("Unit " + t);
        operationalUnitService.create(unit, List.of(), List.of());

        OperationalUnit dup = new OperationalUnit();
        dup.setCode("ou-ci-" + t);
        dup.setName("Other");
        assertThatThrownBy(() -> operationalUnitService.create(dup, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate operational unit code");
    }

    @Test
    void operationalUnitCodeUniqueIndexIsCaseInsensitive() {
        long t = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-DB-" + t);
        unit.setName("Unit");
        unit.setCreatedAt(t);
        unit.setUpdatedAt(t);
        operationalUnitRepository.saveAndFlush(unit);

        OperationalUnit raw = new OperationalUnit();
        raw.setCode("ou-db-" + t);
        raw.setName("Raw");
        raw.setCreatedAt(t);
        raw.setUpdatedAt(t);
        assertThatThrownBy(() -> operationalUnitRepository.saveAndFlush(raw))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assetCodeAndNfcAreCaseInsensitiveUniqueAndSubFunctionRequired() {
        long t = System.currentTimeMillis();
        Location loc = saveLocation("LOC-AE-" + t, t);
        SubFunction sf = new SubFunction();
        sf.setCode("SF-AE-" + t);
        sf.setName("SF");
        sf.setTag("TAG-AE-" + t);
        sf.setCreatedAt(t);
        sf.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry first = new AssetEntry();
        first.setAssetCode("AST-CI-" + t);
        first.setAssetName("Pump");
        first.setNfcTagId("NFC-CI-" + t);
        first.setSubFunctionId(sf.getId());
        assetEntryService.create(first);

        AssetEntry dupCode = new AssetEntry();
        dupCode.setAssetCode("ast-ci-" + t);
        dupCode.setAssetName("Other");
        dupCode.setNfcTagId("NFC-OTHER-" + t);
        dupCode.setSubFunctionId(sf.getId());
        assertThatThrownBy(() -> assetEntryService.create(dupCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate asset code");

        AssetEntry dupNfc = new AssetEntry();
        dupNfc.setAssetCode("AST-OTHER-" + t);
        dupNfc.setAssetName("Other");
        dupNfc.setNfcTagId("nfc-ci-" + t);
        dupNfc.setSubFunctionId(sf.getId());
        assertThatThrownBy(() -> assetEntryService.create(dupNfc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate NFC tag");

        AssetEntry noSf = new AssetEntry();
        noSf.setAssetCode("AST-NOSF-" + t);
        noSf.setAssetName("No SF");
        noSf.setNfcTagId("NFC-NOSF-" + t);
        assertThatThrownBy(() -> assetEntryService.create(noSf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sub function is required.");

        AssetEntry raw = new AssetEntry();
        raw.setAssetCode("AST-RAW-" + t);
        raw.setAssetName("Raw");
        raw.setCreatedAt(t);
        raw.setUpdatedAt(t);
        assertThatThrownBy(() -> assetEntryRepository.saveAndFlush(raw))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void logSheetTemplateNameIsCaseInsensitiveUnique() {
        long t = System.currentTimeMillis();
        AssetClass ac = saveAssetClass("Tpl-Class-" + t, t);

        LogSheetTemplate first = new LogSheetTemplate();
        first.setName("Round-" + t);
        first.setClassId(ac.getId());
        first.setGenerationMode(GenerationMode.MANUAL);
        first.setScheduleActive(false);
        first.setActive(true);
        first.setCreatedAt(t);
        first.setUpdatedAt(t);
        logSheetTemplateRepository.saveAndFlush(first);

        LogSheetTemplate dup = new LogSheetTemplate();
        dup.setName("round-" + t);
        dup.setClassId(ac.getId());
        dup.setGenerationMode(GenerationMode.MANUAL);
        dup.setScheduleActive(false);
        dup.setActive(true);
        dup.setCreatedAt(t);
        dup.setUpdatedAt(t);
        assertThatThrownBy(() -> logSheetTemplateRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void userWithLogSheetActivityCannotBeDeleted() {
        long t = System.currentTimeMillis();
        User actor = saveUser("actor-" + t, t);

        LogSheet sheet = new LogSheet();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCompletedByUserId(actor.getId());
        sheet.setCreatedAt(t);
        sheet.setUpdatedAt(t);
        logSheetRepository.saveAndFlush(sheet);

        Long actorId = actor.getId();
        assertThatThrownBy(() -> userService.delete(actorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Deactivate the user instead");
        assertThat(userRepository.findById(actorId)).isPresent();

        User unused = saveUser("unused-" + t, t);
        Long unusedId = unused.getId();
        userService.delete(unusedId);
        assertThat(userRepository.findById(unusedId)).isEmpty();
    }

    @Test
    void databaseRejectsHardDeleteOfUserReferencedByLogSheet() {
        long t = System.currentTimeMillis();
        User actor = saveUser("fk-actor-" + t, t);

        LogSheet sheet = new LogSheet();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCompletedByUserId(actor.getId());
        sheet.setCreatedAt(t);
        sheet.setUpdatedAt(t);
        logSheetRepository.saveAndFlush(sheet);

        Long actorId = actor.getId();
        assertThatThrownBy(() -> {
            userRepository.deleteById(actorId);
            userRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Location saveLocation(String code, long t) {
        Location loc = new Location();
        loc.setCode(code);
        loc.setName(code);
        loc.setCreatedAt(t);
        loc.setUpdatedAt(t);
        return hierarchyService.saveLocation(loc);
    }

    private PlantSystem saveSystem(String code, Long locationId, long t) {
        PlantSystem ps = new PlantSystem();
        ps.setCode(code);
        ps.setName(code);
        ps.setLocationId(locationId);
        ps.setCreatedAt(t);
        ps.setUpdatedAt(t);
        return hierarchyService.savePlantSystem(ps);
    }

    private MainFunction saveMainFunction(String code, Long systemId, long t) {
        MainFunction mf = new MainFunction();
        mf.setCode(code);
        mf.setName(code);
        mf.setCreatedAt(t);
        mf.setUpdatedAt(t);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, systemId);
        return hierarchyService.saveMainFunction(mf);
    }

    private SubFunction saveSubFunction(String code, String tag, Long mainFunctionId, long t) {
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag(tag);
        sf.setCreatedAt(t);
        sf.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mainFunctionId);
        return hierarchyService.saveSubFunction(sf);
    }

    private AssetClass saveAssetClass(String name, long t) {
        AssetClass ac = new AssetClass();
        ac.setName(name);
        ac.setCreatedAt(t);
        ac.setUpdatedAt(t);
        return assetClassRepository.saveAndFlush(ac);
    }

    private User saveUser(String username, long t) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash("{noop}x");
        user.setAuthType(UserAuthType.LOCAL);
        user.setActive(true);
        user.setCreatedAt(t);
        user.setUpdatedAt(t);
        return userRepository.saveAndFlush(user);
    }

    private static FieldDefinition field(String key, Long classId, long t) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setRequired(false);
        fd.setDeleted(false);
        fd.setSynced(false);
        fd.setVersion(1);
        fd.setOrder(1);
        fd.setCreatedAt(t);
        fd.setUpdatedAt(t);
        return fd;
    }
}
