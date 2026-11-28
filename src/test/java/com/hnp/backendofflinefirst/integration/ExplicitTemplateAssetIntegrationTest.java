package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.LogSheetTemplateAsset;
import com.hnp.backendofflinefirst.entity.MainFunction;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.PlantSystem;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.MasterDataDeleteService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hnp.backendofflinefirst.domain.TemplateParameterRules;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.support.WithAppUser;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end behaviour of EXPLICIT ("frozen list") templates against a real database.
 *
 * <p>The contract under test: a scheduled custom log sheet must reproduce the SAME assets on
 * every run — new assets appearing in the surrounding hierarchy must never join it — and the
 * only way an asset leaves the list is by being deactivated.
 */
@Transactional
class ExplicitTemplateAssetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired MasterDataDeleteService masterDataDeleteService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetTemplateAssetRepository templateAssetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;

    @Test
    void frozenListIgnoresAssetsAddedToTheSurroundingScopeAfterwards() {
        Fixture f = seed();
        LogSheetTemplate explicit = explicitTemplate(f, List.of(f.pumpA(), f.pumpB()));
        LogSheetTemplate scoped = scopeTemplate(f);

        // A new pump appears in exactly the location + class the scope template covers.
        AssetEntry newcomer = saveAsset("AST-NEW", f.pumpClass().getId(), newSubFunction(f, "SF-NEW"));

        assertThat(generatedAssetIds(explicit))
                .containsExactly(f.pumpA().getId(), f.pumpB().getId());
        // Control: the dynamic mode still behaves as before and DOES pick the newcomer up.
        assertThat(generatedAssetIds(scoped))
                .contains(newcomer.getId());
    }

    @Test
    void deactivatingAnAssetRemovesItFromTheNextGenerationOnly() {
        Fixture f = seed();
        LogSheetTemplate explicit = explicitTemplate(f, List.of(f.pumpA(), f.pumpB()));

        assertThat(generatedAssetIds(explicit)).containsExactly(f.pumpA().getId(), f.pumpB().getId());

        AssetEntry pumpA = assetEntryRepository.findById(f.pumpA().getId()).orElseThrow();
        pumpA.setActive(false);
        assetEntryRepository.save(pumpA);

        assertThat(generatedAssetIds(explicit)).containsExactly(f.pumpB().getId());
        // The membership row survives: reactivating the asset brings it back, no re-edit needed.
        assertThat(templateAssetRepository.findAssetIdsByTemplateId(explicit.getId()))
                .containsExactly(f.pumpA().getId(), f.pumpB().getId());

        pumpA.setActive(true);
        assetEntryRepository.save(pumpA);
        assertThat(generatedAssetIds(explicit)).containsExactly(f.pumpA().getId(), f.pumpB().getId());
    }

    @Test
    void frozenListMaySpanSeveralAssetClasses() {
        Fixture f = seed();
        LogSheetTemplate explicit = explicitTemplate(f, List.of(f.pumpA(), f.valve()));

        LogSheet sheet = generationService.generateFromTemplate(explicit, GenerationMode.MANUAL, null,
                System.currentTimeMillis());

        assertThat(logSheetEntryRepository.findByLogSheetId(sheet.getId()))
                .extracting(LogSheetEntry::getAssetId)
                .containsExactly(f.pumpA().getId(), f.valve().getId());
        // classId on the template stays null — an EXPLICIT template is not class-driven.
        assertThat(explicit.getClassId()).isNull();
        assertThat(sheet.getScopeSummary()).isNull();
    }

    @Test
    void anAssetFrozenIntoATemplateCannotBeHardDeleted() {
        Fixture f = seed();
        explicitTemplate(f, List.of(f.pumpA()));

        assertThatThrownBy(() -> masterDataDeleteService.deleteAssetEntry(f.pumpA().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("This asset entry is used by a fixed-list log sheet template and cannot be deleted.");
    }

    @Autowired FieldDefinitionRepository fieldRepository;
    @Autowired LogSheetTemplateService templateService;
    @Autowired jakarta.persistence.EntityManager em;

    @Test
    @WithAppUser(roles = "ADMIN")
    void perClassSelectionUsesPersistentBindingsAndFreezesEveryIssuedEntry() {
        Fixture f = seed();
        var template = explicitTemplate(f, List.of(f.pumpA(), f.pumpB(), f.valve()));
        var temperature = field(f.pumpClass().getId(), "temperature", "C");
        field(f.pumpClass().getId(), "current", "A");
        var valve = f.valve();
        valve.setFieldSource("FULL");
        valve.setFieldOverrides(Map.of("vibration", Map.of("label", "Drive-end vibration", "unit", "mm/s", "dataType", "number")));
        assetEntryRepository.saveAndFlush(valve);
        var vibrationId = valve.getFieldBindings().get("vibration").id().toString();
        var original = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null, 1000);
        var rule = new TemplateParameterRules(TemplateParameterRules.Mode.ONLY,
                Map.of(f.pumpClass().getId(), new TemplateParameterRules.ClassRule(
                        TemplateParameterRules.Mode.ALL_EXCEPT, Set.of(temperature.getBindingId().toString()))),
                Map.of(valve.getId(), new TemplateParameterRules.AssetRule(Set.of(vibrationId), Set.of(), false)));
        template.setNextRunAt(123456L);
        templateService.saveParameterRules(template.getId(), 0, rule);
        em.flush();
        em.clear();
        var saved = templateRepository.findById(template.getId()).orElseThrow();
        assertThat(saved.getNextRunAt()).isEqualTo(123456L);
        assertThat(saved.getParameterRules()).isEqualTo(rule);
        var preview = generationService.parameterPreview(saved, rule, false);
        var issued = generationService.generateFromTemplate(saved, GenerationMode.MANUAL, null, 2000);
        var schema = issued.getEntrySchema();
        assertThat(schema).isEqualTo(preview.freeze(true));
        assertThat(schema.definitions()).hasSize(2);
        assertThat(schema.fields(valve.getId())).extracting(FieldDefinitionSnapshot::getBindingId)
                .containsExactly(java.util.UUID.fromString(vibrationId));
        assertThat(schema.fields(f.pumpA().getId())).extracting(FieldDefinitionSnapshot::getKey).containsExactly("current");
        assertThat(original.getEntrySchema().fields(f.pumpA().getId())).hasSize(2);
        field(f.pumpClass().getId(), "speed", "rpm");
        field(valve.getClassId(), "position", null);
        var later = generationService.generateFromTemplate(saved, GenerationMode.MANUAL, null, 3000);
        assertThat(later.getEntrySchema().fields(f.pumpA().getId())).extracting(FieldDefinitionSnapshot::getKey)
                .containsExactlyInAnyOrder("current", "speed");
        assertThat(later.getEntrySchema().fields(valve.getId())).extracting(FieldDefinitionSnapshot::getKey)
                .containsExactly("vibration");
        em.flush();
        em.clear();
        assertThat(em.find(LogSheet.class, issued.getId()).getEntrySchema()).isEqualTo(schema);
        assertThatThrownBy(() -> templateService.saveParameterRules(saved.getId(), 0, TemplateParameterRules.all()))
                .hasMessageContaining("Reload");
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    void aRuleCannotSelectAnotherClassBindingOrLeaveAnUnacknowledgedEmptyEntry() {
        Fixture f = seed();
        var template = explicitTemplate(f, List.of(f.pumpA()));
        var other = field(f.valve().getClassId(), "temperature", null);
        var invalid = new TemplateParameterRules(TemplateParameterRules.Mode.ONLY,
                Map.of(f.pumpClass().getId(), new TemplateParameterRules.ClassRule(
                        TemplateParameterRules.Mode.ONLY, Set.of(other.getBindingId().toString()))), Map.of());
        assertThatThrownBy(() -> templateService.saveParameterRules(template.getId(), 0, invalid))
                .hasMessageContaining("Missing parameter binding").hasMessageContaining("Empty parameter list");
        assertThat(template.getParameterRevision()).isZero();
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    void inactiveEquipmentKeepsItsExceptionAcrossAnEditButCannotAcquireANewOne() {
        var f = seed();
        var template = explicitTemplate(f, List.of(f.pumpA(), f.pumpB()));
        var pressure = field(f.pumpClass().getId(), "pressure", "bar");
        var exception = new TemplateParameterRules.AssetRule(Set.of(), Set.of(pressure.getBindingId().toString()), true);
        var rules = new TemplateParameterRules(TemplateParameterRules.Mode.ALL_EXCEPT,
                Map.of(), Map.of(f.pumpB().getId(), exception));
        templateService.saveParameterRules(template.getId(), 0, rules);
        f.pumpB().setActive(false);
        assetEntryRepository.saveAndFlush(f.pumpB());
        templateService.saveParameterRules(template.getId(), 1, rules);
        var changed = new TemplateParameterRules(TemplateParameterRules.Mode.ALL_EXCEPT, Map.of(),
                Map.of(f.pumpB().getId(), new TemplateParameterRules.AssetRule(Set.of(), Set.of(), true)));
        assertThat(generationService.parameterPreview(template, changed, true).problems())
                .contains("Parameter target outside template scope.");
        var inactive = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null, 2000);
        assertThat(inactive.getEntrySchema().byAsset()).doesNotContainKey(f.pumpB().getId().toString());
        f.pumpB().setActive(true);
        assetEntryRepository.saveAndFlush(f.pumpB());
        var next = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null, 3000);
        assertThat(next.getEntrySchema().fields(f.pumpB().getId())).isEmpty();
        assertThat(next.getEntrySchema().fields(f.pumpA().getId())).hasSize(1);
    }

    @Test
    @WithAppUser(roles = "ADMIN")
    void scopedOccurrenceWithMissingDependencySkipsTheWholeRoundAndAdvancesOnce() {
        var f = seed();
        var scoped = scopeTemplate(f);
        var state = field(f.pumpClass().getId(), "running", null);
        var vibration = field(f.pumpClass().getId(), "vibration", "mm/s");
        vibration.setValidation(Map.of("conditions", List.of(Map.of("when", Map.of("field", state.getKey())))));
        fieldRepository.saveAndFlush(vibration);
        scoped.setParameterRules(new TemplateParameterRules(TemplateParameterRules.Mode.ONLY,
                Map.of(f.pumpClass().getId(), new TemplateParameterRules.ClassRule(
                        TemplateParameterRules.Mode.ONLY, Set.of(vibration.getBindingId().toString()))), Map.of()));
        long now = System.currentTimeMillis();
        scoped.setNextRunAt(now);
        scoped.setRecurrenceUnit(com.hnp.backendofflinefirst.domain.RecurrenceUnit.DAY);
        templateRepository.saveAndFlush(scoped);
        assertThat(generationService.parameterPreview(scoped, scoped.getParameterRules(), false).problems())
                .anySatisfy(problem -> assertThat(problem).contains("vibration", "running"));
        long sheetsBefore = em.createQuery("select count(s) from LogSheet s", Long.class).getSingleResult();
        generationService.runScheduled(scoped, now, 0);
        assertThat(em.createQuery("select count(s) from LogSheet s", Long.class).getSingleResult()).isEqualTo(sheetsBefore);
        assertThat(templateRepository.findById(scoped.getId()).orElseThrow().getNextRunAt()).isGreaterThan(now);
    }

    @Test
    void incompatibleUnitsAndHardRecreationRotateBindingsButRenamingAndDisablingDoNot() {
        var f = seed();
        var reading = field(f.pumpClass().getId(), "temperature", "C");
        var original = reading.getBindingId();
        reading.setLabel("Outlet temperature");
        reading.setDeleted(true);
        fieldRepository.saveAndFlush(reading);
        reading.setDeleted(false);
        fieldRepository.saveAndFlush(reading);
        assertThat(reading.getBindingId()).isEqualTo(original);
        reading.setUnit("F");
        fieldRepository.saveAndFlush(reading);
        assertThat(reading.getBindingId()).isNotEqualTo(original);
        var changed = reading.getBindingId();
        fieldRepository.delete(reading);
        fieldRepository.flush();
        assertThat(field(f.pumpClass().getId(), "temperature", "F").getBindingId()).isNotEqualTo(changed);
    }

    private FieldDefinition field(Long classId, String key, String unit) {
        var field = new FieldDefinition();
        field.setClassId(classId);
        field.setKey(key);
        field.setLabel(key);
        field.setDataType("number");
        field.setUnit(unit);
        return fieldRepository.saveAndFlush(field);
    }

    // ---- fixture ----

    private List<Long> generatedAssetIds(LogSheetTemplate template) {
        LogSheet sheet = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null,
                System.currentTimeMillis());
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .map(LogSheetEntry::getAssetId)
                .toList();
    }

    private LogSheetTemplate explicitTemplate(Fixture f, List<AssetEntry> assets) {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("Frozen round " + now);
        t.setOperationalUnitId(f.unit().getId());
        t.setAssetSelectionMode(AssetSelectionMode.EXPLICIT);
        t.setActive(true);
        t.setGenerationMode(GenerationMode.SCHEDULED);
        t.setScheduleActive(false);
        t.setRestrictScopeToUnit(true);
        t.setCompletionWindowMinutes(60);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        t = templateRepository.save(t);
        for (AssetEntry a : assets) {
            LogSheetTemplateAsset row = new LogSheetTemplateAsset();
            row.setTemplateId(t.getId());
            row.setAssetId(a.getId());
            templateAssetRepository.save(row);
        }
        return t;
    }

    // -- finding 18l: the compare-and-set that keeps two instances from doubling every round --

    /**
     * Exactly one caller can advance a template's cursor from a given value.
     *
     * <p>Asserted against the real database because it is the SQL that carries the guarantee —
     * the row count of an {@code UPDATE … WHERE next_run_at = :expected}. In the application this
     * shares a transaction with the generation it authorises, so the loser's rounds roll back
     * with it; here it is the statement's own semantics that are pinned.
     */
    @Test
    void onlyOneCallerCanAdvanceATemplateCursorFromTheSameValue() {
        Fixture f = seed();
        LogSheetTemplate t = scopeTemplate(f);
        long cursor = System.currentTimeMillis();
        long now = cursor + 1;
        t.setNextRunAt(cursor);
        templateRepository.saveAndFlush(t);

        int first = templateRepository.advanceCursorIfUnchanged(t.getId(), cursor, cursor + 3_600_000L, now);
        int second = templateRepository.advanceCursorIfUnchanged(t.getId(), cursor, cursor + 3_600_000L, now);

        assertThat(first).as("the winner").isEqualTo(1);
        assertThat(second).as("the loser, whose transaction must roll back").isZero();
        LogSheetTemplate reread = templateRepository.findById(t.getId()).orElseThrow();
        assertThat(reread.getNextRunAt()).isEqualTo(cursor + 3_600_000L);
        assertThat(reread.getLastRunAt()).isEqualTo(now);
    }

    /** The null-safe half: a template whose schedule has never run. */
    @Test
    void aNullCursorIsMatchedAndAdvancedExactlyOnce() {
        Fixture f = seed();
        LogSheetTemplate t = scopeTemplate(f);
        long now = System.currentTimeMillis();
        t.setNextRunAt(null);
        templateRepository.saveAndFlush(t);

        assertThat(templateRepository.advanceCursorIfUnchanged(t.getId(), null, now + 60_000L, now))
                .isEqualTo(1);
        assertThat(templateRepository.advanceCursorIfUnchanged(t.getId(), null, now + 60_000L, now))
                .isZero();
    }

    /** And a template nobody touched is not advanced by somebody else's expectation. */
    @Test
    void aMismatchedExpectationChangesNothing() {
        Fixture f = seed();
        LogSheetTemplate t = scopeTemplate(f);
        long cursor = System.currentTimeMillis();
        t.setNextRunAt(cursor);
        templateRepository.saveAndFlush(t);

        assertThat(templateRepository.advanceCursorIfUnchanged(t.getId(), cursor - 1, cursor + 99L, cursor))
                .isZero();
        assertThat(templateRepository.findById(t.getId()).orElseThrow().getNextRunAt()).isEqualTo(cursor);
    }

    private LogSheetTemplate scopeTemplate(Fixture f) {
        long now = System.currentTimeMillis();
        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("Dynamic round " + now);
        t.setOperationalUnitId(f.unit().getId());
        t.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        t.setScopeType("location");
        t.setScopeId(f.location().getId());
        t.setClassId(f.pumpClass().getId());
        t.setActive(true);
        t.setGenerationMode(GenerationMode.MANUAL);
        t.setScheduleActive(false);
        t.setRestrictScopeToUnit(true);
        t.setCompletionWindowMinutes(60);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return templateRepository.save(t);
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("OU-EXP");
        unit.setName("Explicit unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location loc = new Location();
        loc.setCode("LOC-EXP");
        loc.setName("Explicit location");
        loc.setCreatedAt(now);
        loc.setUpdatedAt(now);
        loc = hierarchyService.saveLocation(loc, List.of(unit.getId()));

        PlantSystem system = new PlantSystem();
        system.setCode("SYS-EXP");
        system.setName("Explicit system");
        system.setLocationId(loc.getId());
        system.setCreatedAt(now);
        system.setUpdatedAt(now);
        system = hierarchyService.savePlantSystem(system);

        MainFunction mf = new MainFunction();
        mf.setCode("MF-EXP");
        mf.setName("Explicit main");
        mf.setCreatedAt(now);
        mf.setUpdatedAt(now);
        hierarchyService.applyMainFunctionParent(mf, AssetHierarchyService.SCOPE_SYSTEM, system.getId());
        mf = hierarchyService.saveMainFunction(mf);

        AssetClass pumpClass = new AssetClass();
        pumpClass.setName("ExpPumpClass");
        pumpClass.setCreatedAt(now);
        pumpClass.setUpdatedAt(now);
        pumpClass = assetClassRepository.save(pumpClass);

        AssetClass valveClass = new AssetClass();
        valveClass.setName("ExpValveClass");
        valveClass.setCreatedAt(now);
        valveClass.setUpdatedAt(now);
        valveClass = assetClassRepository.save(valveClass);

        // asset_entries.sub_function_id is UNIQUE — one asset per sub-function.
        AssetEntry pumpA = saveAsset("AST-EXP-A", pumpClass.getId(), subFunctionUnder(mf.getId(), "SF-EXP-A"));
        AssetEntry pumpB = saveAsset("AST-EXP-B", pumpClass.getId(), subFunctionUnder(mf.getId(), "SF-EXP-B"));
        AssetEntry valve = saveAsset("AST-EXP-V", valveClass.getId(), subFunctionUnder(mf.getId(), "SF-EXP-V"));

        return new Fixture(unit, loc, mf, pumpClass, pumpA, pumpB, valve);
    }

    private Long newSubFunction(Fixture f, String code) {
        return subFunctionUnder(f.mainFunction().getId(), code);
    }

    private Long subFunctionUnder(Long mainFunctionId, String code) {
        long now = System.currentTimeMillis();
        SubFunction sf = new SubFunction();
        sf.setCode(code);
        sf.setName(code);
        sf.setTag("TAG-" + code);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_MAIN_FUNCTION, mainFunctionId);
        return hierarchyService.saveSubFunction(sf).getId();
    }

    private AssetEntry saveAsset(String code, Long classId, Long subFunctionId) {
        long now = System.currentTimeMillis();
        AssetEntry ae = new AssetEntry();
        ae.setAssetCode(code);
        ae.setAssetName(code);
        ae.setClassId(classId);
        ae.setSubFunctionId(subFunctionId);
        ae.setCreatedAt(now);
        ae.setUpdatedAt(now);
        return assetEntryRepository.save(ae);
    }

    private record Fixture(
            OperationalUnit unit,
            Location location,
            MainFunction mainFunction,
            AssetClass pumpClass,
            AssetEntry pumpA,
            AssetEntry pumpB,
            AssetEntry valve) {}
}
