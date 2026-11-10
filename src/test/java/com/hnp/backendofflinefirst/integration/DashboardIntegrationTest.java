package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.DashboardPeriod;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.DashboardRows.PeriodSummary;
import com.hnp.backendofflinefirst.dto.DashboardRows.UnitPeriodRow;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.DashboardService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.util.DateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The landing dashboard's numbers, and the scope that decides whose they are.
 *
 * <h2>Why this is an integration test and not a unit test</h2>
 *
 * <p>Everything it checks lives in SQL or in the gap between SQL and the scope resolver:
 * whether the half-open bound actually excludes the round raised at midnight, whether an
 * administrator's {@code null} scope reaches the query as "no filter" rather than as "no units",
 * whether a supervisor's row set stops at their own branch. A mock of the repository would
 * confirm the service calls it and prove nothing about any of those.
 *
 * <p><b>Written with a scoped principal first.</b> An administrator passes almost any scope bug,
 * because their scope is everything — {@code docs/reports.md} says this in its own checklist for
 * adding a report, and it is the reason the data-quality report shipped returning nothing at all
 * for administrators.
 */
class DashboardIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired DashboardService dashboardService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UserRepository userRepository;

    /** Fixed so the window is the same in every assertion, whatever the clock does mid-run. */
    private long now;
    private DashboardPeriod.Window today;

    private OperationalUnit unitA;
    private OperationalUnit unitB;
    private User supervisorOfA;
    private User orphan;

    /** Every round this test raised, so {@link #removeFixturesAndClearContext()} can undo it. */
    private final List<LogSheet> raised = new ArrayList<>();

    @BeforeEach
    void seed() {
        now = System.currentTimeMillis();
        today = DashboardPeriod.DAY.window(DateUtils.TEHRAN, now);

        long tag = System.nanoTime();
        unitA = unit("DSH-OU-A-" + tag);
        unitB = unit("DSH-OU-B-" + tag);

        supervisorOfA = user("dsh-sup-" + tag);
        UnitSupervisor link = new UnitSupervisor();
        link.setUnitId(unitA.getId());
        link.setUserId(supervisorOfA.getId());
        unitSupervisorRepository.save(link);

        orphan = user("dsh-orphan-" + tag);
    }

    /**
     * Removes the rounds this test raised, and that is not housekeeping — it is required.
     *
     * <p>Every integration test in this suite shares one PostgreSQL container, so a fixture row
     * is visible to every test that runs afterwards. These fixtures are unusually loud about it:
     * a `CANCELLED` round makes `LogSheetListFilterIntegrationTest`'s «no rows» case find one,
     * and a `DANGER` entry with no asset and no class reaches `/reports/exceptions`, which is
     * built to describe a real reading. Both failures land in another file and blame it.
     */
    @AfterEach
    void removeFixturesAndClearContext() {
        SecurityContextHolder.clearContext();
        for (LogSheet sheet : raised) {
            logSheetEntryRepository.deleteAll(logSheetEntryRepository.findByLogSheetId(sheet.getId()));
            logSheetRepository.deleteById(sheet.getId());
        }
        raised.clear();
    }

    // ── scope ─────────────────────────────────────────────────────────────────

    @Test
    void aSupervisorSeesTheirOwnUnitAndNotTheOneBesideIt() {
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        sheet(unitB, LogSheetStatus.SUBMITTED, insideToday());

        asSupervisorOfA();
        PeriodSummary summary = dashboardService.summarise(
                DashboardPeriod.DAY, DateUtils.TEHRAN, now);

        assertThat(summary.rows()).extracting(UnitPeriodRow::unitId).containsExactly(unitA.getId());
        assertThat(summary.total().generated())
                .as("the other unit's round must not reach the totals either — a KPI card is the "
                        + "one place a leak would be read as the plant's own figure")
                .isEqualTo(1);
    }

    @Test
    void anUnrestrictedAdministratorSeesEveryUnitRatherThanAnEmptyPage() {
        // `visibleUnitIds()` answers null for them, and a query that read null as "no units"
        // would show the person with the most access the least data, with no error anywhere.
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        sheet(unitB, LogSheetStatus.SUBMITTED, insideToday());

        asPlantWideViewer();
        PeriodSummary summary = dashboardService.summarise(
                DashboardPeriod.DAY, DateUtils.TEHRAN, now);

        assertThat(summary.rows()).extracting(UnitPeriodRow::unitId)
                .contains(unitA.getId(), unitB.getId());
        assertThat(summary.unitsInScope())
                .as("every unit of the plant, which is the denominator behind «N raised nothing»")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void aScopedUserWithNoUnitAtAllGetsAnEmptyPageAndNoFailure() {
        // Short-circuited before SQL: an empty collection in `IN (:unitIds)` is a syntax error,
        // and a query built from an empty list would otherwise be unfiltered — fail open.
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        authenticate(orphan, Set.of());

        PeriodSummary summary = dashboardService.summarise(
                DashboardPeriod.DAY, DateUtils.TEHRAN, now);

        assertThat(summary.rows()).isEmpty();
        assertThat(summary.total().generated()).isZero();
        assertThat(summary.unitsInScope()).isZero();
        assertThat(summary.unitsWithoutRounds()).isZero();
    }

    @Test
    void aRoundBelongingToNoUnitIsVisibleOnlyToAPlantWideViewer() {
        // `NULL IN (…)` is never true, so a scoped query drops it silently. An administrator is
        // the only person who can see it at all, which is why the page says so out loud.
        sheet(null, LogSheetStatus.PENDING, insideToday());

        asSupervisorOfA();
        assertThat(dashboardService.summarise(DashboardPeriod.DAY, DateUtils.TEHRAN, now)
                .unassignedRounds()).isFalse();

        asPlantWideViewer();
        assertThat(dashboardService.summarise(DashboardPeriod.DAY, DateUtils.TEHRAN, now)
                .unassignedRounds()).isTrue();
    }

    // ── the window ────────────────────────────────────────────────────────────

    @Test
    void theRangeIsHalfOpenAtBothEnds() {
        // The two boundary rounds. `from` is in, `to` is out — otherwise today's dashboard and
        // tomorrow's would both claim the round raised exactly at midnight.
        sheet(unitA, LogSheetStatus.PENDING, today.fromAt());
        sheet(unitA, LogSheetStatus.PENDING, today.toAt());

        asSupervisorOfA();
        assertThat(rowFor(unitA, DashboardPeriod.DAY).generated())
                .as("the round at `from` counts, the one at `to` does not")
                .isEqualTo(1);
    }

    @Test
    void aLongerPeriodReachesBackFurtherAndAShorterOneDoesNot() {
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        sheet(unitA, LogSheetStatus.SUBMITTED, daysAgo(10));

        asSupervisorOfA();
        assertThat(rowFor(unitA, DashboardPeriod.DAY).generated()).isEqualTo(1);
        assertThat(rowFor(unitA, DashboardPeriod.WEEK).generated()).isEqualTo(1);
        assertThat(rowFor(unitA, DashboardPeriod.MONTH).generated()).isEqualTo(2);
    }

    @Test
    void theWindowIsOnWhenTheRoundWasRaisedNotWhenItWasFinished() {
        // A round raised 10 days ago and completed today belongs to the period it was owed in.
        // Windowing on completion instead inflates today and hollows out the week the work was
        // actually due — the reasoning docs/reports.md records for the compliance report.
        LogSheet backlog = sheet(unitA, LogSheetStatus.SUBMITTED, daysAgo(10));
        backlog.setCompletedAt(insideToday());
        backlog.setSubmittedAt(insideToday());
        logSheetRepository.save(backlog);

        asSupervisorOfA();
        assertThat(maybeRowFor(unitA, DashboardPeriod.DAY))
                .as("finishing a backlog item today does not make it today's work, so the unit "
                        + "has no row at all in today's window")
                .isEmpty();
        assertThat(rowFor(unitA, DashboardPeriod.MONTH).generated()).isEqualTo(1);
    }

    // ── the counters ──────────────────────────────────────────────────────────

    @Test
    void anApprovedRoundIsACompletedRoundAndAlsoItsOwnColumn() {
        // The silent failure this guards: counting only SUBMITTED makes a unit's completion rate
        // fall as its work gets reviewed, with no error and no obvious cause.
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        sheet(unitA, LogSheetStatus.APPROVED, insideToday());

        asSupervisorOfA();
        UnitPeriodRow row = rowFor(unitA, DashboardPeriod.DAY);

        assertThat(row.completed()).isEqualTo(2);
        assertThat(row.approved()).isEqualTo(1);
        assertThat(row.awaitingApproval()).isEqualTo(1);
        assertThat(row.completionRate()).isEqualTo(100d);
    }

    @Test
    void everyLifecycleStateLandsInExactlyOneColumnAndTheRowAddsUp() {
        sheet(unitA, LogSheetStatus.PENDING, insideToday());
        sheet(unitA, LogSheetStatus.ASSIGNED, insideToday());
        sheet(unitA, LogSheetStatus.IN_PROGRESS, insideToday());
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        sheet(unitA, LogSheetStatus.APPROVED, insideToday());
        sheet(unitA, LogSheetStatus.EXPIRED, insideToday());
        sheet(unitA, LogSheetStatus.CANCELLED, insideToday());
        sheet(unitA, LogSheetStatus.VOIDED, insideToday());

        asSupervisorOfA();
        UnitPeriodRow row = rowFor(unitA, DashboardPeriod.DAY);

        assertThat(row.generated()).isEqualTo(8);
        assertThat(row.open()).as("PENDING + ASSIGNED + IN_PROGRESS").isEqualTo(3);
        assertThat(row.completed()).isEqualTo(2);
        assertThat(row.expired()).isEqualTo(1);
        assertThat(row.cancelled()).isEqualTo(1);
        assertThat(row.voided()).isEqualTo(1);
        assertThat(row.completed() + row.open() + row.expired() + row.cancelled() + row.voided())
                .as("no round counted twice and none missing — the property that makes a row "
                        + "readable as a census rather than as eight unrelated numbers")
                .isEqualTo(row.generated());
    }

    @Test
    void overdueCountsOnlyOpenRoundsWhoseDeadlineHasAlreadyPassed() {
        LogSheet late = sheet(unitA, LogSheetStatus.ASSIGNED, insideToday());
        late.setDueAt(now - Duration.ofHours(1).toMillis());
        logSheetRepository.save(late);

        LogSheet stillDue = sheet(unitA, LogSheetStatus.ASSIGNED, insideToday());
        stillDue.setDueAt(now + Duration.ofHours(1).toMillis());
        logSheetRepository.save(stillDue);

        LogSheet noDeadline = sheet(unitA, LogSheetStatus.ASSIGNED, insideToday());
        noDeadline.setDueAt(null);
        logSheetRepository.save(noDeadline);

        // A deadline that passed on a round somebody already delivered is not open work.
        LogSheet finishedLate = sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        finishedLate.setDueAt(now - Duration.ofHours(1).toMillis());
        logSheetRepository.save(finishedLate);

        asSupervisorOfA();
        UnitPeriodRow row = rowFor(unitA, DashboardPeriod.DAY);

        assertThat(row.open()).isEqualTo(3);
        assertThat(row.overdueOpen())
                .as("a round with no deadline cannot be late, and a delivered one is not open")
                .isEqualTo(1);
    }

    @Test
    void readingsAreCountedAgainstTheUnitWhoseRoundTheyWereTakenOn() {
        LogSheet aSheet = sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        breach(aSheet, "DANGER");
        breach(aSheet, "WARNING");
        breach(aSheet, "WARNING");
        breach(aSheet, "OK");

        LogSheet bSheet = sheet(unitB, LogSheetStatus.APPROVED, insideToday());
        breach(bSheet, "DANGER");

        asPlantWideViewer();
        assertThat(rowFor(unitA, DashboardPeriod.DAY).dangerReadings()).isEqualTo(1);
        assertThat(rowFor(unitA, DashboardPeriod.DAY).warningReadings())
                .as("an OK reading is not a breach")
                .isEqualTo(2);
        assertThat(rowFor(unitB, DashboardPeriod.DAY).dangerReadings())
                .as("an approved round's readings are as real as an unapproved one's")
                .isEqualTo(1);

        asSupervisorOfA();
        assertThat(dashboardService.summarise(DashboardPeriod.DAY, DateUtils.TEHRAN, now)
                .total().dangerReadings())
                .as("the supervisor of A must not be shown B's danger reading")
                .isEqualTo(1);
    }

    @Test
    void aBreachOnARoundNobodyDeliveredIsNotCountedYet() {
        LogSheet open = sheet(unitA, LogSheetStatus.IN_PROGRESS, insideToday());
        breach(open, "DANGER");

        asSupervisorOfA();
        assertThat(rowFor(unitA, DashboardPeriod.DAY).dangerReadings()).isZero();
    }

    @Test
    void theTotalsLineIsTheSumOfTheRowsItSitsUnder() {
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());
        sheet(unitA, LogSheetStatus.PENDING, insideToday());
        sheet(unitB, LogSheetStatus.APPROVED, insideToday());

        asPlantWideViewer();
        PeriodSummary summary = dashboardService.summarise(
                DashboardPeriod.DAY, DateUtils.TEHRAN, now);

        long generated = summary.rows().stream().mapToLong(UnitPeriodRow::generated).sum();
        assertThat(summary.total().generated()).isEqualTo(generated);
        assertThat(summary.total().completed()).isEqualTo(
                summary.rows().stream().mapToLong(UnitPeriodRow::completed).sum());
    }

    @Test
    void aUnitThatRaisedNothingIsCountedRatherThanSilentlyAbsent() {
        // Unit B has no round in the window. It gets no row — a plant with hundreds of units
        // would bury the ones that worked — but the page must still be able to say so.
        sheet(unitA, LogSheetStatus.SUBMITTED, insideToday());

        asPlantWideViewer();
        PeriodSummary summary = dashboardService.summarise(
                DashboardPeriod.DAY, DateUtils.TEHRAN, now);

        assertThat(summary.rows()).extracting(UnitPeriodRow::unitId).doesNotContain(unitB.getId());
        assertThat(summary.unitsWithoutRounds())
                .as("unit B is in scope, raised nothing, and must still be accounted for")
                .isPositive();
        assertThat(summary.unitsWithRounds())
                .isEqualTo(summary.rows().stream().filter(r -> r.unitId() != null).count());
    }

    @Test
    void theBusiestUnitLeadsTheTable() {
        sheet(unitA, LogSheetStatus.PENDING, insideToday());
        sheet(unitB, LogSheetStatus.PENDING, insideToday());
        sheet(unitB, LogSheetStatus.PENDING, insideToday());

        asPlantWideViewer();
        List<Long> order = dashboardService.summarise(DashboardPeriod.DAY, DateUtils.TEHRAN, now)
                .rows().stream().map(UnitPeriodRow::unitId).toList();

        assertThat(order.indexOf(unitB.getId())).isLessThan(order.indexOf(unitA.getId()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UnitPeriodRow rowFor(OperationalUnit unit, DashboardPeriod period) {
        Optional<UnitPeriodRow> row = maybeRowFor(unit, period);
        assertThat(row).as("no row for unit %s in the %s window", unit.getCode(), period).isPresent();
        return row.get();
    }

    /**
     * The unit's row, or empty when it raised nothing in the window.
     *
     * <p>Absence is a real outcome here rather than a failure — a unit with no round in the
     * period is deliberately not given a row — so the two cases need two helpers.
     */
    private Optional<UnitPeriodRow> maybeRowFor(OperationalUnit unit, DashboardPeriod period) {
        return dashboardService.summarise(period, DateUtils.TEHRAN, now).rows().stream()
                .filter(r -> unit.getId().equals(r.unitId()))
                .findFirst();
    }

    /** An instant inside today's window whatever the hour — the window's own lower bound. */
    private long insideToday() {
        return today.fromAt();
    }

    private long daysAgo(int days) {
        return today.fromAt() - Duration.ofDays(days).toMillis();
    }

    private LogSheet sheet(OperationalUnit unit, LogSheetStatus status, long createdAt) {
        LogSheet s = new LogSheet();
        s.setTemplateName("Dashboard fixture");
        s.setOperationalUnitId(unit == null ? null : unit.getId());
        s.setStatus(status);
        s.setOrigin(GenerationMode.MANUAL);
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(createdAt);
        if (status.isCompleted() || status == LogSheetStatus.VOIDED) {
            s.setCompletedAt(createdAt);
            s.setSubmittedAt(createdAt);
        }
        LogSheet saved = logSheetRepository.save(s);
        raised.add(saved);
        return saved;
    }

    /**
     * A reading with its severity stamped the way the write paths stamp it.
     *
     * <p>{@code breached_fields} is set alongside {@code max_severity} rather than left null,
     * because {@code EntrySeverityEvaluator} never writes one without the other — and a reading
     * that says DANGER while naming no offending key is a row `/reports/exceptions` cannot
     * describe.
     */
    private void breach(LogSheet sheet, String severity) {
        LogSheetEntry e = new LogSheetEntry();
        e.setLogSheetId(sheet.getId());
        e.setFormData(Map.of("temp", "99"));
        e.setMaxSeverity(severity);
        if (!"OK".equals(severity)) {
            e.setBreachedFields(List.of("temp"));
        }
        e.setCreatedAt(sheet.getCreatedAt());
        e.setUpdatedAt(sheet.getCreatedAt());
        logSheetEntryRepository.save(e);
    }

    private OperationalUnit unit(String code) {
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(code);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.save(unit);
    }

    private User user(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPersonnelCode("PC-" + username);
        u.setPasswordHash("x");
        u.setActive(true);
        return userRepository.save(u);
    }

    private void asSupervisorOfA() {
        authenticate(supervisorOfA, Set.of());
    }

    /**
     * A plant-wide viewer, built the way one is built in production: by the capability, under a
     * role code nothing in the application reads. Naming the role "ADMIN" would prove less —
     * access is decided from {@code CAP:SCOPE_PLANT_WIDE}, and a hand-built principal that was
     * merely called an admin would have no capabilities at all (AGENTS.md #125).
     */
    private void asPlantWideViewer() {
        authenticate(supervisorOfA, Set.of(Capabilities.SCOPE_PLANT_WIDE));
    }

    private void authenticate(User user, Set<String> authorities) {
        AppUserDetails principal = new AppUserDetails(user, Set.of("SOME_ROLE"), authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
    }
}
