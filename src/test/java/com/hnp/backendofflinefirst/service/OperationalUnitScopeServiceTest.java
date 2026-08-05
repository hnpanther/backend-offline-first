package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The unit-hierarchy rule, in one place.
 *
 * <p>Fixture used throughout: <b>A</b> is the parent of <b>B</b> and <b>C</b>; <b>D</b> sits
 * under B; <b>Z</b> is unrelated.
 *
 * <pre>
 *        A
 *       / \
 *      B   C          Z
 *      |
 *      D
 * </pre>
 *
 * <p>Supervising A covers A, B, C and D. Operating A covers <em>only</em> A — operators of A and
 * operators of B are different teams that merely share a manager.
 */
@ExtendWith(MockitoExtension.class)
class OperationalUnitScopeServiceTest {

    @Mock UnitSupervisorRepository unitSupervisorRepository;
    @Mock UnitOperatorRepository unitOperatorRepository;
    @Mock OperationalUnitRepository operationalUnitRepository;

    @InjectMocks OperationalUnitScopeService scopeService;

    private static final long A = 1L, B = 2L, C = 3L, D = 4L, Z = 9L;

    private void seedHierarchy() {
        lenient().when(operationalUnitRepository.findAll())
                .thenReturn(List.of(unit(A, null), unit(B, A), unit(C, A), unit(D, B), unit(Z, null)));
    }

    private static OperationalUnit unit(long id, Long parentId) {
        OperationalUnit u = new OperationalUnit();
        u.setId(id);
        u.setParentId(parentId);
        return u;
    }

    private void asSupervisorOf(long userId, long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUserId(userId);
        link.setUnitId(unitId);
        lenient().when(unitSupervisorRepository.findByUserId(userId)).thenReturn(List.of(link));
        lenient().when(unitOperatorRepository.findByUserId(userId)).thenReturn(List.of());
    }

    private void asOperatorOf(long userId, long unitId) {
        UnitOperator link = new UnitOperator();
        link.setUserId(userId);
        link.setUnitId(unitId);
        lenient().when(unitOperatorRepository.findByUserId(userId)).thenReturn(List.of(link));
        lenient().when(unitSupervisorRepository.findByUserId(userId)).thenReturn(List.of());
    }

    // ── supervision cascades down ────────────────────────────────────────────────

    @Test
    void supervisingAParentCoversEveryDescendantIncludingGrandchildren() {
        seedHierarchy();
        asSupervisorOf(100L, A);

        assertThat(scopeService.getSupervisorScopeUnitIds(100L))
                .containsExactlyInAnyOrder(A, B, C, D);
        assertThat(scopeService.isSupervisorOf(100L, A)).isTrue();
        assertThat(scopeService.isSupervisorOf(100L, B)).isTrue();
        assertThat(scopeService.isSupervisorOf(100L, C)).isTrue();
        assertThat(scopeService.isSupervisorOf(100L, D)).as("grandchild under B").isTrue();
        assertThat(scopeService.isSupervisorOf(100L, Z)).as("unrelated branch").isFalse();
    }

    @Test
    void supervisingAChildDoesNotReachUpwardsToTheParentOrSiblings() {
        seedHierarchy();
        asSupervisorOf(101L, B);

        assertThat(scopeService.getSupervisorScopeUnitIds(101L)).containsExactlyInAnyOrder(B, D);
        assertThat(scopeService.isSupervisorOf(101L, A)).as("no upward inheritance").isFalse();
        assertThat(scopeService.isSupervisorOf(101L, C)).as("no sibling access").isFalse();
    }

    // ── operation does NOT cascade ───────────────────────────────────────────────

    @Test
    void operatingAParentDoesNotMakeSomeoneAnOperatorOfItsChildren() {
        // The core rule: operators of A must never reach the work of operators in B or C.
        seedHierarchy();
        asOperatorOf(200L, A);

        assertThat(scopeService.isOperatorOf(200L, A)).isTrue();
        assertThat(scopeService.isOperatorOf(200L, B)).isFalse();
        assertThat(scopeService.isOperatorOf(200L, C)).isFalse();
        assertThat(scopeService.isOperatorOf(200L, D)).isFalse();
    }

    @Test
    void anOperatorOfAParentSeesOnlyThatUnitAsAccessible() {
        // getAccessibleUnitIds drives the claimable pool, sheet visibility and master-data
        // scoping — expanding it for an operator would leak the sub-units' work.
        seedHierarchy();
        asOperatorOf(201L, A);

        assertThat(scopeService.getAccessibleUnitIds(201L)).containsExactly(A);
        assertThat(scopeService.canAccessUnit(201L, A)).isTrue();
        assertThat(scopeService.canAccessUnit(201L, B)).isFalse();
        assertThat(scopeService.canAccessUnit(201L, C)).isFalse();
    }

