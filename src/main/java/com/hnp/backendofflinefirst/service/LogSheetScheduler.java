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

    @Value("${app.scheduler.log-sheet-max-backfill:500}")
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
            } catch (Exception e) {
                log.error("Scheduled generation failed for template {}: {}", template.getId(), e.getMessage(), e);
                businessEventLogger.error("SCHEDULER_GENERATE", "templateId=" + template.getId(), e);
            }
        }
        if (!due.isEmpty()) {
            businessEventLogger.schedulerRun("log-sheet-generate", due.size());
        }
    }

    @Scheduled(fixedDelayString = "${app.scheduler.log-sheet-expiry-ms:60000}")
    @Transactional
    public void expireOverdueSheets() {
        long now = System.currentTimeMillis();
        List<LogSheet> overdue = logSheetRepository.findByStatusInAndDueAtLessThanEqual(OPEN_STATUSES, now);
        int changed = 0;
        for (LogSheet sheet : overdue) {
            if (sheet.getDraftSavedAt() != null) {
                if (logSheetService.finalizeDraftOnExpiry(sheet.getId(), now)) {
                    changed++;
                    log.info("Auto-finalized draft log sheet {} on expiry (dueAt={})", sheet.getId(), sheet.getDueAt());
                }
                continue;
            }
            if (logSheetService.tryExpireOverdue(sheet.getId(), now)) {
                changed++;
                log.info("Expired log sheet {} (dueAt={})", sheet.getId(), sheet.getDueAt());
            }
        }
        if (changed > 0) {
            businessEventLogger.schedulerRun("log-sheet-expire", changed);
        }
    }
}
