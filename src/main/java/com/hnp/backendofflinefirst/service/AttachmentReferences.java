package com.hnp.backendofflinefirst.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading attachment references out of an entry's {@code form_data}.
 *
 * <p>An image/audio field stores <em>ids</em>, never bytes:
 * <pre>{ "pump_photo": { "type": "attachment", "ids": ["a7f3…", "b2c1…"] } }</pre>
 *
 * <p>Base64 in {@code form_data} was rejected deliberately — it inflates by a third and puts
 * binaries inside a {@code jsonb} column that every log-sheet read, every bundle sync and
 * every backup then carries. At the project's own target load that is tens of gigabytes a year
 * inside the database rather than on a disk where it belongs.
 *
 * <p>Parsing is tolerant on shape and strict on content: a bare string or a plain array is
 * accepted (older clients, hand-written data), but anything that is not a non-blank string id
 * is dropped rather than passed along as a phantom reference.
 */
public final class AttachmentReferences {

    public static final String TYPE_KEY = "type";
    public static final String TYPE_VALUE = "attachment";
    public static final String IDS_KEY = "ids";

    private AttachmentReferences() {}

    /**
      * Every field key in {@code formData} that carries attachment ids, with those ids.
      *
      * <p>Only values <em>shaped</em> like references count — the canonical object, or a plain
      * collection. A bare scalar is deliberately excluded even though {@link #idsOf} would
      * happily stringify it: {@code {"pressure": 42}} would otherwise be reported as holding
      * an attachment with id {@code "42"}, and a caller deleting "orphaned" files by this map
      * would act on nonsense. When you already know a field is attachment-typed, call
      * {@link #idsOf} directly; this method is for scanning form data blind.
      */
    public static Map<String, List<String>> extract(Map<String, Object> formData) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (formData == null || formData.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Object> e : formData.entrySet()) {
            Object value = e.getValue();
            if (!looksLikeAttachmentValue(value) && !(value instanceof Collection<?>)) {
                continue;
            }
            List<String> ids = idsOf(value);
            if (!ids.isEmpty()) {
                out.put(e.getKey(), ids);
            }
        }
        return out;
    }

    /**
     * Ids held by one field value.
     *
     * @return an empty list when the value holds none — used both to read references and, by
     *         the validator, to decide whether a required attachment field was answered.
     */
    public static List<String> idsOf(Object value) {
        List<String> ids = new ArrayList<>();
        if (value == null) {
            return ids;
        }
        if (value instanceof Map<?, ?> map) {
            Object rawIds = map.get(IDS_KEY);
            if (rawIds instanceof Collection<?> collection) {
                for (Object id : collection) {
                    addIfUsable(ids, id);
                }
            } else {
                addIfUsable(ids, rawIds);
            }
            return ids;
        }
        if (value instanceof Collection<?> collection) {
            for (Object id : collection) {
                addIfUsable(ids, id);
            }
            return ids;
        }
        addIfUsable(ids, value);
        return ids;
    }

    /** True when the value is shaped like an attachment reference, even if it holds no ids. */
    public static boolean looksLikeAttachmentValue(Object value) {
        return value instanceof Map<?, ?> map && TYPE_VALUE.equals(String.valueOf(map.get(TYPE_KEY)));
    }

    /** Canonical form written back so stored values are consistent regardless of client. */
    public static Map<String, Object> toValue(List<String> ids) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(TYPE_KEY, TYPE_VALUE);
        value.put(IDS_KEY, ids == null ? List.of() : List.copyOf(ids));
        return value;
    }

    private static void addIfUsable(List<String> target, Object candidate) {
        if (candidate == null) {
            return;
        }
        String id = String.valueOf(candidate).trim();
        // A blank or "null" entry is a client bug; keeping it would create a reference to an
        // attachment that can never resolve.
        if (id.isEmpty() || "null".equalsIgnoreCase(id) || target.contains(id)) {
            return;
        }
        target.add(id);
    }
}
