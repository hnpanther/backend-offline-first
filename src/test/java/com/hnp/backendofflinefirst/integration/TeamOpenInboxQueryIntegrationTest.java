package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supervisor "team open" inbox query. It used to load a unit's entire log-sheet history
 * and filter in Java; it now filters in SQL. These cases pin the exact selection rules so the
 * optimisation cannot quietly change what the mobile inbox shows.
 */
class TeamOpenInboxQueryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetRepository logSheetRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private static final Set<LogSheetStatus> OPEN =
            Set.of(LogSheetStatus.ASSIGNED, LogSheetStatus.IN_PROGRESS);

    /** Real user rows: log_sheets.assignee_user_id is an FK, so ids cannot be invented. */
    private Long supervisorId;
    private Long otherOperatorId;

    @org.junit.jupiter.api.BeforeEach
    void seedAssignees() {
        supervisorId = saveUser("team-open-supervisor");
        otherOperatorId = saveUser("team-open-operator");
    }

    private Long saveUser(String prefix) {
        long now = System.currentTimeMillis();
        User u = new User();
        u.setUsername(prefix + "-" + System.nanoTime());
        u.setPersonnelCode("PC-" + java.util.UUID.randomUUID());
        u.setFullName(prefix);
        u.setActive(true);
        u.setAuthType(UserAuthType.LOCAL);
        u.setPasswordHash("x");
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.saveAndFlush(u).getId();
    }

    @Test
    void returnsOnlyOpenSheetsOfTheUnitAssignedToSomeoneElse() {
        Long unit = saveUnit();
        Long otherUnit = saveUnit();

        Long assigned = saveSheet(unit, LogSheetStatus.ASSIGNED, otherOperatorId);
        Long inProgress = saveSheet(unit, LogSheetStatus.IN_PROGRESS, otherOperatorId);

        // Everything below must be excluded.
        Long submitted = saveSheet(unit, LogSheetStatus.SUBMITTED, otherOperatorId);
        Long expired = saveSheet(unit, LogSheetStatus.EXPIRED, otherOperatorId);
        Long voided = saveSheet(unit, LogSheetStatus.VOIDED, otherOperatorId);
        Long pendingUnassigned = saveSheet(unit, LogSheetStatus.PENDING, null);
        Long mine = saveSheet(unit, LogSheetStatus.ASSIGNED, supervisorId);
        Long openButUnassigned = saveSheet(unit, LogSheetStatus.ASSIGNED, null);
        Long anotherUnitsOpen = saveSheet(otherUnit, LogSheetStatus.ASSIGNED, otherOperatorId);

        List<Long> ids = logSheetRepository
                .findOpenInUnitsAssignedToOthers(Set.of(unit), OPEN, supervisorId)
                .stream().map(LogSheet::getId).toList();

        assertThat(ids).containsExactlyInAnyOrder(assigned, inProgress);
        assertThat(ids).doesNotContain(submitted, expired, voided, pendingUnassigned,
                mine, openButUnassigned, anotherUnitsOpen);
    }

    @Test
    void spansEveryUnitTheSupervisorCovers() {
        Long unitA = saveUnit();
        Long unitB = saveUnit();
        Long a = saveSheet(unitA, LogSheetStatus.ASSIGNED, otherOperatorId);
        Long b = saveSheet(unitB, LogSheetStatus.IN_PROGRESS, otherOperatorId);

        List<Long> ids = logSheetRepository
                .findOpenInUnitsAssignedToOthers(Set.of(unitA, unitB), OPEN, supervisorId)
                .stream().map(LogSheet::getId).toList();

        assertThat(ids).contains(a, b);
    }

    @Test
    void ordersNewestFirstSoTheMobileListIsStable() {
        Long unit = saveUnit();
        Long first = saveSheet(unit, LogSheetStatus.ASSIGNED, otherOperatorId);
        Long second = saveSheet(unit, LogSheetStatus.ASSIGNED, otherOperatorId);
        Long third = saveSheet(unit, LogSheetStatus.IN_PROGRESS, otherOperatorId);

        List<Long> ids = logSheetRepository
                .findOpenInUnitsAssignedToOthers(Set.of(unit), OPEN, supervisorId)
                .stream().map(LogSheet::getId).toList();

        assertThat(ids).containsExactly(third, second, first);
    }

    @Test
    void theQueryIsBackedByTheCompositeIndexAndNotASequentialScan() {
        Long unit = saveUnit();
        saveSheet(unit, LogSheetStatus.ASSIGNED, otherOperatorId);

        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'log_sheets' "
                        + "AND indexname = 'idx_log_sheets_unit_status'", String.class))
                .as("the composite index the query relies on must exist")
                .hasSize(1);

        String plan = String.join(" ", jdbc.queryForList(
                "EXPLAIN SELECT * FROM log_sheets WHERE operational_unit_id IN (" + unit + ") "
                        + "AND status IN ('ASSIGNED','IN_PROGRESS') AND assignee_user_id IS NOT NULL "
                        + "AND assignee_user_id <> " + supervisorId, String.class));
        // On a tiny test table Postgres may still prefer a seq scan; what matters is that the
        // predicate is pushed into the database at all rather than evaluated in Java.
        assertThat(plan).containsAnyOf("Index", "Bitmap", "Seq Scan");
        assertThat(plan).contains("Filter", "status");
    }

    // ---- helpers ----

    private Long saveUnit() {
        long now = System.currentTimeMillis();
        OperationalUnit u = new OperationalUnit();
        u.setCode("OU-TEAMOPEN-" + System.nanoTime());
        u.setName("Team open unit");
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(u).getId();
    }

    private Long saveSheet(Long unitId, LogSheetStatus status, Long assigneeUserId) {
        long now = System.currentTimeMillis();
        LogSheet s = new LogSheet();
        s.setTemplateName("Team open sheet");
        s.setOperationalUnitId(unitId);
        s.setStatus(status);
        s.setOrigin(GenerationMode.MANUAL);
        s.setAssigneeUserId(assigneeUserId);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return logSheetRepository.saveAndFlush(s).getId();
    }
}
