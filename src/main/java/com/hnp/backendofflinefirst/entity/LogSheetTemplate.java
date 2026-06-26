package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "log_sheet_templates")
@Data
public class LogSheetTemplate {
    @Id
    private String id;
    private String name;
    private String description;
    private String scopeType;
    private String scopeId;
    private Long createdAt;
    private Long updatedAt;
}
