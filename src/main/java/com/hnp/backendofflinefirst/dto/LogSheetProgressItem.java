package com.hnp.backendofflinefirst.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * One sheet's partial values, from a round the operator is still walking.
 *
 * <p><b>Deliberately not {@link LogSheetDto}.</b> That type carries a completion —
 * {@code completedAt}, {@code submittedAt}, {@code clientActionId}, {@code status} — and every
 * one of those fields is a statement this payload must not be able to make. Sharing it would
 * mean the progress endpoint had to ignore half its own request body, and one careless read of
 * {@code status} away from finalising a round nobody submitted.
 *
 * <p><b>{@code entries} carries only what changed on the device since the last accepted push</b>,
 * not the whole sheet. That is the opposite of a submit, which resends every asset so the
 * delivered round is self-contained. A progress push is an increment: it runs on a timer, so
 * making it proportional to the work actually done rather than to the size of the sheet is what
 * keeps the cost proportional to the plant instead of to the clock.
 */
@Data
public class LogSheetProgressItem {

    /** The sheet on the server. {@link #id} is accepted as an alias, as on the submit path. */
    private Long serverId;
    private Long id;

    /**
     * The device's own row id, echoed back untouched in the result.
     *
     * <p>The correlation key: the response is a list and the client matches each outcome to the
     * row that produced it by this, never by position. The server does not store it.
     */
    private String localId;

    /**
     * Display name for {@code log_sheets.operator_name}, as the submit path also sends.
     *
     * <p>Only used to fill a name the sheet does not already have; it never overwrites one. A
     * pool sheet claimed offline can reach the server with no operator name on it, and the
     * supervisor's progress view is the first place that shows.
     */
    private String operatorName;

    /** Only the entries edited on the device since the last accepted push. */
    private List<LogSheetEntryDto> entries;

    /**
     * The round's own answers, as they stand on the device.
     *
     * <p>Unlike {@link #entries} this is the whole round-level form rather than a delta: it is
     * one small map per sheet, not one per asset, so sending it whole costs nothing and removes
     * the question of how a cleared answer would be expressed in a delta.
     *
     * <p>{@code null} means the push did not carry it. A correction here is still a correction —
     * the server keeps what it replaced, exactly as the web panel does. What the progress path
     * does <b>not</b> do is apply the required check: a round being walked is partial by
     * definition, and judging it against "every required question is answered" would refuse
     * every push until the last one.
     */
    private Map<String, Object> sheetFormData;
}
