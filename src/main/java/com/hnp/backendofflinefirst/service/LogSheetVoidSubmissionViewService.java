package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.util.FormDataViewHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a voided offline submission's raw JSON payload into displayable rows.
 *
 * <p>The payload is a snapshot of what the operator's device sent, written by
 * {@code LogSheetService.entriesToPayload} as {@code assetId / assetName / formData /
 * createdAt / updatedAt}. It is deliberately NOT re-read from {@code log_sheet_entries}:
 * those hold the authoritative state that superseded this submission, so showing them
 * would defeat the whole point of the page.
 */
@Service
@RequiredArgsConstructor
public class LogSheetVoidSubmissionViewService {

    private final LogSheetRepository logSheetRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final FormDataViewHelper formDataViewHelper;
    private final AttachmentService attachmentService;

    /** One payload entry, resolved for display. */
    public record VoidedEntryRow(Long assetId,
                                 String assetCode,
                                 String assetName,
                                 Long updatedAt,
                                 List<FormDataViewHelper.FormFieldRow> fields) {

        public boolean hasData() {
            return !fields.isEmpty();
        }
    }

    public List<VoidedEntryRow> toRows(LogSheetVoidSubmission submission) {
        List<Map<String, Object>> payload = submission.getPayload();
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        LogSheet sheet = logSheetRepository.findById(submission.getLogSheetId()).orElse(null);

        // Attachments belong to the sheet, not to the submission, so a voided payload's photos
        // are still resolvable — which is exactly what makes comparing the two versions useful.
        Map<String, Attachment> attachmentsById =
                attachmentService.findForLogSheet(submission.getLogSheetId()).stream()
                        .collect(Collectors.toMap(Attachment::getId, a -> a, (a, b) -> a,
                                LinkedHashMap::new));

        Set<Long> assetIds = payload.stream()
                .map(m -> asLong(m.get("assetId")))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, AssetEntry> assetsById = assetIds.isEmpty()
                ? Map.of()
                : assetEntryRepository.findAllById(assetIds).stream()
                        .collect(Collectors.toMap(AssetEntry::getId, a -> a));

        List<VoidedEntryRow> rows = new ArrayList<>();
        for (Map<String, Object> item : payload) {
            Long assetId = asLong(item.get("assetId"));
            AssetEntry asset = assetId != null ? assetsById.get(assetId) : null;
            // Labels come from the class the asset belongs to; fall back to the sheet-wide
            // snapshot when the asset itself is gone (hard-deleted or never known here).
            List<FieldDefinition> defs = resolveDefs(sheet, asset);
            rows.add(new VoidedEntryRow(
                    assetId,
                    asset != null ? asset.getAssetCode() : null,
                    asset != null ? asset.getAssetName() : stringOf(item.get("assetName")),
                    asLong(item.get("updatedAt")),
                    formDataViewHelper.rows(item.get("formData"), defs, attachmentsById)));
        }
        return rows;
    }

    private List<FieldDefinition> resolveDefs(LogSheet sheet, AssetEntry asset) {
        if (sheet == null) {
            return List.of();
        }
        if (asset != null && asset.getClassId() != null) {
            return fieldDefinitionsService.resolveForClass(sheet, asset.getClassId());
        }
        return fieldDefinitionsService.resolve(sheet);
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOf(Object v) {
        return v != null ? String.valueOf(v) : null;
    }
}
