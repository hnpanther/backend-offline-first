package com.hnp.backendofflinefirst.logging;

import com.hnp.backendofflinefirst.entity.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Structured business/operational events written to {@code business.log} (separate from trace noise).
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

    public void auditPersisted(AuditLog row) {
        if (row.getChanges() == null || row.getChanges().isEmpty()) {
            BUSINESS.info("[AUDIT] action={} entity={} id={} actor={} fields=0",
                    row.getAction(), row.getEntityType(), row.getEntityId(), row.getActorUsername());
            return;
        }
        for (var change : row.getChanges()) {
            BUSINESS.info("[AUDIT] action={} entity={} id={} actor={} field={} old={} new={}",
                    row.getAction(),
                    row.getEntityType(),
                    row.getEntityId(),
                    row.getActorUsername(),
                    change.get("field"),
                    change.get("oldValue"),
                    change.get("newValue"));
        }
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
