package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.BulkDeleteResult;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.LocationUnitRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.ui.ErrorTranslator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OperationalUnitService {

    private final OperationalUnitRepository operationalUnitRepository;
    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final LocationUnitRepository locationUnitRepository;
    private final LogSheetTemplateRepository logSheetTemplateRepository;
    private final LogSheetRepository logSheetRepository;
    private final TransactionTemplate transactionTemplate;

    public OperationalUnitService(OperationalUnitRepository operationalUnitRepository,
                                  UnitSupervisorRepository unitSupervisorRepository,
                                  UnitOperatorRepository unitOperatorRepository,
                                  LocationUnitRepository locationUnitRepository,
                                  LogSheetTemplateRepository logSheetTemplateRepository,
                                  LogSheetRepository logSheetRepository,
                                  PlatformTransactionManager transactionManager) {
        this.operationalUnitRepository = operationalUnitRepository;
        this.unitSupervisorRepository = unitSupervisorRepository;
        this.unitOperatorRepository = unitOperatorRepository;
        this.locationUnitRepository = locationUnitRepository;
        this.logSheetTemplateRepository = logSheetTemplateRepository;
        this.logSheetRepository = logSheetRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OperationalUnit> findAll() {
        return operationalUnitRepository.findAllByOrderByIdDesc();
    }

    public List<Long> getSupervisorIds(Long unitId) {
        return unitSupervisorRepository.findByUnitId(unitId).stream()
                .map(UnitSupervisor::getUserId)
                .toList();
    }

    public List<Long> getOperatorIds(Long unitId) {
        return unitOperatorRepository.findByUnitId(unitId).stream()
                .map(UnitOperator::getUserId)
                .toList();
    }

    /**
     * Supervisor ids for a whole page of units, in one query.
     *
     * <p>The units list called {@link #getSupervisorIds} and {@link #getOperatorIds} once per
     * row, which is two queries per unit on top of the page itself — 50 on a default page of 25,
     * and 500 at the 250-per-page setting the toolbar offers. Nothing was visibly slow at four
     * units, which is exactly how this kind of thing survives.
     *
     * <p>Returns a map covering only the units that have somebody assigned; callers read it with
     * {@code getOrDefault(id, List.of())} so an absent key means "nobody", not "not loaded".
     */
    public Map<Long, List<Long>> supervisorIdsByUnit(Collection<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return Map.of();
        }
        return unitSupervisorRepository.findByUnitIdIn(unitIds).stream()
                .collect(Collectors.groupingBy(UnitSupervisor::getUnitId,
                        Collectors.mapping(UnitSupervisor::getUserId, Collectors.toList())));
    }

    /** Operator ids for a whole page of units, in one query. See {@link #supervisorIdsByUnit}. */
    public Map<Long, List<Long>> operatorIdsByUnit(Collection<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) {
            return Map.of();
        }
        return unitOperatorRepository.findByUnitIdIn(unitIds).stream()
                .collect(Collectors.groupingBy(UnitOperator::getUnitId,
                        Collectors.mapping(UnitOperator::getUserId, Collectors.toList())));
    }

    @Transactional
    public OperationalUnit create(OperationalUnit unit, List<Long> supervisorIds, List<Long> operatorIds) {
        String code = requireUniqueCode(null, unit.getCode());
        unit.setCode(code);
        long now = System.currentTimeMillis();
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        OperationalUnit saved = operationalUnitRepository.save(unit);
        saveAssignments(saved.getId(), supervisorIds, operatorIds);
        return saved;
    }

    @Transactional
    public void update(Long id, OperationalUnit form, List<Long> supervisorIds, List<Long> operatorIds) {
        OperationalUnit unit = operationalUnitRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Operational unit not found."));
        if (id.equals(form.getParentId())) {
            throw new IllegalArgumentException("Unit cannot be its own parent.");
        }
        requireNoParentCycle(id, form.getParentId());
        unit.setCode(requireUniqueCode(id, form.getCode()));
        unit.setName(form.getName());
        unit.setParentId(form.getParentId());
        unit.setUpdatedAt(System.currentTimeMillis());
        operationalUnitRepository.save(unit);
        saveAssignments(id, supervisorIds, operatorIds);
    }

    /**
     * Rejects a parent that is already a descendant of this unit (A → B → A).
     *
     * <p>Blocking only the direct self-parent is not enough: the unit tree drives access control
     * — a supervisor's authority expands downward through it — so a cycle would make two units
     * each other's descendant and hand every supervisor on the loop the other's scope. It would
     * also make any ancestor walk non-terminating.
     */
    private void requireNoParentCycle(Long id, Long proposedParentId) {
        Long cursor = proposedParentId;
        // Bounded by the number of units: a pre-existing loop can never spin forever here.
        int guard = 0, maxHops = (int) operationalUnitRepository.count() + 1;
        while (cursor != null && guard++ <= maxHops) {
            if (cursor.equals(id)) {
                throw new IllegalArgumentException("Unit parent chain would create a cycle");
            }
            cursor = operationalUnitRepository.findById(cursor)
                    .map(OperationalUnit::getParentId)
                    .orElse(null);
        }
    }

    @Transactional
    public void delete(Long id) {
        doDelete(id);
    }

    /**
     * Deletes several units, each in its own transaction, and reports what happened per id.
     *
     * <p><b>Per-id transactions rather than one.</b> Selecting twenty units and finding that one
     * of them still owns a location is the ordinary case, not the exception; a single transaction
     * would roll the other nineteen back and leave the administrator to work out which one was
     * the problem by bisecting. Each id therefore succeeds or fails on its own, and the page
     * names the ones that were refused and why. Same shape and the same reasoning as
     * {@code MasterDataDeleteService}.
     *
     * <p>The guards are {@link #doDelete}'s — shared with the single-row delete rather than
     * restated, so the two can never disagree about what makes a unit deletable.
     */
    public BulkDeleteResult deleteAll(Collection<Long> ids) {
        BulkDeleteResult result = new BulkDeleteResult();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        Set<Long> unique = new LinkedHashSet<>(ids);
        for (Long id : unique) {
            if (id == null) {
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> doDelete(id));
                result.addSuccess();
            } catch (Exception e) {
                result.addError(id, translateDeleteError(e));
            }
        }
        return result;
    }

    /**
     * What makes a unit deletable — and what deliberately does not.
     *
     * <p>The four refusals are all about something that would be left pointing at nothing: a
     * child unit, a location owned by this unit, a template scoped to it, or a log sheet raised
     * against it. Each is history or configuration that a person would have to reconstruct.
     *
     * <p><b>Supervisors and operators are not a reason to refuse.</b> They are assignments *to*
     * the unit, not records that outlive it: `unit_supervisors` and `unit_operators` hold nothing
     * but the pairing, the user rows are untouched, and a unit that is being deleted has no
     * staff to have. Refusing here would mean an administrator has to unassign everybody by hand
     * first, for no gain — and the rows would be orphaned anyway if they were left.
     */
    private void doDelete(Long id) {
        if (operationalUnitRepository.existsByParentId(id)) {
            throw new IllegalStateException("This unit has child units and cannot be deleted.");
        }
        if (locationUnitRepository.existsByUnitId(id)) {
            throw new IllegalStateException("This unit has locations and cannot be deleted.");
        }
        if (logSheetTemplateRepository.existsByOperationalUnitId(id)) {
            throw new IllegalStateException("This unit has log sheet templates and cannot be deleted.");
        }
        if (logSheetRepository.existsByOperationalUnitId(id)) {
            throw new IllegalStateException("This unit has log sheets and cannot be deleted.");
        }
        unitSupervisorRepository.deleteByUnitId(id);
        unitOperatorRepository.deleteByUnitId(id);
        operationalUnitRepository.deleteById(id);
    }

    private static String translateDeleteError(Exception e) {
        if (e instanceof DataIntegrityViolationException dive) {
            return ErrorTranslator.dataIntegrityViolation(dive);
        }
        return ErrorTranslator.toFa(e.getMessage());
    }

    private String requireUniqueCode(Long id, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Operational unit code is required.");
        }
        String trimmed = code.trim();
        operationalUnitRepository.findByCodeIgnoreCase(trimmed).ifPresent(existing -> {
            if (!Objects.equals(id, existing.getId())) {
                throw new IllegalArgumentException("Duplicate operational unit code: " + trimmed);
            }
        });
        return trimmed;
    }

    private void saveAssignments(Long unitId, List<Long> supervisorIds, List<Long> operatorIds) {
        unitSupervisorRepository.deleteByUnitId(unitId);
        unitOperatorRepository.deleteByUnitId(unitId);

        if (supervisorIds != null) {
            for (Long userId : supervisorIds) {
                if (userId == null) continue;
                UnitSupervisor link = new UnitSupervisor();
                link.setUnitId(unitId);
                link.setUserId(userId);
                unitSupervisorRepository.save(link);
            }
        }
        if (operatorIds != null) {
            for (Long userId : operatorIds) {
                if (userId == null) continue;
                UnitOperator link = new UnitOperator();
                link.setUnitId(unitId);
                link.setUserId(userId);
                unitOperatorRepository.save(link);
            }
        }
    }

    public List<String> formatUserNames(List<Long> userIds, Map<Long, String> userNameById) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (Long userId : userIds) {
            names.add(userNameById.getOrDefault(userId, String.valueOf(userId)));
        }
        return names;
    }
}
