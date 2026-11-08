package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.UserUnitMembershipService;
import com.hnp.backendofflinefirst.service.UserUnitMembershipService.Membership;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which operational units a person belongs to, and in what role.
 *
 * <p>The panel could not answer this before: «واحد سازمانی» on the users page is a free-text HR
 * label, while the membership that actually decides what somebody may approve and which rounds
 * reach their inbox lived in two join tables, visible only by opening each unit in turn.
 *
 * <p>The cases worth pinning are the ones a naive join would get wrong — somebody holding
 * <b>both</b> roles in one unit, and somebody holding none.
 */
@Transactional
class UserUnitMembershipIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired UserUnitMembershipService service;
    @Autowired UserRepository userRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;

    @Test
    void bothRolesInOneUnitCollapseOntoOneLine() {
        User user = user("both");
        OperationalUnit unit = unit("Both Unit");
        supervise(user, unit);
        operate(user, unit);

        List<Membership> held = service.findForUser(user.getId());

        // One line, not two. A person who supervises a unit and also walks its rounds is one
        // relationship to that unit; showing it twice reads as a data error.
        assertThat(held).hasSize(1);
        assertThat(held.get(0).unitId()).isEqualTo(unit.getId());
        assertThat(held.get(0).supervisor()).isTrue();
        assertThat(held.get(0).operator()).isTrue();
        assertThat(held.get(0).roleLabel()).isEqualTo("سرپرست و اپراتور");
    }

    @Test
    void eachRoleOnItsOwnReadsCorrectly() {
        User supervisor = user("sup");
        User operator = user("op");
        OperationalUnit unit = unit("Single Role Unit");
        supervise(supervisor, unit);
        operate(operator, unit);

        assertThat(service.findForUser(supervisor.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.roleLabel()).isEqualTo("سرپرست");
                    assertThat(m.operator()).isFalse();
                });
        assertThat(service.findForUser(operator.getId()))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.roleLabel()).isEqualTo("اپراتور");
                    assertThat(m.supervisor()).isFalse();
                });
    }

    @Test
    void supervisedUnitsComeFirst() {
        User user = user("mixed");
        OperationalUnit operated = unit("AAA Operated");
        OperationalUnit supervised = unit("ZZZ Supervised");
        operate(user, operated);
        supervise(user, supervised);

        // Alphabetically the operated unit wins; by relevance the supervised one does, and that
        // is what somebody scanning this column is looking for.
        assertThat(service.findForUser(user.getId()))
                .extracting(Membership::unitLabel)
                .containsExactly("ZZZ Supervised", "AAA Operated");
    }

    @Test
    void everyUserAskedAboutIsAKeyEvenWhenTheyBelongNowhere() {
        User member = user("member");
        User loner = user("loner");
        supervise(member, unit("Some Unit"));

        Map<Long, List<Membership>> result =
                service.findForUsers(List.of(member.getId(), loner.getId()));

        // The template indexes this map by user id on every row. A missing key would be a null
        // index, which SpEL throws on rather than yielding null (AGENTS.md).
        assertThat(result).containsKeys(member.getId(), loner.getId());
        assertThat(result.get(loner.getId())).isEmpty();
        assertThat(result.get(member.getId())).hasSize(1);
    }

    @Test
    void anEmptyRequestAsksTheDatabaseNothing() {
        assertThat(service.findForUsers(List.of())).isEmpty();
        assertThat(service.findForUsers(null)).isEmpty();
        // A null id in the list is skipped rather than becoming a key nothing can match.
        assertThat(service.findForUsers(java.util.Arrays.asList((Long) null))).isEmpty();
    }

    @Test
    void theUnitIsNamed() {
        User user = user("named");
        OperationalUnit unit = unit("واحد برق");
        supervise(user, unit);

        assertThat(service.findForUser(user.getId()).get(0).unitLabel()).isEqualTo("واحد برق");
    }

    // ------------------------------------------------------------------ fixtures

    private User user(String prefix) {
        long nano = System.nanoTime();
        User user = new User();
        user.setUsername(prefix + "-" + nano);
        user.setPersonnelCode("PC-" + prefix + "-" + nano);
        user.setFullName(prefix);
        user.setPasswordHash("x");
        user.setActive(true);
        user.setCreatedAt(System.currentTimeMillis());
        user.setUpdatedAt(System.currentTimeMillis());
        return userRepository.saveAndFlush(user);
    }

    private OperationalUnit unit(String name) {
        long now = System.currentTimeMillis();
        OperationalUnit unit = new OperationalUnit();
        unit.setCode("UM-" + System.nanoTime());
        unit.setName(name);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(unit);
    }

    private void supervise(User user, OperationalUnit unit) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUserId(user.getId());
        link.setUnitId(unit.getId());
        unitSupervisorRepository.saveAndFlush(link);
    }

    private void operate(User user, OperationalUnit unit) {
        UnitOperator link = new UnitOperator();
        link.setUserId(user.getId());
        link.setUnitId(unit.getId());
        unitOperatorRepository.saveAndFlush(link);
    }
}
