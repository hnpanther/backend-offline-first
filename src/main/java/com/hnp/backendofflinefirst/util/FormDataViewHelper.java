package com.hnp.backendofflinefirst.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.FormDataValidationSupport;
import com.hnp.backendofflinefirst.domain.LocationValues;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.service.AttachmentReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.hnp.backendofflinefirst.domain.AttachmentKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders formData JSON as labeled key/value rows for the UI. */
@Component("formDataView")
@RequiredArgsConstructor
public class FormDataViewHelper {

    private final ObjectMapper objectMapper;

    public record FormFieldRow(String label, String value, String unit,
                               String validationAlertClass, String validationMessage,
                               List<AttachmentView> attachments) {

        public FormFieldRow(String label, String value, String unit) {
            this(label, value, unit, null, null, List.of());
        }

        public FormFieldRow(String label, String value, String unit,
                            String validationAlertClass, String validationMessage) {
            this(label, value, unit, validationAlertClass, validationMessage, List.of());
        }

        /** True when this row should render media instead of a text value. */
        public boolean hasAttachments() {
            return attachments != null && !attachments.isEmpty();
        }
    }

    /**
     * One attachment, resolved for display.
     *
     * <p>{@code missing} covers a reference whose row is gone — a photo deleted after the sheet
     * was filled, or data restored without its files. Saying so is better than rendering a
     * broken image tag and leaving the supervisor to guess.
     */
    public record AttachmentView(String id, String kind, String mimeType,
                                 Long sizeBytes, Long durationMs, boolean missing) {

        public boolean isImage() {
            return "IMAGE".equals(kind);
        }

        public boolean isAudio() {
            return "AUDIO".equals(kind);
        }

        public boolean isVideo() {
            return "VIDEO".equals(kind);
        }

        /** Human-readable size for the caption under a thumbnail or player. */
        public String sizeLabel() {
            if (sizeBytes == null) return "";
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return Math.round(sizeBytes / 1024.0) + " KB";
            return String.format(Locale.ROOT, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
        }

        /** Size, plus duration when there is one — the single line shown under a tile. */
        public String captionLabel() {
            String duration = durationLabel();
            return duration.isEmpty() ? sizeLabel() : sizeLabel() + " · " + duration;
        }

        /** m:ss for a voice note, empty for anything without a duration. */
        public String durationLabel() {
            if (durationMs == null || durationMs < 0) return "";
            long totalSeconds = Math.round(durationMs / 1000.0);
            return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
        }
    }

    public List<FormFieldRow> rows(Object formData, List<FieldDefinition> fieldDefs) {
        return rows(formData, fieldDefs, Map.of());
    }

    /**
     * @param attachmentsById metadata for the sheet's attachments, keyed by id. Pass an empty
     *        map to render media fields as a plain count instead of players — used where the
     *        surrounding page has no way to stream the bytes.
     */
    public List<FormFieldRow> rows(Object formData, List<FieldDefinition> fieldDefs,
                                   Map<String, Attachment> attachmentsById) {
        Map<String, Object> data = asMap(formData);
        if (data.isEmpty()) return List.of();

        Map<String, FieldDefinition> defByKey = new LinkedHashMap<>();
        if (fieldDefs != null) {
            for (FieldDefinition fd : fieldDefs) {
                if (fd.getKey() != null) {
                    defByKey.put(fd.getKey(), fd);
                }
            }
        }

        List<FormFieldRow> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            FieldDefinition fd = defByKey.get(e.getKey());
            String label = fd != null && fd.getLabel() != null ? fd.getLabel() : e.getKey();
            String unit = fd != null ? fd.getUnit() : null;
            String alertClass = null;
            String validationMessage = null;
            if (fd != null && "number".equals(fd.getDataType())) {
                FieldValidationSeverity severity = FieldValidationSupport.evaluateNumericValue(
                        e.getValue(), fd.getValidation());
                if (severity != FieldValidationSeverity.OK) {
                    alertClass = FieldValidationSupport.alertClass(severity);
                    validationMessage = FieldValidationSupport.messageFa(severity);
                }
            }
            if (fd != null && LocationValues.isLocationField(fd.getDataType())) {
                rows.add(new FormFieldRow(label, formatLocation(e.getValue()), unit));
                continue;
            }
            if (fd != null && AttachmentKind.forFieldDataType(fd.getDataType()) != null) {
                List<AttachmentView> media = resolveAttachments(e.getValue(), attachmentsById);
                rows.add(new FormFieldRow(label, mediaSummary(media), unit, null, null, media));
                continue;
            }
            rows.add(new FormFieldRow(label, formatValue(e.getValue()), unit, alertClass, validationMessage));
        }
        return rows;
    }

    public List<FormFieldRow> rowsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});
            List<FormFieldRow> rows = new ArrayList<>();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                rows.add(new FormFieldRow(e.getKey(), formatValue(e.getValue()), null));
            }
            return rows;
        } catch (Exception ex) {
            return List.of(new FormFieldRow("داده", json, null));
        }
    }

    /** True when the entry has at least one non-blank form value (for UI highlighting). */
    public boolean hasMeaningfulData(Object formData) {
        return FormDataValidationSupport.hasMeaningfulFormData(asMap(formData));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object formData) {
        if (formData == null) return Collections.emptyMap();
        if (formData instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (formData instanceof String s) {
            try {
                return objectMapper.readValue(s, new TypeReference<>() {});
            } catch (Exception ex) {
                return Map.of("raw", s);
            }
        }
        return Map.of("value", String.valueOf(formData));
    }

    /**
     * Turns a field's attachment ids into displayable metadata.
     *
     * <p>Ids that no longer resolve are kept rather than dropped: a reference with no row is a
     * fact worth showing, and silently omitting it would make a partially-restored sheet look
     * complete.
     */
    private static List<AttachmentView> resolveAttachments(
            Object value, Map<String, Attachment> attachmentsById) {
        List<String> ids = AttachmentReferences.idsOf(value);
        List<AttachmentView> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Attachment a = attachmentsById.get(id);
            if (a == null) {
                out.add(new AttachmentView(id, null, null, null, null, true));
                continue;
            }
            out.add(new AttachmentView(
                    a.getId(),
                    a.getKind() != null ? a.getKind().name() : null,
                    a.getMimeType(),
                    a.getSizeBytes(),
                    a.getDurationMs(),
                    false));
        }
        return out;
    }

    /** Fallback text for a media field, used when the page cannot render players. */
    private static String mediaSummary(List<AttachmentView> media) {
        if (media.isEmpty()) return "—";
        return media.size() + " پیوست";
    }

    private static String formatValue(Object v) {
        if (v == null) return "—";
        if (v instanceof Boolean b) return b ? "بله" : "خیر";
        return String.valueOf(v);
    }

    /**
     * A coordinate as text.
     *
     * <p>Deliberately not a map link. The panel runs on plant networks with no route to the
     * internet, so a tile server or a maps.google.com link would fail precisely where it is
     * used — and a dead link is worse than plain numbers an operator can read out on the radio.
     */
    private static String formatLocation(Object v) {
        LocationValues.Coordinate coordinate = LocationValues.parse(v);
        if (coordinate == null) return "—";
        StringBuilder sb = new StringBuilder(coordinate.display());
        if (coordinate.accuracyMeters() != null) {
            sb.append(" (±").append(Math.round(coordinate.accuracyMeters())).append(" m)");
        }
        return sb.toString();
    }
}
