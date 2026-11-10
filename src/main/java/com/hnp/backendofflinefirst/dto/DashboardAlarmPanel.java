package com.hnp.backendofflinefirst.dto;

/**
 * The landing dashboard's alarm section: three counts, each a link into the queue already
 * filtered to what it counts.
 *
 * <p>Counts and nothing else, deliberately. The dashboard is a manager's overview; the list of
 * alarms — with its acknowledge and close buttons — is one click away on {@code /alarms}, and
 * repeating ten of its rows here made the page longer without making it more useful. Three
 * indexed {@code count(*)} queries, scoped exactly as the queue is.
 *
 * @param openCount         unacknowledged live alarms in the viewer's scope — «تعیین تکلیف نشده»
 * @param acknowledgedCount acknowledged and still live — «در حال پیگیری»
 * @param liveDangerCount   live alarms of either state whose severity is DANGER
 */
public record DashboardAlarmPanel(long openCount, long acknowledgedCount, long liveDangerCount) {
}
