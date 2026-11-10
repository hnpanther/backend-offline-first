package com.hnp.backendofflinefirst.service.alarm;

import com.hnp.backendofflinefirst.domain.AlarmCondition.Reading;
import com.hnp.backendofflinefirst.domain.EntrySeverityEvaluator;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.service.LogSheetFieldDefinitionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One asset's readings of one parameter, newest first, each with its severity.
 *
 * <h2>Which rounds count</h2>
 *
 * <p>{@code SUBMITTED} and {@code APPROVED} — {@link LogSheetStatus#COMPLETED_STATUSES}. A voided
 * round's readings were invalidated by this plant, so they neither raise nor sustain an alarm; an
 * expired or cancelled round recorded nothing; a round still being walked — including one
 * reopened for a correction — has not claimed its readings are true yet. A reopened round's
 * readings therefore drop out of the history until it is completed again, which is why a reopen
 * does not trigger an evaluation: doing so would withdraw an alarm and re-raise it a minute later,
 * and send its webhook twice.
 *
 * <h2>The severity of one parameter, read rather than recomputed</h2>
 *
 * <p>{@code EntrySeverityEvaluator} already judged every reading when it was written, and stored
 * the verdict: {@code max_severity} and {@code breached_fields}. Those answer almost every
 * question directly — a key absent from {@code breached_fields} was fine, and on a round whose
 * worst was a warning a listed key was a warning. They fail in exactly one case: a round that
 * recorded a danger <em>and</em> warnings, where {@code breached_fields} lists dangers first but
 * does not record where they end. Only then — and for a reading stored before severity was —
 * is the evaluator's own function asked, with the definitions that round was judged by. Never a
 * second implementation of the judgement.
 */
@Component
@RequiredArgsConstructor
public class AlarmReadingHistory {

    private static final Collection<LogSheetStatus> COUNTED = LogSheetStatus.COMPLETED_STATUSES;
    private static final int MAX_VALUE_TEXT = 1000;

    private final LogSheetEntryRepository entryRepository;
    private final LogSheetRepository logSheetRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;

    /**
     * @param afterExclusive only rounds completed strictly after this instant
     * @param limit          at most this many readings — the newest ones
     */
    public List<Reading> readings(Long assetId, String key, long afterExclusive, int limit) {
        List<Object[]> rows = entryRepository.findFinishedReadingsOfAsset(
                assetId, COUNTED, afterExclusive, PageRequest.of(0, limit));
        Map<Long, LogSheet> sheets = new HashMap<>();
        List<Reading> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            LogSheetEntry entry = (LogSheetEntry) row[0];
            long completedAt = ((Number) row[1]).longValue();
            Long unitId = row[2] == null ? null : ((Number) row[2]).longValue();
            Object raw = entry.getFormData() == null ? null : entry.getFormData().get(key);
            FieldValidationSeverity severity = isAnswered(raw) ? severityOf(entry, key, sheets) : null;
            out.add(new Reading(entry.getLogSheetId(), entry.getId(), completedAt, unitId,
                    severity, isAnswered(raw) ? valueText(raw) : null));
        }
        return out;
    }

    FieldValidationSeverity severityOf(LogSheetEntry entry, String key, Map<Long, LogSheet> sheets) {
        String max = entry.getMaxSeverity();
        List<String> breached = entry.getBreachedFields();
        if (max != null) {
            if (breached == null || !breached.contains(key)) {
                return FieldValidationSeverity.OK;
            }
            if (FieldValidationSeverity.WARNING.name().equals(max)) {
                return FieldValidationSeverity.WARNING;
            }
            if (FieldValidationSeverity.DANGER.name().equals(max) && key.equals(breached.get(0))) {
                // Dangers are listed first, so the first key of a danger round is a danger.
                return FieldValidationSeverity.DANGER;
            }
        }
        // Ambiguous, or never evaluated: ask the evaluator, with this round's own definitions.
        LogSheet sheet = sheets.get(entry.getLogSheetId());
        if (sheet == null) {
            sheet = logSheetRepository.findById(entry.getLogSheetId()).orElse(null);
            if (sheet == null) {
                return FieldValidationSeverity.OK;
            }
            sheets.put(sheet.getId(), sheet);
        }
        FieldValidationSeverity severity = EntrySeverityEvaluator.fieldSeverities(
                entry, fieldDefinitionsService.resolveForEntry(sheet, entry), null).get(key);
        return severity == null ? FieldValidationSeverity.OK : severity;
    }

    /**
     * Whether a parameter was answered at all. The same notion of «blank» the fill pages use: an
     * empty string, an empty choice list or an empty object is nobody having answered.
     */
    static boolean isAnswered(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (raw instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (raw instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    static String valueText(Object raw) {
        String text;
        if (raw instanceof Collection<?> collection) {
            List<String> parts = new ArrayList<>();
            for (Object item : collection) {
                parts.add(String.valueOf(item));
            }
            text = String.join("، ", parts);
        } else if (raw instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                && !Double.isInfinite(number.doubleValue()) && Math.abs(number.doubleValue()) < 1e15) {
            // 72.0 is what an operator typed as «72»; printing the «.0» reads like a precision
            // nobody recorded.
            text = String.valueOf(number.longValue());
        } else {
            text = String.valueOf(raw);
        }
        return text.length() <= MAX_VALUE_TEXT ? text : text.substring(0, MAX_VALUE_TEXT);
    }
}