    // ── the two combined ─────────────────────────────────────────────────────────

    @Test
    void aSupervisorReachesTheWholeBranchThroughAccessibleUnitsToo() {
        seedHierarchy();
        asSupervisorOf(300L, A);

        assertThat(scopeService.getAccessibleUnitIds(300L)).containsExactlyInAnyOrder(A, B, C, D);
        assertThat(scopeService.canAccessUnit(300L, D)).isTrue();
    }

    @Test
    void supervisingOneBranchWhileOperatingAnotherKeepsTheTwoRulesSeparate() {
        seedHierarchy();
        UnitSupervisor sup = new UnitSupervisor();
        sup.setUserId(400L);
        sup.setUnitId(B);              // supervises B (and therefore D)
        UnitOperator op = new UnitOperator();
        op.setUserId(400L);
        op.setUnitId(C);               // but merely operates C
        when(unitSupervisorRepository.findByUserId(400L)).thenReturn(List.of(sup));
        when(unitOperatorRepository.findByUserId(400L)).thenReturn(List.of(op));

        assertThat(scopeService.getSupervisorScopeUnitIds(400L)).containsExactlyInAnyOrder(B, D);
        assertThat(scopeService.getAccessibleUnitIds(400L)).containsExactlyInAnyOrder(B, D, C);
        assertThat(scopeService.isSupervisorOf(400L, C)).as("operating C is not supervising it").isFalse();
        assertThat(scopeService.isOperatorOf(400L, D)).as("supervising D is not operating it").isFalse();
    }

    @Test
    void aUserWithNoUnitsReachesNothing() {
        when(unitSupervisorRepository.findByUserId(500L)).thenReturn(List.of());
        when(unitOperatorRepository.findByUserId(500L)).thenReturn(List.of());

        assertThat(scopeService.getAccessibleUnitIds(500L)).isEmpty();
        assertThat(scopeService.getSupervisorScopeUnitIds(500L)).isEmpty();
        assertThat(scopeService.canAccessUnit(500L, A)).isFalse();
    }

    @Test
    void operatorIsNotSupervisorAndViceVersa() {
        seedHierarchy();
        asOperatorOf(600L, A);

        assertThat(scopeService.isOperatorOf(600L, A)).isTrue();
        assertThat(scopeService.isSupervisorOf(600L, A)).isFalse();
    }

    @Test
    void nullArgumentsAreRejectedRatherThanTreatedAsWildcards() {
        assertThat(scopeService.isSupervisorOf(null, A)).isFalse();
        assertThat(scopeService.isSupervisorOf(100L, null)).isFalse();
        assertThat(scopeService.isOperatorOf(null, A)).isFalse();
        assertThat(scopeService.isOperatorOf(100L, null)).isFalse();
        assertThat(scopeService.canAccessUnit(100L, null)).isFalse();
    }

    @Test
    void directlyAssignedUnitsAreReportedWithoutAnyExpansion() {
        seedHierarchy();
        asSupervisorOf(700L, A);

        assertThat(scopeService.getSupervisedUnitIds(700L)).containsExactly(A);
        assertThat(scopeService.getAssignedUnitIds(700L)).containsExactly(A);
    }

    @Test
    void primaryUnitPrefersASupervisedUnitOverAnOperatedOne() {
        UnitSupervisor sup = new UnitSupervisor();
        sup.setUserId(800L);
        sup.setUnitId(A);
        UnitOperator op = new UnitOperator();
        op.setUserId(800L);
        op.setUnitId(C);
        when(unitSupervisorRepository.findByUserId(800L)).thenReturn(List.of(sup));
        lenient().when(unitOperatorRepository.findByUserId(800L)).thenReturn(List.of(op));

        assertThat(scopeService.getPrimaryUnitId(800L)).isEqualTo(A);
    }

    @Test
    void deepChainsAreFullyExpandedForSupervisors() {
        // A → B → D, plus a fourth level to prove the closure is not depth-limited.
        long e = 5L;
        lenient().when(operationalUnitRepository.findAll())
                .thenReturn(List.of(unit(A, null), unit(B, A), unit(D, B), unit(e, D)));
        asSupervisorOf(900L, A);

        assertThat(scopeService.getSupervisorScopeUnitIds(900L)).containsExactlyInAnyOrder(A, B, D, e);
    }
}
