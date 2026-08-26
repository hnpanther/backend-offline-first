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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders formData JSON as labeled key/value rows for the UI. */
@Component("formDataView")
@RequiredArgsConstructor
public class FormDataViewHelper {

    private final ObjectMapper objectMapper;

    public record FormFieldRow(String label, String value, String unit,
                               String validationAlertClass, String validationMessage,
                               List<AttachmentView> attachments) {

        /**
         * True when the operator recorded nothing for this parameter.
         *
         * <p>Worth its own flag rather than comparing the rendered text: an unfilled row used to
         * show only its unit («°C» and nothing else), which reads exactly like a filled row whose
         * value happened to be invisible. On a 50-asset sheet that made "which readings are
         * actually missing" impossible to answer at a glance.
         */
        public boolean isEmpty() {
            return !hasAttachments() && (value == null || value.isBlank() || "—".equals(value));
        }

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
                                 Long sizeBytes, Long durationMs, boolean missing,
                                 boolean removed) {

        /** Everything but the two flags, for a file that is present and streamable. */
        public AttachmentView(String id, String kind, String mimeType, Long sizeBytes, Long durationMs) {
            this(id, kind, mimeType, sizeBytes, durationMs, false, false);
        }

        /**
         * An id nothing can describe — no row, no snapshot. Could be storage loss, could be a
         * deletion from before revisions carried a snapshot; the page must not guess which.
         */
        public static AttachmentView unknown(String id) {
            return new AttachmentView(id, null, null, null, null, true, false);
        }

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

        List<FormFieldRow> rows = new ArrayList<>();
        Map<String, FieldDefinition> defByKey = defsByKey(fieldDefs);
        for (Map.Entry<String, Object> e : data.entrySet()) {
            rows.add(row(e.getKey(), e.getValue(), defByKey.get(e.getKey()), attachmentsById, Set.of()));
        }
        return rows;
    }

    /**
     * Every parameter the sheet's schema defines, answered or not.
     *
     * <h2>Why this is not what {@link #rows} does</h2>
     *
     * {@code rows} walks {@code form_data}, and since V3 that map holds <b>only fields that were
     * actually answered</b> — an unanswered key is absent, not blank. That is deliberate and must
     * stay: it is what stops one supervisor save writing {@code {"Bar": "", "Status": ""}} onto
     * all forty entries of a sheet, and what makes {@code max_severity IS NOT NULL} an exact test
     * for "this entry carries a reading".
     *
     * <p>But it means a display built on those keys can only ever show what was filled. An asset
     * with three of its seven parameters recorded rendered three rows, and the four the operator
     * skipped were <b>indistinguishable from parameters that do not exist</b> — the one question
     * a supervisor is reading the page to answer.
     *
     * <p>So the schema drives the list here, not the data. Every field appears, in the order the
     * class defines ({@code order}), and an unanswered one comes back with a null value —
     * {@link FormFieldRow#isEmpty()} is already true for that and the fragment already renders it
     * as «ثبت نشده».
     *
     * <h2>Keys the schema does not know</h2>
     *
     * Appended after the schema's own fields rather than dropped. A sheet generated before
     * {@code retainKnownKeys} existed, or one whose class lost a field afterwards, can hold a
     * reading whose definition is gone (see {@code roadmap.md} §5). Hiding it because the schema
     * moved on would quietly delete a measurement from the record — the opposite of what this
     * method is for.
     */
    public List<FormFieldRow> allRows(Object formData, List<FieldDefinition> fieldDefs,
                                      Map<String, Attachment> attachmentsById) {
        return allRows(formData, fieldDefs, attachmentsById, Set.of());
    }

    private List<FormFieldRow> allRows(Object formData, List<FieldDefinition> fieldDefs,
                                       Map<String, Attachment> attachmentsById,
                                       Set<String> removedIds) {
        if (fieldDefs == null || fieldDefs.isEmpty()) {
            return rows(formData, fieldDefs, attachmentsById);
        }
        Map<String, Object> data = asMap(formData);
        List<FormFieldRow> rows = new ArrayList<>();
        Set<String> rendered = new LinkedHashSet<>();

        for (FieldDefinition fd : fieldDefs) {
            String key = fd.getKey();
            if (key == null || !rendered.add(key)) continue;
            rows.add(row(key, data.get(key), fd, attachmentsById, removedIds));
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (rendered.contains(e.getKey())) continue;
            rows.add(row(e.getKey(), e.getValue(), null, attachmentsById, removedIds));
        }
        return rows;
    }

    private Map<String, FieldDefinition> defsByKey(List<FieldDefinition> fieldDefs) {
        Map<String, FieldDefinition> defByKey = new LinkedHashMap<>();
        if (fieldDefs != null) {
            for (FieldDefinition fd : fieldDefs) {
                if (fd.getKey() != null) {
                    defByKey.put(fd.getKey(), fd);
                }
            }
        }
        return defByKey;
    }

