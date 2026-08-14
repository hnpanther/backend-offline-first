package com.hnp.backendofflinefirst.audit;

import com.hnp.backendofflinefirst.domain.AuditAction;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.Hibernate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reflection helpers for entity audit: type/id resolution and field-level diff.
 * <p>
 * Old state for UPDATE must be an independent {@link Map} snapshot (not a managed
 * entity reference); otherwise Hibernate's persistence context returns the already
 * mutated instance and diffs appear empty.
 */
public final class AuditEntitySupport {

    /**
     * Types whose writes never produce an audit row.
     * <p>
     * The import job types are excluded for a reason beyond noise. {@code ImportJob} is
     * re-saved every 25 rows by the progress listener and once more by
     * {@code complete()}/{@code fail()}; {@code ImportJobError} is saved once per stored
     * error row (up to {@code app.import.max-stored-errors}). On a real 9,942-row asset
     * import those two accounted for 2,574 of 4,503 audit rows — and, far worse, the audit
     * queue they filled was what then rejected the very {@code save} that writes the job's
     * final status, leaving the row stuck at RUNNING forever. Keeping the bookkeeping of a
     * job out of the audit trail makes writing that job's status independent of the audit
     * pipeline, which is what a status write has to be. The imported entities themselves
     * (assets, locations, …) are still fully audited.
     */
    private static final Set<String> EXCLUDED_TYPES = Set.of(
            "AuditLog",
            "LogSheetActionLog",
            "LogSheetEntry",
            "LogSheetVoidSubmission",
            "ImportJob",
            "ImportJobError"
    );

    private static final Set<String> SKIPPED_FIELDS = Set.of(
            "createdAt", "updatedAt", "recordedAt", "actionAt", "syncedAt"
    );

    private static final Set<String> MASKED_FIELDS = Set.of(
            "passwordHash", "password"
    );

    private AuditEntitySupport() {
    }

    public static boolean shouldAudit(Object entity) {
        if (entity == null) {
            return false;
        }
        Class<?> type = Hibernate.getClass(entity);
        return !EXCLUDED_TYPES.contains(type.getSimpleName());
    }

    public static String entityType(Object entity) {
        Class<?> type = Hibernate.getClass(entity);
        Table table = type.getAnnotation(Table.class);
        if (table != null && table.name() != null && !table.name().isBlank()) {
            return table.name();
        }
        return type.getSimpleName();
    }

    public static String entityId(Object entity) {
        if (entity == null) {
            return null;
        }
        Class<?> type = Hibernate.getClass(entity);
        IdClass idClass = type.getAnnotation(IdClass.class);
        if (idClass != null) {
            List<String> parts = new ArrayList<>();
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    try {
                        Object val = field.get(entity);
                        parts.add(field.getName() + "=" + val);
                    } catch (IllegalAccessException ignored) {
                        parts.add(field.getName() + "=?");
                    }
                }
            }
            return String.join(",", parts);
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
                field.setAccessible(true);
                try {
                    Object val = field.get(entity);
                    return val != null ? String.valueOf(val) : null;
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Copies auditable scalar field values into a new map. Safe to keep after the
     * source entity is mutated or flushed.
     */
    public static Map<String, Object> captureFieldValues(Object entity) {
        if (entity == null) {
            return null;
        }
        Class<?> type = Hibernate.getClass(entity);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : auditFields(type)) {
            String name = field.getName();
            if (SKIPPED_FIELDS.contains(name)) {
                continue;
            }
            values.put(name, readField(entity, field));
        }
        return values;
    }

