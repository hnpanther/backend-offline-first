package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.LogSheetEntryRevision;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the reading a change replaced.
 *
 * <p><b>One writer, called from every path that mutates {@code form_data}.</b> There are three
 * of them — the mobile submit, the mobile progress push and the web fill form — and each one
 * already knows whether the value genuinely changed, because each already uses that answer to
 * decide whether to re-attribute the entry (see gotcha #20). This service hangs off the same
 * decision so the two can never drift apart: an entry whose authorship moved without a history
 * row, or a row written for a save that changed nothing, would each be a lie of its own kind.
 *
 * <p><b>What it deliberately does not record.</b> A first fill. Nothing was replaced, so there
 * is no earlier reading to keep, and writing an empty row for every asset of every round would
 * make the table grow with readings instead of with corrections.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogSheetEntryRevisionService {

    private final LogSheetEntryRevisionRepository repository;
    private final AttachmentRepository attachmentRepository;

    /**
     * Records the value {@code entry} is about to lose, if it had one.
     *
     * <p><b>Call this before mutating the entry</b>, while it still holds the old values — the
     * whole point is the state that is about to be overwritten. Every field copied here comes
     * off the entry as it currently stands; nothing is re-derived.
     *
     * @param entry             the entry as it is <em>now</em>, before the new values are applied
     * @param sheet             its sheet, for the status the correction happened under
     * @param actorUserId       who is overwriting it; null for a server-driven path
     * @param source            which surface the overwrite came from
     * @param supersededAt      server time of the overwrite
     * @return {@code true} when a revision row was written
     */
    @Transactional
    public boolean recordSupersededValue(LogSheetEntry entry,
                                         LogSheet sheet,
                                         Long actorUserId,
                                         ActionSource source,
                                         long supersededAt) {
        if (entry == null || entry.getId() == null) {
            return false;
        }
        Map<String, Object> previous = entry.getFormData();
        if (!com.hnp.backendofflinefirst.domain.FormDataValidationSupport
                .hasMeaningfulFormData(previous)) {
            // Nothing was replaced. See the class javadoc.
            return false;
        }

        LogSheetEntryRevision revision = new LogSheetEntryRevision();
        revision.setLogSheetEntryId(entry.getId());
        revision.setLogSheetId(entry.getLogSheetId());
        revision.setAssetId(entry.getAssetId());
        // Copied into an independent map. The caller is about to hand the entry a new map, and
        // in some paths the same instance is reused — keeping a reference here would silently
        // record the new value as the old one.
        revision.setFormData(new LinkedHashMap<>(previous));
        revision.setMaxSeverity(entry.getMaxSeverity());
        revision.setBreachedFields(entry.getBreachedFields() == null
                ? null : List.copyOf(entry.getBreachedFields()));
        revision.setEntrySource(entry.getEntrySource());
        revision.setRecordedByUserId(entry.getFilledByUserId());
        // The device time of the replaced reading, falling back to when it was first recorded.
        // Both are the operator's clock, which is the point: a history line says when somebody
        // was at the equipment.
        revision.setRecordedAt(entry.getUpdatedAt() != null ? entry.getUpdatedAt() : entry.getCreatedAt());
        revision.setSupersededByUserId(actorUserId);
        revision.setSupersededAt(supersededAt);
        revision.setSupersededSource(source);
        revision.setSheetStatus(sheet != null ? sheet.getStatus() : null);
        revision.setAttachmentSnapshot(snapshotAttachments(previous));
        repository.save(revision);
        return true;
    }

    /**
     * What the attachments referenced by a replaced value actually were.
     *
     * <p>Read <b>now</b>, while the rows still exist. Deleting an attachment removes its row and
     * its bytes, so an id kept in a revision resolves to nothing afterwards and the history can
     * only report a missing file — which reads identically to storage having lost it. The
     * metadata is what lets the panel say a photo was deliberately removed, and what it was.
     *
     * <p>An id with no row is skipped rather than recorded as an empty entry: it was already
     * gone before this correction, so this revision is not the place that lost it.
     *
     * @return null when the value referenced no attachments, which is the ordinary case — a
     *         column of empty objects on every numeric correction would be pure noise
     */
    private Map<String, Map<String, Object>> snapshotAttachments(Map<String, Object> formData) {
        Map<String, List<String>> byField = AttachmentReferences.extract(formData);
        if (byField.isEmpty()) {
            return null;
        }
        List<String> ids = byField.values().stream().flatMap(List::stream).distinct().toList();
        if (ids.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Attachment a : attachmentRepository.findAllById(ids)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("kind", a.getKind() == null ? null : a.getKind().name());
            meta.put("mimeType", a.getMimeType());
            meta.put("sizeBytes", a.getSizeBytes());
            meta.put("durationMs", a.getDurationMs());
            meta.put("width", a.getWidth());
            meta.put("height", a.getHeight());
            meta.put("uploadedAt", a.getUploadedAt());
            meta.put("createdByUserId", a.getCreatedByUserId());
            out.put(a.getId(), meta);
        }
        return out.isEmpty() ? null : out;
    }

    /** One entry's superseded values, oldest first. */
    @Transactional(readOnly = true)
    public List<LogSheetEntryRevision> findForEntry(Long logSheetEntryId) {
        return repository.findByLogSheetEntryIdOrderByIdAsc(logSheetEntryId);
    }

    /**
     * Every superseded value on one sheet, grouped by entry, in one query.
     *
     * <p>An entry with no corrections is <b>absent from the map</b> rather than mapped to an
     * empty list — the same contract {@code OperationalUnitService.supervisorIdsByUnit} uses, so
     * callers read it with {@code getOrDefault(id, List.of())}.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<LogSheetEntryRevision>> findForSheetByEntryId(Long logSheetId) {
        return groupByEntry(repository.findByLogSheetIdOrderByIdAsc(logSheetId));
    }

    @Transactional(readOnly = true)
    public Map<Long, List<LogSheetEntryRevision>> findForEntriesByEntryId(Collection<Long> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return Map.of();
        }
        return groupByEntry(repository.findByLogSheetEntryIdInOrderByIdAsc(entryIds));
    }

    private Map<Long, List<LogSheetEntryRevision>> groupByEntry(List<LogSheetEntryRevision> rows) {
        Map<Long, List<LogSheetEntryRevision>> out = new LinkedHashMap<>();
        for (LogSheetEntryRevision row : rows) {
            out.computeIfAbsent(row.getLogSheetEntryId(), k -> new java.util.ArrayList<>()).add(row);
        }
        return out;
    }
}
