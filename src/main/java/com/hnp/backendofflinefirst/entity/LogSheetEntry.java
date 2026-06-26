package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "log_sheet_entries")
@Data
public class LogSheetEntry {
    @Id
    private String id;

    private String logSheetId;
    private String assetId;
    private String assetName;
    private String subFunctionCode;
    private String subFunctionTag;
    private String classId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> formData;
}