    /**
     * Builds a field snapshot from Hibernate's original loaded state (pre-dirty values).
     * Returns {@code null} when the entity is not managed or loaded state is unavailable.
     */
    public static Map<String, Object> captureFromLoadedState(
            Object entity,
            String[] propertyNames,
            Object[] loadedState) {
        if (entity == null || propertyNames == null || loadedState == null) {
            return null;
        }
        if (propertyNames.length != loadedState.length) {
            return null;
        }
        Class<?> type = Hibernate.getClass(entity);
        Map<String, Object> byProperty = new LinkedHashMap<>();
        for (int i = 0; i < propertyNames.length; i++) {
            byProperty.put(propertyNames[i], loadedState[i]);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : auditFields(type)) {
            String name = field.getName();
            if (SKIPPED_FIELDS.contains(name)) {
                continue;
            }
            if (byProperty.containsKey(name)) {
                values.put(name, byProperty.get(name));
            } else if (field.isAnnotationPresent(Id.class)) {
                // Identifiers live outside loadedState; current id is stable for UPDATE.
                values.put(name, readField(entity, field));
            }
        }
        return values;
    }

    public static List<AuditFieldChange> diff(Object oldEntity, Object newEntity, AuditAction action) {
        if (oldEntity == null && newEntity == null) {
            return List.of();
        }

        Map<String, Object> oldVals = asValueMap(oldEntity);
        Map<String, Object> newVals = asValueMap(newEntity);

        Object typedSample = firstNonMap(newEntity, oldEntity);
        List<String> fieldNames;
        if (typedSample != null) {
            fieldNames = new ArrayList<>();
            for (Field field : auditFields(Hibernate.getClass(typedSample))) {
                String name = field.getName();
                if (!SKIPPED_FIELDS.contains(name)) {
                    fieldNames.add(name);
                }
            }
        } else {
            fieldNames = mapKeys(oldVals, newVals);
        }
        return diffMaps(oldVals, newVals, action, fieldNames);
    }

    private static List<AuditFieldChange> diffMaps(
            Map<String, Object> oldVals,
            Map<String, Object> newVals,
            AuditAction action,
            Iterable<String> fieldNames) {
        List<AuditFieldChange> changes = new ArrayList<>();
        for (String name : fieldNames) {
            Object oldVal = oldVals != null ? oldVals.get(name) : null;
            Object newVal = newVals != null ? newVals.get(name) : null;

            if (action == AuditAction.CREATE) {
                if (newVal != null && !Objects.equals("", String.valueOf(newVal))) {
                    changes.add(new AuditFieldChange(name, null, formatValue(name, newVal)));
                }
            } else if (action == AuditAction.DELETE) {
                if (oldVal != null) {
                    changes.add(new AuditFieldChange(name, formatValue(name, oldVal), null));
                }
            } else if (!Objects.equals(oldVal, newVal)) {
                changes.add(new AuditFieldChange(name, formatValue(name, oldVal), formatValue(name, newVal)));
            }
        }
        return changes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asValueMap(Object entityOrMap) {
        if (entityOrMap == null) {
            return null;
        }
        if (entityOrMap instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return captureFieldValues(entityOrMap);
    }

    private static Object firstNonMap(Object primary, Object secondary) {
        if (primary != null && !(primary instanceof Map)) {
            return primary;
        }
        if (secondary != null && !(secondary instanceof Map)) {
            return secondary;
        }
        return null;
    }

    private static List<String> mapKeys(Map<String, Object> oldVals, Map<String, Object> newVals) {
        LinkedHashMap<String, Boolean> keys = new LinkedHashMap<>();
        if (oldVals != null) {
            oldVals.keySet().forEach(k -> keys.put(k, Boolean.TRUE));
        }
        if (newVals != null) {
            newVals.keySet().forEach(k -> keys.put(k, Boolean.TRUE));
        }
        return new ArrayList<>(keys.keySet());
    }

    static List<Field> auditFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (Collection.class.isAssignableFrom(field.getType()) || Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            fields.add(field);
        }
        return fields;
    }

    private static Object readField(Object entity, Field field) {
        if (entity == null) {
            return null;
        }
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static String formatValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        if (MASKED_FIELDS.contains(fieldName)) {
            return "***";
        }
        String s = String.valueOf(value);
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
