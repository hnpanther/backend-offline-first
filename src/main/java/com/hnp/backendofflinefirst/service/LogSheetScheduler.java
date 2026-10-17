package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Periodic driver for the log-sheet lifecycle:
 *  - generates sheets from scheduled templates that are due
 *  - expires sheets whose completion window has passed (atomic conditional update)
 * Runs on a single backend instance (offline-first deployment); per-template
 * next_run_at advancement acts as the concurrency guard for generation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LogSheetScheduler {

    private final LogSheetTemplateRepository templateRepository;
    private final LogSheetRepository logSheetRepository;
    private final LogSheetGenerationService generationService;
    private final LogSheetService logSheetService;
    private final BusinessEventLogger businessEventLogger;

    private static final List<LogSheetStatus> OPEN_STATUSES =
            List.of(LogSheetStatus.PENDING, LogSheetStatus.ASSIGNED, LogSheetStatus.IN_PROGRESS);

    /**
     * How many missed occurrences one tick may materialise, per template.
     *
     * <p>The fallback is <b>0</b>, and it matters that it matches the value in
     * {@code application.properties} rather than being a number of its own. It used to be 500: a
     * harmless-looking default that only ever applied when the property was missing — and the
     * moment it did apply would be the worst one, because the case that makes it apply is a
     * deployment whose configuration was trimmed, and the case that makes it <em>visible</em> is
     * the first tick after a long outage, which would then fill every operator's pool with up to
     * 500 back-dated rounds per template. A default that is only reachable by accident should do
     * the safe thing.
     */
    @Value("${app.scheduler.log-sheet-max-backfill:0}")
    private int maxBackfill;

    @Scheduled(fixedDelayString = "${app.scheduler.log-sheet-gen-ms:60000}")
    public void generateDueSheets() {
        long now = System.currentTimeMillis();
        List<LogSheetTemplate> due = templateRepository
                .findByGenerationModeAndScheduleActiveTrueAndNextRunAtLessThanEqual(GenerationMode.SCHEDULED, now);
        for (LogSheetTemplate template : due) {
            if (Boolean.FALSE.equals(template.getActive())) {
                continue;
            }
            try {
                generationService.runScheduled(template, now, maxBackfill);
            } catch (TemplateCursorMovedException e) {
                // Not a fault: another writer advanced this template's cursor while we were
                // generating, so our transaction rolled back and nothing was created. Two
                // instances against one database is the usual cause and is forbidden — see
                // docs/deployment.md — but an administrator editing the schedule mid-tick lands
                // here too, and wins, which is correct. WARN rather than ERROR so that a log
                // somebody reads keeps meaning something.
                log.warn("Template {} was advanced by another writer during this tick; nothing was "
                        + "created. If this repeats, more than one instance is running against "
                        + "this database — see docs/deployment.md.", template.getId());
            } catch (Exception e) {
                log.error("Scheduled generation failed for template {}: {}", template.getId(), e.getMessage(), e);
                businessEventLogger.error("SCHEDULER_GENERATE", "templateId=" + template.getId(), e);
            }
        }
        if (!due.isEmpty()) {
            businessEventLogger.schedulerRun("log-sheet-generate", due.size());
        }
    }

    /**
     * Closes rounds whose deadline has passed. <b>Every one of them expires.</b>
     *
     * <p>This used to branch: a sheet carrying {@code draft_saved_at} was auto-submitted as its
     * own final record, and only a sheet with nothing recorded expired. That branch has been
     * removed on the plant's instruction, and the reasoning is worth keeping because the change
     * looks like data loss and is not.
     *
     * <p><b>Nothing is discarded.</b> The readings stay exactly where they were written, in
     * {@code log_sheet_entries}; only the sheet's own status differs. What changes is who decides
     * that a partial round counts as done — the scheduler used to, silently, at the moment a
     * clock ran out, and now a supervisor does, by extending the deadline (which reopens the
     * sheet with its values intact) and completing it. The list page shows «N/M» recorded on every
     * row, expired ones included, so a round that was three-quarters walked is visible rather than
     * inferred.
     *
     * <p><b>It also had to go for progress sync to be safe.</b> {@code draft_saved_at} now has two
     * writers: the panel's save-draft and a tablet's progress push. Keeping the branch would have
     * auto-submitted every mobile round the moment its deadline passed, finalising work an
     * operator was still walking — the opposite of what the column meant when the branch was
     * written. See {@code docs/jobs.md} for the consequences that follow (compliance counts,
     * asset status requests) and what to do about them.
     *
     * <p><b>{@code EXPIRED} is still not final.</b> This job races every tablet out of coverage
     * and often wins, so a round it expired can still be completed afterwards by an offline
     * submission whose {@code completed_at} falls before {@code due_at} — {@code EXPIRED} is in
     * {@code COMPLETABLE_STATUSES} for exactly that reason.
     */
    @Scheduled(fixedDelayString = "${app.scheduler.log-sheet-expiry-ms:60000}")
    @Transactional
    public void expireOverdueSheets() {
        long now = System.currentTimeMillis();
        List<LogSheet> overdue = logSheetRepository.findByStatusInAndDueAtLessThanEqual(OPEN_STATUSES, now);
        int changed = 0;
        for (LogSheet sheet : overdue) {
            if (logSheetService.tryExpireOverdue(sheet.getId(), now)) {
                changed++;
                log.info("Expired log sheet {} (dueAt={}, draftSavedAt={})",
                        sheet.getId(), sheet.getDueAt(), sheet.getDraftSavedAt());
            }
        }
        if (changed > 0) {
            businessEventLogger.schedulerRun("log-sheet-expire", changed);
        }
    }
}
