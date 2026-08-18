package com.hnp.backendofflinefirst.config;

import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stamps {@code max_severity} on entries that predate the column.
 *
 * <p>Rows written before the flag existed hold {@code NULL}, which means "never evaluated" —
 * they are invisible to the exception report and to any external poller, which would read as
 * "nothing is wrong" rather than "this was never checked". This walks those rows once and
 * evaluates them exactly the way a write would.
 *
 * <p><strong>Idempotent and self-disabling.</strong> It only selects entries whose severity is
 * still NULL and that <em>actually</em> have values — meaning a non-empty {@code form_data}
 * object, the same test {@link com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator} applies
 * — so a second start finds nothing and does no work. It is safe to leave in place permanently:
 * it costs one count on a normal boot, and it is what makes the column correct on any environment
 * that adds it later.
 *
 * <p>That word "actually" was wrong once and cost a fixed price on every single restart. The
 * query asked for {@code form_data IS NOT NULL}; an entry that was raised with its sheet and
 * never filled holds {@code '{}'}, which satisfies that and does not satisfy the evaluator, which
 * writes the severity straight back to NULL. The result was a runner that read 3,093 entries,
 * loaded their sheets, resolved their definition snapshots, stamped nothing, and then logged
 * "3093 entries stamped" — every boot, forever. See the repository's comment for the predicate.
 *
 * <p>Evaluation uses each sheet's own {@code field_definitions_snapshot} (via
 * {@link LogSheetFieldDefinitionsService}), so historic rows are judged by the ranges that
 * applied when they were recorded — the same rule the live write path follows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntrySeverityBackfillRunner implements ApplicationRunner {

    /** Sheets per transaction — keeps the backfill off one enormous transaction. */
    private static final int BATCH_SHEETS = 200;

    /**
     * Entries fetched per pass.
     *
     * <p>The previous version fetched every candidate row in one query. That was survivable only
     * because the predicate happened to select rows that are cheap; on an environment adding the
     * column to a large history it is an unbounded read into heap, which this codebase has already
     * paid for once ({@code LoggingAspect}, gotcha 9b-2). Each pass stamps what it reads, so the
     * set shrinks and the next pass picks up where this one stopped.
     */
    private static final int BATCH_ENTRIES = 1_000;

    /**
     * Hard stop on the drain loop.
     *
     * <p>The loop's termination depends on every row it stamps leaving the candidate set. That is
     * true — the evaluator always assigns at least {@code OK} to an entry that has values — but
     * "true" and "true forever, through every future change to the evaluator" are different
     * claims, and the failure mode of being wrong is an application that never finishes starting.
     * The guard below turns that into a warning and a boot.
     */
    private static final int MAX_PASSES = 1_000;

    private final LogSheetRepository logSheetRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

    @Override
    public void run(ApplicationArguments args) {
        long pending = logSheetEntryRepository.countUnevaluatedWithValues();
        if (pending == 0) {
            return;
        }
        log.info("Backfilling entry severity for {} log sheet entries…", pending);

        int total = 0;
        int passes = 0;
        while (passes++ < MAX_PASSES) {
            int stamped = backfill();
            if (stamped == 0) {
                break;
            }
            total += stamped;
        }
        if (passes >= MAX_PASSES) {
            // Rows were read and stamped and are still being selected: the query and the
            // evaluator disagree about what "has values" means, which is the exact defect this
            // guard exists for. Say so loudly rather than looping until somebody kills the boot.
            log.warn("Entry severity backfill stopped after {} passes with {} entries still "
                    + "selected — the backfill query and EntrySeverityEvaluator disagree.",
                    MAX_PASSES, logSheetEntryRepository.countUnevaluatedWithValues());
        }
        log.info("Entry severity backfill complete: {} entries stamped.", total);
    }

    @Transactional
    public int backfill() {
        List<LogSheetEntry> entries = logSheetEntryRepository.findUnevaluatedWithValues(BATCH_ENTRIES);
        if (entries.isEmpty()) {
            return 0;
        }
        Map<Long, List<LogSheetEntry>> bySheet = entries.stream()
                .filter(e -> e.getLogSheetId() != null)
                .collect(Collectors.groupingBy(LogSheetEntry::getLogSheetId));

        int stamped = 0;
        List<Long> sheetIds = List.copyOf(bySheet.keySet());
        for (int i = 0; i < sheetIds.size(); i += BATCH_SHEETS) {
            List<Long> chunk = sheetIds.subList(i, Math.min(i + BATCH_SHEETS, sheetIds.size()));
            Map<Long, LogSheet> sheets = logSheetRepository.findAllById(chunk).stream()
                    .collect(Collectors.toMap(LogSheet::getId, s -> s, (a, b) -> a));

            for (Long sheetId : chunk) {
                LogSheet sheet = sheets.get(sheetId);
                if (sheet == null) continue;
                List<LogSheetEntry> sheetEntries = bySheet.get(sheetId);
                List<FieldDefinition> defs =
                        fieldDefinitionsService.resolveForEntries(sheet, sheetEntries);
                for (LogSheetEntry entry : sheetEntries) {
                    EntrySeverityEvaluator.apply(entry, defs);
                    stamped++;
                }
                logSheetEntryRepository.saveAll(sheetEntries);
            }
        }
        return stamped;
    }
}
