package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Captures field definitions when a log sheet is generated and resolves the frozen
 * schema for bundles, web UI, and submit-time validation.
 */
@Service
@RequiredArgsConstructor
public class LogSheetFieldDefinitionsService {

    private final FieldDefinitionRepository fieldDefinitionRepository;

    public List<FieldDefinitionSnapshot> captureSnapshot(Long classId) {
        if (classId == null) {
            return List.of();
        }
        return fieldDefinitionRepository.findByClassId(classId).stream()
                .filter(field -> !field.isDeleted())
                .sorted(snapshotOrder())
                .map(FieldDefinitionSnapshot::from)
                .toList();
    }

    public List<FieldDefinition> resolve(LogSheet sheet) {
        return resolveForClassIds(sheet, null);
    }

    public List<FieldDefinition> resolveForEntries(LogSheet sheet, Collection<LogSheetEntry> entries) {
        if (sheet.getFieldDefinitionsSnapshot() != null) {
            return resolveForClassIds(sheet, null);
        }
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Set<Long> classIds = entries.stream()
                .map(LogSheetEntry::getClassId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return loadLiveDefinitions(classIds);
    }

    public List<FieldDefinition> resolveForClass(LogSheet sheet, Long classId) {
        if (classId == null) {
            return List.of();
        }
        return resolveForClassIds(sheet, Set.of(classId));
    }

    public List<FieldDefinition> resolveForBundle(LogSheet sheet, Set<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return List.of();
        }
        return resolveForClassIds(sheet, classIds);
    }

    public Map<Long, List<FieldDefinition>> groupByClass(LogSheet sheet, Collection<LogSheetEntry> entries) {
        Map<Long, List<FieldDefinition>> grouped = new HashMap<>();
        if (entries == null) {
            return grouped;
        }
        for (LogSheetEntry entry : entries) {
            Long classId = entry.getClassId();
            if (classId != null) {
                grouped.computeIfAbsent(classId, id -> resolveForClass(sheet, id));
            }
        }
        return grouped;
    }

    private List<FieldDefinition> resolveForClassIds(LogSheet sheet, Set<Long> classIds) {
        if (sheet.getFieldDefinitionsSnapshot() != null) {
            return sheet.getFieldDefinitionsSnapshot().stream()
                    .filter(snapshot -> classIds == null || classIds.contains(snapshot.getClassId()))
                    .sorted(FieldDefinitionSnapshot.displayOrder())
                    .map(FieldDefinitionSnapshot::toFieldDefinition)
                    .toList();
        }
        return loadLiveDefinitions(classIds);
    }

    private List<FieldDefinition> loadLiveDefinitions(Set<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return List.of();
        }
        return fieldDefinitionRepository.findByClassIdIn(classIds).stream()
                .filter(field -> !field.isDeleted())
                .sorted(fieldOrder())
                .collect(Collectors.toList());
    }

    private static Comparator<FieldDefinition> fieldOrder() {
        return Comparator
                .comparing((FieldDefinition f) -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinition::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<FieldDefinition> snapshotOrder() {
        return Comparator
                .comparing((FieldDefinition f) -> f.getOrder() != null ? f.getOrder() : Integer.MAX_VALUE)
                .thenComparing(FieldDefinition::getKey, Comparator.nullsLast(String::compareTo));
    }
}
