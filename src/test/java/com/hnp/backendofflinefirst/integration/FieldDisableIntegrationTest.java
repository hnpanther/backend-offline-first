package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.FieldSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
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
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import com.hnp.backendofflinefirst.ui.FaMessages;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Switching a class parameter off and back on, against a real PostgreSQL.
 *
 * <p><strong>What makes this worth testing end to end.</strong> {@code field_definitions.deleted}
 * has existed since V1 and eight readers have always honoured it, but nothing ever wrote it —
 * so every one of those readers is being exercised for the first time here. The failures this
 * guards against are all silent in the same way: a sheet that quietly loses a parameter it
 * already carries, a reading that quietly stops being judged, a band that quietly reverts to
 * the class's, a tablet that quietly gets a form it cannot fill.
 *
 * <p>The two halves the feature stands on, which every test below is an angle on:
 * <ul>
 *   <li><strong>Forwards it is a real change</strong> — the parameter is not captured into the
 *       next sheet's snapshot, so it is never offered on a new round.</li>
 *   <li><strong>Backwards it is no change at all</strong> — a sheet already raised froze its own
 *       schema, so it renders, validates and judges exactly as it did.</li>
 * </ul>
 */
@Transactional
class FieldDisableIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
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

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // =======================================================================================
    // Forwards: the next sheet
    // =======================================================================================

    @Test
    @DisplayName("a parameter switched off is absent from the NEXT sheet's frozen schema")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void switchingOffKeepsItOutOfTheNextSheet() throws Exception {
        Fixture f = seed();
        assertThat(snapshotKeys(generate(f))).contains("ampere", "pressure");

        disable(f.pumpClass().getId(), f.pressureFieldId(), true);

        assertThat(snapshotKeys(generate(f)))
                .as("a parameter nobody collects any more must not reach a tablet")
                .contains("ampere")
                .doesNotContain("pressure");
    }

    @Test
    @DisplayName("switching it back on puts it in the next sheet again")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void switchingItBackOnRestoresIt() throws Exception {
        Fixture f = seed();
        disable(f.pumpClass().getId(), f.pressureFieldId(), true);
        assertThat(snapshotKeys(generate(f))).doesNotContain("pressure");

        disable(f.pumpClass().getId(), f.pressureFieldId(), false);

        assertThat(snapshotKeys(generate(f))).contains("pressure");
    }

    @Test
    @DisplayName("the bands survive the round trip untouched")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void theBandSurvivesBeingSwitchedOffAndOn() throws Exception {
        Fixture f = seed();
        Map<String, Object> before = reloadField(f.pressureFieldId()).getValidation();

        disable(f.pumpClass().getId(), f.pressureFieldId(), true);
        disable(f.pumpClass().getId(), f.pressureFieldId(), false);

        assertThat(reloadField(f.pressureFieldId()).getValidation())
                .as("a switch that loses the configuration is a delete with extra clicks")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a parameter switched off on the parent is not inherited by its sub-classes")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void aDisabledParentParameterIsNotInherited() throws Exception {
        Fixture f = seed();
        assertThat(flattenedKeys(f.pumpSubClass().getId())).contains("pressure");

        disable(f.pumpClass().getId(), f.pressureFieldId(), true);

        assertThat(flattenedKeys(f.pumpSubClass().getId()))
                .as("inheritance resolves the parent's LIVE rows, not its history")
                .doesNotContain("pressure");
    }

    @Test
    @DisplayName("the switch is an edit: version up, synced cleared")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void theSwitchIsRecordedLikeAnyOtherEdit() throws Exception {
        Fixture f = seed();
        FieldDefinition before = reloadField(f.pressureFieldId());
        Integer versionBefore = before.getVersion();

        disable(f.pumpClass().getId(), f.pressureFieldId(), true);

        FieldDefinition after = reloadField(f.pressureFieldId());
        assertThat(after.isDeleted()).isTrue();
        assertThat(after.getVersion()).isGreaterThan(versionBefore == null ? 0 : versionBefore);
        assertThat(after.isSynced()).isFalse();
    }

    // =======================================================================================
    // Backwards: a sheet already raised
    // =======================================================================================

    @Test
    @DisplayName("a sheet raised BEFORE the switch still carries the parameter")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void anAlreadyRaisedSheetIsUntouched() throws Exception {
        Fixture f = seed();
        LogSheet raised = generate(f);
        LogSheetEntry entry = entryFor(raised, f.assetA().getId());

        disable(f.pumpClass().getId(), f.pressureFieldId(), true);

        LogSheet reloaded = logSheetRepository.findById(raised.getId()).orElseThrow();
        assertThat(reloaded.getFieldDefinitionsSnapshot())
                .as("the snapshot is the sheet's own copy; nothing may reach back into it")
                .anyMatch(fd -> "pressure".equals(fd.getKey()));
        assertThat(fieldDefinitionsService.resolveForEntry(reloaded, entry))
                .as("an operator mid-round must not lose a field under them")
                .extracting(FieldDefinition::getKey)
                .contains("pressure");
    }

    @Test
    @DisplayName("a reading taken on that sheet is still judged against its frozen band")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void aReadingOnAnOlderSheetIsStillJudged() throws Exception {
        Fixture f = seed();
        LogSheet raised = generate(f);
        LogSheetEntry entry = entryFor(raised, f.assetA().getId());

        disable(f.pumpClass().getId(), f.pressureFieldId(), true);

        LogSheet reloaded = logSheetRepository.findById(raised.getId()).orElseThrow();
        entry.setFormData(new LinkedHashMap<>(Map.of("pressure", 95)));
        EntrySeverityEvaluator.apply(entry,
                fieldDefinitionsService.resolveForEntry(reloaded, entry),
                fieldDefinitionsService.frozenDeltaFor(reloaded, entry.getAssetId()));

        assertThat(entry.getMaxSeverity())
                .as("95 is past the frozen danger maximum of 60; a switch must not make it OK")
                .isEqualTo(FieldValidationSeverity.DANGER.name());
    }

    // =======================================================================================
    // The reports — the one place that must still see it
    // =======================================================================================

    @Test
    @DisplayName("the report still resolves a parameter that was switched off, marked")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void theReportStillResolvesASwitchedOffParameter() throws Exception {
        Fixture f = seed();
        disable(f.pumpClass().getId(), f.pressureFieldId(), true);

        AssetEntry asset = assetEntryRepository.findById(f.assetA().getId()).orElseThrow();
        List<FieldDefinition> reportable = fieldDefinitionsService.resolveForReporting(asset);

        assertThat(reportable).extracting(FieldDefinition::getKey).contains("ampere", "pressure");
        FieldDefinition pressure = reportable.stream()
                .filter(fd -> "pressure".equals(fd.getKey())).findFirst().orElseThrow();
        assertThat(pressure.isDeleted()).isTrue();
        assertThat(pressure.getValidation())
                .as("without the band the history cannot say the reading breached")
                .isNotNull();
    }

    @Test
    @DisplayName("a parameter the ASSET switched off is reportable too")
    void theReportSeesAnAssetLevelSwitchOff() {
        Fixture f = seed();
        AssetEntry asset = assetEntryRepository.findById(f.assetA().getId()).orElseThrow();
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("warning", Map.of("min", 1.0, "max", 4.0));
        off.put("removed", true);
        asset.setFieldSource(FieldSource.FULL.name());
        asset.setFieldOverrides(new LinkedHashMap<>(Map.of("pressure", off)));
        assetEntryRepository.saveAndFlush(asset);

        List<FieldDefinition> reportable = fieldDefinitionsService.resolveForReporting(asset);

        FieldDefinition pressure = reportable.stream()
                .filter(fd -> "pressure".equals(fd.getKey())).findFirst().orElseThrow();
        assertThat(pressure.isDeleted()).isTrue();
        // The asset's own band, not its class's: that is what the reading was judged against.
        assertThat(FieldValidationSupport.rangeMax(
                pressure.getValidation(), FieldValidationSupport.KEY_WARNING)).isEqualTo(4.0);
        // ...and the live path still drops it, which is what keeps it off the next round.
        assertThat(fieldDefinitionsService.flattenedByClass(List.of(asset.getClassId()))
                .get(asset.getClassId()))
                .extracting(FieldDefinition::getKey).contains("pressure");
    }

    // =======================================================================================
    // The hard delete, now guarded
    // =======================================================================================

    @Test
    @DisplayName("deleting a parameter a reading uses is refused, and the row survives")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/delete",
            "CAP:SCOPE_PLANT_WIDE"})
    void deleteIsRefusedOnceSomethingUsesTheKey() throws Exception {
        Fixture f = seed();
        LogSheet sheet = generate(f);
        LogSheetEntry entry = entryFor(sheet, f.assetA().getId());
        entry.setFormData(new LinkedHashMap<>(Map.of("pressure", 12)));
        logSheetEntryRepository.saveAndFlush(entry);

        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/delete",
                        f.pumpClass().getId(), f.pressureFieldId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage",
                        FaMessages.fieldDefinitionInUseUseDisable()));

        assertThat(fieldDefinitionRepository.findById(f.pressureFieldId()))
                .as("an unguarded delete is how three readings on the live database "
                        + "came to belong to no definition")
                .isPresent();
    }

    @Test
    @DisplayName("a reading stored against a SUB-CLASS blocks the parent's delete")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/delete",
            "CAP:SCOPE_PLANT_WIDE"})
    void aSubClassReadingBlocksTheParentsDelete() throws Exception {
        Fixture f = seed();
        LogSheet sheet = generate(f);
        // Asset C belongs to the SUB-class and inherits `pressure`; its entry therefore carries
        // the sub-class id. A guard that asked about the parent alone would miss this entirely.
        LogSheetEntry entry = entryFor(sheet, f.assetC().getId());
        entry.setFormData(new LinkedHashMap<>(Map.of("pressure", 12)));
        logSheetEntryRepository.saveAndFlush(entry);

        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/delete",
                        f.pumpClass().getId(), f.pressureFieldId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(fieldDefinitionRepository.findById(f.pressureFieldId())).isPresent();
    }

    @Test
    @DisplayName("a band an asset set on one machine also blocks the delete")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/delete",
            "CAP:SCOPE_PLANT_WIDE"})
    void anAssetOverrideBlocksTheDelete() throws Exception {
        Fixture f = seed();
        AssetEntry asset = assetEntryRepository.findById(f.assetA().getId()).orElseThrow();
        asset.setFieldSource(FieldSource.RANGES_ONLY.name());
        asset.setFieldOverrides(new LinkedHashMap<>(
                Map.of("pressure", Map.of("warning", Map.of("min", 1.0, "max", 4.0)))));
        assetEntryRepository.saveAndFlush(asset);

        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/delete",
                        f.pumpClass().getId(), f.pressureFieldId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(fieldDefinitionRepository.findById(f.pressureFieldId())).isPresent();
    }

    @Test
    @DisplayName("a parameter nobody ever answered is still deletable outright")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/delete",
            "CAP:SCOPE_PLANT_WIDE"})
    void deleteStillWorksForAnUnusedParameter() throws Exception {
        Fixture f = seed();
        FieldDefinition typo = saveField(f.pumpClass().getId(), "presure_typo", 9,
                bands(0d, 1d, 0d, 2d));
        fieldDefinitionRepository.flush();

        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/delete",
                        f.pumpClass().getId(), typo.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(fieldDefinitionRepository.findById(typo.getId()))
                .as("a typo created and removed in the same minute deserves no tombstone")
                .isEmpty();
    }

    @Test
    @DisplayName("a sheet that merely FROZE the parameter does not block the delete")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/delete",
            "CAP:SCOPE_PLANT_WIDE"})
    void aFrozenSnapshotAloneDoesNotBlockTheDelete() throws Exception {
        Fixture f = seed();
        generate(f);   // the sheet freezes `pressure`, but nobody answers it

        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/delete",
                        f.pumpClass().getId(), f.pressureFieldId()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(fieldDefinitionRepository.findById(f.pressureFieldId()))
                .as("a raised sheet renders from its own copy and never consults this row")
                .isEmpty();
    }

    // =======================================================================================
    // The page \u2014 the only place the state is visible or reversible
    // =======================================================================================

    @Test
    @DisplayName("a switched-off parameter is listed, marked, and offers the way back")
    @WithAppUser(authorities = {"GET:/asset-classes/{classId}/fields",
            "POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "POST:/asset-classes/{classId}/fields/{fieldId}/delete",
            "CAP:SCOPE_PLANT_WIDE"})
    void theClassPageShowsTheStateAndTheWayBack() throws Exception {
        Fixture f = seed();
        FieldDefinition pressure = fieldDefinitionRepository.findById(f.pressureFieldId()).orElseThrow();
        pressure.setDeleted(true);
        fieldDefinitionRepository.saveAndFlush(pressure);
        entityManager.clear();

        String html = fieldsPage(f.pumpClass().getId());

        assertThat(html)
                .as("hidden, a switched-off parameter cannot be switched back on by anyone")
                .contains("pressure")
                .contains("\u063a\u06cc\u0631\u0641\u0639\u0627\u0644");
        assertThat(html)
                .as("the enable form posts the target state, not a toggle")
                .contains("value=\"false\"");
    }

    @Test
    @DisplayName("a live parameter offers the switch, not only the delete")
    @WithAppUser(authorities = {"GET:/asset-classes/{classId}/fields",
            "POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void theClassPageOffersTheSwitchOnALiveParameter() throws Exception {
        Fixture f = seed();

        String html = fieldsPage(f.pumpClass().getId());

        assertThat(html).contains("/fields/" + f.pressureFieldId() + "/state");
        assertThat(html).contains("value=\"true\"");
    }

    @Test
    @DisplayName("a sub-class is told when its parent switched an inherited parameter off")
    @WithAppUser(authorities = {"GET:/asset-classes/{classId}/fields",
            "POST:/asset-classes/{classId}/fields", "CAP:SCOPE_PLANT_WIDE"})
    void theSubClassPageExplainsAMissingInheritedParameter() throws Exception {
        Fixture f = seed();
        // Live: the sub-class page lists it as inherited, with the \u00aboverride\u00bb action.
        assertThat(fieldsPage(f.pumpSubClass().getId())).contains("inheritKey=pressure");

        FieldDefinition pressure = fieldDefinitionRepository.findById(f.pressureFieldId()).orElseThrow();
        pressure.setDeleted(true);
        fieldDefinitionRepository.saveAndFlush(pressure);
        entityManager.clear();

        String html = fieldsPage(f.pumpSubClass().getId());

        assertThat(html)
                .as("silently missing from an inheritance that is supposed to be automatic "
                        + "is the one thing this page must never be")
                .contains("pressure")
                .contains("\u063a\u06cc\u0631\u0641\u0639\u0627\u0644");
        assertThat(html)
                .as("overriding a parameter the parent switched off would resurrect it here")
                .doesNotContain("inheritKey=pressure");
    }

    // =======================================================================================
    // Access
    // =======================================================================================

    @Test
    @DisplayName("the switch needs its own permission")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}",
            "CAP:SCOPE_PLANT_WIDE"})
    void theSwitchIsNotCoveredByTheEditPermission() throws Exception {
        Fixture f = seed();

        // The web chain answers a denial with a redirect and a Persian flash, not a 403
        // (WebAccessDeniedHandler) — so the status alone cannot tell denial from success, and
        // the assertion has to be on the flash and on the row.
        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/state",
                        f.pumpClass().getId(), f.pressureFieldId())
                        .param("disabled", "true").with(csrf()))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attributeCount(1));

        assertThat(reloadField(f.pressureFieldId()).isDeleted()).isFalse();
    }

    @Test
    @DisplayName("a field of another class cannot be switched through this one's URL")
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields/{fieldId}/state",
            "CAP:SCOPE_PLANT_WIDE"})
    void theClassIdInTheUrlIsChecked() throws Exception {
        Fixture f = seed();

        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/state",
                        f.pumpSubClass().getId(), f.pressureFieldId())
                        .param("disabled", "true").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage", FaMessages.fieldDefinitionNotInClass()));

        assertThat(reloadField(f.pressureFieldId()).isDeleted()).isFalse();
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    private String fieldsPage(Long classId) throws Exception {
        return mockMvc.perform(get("/asset-classes/{classId}/fields", classId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void disable(Long classId, Long fieldId, boolean disabled) throws Exception {
        mockMvc.perform(post("/asset-classes/{c}/fields/{fid}/state", classId, fieldId)
                        .param("disabled", String.valueOf(disabled)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        fieldDefinitionRepository.flush();
        entityManager.clear();
    }

    private FieldDefinition reloadField(Long fieldId) {
        fieldDefinitionRepository.flush();
        entityManager.clear();
        return fieldDefinitionRepository.findById(fieldId).orElseThrow();
    }

    private List<String> snapshotKeys(LogSheet sheet) {
        return sheet.getFieldDefinitionsSnapshot().stream()
                .map(com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot::getKey)
                .toList();
    }

    private List<String> flattenedKeys(Long classId) {
        return fieldDefinitionsService.flattenedByClass(List.of(classId))
                .getOrDefault(classId, List.of()).stream()
                .map(FieldDefinition::getKey).toList();
    }

    private LogSheet generate(Fixture f) {
        LogSheetTemplate template = templateRepository.findById(f.template().getId()).orElseThrow();
        LogSheet sheet = generationService.generateAt(
                template, GenerationMode.MANUAL, 1L,
                System.currentTimeMillis(), System.currentTimeMillis());
        logSheetRepository.flush();
        entityManager.clear();
        return logSheetRepository.findById(sheet.getId()).orElseThrow();
    }

    private LogSheetEntry entryFor(LogSheet sheet, Long assetId) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst().orElseThrow();
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
        FieldDefinition pressure = saveField(pump.getId(), "pressure", 2, bands(0d, 50d, 0d, 60d));

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-FD-" + now);
        unit.setName("واحد");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("LOC-FD-" + now);
        location.setName("محل");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of(unit.getId()));

        SubFunction sf = new SubFunction();
        sf.setCode("SF-FD-" + now);
        sf.setName("ساب‌فانکشن");
        sf.setTag("TAG-FD-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        sf = hierarchyService.saveSubFunction(sf);

        // One installed asset per position — ux_asset_entries_active_sub_function is partial
        // on `active`, so three assets need three positions under one parent.
        AssetEntry a = saveAsset("A", now, pump.getId(), childPosition("A", now, sf.getId()).getId());
        AssetEntry c = saveAsset("C", now, pumpHp.getId(), childPosition("C", now, sf.getId()).getId());

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
        return new Fixture(pump, pumpHp, a, c, template, pressure.getId());
    }

    private SubFunction childPosition(String suffix, long now, Long parentId) {
        SubFunction sf = new SubFunction();
        sf.setCode("SF-FD-" + suffix + "-" + now);
        sf.setName("موقعیت " + suffix);
        sf.setTag("TAG-FD-" + suffix + "-" + now);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_SUB_FUNCTION, parentId);
        return hierarchyService.saveSubFunction(sf);
    }

    private AssetEntry saveAsset(String suffix, long now, Long classId, Long subFunctionId) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-FD-" + suffix + "-" + now);
        asset.setAssetName("پمپ " + suffix);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setActive(true);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.save(asset);
    }

    private FieldDefinition saveField(Long classId, String key, int order, Map<String, Object> validation) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setValidation(validation);
        fd.setOrder(order);
        fd.setVersion(1);
        fd.setCreatedAt(1L);
        fd.setUpdatedAt(1L);
        return fieldDefinitionRepository.save(fd);
    }

    private static Map<String, Object> bands(Double warnMin, Double warnMax,
                                             Double dangerMin, Double dangerMax) {
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
                           AssetEntry assetA, AssetEntry assetC,
                           LogSheetTemplate template, Long pressureFieldId) {}
}
