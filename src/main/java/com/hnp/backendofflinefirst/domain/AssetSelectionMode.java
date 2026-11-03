package com.hnp.backendofflinefirst.domain;

/**
 * How a log-sheet template picks the assets of each generated sheet.
 * <pre>
 * SCOPE    — re-resolved on EVERY generation from scopeType + scopeId + classId, so
 *            assets later added to that scope are picked up automatically.
 * EXPLICIT — a frozen, hand-picked set (log_sheet_template_assets). The list never
 *            changes on its own; the only filter applied at generation time is that
 *            inactive assets are skipped. May span several asset classes, so classId
 *            is optional in this mode.
 * NONE     — this round has no equipment in it at all: a questionnaire, a shift handover,
 *            an area inspection. Scope, class and the hand-picked list are all irrelevant
 *            and none is required.
 * </pre>
 *
 * <h2>Why {@code NONE} is a mode rather than an empty list</h2>
 *
 * <p>A {@code SCOPE} template always <em>means</em> "the assets of this class in this place". It
 * can resolve to zero by accident — nobody has registered one yet — and the next person to
 * register a pump there turns a questionnaire into a 40-asset round with nobody having edited
 * anything. An {@code EXPLICIT} template with an empty list has the same problem from the other
 * side: a year later nothing can tell "I meant no assets" from "somebody deleted the last one".
 *
 * <p>So the intent is recorded. A {@code NONE} template asks only the round-level questions
 * ({@code log_sheet_fields}), and every other part of the lifecycle — scheduling, assignment,
 * claiming, offline sync, attachments, approval, reporting — applies to it unchanged. That is
 * the whole argument of roadmap §22: a questions-only log sheet is a log sheet.
 */
public enum AssetSelectionMode {
    SCOPE,
    EXPLICIT,
    NONE
}
