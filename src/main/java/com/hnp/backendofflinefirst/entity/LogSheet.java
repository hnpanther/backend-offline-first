package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "log_sheets")
@Data
public class LogSheet {
    @Id
    private String id; // server-generated UUID, returned to client as serverId

    @Column(unique = true)
    private String localId;

    private String templateId;
    private String templateName;
    private String scopeSummary;
    private String operatorName;
    private String status;
    private String syncStatus;
    private Long submittedAt;
    private Long syncedAt;
    private String syncError;
    private String operationalUnitId;
    private Long createdAt;
    private Long updatedAt;
}
