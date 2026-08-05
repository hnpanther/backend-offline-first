package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.LogSheetAccessService;
import com.hnp.backendofflinefirst.service.OperationalUnitScopeService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.security.access.AccessDeniedException;

/**
 * The real-world scenario, end to end against PostgreSQL.
 *
 * <pre>
 *   unit A  ── supervised by X
 *    ├── B  ── operated by opB
 *    │    └── D
 *    └── C
 *   unit A also operated by opA
 * </pre>
 *
 * X supervises the whole branch and may act anywhere in it. opA operates only A: the work of
 * opB in unit B must be invisible and unreachable to them, even though B sits under A.
 */
@Transactional
class UnitHierarchyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired OperationalUnitScopeService scopeService;
    @Autowired LogSheetAccessService logSheetAccessService;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired UserRepository userRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired com.hnp.backendofflinefirst.service.LogSheetAssignmentService assignmentService;

    private Long unitA, unitB, unitC, unitD, unitZ;
    private Long supervisorX, operatorOfA, operatorOfB;

    @BeforeEach
    void seed() {
        unitA = saveUnit("A", null);
        unitB = saveUnit("B", unitA);
        unitC = saveUnit("C", unitA);
        unitD = saveUnit("D", unitB);
        unitZ = saveUnit("Z", null);

        supervisorX = saveUser("x-supervisor");
        operatorOfA = saveUser("op-a");
        operatorOfB = saveUser("op-b");

        linkSupervisor(supervisorX, unitA);
        linkOperator(operatorOfA, unitA);
        linkOperator(operatorOfB, unitB);
    }

    // ── the supervisor half: authority flows down ────────────────────────────────

    @Test
    void supervisorOfAMayActAcrossTheWholeBranch() {
        assertThat(scopeService.getSupervisorScopeUnitIds(supervisorX))
                .containsExactlyInAnyOrder(unitA, unitB, unitC, unitD);

        for (Long unit : List.of(unitA, unitB, unitC, unitD)) {
            assertThat(scopeService.isSupervisorOf(supervisorX, unit))
                    .as("supervises unit %s", unit).isTrue();
        }
        assertThat(scopeService.isSupervisorOf(supervisorX, unitZ))
                .as("unrelated unit stays out of reach").isFalse();
    }

    @Test
    void supervisorsAvailablePoolAndTeamListCoverTheSubUnitsToo() {
        Long inA = savePendingSheet(unitA);
        Long inB = savePendingSheet(unitB);
        Long inD = savePendingSheet(unitD);
        Long inZ = savePendingSheet(unitZ);

        List<Long> pool = logSheetAccessService.findAvailablePool(supervisorX)
                .stream().map(LogSheet::getId).toList();

        assertThat(pool).contains(inA, inB, inD);
        assertThat(pool).as("another branch must not appear").doesNotContain(inZ);

        Long openInC = saveAssignedSheet(unitC, operatorOfB);
        assertThat(logSheetAccessService.findTeamOpenForSupervisor(supervisorX)
                .stream().map(LogSheet::getId).toList())
                .as("open work anywhere in the branch is visible to the supervisor")
                .contains(openInC);
    }

    // ── the operator half: authority does NOT flow down ──────────────────────────

    @Test
    void operatorOfAMayNotSeeOrClaimWorkOfTheSubUnits() {
        Long inA = savePendingSheet(unitA);
        Long inB = savePendingSheet(unitB);
        Long inC = savePendingSheet(unitC);
        Long inD = savePendingSheet(unitD);

        List<Long> pool = logSheetAccessService.findAvailablePool(operatorOfA)
                .stream().map(LogSheet::getId).toList();

        assertThat(pool).as("their own unit's work is claimable").contains(inA);
        assertThat(pool).as("sub-unit work belongs to other teams")
                .doesNotContain(inB, inC, inD);
    }

    @Test
    void operatorOfAIsNotAnOperatorOfTheSubUnits() {
        assertThat(scopeService.isOperatorOf(operatorOfA, unitA)).isTrue();
        assertThat(scopeService.isOperatorOf(operatorOfA, unitB)).isFalse();
        assertThat(scopeService.isOperatorOf(operatorOfA, unitC)).isFalse();
        assertThat(scopeService.isOperatorOf(operatorOfA, unitD)).isFalse();
    }

    @Test
    void operatorOfAcannotAccessSubUnitsAtAll() {
        assertThat(scopeService.getAccessibleUnitIds(operatorOfA)).containsExactly(unitA);
        assertThat(scopeService.canAccessUnit(operatorOfA, unitA)).isTrue();
        assertThat(scopeService.canAccessUnit(operatorOfA, unitB)).isFalse();
        assertThat(scopeService.canAccessUnit(operatorOfA, unitD)).isFalse();
    }

    @Test
    void operatorOfBStaysConfinedToBAndDoesNotReachTheParentOrSiblings() {
        assertThat(scopeService.getAccessibleUnitIds(operatorOfB)).containsExactly(unitB);
        assertThat(scopeService.canAccessUnit(operatorOfB, unitA)).as("no upward access").isFalse();
        assertThat(scopeService.canAccessUnit(operatorOfB, unitC)).as("no sibling access").isFalse();
        assertThat(scopeService.canAccessUnit(operatorOfB, unitD)).as("no downward access").isFalse();
    }

    // ── the supervisor may DO the sub-unit's work, but only its own operators may be assigned ──

    @Test
    void parentUnitSupervisorMayTakeOverSubUnitWorkFromItsOperator() {
        // Explicitly wanted: supervising A includes taking B's work over personally.
        Long sheetInB = saveAssignedSheet(unitB, operatorOfB);

        assignmentService.takeover(sheetInB, supervisorX, null);

        LogSheet after = logSheetRepository.findById(sheetInB).orElseThrow();
        assertThat(after.getAssigneeUserId()).isEqualTo(supervisorX);
    }

    @Test
    void takeoverDeeperInTheBranchWorksTooButNotInAnUnrelatedUnit() {
        Long sheetInD = saveAssignedSheet(unitD, operatorOfB);
        assignmentService.takeover(sheetInD, supervisorX, null);
        assertThat(logSheetRepository.findById(sheetInD).orElseThrow().getAssigneeUserId())
                .as("grandchild unit is still inside the supervised branch").isEqualTo(supervisorX);

        Long sheetInZ = saveAssignedSheet(unitZ, operatorOfB);
        assertThatThrownBy(() -> assignmentService.takeover(sheetInZ, supervisorX, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assigningSubUnitWorkIsAllowedOnlyToThatSubUnitsOwnOperators() {
        Long sheetInB = savePendingSheet(unitB);

        // The supervisor of A may assign work sitting in B …
        assertThat(scopeService.isSupervisorOf(supervisorX, unitB)).isTrue();
        // … but the target must be an operator of B, not of the parent unit A.
        assertThat(scopeService.isOperatorOf(operatorOfB, unitB)).as("B's own operator").isTrue();
        assertThat(scopeService.isOperatorOf(operatorOfA, unitB))
                .as("an operator of the parent unit is NOT eligible for B's work").isFalse();

        assertThatThrownBy(() -> assignmentService.assign(sheetInB, operatorOfA, supervisorX, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Target user is not an operator of this unit.");

        assignmentService.assign(sheetInB, operatorOfB, supervisorX, null);
        assertThat(logSheetRepository.findById(sheetInB).orElseThrow().getAssigneeUserId())
                .isEqualTo(operatorOfB);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Long saveUnit(String label, Long parentId) {
        long now = System.currentTimeMillis();
        OperationalUnit u = new OperationalUnit();
        u.setCode("OU-HIER-" + label + "-" + System.nanoTime());
        u.setName("Hierarchy unit " + label);
        u.setParentId(parentId);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(u).getId();
    }

    private Long saveUser(String prefix) {
        long now = System.currentTimeMillis();
        User u = new User();
        u.setUsername(prefix + "-" + System.nanoTime());
        u.setFullName(prefix);
        u.setActive(true);
        u.setAuthType(UserAuthType.LOCAL);
        u.setPasswordHash("x");
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.saveAndFlush(u).getId();
    }

    private void linkSupervisor(Long userId, Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUserId(userId);
        link.setUnitId(unitId);
        unitSupervisorRepository.saveAndFlush(link);
    }

    private void linkOperator(Long userId, Long unitId) {
        UnitOperator link = new UnitOperator();
        link.setUserId(userId);
        link.setUnitId(unitId);
        unitOperatorRepository.saveAndFlush(link);
    }

    private Long savePendingSheet(Long unitId) {
        return saveSheet(unitId, LogSheetStatus.PENDING, null);
    }

    private Long saveAssignedSheet(Long unitId, Long assigneeUserId) {
        return saveSheet(unitId, LogSheetStatus.ASSIGNED, assigneeUserId);
    }

    private Long saveSheet(Long unitId, LogSheetStatus status, Long assigneeUserId) {
        long now = System.currentTimeMillis();
        LogSheet s = new LogSheet();
        s.setTemplateName("Hierarchy scope sheet");
        s.setOperationalUnitId(unitId);
        s.setStatus(status);
        s.setOrigin(GenerationMode.MANUAL);
        s.setAssigneeUserId(assigneeUserId);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return logSheetRepository.saveAndFlush(s).getId();
    }
}
