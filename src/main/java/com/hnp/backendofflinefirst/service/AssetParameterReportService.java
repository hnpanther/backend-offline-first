package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.AssetParameterChartPoint;
import com.hnp.backendofflinefirst.dto.AssetParameterChartSeries;
import com.hnp.backendofflinefirst.dto.AssetParameterReadingRow;
import com.hnp.backendofflinefirst.dto.AssetParameterValueRow;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.util.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssetParameterReportService {

    private final LogSheetEntryRepository logSheetEntryRepository;
    private final AssetEntryRepository assetEntryRepository;
    private final AssetAccessService assetAccessService;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final DateUtils dateUtils;

    public Optional<AssetEntry> findAsset(Long assetId) {
        return assetAccessService.findVisible(assetId);
    }

    public List<FieldDefinition> fieldDefinitionsForAsset(AssetEntry asset) {
        if (asset == null || asset.getClassId() == null) {
            return List.of();
        }
        return fieldDefinitionRepository.findByClassId(asset.getClassId()).stream()
                .filter(fd -> !fd.isDeleted())
                .sorted(Comparator
                        .comparing((FieldDefinition fd) -> fd.getOrder() != null ? fd.getOrder() : Integer.MAX_VALUE)
                        .thenComparing(FieldDefinition::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public Page<AssetParameterValueRow> buildValueHistoryPage(Long assetId,
                                                               String fieldKey,
                                                               Long from,
                                                               Long to,
                                                               Pageable pageable) {
        if (assetId == null) {
            return Page.empty(pageable);
        }
        Page<Object[]> rawPage = logSheetEntryRepository.findSubmittedReadingRowsByAssetId(
                assetId, LogSheetStatus.SUBMITTED, from, to, pageable);
        Map<String, FieldDefinition> defsByKey = definitionsByKey(assetId);
        List<AssetParameterValueRow> rows = new ArrayList<>();
        for (Object[] raw : rawPage.getContent()) {
            rows.addAll(flattenReading(toReadingRow(raw), defsByKey, fieldKey));
        }
        return new PageImpl<>(rows, pageable, rawPage.getTotalElements());
    }

    public Optional<AssetParameterChartSeries> buildChartSeries(Long assetId,
                                                                 String fieldKey,
                                                                 Long from,
                                                                 Long to) {
        if (assetId == null || fieldKey == null || fieldKey.isBlank()) {
            return Optional.empty();
        }
        if (findAsset(assetId).isEmpty()) {
            return Optional.empty();
        }
        Map<String, FieldDefinition> defsByKey = definitionsByKey(assetId);
        FieldDefinition field = defsByKey.get(fieldKey);
        if (field == null || !"number".equals(field.getDataType())) {
            return Optional.empty();
        }

        List<AssetParameterChartPoint> points = new ArrayList<>();
        for (AssetParameterReadingRow row : loadReadingRowsAsc(assetId, from, to)) {
            if (row.formData() == null || !row.formData().containsKey(fieldKey)) {
                continue;
            }
            Double numeric = toDouble(row.formData().get(fieldKey));
            if (numeric == null || row.recordedAt() == null) {
                continue;
            }
            points.add(new AssetParameterChartPoint(row.recordedAt(), numeric, row.logSheetId()));
        }
        if (points.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> validation = field.getValidation();
        return Optional.of(new AssetParameterChartSeries(
                fieldKey,
                field.getLabel() != null ? field.getLabel() : fieldKey,
                field.getUnit(),
                FieldValidationSupport.rangeMin(validation, FieldValidationSupport.KEY_WARNING),
                FieldValidationSupport.rangeMax(validation, FieldValidationSupport.KEY_WARNING),
                FieldValidationSupport.rangeMin(validation, FieldValidationSupport.KEY_DANGER),
                FieldValidationSupport.rangeMax(validation, FieldValidationSupport.KEY_DANGER),
                points
        ));
    }

    public long countSubmittedReadings(Long assetId, Long from, Long to) {
        if (assetId == null) {
            return 0;
        }
        return logSheetEntryRepository.findSubmittedReadingRowsByAssetId(
                assetId, LogSheetStatus.SUBMITTED, from, to, Pageable.ofSize(1)).getTotalElements();
    }

    private List<AssetParameterReadingRow> loadReadingRowsAsc(Long assetId, Long from, Long to) {
        return logSheetEntryRepository
                .findSubmittedReadingRowsByAssetIdAsc(assetId, LogSheetStatus.SUBMITTED, from, to)
                .stream()
                .map(this::toReadingRow)
                .toList();
    }

    private AssetParameterReadingRow toReadingRow(Object[] row) {
        @SuppressWarnings("unchecked")
        Map<String, Object> formData = row[5] instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        return new AssetParameterReadingRow(
                (Long) row[0],
                (Long) row[1],
                (Long) row[2],
                (String) row[3],
                (String) row[4],
                formData
        );
    }

    private Map<String, FieldDefinition> definitionsByKey(Long assetId) {
        return findAsset(assetId)
                .map(this::fieldDefinitionsForAsset)
                .map(defs -> {
                    Map<String, FieldDefinition> map = new LinkedHashMap<>();
                    for (FieldDefinition fd : defs) {
                        if (fd.getKey() != null) {
                            map.put(fd.getKey(), fd);
                        }
                    }
                    return map;
                })
                .orElse(Map.of());
    }

    private List<AssetParameterValueRow> flattenReading(AssetParameterReadingRow reading,
                                                         Map<String, FieldDefinition> defsByKey,
                                                         String fieldKeyFilter) {
        if (reading.formData() == null || reading.formData().isEmpty()) {
            return List.of();
        }
        List<AssetParameterValueRow> rows = new ArrayList<>();
        String timeLabel = dateUtils.format(reading.recordedAt());
        for (Map.Entry<String, Object> entry : reading.formData().entrySet()) {
            if (fieldKeyFilter != null && !fieldKeyFilter.isBlank() && !fieldKeyFilter.equals(entry.getKey())) {
                continue;
            }
            FieldDefinition fd = defsByKey.get(entry.getKey());
            String label = fd != null && fd.getLabel() != null ? fd.getLabel() : entry.getKey();
            String unit = fd != null ? fd.getUnit() : null;
            FieldValidationSeverity severity = FieldValidationSeverity.OK;
            if (fd != null && "number".equals(fd.getDataType())) {
                severity = FieldValidationSupport.evaluateNumericValue(entry.getValue(), fd.getValidation());
            }
            rows.add(new AssetParameterValueRow(
                    reading.recordedAt(),
                    timeLabel,
                    entry.getKey(),
                    label,
                    formatValue(entry.getValue()),
                    unit,
                    severity,
                    reading.logSheetId(),
                    reading.templateName(),
                    reading.operatorName()
            ));
        }
        return rows;
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                parts.add(String.valueOf(item));
            }
            return String.join("، ", parts);
        }
        return String.valueOf(value);
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
