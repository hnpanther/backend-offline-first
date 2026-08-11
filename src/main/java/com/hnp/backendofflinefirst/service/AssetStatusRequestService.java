package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetStatusChangeType;
import com.hnp.backendofflinefirst.domain.AssetStatusRequestStatus;
import com.hnp.backendofflinefirst.domain.AssetStatusSource;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.AssetStatusChangeRequest;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetStatusChangeRequestRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The approval workflow for changing an asset's operational status.
 *
 * <h2>Why a request instead of a direct write</h2>
 * A reading taken in the field is a claim, not a decision. An operator recording a pump as out
 * of service should not silently retag the asset for everyone who looks at it afterwards — a
 * supervisor decides. So a completed log sheet whose status reading <em>differs</em> from the
 * asset's current status raises a PENDING request, and only an approval moves the column.
 *
 * <h2>This is the only mechanism</h2>
 * Voiding or reopening the source sheet deliberately does <b>not</b> touch the asset. With two
 * mechanisms the only-latest rule below could be violated by the sheet lifecycle itself,
 * silently and behind the supervisor's back. A request raised by a sheet that is later voided
 * simply stays in the queue showing that its sheet was voided, and the supervisor decides with
 * that in front of them.
 *
 * <h2>The only-latest rule</h2>
 * Undoing an approval restores {@code appliedOldStatus} — the exact value that approval
 * replaced. That is only sound for an asset's <em>newest</em> request: undoing one in the middle
 * would restore a status that later requests have already superseded, quietly rolling the asset
 * back in time. {@link #decide} therefore refuses any decision on a stale request that would
 * move the column, and says so.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetStatusRequestService {

    private final AssetStatusChangeRequestRepository requestRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetStatusService assetStatusService;
    private final AssetAccessService assetAccessService;
    private final FieldDefinitionRepository fieldDefinitionRepository;

    // ── Raising ──────────────────────────────────────────────────────────────

    /**
     * Raises a request for every asset on a completed sheet whose status reading differs from
     * what the asset currently holds.
     *
     * <p>Called after the entries are persisted, from every completion path. Safe to call for a
     * sheet with no status field — it does nothing. Readings equal to the current status raise
     * nothing: there is no change to decide on.
     *
     * @return how many requests were raised
     */
    @Transactional
    public int raiseFromCompletedSheet(LogSheet sheet, Long actorUserId) {
        List<AssetStatusService.SheetStatusReading> readings = assetStatusService.readingsFromSheet(sheet);
        if (readings.isEmpty()) {
            return 0;
        }

        Map<Long, AssetEntry> assets = assetEntryRepository
                .findAllById(readings.stream().map(AssetStatusService.SheetStatusReading::assetId).toList())
                .stream().collect(Collectors.toMap(AssetEntry::getId, a -> a, (a, b) -> a));

        long now = System.currentTimeMillis();
        int raised = 0;
        for (AssetStatusService.SheetStatusReading reading : readings) {
            AssetEntry asset = assets.get(reading.assetId());
            if (asset == null) {
                continue;
            }
            if (Objects.equals(asset.getStatus(), reading.value())) {
                // Already in that state — nothing to decide.
                continue;
            }
            // A sheet can be completed more than once (reopened for a correction, or restored
            // after a void). Raising a second identical request each time would bury the
            // supervisor in duplicates of the same question.
            if (requestRepository.existsOpenOrApprovedForSheetAndAsset(sheet.getId(), asset.getId())) {
                continue;
            }

            AssetStatusChangeRequest request = new AssetStatusChangeRequest();
            request.setAssetId(asset.getId());
            request.setRequestedStatus(reading.value());
            request.setPreviousStatus(asset.getStatus());
            request.setStatus(AssetStatusRequestStatus.PENDING);
            request.setSource(AssetStatusSource.LOG_SHEET);
            request.setLogSheetId(sheet.getId());
            request.setLogSheetEntryId(reading.entryId());
            request.setFieldKey(reading.fieldKey());
            // Carried so approval can date the change to when the reading was taken.
            request.setReadingRecordedAt(reading.recordedAt());
            request.setRequestedByUserId(actorUserId);
            request.setRequestedAt(now);
            request.setCreatedAt(now);
            request.setUpdatedAt(now);
            requestRepository.save(request);
            raised++;
        }
        if (raised > 0) {
            log.info("Log sheet {} raised {} asset status change request(s)", sheet.getId(), raised);
        }
        return raised;
    }

    /**
     * A request filed by hand, with no log sheet behind it.
     *
     * <p>Supervisors and admins only — the same people who decide them. An operator noticing
     * something raises it through their round, not through this.
     */
    @Transactional
    public AssetStatusChangeRequest raiseManual(Long assetId, String requestedStatus, String reason) {
        requireDecider();
        AssetEntry asset = assetAccessService.requireReportable(assetId);

        // An asset whose class declares no status field has no status to change: nothing would
        // ever set it back through a log sheet, and approving would invent a value the form the
        // operators fill in cannot even express. Refused outright rather than quietly allowed.
        StatusFieldOptions statusField = statusOptionsForAsset(asset.getId());
        if (!statusField.supported()) {
            throw new IllegalArgumentException(
                    "کلاس این دارایی فیلد وضعیت (status) ندارد؛ ثبت درخواست تغییر وضعیت ممکن نیست.");
        }

        String desired = AssetStatusService.normaliseStatus(requestedStatus);
        if (desired == null) {
            throw new IllegalArgumentException("وضعیت جدید را انتخاب کنید.");
        }
        // When the field declares choices, only those are acceptable — a typed-in value would
        // never match what a log sheet can later report and would sit outside the vocabulary.
        if (!statusField.options().isEmpty() && !statusField.options().contains(desired)) {
            throw new IllegalArgumentException("وضعیت انتخاب‌شده جزو گزینه‌های مجاز این کلاس نیست.");
        }
        if (Objects.equals(asset.getStatus(), desired)) {
            throw new IllegalArgumentException("وضعیت درخواستی با وضعیت فعلی دارایی یکسان است.");
        }

        long now = System.currentTimeMillis();
        AssetStatusChangeRequest request = new AssetStatusChangeRequest();
        request.setAssetId(asset.getId());
        request.setRequestedStatus(desired);
        request.setPreviousStatus(asset.getStatus());
        request.setStatus(AssetStatusRequestStatus.PENDING);
        request.setSource(AssetStatusSource.MANUAL);
        request.setFieldKey(statusField.fieldKey());
        request.setReason(trimToNull(reason));
        // No log sheet behind it, so the moment the supervisor states it IS the observation.
        request.setReadingRecordedAt(now);
        request.setRequestedByUserId(SecurityUtils.currentUserId());
        request.setRequestedAt(now);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        return requestRepository.save(request);
    }

    /**
     * What a manual request may propose for this asset.
     *
     * <p>Resolved from the class the asset belongs to <em>today</em> — unlike a sheet-raised
     * request, which reads the sheet's frozen snapshot, because a request filed by hand is being
     * made against the current definition.
     *
     * @param fieldKey the status field's declared key, or null when the class has none
     * @param options  the values the field allows; empty when it is a free-text status field
     */
    public record StatusFieldOptions(String fieldKey, List<String> options) {
        /** No status field on the class means there is nothing to request. */
        public boolean supported() {
            return fieldKey != null;
        }
    }

    @Transactional(readOnly = true)
    public StatusFieldOptions statusOptionsForAsset(Long assetId) {
        AssetEntry asset = assetAccessService.requireReportable(assetId);
        if (asset.getClassId() == null) {
            return new StatusFieldOptions(null, List.of());
        }
        for (FieldDefinition def : fieldDefinitionRepository.findByClassId(asset.getClassId())) {
            if (def.isDeleted() || !AssetStatusService.isStatusField(def)) {
                continue;
            }
            return new StatusFieldOptions(def.getKey(), optionsOf(def));
        }
        return new StatusFieldOptions(null, List.of());
    }

    /** The declared choices for a select/multiselect status field; empty for free text. */
    @SuppressWarnings("unchecked")
    private static List<String> optionsOf(FieldDefinition def) {
        Map<String, Object> validation = def.getValidation();
        if (validation == null) {
            return List.of();
        }
        Object raw = validation.get(FieldValidationSupport.KEY_OPTIONS);
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .filter(Objects::nonNull)
                .map(o -> String.valueOf(o).trim())
                .filter(o -> !o.isEmpty())
                .toList();
    }

    // ── Deciding ─────────────────────────────────────────────────────────────

    /**
     * Moves a request to {@code target}, applying or undoing the asset's status as required.
     *
     * <p>The transitions that matter:
     * <ul>
     *   <li><b>PENDING → APPROVED</b> — the asset takes the requested status. The value replaced
     *       is stored on the request, because that is what an undo has to restore.</li>
     *   <li><b>PENDING → REJECTED</b> — nothing happens to the asset.</li>
     *   <li><b>APPROVED → PENDING or REJECTED</b> — the asset goes back to exactly what the
     *       approval replaced, and the timeline records the undo.</li>
     *   <li><b>REJECTED → PENDING</b> — reopens the question; the asset is untouched.</li>
     * </ul>
     *
     * <p>Any transition that would move the column is refused unless this is the asset's newest
     * request. See the class note for why.
     */
    @Transactional
    public AssetStatusChangeRequest decide(Long requestId,
                                           AssetStatusRequestStatus target,
                                           String note) {
        requireDecider();
        AssetStatusChangeRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("درخواست تغییر وضعیت یافت نشد."));
        AssetEntry asset = assetAccessService.requireReportable(request.getAssetId());

        AssetStatusRequestStatus current = request.getStatus();
        if (current == target) {
            return request;
        }

        boolean movesTheAsset = current == AssetStatusRequestStatus.APPROVED
                || target == AssetStatusRequestStatus.APPROVED;
        if (movesTheAsset && !isLatestForAsset(request)) {
            throw new IllegalStateException(
                    "فقط آخرین درخواست تغییر وضعیت این دارایی قابل تأیید یا بازگردانی است؛ "
                            + "ابتدا درخواست‌های جدیدتر را رسیدگی کنید.");
        }

        long now = System.currentTimeMillis();
        Long actorUserId = SecurityUtils.currentUserId();

        if (target == AssetStatusRequestStatus.APPROVED) {
            // Record what is being replaced BEFORE the write, so an undo has an exact anchor.
            request.setAppliedOldStatus(asset.getStatus());
            // Dated to when the reading was taken, not to now: the timeline should line up with
            // the round that produced it. Requests raised before that was recorded fall back to
            // their own filing time rather than to the approval.
            Long observedAt = request.getReadingRecordedAt() != null
                    ? request.getReadingRecordedAt() : request.getRequestedAt();
            assetStatusService.writeStatus(asset, request.getRequestedStatus(),
                    AssetStatusChangeType.APPLIED, request.getSource(), request.getId(),
                    request.getLogSheetId(), request.getLogSheetEntryId(), request.getFieldKey(),
                    actorUserId, observedAt);
        } else if (current == AssetStatusRequestStatus.APPROVED) {
            // Undoing an approval: back to exactly what it replaced. The only-latest guard above
            // is what makes this safe — no newer request can have moved the column since.
            // Undoing is dated NOW: it is an administrative decision, not an observation, and
            // back-dating it would hide when the correction actually happened.
            assetStatusService.writeStatus(asset, request.getAppliedOldStatus(),
                    AssetStatusChangeType.REVERTED, request.getSource(), request.getId(),
                    request.getLogSheetId(), request.getLogSheetEntryId(), request.getFieldKey(),
                    actorUserId, now);
            request.setAppliedOldStatus(null);
        }

        request.setStatus(target);
        request.setDecidedByUserId(target == AssetStatusRequestStatus.PENDING ? null : actorUserId);
        request.setDecidedAt(target == AssetStatusRequestStatus.PENDING ? null : now);
        request.setDecisionNote(trimToNull(note));
        request.setUpdatedAt(now);
        log.info("Asset status request {} moved {} -> {} by user {}", requestId, current, target, actorUserId);
        return requestRepository.save(request);
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /** One asset's requests, newest first — for the asset history timeline. */
    @Transactional(readOnly = true)
    public List<AssetStatusChangeRequest> forAsset(Long assetId) {
        return assetId == null ? List.of() : requestRepository.findByAssetIdOrderByIdDesc(assetId);
    }

    /**
     * Whether this request is the newest for its asset, i.e. whether its approval may be undone.
     * The UI uses it to disable the control rather than let someone click into a refusal.
     */
    @Transactional(readOnly = true)
    public boolean isLatestForAsset(AssetStatusChangeRequest request) {
        if (request == null || request.getId() == null) {
            return false;
        }
        return requestRepository.findFirstByAssetIdOrderByIdDesc(request.getAssetId())
                .map(latest -> Objects.equals(latest.getId(), request.getId()))
                .orElse(false);
    }

    public Optional<AssetStatusChangeRequest> find(Long id) {
        return id == null ? Optional.empty() : requestRepository.findById(id);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Deciding is a supervisor's or admin's job.
     *
     * <p>Checked here rather than only on the endpoint: the service is reachable from more than
     * one route, and "who may change equipment state" is not a rule to leave to a controller
     * annotation alone.
     */
    private void requireDecider() {
        if (!SecurityUtils.isAdmin() && !SecurityUtils.hasRole("SUPERVISOR") && !SecurityUtils.hasRole("HIGH_USER")) {
            throw new AccessDeniedException("Only a supervisor or administrator may decide asset status changes.");
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
