package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.CustomLogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

@Transactional
class CustomLogSheetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired CustomLogSheetService customLogSheetService;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired com.hnp.backendofflinefirst.service.MasterDataOptionsService masterDataOptionsService;

    @Test
    void createsMultiClassCustomSheetWithSnapshotAndEntries() {
        Fixture fx = seedFixture();

        long now = System.currentTimeMillis();
        long dueAt = now + 60 * 60_000L;

        try (var security = mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly)
                    .thenReturn(false);

            LogSheet sheet = customLogSheetService.createCustom(
                    fx.unit.getId(),
                    "Custom Multi-Class Round",
                    dueAt,
                    List.of(fx.pump.getId(), fx.motor.getId()),
                    1L,
                    now);

            assertThat(sheet.getId()).isNotNull();
            assertThat(sheet.getTemplateId()).isNull();
            assertThat(sheet.getTemplateName()).isEqualTo("Custom Multi-Class Round");
            assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.PENDING);
            assertThat(sheet.getOperationalUnitId()).isEqualTo(fx.unit.getId());
            assertThat(sheet.getDueAt()).isEqualTo(dueAt);
            assertThat(sheet.getFieldDefinitionsSnapshot()).isNotNull();
            assertThat(sheet.getFieldDefinitionsSnapshot())
                    .extracting(s -> s.getClassId())
                    .containsExactlyInAnyOrder(fx.pumpClass.getId(), fx.motorClass.getId());
            assertThat(sheet.getFieldDefinitionsSnapshot())
                    .extracting(s -> s.getKey())
                    .containsExactlyInAnyOrder("temp", "rpm");

            List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
            assertThat(entries).hasSize(2);
            assertThat(entries).extracting(LogSheetEntry::getAssetId)
                    .containsExactlyInAnyOrder(fx.pump.getId(), fx.motor.getId());
            assertThat(entries).extracting(LogSheetEntry::getClassId)
                    .containsExactlyInAnyOrder(fx.pumpClass.getId(), fx.motorClass.getId());
        }
    }

    @Test
    void rejectsInactiveAssetInSelection() {
        Fixture fx = seedFixture();
        fx.pump.setActive(false);
        assetEntryRepository.save(fx.pump);

        long now = System.currentTimeMillis();
        try (var security = mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly)
                    .thenReturn(false);

            assertThatThrownBy(() -> customLogSheetService.createCustom(
                    fx.unit.getId(), "Round", null,
                    List.of(fx.pump.getId(), fx.motor.getId()), 1L, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Test
    void rejectsAssetBelongingToAnotherUnit() {
        Fixture fx = seedFixture();
        Fixture other = seedOtherUnitAsset();

        long now = System.currentTimeMillis();
        try (var security = mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly)
                    .thenReturn(false);

            assertThatThrownBy(() -> customLogSheetService.createCustom(
                    fx.unit.getId(), "Round", null,
                    List.of(fx.pump.getId(), other.pump.getId()), 1L, now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not available");
        }
    }

    @Test
    void deniesUnitScopedSupervisorOutsideOwnUnits() {
        Fixture fx = seedFixture();
        long now = System.currentTimeMillis();

        try (var security = mockStatic(com.hnp.backendofflinefirst.security.SecurityUtils.class)) {
            security.when(com.hnp.backendofflinefirst.security.SecurityUtils::isUnitScopedOnly)
                    .thenReturn(true);

            // Actor has no supervised units → AccessDenied via empty supervisor scope
            // (OperationalUnitScopeService is real; no UnitSupervisor rows for user 99)
            assertThatThrownBy(() -> customLogSheetService.createCustom(
                    fx.unit.getId(), "Round", null,
                    List.of(fx.pump.getId()), 99L, now))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("units you supervise");
        }
    }

    @Test
    void searchAssetsForUnitReturnsOnlyActiveInScope() {
        Fixture fx = seedFixture();
        fx.motor.setActive(false);
        assetEntryRepository.save(fx.motor);
        Fixture other = seedOtherUnitAsset();

        var options = masterDataOptionsService.searchAssetsForUnit(null, fx.unit.getId(), 30);

        Set<String> values = options.stream().map(o -> o.value()).collect(Collectors.toSet());
        assertThat(values).contains(String.valueOf(fx.pump.getId()));
        assertThat(values).doesNotContain(String.valueOf(fx.motor.getId()));
        assertThat(values).doesNotContain(String.valueOf(other.pump.getId()));
    }

    private Fixture seedFixture() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("CU-" + now);
        unit.setName("Custom Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("CU-LOC-" + now);
        location.setName("Custom Hall");
        location.setUnitId(unit.getId());
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location);

        SubFunction sfPump = newSubFunction("CU-SF-P-" + now, "Pump SF", "NFC-CU-P-" + now, location, now);
        SubFunction sfMotor = newSubFunction("CU-SF-M-" + now, "Motor SF", "NFC-CU-M-" + now, location, now);

        AssetClass pumpClass = newClass("Pump Class " + now, now);
        AssetClass motorClass = newClass("Motor Class " + now, now);
        saveField(pumpClass.getId(), "temp", "Temperature", now);
        saveField(motorClass.getId(), "rpm", "RPM", now);

        AssetEntry pump = newAsset("CU-P-" + now, "Pump A", pumpClass.getId(), sfPump.getId(), now);
        AssetEntry motor = newAsset("CU-M-" + now, "Motor B", motorClass.getId(), sfMotor.getId(), now);

        return new Fixture(unit, location, pumpClass, motorClass, pump, motor);
    }

    private Fixture seedOtherUnitAsset() {
        long now = System.currentTimeMillis() + 7;
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("CU-O-" + now);
        unit.setName("Other Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("CU-OLOC-" + now);
        location.setName("Other Hall");
        location.setUnitId(unit.getId());
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location);

        SubFunction sf = newSubFunction("CU-OSF-" + now, "Other SF", "NFC-CU-O-" + now, location, now);
        AssetClass cls = newClass("Other Class " + now, now);
        AssetEntry asset = newAsset("CU-OA-" + now, "Other Pump", cls.getId(), sf.getId(), now);
        return new Fixture(unit, location, cls, null, asset, null);
    }

    private SubFunction newSubFunction(String code, String name, String tag, Location location, long now) {
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(name);
        sf.setTag(tag);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        return hierarchyService.saveSubFunction(sf);
    }

    private AssetClass newClass(String name, long now) {
        AssetClass cls = new AssetClass();
        cls.setName(name);
        cls.setCreatedAt(now);
        cls.setUpdatedAt(now);
        return assetClassRepository.save(cls);
    }

    private void saveField(Long classId, String key, String label, long now) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(label);
        fd.setDataType("number");
        fd.setRequired(false);
        fd.setCreatedAt(now);
        fd.setUpdatedAt(now);
        fieldDefinitionRepository.save(fd);
    }

    private AssetEntry newAsset(String code, String name, Long classId, Long sfId, long now) {
        AssetEntry a = new AssetEntry();
        a.setAssetCode(code);
        a.setAssetName(name);
        a.setClassId(classId);
        a.setSubFunctionId(sfId);
        a.setActive(true);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return assetEntryRepository.save(a);
    }

    private record Fixture(
            OperationalUnit unit,
            Location location,
            AssetClass pumpClass,
            AssetClass motorClass,
            AssetEntry pump,
            AssetEntry motor) {}
}
