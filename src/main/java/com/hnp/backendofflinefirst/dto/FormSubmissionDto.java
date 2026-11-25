package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.entity.FormSubmission;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class FormSubmissionDto {
    private Long id;
    private Long formDefinitionId;
    private String status;
    private Long reporterUnitId;
    private Long subjectUnitId;
    private Map<String, Object> formData;
    private List<FieldDefinitionSnapshot> fieldDefinitionsSnapshot;
    private Long reportedByUserId;
    private String reportedByName;
    private String lastSavedByName;
    private String source;
    private String localId;
    private Long createdAt;
    private Long updatedAt;
    private Long submittedAt;
    private Long approvedAt;
    private Long approvedByUserId;
    private Long syncedAt;
    private Long deviceTimeAt;

    public static FormSubmissionDto from(FormSubmission s) {
        return FormSubmissionDto.builder()
                .id(s.getId())
                .formDefinitionId(s.getFormDefinitionId())
                .status(s.getStatus() == null ? null : s.getStatus().name())
                .reporterUnitId(s.getReporterUnitId())
                .subjectUnitId(s.getSubjectUnitId())
                .formData(s.getFormData())
                .fieldDefinitionsSnapshot(s.getFieldDefinitionsSnapshot())
                .reportedByUserId(s.getReportedByUserId())
                .reportedByName(s.getReportedByName())
                .lastSavedByName(s.getLastSavedByName())
                .source(s.getSource() == null ? null : s.getSource().name())
                .localId(s.getLocalId())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .submittedAt(s.getSubmittedAt())
                .approvedAt(s.getApprovedAt())
                .approvedByUserId(s.getApprovedByUserId())
                .syncedAt(s.getSyncedAt())
                .deviceTimeAt(s.getDeviceTimeAt())
                .build();
    }
}
