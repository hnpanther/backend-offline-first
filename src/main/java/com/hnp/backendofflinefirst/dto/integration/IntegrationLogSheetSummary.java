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
 * @param scanMode       how this round was required to identify its assets, frozen onto the sheet
 *                       when it was raised (V11): {@code NFC}, {@code QR}, {@code NFC_OR_QR} or
 *                       {@code NONE}. Published because it is the only thing that explains the
 *                       {@code entrySource} on the detail's asset rows — a round in {@code QR}
 *                       mode cannot produce {@code PWA_NFC}, and a reading that was expected to
 *                       be scanned but says {@code PWA_MANUAL} is a different fact from one on a
 *                       round where nothing was ever meant to be scanned. Every sheet raised
 *                       before V11 answers {@code NFC}, which is what it was
 * @param category       the round's category (V19), or null for an uncategorised round — every
 *                       round raised before V19 is one. {@code code} is the stable grouping key;
 *                       {@code name} is frozen onto the round, so a rename does not rewrite it
 * @param unit           the operational unit responsible for the sheet
 * @param dueAt          deadline, if the sheet carried one
 * @param completedAt    when the operator finished it — device time, may precede {@code submittedAt}
 * @param submittedAt    when the server received it
 * @param finalizedAt    the instant the date-range filter matched on; see
 *                       {@code IntegrationLogSheetQueryService} for how it is chosen per status
 * @param approvedAt     when a supervisor accepted the round, or null if nobody has. <b>Not the
 *                       same instant as the window.</b> Approval happens after completion and
 *                       does not move {@code completedAt}, so a forward-only poller has already
 *                       passed the round by the time it is reviewed; this is the field that lets
 *                       a consumer re-reading a trailing window tell a newly approved round from
 *                       one it has already seen approved
 * @param approvedBy     the supervisor who accepted it
 * @param assignedTo     who the sheet was assigned to, if anyone
 * @param completedBy    who completed it; null for an unattended expiry
 * @param operatorName   free-text operator name captured on the device, when present
 * @param assetCount     rows in the detail response, so a caller can size the fetch. <b>Zero is
 *                       meaningful</b>: it is a questions-only round (roadmap §22) whose content
 *                       is entirely in the detail's {@code sheetForm}. One row exists per asset
 *                       the round covered whether or not the operator reached it, so this is the
 *                       size of the round, not the amount of work done on it
 * @param attachmentCount photos/voice notes recorded against the sheet. Counts the live round's
 *                       media only — the evidence attached to a voided sync (V15) is invisible
 *                       on this surface, as it is to every other reader of a live round
 */
public record IntegrationLogSheetSummary(
        Long id,
        Long templateId,
        String templateName,
        String scopeSummary,
        String status,
        String origin,
        String scanMode,
        IntegrationReferences.Category category,
        IntegrationReferences.Unit unit,
        String dueAt,
        String completedAt,
        String submittedAt,
        String finalizedAt,
        String approvedAt,
        IntegrationReferences.Person approvedBy,
        IntegrationReferences.Person assignedTo,
        IntegrationReferences.Person completedBy,
        String operatorName,
        int assetCount,
        int attachmentCount) {
}
