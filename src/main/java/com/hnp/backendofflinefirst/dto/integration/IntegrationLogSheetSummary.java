package com.hnp.backendofflinefirst.dto.integration;

/**
 * One log sheet as the list endpoint returns it: enough to identify it and decide whether to
 * fetch the detail, and nothing more.
 *
 * <p><b>A dedicated record, not the entity and not the mobile DTO.</b> Reusing either would
 * mean that the next internal column added to {@code LogSheet} — as several have been —
 * appears in a third party's feed the day it is added, with nobody having decided that it
 * should. Everything published here was chosen; the default for anything new is that it is
 * not published until somebody adds a line to this file.
 *
 * <p>Explicitly withheld: {@code syncStatus} and {@code syncedAt} (offline-sync bookkeeping),
 * {@code fieldDefinitionsSnapshot} (the schema, which the detail endpoint publishes properly),
 * {@code draftSavedAt}, {@code clientActionId}, {@code notes} (internal supervisor commentary),
 * and every internal user id.
 *
 * <p>All timestamps are ISO-8601 in UTC, e.g. {@code 2026-08-19T07:12:33Z}. The database stores
 * epoch milliseconds; converting at the boundary means an integrator never has to know that.
 *
 * @param id             stable identifier — what {@code GET /integration/v1/log-sheets/{id}} takes
 * @param templateId     null for a custom, template-less sheet; the name is still populated
 * @param templateName   what the round is called
 * @param scopeSummary   human-readable description of what the round covered
 * @param status         SUBMITTED, APPROVED, VOIDED, EXPIRED or CANCELLED — never an in-flight state
 * @param origin         SCHEDULED or MANUAL
 * @param unit           the operational unit responsible for the sheet
 * @param dueAt          deadline, if the sheet carried one
 * @param completedAt    when the operator finished it — device time, may precede {@code submittedAt}
 * @param submittedAt    when the server received it
 * @param finalizedAt    the instant the date-range filter matched on; see
 *                       {@code IntegrationLogSheetQueryService} for how it is chosen per status
 * @param assignedTo     who the sheet was assigned to, if anyone
 * @param completedBy    who completed it; null for an unattended expiry
 * @param operatorName   free-text operator name captured on the device, when present
 * @param assetCount     rows in the detail response, so a caller can size the fetch
 * @param attachmentCount photos/voice notes recorded against the sheet
 */
public record IntegrationLogSheetSummary(
        Long id,
        Long templateId,
        String templateName,
        String scopeSummary,
        String status,
        String origin,
        IntegrationReferences.Unit unit,
        String dueAt,
        String completedAt,
        String submittedAt,
        String finalizedAt,
        IntegrationReferences.Person assignedTo,
        IntegrationReferences.Person completedBy,
        String operatorName,
        int assetCount,
        int attachmentCount) {
}
