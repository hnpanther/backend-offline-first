package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.LogSheetScheduler;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the expiry scheduler does to a round whose deadline has passed.
 *
 * <p><b>Every one of them expires, and there is no longer a second outcome.</b> The job used to
 * branch on {@code draft_saved_at} and auto-submit a round that carried one, as though the clock
 * running out were somebody deciding the work was finished. That branch is gone, and these tests
 * are what stop it coming back by accident.
 *
 * <p>Three things follow, and each has a test below:
 *
 * <ul>
 *   <li><b>No readings are lost.</b> {@code log_sheet_entries} is untouched by expiry — only the
 *       sheet's status changes — so a round that was three-quarters walked keeps every value.
 *       Whether it counts as done becomes a supervisor's decision (extend, which reopens it with
 *       the values intact, then complete) rather than a scheduler's.</li>
 *   <li><b>A mobile round is not finalised behind the operator's back.</b> {@code draft_saved_at}
 *       now has two writers — the panel's save-draft and a tablet's progress push — so keeping
 *       the branch would have auto-submitted every round somebody was still walking the moment
 *       its deadline passed.</li>
 *   <li><b>{@code EXPIRED} is still not final.</b> This job races every tablet out of coverage
 *       and often wins, so a completion whose device time falls before {@code due_at} is still
 *       accepted afterwards.</li>
 * </ul>
 *
 * <p>The ownership-guard tests at the bottom survive the change and matter more than ever: the
 * atomic completion is what keeps a concurrent takeover from losing to a stale submit.
 */
