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
 * still NULL and that actually have values, so a second start finds nothing and does no work.
 * It is safe to leave in place permanently: it costs one indexed count on a normal boot, and
 * it is what makes the column correct on any environment that adds it later.
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
        int stamped = backfill();
        log.info("Entry severity backfill complete: {} entries stamped.", stamped);
    }

    @Transactional
    public int backfill() {
        List<LogSheetEntry> entries = logSheetEntryRepository.findUnevaluatedWithValues();
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
