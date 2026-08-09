package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.entity.AssetActivationHistory;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetActivationHistoryRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AssetStatusHistoryRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.service.AssetActivationHistoryService;
import com.hnp.backendofflinefirst.service.AssetEntryService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AssetHistoryViewService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asset history page: manual status edits, activation changes, and the merged timeline.
 *
 * <p>The rule this test exists to protect is that <b>activation and status stay independent</b>.
 * They are shown together and are easy to conflate, but switching an asset off must never touch
 * its operational status, and setting a status must never touch whether it is active. Most of
 * what follows pins that separation, because the two look alike in the UI and a future edit
 * that "tidies" them into one journal would silently break log-sheet reversal.
 */
@WithAppUser(roles = "ADMIN")
@Transactional
class AssetHistoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired AssetEntryService assetEntryService;
    @Autowired AssetActivationHistoryService activationHistoryService;
    @Autowired AssetHistoryViewService historyViewService;
    @Autowired com.hnp.backendofflinefirst.service.AssetStatusRequestService requestService;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired AssetActivationHistoryRepository activationHistoryRepository;
    @Autowired AssetStatusHistoryRepository statusHistoryRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired AssetHierarchyService hierarchyService;

    private Long classId;
    private Long subFunctionId;
    private long nano;

    @BeforeEach
    void setUp() {
        seedHierarchy();
    }

    // -----------------------------------------------------------------------
    // Status changes now arrive through an approved request, never the asset form
    // -----------------------------------------------------------------------

    @Test
    void theAssetFormNoLongerChangesTheStatusAtAll() {
        AssetEntry asset = createAsset(true, "IN_SERVICE");
        int before = statusRows(asset).size();

        update(asset, true, "OUT_OF_SERVICE");

        // The field is read-only on the form and ignored by the service, so even a
        // hand-crafted POST cannot slip past the approval the workflow exists to require.
        assertThat(reload(asset).getStatus()).isEqualTo("IN_SERVICE");
        assertThat(statusRows(asset)).hasSize(before);
    }

    @Test
    void anApprovedManualRequestChangesTheStatusAndIsRecordedAsManual() {
        AssetEntry asset = createAsset(true, null);

        approveManualChange(asset, "IN_SERVICE");

        assertThat(reload(asset).getStatus()).isEqualTo("IN_SERVICE");
        var rows = statusRows(asset);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSource()).isEqualTo(AssetStatusSource.MANUAL);
        assertThat(rows.get(0).getOldStatus()).isNull();
        assertThat(rows.get(0).getNewStatus()).isEqualTo("IN_SERVICE");
        // A manual request has no sheet behind it — the history must not imply one.
        assertThat(rows.get(0).getLogSheetId()).isNull();
        assertThat(rows.get(0).getActorUserId()).isNotNull();
        // And it says which decision produced it.
        assertThat(rows.get(0).getRequestId()).isNotNull();
    }

    @Test
    void recordsBothTheOldAndTheNewValueOnAnApprovedChange() {
        AssetEntry asset = createAsset(true, "IN_SERVICE");

        approveManualChange(asset, "OUT_OF_SERVICE");

        var rows = statusRows(asset);
        assertThat(rows.get(0).getOldStatus()).isEqualTo("IN_SERVICE");
        assertThat(rows.get(0).getNewStatus()).isEqualTo("OUT_OF_SERVICE");
    }

    // -----------------------------------------------------------------------
    // Activation — and its independence from status
    // -----------------------------------------------------------------------

    @Test
    void registeringAnAssetWritesTheBaselineActivationRow() {
        AssetEntry asset = createAsset(true, null);

        List<AssetActivationHistory> rows = activationHistoryService.forAsset(asset.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChangeType().name()).isEqualTo("CREATED");
        // Null, not false: the record began here, it was not switched off before.
        assertThat(rows.get(0).getWasActive()).isNull();
        assertThat(rows.get(0).isActive()).isTrue();
    }

    @Test
    void deactivatingAndReactivatingAreBothRecordedWithWhoAndWhen() {
        AssetEntry asset = createAsset(true, null);

        update(asset, false, null);
        update(reload(asset), true, null);

        List<AssetActivationHistory> rows = activationHistoryService.forAsset(asset.getId());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getChangeType().name()).isEqualTo("ACTIVATED");
        assertThat(rows.get(0).getWasActive()).isFalse();
        assertThat(rows.get(0).isActive()).isTrue();
        assertThat(rows.get(0).getActorUserId()).isNotNull();
        assertThat(rows.get(0).getChangedAt()).isPositive();
        assertThat(rows.get(1).getChangeType().name()).isEqualTo("DEACTIVATED");
        assertThat(rows.get(2).getChangeType().name()).isEqualTo("CREATED");
    }

    @Test
    void anEditThatDoesNotTouchTheActiveFlagAddsNoActivationRow() {
        AssetEntry asset = createAsset(true, null);

        update(asset, true, null);

        assertThat(activationHistoryService.forAsset(asset.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.getChangeType().name()).isEqualTo("CREATED"));
    }

    @Test
    void switchingAnAssetOffLeavesItsOperationalStatusUntouched() {
        AssetEntry asset = createAsset(true, "IN_SERVICE");
        int afterCreate = statusRows(asset).size();

        update(asset, false, "IN_SERVICE");

        // The whole point of keeping the two apart: a decommissioned record still remembers the
        // last state its equipment was observed in, and deactivating writes no status row.
        assertThat(reload(asset).getStatus()).isEqualTo("IN_SERVICE");
        assertThat(reload(asset).isActive()).isFalse();
        assertThat(statusRows(asset)).hasSize(afterCreate);
    }

    @Test
    void changingTheStatusLeavesTheActiveFlagUntouched() {
        AssetEntry asset = createAsset(true, "IN_SERVICE");

        approveManualChange(asset, "OUT_OF_SERVICE");

        assertThat(reload(asset).isActive()).isTrue();
        assertThat(activationHistoryService.forAsset(asset.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.getChangeType().name()).isEqualTo("CREATED"));
    }

    @Test
    void theTwoJournalsNeverShareRows() {
        AssetEntry asset = createAsset(true, null);
        update(asset, false, null);
        approveManualChange(reload(asset), "OUT_OF_SERVICE");

        // Two independent journals — an activation row must never appear among the status rows,
        // and a status change must never appear among the activation rows.
        assertThat(activationHistoryRepository.findByAssetIdOrderByChangedAtDescIdDesc(asset.getId()))
                .hasSize(2);
        assertThat(statusHistoryRepository.findByAssetIdOrderByChangedAtDescIdDesc(
                asset.getId(), org.springframework.data.domain.Pageable.ofSize(10)).getContent())
                .hasSize(1);
    }

    // -----------------------------------------------------------------------
    // The merged timeline
    // -----------------------------------------------------------------------

    @Test
    void theTimelineMergesBothKindsNewestFirst() {
        AssetEntry asset = createAsset(true, null);
        approveManualChange(asset, "IN_SERVICE");
        update(reload(asset), false, null);

        var events = historyViewService.timeline(asset.getId(), 100);

        assertThat(events).hasSize(3);
        assertThat(events).extracting(e -> e.kind().name())
                .containsExactly("ACTIVATION", "STATUS", "ACTIVATION");
        assertThat(events.get(0).changeType()).isEqualTo("DEACTIVATED");
        assertThat(events.get(1).newValue()).isEqualTo("IN_SERVICE");
        assertThat(events.get(2).changeType()).isEqualTo("CREATED");
        // Newest first, and never out of order.
        assertThat(events.get(0).changedAt()).isGreaterThanOrEqualTo(events.get(2).changedAt());
    }

    @Test
    void theTimelineNamesTheActorAndDescribesActivationInPersian() {
        AssetEntry asset = createAsset(true, null);
        update(asset, false, null);

        var events = historyViewService.timeline(asset.getId(), 100);

        var deactivation = events.get(0);
        assertThat(deactivation.oldValue()).isEqualTo("فعال");
        assertThat(deactivation.newValue()).isEqualTo("غیرفعال");
        assertThat(deactivation.actorName()).isNotBlank();
        // Activation is never attributed to a log sheet — there is no sheet behind it.
        assertThat(deactivation.fromLogSheet()).isFalse();
        assertThat(deactivation.logSheetId()).isNull();
    }

    @Test
    void aManualStatusEditIsNotReportedAsComingFromALogSheet() {
        AssetEntry asset = createAsset(true, null);
        approveManualChange(asset, "IN_SERVICE");

        var status = historyViewService.timeline(asset.getId(), 100).stream()
                .filter(e -> e.isStatus()).findFirst().orElseThrow();

        assertThat(status.source()).isEqualTo(AssetStatusSource.MANUAL);
        assertThat(status.fromLogSheet()).isFalse();
        assertThat(status.logSheetTitle()).isNull();
    }

    @Test
    void anAssetWithNoHistoryYieldsAnEmptyTimelineRatherThanFailing() {
        assertThat(historyViewService.timeline(null, 100)).isEmpty();
        assertThat(historyViewService.timeline(-1L, 100)).isEmpty();
    }

    @Test
    void theStatusLimitCapsStatusRowsButNeverDropsActivationRows() {
        AssetEntry asset = createAsset(true, null);
        for (int i = 0; i < 5; i++) {
            approveManualChange(reload(asset), "S" + i);
        }
        update(reload(asset), false, null);

        var events = historyViewService.timeline(asset.getId(), 2);

        // Two status rows honoured the cap; both activation rows survived it, because they are
        // rare by nature and losing one would leave "who switched this off" unanswerable.
        assertThat(events.stream().filter(e -> e.isStatus()).count()).isEqualTo(2);
        assertThat(events.stream().filter(e -> !e.isStatus()).count()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void seedHierarchy() {
        long now = System.currentTimeMillis();
        nano = System.nanoTime();

        Location location = new Location();
        location.setCode("AH-LOC-" + nano);
        location.setName("Asset History Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("AH-SF-" + nano);
        subFunction.setName("Asset History Sub");
        subFunction.setTag("NFC-AH-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunctionId = hierarchyService.saveSubFunction(subFunction).getId();

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Asset History Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        classId = assetClassRepository.save(assetClass).getId();
    }

    /** Creates through the service, so the same journalling a real form submission gets applies. */
    private AssetEntry createAsset(boolean active, String status) {
        AssetEntry form = new AssetEntry();
        form.setAssetCode("AH-A-" + nano + "-" + System.nanoTime());
        form.setAssetName("Pump");
        form.setClassId(classId);
        form.setSubFunctionId(subFunctionId);
        form.setActive(active);
        form.setStatus(status);
        return assetEntryService.create(form);
    }

    /** Edits through the service, mirroring what the asset form posts. */
    private void update(AssetEntry existing, boolean active, String status) {
        AssetEntry form = new AssetEntry();
        form.setAssetCode(existing.getAssetCode());
        form.setAssetName(existing.getAssetName());
        form.setClassId(existing.getClassId());
        form.setSubFunctionId(existing.getSubFunctionId());
        form.setNfcTagId(existing.getNfcTagId());
        form.setNfcSerial(existing.getNfcSerial());
        form.setActive(active);
        form.setStatus(status);
        assetEntryService.update(existing.getId(), form);
    }

    private List<com.hnp.backendofflinefirst.entity.AssetStatusHistory> statusRows(AssetEntry asset) {
        return statusHistoryRepository.findByAssetIdOrderByChangedAtDescIdDesc(
                asset.getId(), org.springframework.data.domain.Pageable.ofSize(50)).getContent();
    }

    /** Files a manual change request and approves it — the only way status moves now. */
    private void approveManualChange(AssetEntry asset, String newStatus) {
        var request = requestService.raiseManual(asset.getId(), newStatus, null);
        requestService.decide(request.getId(),
                com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus.APPROVED, null);
    }

    private AssetEntry reload(AssetEntry asset) {
        return assetEntryRepository.findById(asset.getId()).orElseThrow();
    }
}
