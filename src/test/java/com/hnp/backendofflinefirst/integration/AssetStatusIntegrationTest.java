package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusHistory;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AssetStatusService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An asset's operational status, driven by a {@code status} field on a completed log sheet.
 *
 * <p>The dangerous direction here is not "a status failed to update" — that is visible and
 * fixable. It is <b>a status changed when it should not have</b>: a stale sheet rolling back a
 * newer reading, or a reversal clobbering a value some other round set. Most of what follows
 * pins down that direction, because a wrong asset state is a wrong maintenance decision.
 */
// Admin, because void / reopen / restore are supervisor-or-admin actions and this test is
// about what happens to the asset afterwards, not about who may trigger them.
@WithAppUser(roles = "ADMIN")
@Transactional
class AssetStatusIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetStatusService assetStatusService;
    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetStatusHistoryRepository historyRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;

    private Long assetId;
    private LogSheetTemplate template;

    @BeforeEach
    void setUp() {
        seed("status");
    }

    // -----------------------------------------------------------------------
    // Applying
    // -----------------------------------------------------------------------

    @Test
    void copiesTheStatusReadingOntoTheAsset() {
        LogSheet sheet = completeWith("OUT_OF_SERVICE");

        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");
        List<AssetStatusHistory> history = historyRepository.findByAssetIdAndChangeTypeOrderByChangedAtDesc(
                assetId, AssetStatusChangeType.APPLIED);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getOldStatus()).isNull();
        assertThat(history.get(0).getNewStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(history.get(0).getLogSheetId()).isEqualTo(sheet.getId());
        assertThat(history.get(0).getFieldKey()).isEqualTo("status");
    }

    @Test
    void matchesTheFieldKeyWhateverItsCase() {
        // The requirement is explicit that "status", "Status" and "STATUS" all drive this.
        for (String key : List.of("Status", "STATUS", "sTaTuS")) {
            seed(key);
            completeWith("IN_SERVICE");
            assertThat(currentStatus()).as("key %s", key).isEqualTo("IN_SERVICE");
        }
    }

    @Test
    void recordsTheOldValueSoAReversalKnowsWhereToGoBack() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");

        AssetStatusHistory applied = historyRepository
                .findByAssetIdAndChangeTypeOrderByChangedAtDesc(assetId, AssetStatusChangeType.APPLIED)
                .get(0);
        assertThat(applied.getOldStatus()).isEqualTo("IN_SERVICE");
        assertThat(applied.getNewStatus()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void ignoresAnAssetWhoseStatusReadingIsBlank() {
        setStatusDirectly("IN_SERVICE");
        completeWith("   ");

        // Blank means "not recorded", which is not the same as "has no state". Overwriting a
        // known status with nothing would lose information the sheet never intended to change.
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
        assertThat(historyRepository.findByAssetIdAndChangeTypeOrderByChangedAtDesc(
                assetId, AssetStatusChangeType.APPLIED)).isEmpty();
    }

    @Test
    void writesNoHistoryWhenTheStatusIsAlreadyWhatTheSheetSays() {
        setStatusDirectly("IN_SERVICE");
        completeWith("IN_SERVICE");

        // A row saying "changed from IN_SERVICE to IN_SERVICE" is noise that makes the real
        // changes harder to find.
        assertThat(historyRepository.findByAssetIdAndChangeTypeOrderByChangedAtDesc(
                assetId, AssetStatusChangeType.APPLIED)).isEmpty();
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void doesNothingForAClassWithNoStatusField() {
        seed("temperature");
        completeWith("OUT_OF_SERVICE");

        assertThat(currentStatus()).isNull();
        assertThat(historyRepository.findByAssetIdAndChangeTypeOrderByChangedAtDesc(
                assetId, AssetStatusChangeType.APPLIED)).isEmpty();
    }

    @Test
    void joinsAMultiselectStatusInsteadOfStoringTheJavaListRendering() {
        // Not hypothetical: the Electric Motor class in the live database declares its Status
        // field as a multiselect, so the reading arrives as a collection. String.valueOf on it
        // would put "[on, IDLE]" — brackets and all — into the asset's status column.
        completeWith(List.of("on", "IDLE"));

        assertThat(currentStatus()).isEqualTo("on, IDLE");
    }

    @Test
    void ignoresAMultiselectStatusWithNothingSelected() {
        setStatusDirectly("IN_SERVICE");
        completeWith(List.of());

        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void truncatesRatherThanFailingTheWholeCompletion() {
        // Losing an operator's whole round over one over-long value would be the worse outcome.
        completeWith("A".repeat(60));

        assertThat(currentStatus()).hasSize(30);
    }

    // -----------------------------------------------------------------------
    // Reverting — the part that must not damage live data
    // -----------------------------------------------------------------------

    @Test
    void voidingTheSheetPutsTheAssetBack() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");

        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);

        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
        List<AssetStatusHistory> reverts = historyRepository
                .findByAssetIdAndChangeTypeOrderByChangedAtDesc(assetId, AssetStatusChangeType.REVERTED);
        assertThat(reverts).hasSize(1);
        assertThat(reverts.get(0).getOldStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(reverts.get(0).getNewStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void reopeningTheSheetPutsTheAssetBack() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");

        assignmentService.reopenSubmittedWithExtend(
                sheet.getId(), null, System.currentTimeMillis() + 7_200_000L, ActionSource.WEB);

        // The sheet is editable again, so it is no longer a completed record.
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void restoringAVoidedSheetReappliesTheStatus() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");

        assignmentService.restoreVoided(sheet.getId(), null, ActionSource.WEB);

        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void neverRollsBackOverAValueSomethingElseSetLater() {
        // The scenario that would corrupt data: an old sheet is voided long after a newer round
        // moved the asset on. Restoring the old value here would resurrect a stale state and
        // silently contradict the newer reading.
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        setStatusDirectly("UNDER_REPAIR");

        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);

        assertThat(currentStatus()).isEqualTo("UNDER_REPAIR");
        assertThat(historyRepository.findByAssetIdAndChangeTypeOrderByChangedAtDesc(
                assetId, AssetStatusChangeType.REVERTED)).isEmpty();
    }

    @Test
    void aSecondVoidHasNothingLeftToUndo() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);

        // Idempotence matters: the reverted rows are marked, so a repeat pass finds no work
        // rather than "restoring" IN_SERVICE over whatever is current by then.
        int second = assetStatusService.revertForSheet(sheet.getId(), null);

        assertThat(second).isZero();
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void survivesAFullCycleWithTheHistoryTellingTheWholeStory() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);
        assignmentService.restoreVoided(sheet.getId(), null, ActionSource.WEB);

        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");

        List<AssetStatusHistory> all = historyRepository
                .findByAssetIdOrderByChangedAtDescIdDesc(assetId,
                        org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent();
        // apply → revert → apply, each with its own before/after, so an auditor can reconstruct
        // exactly what the asset read at any point and which sheet was responsible.
        assertThat(all).hasSize(3);
        assertThat(all).allSatisfy(h -> assertThat(h.getLogSheetId()).isEqualTo(sheet.getId()));
    }

    @Test
    void handlesEveryAssetOnAMultiAssetSheetInOnePass() {
        Long secondAsset = addAssetToTemplateScope();
        LogSheet sheet = generateSheet();
        for (LogSheetEntry entry : logSheetEntryRepository.findByLogSheetId(sheet.getId())) {
            entry.setFormData(Map.of("status", "OUT_OF_SERVICE"));
            logSheetEntryRepository.save(entry);
        }

        int changed = assetStatusService.applyFromCompletedSheet(sheet, null);

        assertThat(changed).isEqualTo(2);
        assertThat(assetEntryRepository.findById(assetId).orElseThrow().getStatus())
                .isEqualTo("OUT_OF_SERVICE");
        assertThat(assetEntryRepository.findById(secondAsset).orElseThrow().getStatus())
                .isEqualTo("OUT_OF_SERVICE");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String currentStatus() {
        return assetEntryRepository.findById(assetId).orElseThrow().getStatus();
    }

    private void setStatusDirectly(String status) {
        AssetEntry asset = assetEntryRepository.findById(assetId).orElseThrow();
        asset.setStatus(status);
        assetEntryRepository.save(asset);
    }

    /** Generates a sheet, writes the reading, marks it submitted, and applies the status. */
    private LogSheet completeWith(Object statusValue) {
        LogSheet sheet = generateSheet();
        for (LogSheetEntry entry : logSheetEntryRepository.findByLogSheetId(sheet.getId())) {
            entry.setFormData(Map.of(fieldKeyOnClass(), statusValue));
            logSheetEntryRepository.save(entry);
        }
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedAt(System.currentTimeMillis());
        sheet.setSubmittedAt(System.currentTimeMillis());
        logSheetRepository.save(sheet);

        assetStatusService.applyFromCompletedSheet(sheet, null);
        return sheet;
    }

    private String fieldKeyOnClass() {
        AssetEntry asset = assetEntryRepository.findById(assetId).orElseThrow();
        return fieldDefinitionRepository.findByClassId(asset.getClassId()).get(0).getKey();
    }

    private LogSheet generateSheet() {
        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        return logSheetRepository.save(sheet);
    }

    private Long addAssetToTemplateScope() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();
        AssetEntry first = assetEntryRepository.findById(assetId).orElseThrow();

        SubFunction sf = new SubFunction();
        sf.setCode("AS-SF2-" + nano);
        sf.setName("Second Sub");
        sf.setTag("NFC-AS2-" + nano);
        sf.setCreatedAt(now);
        sf.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(sf, AssetHierarchyService.SCOPE_LOCATION,
                template.getScopeId());
        sf = hierarchyService.saveSubFunction(sf);

        AssetEntry second = new AssetEntry();
        second.setAssetCode("AS-A2-" + nano);
        second.setAssetName("Pump 2");
        second.setClassId(first.getClassId());
        second.setSubFunctionId(sf.getId());
        second.setCreatedAt(now);
        second.setUpdatedAt(now);
        return assetEntryRepository.save(second).getId();
    }

    /** Fresh hierarchy with a class whose only field uses the given key. */
    private void seed(String statusFieldKey) {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("AS-BU-" + nano);
        unit.setName("Asset Status Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("AS-LOC-" + nano);
        location.setName("Asset Status Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("AS-SF-" + nano);
        subFunction.setName("Asset Status Sub");
        subFunction.setTag("NFC-AS-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Asset Status Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);

        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey(statusFieldKey);
        def.setLabel("وضعیت");
        def.setDataType("text");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.save(def);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("AS-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        assetId = assetEntryRepository.save(asset).getId();

        LogSheetTemplate t = new LogSheetTemplate();
        t.setName("Asset Status Template " + nano);
        t.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        t.setScopeId(location.getId());
        t.setClassId(assetClass.getId());
        t.setOperationalUnitId(unit.getId());
        t.setGenerationMode(GenerationMode.MANUAL);
        t.setScheduleActive(false);
        t.setActive(true);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        template = templateRepository.save(t);
    }
}