    /**
     * One parameter, rendered.
     *
     * <p>{@code value} is null for a parameter nobody answered, which every branch below has to
     * survive: {@code formatValue(null)} gives the em dash {@code isEmpty()} recognises,
     * {@code resolveAttachments} gives an empty list, and the numeric evaluator is skipped
     * outright — a band cannot be breached by an absent reading, and asking would have coloured
     * every unfilled row as though it had.
     */
    private FormFieldRow row(String key, Object value, FieldDefinition fd,
                             Map<String, Attachment> attachmentsById, Set<String> removedIds) {
        String label = fd != null && fd.getLabel() != null ? fd.getLabel() : key;
        String unit = fd != null ? fd.getUnit() : null;

        if (fd != null && LocationValues.isLocationField(fd.getDataType())) {
            return new FormFieldRow(label, value == null ? null : formatLocation(value), unit);
        }
        if (fd != null && AttachmentKind.forFieldDataType(fd.getDataType()) != null) {
            List<AttachmentView> media = value == null
                    ? List.of() : resolveAttachments(value, attachmentsById, removedIds);
            return new FormFieldRow(label, mediaSummary(media), unit, null, null, media);
        }

        String alertClass = null;
        String validationMessage = null;
        if (value != null && fd != null && "number".equals(fd.getDataType())) {
            FieldValidationSeverity severity =
                    FieldValidationSupport.evaluateNumericValue(value, fd.getValidation());
            if (severity != FieldValidationSeverity.OK) {
                alertClass = FieldValidationSupport.alertClass(severity);
                validationMessage = FieldValidationSupport.messageFa(severity);
            }
        }
        return new FormFieldRow(label, value == null ? null : formatValue(value),
                unit, alertClass, validationMessage);
    }

    /**
     * A superseded value's rows, with what its attachments <em>were</em> filled in from the
     * revision's own snapshot.
     *
     * <p>{@code allRows} resolves attachment ids against the sheet's live rows, and a correction
     * that removed a photo removed those rows too — so the history could only ever say «فایل
     * پیوست در دسترس نیست», which reads exactly like storage having lost the file. The snapshot
     * taken when the revision was written is the only surviving description of what was deleted.
     *
     * <p>The live map still wins where it has the id: an attachment that was merely detached from
     * the field, not deleted, can still be opened, and showing a thumbnail beats showing its size.
     *
     * @param attachmentSnapshot {@code {attachmentId: {kind, mimeType, sizeBytes, durationMs, …}}},
     *        or null for a revision whose value referenced no attachments
     */
    public List<FormFieldRow> revisionRows(Object formData, List<FieldDefinition> fieldDefs,
                                           Map<String, Attachment> attachmentsById,
                                           Map<String, Map<String, Object>> attachmentSnapshot) {
        Map<String, Attachment> resolved = attachmentsById == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(attachmentsById);
        Set<String> removed = new LinkedHashSet<>();
        if (attachmentSnapshot != null) {
            attachmentSnapshot.forEach((id, meta) -> {
                // The live row wins where it still exists: an attachment merely detached from the
                // field can still be opened, and a thumbnail beats a description of one.
                if (resolved.containsKey(id)) return;
                resolved.put(id, fromSnapshot(id, meta));
                removed.add(id);
            });
        }
        return allRows(formData, fieldDefs, resolved, removed);
    }

    /**
     * Rebuilds just enough of an {@link Attachment} to describe one that no longer exists.
     *
     * <p>Deliberately keeps the real id, so the caption is honest, and carries no storage key —
     * nothing can be streamed from it. What stops the template offering a link is the
     * {@code removed} flag its id carries, not anything on this object: an {@link Attachment}
     * that looks complete would otherwise render as a thumbnail pointing at a 404. What this adds
     * over «در دسترس نیست» is the kind, the size and the duration — enough for a reviewer to know
     * a two-minute voice note was removed rather than a photo.
     *
     * <p>A null or empty {@code meta} still yields a usable row: the id and the removed flag are
     * the part that matters, and a snapshot written by an older build may carry less than this
     * one reads.
     */
    private Attachment fromSnapshot(String id, Map<String, Object> meta) {
        Attachment a = new Attachment();
        a.setId(id);
        if (meta == null) {
            return a;
        }
        Object kind = meta.get("kind");
        if (kind != null) {
            try {
                a.setKind(AttachmentKind.valueOf(String.valueOf(kind)));
            } catch (IllegalArgumentException ignored) {
                // A kind this build does not know. The row still renders as a removed attachment.
            }
        }
        a.setMimeType(asString(meta.get("mimeType")));
        a.setSizeBytes(asLong(meta.get("sizeBytes")));
        a.setDurationMs(asLong(meta.get("durationMs")));
        a.setWidth(asInt(meta.get("width")));
        a.setHeight(asInt(meta.get("height")));
        a.setUploadedAt(asLong(meta.get("uploadedAt")));
        a.setCreatedByUserId(asLong(meta.get("createdByUserId")));
        return a;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
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
    /**
     * @param removedIds ids described only by a revision's snapshot — the attachment itself is
     *        gone. They render with their kind, size and duration but must never be offered as a
     *        link: the bytes do not exist, and a thumbnail pointing at a 404 is worse than saying
     *        so.
     */
    private static List<AttachmentView> resolveAttachments(
            Object value, Map<String, Attachment> attachmentsById, Set<String> removedIds) {
        List<String> ids = AttachmentReferences.idsOf(value);
        List<AttachmentView> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            Attachment a = attachmentsById.get(id);
            if (a == null) {
                out.add(AttachmentView.unknown(id));
                continue;
            }
            out.add(new AttachmentView(
                    a.getId(),
                    a.getKind() != null ? a.getKind().name() : null,
                    a.getMimeType(),
                    a.getSizeBytes(),
                    a.getDurationMs(),
                    false,
                    removedIds.contains(id)));
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
        if (v instanceof java.util.Collection<?> c) {
            // A multiselect reading is a list. String.valueOf would print Java's own rendering,
            // brackets and all — "[on, IDLE]" is not something to show an operator.
            return c.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(o -> String.valueOf(o).trim())
                    .filter(o -> !o.isEmpty())
                    .collect(java.util.stream.Collectors.joining("، "));
        }
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
