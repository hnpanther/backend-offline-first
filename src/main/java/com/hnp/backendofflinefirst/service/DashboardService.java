package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.DashboardPeriod;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.dto.DashboardRows.PeriodSummary;
import com.hnp.backendofflinefirst.dto.DashboardRows.UnitPeriodRow;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.util.ReferenceLabelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The landing dashboard: one period, broken down by operational unit.
 *
 * <h2>What this page is, and what it replaced</h2>
 *
 * <p>{@code GET:/} used to render an inventory census — how many locations, assets, sub-functions
 * and users exist. Every one of those was a plant-wide {@code count(*)} with no scope in it,
 * which is why the page could only ever be an administrator's: showing those totals to a
 * supervisor would have told them the size of a plant they are not responsible for. It also
 * answered a question nobody asks twice, since master data barely moves.
 *
 * <p>This answers an operational one instead — what happened to the rounds raised in the last
 * day, week or month, per unit — and it is narrowed by the viewer's own units, so the same page
 * is correct for an administrator and for a supervisor of three units. V16 grants it to whoever
 * already holds {@code GET:/reports}.
 *
 * <h2>Three queries, never one per unit</h2>
 *
 * <p>A plant has dozens of operational units and this is the first page of every login, so the
 * cost must not track the organisation chart. The census and the breach counts are each a single
 * grouped statement; the unit labels are one batched lookup over the ids the page actually
 * references. A plant-wide viewer pays one more — {@code count(*)} over
 * {@code operational_units}, to know how many units raised nothing — and a scoped viewer pays
 * none, because their scope set already has a size. Totals are summed in Java from rows already
 * fetched rather than asked for again.
 *
 * <h2>The scope trap this is written around</h2>
 *
 * <p>{@code visibleUnitIds()} has three answers and they are not interchangeable: {@code null}
 * means unrestricted, an <b>empty set</b> means no access, and a non-empty set means exactly
 * those units. Reading {@code null} as "no units" empties an administrator's dashboard; letting
 * an empty set reach SQL as {@code IN ()} either fails outright or, worse, matches everything.
 * So {@code null} is passed straight through to the queries, whose {@code :unitIds IS NULL OR …}
 * branch treats it as "no filter", and the empty set short-circuits before any statement runs.
 * See {@code docs/hierarchy.md} §5.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final OperationalUnitRepository operationalUnitRepository;
    private final AssetAccessService assetAccessService;
    private final ReferenceLabelService referenceLabelService;

    /**
     * Everything the page renders, for one period and one viewer.
     *
     * @param period the window's length — the only thing the request chooses
     * @param zone   the plant's zone; the caller passes {@code DateUtils.TEHRAN}
     * @param now    the instant this request is being served, used both for the window's anchor
     *               and for judging «overdue», so the two cannot disagree by a page load
     */
    public PeriodSummary summarise(DashboardPeriod period, ZoneId zone, long now) {
        DashboardPeriod.Window window = period.window(zone, now);

        Set<Long> unitIds = assetAccessService.visibleUnitIds();
        // Empty is not null. A scoped user mapped to no unit sees nothing, and binding an empty
        // collection into `IN (:unitIds)` is a SQL syntax error rather than a no-match.
        if (unitIds != null && unitIds.isEmpty()) {
            return PeriodSummary.noAccess(period, window);
        }

        List<Object[]> census = logSheetRepository.lifecycleCensusByUnitCreatedBetween(
                unitIds, window.fromAt(), window.toAt(), now);
        Map<Long, long[]> breaches = breachesByUnit(unitIds, window);

        Map<Long, String> labels = referenceLabelService.operationalUnitLabelsFor(
                unitsMentionedBy(census, breaches));

        List<UnitPeriodRow> rows = new ArrayList<>();
        boolean unassignedRounds = false;
        for (Object[] r : census) {
            Long unitId = (Long) r[0];
            long[] breach = breaches.getOrDefault(unitId, new long[2]);
            unassignedRounds |= unitId == null;
            rows.add(new UnitPeriodRow(
                    unitId,
                    // Null for the no-unit row: `operationalUnitLabelsFor` guarantees a key for
                    // every id it was asked about, so a missing label here means a unit that was
                    // deleted while its rounds survived, and the id is the honest answer.
                    unitId == null ? null : labels.getOrDefault(unitId, String.valueOf(unitId)),
                    num(r[1]), num(r[2]), num(r[3]), num(r[4]), num(r[5]),
                    num(r[6]), num(r[7]), num(r[8]),
                    breach[WARNING], breach[DANGER]));
        }
        // Busiest unit first, then by label, so the order is stable across reloads rather than
        // whatever the database felt like returning for two units with the same count.
        rows.sort(Comparator.comparingLong(UnitPeriodRow::generated).reversed()
                .thenComparing(UnitPeriodRow::unitLabel, Comparator.nullsLast(String::compareTo)));

        long unitsWithRounds = rows.stream().filter(row -> row.unitId() != null).count();
        return new PeriodSummary(
                period, window.fromAt(), window.toAt(), window.lastIncludedAt(),
                List.copyOf(rows),
                UnitPeriodRow.totalOf(rows),
                unitsInScope(unitIds),
                unitsWithRounds,
                unassignedRounds);
    }

    /**
     * Warning and danger counts per unit, from one grouped statement.
     *
     * <p>Indexed by the two constants below rather than by a {@code Map<String, Long>} per unit:
     * there are exactly two severities worth counting and the query already filters to them, so
     * a two-slot array is the whole shape. An unrecognised value — which the predicate makes
     * impossible, but which a hand-written row in the column could produce — is ignored rather
     * than folded into one of the two, because quietly counting an unknown severity as a warning
     * is how a column stops meaning anything.
     */
    private Map<Long, long[]> breachesByUnit(Set<Long> unitIds, DashboardPeriod.Window window) {
        Map<Long, long[]> out = new HashMap<>();
        for (Object[] r : logSheetEntryRepository
                .countBreachesByUnitAndSeverityForSheetsCreatedBetween(
                        unitIds, window.fromAt(), window.toAt())) {
            Long unitId = (Long) r[0];
            String severity = r[1] == null ? null : r[1].toString();
            int slot;
            if (FieldValidationSeverity.DANGER.name().equals(severity)) {
                slot = DANGER;
            } else if (FieldValidationSeverity.WARNING.name().equals(severity)) {
                slot = WARNING;
            } else {
                continue;
            }
            out.computeIfAbsent(unitId, k -> new long[2])[slot] += num(r[2]);
        }
        return out;
    }

    /**
     * Unit ids either statement mentioned, for one batched label lookup.
     *
     * <p>Both are walked, not just the census: a unit could in principle appear in the breach
     * result and not in the census only if the two windows disagreed, which they do not — but
     * taking the union costs nothing and means the label map can never be short of a key the
     * rows ask for. A null id is dropped, because «no unit» has no label to fetch and a null map
     * index throws in SpEL rather than yielding null.
     */
    private static Set<Long> unitsMentionedBy(List<Object[]> census, Map<Long, long[]> breaches) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Object[] r : census) {
            if (r[0] != null) {
                ids.add((Long) r[0]);
            }
        }
        for (Long unitId : breaches.keySet()) {
            if (unitId != null) {
                ids.add(unitId);
            }
        }
        return ids;
    }

    /**
     * How many units this viewer may see — the denominator behind «N units raised nothing».
     *
     * <p>Free for a scoped viewer, whose scope set already has a size. A plant-wide viewer costs
     * one {@code count(*)}; reading every row to count them would be the same mistake the
     * overview's breach figures were fixed for.
     */
    private long unitsInScope(Set<Long> unitIds) {
        return unitIds == null ? operationalUnitRepository.count() : unitIds.size();
    }

    private static final int WARNING = 0;
    private static final int DANGER = 1;

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
