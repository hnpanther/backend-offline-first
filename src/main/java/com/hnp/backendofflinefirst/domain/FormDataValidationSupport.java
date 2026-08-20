package com.hnp.backendofflinefirst.domain;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.service.AttachmentReferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side validation of submitted {@code formData} against frozen field definitions.
 * <p>
 * Warning and danger ranges are display-only (never block submit). Required fields,
 * type checks, and select-option membership block submission.
 */
public final class FormDataValidationSupport {

    public record ValidationIssue(String fieldKey, String message) {}

    private FormDataValidationSupport() {}

    public static List<ValidationIssue> validate(Map<String, Object> formData, List<FieldDefinition> fieldDefs) {
        if (fieldDefs == null || fieldDefs.isEmpty()) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (FieldDefinition field : fieldDefs) {
            if (field == null || field.getKey() == null || field.getKey().isBlank()) {
                continue;
            }
            Object value = formData != null ? formData.get(field.getKey()) : null;
            validateField(field, value, issues);
        }
        return issues;
    }

    /**
     * Validates an entry only when the operator actually entered data for it.
     * Completely blank assets on a multi-asset log sheet are allowed.
     */
    public static List<ValidationIssue> validateFilledEntry(Map<String, Object> formData,
                                                            List<FieldDefinition> fieldDefs) {
        if (!hasMeaningfulFormData(formData)) {
            return List.of();
        }
        return validate(formData, fieldDefs);
    }

    /**
     * Keeps only keys that exist in {@code fieldDefs}. Unknown client keys are dropped so
     * persisted formData stays aligned with the frozen snapshot.
     * <p>
     * When {@code fieldDefs} is {@code null}, empty, or has no usable keys, the result is an
     * empty map — an empty schema means no fields are allowed (not “accept anything”).
     *
     * <p>Attachment fields are additionally <b>normalised to the canonical reference object</b>
     * on the way through. Both parsers accept the looser shapes a client may send (a bare array
     * of ids, a single id as text), but what gets persisted should not depend on which client
     * wrote it — the web fill form posts repeated inputs while the app posts an object, and a
     * report reading the column later should not have to care. Idempotent: canonical in,
     * canonical out.
     */
    public static Map<String, Object> retainKnownKeys(Map<String, Object> formData,
                                                      List<FieldDefinition> fieldDefs) {
        if (formData == null) {
            return null;
        }
        if (fieldDefs == null || fieldDefs.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (FieldDefinition field : fieldDefs) {
            if (field != null && field.getKey() != null && !field.getKey().isBlank()) {
                allowed.add(field.getKey());
            }
        }
        if (allowed.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, FieldDefinition> defByKey = new LinkedHashMap<>();
        for (FieldDefinition field : fieldDefs) {
            if (field != null && field.getKey() != null && !field.getKey().isBlank()) {
                defByKey.put(field.getKey(), field);
            }
        }

        Map<String, Object> retained = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            if (entry.getKey() == null || !allowed.contains(entry.getKey())) {
                continue;
            }
            FieldDefinition def = defByKey.get(entry.getKey());
            if (def != null && AttachmentKind.forFieldDataType(def.getDataType()) != null) {
                retained.put(entry.getKey(),
                        AttachmentReferences.toValue(AttachmentReferences.idsOf(entry.getValue())));
                continue;
            }
            if (def != null && LocationValues.isLocationField(def.getDataType())) {
                LocationValues.Coordinate coordinate = LocationValues.parse(entry.getValue());
                // An unparseable value is dropped rather than normalised into a fake coordinate;
                // the validator above is what reports it, and this keeps junk out of the column.
                if (coordinate != null) {
                    retained.put(entry.getKey(), LocationValues.toValue(coordinate));
                }
                continue;
            }
            retained.put(entry.getKey(), entry.getValue());
        }
        return retained;
    }

    /**
     * Whether one stored value counts as an <b>answer</b>.
     *
     * <p><b>This is the single definition of "the operator answered this field", and everything
     * that needs the question must call it.</b> The rule used to exist in several places with
     * slightly different meanings, and one of them — the PWA merge asking
     * {@code Object.keys(formData).length > 0}, i.e. key presence rather than value presence —
     * cost real readings: once an entry held {@code {"Bar": "", "Status": ""}} that test was
     * permanently true, so the device's blank copy won every merge and then overwrote the
     * server's values. See V4's header for the full chain.
     *
     * <p>Not an answer: {@code null}, a blank or whitespace-only string, an empty collection,
     * and an attachment reference carrying no ids — the last one because
     * {@link AttachmentReferences#toValue} always produces the wrapper object, so an emptied
     * photo field is a non-empty {@code Map} that means "nothing attached".
     *
     * <p>An answer: everything else, explicitly including {@code 0} and {@code false}. A reading
     * of zero is a reading.
     */
    public static boolean isAnswered(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            // Only the attachment wrapper is judged by its contents; any other object (a
            // location coordinate, say) is an answer by virtue of existing.
            Object ids = m.get(AttachmentReferences.IDS_KEY);
            if (ids instanceof Collection<?> c) {
                return !c.isEmpty();
            }
            return !m.isEmpty();
        }
        return true;
    }

