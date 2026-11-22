package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/** Fillable form definition for the tablet / panel fill UI. */
@Data
@Builder
public class FormDefinitionDto {
    private Long id;
    private String code;
    private String title;
    private String description;
    private boolean active;
    private Set<Long> subjectUnitIds;
    private List<FieldDefinitionSnapshot> fields;
    private List<LogSheetContextDto.RequiredLookup> requiredLookups;

    public static FormDefinitionDto from(FormDefinition form, Set<Long> subjectUnitIds,
                                         List<FieldDefinitionSnapshot> fields,
                                         List<LogSheetContextDto.RequiredLookup> requiredLookups) {
        return FormDefinitionDto.builder()
                .id(form.getId())
                .code(form.getCode())
                .title(form.getTitle())
                .description(form.getDescription())
                .active(form.isActive())
                .subjectUnitIds(subjectUnitIds)
                .fields(fields)
                .requiredLookups(requiredLookups)
                .build();
    }
}
