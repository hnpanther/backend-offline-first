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
        SubFunction sf = saveLocationSubFunction("SF-AE-" + t, "TAG-AE-" + t, loc.getId(), t);
        SubFunction sfDupCode = saveLocationSubFunction("SF-AE-DC-" + t, "TAG-AE-DC-" + t, loc.getId(), t);
        SubFunction sfDupNfc = saveLocationSubFunction("SF-AE-DN-" + t, "TAG-AE-DN-" + t, loc.getId(), t);

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
        dupCode.setSubFunctionId(sfDupCode.getId());
        assertThatThrownBy(() -> assetEntryService.create(dupCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate asset code");

        AssetEntry dupNfc = new AssetEntry();
        dupNfc.setAssetCode("AST-OTHER-" + t);
        dupNfc.setAssetName("Other");
        dupNfc.setNfcTagId("nfc-ci-" + t);
        dupNfc.setSubFunctionId(sfDupNfc.getId());
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
    void assetSubFunctionIsUniqueAcrossActiveAssets() {
        long t = System.currentTimeMillis();
        Location loc = saveLocation("LOC-SFU-" + t, t);
        SubFunction sf = saveLocationSubFunction("SF-SFU-" + t, "TAG-SFU-" + t, loc.getId(), t);
        SubFunction otherSf = saveLocationSubFunction("SF-SFU-2-" + t, "TAG-SFU-2-" + t, loc.getId(), t);

        AssetEntry first = new AssetEntry();
        first.setAssetCode("AST-SFU-1-" + t);
        first.setAssetName("Pump");
        first.setNfcTagId("NFC-SFU-1-" + t);
        first.setSubFunctionId(sf.getId());
        first = assetEntryService.create(first);

        AssetEntry second = new AssetEntry();
        second.setAssetCode("AST-SFU-2-" + t);
        second.setAssetName("Other");
        second.setNfcTagId("NFC-SFU-2-" + t);
        second.setSubFunctionId(sf.getId());
        assertThatThrownBy(() -> assetEntryService.create(second))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This sub function is already assigned to another active asset.");

        AssetEntry updateClash = new AssetEntry();
        updateClash.setAssetCode("AST-SFU-3-" + t);
        updateClash.setAssetName("Third");
        updateClash.setNfcTagId("NFC-SFU-3-" + t);
        updateClash.setSubFunctionId(otherSf.getId());
        updateClash = assetEntryService.create(updateClash);

        AssetEntry form = new AssetEntry();
        form.setAssetCode(updateClash.getAssetCode());
        form.setAssetName(updateClash.getAssetName());
        form.setNfcTagId(updateClash.getNfcTagId());
        form.setSubFunctionId(sf.getId());
        form.setActive(true);
        Long clashId = updateClash.getId();
        assertThatThrownBy(() -> assetEntryService.update(clashId, form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This sub function is already assigned to another active asset.");

        // Same asset may keep its own sub-function on update.
        AssetEntry keepForm = new AssetEntry();
        keepForm.setAssetCode(first.getAssetCode());
        keepForm.setAssetName("Renamed");
        keepForm.setNfcTagId(first.getNfcTagId());
        keepForm.setSubFunctionId(sf.getId());
        keepForm.setActive(true);
        assetEntryService.update(first.getId(), keepForm);
        assertThat(assetEntryRepository.findById(first.getId())).get()
                .extracting(AssetEntry::getAssetName)
                .isEqualTo("Renamed");

        // DB unique index is the final safety net (keep last — integrity errors poison the session).
        AssetEntry rawDup = new AssetEntry();
        rawDup.setAssetCode("AST-SFU-RAW-" + t);
        rawDup.setAssetName("Raw");
        rawDup.setNfcTagId("NFC-SFU-RAW-" + t);
        rawDup.setSubFunctionId(sf.getId());
        rawDup.setCreatedAt(t);
        rawDup.setUpdatedAt(t);
        assertThatThrownBy(() -> assetEntryRepository.saveAndFlush(rawDup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assetDefaultsToActiveAndInactiveRemainsFindableByNfc() {
        long t = System.currentTimeMillis();
        Location loc = saveLocation("LOC-ACT-" + t, t);
        SubFunction sf = new SubFunction();
        sf.setCode("SF-ACT-" + t);
        sf.setName("SF");
        sf.setTag("TAG-ACT-" + t);
        sf.setCreatedAt(t);
        sf.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, loc.getId());
        sf = hierarchyService.saveSubFunction(sf);
        AssetClass ac = saveAssetClass("Class-ACT-" + t, t);

        AssetEntry created = new AssetEntry();
        created.setAssetCode("AST-ACT-" + t);
        created.setAssetName("Pump");
        created.setNfcTagId("NFC-ACT-" + t);
        created.setClassId(ac.getId());
        created.setSubFunctionId(sf.getId());
        created = assetEntryService.create(created);
        assertThat(created.isActive()).isTrue();
        assertThat(hierarchyService.findAssetsInScope(
                AssetHierarchyService.SCOPE_LOCATION, loc.getId(), ac.getId()))
                .extracting(AssetEntry::getId)
                .containsExactly(created.getId());

        created.setActive(false);
        assetEntryRepository.saveAndFlush(created);

        assertThat(assetEntryService.findByNfcTag("nfc-act-" + t)).isPresent();
        assertThat(hierarchyService.findAssetsInScope(
                AssetHierarchyService.SCOPE_LOCATION, loc.getId(), ac.getId()))
                .isEmpty();
    }

    @Test
    void userContactFieldsPersistAndAreOptional() {
        long t = System.currentTimeMillis();
        User withContacts = userService.create(
                "u-contact-" + t, "User", "0012345678901", "09120000000", "NFC-USER-" + t,
                "pass123", UserAuthType.LOCAL, true, List.of());
        assertThat(withContacts.getNationalCode()).isEqualTo("0012345678901");
        assertThat(withContacts.getPhoneNumber()).isEqualTo("09120000000");
        assertThat(withContacts.getNfcTagId()).isEqualTo("NFC-USER-" + t);

        User withoutContacts = userService.create(
                "u-plain-" + t, "Plain", null, null, null,
                "pass123", UserAuthType.LOCAL, true, List.of());
        assertThat(withoutContacts.getNationalCode()).isNull();
        assertThat(withoutContacts.getPhoneNumber()).isNull();
        assertThat(withoutContacts.getNfcTagId()).isNull();
        assertThat(userRepository.findById(withoutContacts.getId())).isPresent();
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

    @Test
    void replacingAnAssetOnASubFunctionHandsOverTheInheritedNfcTag() {
        // The real-world scenario: a pump breaks, is deactivated, and its replacement takes
        // over the very same sub-function — including the NFC tag operators scan in the field.
        long t = System.nanoTime();
        Location loc = saveLocation("LOC-SWAP-" + t, t);
        SubFunction sf = saveLocationSubFunction("SF-SWAP-" + t, "TAG-SWAP-" + t, loc.getId(), t);

        AssetEntry oldPump = new AssetEntry();
        oldPump.setAssetCode("AST-SWAP-OLD-" + t);
        oldPump.setAssetName("Old pump");
        oldPump.setSubFunctionId(sf.getId());
        oldPump = assetEntryService.create(oldPump);
        // No explicit tag was given, so it inherited the sub-function's.
        assertThat(oldPump.getNfcTagId()).isEqualTo("TAG-SWAP-" + t);

        // A second ACTIVE asset cannot join while the first one is still active.
        AssetEntry tooSoon = new AssetEntry();
        tooSoon.setAssetCode("AST-SWAP-EARLY-" + t);
        tooSoon.setAssetName("Early");
        tooSoon.setSubFunctionId(sf.getId());
        assertThatThrownBy(() -> assetEntryService.create(tooSoon))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This sub function is already assigned to another active asset.");

        // Deactivate the broken pump: it keeps its sub-function but releases the shared tag.
        AssetEntry deactivate = new AssetEntry();
        deactivate.setAssetCode(oldPump.getAssetCode());
        deactivate.setAssetName(oldPump.getAssetName());
        deactivate.setSubFunctionId(sf.getId());
        deactivate.setNfcTagId(oldPump.getNfcTagId());
        deactivate.setActive(false);
        assetEntryService.update(oldPump.getId(), deactivate);

        AssetEntry retired = assetEntryRepository.findById(oldPump.getId()).orElseThrow();
        assertThat(retired.isActive()).isFalse();
        assertThat(retired.getSubFunctionId()).isEqualTo(sf.getId());
        assertThat(retired.getNfcTagId()).isNull();

        // The replacement now attaches to the same sub-function and inherits the same tag.
        AssetEntry newPump = new AssetEntry();
        newPump.setAssetCode("AST-SWAP-NEW-" + t);
        newPump.setAssetName("New pump");
        newPump.setSubFunctionId(sf.getId());
        newPump = assetEntryService.create(newPump);
        assertThat(newPump.getNfcTagId()).isEqualTo("TAG-SWAP-" + t);

        // Both rows coexist on the sub-function; exactly one of them is active.
        List<AssetEntry> onSubFunction = assetEntryRepository.findBySubFunctionId(sf.getId());
        assertThat(onSubFunction).hasSize(2);
        assertThat(onSubFunction.stream().filter(AssetEntry::isActive).toList()).hasSize(1);

        // A field scan of that tag resolves to the replacement, not the retired asset.
        assertThat(assetEntryRepository.findByNfcTagIdIgnoreCase("TAG-SWAP-" + t))
                .get().extracting(AssetEntry::getId).isEqualTo(newPump.getId());
    }

    @Test
    void manyInactiveAssetsMayShareOneSubFunction() {
        long t = System.nanoTime();
        Location loc = saveLocation("LOC-MULTI-" + t, t);
        SubFunction sf = saveLocationSubFunction("SF-MULTI-" + t, "TAG-MULTI-" + t, loc.getId(), t);

        for (int i = 0; i < 3; i++) {
            AssetEntry retired = new AssetEntry();
            retired.setAssetCode("AST-MULTI-" + i + "-" + t);
            retired.setAssetName("Retired " + i);
            retired.setSubFunctionId(sf.getId());
            retired.setActive(false);
            assetEntryService.create(retired);
        }

        AssetEntry current = new AssetEntry();
        current.setAssetCode("AST-MULTI-CUR-" + t);
        current.setAssetName("Current");
        current.setSubFunctionId(sf.getId());
        assetEntryService.create(current);

        List<AssetEntry> all = assetEntryRepository.findBySubFunctionId(sf.getId());
        assertThat(all).hasSize(4);
        assertThat(all.stream().filter(AssetEntry::isActive).toList()).hasSize(1);
        // Only the active one carries the shared tag.
        assertThat(all.stream().filter(a -> ("TAG-MULTI-" + t).equals(a.getNfcTagId())).toList()).hasSize(1);
    }

    @Test
    void reactivatingARetiredAssetIsBlockedWhileASuccessorIsActive() {
        long t = System.nanoTime();
        Location loc = saveLocation("LOC-REACT-" + t, t);
        SubFunction sf = saveLocationSubFunction("SF-REACT-" + t, "TAG-REACT-" + t, loc.getId(), t);

        AssetEntry retired = new AssetEntry();
        retired.setAssetCode("AST-REACT-OLD-" + t);
        retired.setAssetName("Retired");
        retired.setSubFunctionId(sf.getId());
        retired.setActive(false);
        retired = assetEntryService.create(retired);

        AssetEntry current = new AssetEntry();
        current.setAssetCode("AST-REACT-CUR-" + t);
        current.setAssetName("Current");
        current.setSubFunctionId(sf.getId());
        assetEntryService.create(current);

        AssetEntry reactivate = new AssetEntry();
        reactivate.setAssetCode(retired.getAssetCode());
        reactivate.setAssetName(retired.getAssetName());
        reactivate.setSubFunctionId(sf.getId());
        reactivate.setActive(true);
        Long retiredId = retired.getId();
        assertThatThrownBy(() -> assetEntryService.update(retiredId, reactivate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This sub function is already assigned to another active asset.");
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

    private SubFunction saveLocationSubFunction(String code, String tag, Long locationId, long t) {
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag(tag);
        sf.setCreatedAt(t);
        sf.setUpdatedAt(t);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, locationId);
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