    /**
     * The same map with every unanswered key removed, so an untouched asset stores {@code {}}.
     *
     * <p>Applied by <b>both</b> write paths before persisting. The web fill form posts every
     * entry of the sheet on every save and the mobile submit resends every entry on the device,
     * so without this a single save writes blank keys onto assets nobody has opened — which is
     * exactly how the damage V4 repairs was created.
     *
     * <p>Clearing a field still works and still means what it says: the key disappears, and
     * {@link #isAnswered} treats an absent key and a blank one identically anyway.
     *
     * @return a new map; {@code null} in, {@code null} out
     */
    public static Map<String, Object> answeredOnly(Map<String, Object> formData) {
        if (formData == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : formData.entrySet()) {
            if (entry.getKey() != null && isAnswered(entry.getValue())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** Whether an entry holds any answer at all. Delegates to {@link #isAnswered}. */
    public static boolean hasMeaningfulFormData(Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return false;
        }
        for (Object value : formData.values()) {
            if (isAnswered(value)) {
                return true;
            }
        }
        return false;
    }

    public static String formatIssues(Long assetId, List<ValidationIssue> issues) {
        return formatIssues(assetId, null, null, issues);
    }

    /**
     * Builds a server-side validation message. Prefer human-readable asset name/code so web
     * operators can match the alert to the fill-page cards (not just opaque asset ids).
     */
    public static String formatIssues(Long assetId,
                                      String assetName,
                                      String assetCode,
                                      List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Form data validation failed (");
        sb.append(describeAsset(assetId, assetName, assetCode)).append("): ");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            ValidationIssue issue = issues.get(i);
            sb.append("field '").append(issue.fieldKey()).append("': ").append(issue.message());
        }
        return sb.toString();
    }

    static String describeAsset(Long assetId, String assetName, String assetCode) {
        boolean hasName = assetName != null && !assetName.isBlank();
        boolean hasCode = assetCode != null && !assetCode.isBlank();
        if (hasName && hasCode) {
            return "asset '" + assetName.trim() + "' / " + assetCode.trim();
        }
        if (hasName) {
            return "asset '" + assetName.trim() + "'";
        }
        if (hasCode) {
            return "asset '" + assetCode.trim() + "'";
        }
        return "assetId=" + assetId;
    }

    private static void validateField(FieldDefinition field, Object value, List<ValidationIssue> issues) {
        String key = field.getKey();
        String display = fieldDisplayName(field);
        String dataType = field.getDataType() != null ? field.getDataType() : "text";

        if (field.isRequired() && isBlank(value, dataType)) {
            issues.add(new ValidationIssue(display, "required field is missing"));
            return;
        }
        if (isBlank(value, dataType)) {
            return;
        }

        switch (dataType) {
            case "image", "audio", "video" -> validateAttachment(display, value, issues);
            case "location" -> validateLocation(display, value, issues);
            case "number" -> validateNumber(display, value, issues);
            case "select" -> validateSelect(display, value, field.getValidation(), false, issues);
            case "multiselect" -> validateSelect(display, value, field.getValidation(), true, issues);
            case "checkbox" -> validateCheckbox(display, value, issues);
            default -> { /* text, textarea, date, etc. — required check only */ }
        }
    }

    /**
     * An attachment field's value must be a reference, never the media itself.
     *
     * <p>The only failure worth rejecting here is a client that tried to inline the bytes —
     * a base64 blob in {@code form_data} would bloat every subsequent read of this sheet. A
     * well-formed reference holding no ids is not an error at this point: emptiness is what
     * the required check above already covers.
     */
    private static void validateAttachment(String display, Object value, List<ValidationIssue> issues) {
        if (value instanceof String s && s.length() > 512) {
            issues.add(new ValidationIssue(display, "attachment must be sent as ids, not inline data"));
            return;
        }
        if (AttachmentReferences.looksLikeAttachmentValue(value)
                || value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof String) {
            // The canonical object, plus the shapes an older client may have stored: a bare
            // array of ids, or a single id as text.
            return;
        }
        // Anything else — a number, a boolean — is not a reference. It would stringify into a
        // plausible-looking id ("42") that can never resolve, and would satisfy the required
        // check above while leaving the field genuinely unanswered.
        issues.add(new ValidationIssue(display, "attachment value is not a valid reference"));
    }

    /**
     * A location field must hold a usable WGS-84 coordinate.
     *
     * <p>Rejected rather than stored-as-is, because a coordinate outside the world's bounds is
     * not a degraded reading, it is corruption — and once written it would be indistinguishable
     * from a real place on every screen and report that shows it afterwards.
     */
    private static void validateLocation(String display, Object value, List<ValidationIssue> issues) {
        if (LocationValues.parse(value) == null) {
            issues.add(new ValidationIssue(display, "location must hold a valid lat/lng coordinate"));
        }
    }

    private static String fieldDisplayName(FieldDefinition field) {
        if (field.getLabel() != null && !field.getLabel().isBlank()) {
            return field.getLabel().trim();
        }
        return field.getKey();
    }

    private static void validateNumber(String key, Object value, List<ValidationIssue> issues) {
        if (toDouble(value) == null) {
            issues.add(new ValidationIssue(key, "must be a number"));
        }
        // Warning/danger ranges are evaluated only when rendering (FormDataViewHelper, charts).
    }

    private static void validateSelect(String key, Object value, Map<String, Object> validation,
                                       boolean multi, List<ValidationIssue> issues) {
        List<String> options = selectOptions(validation);
        if (options.isEmpty()) {
            return;
        }
        Set<String> allowed = new LinkedHashSet<>(options);
        if (multi) {
            Collection<?> values = asCollection(value);
            if (values.isEmpty()) {
                return;
            }
            for (Object item : values) {
                if (item == null || !allowed.contains(String.valueOf(item).trim())) {
                    issues.add(new ValidationIssue(key, "has an invalid option"));
                    return;
                }
            }
            return;
        }
        if (!allowed.contains(String.valueOf(value).trim())) {
            issues.add(new ValidationIssue(key, "has an invalid option"));
        }
    }

    private static void validateCheckbox(String key, Object value, List<ValidationIssue> issues) {
        if (value instanceof Boolean) {
            return;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "false".equals(text) || "1".equals(text) || "0".equals(text)) {
            return;
        }
        issues.add(new ValidationIssue(key, "must be a boolean value"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> selectOptions(Map<String, Object> validation) {
        if (validation == null || !validation.containsKey(FieldValidationSupport.KEY_OPTIONS)) {
            return List.of();
        }
        Object raw = validation.get(FieldValidationSupport.KEY_OPTIONS);
        if (!(raw instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (Object item : collection) {
            if (item != null) {
                String option = String.valueOf(item).trim();
                if (!option.isEmpty()) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private static Collection<?> asCollection(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value);
    }

    private static boolean isBlank(Object value, String dataType) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if ("location".equals(dataType)) {
            // A well-formed but empty {"type":"location"} is exactly as unanswered as a null —
            // which is what the control produces before the operator presses "capture".
            return LocationValues.parse(value) == null;
        }
        if ("image".equals(dataType) || "audio".equals(dataType) || "video".equals(dataType)) {
            // "Answered" means at least one attachment id — a reference object with an empty
            // list is exactly as unanswered as a null.
            return AttachmentReferences.idsOf(value).isEmpty();
        }
        if ("checkbox".equals(dataType)) {
            if (value instanceof Boolean b) {
                return !b;
            }
            String text = String.valueOf(value).trim().toLowerCase();
            return text.isEmpty() || "false".equals(text) || "0".equals(text);
        }
        return false;
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
