package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.SelectOptionDto;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The options behind the log-sheet list's searchable filters, and the labels a reloaded filter
 * shows (roadmap §26).
 *
 * <h2>Every picker offers only what the list could show</h2>
 *
 * <p>The list intersects each filter with the viewer's visible units, so an option outside them
 * would be a choice that always answers «nothing» — and, worse, a picker is a <em>directory</em>:
 * a scoped supervisor's unit picker listing the whole plant, or an assignee picker listing every
 * employee, discloses names no other page of theirs shows. So each search runs under the same
 * scope as the list, through {@link LogSheetAccessService#visibleUnitIdsForList()}.
 *
 * <p>The labels follow the same rule. A filter value arrives from the URL and can be anything;
 * resolving it without the scope check would print the name of whatever unit, template or
 * person the id happens to name.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogSheetFilterOptionsService {

    /** The picker shows a page of matches; typing narrows it. Same ceiling as the other pickers. */
    private static final int MAX_OPTIONS = 50;

    private final LogSheetAccessService accessService;
    private final MasterDataOptionsService masterDataOptionsService;
    private final LogSheetTemplateService templateService;
    private final LogSheetTemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final ReferenceLabelService referenceLabelService;

    public List<SelectOptionDto> units(String q, int limit) {
        Collection<Long> unitIds = accessService.visibleUnitIdsForList();
        return unitIds == null
                ? masterDataOptionsService.searchOperationalUnits(q, clamp(limit))
                : masterDataOptionsService.searchOperationalUnitsInIds(q, unitIds, clamp(limit));
    }

    public List<SelectOptionDto> templates(String q, int limit) {
        return templateService.findVisible(q, PageRequest.of(0, clamp(limit))).getContent().stream()
                .map(t -> SelectOptionDto.of(String.valueOf(t.getId()), templateLabel(t)))
                .toList();
    }

    public List<SelectOptionDto> assignees(String q, int limit) {
        Collection<Long> unitIds = accessService.visibleUnitIdsForList();
        if (unitIds != null && unitIds.isEmpty()) {
            return List.of();
        }
        String like = "%" + (q == null ? "" : q.trim().toLowerCase()) + "%";
        return userRepository.searchAssigneesOfVisibleSheets(like, unitIds, PageRequest.of(0, clamp(limit)))
                .stream()
                .map(u -> SelectOptionDto.of(String.valueOf(u.getId()), userLabel(u)))
                .toList();
    }

    /** The unit's «code — name», or null when this viewer may not see it. */
    public String unitLabel(Long unitId) {
        if (unitId == null) {
            return null;
        }
        Collection<Long> unitIds = accessService.visibleUnitIdsForList();
        if (unitIds != null && !unitIds.contains(unitId)) {
            return null;
        }
        Map<Long, String> labels = referenceLabelService.operationalUnitLabelsFor(List.of(unitId));
        return labels.get(unitId);
    }

    /** The template's name, or null when this viewer may not see it. */
    public String templateLabel(Long templateId) {
        if (templateId == null) {
            return null;
        }
        return templateRepository.findById(templateId)
                .filter(templateService::canView)
                .map(LogSheetFilterOptionsService::templateLabel)
                .orElse(null);
    }

    /** The person's label, or null unless they hold a round this viewer can see. */
    public String assigneeLabel(Long userId) {
        if (userId == null) {
            return null;
        }
        Collection<Long> unitIds = accessService.visibleUnitIdsForList();
        if (unitIds != null && unitIds.isEmpty()) {
            return null;
        }
        return userRepository.findAssigneeOfVisibleSheets(userId, unitIds)
                .map(LogSheetFilterOptionsService::userLabel)
                .orElse(null);
    }

    private static String templateLabel(LogSheetTemplate t) {
        String name = t.getName() == null ? "#" + t.getId() : t.getName();
        return Boolean.FALSE.equals(t.getActive()) ? name + " · غیرفعال" : name;
    }

    /** «name · username» — a separator rather than parentheses, which RTL mirrors (AGENTS.md §9c-4). */
    private static String userLabel(User u) {
        String name = u.getFullName() == null || u.getFullName().isBlank() ? null : u.getFullName().trim();
        return name == null ? u.getUsername() : name + " · " + u.getUsername();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_OPTIONS));
    }
}
