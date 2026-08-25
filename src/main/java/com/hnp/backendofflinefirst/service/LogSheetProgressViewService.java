package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.LogSheetProgressSummary;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "N of M assets recorded", for a whole page of log sheets at once.
 *
 * <p>Its own service rather than a method on {@link LogSheetService} because it answers a
 * question about presentation, not about the lifecycle, and because both places that ask it —
 * the log-sheet list and «کارتابل من» — need the same batch shape. A per-row count would be one
 * query per sheet on a page that shows up to 250 of them; {@code /log-sheets} already runs 99
 * statements at that size and this keeps it at 100. See {@code docs/performance.md} §3 for the
 * pattern and the measurement behind it.
 */
@Service
@RequiredArgsConstructor
public class LogSheetProgressViewService {

    private final LogSheetEntryRepository logSheetEntryRepository;

    /**
     * Progress for each of these sheets, keyed by sheet id.
     *
     * <p>A sheet with no entries at all is <b>absent</b> from the map rather than mapped to a
     * zero summary — the same contract {@code OperationalUnitService.supervisorIdsByUnit} uses,
     * so callers read it with {@code getOrDefault(id, LogSheetProgressSummary.EMPTY)}.
     */
    @Transactional(readOnly = true)
    public Map<Long, LogSheetProgressSummary> summariseByShortId(Collection<Long> sheetIds) {
        if (sheetIds == null || sheetIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LogSheetProgressSummary> out = new LinkedHashMap<>();
        for (Object[] row : logSheetEntryRepository.countProgressBySheetId(sheetIds)) {
            Long sheetId = (Long) row[0];
            long total = row[1] == null ? 0L : ((Number) row[1]).longValue();
            long filled = row[2] == null ? 0L : ((Number) row[2]).longValue();
            out.put(sheetId, new LogSheetProgressSummary(filled, total));
        }
        return out;
    }

    /** Convenience for a list of entities. */
    @Transactional(readOnly = true)
    public Map<Long, LogSheetProgressSummary> summarise(List<LogSheet> sheets) {
        if (sheets == null || sheets.isEmpty()) {
            return Map.of();
        }
        return summariseByShortId(sheets.stream()
                .map(LogSheet::getId)
                .filter(Objects::nonNull)
                .toList());
    }

    /** One sheet, for its own detail page. */
    @Transactional(readOnly = true)
    public LogSheetProgressSummary summariseOne(Long sheetId) {
        if (sheetId == null) {
            return LogSheetProgressSummary.EMPTY;
        }
        return summariseByShortId(List.of(sheetId))
                .getOrDefault(sheetId, LogSheetProgressSummary.EMPTY);
    }
}
