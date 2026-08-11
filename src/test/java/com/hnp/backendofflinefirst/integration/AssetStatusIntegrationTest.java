package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusChangeRequest;
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
import com.hnp.backendofflinefirst.repository.AssetStatusChangeRequestRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AssetStatusRequestService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asset status changes as a request that a supervisor decides.
 *
 * <p>A reading taken in the field is a claim, not a decision. Completing a log sheet whose
 * {@code status} field differs from the asset's current status <b>raises a request</b>; only an
 * approval moves the column. That is the whole point of the workflow and most of what follows
 * pins it, because the failure that matters is an asset quietly retagged by a round nobody has
 * reviewed.
 *
 * <p>The other rule under test is the <b>only-latest</b> guard. Undoing an approval restores the
 * exact value that approval replaced, which is only sound for an asset's newest request; undoing
 * one in the middle would roll the asset back over decisions taken since.
 */
@WithAppUser(roles = "ADMIN")
@Transactional
class AssetStatusIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetStatusRequestService requestService;
    @Autowired AssetStatusChangeRequestRepository requestRepository;
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
    // Raising a request — completion proposes, it does not decide
    // -----------------------------------------------------------------------

    @Test
    void completingASheetRaisesARequestAndLeavesTheAssetAlone() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");

        // The asset must not move until somebody decides.
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");

        AssetStatusChangeRequest request = latestRequest();
        assertThat(request.getStatus()).isEqualTo(AssetStatusRequestStatus.PENDING);
        assertThat(request.getRequestedStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(request.getPreviousStatus()).isEqualTo("IN_SERVICE");
        assertThat(request.getSource()).isEqualTo(AssetStatusSource.LOG_SHEET);
        assertThat(request.getLogSheetId()).isEqualTo(sheet.getId());
        assertThat(request.getLogSheetEntryId()).isNotNull();
        assertThat(request.getFieldKey()).isEqualTo("status");
        assertThat(request.getRequestedAt()).isPositive();
    }

    @Test
    void aReadingThatMatchesTheCurrentStatusRaisesNothing() {
        setStatusDirectly("IN_SERVICE");
        completeWith("IN_SERVICE");

        // There is no change to decide on; a request here would be noise in the queue.
        assertThat(requestRepository.findByAssetIdOrderByIdDesc(assetId)).isEmpty();
    }

    @Test
    void aBlankReadingRaisesNothing() {
        setStatusDirectly("IN_SERVICE");
        completeWith("   ");

        // Blank means "not recorded", not "no status" — proposing to blank the asset because a
        // field was skipped would be worse than useless.
        assertThat(requestRepository.findByAssetIdOrderByIdDesc(assetId)).isEmpty();
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void theStatusFieldKeyIsMatchedCaseInsensitively() {
        for (String key : List.of("Status", "STATUS", "sTaTuS")) {
            seed(key);
            completeWith("OUT_OF_SERVICE");
            assertThat(latestRequest().getRequestedStatus()).as("key %s", key).isEqualTo("OUT_OF_SERVICE");
        }
    }

    @Test
    void aMultiselectReadingIsJoinedRatherThanStoredAsAJavaList() {
        completeWith(List.of("on", "IDLE"));

        // Live data has a multiselect Status field; String.valueOf on the list would propose
        // the Java rendering, brackets and all.
        assertThat(latestRequest().getRequestedStatus()).isEqualTo("on, IDLE");
    }

    @Test
    void anOverlongReadingIsTruncatedRatherThanFailingTheCompletion() {
        completeWith("A".repeat(60));

        assertThat(latestRequest().getRequestedStatus()).hasSize(30);
    }

    @Test
    void aClassWithNoStatusFieldRaisesNothing() {
        seed("temperature");
        completeWith("OUT_OF_SERVICE");

        assertThat(requestRepository.findByAssetIdOrderByIdDesc(assetId)).isEmpty();
    }

    @Test
    void completingTheSameSheetTwiceDoesNotRaiseADuplicate() {
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        int before = requestRepository.findByAssetIdOrderByIdDesc(assetId).size();

        requestService.raiseFromCompletedSheet(sheet, null);

        // A sheet can be completed again after a reopen or a restore; asking the supervisor the
        // same question twice would bury the queue in duplicates.
        assertThat(requestRepository.findByAssetIdOrderByIdDesc(assetId)).hasSize(before);
    }

    @Test
    void everyAssetOnAMultiAssetSheetGetsItsOwnRequest() {
        Long secondAsset = addAssetToTemplateScope();
        LogSheet sheet = generateSheet();
        for (LogSheetEntry entry : logSheetEntryRepository.findByLogSheetId(sheet.getId())) {
            entry.setFormData(Map.of("status", "OUT_OF_SERVICE"));
            logSheetEntryRepository.save(entry);
        }
        markSubmitted(sheet);

        int raised = requestService.raiseFromCompletedSheet(sheet, null);

        assertThat(raised).isEqualTo(2);
        assertThat(requestRepository.findByAssetIdOrderByIdDesc(assetId)).hasSize(1);
        assertThat(requestRepository.findByAssetIdOrderByIdDesc(secondAsset)).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Deciding
    // -----------------------------------------------------------------------

    @Test
    void approvingAppliesTheStatusAndRecordsWhichRequestDidIt() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        AssetStatusChangeRequest request = latestRequest();

        requestService.decide(request.getId(), AssetStatusRequestStatus.APPROVED, "تأیید شد");

        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");
        AssetStatusChangeRequest after = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AssetStatusRequestStatus.APPROVED);
        assertThat(after.getDecidedByUserId()).isNotNull();
        assertThat(after.getDecidedAt()).isPositive();
        // The exact value replaced, stored so an undo has an anchor rather than a guess.
        assertThat(after.getAppliedOldStatus()).isEqualTo("IN_SERVICE");

        AssetStatusHistory row = history().get(0);
        assertThat(row.getChangeType()).isEqualTo(AssetStatusChangeType.APPLIED);
        assertThat(row.getOldStatus()).isEqualTo("IN_SERVICE");
        assertThat(row.getNewStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(row.getRequestId()).isEqualTo(request.getId());
    }

    @Test
    void rejectingAPendingRequestLeavesTheAssetUntouched() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");

        requestService.decide(latestRequest().getId(), AssetStatusRequestStatus.REJECTED, "قبول نشد");

        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
        assertThat(history()).isEmpty();
        assertThat(latestRequest().getStatus()).isEqualTo(AssetStatusRequestStatus.REJECTED);
    }

    @Test
    void undoingAnApprovalBackToPendingRestoresExactlyWhatItReplaced() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);

        requestService.decide(id, AssetStatusRequestStatus.PENDING, "اشتباه تأیید شد");

        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
        AssetStatusChangeRequest after = requestRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AssetStatusRequestStatus.PENDING);
        // Back to undecided: no decider, no decision time.
        assertThat(after.getDecidedByUserId()).isNull();
        assertThat(after.getDecidedAt()).isNull();
        assertThat(history().get(0).getChangeType()).isEqualTo(AssetStatusChangeType.REVERTED);
        assertThat(history().get(0).getRequestId()).isEqualTo(id);
    }

    @Test
    void rejectingAnAlreadyApprovedRequestAlsoPutsTheStatusBack() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);

        requestService.decide(id, AssetStatusRequestStatus.REJECTED, "رد شد");

        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
        assertThat(requestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AssetStatusRequestStatus.REJECTED);
    }

    @Test
    void reopeningARejectedRequestDoesNotTouchTheAsset() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.REJECTED, null);

        requestService.decide(id, AssetStatusRequestStatus.PENDING, null);

        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
        assertThat(history()).isEmpty();
    }

    @Test
    void decidingTheSameWayTwiceIsANoOp() {
        completeWith("OUT_OF_SERVICE");
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);

        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);

        // One approval, one history row — a double-submitted form must not double-journal.
        assertThat(history()).hasSize(1);
        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");
    }

    // -----------------------------------------------------------------------
    // The only-latest guard — the rule that stops history being rewritten
    // -----------------------------------------------------------------------

    @Test
    void anOlderApprovedRequestCannotBeUndone() {
        setStatusDirectly("IN_SERVICE");
        Long first = raiseAndApprove("OUT_OF_SERVICE");
        Long second = raiseAndApprove("UNDER_REPAIR");

        assertThatThrownBy(() -> requestService.decide(first, AssetStatusRequestStatus.PENDING, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("آخرین درخواست");

        // Undoing the middle request would restore IN_SERVICE over UNDER_REPAIR, silently
        // discarding a decision taken since.
        assertThat(currentStatus()).isEqualTo("UNDER_REPAIR");
        assertThat(requestRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(AssetStatusRequestStatus.APPROVED);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void theNewestRequestCanStillBeUndoneAndTheAssetWalksBackOneStep() {
        setStatusDirectly("IN_SERVICE");
        raiseAndApprove("OUT_OF_SERVICE");
        Long second = raiseAndApprove("UNDER_REPAIR");

        requestService.decide(second, AssetStatusRequestStatus.REJECTED, "اشتباه بود");

        // Back to what the newest approval replaced — not all the way to IN_SERVICE.
        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void anOlderPendingRequestCannotBeApproved() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        Long older = latestRequest().getId();
        completeWith("UNDER_REPAIR");

        assertThatThrownBy(() -> requestService.decide(older, AssetStatusRequestStatus.APPROVED, null))
                .isInstanceOf(IllegalStateException.class);

        // Approving a stale proposal would set the asset to a reading a newer round has
        // already superseded.
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void anOlderPendingRequestCanStillBeRejected() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        Long older = latestRequest().getId();
        completeWith("UNDER_REPAIR");

        // Rejecting changes nothing about the asset, so there is no reason to block it —
        // otherwise stale proposals would clog the queue for ever.
        requestService.decide(older, AssetStatusRequestStatus.REJECTED, "منقضی");

        assertThat(requestRepository.findById(older).orElseThrow().getStatus())
                .isEqualTo(AssetStatusRequestStatus.REJECTED);
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    // -----------------------------------------------------------------------
    // The log sheet lifecycle no longer moves asset status
    // -----------------------------------------------------------------------

    @Test
    void voidingTheSheetLeavesTheAssetAndTheRequestAlone() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);

        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);

        // Two mechanisms for one column would let the sheet lifecycle break the only-latest
        // rule from behind. Undoing is the supervisor's explicit act, not a side effect.
        assertThat(currentStatus()).isEqualTo("OUT_OF_SERVICE");
        assertThat(requestRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(AssetStatusRequestStatus.APPROVED);
    }

    @Test
    void voidingLeavesAPendingRequestPendingSoTheSupervisorStillDecides() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");

        assignmentService.voidSubmitted(sheet.getId(), null, ActionSource.WEB);

        // The queue shows the request with its sheet marked voided; the decision stays human.
        assertThat(latestRequest().getStatus()).isEqualTo(AssetStatusRequestStatus.PENDING);
        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.VOIDED);
    }

    // -----------------------------------------------------------------------
    // When the change is dated
    // -----------------------------------------------------------------------

    @Test
    void approvalDatesTheChangeToWhenTheReadingWasTakenNotWhenItWasSignedOff() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");

        // Backdate the entry to simulate a round walked at 08:15 and reviewed hours later.
        long observedAt = System.currentTimeMillis() - 8 * 60 * 60 * 1000L;
        for (LogSheetEntry entry : logSheetEntryRepository.findByLogSheetId(sheet.getId())) {
            entry.setUpdatedAt(observedAt);
            logSheetEntryRepository.save(entry);
        }
        requestRepository.deleteAll();
        requestService.raiseFromCompletedSheet(sheet, null);

        long approvedAt = System.currentTimeMillis();
        requestService.decide(latestRequest().getId(), AssetStatusRequestStatus.APPROVED, null);

        // The asset became OUT_OF_SERVICE when the operator saw it, not when the supervisor
        // got round to the queue — otherwise every asset's history bunches up at review times.
        AssetStatusHistory row = history().get(0);
        assertThat(row.getChangedAt()).isEqualTo(observedAt);
        assertThat(row.getChangedAt()).isLessThan(approvedAt);
    }

    @Test
    void undoingIsDatedNowBecauseItIsADecisionNotAnObservation() {
        setStatusDirectly("IN_SERVICE");
        LogSheet sheet = completeWith("OUT_OF_SERVICE");
        long observedAt = System.currentTimeMillis() - 8 * 60 * 60 * 1000L;
        for (LogSheetEntry entry : logSheetEntryRepository.findByLogSheetId(sheet.getId())) {
            entry.setUpdatedAt(observedAt);
            logSheetEntryRepository.save(entry);
        }
        requestRepository.deleteAll();
        requestService.raiseFromCompletedSheet(sheet, null);
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);

        requestService.decide(id, AssetStatusRequestStatus.REJECTED, null);

        // Back-dating the correction too would hide when it actually happened.
        assertThat(history().get(0).getChangeType()).isEqualTo(AssetStatusChangeType.REVERTED);
        assertThat(history().get(0).getChangedAt()).isGreaterThan(observedAt);
    }

    @Test
    void aRequestWithNoRecordedReadingTimeFallsBackToItsFilingTime() {
        setStatusDirectly("IN_SERVICE");
        completeWith("OUT_OF_SERVICE");
        AssetStatusChangeRequest request = latestRequest();
        // Rows raised before the column existed have nothing to recover.
        request.setReadingRecordedAt(null);
        requestRepository.save(request);

        requestService.decide(request.getId(), AssetStatusRequestStatus.APPROVED, null);

        assertThat(history().get(0).getChangedAt()).isEqualTo(request.getRequestedAt());
    }

    // -----------------------------------------------------------------------
    // Filing by hand
    // -----------------------------------------------------------------------

    @Test
    void aSupervisorCanFileARequestWithNoLogSheetBehindIt() {
        setStatusDirectly("IN_SERVICE");

        AssetStatusChangeRequest request =
                requestService.raiseManual(assetId, "UNDER_REPAIR", "بازرسی موردی");

        assertThat(request.getSource()).isEqualTo(AssetStatusSource.MANUAL);
        assertThat(request.getLogSheetId()).isNull();
        assertThat(request.getReason()).isEqualTo("بازرسی موردی");
        assertThat(request.getStatus()).isEqualTo(AssetStatusRequestStatus.PENDING);
        // Filing is still only a proposal.
        assertThat(currentStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void aManualRequestIsRefusedWhenTheClassHasNoStatusFieldAtAll() {
        seed("temperature");

        // Nothing would ever set such a status back through a log sheet, and approving would
        // invent a value the operators' own form cannot express.
        assertThatThrownBy(() -> requestService.raiseManual(assetId, "OUT_OF_SERVICE", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("فیلد وضعیت");
    }

    @Test
    void theStatusOptionsOfferedAreTheOnesTheClassDeclares() {
        var options = requestService.statusOptionsForAsset(assetId);

        assertThat(options.supported()).isTrue();
        assertThat(options.fieldKey()).isEqualTo("status");
        // This fixture's field is free text, so there is no vocabulary to choose from.
        assertThat(options.options()).isEmpty();
    }

    @Test
    void anAssetWhoseClassHasNoStatusFieldReportsUnsupported() {
        seed("temperature");

        assertThat(requestService.statusOptionsForAsset(assetId).supported()).isFalse();
    }

    @Test
    void aManualRequestForTheStatusTheAssetAlreadyHasIsRefused() {
        setStatusDirectly("IN_SERVICE");

        assertThatThrownBy(() -> requestService.raiseManual(assetId, "IN_SERVICE", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // Helper wrappers over the shared fixture
    // -----------------------------------------------------------------------

    private AssetStatusChangeRequest latestRequest() {
        return requestRepository.findFirstByAssetIdOrderByIdDesc(assetId).orElseThrow();
    }

    private List<AssetStatusHistory> history() {
        return historyRepository.findByAssetIdOrderByChangedAtDescIdDesc(
                assetId, org.springframework.data.domain.PageRequest.of(0, 50)).getContent();
    }

    /** Completes a sheet with the reading and approves the request it raises. */
    private Long raiseAndApprove(String reading) {
        completeWith(reading);
        Long id = latestRequest().getId();
        requestService.decide(id, AssetStatusRequestStatus.APPROVED, null);
        return id;
    }

    private void markSubmitted(LogSheet sheet) {
        long now = System.currentTimeMillis();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedAt(now);
        sheet.setSubmittedAt(now);
        logSheetRepository.save(sheet);
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

    /** Generates a sheet, writes the reading, marks it submitted, and raises the request. */
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

        requestService.raiseFromCompletedSheet(sheet, null);
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
