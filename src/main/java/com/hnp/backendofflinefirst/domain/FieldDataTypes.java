package com.hnp.backendofflinefirst.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data types a class field may have — one list, used everywhere one is needed.
 *
 * <h2>Why this exists</h2>
 *
 * <p>There used to be two hardcoded lists in {@code field-definitions.html}: ten options in the
 * create modal, six in the edit modal. They drifted, and the drift was not cosmetic. Editing a
 * field whose type was {@code image} found no matching {@code <option>}, so the browser fell back
 * to the first one — {@code number} — and the field silently changed type on save. Readings
 * already stored against it were attachment references, which a numeric field can neither
 * validate nor render; the PWA drew a number box where the photographs had been.
 *
 * <p>The list is therefore in one place, in Java, and both modals iterate it. A type added here
 * appears in both, or in neither.
 *
 * <h2>Not a Java enum, deliberately</h2>
 *
 * <p>{@code FieldDefinition.dataType} is a {@code String} in the database and travels as a string
 * to the PWA, which has its own {@code FieldDataType} union. Introducing an enum would mean a
 * converter and a migration for no gain, and would turn an unrecognised value read from an older
 * row into a boot failure rather than a field that renders as plain text. What the system
 * actually needs is a *whitelist for writes*, which is what {@link #isValid} provides.
 *
 * @see FormDataValidationSupport the reader side, which must keep tolerating unknown types
 */
public final class FieldDataTypes {

    /** Machine value to Persian label, in the order the dropdowns show them. */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    /** Wrapped once: {@link #labels()} is called on every render of the field list page. */
    private static final Map<String, String> LABELS_VIEW = Collections.unmodifiableMap(LABELS);

    static {
        LABELS.put("number", "عددی (number)");
        LABELS.put("text", "متنی (text)");
        LABELS.put("select", "انتخابی (select)");
        LABELS.put("multiselect", "چند انتخابی");
        LABELS.put("checkbox", "چک‌باکس");
        LABELS.put("textarea", "متن بلند");
        LABELS.put("image", "تصویر (عکس)");
        LABELS.put("audio", "صوت (ضبط صدا)");
        LABELS.put("video", "ویدئو");
        LABELS.put("location", "موقعیت جغرافیایی (GPS)");
    }

    private FieldDataTypes() {
    }

    /**
     * Every type, in display order, as {@code value -> label} for the dropdowns.
     *
     * <p>An unmodifiable <em>view</em> rather than {@code Map.copyOf}, which would be unordered —
     * and the order here is the order the operator reads down the list.
     */
    public static Map<String, String> labels() {
        return LABELS_VIEW;
    }

    /** Every type, in display order. */
    public static List<String> values() {
        return List.copyOf(LABELS.keySet());
    }

    /** The Persian label, falling back to the raw value for a type stored by an older build. */
    public static String label(String dataType) {
        return LABELS.getOrDefault(dataType, dataType);
    }

    /**
     * Whether a value may be *written* as a field's type.
     *
     * <p>Checked on the server because a dropdown is not a boundary: the form posts a string, and
     * a typo in a template or a hand-made request would otherwise store a type nothing can
     * render. Reads stay permissive — see the class comment.
     */
    public static boolean isValid(String dataType) {
        return dataType != null && LABELS.containsKey(dataType);
    }

    /**
     * The dropdown for a field that already exists: the standard types, plus its own if that is
     * not one of them.
     *
     * <p>Older rows can carry a type this build does not offer — {@code boolean} and {@code date}
     * were documented once, and an import or an older deployment may have written others. Such a
     * field must still be editable: leaving its type out of the list is precisely what caused the
     * reported bug, because a {@code <select>} with nothing selected submits its first option and
     * the save rewrites the field. So the value is carried, labelled as itself, and preselected.
     *
     * <p>It is offered only on the field that already has it. {@link #labels()} — the create
     * form's list — is unchanged, so a legacy type can be kept but never newly chosen.
     */
    public static Map<String, String> labelsIncluding(String currentDataType) {
        if (currentDataType == null || currentDataType.isBlank() || isValid(currentDataType)) {
            return LABELS_VIEW;
        }
        Map<String, String> withLegacy = new LinkedHashMap<>(LABELS);
        withLegacy.put(currentDataType, currentDataType);
        return Collections.unmodifiableMap(withLegacy);
    }

    /**
     * Whether this write may store {@code dataType} on a field currently typed {@code existing}.
     *
     * <p>A standard type is always allowed. An unknown one is allowed only when it is what the
     * field already had — so an editor can rename or reorder a legacy field without being
     * blocked, and without any request being able to *introduce* a type nothing renders.
     */
    public static boolean isValidFor(String dataType, String existing) {
        return isValid(dataType) || (dataType != null && dataType.equals(existing));
    }
}
