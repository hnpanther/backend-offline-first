package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.LogSheetTemplateAsset;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateAssetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns log-sheet template creation/edit rules and schedule bookkeeping.
 * Creation authorization: ADMIN may target any unit; HIGH_USER and SUPERVISOR may
 * only target a unit they supervise (or a sub-unit of it). Edit/delete: ADMIN
 * and HIGH_USER only, within the same unit scope.
 */
@Service
@RequiredArgsConstructor
public class LogSheetTemplateService {

    private final LogSheetTemplateRepository templateRepository;
    private final AssetClassRepository assetClassRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final LogSheetTemplateAssetRepository templateAssetRepository;
    private final AssetHierarchyService assetHierarchyService;
    private final OperationalUnitScopeService unitScopeService;
    private final BusinessEventLogger businessEventLogger;

    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    /**
     * Rejects the operation unless the current user may manage templates for this unit.
     *
     * <p>Writing a template is reserved for plant-wide roles: ADMIN anywhere, HIGH_USER within
     * the units they supervise. A SUPERVISOR is <strong>read-only</strong> on templates — they see
     * the ones belonging to their own units (see {@link #visibleUnitIds()}) but may not create,
     * edit, or delete any. Enforced here as well as by the endpoint permission, because the
     * permission set is user-editable and must not be the only gate.
     */
    public void assertCanManageUnit(Long unitId) {
        if (SecurityUtils.isAdmin()) {
            return;
        }
        if (!canEditOrDelete()) {
            throw new AccessDeniedException("Only admin or senior supervisor may create or edit log sheet templates.");
        }
        Long userId = SecurityUtils.currentUserId();
        if (unitId == null || !unitScopeService.isSupervisorOf(userId, unitId)) {
            throw new AccessDeniedException("You may only manage templates for units you supervise.");
        }
    }

    /** ADMIN and HIGH_USER only — the single source of truth for "may write a template". */
    public boolean canEditOrDelete() {
        return SecurityUtils.isAdmin() || SecurityUtils.hasRole("HIGH_USER");
    }

    /** Only plant-wide roles may point a template outside its unit's own locations. */
    public boolean canUnrestrictScope() {
        return !SecurityUtils.isUnitScopedOnly();
    }

