package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.RecurrenceUnit;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import com.hnp.backendofflinefirst.ui.WebListSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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
    private final OperationalUnitScopeService unitScopeService;
    private final BusinessEventLogger businessEventLogger;

    private static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    /** Rejects the operation unless the current user may manage templates for this unit. */
    public void assertCanManageUnit(Long unitId) {
        if (SecurityUtils.isAdmin()) {
            return;
        }
        Long userId = SecurityUtils.currentUserId();
        if (unitId == null || !unitScopeService.isSupervisorOf(userId, unitId)) {
            throw new AccessDeniedException("You may only manage templates for units you supervise.");
        }
    }

    public boolean canEditOrDelete() {
        return SecurityUtils.isAdmin() || SecurityUtils.hasRole("HIGH_USER");
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
        assertCanManageUnit(form.getOperationalUnitId());
        long now = System.currentTimeMillis();
        form.setCreatedAt(now);
        form.setUpdatedAt(now);
        normalize(form);
        form.setNextRunAt(computeInitialNextRun(form, now));
        form.setLastRunAt(null);
        LogSheetTemplate saved = templateRepository.save(form);
        businessEventLogger.templateCreated(saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void update(Long id, LogSheetTemplate form) {
        LogSheetTemplate e = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Log sheet template not found."));
        assertCanEditOrDelete(e);
        assertCanManageUnit(form.getOperationalUnitId());

        e.setName(form.getName());
        e.setDescription(blankToNull(form.getDescription()));
        e.setScopeType(form.getScopeType());
        e.setScopeId(form.getScopeId());
        e.setClassId(form.getClassId());
        e.setOperationalUnitId(form.getOperationalUnitId());
        e.setGenerationMode(form.getGenerationMode());
        e.setRecurrenceUnit(form.getRecurrenceUnit());
        e.setRecurrenceEvery(form.getRecurrenceEvery());
        e.setScheduleStartAt(form.getScheduleStartAt());
        e.setScheduleActive(form.getScheduleActive());
        e.setCompletionWindowMinutes(form.getCompletionWindowMinutes());
        e.setActive(form.getActive() != null ? form.getActive() : true);
        e.setUpdatedAt(System.currentTimeMillis());
        normalize(e);
        e.setNextRunAt(computeInitialNextRun(e, System.currentTimeMillis()));
        templateRepository.save(e);
        businessEventLogger.templateUpdated(id, e.getName());
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

    /** Normalizes inconsistent scheduling input into a coherent state. */
    private void normalize(LogSheetTemplate t) {
        t.setDescription(blankToNull(t.getDescription()));
        if (t.getActive() == null) {
            t.setActive(true);
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