@Transactional
class LogSheetExpiryFinalizeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetScheduler scheduler;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired UserRepository userRepository;

    private static final long HOUR = 3_600_000L;

    // ---------------------------------------------------------------- everything overdue expires

    @Test
    void aRoundCarryingASavedDraftExpiresRatherThanBeingAutoSubmitted() {
        LogSheet sheet = overdueSheet(user().getId(), true);

        scheduler.expireOverdueSheets();

        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
        assertThat(after.getCompletedAt()).isNull();
        assertThat(after.getCompletedByUserId()).isNull();
    }

    @Test
    void aRoundWithNothingRecordedExpiresTheSameWay() {
        LogSheet sheet = overdueSheet(user().getId(), false);

        scheduler.expireOverdueSheets();

        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
    }

    @Test
    void anUnassignedPoolSheetWithASavedDraftExpiresInsteadOfSittingInLimbo() {
        // The old defect this file was written for was a pool sheet that could be neither
        // finalised nor expired, so it was retried every sixty seconds forever. One branch means
        // that state cannot exist: PENDING is in OPEN_STATUSES and expiry has no ownership
        // precondition at all.
        LogSheet sheet = overdueSheet(null, true);

        scheduler.expireOverdueSheets();

        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
    }

    @Test
    void expiryLeavesTheDraftMarkerAndTheRecordedValuesWhereTheyAre() {
        // The point of the whole change: expiring a round is a statement about its deadline, not
        // about its data. `extend` reopens it with everything still in place.
        LogSheet sheet = overdueSheet(user().getId(), true);
        Long draftSavedAt = sheet.getDraftSavedAt();

        scheduler.expireOverdueSheets();

        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
        assertThat(after.getDraftSavedAt()).isEqualTo(draftSavedAt);
    }

    @Test
    void aRoundThatAlreadyReachedAFinalStateIsLeftAlone() {
        for (LogSheetStatus terminal : List.of(
                LogSheetStatus.SUBMITTED, LogSheetStatus.VOIDED, LogSheetStatus.CANCELLED)) {
            LogSheet sheet = overdueSheet(user().getId(), true);
            sheet.setStatus(terminal);
            logSheetRepository.saveAndFlush(sheet);

            scheduler.expireOverdueSheets();

            assertThat(reload(sheet).getStatus()).isEqualTo(terminal);
        }
    }

    @Test
    void theSchedulerLeavesARoundThatIsStillWithinItsDeadlineAlone() {
        LogSheet sheet = sheet(user().getId(), true, now() + HOUR);

        scheduler.expireOverdueSheets();

        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(after.getDraftSavedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- EXPIRED is not final

    @Test
    void anOnTimeCompletionStillWinsAfterTheSchedulerHasExpiredTheRound() {
        // The scenario the whole offline design exists for: finished at 17:55 against an 18:00
        // deadline, signal at 19:30. The scheduler got there first and that must not matter.
        User operator = user();
        LogSheet sheet = overdueSheet(operator.getId(), false);
        long completedInTime = sheet.getDueAt() - 60_000L;

        scheduler.expireOverdueSheets();
        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.EXPIRED);

        assertThat(submitAttempt(sheet, operator.getId(), operator.getId(), completedInTime))
                .isEqualTo(1);
        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
    }

    @Test
    void aLateCompletionIsStillRefusedAfterExpiry() {
        User operator = user();
        LogSheet sheet = overdueSheet(operator.getId(), false);
        long completedLate = sheet.getDueAt() + 60_000L;

        scheduler.expireOverdueSheets();

        assertThat(submitAttempt(sheet, operator.getId(), operator.getId(), completedLate)).isZero();
        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
    }

    // ---------------------------------------------------------------- the ownership guard

    @Test
    void aSheetTakenOverBySomebodyElseIsNotCompletedByTheOldAssignee() {
        User original = user();
        User newOwner = user();
        LogSheet sheet = sheet(original.getId(), true, now() + HOUR);
        sheet.setAssigneeUserId(newOwner.getId());
        logSheetRepository.saveAndFlush(sheet);

        assertThat(submitAttempt(sheet, original.getId(), original.getId(), now())).isZero();
    }

    @Test
    void theOrdinarySubmitPathIsUnaffected() {
        User operator = user();
        LogSheet sheet = sheet(operator.getId(), false, now() + HOUR);

        assertThat(submitAttempt(sheet, operator.getId(), operator.getId(), now())).isEqualTo(1);
    }

    @Test
    void aPlantWideCompleterMayFinishASheetTheyDoNotHold() {
        // expectedAssigneeUserId == null is the CAP:LOGSHEET_COMPLETE_WEB_ANY case. It was
        // previously entangled with the removed requireUnassigned flag, so it is pinned here.
        User operator = user();
        User admin = user();
        LogSheet sheet = sheet(operator.getId(), true, now() + HOUR);

        assertThat(submitAttempt(sheet, admin.getId(), null, now())).isEqualTo(1);
        assertThat(reload(sheet).getCompletedByUserId()).isEqualTo(admin.getId());
    }

    // ---------------------------------------------------------------- fixture

    private long now() {
        return System.currentTimeMillis();
    }

    private User user() {
        long now = now();
        User u = new User();
        u.setUsername("exp-" + UUID.randomUUID());
        u.setPersonnelCode("PC-" + UUID.randomUUID());
        u.setPasswordHash("{noop}x");
        u.setActive(true);
        u.setAuthType(UserAuthType.LOCAL);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.saveAndFlush(u);
    }

    private LogSheet overdueSheet(Long assigneeUserId, boolean draftSaved) {
        return sheet(assigneeUserId, draftSaved, now() - HOUR);
    }

    private LogSheet sheet(Long assigneeUserId, boolean draftSaved, long dueAt) {
        long now = now();
        LogSheet s = new LogSheet();
        s.setTemplateName("Expiry Sheet");
        s.setStatus(assigneeUserId != null ? LogSheetStatus.IN_PROGRESS : LogSheetStatus.PENDING);
        s.setOrigin(GenerationMode.MANUAL);
        s.setAssigneeUserId(assigneeUserId);
        s.setDueAt(dueAt);
        // Written by the panel's save-draft and, since V5, by a tablet's progress push. Its
        // presence no longer decides anything about expiry — which is what these tests pin.
        s.setDraftSavedAt(draftSaved ? dueAt - 60_000L : null);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return logSheetRepository.saveAndFlush(s);
    }

    private LogSheet reload(LogSheet sheet) {
        return logSheetRepository.findById(sheet.getId()).orElseThrow();
    }

    /** The atomic completion the service runs, called directly so a race can be staged exactly. */
    private int submitAttempt(LogSheet sheet, Long actorUserId, Long expectedAssigneeUserId,
                              long completedAt) {
        return logSheetRepository.submitIfStillCompletable(
                sheet.getId(), actorUserId, completedAt, now(), now(), null, null,
                LogSheetStatus.SUBMITTED,
                List.of(LogSheetStatus.PENDING, LogSheetStatus.ASSIGNED,
                        LogSheetStatus.IN_PROGRESS, LogSheetStatus.EXPIRED),
                expectedAssigneeUserId);
    }
}
