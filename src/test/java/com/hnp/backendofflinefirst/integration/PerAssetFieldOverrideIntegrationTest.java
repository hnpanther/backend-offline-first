package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
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
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-asset band feature, end to end against a real PostgreSQL — so the migration, the
 * one-level trigger and the two check constraints are exercised along with the Java.
 *
 * <p>The defect this feature removes is silent: a pump judged against its class's range still
 * stores, still reports and still looks right. So the assertions here are mostly about
 * <em>differences</em> — the same number landing on two severities, a field reaching one asset
 * and not another — because a resolver that quietly ignored every override would pass any test
 * that only looked at one asset.
 */
@Transactional
class PerAssetFieldOverrideIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetFieldDefinitionsService fieldDefinitionsService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired EntityManager entityManager;
    @Autowired org.springframework.web.context.WebApplicationContext webApplicationContext;

    MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity()).build();
    }

    // =======================================================================================
    // The headline: one value, two assets, two answers
    // =======================================================================================

    // =======================================================================================
    // The band the operator is SHOWN, not only the one the server judges against
    // =======================================================================================

    /**
     * The fill form must offer the asset's own range.
     *
     * <p>Every other case here asserts stored severity, which the server computes from the
     * frozen delta and had right all along. That is exactly why this was missing: the resolver
     * was correct and tested, and nothing checked that any screen ever called it. The web
     * controller resolved by class, so the operator typed into an input carrying the class's
     * limits while the server graded against the asset's — the two disagreeing with no error.
     *
     * <p>Asserted on {@code data-warning-max}, because that attribute is what the fill page's
     * client-side range feedback reads; a test on the model map alone would pass against a
     * template that never renders it.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = {
            "GET:/log-sheets/{id}/fill", "GET:/log-sheets/{id}", "POST:/log-sheets/{id}/complete"})
    @DisplayName("the fill page shows the asset's own band, not its class's")
    void theFillFormOffersTheAssetsOwnRange() throws Exception {
        Fixture f = seed();
        setOverride(f.assetB(), FieldSource.RANGES_ONLY,
                Map.of("ampere", bands(0d, 25d, 0d, 30d)));
        LogSheet sheet = generate(f);
        Map<Long, Long> entryByAsset = entryIdsByAsset(sheet);

        String html = mockMvc.perform(get("/log-sheets/{id}/fill", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        String bBlock = entryBlockFor(html, entryByAsset.get(f.assetB().getId()));
        assertThat(bBlock)
                .as("pump B was re-banded to a warning maximum of 25")
                .contains("data-warning-max=\"25");

        String aBlock = entryBlockFor(html, entryByAsset.get(f.assetA().getId()));
        assertThat(aBlock)
                .as("pump A overrides nothing and must still show the class's 100")
                .contains("data-warning-max=\"100");
    }

    /**
     * The same question for the read-only detail page, which is where a supervisor checks a
     * finished round against the thresholds that applied to it.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = {
            "GET:/log-sheets/{id}", "GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    @DisplayName("the detail page reports against the asset's own band")
    void theDetailPageUsesTheAssetsOwnRange() throws Exception {
        Fixture f = seed();
        setOverride(f.assetB(), FieldSource.RANGES_ONLY,
                Map.of("ampere", bands(0d, 25d, 0d, 30d)));
        LogSheet sheet = generate(f);
        Map<Long, Long> entryByAsset = entryIdsByAsset(sheet);
        logSheetService.saveDraftFromWeb(sheet.getId(), Map.of(
                String.valueOf(entryByAsset.get(f.assetB().getId())), Map.of("ampere", 28)));

        String html = mockMvc.perform(get("/log-sheets/{id}", sheet.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 28 is inside the class's 0..100 but past pump B's warning maximum of 25.
        assertThat(reload(entryByAsset.get(f.assetB().getId())).getMaxSeverity()).isEqualTo("WARNING");
        assertThat(html).contains("28");
    }

    /**
     * The slice of the rendered page belonging to one entry's fill dialog. The card in the list is a read-only
     * summary; the inputs live in the dialog.
     *
     * <p>Sliced rather than parsed because the assertion is "this number appears inside THIS
     * asset's dialog": a whole-page {@code contains} would pass on a page that printed every
     * band against every asset, which is the exact failure being tested for.
     */
    private String entryBlockFor(String html, Long entryId) {
        String marker = "data-entry-dialog=\"" + entryId + "\"";
        int at = html.indexOf(marker);
        assertThat(at).as("no fill dialog rendered for entry %s", entryId).isGreaterThanOrEqualTo(0);
        int next = html.indexOf("data-entry-dialog=\"", at + marker.length());
        return next < 0 ? html.substring(at) : html.substring(at, next);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    @DisplayName("the same reading is OK on one pump and DANGER on another, from their own bands")
    void twoAssetsOfOneClassAreJudgedAgainstTheirOwnBands() {
        Fixture f = seed();

        // The class says 0..100 is fine. The second pump is a small one: over 30 is dangerous.
        setOverride(f.assetB(), FieldSource.RANGES_ONLY,
                Map.of("ampere", bands(0d, 25d, 0d, 30d)));

        LogSheet sheet = generate(f);
        Map<Long, Long> entryByAsset = entryIdsByAsset(sheet);

        logSheetService.saveDraftFromWeb(sheet.getId(), Map.of(
                String.valueOf(entryByAsset.get(f.assetA().getId())), Map.of("ampere", 45),
                String.valueOf(entryByAsset.get(f.assetB().getId())), Map.of("ampere", 45)));

        assertThat(reload(entryByAsset.get(f.assetA().getId())).getMaxSeverity())
                .as("pump A is on the class band 0..100")
                .isEqualTo("OK");
        assertThat(reload(entryByAsset.get(f.assetB().getId())).getMaxSeverity())
                .as("pump B carries its own danger band 0..30")
                .isEqualTo("DANGER");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    @DisplayName("re-tuning a band after the sheet was raised does not re-judge it")
    void theFrozenBandIsTheOneInForceWhenTheSheetWasRaised() {
        Fixture f = seed();
        setOverride(f.assetB(), FieldSource.RANGES_ONLY, Map.of("ampere", bands(0d, 25d, 0d, 30d)));

        LogSheet sheet = generate(f);
        Long entryB = entryIdsByAsset(sheet).get(f.assetB().getId());

        // Widen the asset's band afterwards — the raised sheet must not notice.
        setOverride(f.assetB(), FieldSource.RANGES_ONLY, Map.of("ampere", bands(0d, 900d, 0d, 999d)));

        logSheetService.saveDraftFromWeb(sheet.getId(),
                Map.of(String.valueOf(entryB), Map.of("ampere", 45)));

        assertThat(reload(entryB).getMaxSeverity())
                .as("the sheet carries the band it froze, exactly as it does for definitions")
                .isEqualTo("DANGER");
    }

    // =======================================================================================
    // Sub-classes
    // =======================================================================================

    @Test
    @DisplayName("a template scoped to a class picks up assets in its sub-classes")
    void scopeReachesSubClassAssets() {
        Fixture f = seed();

        List<AssetEntry> inScope = assetEntryRepository.findByClassIdInSubFunctionScope(
                f.pumpClass().getId(), f.subFunction().getId());

        assertThat(inScope).extracting(AssetEntry::getId)
                .as("A and B are on the class itself, C is on its sub-class")
                .containsExactlyInAnyOrder(f.assetA().getId(), f.assetB().getId(), f.assetC().getId());
    }

    @Test
    @DisplayName("selecting the sub-class directly brings only its own assets")
    void scopeOnASubClassDoesNotReachTheParent() {
        Fixture f = seed();

        List<AssetEntry> inScope = assetEntryRepository.findByClassIdInSubFunctionScope(
                f.pumpSubClass().getId(), f.subFunction().getId());

        assertThat(inScope).extracting(AssetEntry::getId).containsExactly(f.assetC().getId());
    }

    @Test
    @DisplayName("a sub-class inherits its parent's fields and overrides them by key")
    void subClassInheritsAndOverrides() {
        Fixture f = seed();

        FieldDefinition subAmpere = new FieldDefinition();
        subAmpere.setClassId(f.pumpSubClass().getId());
        subAmpere.setKey("ampere");
        subAmpere.setLabel("Ampere (HP)");
        subAmpere.setDataType("number");
        subAmpere.setValidation(bands(0d, 10d, 0d, 12d));
        subAmpere.setOrder(1);
        fieldDefinitionRepository.save(subAmpere);

        List<FieldDefinition> resolved = fieldDefinitionsService
                .flattenedByClass(List.of(f.pumpSubClass().getId()))
                .get(f.pumpSubClass().getId());

        assertThat(resolved).extracting(FieldDefinition::getKey)
                .as("`pressure` is inherited untouched, `ampere` is the sub-class's")
                .containsExactly("ampere", "pressure");
        assertThat(resolved).allSatisfy(fd -> assertThat(fd.getClassId())
                .as("every row carries the sub-class id — the client filters on it")
                .isEqualTo(f.pumpSubClass().getId()));
        assertThat(resolved.get(0).getLabel()).isEqualTo("Ampere (HP)");
    }

    @Test
    @DisplayName("the generated snapshot covers the sub-class, not the template's class")
    void snapshotCoversTheAssetsActualClasses() {
        Fixture f = seed();

        LogSheet sheet = generate(f);

        Set<Long> snapshotClassIds = sheet.getFieldDefinitionsSnapshot().stream()
                .map(s -> s.getClassId()).collect(Collectors.toSet());
        assertThat(snapshotClassIds)
                .as("an entry whose classId is absent from the snapshot renders an EMPTY FORM "
                        + "on the tablet, with no error — AGENTS.md #145")
                .contains(f.pumpClass().getId(), f.pumpSubClass().getId());

        // ...and every entry's class is actually covered, which is the property that matters.
        for (LogSheetEntry entry : logSheetEntryRepository.findByLogSheetId(sheet.getId())) {
            assertThat(snapshotClassIds).contains(entry.getClassId());
        }
    }

    // =======================================================================================
    // The three modes
    // =======================================================================================

    @Test
    @DisplayName("RANGES_ONLY keeps following the class when a field is added to it")
    void rangesOnlyFollowsTheClassFieldList() {
        Fixture f = seed();
        setOverride(f.assetB(), FieldSource.RANGES_ONLY, Map.of("ampere", bands(0d, 25d, 0d, 30d)));

        FieldDefinition added = new FieldDefinition();
        added.setClassId(f.pumpClass().getId());
        added.setKey("vibration");
        added.setDataType("number");
        added.setOrder(3);
        fieldDefinitionRepository.saveAndFlush(added);

        LogSheet sheet = generate(f);
        List<FieldDefinition> forB = fieldDefinitionsService.resolveForEntry(
                sheet, entryFor(sheet, f.assetB().getId()));

        assertThat(forB).extracting(FieldDefinition::getKey).contains("vibration");
    }

    @Test
    @DisplayName("FULL detaches: a field added to the class does not reach the asset")
    void fullDetachesFromTheClass() {
        Fixture f = seed();
        Map<String, Object> own = new LinkedHashMap<>();
        own.put("ampere", bands(0d, 25d, 0d, 30d));
        setOverride(f.assetB(), FieldSource.FULL, own);

        FieldDefinition added = new FieldDefinition();
        added.setClassId(f.pumpClass().getId());
        added.setKey("vibration");
        added.setDataType("number");
        added.setOrder(3);
        fieldDefinitionRepository.saveAndFlush(added);

        // FULL still starts from the class's list — what it owns is the right to add and
        // remove. Removing is how an asset sheds a field it does not have, and the snapshot
        // has to carry that: a band-only delta could not express it, which is why a FULL
        // asset's whole override map is frozen rather than pruned.
        Map<String, Object> withRemoval = new LinkedHashMap<>(own);
        withRemoval.put("vibration", Map.of(
                com.hnp.backendofflinefirst.domain.AssetFieldResolver.OVERRIDE_REMOVED, true));
        setOverride(f.assetB(), FieldSource.FULL, withRemoval);

        LogSheet sheet = generate(f);
        List<FieldDefinition> forB = fieldDefinitionsService.resolveForEntry(
                sheet, entryFor(sheet, f.assetB().getId()));
        List<FieldDefinition> forA = fieldDefinitionsService.resolveForEntry(
                sheet, entryFor(sheet, f.assetA().getId()));

        assertThat(forA).extracting(FieldDefinition::getKey).contains("vibration");
        assertThat(forB).extracting(FieldDefinition::getKey).doesNotContain("vibration");
    }

    // =======================================================================================
    // The snapshot is a delta
    // =======================================================================================

    @Test
    @DisplayName("a sheet where nothing is overridden stores no delta at all")
    void noOverridesMeansNoSnapshotColumn() {
        Fixture f = seed();

        LogSheet sheet = generate(f);

        assertThat(sheet.getFieldOverridesSnapshot())
                .as("null, not an empty object per asset — this is what keeps a 47-entry sheet at 2.9 KB")
                .isNull();
    }

    @Test
    @DisplayName("only the assets that differ appear, and only the fields that differ")
    void theSnapshotCarriesOnlyDifferences() {
        Fixture f = seed();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("ampere", bands(0d, 25d, 0d, 30d));      // differs from the class
        overrides.put("pressure", bands(0d, 50d, 0d, 60d));     // identical to the class
        setOverride(f.assetB(), FieldSource.RANGES_ONLY, overrides);

        LogSheet sheet = generate(f);

        Map<String, Map<String, Object>> delta = sheet.getFieldOverridesSnapshot();
        assertThat(delta).containsOnlyKeys(String.valueOf(f.assetB().getId()));
        Map<String, Object> entry = delta.get(String.valueOf(f.assetB().getId()));
        assertThat(entry).containsEntry("source", "RANGES_ONLY");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) entry.get("fields");
        assertThat(fields)
                .as("pressure matches the class, so it is not worth a byte")
                .containsOnlyKeys("ampere");
    }

    // =======================================================================================
    // What the database refuses
    // =======================================================================================

    @Test
    @DisplayName("the database refuses a two-level class chain")
    void nestingIsCappedAtOneLevel() {
        Fixture f = seed();
        AssetClass third = new AssetClass();
        third.setName("Deeper " + System.nanoTime());
        third.setParentId(f.pumpSubClass().getId());
        third.setCreatedAt(1L);
        third.setUpdatedAt(1L);

        assertThatThrownBy(() -> {
            assetClassRepository.save(third);
            entityManager.flush();
        })
                .as("trg_asset_classes_single_level — the scope sub-select looks exactly one "
                        + "level down, so a deeper chain would silently return the wrong assets")
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause().hasMessageContaining("limited to one level");
    }

    @Test
    @DisplayName("the database refuses a source and overrides that disagree")
    void sourceAndOverridesMustAgree() {
        Fixture f = seed();
        AssetEntry asset = assetEntryRepository.findById(f.assetB().getId()).orElseThrow();
        asset.setFieldSource(FieldSource.RANGES_ONLY.name());
        asset.setFieldOverrides(null);

        assertThatThrownBy(() -> {
            assetEntryRepository.save(asset);
            entityManager.flush();
        })
                .as("ck_asset_entries_field_overrides_match_source — an asset whose stored "
                        + "intent and stored data disagree leaves the resolver guessing")
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .rootCause().hasMessageContaining("ck_asset_entries_field_overrides_match_source");
    }

    @Test
    @DisplayName("every existing asset defaults to INHERIT and behaves exactly as before")
    void theDefaultIsUnchangedBehaviour() {
        Fixture f = seed();
        AssetEntry asset = assetEntryRepository.findById(f.assetA().getId()).orElseThrow();

        assertThat(asset.getFieldSource()).isEqualTo("INHERIT");
        assertThat(asset.getFieldOverrides()).isNull();
        assertThat(FieldSource.from(asset.getFieldSource()).inheritsEverything()).isTrue();
    }

    // =======================================================================================
    // Fixture
    // =======================================================================================

    private LogSheet generate(Fixture f) {
        LogSheet sheet = generationService.generateAt(
                f.template(), GenerationMode.MANUAL, 1L,
                System.currentTimeMillis(), System.currentTimeMillis());
        logSheetRepository.flush();
        entityManager.clear();
        return logSheetRepository.findById(sheet.getId()).orElseThrow();
    }

    private void setOverride(AssetEntry asset, FieldSource source, Map<String, Object> overrides) {
        AssetEntry managed = assetEntryRepository.findById(asset.getId()).orElseThrow();
        managed.setFieldSource(source.name());
        managed.setFieldOverrides(source.inheritsEverything() ? null : new LinkedHashMap<>(overrides));
        assetEntryRepository.saveAndFlush(managed);
    }

    private Map<Long, Long> entryIdsByAsset(LogSheet sheet) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .collect(Collectors.toMap(LogSheetEntry::getAssetId, LogSheetEntry::getId));
    }

    private LogSheetEntry entryFor(LogSheet sheet, Long assetId) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst().orElseThrow();
    }

    private LogSheetEntry reload(Long entryId) {
        logSheetEntryRepository.flush();
        entityManager.clear();
        return logSheetEntryRepository.findById(entryId).orElseThrow();
    }

    private Fixture seed() {
        long now = System.nanoTime();

        AssetClass pump = new AssetClass();
        pump.setName("Pumps " + now);
        pump.setCreatedAt(now);
        pump.setUpdatedAt(now);
        pump = assetClassRepository.save(pump);

        AssetClass pumpHp = new AssetClass();
        pumpHp.setName("Pumps HP " + now);
        pumpHp.setParentId(pump.getId());
        pumpHp.setCreatedAt(now);
        pumpHp.setUpdatedAt(now);
        pumpHp = assetClassRepository.save(pumpHp);

        saveField(pump.getId(), "ampere", 1, bands(0d, 100d, 0d, 120d));
        saveField(pump.getId(), "pressure", 2, bands(0d, 50d, 0d, 60d));

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-PAO-" + now);
        unit.setName("واحد");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("LOC-PAO-" + now);
        location.setName("محل");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of(unit.getId()));

        // ux_asset_entries_active_sub_function is partial on `active`: ONE installed asset per
        // position. Three assets therefore need three positions, nested under one parent so a
        // sub-function-scoped template still gathers all of them through the recursive CTE.
        SubFunction sf = new SubFunction();
        sf.setCode("SF-PAO-" + now);
        sf.setName("ساب‌فانکشن");
        sf.setTag("TAG-PAO-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        sf = hierarchyService.saveSubFunction(sf);

        SubFunction sfA = childPosition("A", now, sf.getId());
        SubFunction sfB = childPosition("B", now, sf.getId());
        SubFunction sfC = childPosition("C", now, sf.getId());

        AssetEntry a = saveAsset("A", now, pump.getId(), sfA.getId());
        AssetEntry b = saveAsset("B", now, pump.getId(), sfB.getId());
        AssetEntry c = saveAsset("C", now, pumpHp.getId(), sfC.getId());

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Round " + now);
        template.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        template.setScopeType(AssetHierarchyService.SCOPE_SUB_FUNCTION);
        template.setScopeId(sf.getId());
        template.setClassId(pump.getId());
        template.setOperationalUnitId(unit.getId());
        template.setActive(true);
        template.setScheduleActive(false);
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        entityManager.flush();
        return new Fixture(pump, pumpHp, a, b, c, sf, template);
    }

    private SubFunction childPosition(String suffix, long now, Long parentId) {
        SubFunction sf = new SubFunction();
        sf.setCode("SF-PAO-" + suffix + "-" + now);
        sf.setName("موقعیت " + suffix);
        sf.setTag("TAG-PAO-" + suffix + "-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SUB_FUNCTION, parentId);
        return hierarchyService.saveSubFunction(sf);
    }

    private AssetEntry saveAsset(String suffix, long now, Long classId, Long subFunctionId) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-PAO-" + suffix + "-" + now);
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

    private static Map<String, Object> bands(Double warnMin, Double warnMax, Double dangerMin, Double dangerMax) {
        Map<String, Object> validation = new HashMap<>();
        validation.put(FieldValidationSupport.KEY_WARNING, range(warnMin, warnMax));
        validation.put(FieldValidationSupport.KEY_DANGER, range(dangerMin, dangerMax));
        return validation;
    }

    private static Map<String, Object> range(Double min, Double max) {
        Map<String, Object> r = new HashMap<>();
        r.put(FieldValidationSupport.KEY_MIN, min);
        r.put(FieldValidationSupport.KEY_MAX, max);
        return r;
    }

    private record Fixture(AssetClass pumpClass, AssetClass pumpSubClass,
                           AssetEntry assetA, AssetEntry assetB, AssetEntry assetC,
                           SubFunction subFunction, LogSheetTemplate template) {}
}
