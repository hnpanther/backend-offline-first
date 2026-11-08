package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which operational units a person belongs to, and as what.
 *
 * <h2>Why this needed a service rather than two lookups in a controller</h2>
 *
 * The answer lives in two tables — {@code unit_supervisors} and {@code unit_operators} — and
 * the useful form of it is neither: it is "this unit, in this role", with <b>both</b> roles
 * collapsed onto one line where somebody holds them. Reading that off two lists per row is the
 * shape that becomes an N+1 the first time it is written inline, on a page that already shows
 * twenty-five users.
 *
 * <p>It also answers a question the panel genuinely could not answer before. «واحد سازمانی» and
 * «پست سازمانی» on the users page are free-text HR fields; the *operational* membership — the
 * thing that decides what a supervisor may approve and which rounds reach an operator's inbox —
 * was only visible by opening each unit in turn and reading its two pickers.
 */
@Service
@RequiredArgsConstructor
public class UserUnitMembershipService {

    private final UnitSupervisorRepository unitSupervisorRepository;
    private final UnitOperatorRepository unitOperatorRepository;
    private final ReferenceLabelService referenceLabelService;

    /**
     * One person's place in one unit.
     *
     * @param unitId     the unit
     * @param unitLabel  its name, resolved once for the whole page
     * @param supervisor whether they supervise it
     * @param operator   whether they are an operator in it
     */
    public record Membership(Long unitId, String unitLabel, boolean supervisor, boolean operator) {

        /** «سرپرست»، «اپراتور» or both — the two are not exclusive and the page must show that. */
        public String roleLabel() {
            if (supervisor && operator) {
                return "سرپرست و اپراتور";
            }
            return supervisor ? "سرپرست" : "اپراتور";
        }
    }

    /**
     * Memberships for a whole page of users, keyed by user id.
     *
     * <p><b>Three queries regardless of how many users are on the page</b> — the supervisors,
     * the operators, then one batched label lookup for the units those two mention. Every user
     * id asked for is a key in the result, mapping to an empty list when they belong nowhere,
     * so a template can index it without a null check (SpEL throws on a null map index).
     */
    @Transactional(readOnly = true)
    public Map<Long, List<Membership>> findForUsers(Collection<Long> userIds) {
        Set<Long> wanted = new LinkedHashSet<>();
        for (Long id : userIds == null ? List.<Long>of() : userIds) {
            if (id != null) {
                wanted.add(id);
            }
        }
        Map<Long, List<Membership>> byUser = new LinkedHashMap<>();
        for (Long id : wanted) {
            byUser.put(id, new ArrayList<>());
        }
        if (wanted.isEmpty()) {
            return byUser;
        }

        List<UnitSupervisor> supervisors = unitSupervisorRepository.findByUserIdIn(wanted);
        List<UnitOperator> operators = unitOperatorRepository.findByUserIdIn(wanted);

        // user -> unit -> which roles. A LinkedHashMap per user so the units come out in a
        // stable order rather than a hash order that shuffles between renders.
        Map<Long, Map<Long, boolean[]>> roles = new LinkedHashMap<>();
        for (UnitSupervisor link : supervisors) {
            roles.computeIfAbsent(link.getUserId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(link.getUnitId(), k -> new boolean[2])[0] = true;
        }
        for (UnitOperator link : operators) {
            roles.computeIfAbsent(link.getUserId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(link.getUnitId(), k -> new boolean[2])[1] = true;
        }

        Set<Long> unitIds = new LinkedHashSet<>();
        roles.values().forEach(byUnit -> unitIds.addAll(byUnit.keySet()));
        Map<Long, String> labels = referenceLabelService.operationalUnitLabelsFor(unitIds);

        roles.forEach((userId, byUnit) -> {
            List<Membership> list = byUser.get(userId);
            if (list == null) {
                // A link to a user outside the page — ignored rather than added, because the
                // caller asked about these users and a stray key would surprise the template.
                return;
            }
            byUnit.forEach((unitId, held) -> list.add(new Membership(
                    unitId, labels.getOrDefault(unitId, String.valueOf(unitId)), held[0], held[1])));
            // Supervised units first, then alphabetically: a supervisor's own units are what
            // somebody reading this row is almost always looking for.
            list.sort(Comparator.comparing(Membership::supervisor).reversed()
                    .thenComparing(Membership::unitLabel, Comparator.nullsLast(String::compareTo)));
        });
        return byUser;
    }

    /** The same for one person, for a detail view. */
    @Transactional(readOnly = true)
    public List<Membership> findForUser(Long userId) {
        return findForUsers(List.of(userId)).getOrDefault(userId, List.of());
    }
}
