package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.NfcFaultReportStatus;
import com.hnp.backendofflinefirst.dto.NfcFaultReportDto;
import com.hnp.backendofflinefirst.dto.NfcFaultReportSubmitResult;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.NfcFaultReport;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.NfcFaultReportRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Immutable NFC-scan-failure reports: an operator or supervisor records that a
 * specific asset's tag could not be scanned within a specific log sheet, which
 * unlocks a manual-entry fallback for that {@code (logSheetId, assetId)} pair.
 * Reports are never edited; only ADMIN may delete one (web only).
 */
@Service
@RequiredArgsConstructor
public class NfcFaultReportService {

    private final NfcFaultReportRepository repository;
    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final UserRepository userRepository;
    private final OperationalUnitScopeService scopeService;
    private final LogSheetAccessService logSheetAccessService;

    /** Same rationale/property as {@link LogSheetService#batchMaxItems}. */
    @Value("${app.sync.batch-max-items}")
    private int batchMaxItems = 500;

    @Transactional
    public NfcFaultReport createFromWeb(Long logSheetId, Long assetId, String reason) {
        LogSheet sheet = requireSheetWithAsset(logSheetId, assetId);
        requireWebCreateAccess(sheet);
        Long userId = SecurityUtils.currentUserId();
        long now = System.currentTimeMillis();
        return save(sheet, assetId, reason, userId, fullName(userId), ActionSource.WEB, now, now, null, null);
    }

    @Transactional
    public List<NfcFaultReportSubmitResult> submitBatch(List<NfcFaultReportDto> dtos) {
        List<NfcFaultReportSubmitResult> results = new ArrayList<>();
        if (dtos == null) return results;
        if (dtos.size() > batchMaxItems) {
            throw new IllegalArgumentException(
                    "Batch has " + dtos.size() + " items; maximum allowed is " + batchMaxItems + ".");
        }
        for (NfcFaultReportDto dto : dtos) {
            results.add(submitOne(dto));
        }
        return results;
    }

    private NfcFaultReportSubmitResult submitOne(NfcFaultReportDto dto) {
        if (dto.getClientActionId() != null && repository.existsByClientActionId(dto.getClientActionId())) {
            return new NfcFaultReportSubmitResult(dto.getLocalId(), null, null, "DUPLICATE");
        }
        if (dto.getLogSheetId() == null || dto.getAssetId() == null) {
            return new NfcFaultReportSubmitResult(dto.getLocalId(), null,
                    "Log sheet id and asset id are required.", "ERROR");
        }
        LogSheet sheet = logSheetRepository.findById(dto.getLogSheetId()).orElse(null);
        if (sheet == null) {
            return new NfcFaultReportSubmitResult(dto.getLocalId(), null,
                    "Log sheet not found on server.", "ERROR");
        }
        boolean assetOnSheet = logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .anyMatch(entry -> dto.getAssetId().equals(entry.getAssetId()));
        if (!assetOnSheet) {
            return new NfcFaultReportSubmitResult(dto.getLocalId(), sheet.getId(),
                    "Asset is not part of this log sheet.", "ERROR");
        }
        Long userId = SecurityUtils.currentUserId();
        // Delegated rather than re-derived from the scope service. This used to ask
        // `canAccessUnit` directly, which is the same rule minus the "or I am this sheet's
        // assignee" branch — so an operator moved to another unit mid-round had their fault
        // reports answered ERROR, and the client parks an ERROR permanently: the report that
        // unlocks manual entry for a broken chip died on the device. One definition, in
        // LogSheetAccessService.
        if (!logSheetAccessService.canView(sheet)) {
            return new NfcFaultReportSubmitResult(dto.getLocalId(), sheet.getId(),
                    "This log sheet is outside your unit scope.", "ERROR");
        }
        long now = System.currentTimeMillis();
        long createdAt = dto.getCreatedAt() != null ? dto.getCreatedAt() : now;
        try {
            NfcFaultReport saved = save(sheet, dto.getAssetId(), dto.getReason(), userId, fullName(userId),
                    ActionSource.MOBILE, createdAt, now, dto.getClientActionId(), dto.getLocalId());
            return new NfcFaultReportSubmitResult(dto.getLocalId(), saved.getId(), null, "CREATED");
        } catch (IllegalArgumentException e) {
            return new NfcFaultReportSubmitResult(dto.getLocalId(), sheet.getId(), e.getMessage(), "ERROR");
        }
    }

