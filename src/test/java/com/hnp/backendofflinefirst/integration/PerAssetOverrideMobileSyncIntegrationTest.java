package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.dto.LogSheetBundleDto;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetBundleService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline round trip, with sub-classes and per-asset bands in play.
 *
 * <p>The question these answer is not "does the new feature work" — that is
 * {@link PerAssetFieldOverrideIntegrationTest} — but <strong>"does an operator's day still
 * work"</strong>. The mobile contract is the one place where a server-side change reaches a
 * device that may be running last month's build, down a mine, with no way to report a problem
 * except by not recording a round.
 *
 * <p>So: the bundle must still carry a usable form for every entry, a batch submitted from a
 * client that has never heard of overrides must still store correctly, and the server must
 * judge it against the right bands anyway.
 */
@Transactional
class PerAssetOverrideMobileSyncIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetBundleService bundleService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired EntityManager entityManager;

    // =======================================================================================
    // The bundle an operator downloads
    // =======================================================================================

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("every entry in the bundle resolves to a non-empty form, sub-class included")
    void theBundleCarriesAFormForEveryEntry() {
        Fixture f = seed();
        LogSheet sheet = generate(f);

        LogSheetBundleDto bundle = bundleService.buildFullBundle(sheet.getId());

        Set<Long> definitionClassIds = bundle.getContext().getFieldDefinitions().stream()
                .map(FieldDefinition::getClassId).collect(Collectors.toSet());
        assertThat(bundle.getEntries()).isNotEmpty();
        for (LogSheetEntryDto entry : bundle.getEntries()) {
            assertThat(definitionClassIds)
                    .as("the PWA filters definitions with fd.classId === entry.classId; an entry "
                            + "whose class is missing renders a blank form and says nothing")
                    .contains(entry.getClassId());
        }
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("the bundle names the sub-class, so the device can label it")
    void theBundleCarriesTheSubClassRow() {
        Fixture f = seed();
        LogSheet sheet = generate(f);

        LogSheetBundleDto bundle = bundleService.buildFullBundle(sheet.getId());

        assertThat(bundle.getContext().getAssetClasses())
                .extracting(AssetClass::getId)
                .as("getAssetClass(entry.classId) must find a row, or the asset shows no class")
                .contains(f.pumpClass().getId(), f.pumpSubClass().getId());
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("a sheet with no overrides is byte-identical to one generated before V5")
    void nothingIsAddedToABundleThatNeedsNothing() {
        Fixture f = seed();
        LogSheet sheet = generate(f);

        assertThat(bundleService.buildFullBundle(sheet.getId()).getSheet().getFieldOverridesSnapshot())
                .isNull();
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("the delta reaches the device, keyed by asset id as a string")
    void theDeltaIsCarriedToTheDevice() {
        Fixture f = seed();
        setOverride(f.assetB(), Map.of("ampere", bands(0d, 25d, 0d, 30d)));
        LogSheet sheet = generate(f);

        Map<String, Map<String, Object>> delta =
                bundleService.buildFullBundle(sheet.getId()).getSheet().getFieldOverridesSnapshot();

        assertThat(delta).containsOnlyKeys(String.valueOf(f.assetB().getId()));
        assertThat(delta.get(String.valueOf(f.assetB().getId())))
                .containsEntry("source", "RANGES_ONLY");
    }

    // =======================================================================================
    // What the operator sends back
    // =======================================================================================

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("a batch from a client that knows nothing about overrides is judged correctly")
    void anOldClientStillGetsTheRightSeverity() {
        Fixture f = seed();
        setOverride(f.assetB(), Map.of("ampere", bands(0d, 25d, 0d, 30d)));
        LogSheet sheet = generate(f);

        // Exactly what the PWA posts today: form data keyed by field key, no severity, no
        // override awareness, and a classId it read back out of the bundle.
        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("offline-round-1");
        dto.setClientActionId("client-" + sheet.getId());
        dto.setEntries(List.of(
                entryDto(f.assetA().getId(), f.pumpClass().getId(), 45),
                entryDto(f.assetB().getId(), f.pumpClass().getId(), 45)));

        assertThat(logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome())
                .isEqualTo("SUBMITTED");

        Map<Long, LogSheetEntry> stored = reloadEntries(sheet);
        assertThat(stored.get(f.assetA().getId()).getMaxSeverity()).isEqualTo("OK");
        assertThat(stored.get(f.assetB().getId()).getMaxSeverity())
                .as("the server re-resolves from its own snapshot; the client's ignorance is harmless")
                .isEqualTo("DANGER");
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("a client that sends the wrong classId cannot change which bands are applied")
    void theClientsClassIdIsNotTrusted() {
        Fixture f = seed();
        setOverride(f.assetB(), Map.of("ampere", bands(0d, 25d, 0d, 30d)));
        LogSheet sheet = generate(f);

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("offline-round-2");
        dto.setClientActionId("client-tampered-" + sheet.getId());
        // A tampered or simply stale payload naming another class for this asset.
        dto.setEntries(List.of(entryDto(f.assetB().getId(), f.pumpSubClass().getId(), 45)));

        logSheetService.submitBatch(List.of(dto));

        assertThat(reloadEntries(sheet).get(f.assetB().getId()).getMaxSeverity())
                .as("resolution reads entry.getClassId() from the database, never the payload")
                .isEqualTo("DANGER");
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("values for a field the asset does not have are not invented into existence")
    void anUnknownFieldDoesNotBecomeASeverity() {
        Fixture f = seed();
        LogSheet sheet = generate(f);

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("offline-round-3");
        dto.setClientActionId("client-unknown-" + sheet.getId());
        LogSheetEntryDto entry = entryDto(f.assetA().getId(), f.pumpClass().getId(), 10);
        entry.getFormData().put("notAFieldOfThisClass", 9999);
        dto.setEntries(List.of(entry));

        logSheetService.submitBatch(List.of(dto));

        assertThat(reloadEntries(sheet).get(f.assetA().getId()).getMaxSeverity()).isEqualTo("OK");
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("re-submitting the same batch is still idempotent with an override in play")
    void resubmissionIsStillIdempotent() {
        Fixture f = seed();
        setOverride(f.assetB(), Map.of("ampere", bands(0d, 25d, 0d, 30d)));
        LogSheet sheet = generate(f);

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("offline-round-4");
        dto.setClientActionId("client-idem-" + sheet.getId());
        dto.setEntries(List.of(entryDto(f.assetB().getId(), f.pumpClass().getId(), 45)));

        String first = logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome();
        String second = logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome();

        assertThat(first).isEqualTo("SUBMITTED");
        assertThat(second)
                .as("the second post carries the same clientActionId and is recognised as a replay")
                .isEqualTo("DUPLICATE");
        assertThat(reloadEntries(sheet).get(f.assetB().getId()).getMaxSeverity()).isEqualTo("DANGER");
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("an asset moved to a sub-class after the sheet was raised does not change it")
    void movingAnAssetDoesNotDisturbARaisedSheet() {
        Fixture f = seed();
        LogSheet sheet = generate(f);
        Long entryA = entryIdsByAsset(sheet).get(f.assetA().getId());

        // The registry changes under a round that is already out in the field.
        AssetEntry managed = assetEntryRepository.findById(f.assetA().getId()).orElseThrow();
        managed.setClassId(f.pumpSubClass().getId());
        assetEntryRepository.saveAndFlush(managed);
        entityManager.clear();

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("offline-round-5");
        dto.setClientActionId("client-moved-" + sheet.getId());
        dto.setEntries(List.of(entryDto(f.assetA().getId(), f.pumpClass().getId(), 10)));

        assertThat(logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome())
                .isEqualTo("SUBMITTED");
        assertThat(logSheetEntryRepository.findById(entryA).orElseThrow().getMaxSeverity())
                .as("the entry froze its class at generation; the registry moving is not its problem")
                .isEqualTo("OK");
    }

    // =======================================================================================
    // Fixture
    // =======================================================================================

    /**
     * A field only this asset has must survive a mobile sync.
     *
     * <p>The merge resolves its own definitions and passes them to {@code storableFormData},
     * which keeps only the keys those definitions name. Resolved by class, an asset-added field
     * was dropped between the tablet and the database — the sync reported SUBMITTED, the row
     * stored, and the reading was simply not in it.
     *
     * <p>The web path has the same shape and its own test; they are separate because they
     * resolve independently, and a fix to one does not reach the other.
     */
    @Test
    @WithAppUser(roles = "ADMIN")
    @DisplayName("a field the asset added survives a mobile sync")
    void aFieldTheAssetAddedIsStoredFromMobile() {
        Fixture f = seed();
        Map<String, Object> own = new LinkedHashMap<>();
        own.put("vibration", Map.of(
                "dataType", "number",
                "label", "لرزش",
                "warning", Map.of("min", 0d, "max", 5d)));
        setOverride(f.assetB(), FieldSource.FULL, own);

        LogSheet sheet = generate(f);

        LogSheetDto dto = new LogSheetDto();
        dto.setServerId(sheet.getId());
        dto.setLocalId("offline-added-field");
        dto.setClientActionId("client-added-" + sheet.getId());
        LogSheetEntryDto entry = new LogSheetEntryDto();
        entry.setAssetId(f.assetB().getId());
        entry.setClassId(f.pumpClass().getId());
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("vibration", 7);
        entry.setFormData(formData);
        dto.setEntries(List.of(entry));

        assertThat(logSheetService.submitBatch(List.of(dto)).getFirst().getOutcome())
                .isEqualTo("SUBMITTED");

        LogSheetEntry stored = reloadEntries(sheet).get(f.assetB().getId());
        assertThat(stored.getFormData())
                .as("the asset's own field must reach the database")
                .containsKey("vibration");
        assertThat(stored.getMaxSeverity())
                .as("7 is past this asset's warning maximum of 5")
                .isEqualTo("WARNING");
    }

    private LogSheetEntryDto entryDto(Long assetId, Long classId, Number ampere) {
        LogSheetEntryDto dto = new LogSheetEntryDto();
        dto.setAssetId(assetId);
        dto.setClassId(classId);
        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("ampere", ampere);
        dto.setFormData(formData);
        return dto;
    }

    private Map<Long, LogSheetEntry> reloadEntries(LogSheet sheet) {
        logSheetEntryRepository.flush();
        entityManager.clear();
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, e -> e));
    }

    private Map<Long, Long> entryIdsByAsset(LogSheet sheet) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, LogSheetEntry::getId));
    }

    private LogSheet generate(Fixture f) {
        LogSheet sheet = generationService.generateAt(
                f.template(), GenerationMode.MANUAL, 1L,
                System.currentTimeMillis(), System.currentTimeMillis());
        logSheetRepository.flush();
        entityManager.clear();
        return logSheetRepository.findById(sheet.getId()).orElseThrow();
    }

    private void setOverride(AssetEntry asset, Map<String, Object> overrides) {
        setOverride(asset, FieldSource.RANGES_ONLY, overrides);
    }

    private void setOverride(AssetEntry asset, FieldSource source, Map<String, Object> overrides) {
        AssetEntry managed = assetEntryRepository.findById(asset.getId()).orElseThrow();
        managed.setFieldSource(source.name());
        managed.setFieldOverrides(new LinkedHashMap<>(overrides));
        assetEntryRepository.saveAndFlush(managed);
        entityManager.clear();
    }

    private Fixture seed() {
        long now = System.nanoTime();

        AssetClass pump = saveClass("Pumps " + now, null);
        AssetClass pumpHp = saveClass("Pumps HP " + now, pump.getId());
        saveField(pump.getId(), "ampere", 1, bands(0d, 100d, 0d, 120d));

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-SYNC-" + now);
        unit.setName("واحد");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("LOC-SYNC-" + now);
        location.setName("محل");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of(unit.getId()));

        SubFunction root = new SubFunction();
        root.setCode("SF-SYNC-" + now);
        root.setName("ساب‌فانکشن");
        root.setTag("TAG-SYNC-" + now);
        root.setCreatedAt(now);
        root.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(root, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        root = hierarchyService.saveSubFunction(root);

        AssetEntry a = saveAsset("A", now, pump.getId(), child("A", now, root.getId()).getId());
        AssetEntry b = saveAsset("B", now, pump.getId(), child("B", now, root.getId()).getId());
        saveAsset("C", now, pumpHp.getId(), child("C", now, root.getId()).getId());

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Sync round " + now);
        template.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        template.setScopeType(AssetHierarchyService.SCOPE_SUB_FUNCTION);
        template.setScopeId(root.getId());
        template.setClassId(pump.getId());
        template.setOperationalUnitId(unit.getId());
        template.setActive(true);
        template.setScheduleActive(false);
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        entityManager.flush();
        return new Fixture(pump, pumpHp, a, b, template);
    }

    private AssetClass saveClass(String name, Long parentId) {
        AssetClass c = new AssetClass();
        c.setName(name);
        c.setParentId(parentId);
        c.setCreatedAt(1L);
        c.setUpdatedAt(1L);
        return assetClassRepository.save(c);
    }

    private SubFunction child(String suffix, long now, Long parentId) {
        SubFunction sf = new SubFunction();
        sf.setCode("SF-SYNC-" + suffix + "-" + now);
        sf.setName("موقعیت " + suffix);
        sf.setTag("TAG-SYNC-" + suffix + "-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SUB_FUNCTION, parentId);
        return hierarchyService.saveSubFunction(sf);
    }

    private AssetEntry saveAsset(String suffix, long now, Long classId, Long subFunctionId) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-SYNC-" + suffix + "-" + now);
        asset.setAssetName("پمپ " + suffix);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setActive(true);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.save(asset);
    }

    private void saveField(Long classId, String key, int order, Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setValidation(validation);
        fd.setOrder(order);
        fd.setCreatedAt(1L);
        fd.setUpdatedAt(1L);
        fieldDefinitionRepository.save(fd);
    }

    private static Map<String, Object> bands(Double wMin, Double wMax, Double dMin, Double dMax) {
        Map<String, Object> v = new HashMap<>();
        v.put(FieldValidationSupport.KEY_WARNING, range(wMin, wMax));
        v.put(FieldValidationSupport.KEY_DANGER, range(dMin, dMax));
        return v;
    }

    private static Map<String, Object> range(Double min, Double max) {
        Map<String, Object> r = new HashMap<>();
        r.put(FieldValidationSupport.KEY_MIN, min);
        r.put(FieldValidationSupport.KEY_MAX, max);
        return r;
    }

    private record Fixture(AssetClass pumpClass, AssetClass pumpSubClass,
                           AssetEntry assetA, AssetEntry assetB, LogSheetTemplate template) {}
}
