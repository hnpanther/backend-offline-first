package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code max_severity} / {@code breached_fields} must stay true through the <em>real</em>
 * write paths, not just in the evaluator's unit tests.
 *
 * <p>A denormalised flag is only worth having if it can never go stale, so these cases drive
 * the actual service methods that mutate {@code form_data} — the web draft save and the web
 * completion — and re-check the persisted row after every edit, correction and clear. If a
 * new write path is ever added without calling the evaluator, the "value corrected" cases
 * here are what should fail.
 */
// Runs as ADMIN: web completion is restricted to the assignee plus a senior-operator or
// supervisor role, and none of that is what these tests are about — the subject is whether the
// severity flag tracks the value, not who may write it.
@Transactional
class EntrySeverityPersistenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired OperationalUnitRepository operationalUnitRepository;

    // warning 10–20, danger 5–25 → 12 ok, 22 warning, 40 danger
    private static final Map<String, Object> PRESSURE_VALIDATION = validation(10d, 20d, 5d, 25d);

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void savingADangerValueFromTheWebStampsTheEntry() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity()).isEqualTo("DANGER");
        assertThat(stored.getBreachedFields()).containsExactly("pressure");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void correctingTheValueOnASecondSaveClearsTheBreach() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));
        assertThat(reload(f.entryId()).getMaxSeverity()).isEqualTo("DANGER");

        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 12)));

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity())
                .as("the flag must follow the value, not survive it")
                .isEqualTo("OK");
        assertThat(stored.getBreachedFields()).isNull();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void aDangerIsDowngradedToWarningWhenTheValueMovesIntoTheWarningBand() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));
        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 22)));

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity()).isEqualTo("WARNING");
        assertThat(stored.getBreachedFields()).containsExactly("pressure");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void clearingTheValuesResetsTheFlagToNotEvaluated() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));
        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), new HashMap<>()));

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity())
                .as("an emptied entry must not keep advertising a breach")
                .isNull();
        assertThat(stored.getBreachedFields()).isNull();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void theFlagSurvivesFinalSubmissionAndMatchesTheSubmittedValue() {
        Fixture f = seed();

        // Draft one value, then submit a different one — the stored flag must describe what
        // was actually submitted, not what was drafted along the way.
        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 12)));
        logSheetService.completeFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));

        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);

        LogSheetEntry stored = reload(f.entryId());
        assertThat(stored.getMaxSeverity()).isEqualTo("DANGER");
        assertThat(stored.getFormData()).containsEntry("pressure", 40);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void aSubmittedBreachIsFoundByTheIndexedReportQuery() {
        Fixture f = seed();
        logSheetService.completeFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));

        List<Object[]> danger = logSheetEntryRepository.findBreachedEntries(
                null, null, null, true, PageRequest.of(0, 50));

        assertThat(danger)
                .as("the report reads the stored flag rather than re-evaluating form_data")
                .anySatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void aCorrectedEntryDisappearsFromTheReportQuery() {
        Fixture f = seed();
        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));
        logSheetService.completeFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 12)));

        List<Object[]> breaches = logSheetEntryRepository.findBreachedEntries(
                null, null, null, false, PageRequest.of(0, 50));

        assertThat(breaches)
                .as("a fixed reading must stop showing up as an exception")
                .noneSatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void warningsAreExcludedWhenTheCallerAsksForDangerOnly() {
        Fixture f = seed();
        logSheetService.completeFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 22)));

        assertThat(logSheetEntryRepository.findBreachedEntries(null, null, null, false, PageRequest.of(0, 50)))
                .as("a warning is a breach when everything is requested")
                .anySatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));

        assertThat(logSheetEntryRepository.findBreachedEntries(null, null, null, true, PageRequest.of(0, 50)))
                .noneSatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void aUnitScopedQueryOnlyReturnsItsOwnBreaches() {
        Fixture f = seed();
        logSheetService.completeFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));

        assertThat(logSheetEntryRepository.findBreachedEntries(
                Set.of(f.unitId()), null, null, true, PageRequest.of(0, 50)))
                .anySatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));

        assertThat(logSheetEntryRepository.findBreachedEntries(
                Set.of(f.unitId() + 99_999), null, null, true, PageRequest.of(0, 50)))
                .as("widening the flag must not widen who can see it")
                .noneSatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets/{id}/fill", "POST:/log-sheets/{id}/complete"})
    void anUnsubmittedDraftBreachIsNotReportedAsAnException() {
        Fixture f = seed();

        logSheetService.saveDraftFromWeb(f.sheetId(),
                Map.of(String.valueOf(f.entryId()), Map.of("pressure", 40)));

        assertThat(reload(f.entryId()).getMaxSeverity())
                .as("the flag is stamped as soon as the value is stored…")
                .isEqualTo("DANGER");
        assertThat(logSheetEntryRepository.findBreachedEntries(null, null, null, true, PageRequest.of(0, 50)))
                .as("…but only SUBMITTED work counts as a real reading")
                .noneSatisfy(row -> assertThat(((LogSheetEntry) row[0]).getId()).isEqualTo(f.entryId()));
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private LogSheetEntry reload(Long entryId) {
        logSheetEntryRepository.flush();
        return logSheetEntryRepository.findById(entryId).orElseThrow();
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();

        AssetClass klass = new AssetClass();
        klass.setName("کلاس فشار " + now);
        klass.setCreatedAt(now);
        klass.setUpdatedAt(now);
        klass = assetClassRepository.save(klass);

        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(klass.getId());
        fd.setKey("pressure");
        fd.setLabel("فشار");
        fd.setDataType("number");
        fd.setValidation(PRESSURE_VALIDATION);
        fd.setOrder(1);
        fd.setCreatedAt(now);
        fd.setUpdatedAt(now);
        fieldDefinitionRepository.save(fd);

        // asset_entries.sub_function_id is NOT NULL, so the placement chain has to exist
        // even though this test only cares about the entry's values.
        Location location = new Location();
        location.setCode("LOC-SEV-" + now);
        location.setName("محل آزمون");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = hierarchyService.saveLocation(location, List.of());

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SF-SEV-" + now);
        subFunction.setName("ساب‌فانکشن آزمون");
        subFunction.setTag("TAG-SEV-" + now);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AST-SEV-" + now);
        asset.setAssetName("پمپ آزمون");
        asset.setClassId(klass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-SEV-" + now);
        unit.setName("واحد آزمون");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);
        long unitId = unit.getId();

        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Severity fixture");
        sheet.setScopeSummary("fixture");
        sheet.setOperationalUnitId(unitId);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setOrigin(GenerationMode.MANUAL);
        // WithAppUser injects a fixed id=1 principal; making them the assignee is what the
        // web completion access check requires.
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

        return new Fixture(sheet.getId(), entry.getId(), unitId);
    }

    private static Map<String, Object> validation(double warnMin, double warnMax,
                                                  double dangerMin, double dangerMax) {
        Map<String, Object> warning = new HashMap<>();
        warning.put(FieldValidationSupport.KEY_MIN, warnMin);
        warning.put(FieldValidationSupport.KEY_MAX, warnMax);
        Map<String, Object> danger = new HashMap<>();
        danger.put(FieldValidationSupport.KEY_MIN, dangerMin);
        danger.put(FieldValidationSupport.KEY_MAX, dangerMax);
        Map<String, Object> validation = new HashMap<>();
        validation.put(FieldValidationSupport.KEY_WARNING, warning);
        validation.put(FieldValidationSupport.KEY_DANGER, danger);
        return validation;
    }

    private record Fixture(Long sheetId, Long entryId, Long unitId) {}
}