    /**
     * One page of the reports visible to the current user: unrestricted for ADMIN/HIGH_USER,
     * unit-scoped otherwise.
     *
     * <p><b>Paged, not filtered in Java.</b> This used to return the entire table and let the
     * template render all of it. One row is filed per broken tag and nothing ever deletes them,
     * so the page grew with the plant's whole NFC history — and it is read most on the days that
     * history is longest. Scope, status, search and paging are now all one query.
     *
     * @param status null for every status
     * @param q      free text over the reason, the reporter's name and the asset's code and name;
     *               null or blank matches everything
     */
    public Page<NfcFaultReport> findVisible(NfcFaultReportStatus status, String q, Pageable pageable) {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            // Scoped to nothing. An empty IN () list is not portable, so answer without asking.
            return Page.empty(pageable);
        }
        return repository.search(unitIds, status, likeOrNull(q), pageable);
    }

    /** Open reports in the caller's scope — the backlog figure, independent of the page shown. */
    public long countOpenVisible() {
        Collection<Long> unitIds = visibleUnitIdsOrNull();
        if (unitIds != null && unitIds.isEmpty()) {
            return 0L;
        }
        return repository.countOpenInScope(unitIds);
    }

    /**
     * Lower-cased and wrapped in {@code %} once here, so the query compares a column against a
     * literal instead of calling {@code LOWER} on the parameter for every row.
     */
    private static String likeOrNull(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase(java.util.Locale.ROOT) + "%";
    }

    /** Reports for one log sheet — caller must have already checked the sheet itself is visible. */
    public List<NfcFaultReport> findByLogSheet(Long logSheetId) {
        return repository.findByLogSheetIdOrderByCreatedAtDesc(logSheetId);
    }

    /**
     * Marks a report reviewed, or puts it back to open.
     *
     * <p>Admin-only, enforced here as well as at the endpoint: "someone has looked at this" is
     * only worth anything if not everyone who can see the list can assert it. Setting the same
     * state twice is a no-op rather than an error — a double-click should not be a failure.
     */
    @Transactional
    public NfcFaultReport setReviewed(Long id, boolean reviewed, Long actorUserId) {
        if (!SecurityUtils.hasCapability(Capabilities.NFC_FAULT_REVIEW)) {
            throw new AccessDeniedException("Only a system administrator can review NFC fault reports.");
        }
        NfcFaultReport report = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("NFC fault report not found."));

        NfcFaultReportStatus target = reviewed
                ? NfcFaultReportStatus.REVIEWED : NfcFaultReportStatus.OPEN;
        if (report.getStatus() == target) {
            return report;
        }
        report.setStatus(target);
        // Reopening clears the attribution: leaving a reviewer's name on a report that is open
        // again would read as "they looked at it and it is still broken", which is not the claim.
        report.setReviewedByUserId(reviewed ? actorUserId : null);
        report.setReviewedAt(reviewed ? System.currentTimeMillis() : null);
        return repository.save(report);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("NFC fault report not found.");
        }
        repository.deleteById(id);
    }

    private NfcFaultReport save(LogSheet sheet, Long assetId, String reason, Long userId, String name,
                                ActionSource source, long createdAt, long syncedAt,
                                String clientActionId, String localId) {
        NfcFaultReport report = new NfcFaultReport();
        report.setLogSheetId(sheet.getId());
        report.setAssetId(assetId);
        report.setOperationalUnitId(sheet.getOperationalUnitId());
        report.setReportedByUserId(userId);
        report.setReportedByName(name);
        report.setSource(source);
        report.setReason(normalizeReason(reason));
        report.setStatus(NfcFaultReportStatus.OPEN);
        report.setCreatedAt(createdAt);
        report.setSyncedAt(syncedAt);
        report.setClientActionId(clientActionId);
        report.setLocalId(localId);
        return repository.save(report);
    }

    private LogSheet requireSheetWithAsset(Long logSheetId, Long assetId) {
        LogSheet sheet = logSheetRepository.findById(logSheetId)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet not found."));
        boolean assetOnSheet = logSheetEntryRepository.findByLogSheetId(logSheetId).stream()
                .anyMatch(entry -> assetId != null && assetId.equals(entry.getAssetId()));
        if (!assetOnSheet) {
            throw new IllegalArgumentException("Asset is not part of this log sheet.");
        }
        return sheet;
    }

    private void requireWebCreateAccess(LogSheet sheet) {
        if (SecurityUtils.hasCapability(Capabilities.SUPERVISE_ANY_UNIT)) {
            return;
        }
        if (!scopeService.isSupervisorOf(SecurityUtils.currentUserId(), sheet.getOperationalUnitId())) {
            throw new AccessDeniedException("You are not the supervisor of this unit.");
        }
    }

    private Collection<Long> visibleUnitIdsOrNull() {
        if (!SecurityUtils.isUnitScopedOnly()) {
            return null;
        }
        return scopeService.getAccessibleUnitIds(SecurityUtils.currentUserId());
    }

    private String fullName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    private static String normalizeReason(String reason) {
        if (reason == null) return null;
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 2000) {
            throw new IllegalArgumentException("NFC fault report reason must be at most 2000 characters.");
        }
        return trimmed;
    }
}
