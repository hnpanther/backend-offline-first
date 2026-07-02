package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
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
 *  - expires sheets whose completion window has passed (completion then locked)
 * Runs on a single backend instance (offline-first deployment); per-template
 * next_run_at advancement acts as the concurrency guard.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LogSheetScheduler {

    private final LogSheetTemplateRepository templateRepository;
    private final LogSheetRepository logSheetRepository;
    private final LogSheetGenerationService generationService;
    private final LogSheetActionLogger actionLogger;

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
            try {
                generationService.runScheduled(template, now, maxBackfill);
            } catch (Exception e) {
                log.error("Scheduled generation failed for template {}: {}", template.getId(), e.getMessage(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.scheduler.log-sheet-expiry-ms:60000}")
    @Transactional
    public void expireOverdueSheets() {
        long now = System.currentTimeMillis();
        List<LogSheet> overdue = logSheetRepository.findByStatusInAndDueAtLessThanEqual(OPEN_STATUSES, now);
        for (LogSheet sheet : overdue) {
            sheet.setStatus(LogSheetStatus.EXPIRED);
            sheet.setExpiredAt(now);
            sheet.setUpdatedAt(now);
            logSheetRepository.save(sheet);
            actionLogger.record(sheet.getId(), LogSheetActionType.EXPIRE, ActionSource.SERVER,
                    null, sheet.getAssigneeUserId(), null, now, null);
            log.info("Expired log sheet {} (dueAt={})", sheet.getId(), sheet.getDueAt());
        }
    }
}
