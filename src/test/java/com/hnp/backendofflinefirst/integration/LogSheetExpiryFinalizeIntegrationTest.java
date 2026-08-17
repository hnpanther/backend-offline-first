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
 * <p>There are two outcomes and one thing decides between them: {@code draft_saved_at}, which
 * only the web panel's "save draft" ever sets. Data was recorded → the round is auto-submitted as
 * the final record. Nothing was recorded → it expires, and the compliance report counts it as a
 * missed round.
 *
 * <p><b>The defect these were written for.</b> Auto-finalising demanded an assignee, because the
 * atomic update it runs through re-checks ownership to keep a concurrent takeover from losing to a
 * stale submit. A pool sheet has no assignee, so a draft saved against one — which somebody with
 * plant-wide completion rights can do — could never be finalised. The scheduler took the
 * "finalise" branch, failed, and skipped the expire branch: the row stayed {@code PENDING} with a
 * deadline in the past **forever**, retried every sixty seconds, and a round whose readings were
 * actually recorded was reported as missed.
 *
 * <p>The fix keeps the guard rather than removing it — it inverts it. Where there is no assignee,
 * the update requires the row to be <i>still</i> unassigned, so a claim that lands first wins.
 */
@Transactional
class LogSheetExpiryFinalizeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetScheduler scheduler;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired UserRepository userRepository;

    private static final long HOUR = 3_600_000L;

    // ---------------------------------------------------------------- finalise vs expire

    @Test
    void savedDraftOnAnAssignedSheetIsAutoSubmittedAtTheDeadline() {
        User operator = user();
        LogSheet sheet = overdueSheet(operator.getId(), true);

        assertThat(logSheetService.finalizeDraftOnExpiry(sheet.getId(), now())).isTrue();

        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(after.getCompletedByUserId()).isEqualTo(operator.getId());
    }

    @Test
    void theCompletionIsStampedAtTheDeadline_notAtTheMomentTheJobRan() {
        // The record has to say the round was complete when it was due. Stamping the scheduler's
        // own clock would put every auto-finalised sheet a minute or so past its deadline, which
        // then reads as late work in every report built on completed_at.
        LogSheet sheet = overdueSheet(user().getId(), true);
        long dueAt = sheet.getDueAt();

        logSheetService.finalizeDraftOnExpiry(sheet.getId(), now());

        assertThat(reload(sheet).getCompletedAt()).isEqualTo(dueAt);
    }

    @Test
    void theDraftMarkerIsClearedSoTheRoundIsNoLongerAPendingDraft() {
        LogSheet sheet = overdueSheet(user().getId(), true);

        logSheetService.finalizeDraftOnExpiry(sheet.getId(), now());

        assertThat(reload(sheet).getDraftSavedAt()).isNull();
    }

    @Test
    void savedDraftOnAnUnassignedPoolSheetIsAutoSubmittedToo() {
        // The defect. Before the fix this returned false and the sheet was left in limbo.
        LogSheet sheet = overdueSheet(null, true);

        assertThat(logSheetService.finalizeDraftOnExpiry(sheet.getId(), now())).isTrue();

        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        // Nobody to attribute it to, and that is honest: no operator completed this round.
        assertThat(after.getCompletedByUserId()).isNull();
    }

    @Test
    void aRoundWithNothingRecordedIsNotAutoSubmitted() {
        // No web draft means no data on the server. Submitting an empty round would invent a
        // completion that never happened; expiry is the truth, and the scheduler applies it.
        LogSheet sheet = overdueSheet(user().getId(), false);

        assertThat(logSheetService.finalizeDraftOnExpiry(sheet.getId(), now())).isFalse();
        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
    }

    @Test
    void aRoundThatAlreadyReachedAFinalStateIsLeftAlone() {
        for (LogSheetStatus terminal : List.of(
                LogSheetStatus.SUBMITTED, LogSheetStatus.VOIDED, LogSheetStatus.CANCELLED)) {
            LogSheet sheet = overdueSheet(user().getId(), true);
            sheet.setStatus(terminal);
            logSheetRepository.saveAndFlush(sheet);

            assertThat(logSheetService.finalizeDraftOnExpiry(sheet.getId(), now())).isFalse();
            assertThat(reload(sheet).getStatus()).isEqualTo(terminal);
        }
    }

    // ---------------------------------------------------------------- the ownership guards

    @Test
    void aPoolSheetClaimedFirstIsNotCompletedUnderNobody() {
        // The race the inverted guard exists for: the scheduler read an unassigned row, and an
        // operator claimed it before the update landed. Completing it anyway would erase a claim
        // that already happened and record the round as finished by nobody.
        User claimer = user();
        LogSheet sheet = overdueSheet(null, true);
        sheet.setAssigneeUserId(claimer.getId());
        logSheetRepository.saveAndFlush(sheet);

        int updated = submitAttempt(sheet, null, null, true);

        assertThat(updated).isZero();
        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isNotEqualTo(LogSheetStatus.SUBMITTED);
        // The claim stands, and the next tick finalises the round under its new owner.
        assertThat(after.getAssigneeUserId()).isEqualTo(claimer.getId());
        assertThat(logSheetService.finalizeDraftOnExpiry(sheet.getId(), now())).isTrue();
        assertThat(reload(sheet).getCompletedByUserId()).isEqualTo(claimer.getId());
    }

    @Test
    void aPoolSheetStillUnassignedIsCompleted() {
        LogSheet sheet = overdueSheet(null, true);

        assertThat(submitAttempt(sheet, null, null, true)).isEqualTo(1);
    }

    @Test
    void aSheetTakenOverBySomebodyElseIsNotCompletedByTheOldAssignee() {
        // The guard that already existed, pinned so the new parameter cannot weaken it.
        User original = user();
        User newOwner = user();
        LogSheet sheet = overdueSheet(original.getId(), true);
        sheet.setAssigneeUserId(newOwner.getId());
        logSheetRepository.saveAndFlush(sheet);

        assertThat(submitAttempt(sheet, original.getId(), original.getId(), false)).isZero();
    }

    @Test
    void theMobileAndWebPathsAreUnaffectedByTheNewGuard() {
        // Both pass requireUnassigned = false, and an assigned sheet must still complete normally
        // — the ordinary submit path, which is what everything else depends on.
        User operator = user();
        LogSheet sheet = overdueSheet(operator.getId(), false);

        assertThat(submitAttempt(sheet, operator.getId(), operator.getId(), false)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- through the scheduler

    @Test
    void theSchedulerAutoSubmitsARecordedRoundAndExpiresAnEmptyOne() {
        LogSheet recorded = overdueSheet(user().getId(), true);
        LogSheet empty = overdueSheet(user().getId(), false);

        scheduler.expireOverdueSheets();

        assertThat(reload(recorded).getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(reload(empty).getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
    }

    @Test
    void theSchedulerNoLongerLeavesAPoolSheetWithASavedDraftInLimbo() {
        // The regression in one line: this row used to survive every tick untouched.
        LogSheet sheet = overdueSheet(null, true);

        scheduler.expireOverdueSheets();

        assertThat(reload(sheet).getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
    }

    @Test
    void theSchedulerLeavesARoundThatIsStillWithinItsDeadlineAlone() {
        LogSheet sheet = sheet(user().getId(), true, now() + HOUR);

        scheduler.expireOverdueSheets();

        LogSheet after = reload(sheet);
        assertThat(after.getStatus()).isEqualTo(LogSheetStatus.IN_PROGRESS);
        assertThat(after.getDraftSavedAt()).isNotNull();
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
        // Only ever set by the web panel's save-draft. Its presence is the whole decision.
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
                              boolean requireUnassigned) {
        long completedAt = sheet.getDueAt();
        return logSheetRepository.submitIfStillCompletable(
                sheet.getId(), actorUserId, completedAt, now(), now(), null, null,
                LogSheetStatus.SUBMITTED,
                List.of(LogSheetStatus.PENDING, LogSheetStatus.ASSIGNED,
                        LogSheetStatus.IN_PROGRESS, LogSheetStatus.EXPIRED),
                expectedAssigneeUserId, requireUnassigned);
    }
}
