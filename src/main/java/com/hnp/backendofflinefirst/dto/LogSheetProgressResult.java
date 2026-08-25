package com.hnp.backendofflinefirst.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * What the server did with one sheet's partial values.
 *
 * <p><b>The outcome vocabulary is its own, and that is why this is not a
 * {@link LogSheetSubmitResult}.</b> A submit result says whether a round was delivered; this says
 * whether a report about an unfinished round was accepted. The device must act on them
 * differently — a refused progress push changes nothing about the operator's work and must never
 * touch the row's {@code status} or {@code syncStatus}, which belong to the submit path. Sharing
 * one result type is how a "the server said no" branch ends up marking real, undelivered work as
 * failed.
 *
 * <table>
 *   <caption>Outcomes</caption>
 *   <tr><td>{@code SAVED}</td><td>stored; {@code savedAt} is the server's stamp</td></tr>
 *   <tr><td>{@code NO_CHANGE}</td><td>accepted, nothing to write — an empty or already-current payload</td></tr>
 *   <tr><td>{@code SUPERSEDED}</td><td>somebody else holds the sheet now</td></tr>
 *   <tr><td>{@code CANCELLED}</td><td>the round was called off</td></tr>
 *   <tr><td>{@code EXPIRED}</td><td>the deadline passed; the completion path is still open on {@code completedAt}</td></tr>
 *   <tr><td>{@code VALIDATION_ERROR}</td><td>a value the final submit would refuse too</td></tr>
 *   <tr><td>{@code ERROR}</td><td>unknown sheet, foreign asset, missing id</td></tr>
 * </table>
 *
 * <p>Note the absence of {@code DUPLICATE}. Progress carries no {@code clientActionId} and is
 * meant to be re-sent: a push that repeats values the server already holds is a {@code NO_CHANGE},
 * not a replay to be refused.
 */
@Data
@AllArgsConstructor
public class LogSheetProgressResult {

    /** The device's row id, echoed so the client can match the outcome to the row. */
    private String localId;

    private Long serverId;

    private String error;

    /** {@code SAVED} | {@code NO_CHANGE} | {@code SUPERSEDED} | {@code CANCELLED} | {@code EXPIRED} | {@code VALIDATION_ERROR} | {@code ERROR}. */
    private String outcome;

    /**
     * Server time the progress was stamped, on an accepted push only.
     *
     * <p>Sent back so the tablet can show «آخرین همگام‌سازی پیشرفت» from the server's own clock
     * rather than from its own — the two disagree by exactly the offline gap, and the number the
     * operator wants is when the supervisor last saw their work.
     */
    private Long savedAt;
}