    /**
     * Validates a hand-picked selection for an EXPLICIT template and returns it
     * de-duplicated, preserving the order the user chose.
     * <p>Every asset must exist and be active at save time. Assets are NOT required to be
     * inside the owning unit: an EXPLICIT template is the scheduled form of a custom log
     * sheet and may deliberately cover outside assets — the same capability the
     * {@code restrictScopeToUnit} flag grants SCOPE templates. A unit-scoped user is still
     * confined to their own unit's assets, mirroring {@code CustomLogSheetService.createCustom}.
     * <p>That confinement is currently <em>defence-in-depth</em>: template writes are limited to
     * ADMIN/HIGH_USER by {@link #assertCanManageUnit}, and neither role is unit-scoped, so no
     * writer reaches the unit-filtered branch today. It is kept so that relaxing the role rule
     * cannot silently reopen the escalation path.
     */
    private List<Long> validateExplicitAssets(LogSheetTemplate form, List<Long> assetIds) {
        LinkedHashSet<Long> distinct = assetIds == null ? new LinkedHashSet<>()
                : assetIds.stream().filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("Select at least one asset for the log sheet template.");
        }
        List<AssetEntry> found = SecurityUtils.isUnitScopedOnly()
                ? assetEntryRepository.findVisibleActiveByIdInAndUnitIds(
                        Set.of(form.getOperationalUnitId()), distinct)
                : assetEntryRepository.findActiveByIdIn(distinct);
        if (found.size() != distinct.size()) {
            throw new IllegalArgumentException("Some selected assets are not available for this template.");
        }
        return List.copyOf(distinct);
    }

    /** Rewrites an EXPLICIT template's frozen asset list wholesale; clears it for SCOPE. */
    private void replaceTemplateAssets(Long templateId, AssetSelectionMode mode, List<Long> assetIds) {
        templateAssetRepository.deleteByTemplateId(templateId);
        if (mode != AssetSelectionMode.EXPLICIT || assetIds == null || assetIds.isEmpty()) {
            return;
        }
        List<LogSheetTemplateAsset> rows = assetIds.stream().map(assetId -> {
            LogSheetTemplateAsset row = new LogSheetTemplateAsset();
            row.setTemplateId(templateId);
            row.setAssetId(assetId);
            return row;
        }).toList();
        templateAssetRepository.saveAll(rows);
    }

    /** The frozen asset ids of an EXPLICIT template (empty for SCOPE templates). */
    public List<Long> assetIdsForTemplate(Long templateId) {
        if (templateId == null) {
            return List.of();
        }
        return templateAssetRepository.findAssetIdsByTemplateId(templateId);
    }

    /**
     * A unit-scoped supervisor may only build templates over their own unit's hierarchy.
     * Letting them clear the restriction would be a privilege escalation: they could scope
     * a template at another unit's assets and then read those values back through the log
     * sheets generated into their own unit. Enforced here (not only in the UI) because the
     * flag arrives as a plain form field.
     */
    private void applyScopeRestrictionPolicy(LogSheetTemplate form) {
        if (!canUnrestrictScope()) {
            form.setRestrictScopeToUnit(true);
        }
    }

    /** The column is NOT NULL; an absent form value means the classic scope-driven mode. */
    private static void normalizeSelectionMode(LogSheetTemplate form) {
        if (form.getAssetSelectionMode() == null) {
            form.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        }
    }

    /** {@code null} means no unit filter (admin); otherwise only these unit ids are visible. */
    public Collection<Long> visibleUnitIds() {
        if (SecurityUtils.isAdmin()) {
            return null;
        }
        if (SecurityUtils.hasRole("HIGH_USER") || SecurityUtils.hasRole("SUPERVISOR")) {
            Set<Long> ids = unitScopeService.getSupervisorScopeUnitIds(SecurityUtils.currentUserId());
            return ids.isEmpty() ? List.of(-1L) : ids;
        }
        return List.of(-1L);
    }

    public Page<LogSheetTemplate> findVisible(String q, Pageable pageable) {
        Collection<Long> unitIds = visibleUnitIds();
        if (unitIds == null) {
            return WebListSupport.pagedList(q, pageable, templateRepository::findAll, templateRepository::search);
        }
        return WebListSupport.pagedList(q, pageable,
                p -> templateRepository.findByOperationalUnitIdIn(unitIds, p),
                (term, p) -> templateRepository.searchInUnits(term, unitIds, p));
    }

    public List<LogSheetTemplate> findVisibleAll() {
        Collection<Long> unitIds = visibleUnitIds();
        if (unitIds == null) {
            return templateRepository.findAllByOrderByIdDesc();
        }
        return templateRepository.findByOperationalUnitIdInOrderByIdDesc(unitIds);
    }

    public LogSheetTemplate requireVisible(Long id) {
        LogSheetTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet template not found."));
        if (!canView(template)) {
            throw new AccessDeniedException("Access to this template is not allowed.");
        }
        return template;
    }

    public boolean canView(LogSheetTemplate template) {
        Collection<Long> unitIds = visibleUnitIds();
        if (unitIds == null) {
            return true;
        }
        return template.getOperationalUnitId() != null && unitIds.contains(template.getOperationalUnitId());
    }

    public void assertCanEditOrDelete(LogSheetTemplate template) {
        if (!canEditOrDelete()) {
            throw new AccessDeniedException("Only admin or senior supervisor may edit or delete log sheet templates.");
        }
        assertCanManageUnit(template.getOperationalUnitId());
    }

    @Transactional
    public LogSheetTemplate create(LogSheetTemplate form) {
        return create(form, null);
    }

    @Transactional
    public LogSheetTemplate create(LogSheetTemplate form, List<Long> assetIds) {
        assertCanManageUnit(form.getOperationalUnitId());
        applyScopeRestrictionPolicy(form);
        normalizeSelectionMode(form);
        // Required fields first: validateExplicitAssets dereferences the operational unit id,
        // whose presence is only guaranteed once validateRequiredFields has run.
        validateRequiredFields(form, null);
        List<Long> explicitAssets = form.getAssetSelectionMode() == AssetSelectionMode.EXPLICIT
                ? validateExplicitAssets(form, assetIds)
                : List.of();
        long now = System.currentTimeMillis();
        // Brand-new template: every submitted value is a fresh user decision, so always check.
        DateUtils.requireFutureWithinYears(form.getScheduleStartAt(), now, "Schedule start date");
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        normalize(form);
        form.setNextRunAt(computeInitialNextRun(form, now));
        form.setLastRunAt(null);
        LogSheetTemplate saved = templateRepository.save(form);
        replaceTemplateAssets(saved.getId(), saved.getAssetSelectionMode(), explicitAssets);
        businessEventLogger.templateCreated(saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void update(Long id, LogSheetTemplate form) {
        update(id, form, null);
    }

    @Transactional
    public void update(Long id, LogSheetTemplate form, List<Long> assetIds) {
        LogSheetTemplate e = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet template not found."));
        assertCanEditOrDelete(e);
        assertCanManageUnit(form.getOperationalUnitId());
        applyScopeRestrictionPolicy(form);
        normalizeSelectionMode(form);
        // See create(): the unit id must be validated before validateExplicitAssets reads it.
        validateRequiredFields(form, id);
        List<Long> explicitAssets = form.getAssetSelectionMode() == AssetSelectionMode.EXPLICIT
                ? validateExplicitAssets(form, assetIds)
                : List.of();
        // Only re-validate "future, within N years" when the user is actually setting a NEW
        // start date — an existing template's original start naturally drifts into the past
        // as its recurring schedule keeps running, so re-checking an untouched value here
        // would incorrectly block ordinary edits (e.g. renaming) to a template that has been
        // live for a while.
        if (!Objects.equals(e.getScheduleStartAt(), form.getScheduleStartAt())) {
            DateUtils.requireFutureWithinYears(form.getScheduleStartAt(), System.currentTimeMillis(), "Schedule start date");
        }

        boolean scheduleChanged = scheduleFieldsChanged(e, form);

        e.setName(form.getName());
        e.setDescription(blankToNull(form.getDescription()));
        e.setScopeType(form.getScopeType());
        e.setScopeId(form.getScopeId());
        e.setClassId(form.getClassId());
        e.setOperationalUnitId(form.getOperationalUnitId());
        e.setRestrictScopeToUnit(form.getRestrictScopeToUnit());
        e.setAssetSelectionMode(form.getAssetSelectionMode());
        e.setGenerationMode(form.getGenerationMode());
        e.setRecurrenceUnit(form.getRecurrenceUnit());
        e.setRecurrenceEvery(form.getRecurrenceEvery());
        e.setScheduleStartAt(form.getScheduleStartAt());
        e.setScheduleActive(form.getScheduleActive());
        e.setCompletionWindowMinutes(form.getCompletionWindowMinutes());
        e.setActive(form.getActive() != null ? form.getActive() : true);
        e.setUpdatedAt(System.currentTimeMillis());
        normalize(e);
        long now = System.currentTimeMillis();
        Long computedNextRun = computeInitialNextRun(e, now);
        if (computedNextRun == null) {
            // Manual / inactive / incomplete schedule — never leave a stale cursor.
            e.setNextRunAt(null);
        } else if (scheduleChanged || e.getNextRunAt() == null) {
            // Schedule definition changed (or cursor missing) — re-seed from start/now.
            e.setNextRunAt(computedNextRun);
        }
        // else: keep the scheduler cursor (rename/scope/class edits must not move it)
        templateRepository.save(e);
        replaceTemplateAssets(id, e.getAssetSelectionMode(), explicitAssets);
        businessEventLogger.templateUpdated(id, e.getName());
    }

    /**
     * True when any field that defines "when to fire" changed vs the persisted entity.
     * Name, description, scope, class, unit, active, and completion window are excluded.
     */
    private static boolean scheduleFieldsChanged(LogSheetTemplate existing, LogSheetTemplate form) {
        return !java.util.Objects.equals(existing.getGenerationMode(), form.getGenerationMode())
                || !java.util.Objects.equals(existing.getScheduleActive(), form.getScheduleActive())
                || !java.util.Objects.equals(existing.getRecurrenceUnit(), form.getRecurrenceUnit())
                || !java.util.Objects.equals(existing.getRecurrenceEvery(), form.getRecurrenceEvery())
                || !java.util.Objects.equals(existing.getScheduleStartAt(), form.getScheduleStartAt());
    }

    @Transactional
    public void delete(Long id) {
        templateRepository.findById(id).ifPresent(e -> {
            assertCanEditOrDelete(e);
            businessEventLogger.templateDeleted(id, e.getName());
            templateRepository.deleteById(id);
        });
    }

    /** Rejects generation when the template itself is deactivated. */
    public void assertActiveForGeneration(LogSheetTemplate template) {
        if (template == null || Boolean.FALSE.equals(template.getActive())) {
            throw new IllegalStateException("This log sheet template is inactive.");
        }
    }

    /**
     * True when the configured completion window is longer than the scheduled recurrence
     * interval — the previous occurrence(s) would still be open when the next one is
     * generated, so multiple overlapping sheets for the same assets can stack up. Informational
     * only: the caller surfaces this as a warning and never blocks create/update on it.
     */
    public boolean scheduleOverlapRisk(LogSheetTemplate form) {
        if (form.getGenerationMode() != GenerationMode.SCHEDULED) return false;
        if (!Boolean.TRUE.equals(form.getScheduleActive())) return false;
        if (form.getRecurrenceUnit() == null) return false;
        if (form.getCompletionWindowMinutes() == null || form.getCompletionWindowMinutes() <= 0) return false;
        int every = form.getRecurrenceEvery() != null ? Math.max(form.getRecurrenceEvery(), 1) : 1;
        long recurrenceMinutes = recurrenceIntervalMinutes(form.getRecurrenceUnit(), every);
        return form.getCompletionWindowMinutes() > recurrenceMinutes;
    }

    /** Approximate minutes-per-recurrence, deliberately rough for MONTH — this only feeds a heads-up warning. */
    private static long recurrenceIntervalMinutes(RecurrenceUnit unit, int every) {
        return switch (unit) {
            case MINUTE -> every;
            case HOUR -> every * 60L;
            case DAY -> every * 1_440L;
            case WEEK -> every * 10_080L;
            case MONTH -> every * 43_200L;
        };
    }

    private void validateRequiredFields(LogSheetTemplate form, Long excludeId) {
        String name = form.getName() == null ? null : form.getName().trim();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Log sheet template name is required.");
        }
        form.setName(name);
        templateRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
            if (!Objects.equals(excludeId, existing.getId())) {
                throw new IllegalArgumentException("Duplicate log sheet template name: " + name);
            }
        });
        if (form.getOperationalUnitId() == null) {
            throw new IllegalArgumentException("Operational unit is required for log sheet template.");
        }
        // An EXPLICIT template selects a frozen, hand-picked asset set instead of walking
        // the hierarchy, so scope/class are not required (and its assets may span classes).
        // The selection itself is validated in validateExplicitAssets.
        if (form.getAssetSelectionMode() == AssetSelectionMode.EXPLICIT) {
            return;
        }
        if (form.getScopeType() == null || form.getScopeType().isBlank()) {
            throw new IllegalArgumentException("Scope type is required for log sheet template.");
        }
        if (form.getScopeId() == null) {
            throw new IllegalArgumentException("Scope is required for log sheet template.");
        }
        if (form.getClassId() == null) {
            throw new IllegalArgumentException("Asset class is required for log sheet template.");
        }
        if (!assetClassRepository.existsById(form.getClassId())) {
            throw new IllegalArgumentException("Asset class not found.");
        }
        Long locationId = assetHierarchyService.resolveLocationIdForScope(form.getScopeType(), form.getScopeId());
        if (locationId == null) {
            throw new IllegalArgumentException("Scope not found.");
        }
        // Only enforced when the template restricts scope picking to the unit's own
        // locations. With the restriction off the scope may point anywhere — that is the
        // whole point of the flag (a unit made responsible for outside assets). Access is
        // unaffected: the work is still reachable only via log_sheets.operational_unit_id.
        if (!Boolean.FALSE.equals(form.getRestrictScopeToUnit())
                && !assetHierarchyService.scopeBelongsToOperationalUnit(
                        form.getScopeType(), form.getScopeId(), form.getOperationalUnitId())) {
            throw new IllegalArgumentException("Scope does not belong to the selected operational unit.");
        }
    }

    /** Normalizes inconsistent scheduling input into a coherent state. */
    private void normalize(LogSheetTemplate t) {
        t.setDescription(blankToNull(t.getDescription()));
        if (t.getActive() == null) {
            t.setActive(true);
        }
        if (t.getRestrictScopeToUnit() == null) {
            // Column is NOT NULL; an unchecked checkbox posts nothing. Default to the
            // safer, historical behaviour: scope confined to the unit's own locations.
            t.setRestrictScopeToUnit(true);
        }
        if (t.getGenerationMode() == null) {
            t.setGenerationMode(GenerationMode.MANUAL);
        }
        if (t.getScheduleActive() == null) {
            t.setScheduleActive(false);
        }
        if (t.getGenerationMode() != GenerationMode.SCHEDULED) {
            // Manual templates carry no live schedule.
            t.setScheduleActive(false);
            t.setNextRunAt(null);
        }
    }

    /**
     * First fire time for a scheduled template: the start time if it is still in
     * the future, otherwise the next boundary at/after now (missed runs skipped).
     */
    private Long computeInitialNextRun(LogSheetTemplate t, long now) {
        if (t.getGenerationMode() != GenerationMode.SCHEDULED
                || Boolean.FALSE.equals(t.getScheduleActive())
                || t.getRecurrenceUnit() == null) {
            return null;
        }
        long start = t.getScheduleStartAt() != null ? t.getScheduleStartAt() : now;
        if (start >= now) {
            return start;
        }
        RecurrenceUnit unit = t.getRecurrenceUnit();
        int every = t.getRecurrenceEvery() != null ? t.getRecurrenceEvery() : 1;
        long next = start;
        while (next < now) {
            next = unit.advance(next, every, ZONE);
        }
        return next;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
