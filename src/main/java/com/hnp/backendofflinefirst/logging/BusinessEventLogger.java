package com.hnp.backendofflinefirst.logging;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Structured business/operational events written to {@code business.log} (separate from trace noise).
 * <p>
 * <b>Audit rows do not belong here</b> — see {@link AuditTrailLogger}. They used to, one line
 * per changed field, and drowned this file at roughly a thousand to one. This file answers
 * "what did the system do?", which is a question with tens of answers a day, not tens of
 * thousands.
 */
@Component
@Slf4j
public class BusinessEventLogger {

    private static final Logger BUSINESS = LoggerFactory.getLogger("com.hnp.backendofflinefirst.business");

    public void logSheetGenerated(Long sheetId, Long templateId, String templateName, String origin) {
        BUSINESS.info("[LOG_SHEET_CREATED] id={} templateId={} templateName={} origin={}",
                sheetId, templateId, templateName, origin);
    }

    public void logSheetCompleted(Long sheetId, Long actorUserId, String source) {
        BUSINESS.info("[LOG_SHEET_COMPLETED] id={} actorUserId={} source={}", sheetId, actorUserId, source);
    }

    public void logSheetExpired(Long sheetId) {
        BUSINESS.info("[LOG_SHEET_EXPIRED] id={}", sheetId);
    }

    /**
     * A tablet reported partial values for a round it is still walking.
     *
     * <p>INFO, and deliberately not one line per entry. A round pushes progress on a timer for as
     * long as somebody is walking it, so this is the highest-frequency business event in the
     * system: the entry count goes in the message rather than becoming N lines. `business.log`
     * has already been made unreadable once by a per-row stream — see AGENTS.md §9.
     */
    public void logSheetProgressSaved(Long sheetId, Long actorUserId, int entryCount) {
        BUSINESS.info("[LOG_SHEET_PROGRESS] id={} actorUserId={} entries={}",
                sheetId, actorUserId, entryCount);
    }

    public void templateCreated(Long templateId, String name) {
        BUSINESS.info("[TEMPLATE_CREATED] id={} name={}", templateId, name);
    }

    public void templateUpdated(Long templateId, String name) {
        BUSINESS.info("[TEMPLATE_UPDATED] id={} name={}", templateId, name);
    }

    public void templateDeleted(Long templateId, String name) {
        BUSINESS.info("[TEMPLATE_DELETED] id={} name={}", templateId, name);
    }

    public void importStarted(String entityType, String fileName, long fileSizeBytes, int sheetRows) {
        BUSINESS.info("[IMPORT_START] entityType={} file={} sizeBytes={} sheetRows={}",
                entityType, fileName, fileSizeBytes, sheetRows);
        log.info("[IMPORT_START] entityType={} file={} sizeBytes={} sheetRows={} → ExcelImportService",
                entityType, fileName, fileSizeBytes, sheetRows);
    }

    public void importCompleted(String entityType, int rowsRead, int blankSkipped, int success, int errors) {
        BUSINESS.info("[IMPORT_DONE] entityType={} rowsRead={} blankSkipped={} success={} errors={}",
                entityType, rowsRead, blankSkipped, success, errors);
        log.info("[IMPORT_DONE] entityType={} rowsRead={} blankSkipped={} success={} errors={}",
                entityType, rowsRead, blankSkipped, success, errors);
    }

    public void schedulerRun(String job, int processed) {
        BUSINESS.info("[SCHEDULER] job={} processed={}", job, processed);
    }

    public void error(String event, String message, Throwable t) {
        BUSINESS.error("[{}] {}", event, message, t);
        log.error("[{}] {}", event, message, t);
    }

    public void error(String event, String message) {
        BUSINESS.error("[{}] {}", event, message);
        log.error("[{}] {}", event, message);
    }
}
