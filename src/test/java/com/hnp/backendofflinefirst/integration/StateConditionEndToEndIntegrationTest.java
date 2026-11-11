package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.FieldGateOperators;
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
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetFieldOverrideService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A state condition through the <em>real</em> write path and the <em>real</em> pages.
 *
 * <p><strong>Why this file exists beside the unit tests.</strong> Templates are not compiled by
 * {@code mvn compile}, so a Thymeleaf expression error is a render-time 500 that a fully green
 * unit suite cannot see (AGENTS.md #159) — and this change touched the one `th:with` block on the
 * fill fragment, where a null map index or a mis-parenthesised ternary would do exactly that. It
 * also proves the three model attributes the gate needs are supplied by the <em>controllers</em>,
 * which the render tests set by hand.
 *
 * <p>The scenario is the one the feature was asked for: pump P-101, FLA 29 A, so «not drawing
 * current» is below 15% of it. Running and reading 0 A is a danger; stopped and reading 0 A is
 * normal, and the reading is kept either way.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/log-sheets/{id}/fill",
        "POST:/log-sheets/{id}/complete",
        "GET:/asset-classes/{classId}/fields",
        // The parameter screen has no permission of its own: a band is part of the asset, so it
        // carries the registry's `GET:/asset-entries`. Granting the URL-shaped name instead gets
        // a 302 to the list with «دارایی یافت نشد», which looks like a missing fixture.
        "GET:/asset-entries"})
class StateConditionEndToEndIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final double NO_LOAD = 4.5;

    @Autowired WebApplicationContext context;
    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired AssetFieldOverrideService fieldOverrideService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // =======================================================================================
    // The write path
    // =======================================================================================

    @Test
    @DisplayName("running and drawing no current is stored as a danger")
    void runningWithNoCurrentIsADanger() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryId()), Map.of("status", "روشن", "ampere", 0)));

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity()).isEqualTo("DANGER");
        assertThat(stored.getBreachedFields()).containsExactly("ampere");
    }

    @Test
    @DisplayName("stopped and drawing no current is normal, and the zero is still recorded")
    void stoppedWithNoCurrentIsNormalAndStillStored() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryId()), Map.of("status", "خاموش", "ampere", 0)));

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity())
                .as("suppressed by design — evaluated and clean, not «never evaluated»")
                .isEqualTo("OK");
        assertThat(stored.getBreachedFields()).isNull();
        assertThat(stored.getFormData())
                .as("the operator did not have to leave the box empty; the value is kept")
                .containsEntry("ampere", 0);
    }

    @Test
    @DisplayName("the same reading flips verdict when only the sibling changes")
    void theVerdictFollowsTheSibling() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryId()), Map.of("status", "خاموش", "ampere", 0)));
        assertThat(reload(f.entryId()).getMaxSeverity()).isEqualTo("OK");

        // Nothing about `ampere` changed. This is the whole feature in one assertion.
        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryId()), Map.of("status", "روشن", "ampere", 0)));
        assertThat(reload(f.entryId()).getMaxSeverity()).isEqualTo("DANGER");
    }

    @Test
    @DisplayName("running with the current not recorded is not a breach — that is what `required` is for")
    void anUnansweredGatedFieldIsNotABreach() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(), Map.of(
                String.valueOf(f.entryId()), Map.of("status", "روشن")));

        assertThat(reload(f.entryId()).getMaxSeverity()).isEqualTo("OK");
    }

    @Test
    @DisplayName("a per-asset gate is written, resolved and judged against this machine alone")
    void aPerAssetGateIsHonoured() {
        Fixture f = seed();

        // This one machine is a fixed-speed pump that is never switched off, so its current is
        // always meaningful: drop the gate for it alone, without detaching it to FULL.
        AssetEntry saved = fieldOverrideService.save(f.assetId(), FieldSource.RANGES_ONLY,
                Map.of("ampere", nullConditions()));
        assertThat(saved.getFieldSource()).isEqualTo(FieldSource.RANGES_ONLY.name());

        List<FieldDefinition> resolved = fieldOverrideService.effectiveFields(saved);
        Map<String, Object> ampere = resolved.stream()
                .filter(fd -> "ampere".equals(fd.getKey())).findFirst().orElseThrow()
                .getValidation();
        assertThat(FieldValidationSupport.conditions(ampere)).isEmpty();
        assertThat(FieldValidationSupport.evaluateValue("number", 0, ampere, Map.of("status", "خاموش")))
                .as("ungated for this asset: a zero is a danger whatever the status says")
                .isEqualTo(com.hnp.backendofflinefirst.domain.FieldValidationSeverity.DANGER);
    }

    // =======================================================================================
    // The pages — rendered, because nothing else catches a Thymeleaf mistake
    // =======================================================================================

    @Test
    @DisplayName("the fill page renders a gated field, names the condition, and withholds the live band")
    void theFillPageRendersTheConditionAndNoLiveBand() throws Exception {
        Fixture f = seed();
        claim(f.sheetId());

        String html = mockMvc.perform(get("/log-sheets/{id}/fill", f.sheetId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("the band is shown WITH its condition; printed alone it reads as the whole rule")
                .contains("فقط وقتی");
        assertThat(html)
                .as("the reference band itself is still there — the condition narrows it, it does not hide it")
                .contains("≥ 4.5");
        assertThat(html)
                .as("no data-danger-min on a gated field: log-sheet-fill.js would colour it "
                        + "unconditionally, and teaching it the gate would be a third copy of the judgement")
                .doesNotContain("data-danger-min");
    }

    @Test
    @DisplayName("the class field editor page renders, with the gate controls and the candidate list")
    void theFieldEditorPageRenders() throws Exception {
        Fixture f = seed();

        String html = mockMvc.perform(get("/asset-classes/{classId}/fields", f.classId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The controller's own attributes, not the render test's hand-built ones.
        assertThat(html).contains("state-condition-block");
        assertThat(html).contains("name=\"conditionField\"");
        assertThat(html)
                .as("gateCandidates reached the dropdown, so a gate can actually be configured")
                .contains("value=\"status\"");
        assertThat(html)
                .as("the list summary states the condition beside the band")
                .contains("فقط وقتی");
    }

    @Test
    @DisplayName("the asset parameter page renders, with the gate pre-filled from the class")
    void theAssetParameterPageRenders() throws Exception {
        Fixture f = seed();

        String html = mockMvc.perform(get("/asset-entries/{id}/parameters", f.assetId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("asset-gate-cell");
        assertThat(html)
                .as("the marker is what separates «cleared» from «this row never offered a gate»")
                .contains("gateRow");
        assertThat(html.replaceAll("\\s+", " "))
                .as("pre-filled from the class's gate, or saving the page would clear it")
                .contains("value=\"status\" selected=\"selected\"");
        assertThat(html)
                .as("the operator dropdown came from the controller's gateOperatorLabels")
                .contains("value=\"in\"");
    }

    // =================================================================== helpers

    private LogSheetEntry reload(Long entryId) {
        return logSheetEntryRepository.findById(entryId).orElseThrow();
    }

    /** The fill page is for the person holding the round. */
    private void claim(Long sheetId) {
        LogSheet sheet = logSheetRepository.findById(sheetId).orElseThrow();
        sheet.setAssigneeUserId(1L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.save(sheet);
    }

    /** `{"conditions": null}` — «this machine has no condition», not «inherit the class's». */
    private static Map<String, Object> nullConditions() {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put(FieldValidationSupport.KEY_CONDITIONS, null);
        return field;
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();

        AssetClass klass = new AssetClass();
        klass.setName("پمپ " + now);
        klass.setCreatedAt(now);
        klass.setUpdatedAt(now);
        klass = assetClassRepository.save(klass);

        FieldDefinition status = new FieldDefinition();
        status.setClassId(klass.getId());
        status.setKey("status");
        status.setLabel("وضعیت");
        status.setDataType("select");
        status.setValidation(FieldValidationSupport.build(
                "select", "روشن,خاموش", null, null, null, null, null, null));
        status.setOrder(1);
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        fieldDefinitionRepository.save(status);

        FieldDefinition ampere = new FieldDefinition();
        ampere.setClassId(klass.getId());
        ampere.setKey("ampere");
        ampere.setLabel("جریان");
        ampere.setUnit("A");
        ampere.setDataType("number");
        // «جریان کمتر از ۴٫۵ خطر است — فقط وقتی وضعیت روشن باشد.»
        ampere.setValidation(FieldValidationSupport.build(
                "number", null, null, null, NO_LOAD, null, null, null, null, true,
                new FieldValidationSupport.GateSpec("status", FieldGateOperators.IN, "روشن")));
        ampere.setOrder(2);
        ampere.setCreatedAt(now);
        ampere.setUpdatedAt(now);
        fieldDefinitionRepository.save(ampere);

        Location location = new Location();
        location.setCode("LOC-SC-" + now);
        location.setName("محل آزمون");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of());

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SF-SC-" + now);
        subFunction.setName("ساب‌فانکشن آزمون");
        subFunction.setTag("TAG-SC-" + now);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("P-101-" + now);
        asset.setAssetName("پمپ تغذیه");
        asset.setClassId(klass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-SC-" + now);
        unit.setName("واحد آزمون");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("State condition fixture");
        sheet.setScopeSummary("fixture");
        sheet.setOperationalUnitId(unit.getId());
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setAssigneeUserId(1L);
        sheet.setCreatedAt(now);
        sheet.setUpdatedAt(now);
        sheet = logSheetRepository.save(sheet);

        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheet.getId());
        entry.setAssetId(asset.getId());
        entry.setAssetName(asset.getAssetName());
        entry.setClassId(klass.getId());
        entry.setFormData(new HashMap<>());
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry = logSheetEntryRepository.save(entry);

        return new Fixture(sheet.getId(), entry.getId(), asset.getId(), klass.getId());
    }

    private record Fixture(Long sheetId, Long entryId, Long assetId, Long classId) {}
}
