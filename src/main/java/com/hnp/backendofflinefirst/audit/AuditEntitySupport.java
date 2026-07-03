package com.hnp.backendofflinefirst.audit;

import com.hnp.backendofflinefirst.domain.AuditAction;
import com.hnp.backendofflinefirst.entity.*;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.Hibernate;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Reflection helpers for entity audit: type/id resolution and field-level diff.
 */
public final class AuditEntitySupport {

    private static final Set<String> EXCLUDED_TYPES = Set.of(
            "AuditLog",
            "LogSheetActionLog",
            "LogSheetEntry",
            "LogSheetVoidSubmission"
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

    public static List<AuditFieldChange> diff(Object oldEntity, Object newEntity, AuditAction action) {
        Object sample = newEntity != null ? newEntity : oldEntity;
        if (sample == null) {
            return List.of();
        }
        Class<?> type = Hibernate.getClass(sample);
        List<AuditFieldChange> changes = new ArrayList<>();

        for (Field field : auditFields(type)) {
            String name = field.getName();
            if (SKIPPED_FIELDS.contains(name)) {
                continue;
            }
            Object oldVal = readField(oldEntity, field);
            Object newVal = readField(newEntity, field);

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

    private static List<Field> auditFields(Class<?> type) {
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
