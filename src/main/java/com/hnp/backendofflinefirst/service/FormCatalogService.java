package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.LookupValues;
import com.hnp.backendofflinefirst.dto.FormDefinitionDto;
import com.hnp.backendofflinefirst.dto.LogSheetContextDto.RequiredLookup;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.repository.FormDefinitionUnitRepository;
import com.hnp.backendofflinefirst.repository.FormFieldRepository;
import com.hnp.backendofflinefirst.repository.LookupListRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FormCatalogService {
    private final FormAccessService access;
    private final FormFieldRepository fields;
    private final FormDefinitionUnitRepository units;
    private final LookupListRepository lookups;
    private final FormSchemaService schemas;

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public List<FormDefinitionDto> fillable(Long userId, Long formId) {
        var forms = access.fillableForms(userId).stream()
                .filter(form -> formId == null || formId.equals(form.getId())).toList();
        if (forms.isEmpty()) return List.of();
        var ids = forms.stream().map(FormDefinition::getId).toList();
        Map<Long, List<FieldDefinitionSnapshot>> snapshots = new LinkedHashMap<>();
        ids.forEach(id -> snapshots.put(id, new ArrayList<>()));
        for (var field : fields.findByFormDefinitionIdInAndDeletedFalseOrderByOrderAscIdAsc(ids)) {
            snapshots.get(field.getFormDefinitionId()).add(FieldDefinitionSnapshot.from(field.toFieldDefinition()));
        }
        snapshots.values().forEach(list -> list.sort(FieldDefinitionSnapshot.displayOrder()));
        var versions = schemas.publishAll(snapshots);
        Map<Long, Set<Long>> unitIds = new HashMap<>();
        for (var link : units.findByFormDefinitionIdIn(ids)) {
            unitIds.computeIfAbsent(link.getFormDefinitionId(), ignored -> new HashSet<>()).add(link.getOperationalUnitId());
        }
        Map<Long, List<String>> requiredCodes = new HashMap<>();
        Set<String> codes = new HashSet<>();
        snapshots.forEach((id, list) -> {
            var required = list.stream().filter(f -> LookupValues.isLookupField(f.getDataType()))
                    .map(f -> FieldValidationSupport.lookupList(f.getValidation())).filter(Objects::nonNull)
                    .map(code -> code.toLowerCase(Locale.ROOT)).distinct().toList();
            requiredCodes.put(id, required);
            codes.addAll(required);
        });
        Map<String, RequiredLookup> byCode = new HashMap<>();
        if (!codes.isEmpty()) {
            for (var list : lookups.findByLowerCodes(codes)) {
                byCode.put(list.getCode().toLowerCase(Locale.ROOT), new RequiredLookup(list.getCode(), list.getName(), list.getRevision()));
            }
        }
        return forms.stream().map(form -> {
            var id = form.getId();
            var required = requiredCodes.get(id).stream().map(byCode::get).filter(Objects::nonNull).toList();
            var dto = FormDefinitionDto.from(form, unitIds.getOrDefault(id, Set.of()), snapshots.get(id), required);
            dto.setSchemaVersionId(versions.get(id));
            return dto;
        }).toList();
    }
}
