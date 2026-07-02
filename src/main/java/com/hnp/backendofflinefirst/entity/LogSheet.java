package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A unit of inspection work generated (manually or on schedule) from a template.
 * Progresses through a server-authoritative lifecycle ({@link LogSheetStatus}).
 * Milestone timestamps are stored per action; {@code completedAt} is device-
 * authoritative (recorded offline on mobile, synced later), while {@code syncedAt}
 * is the server receive time.
 */
@Entity
@Table(name = "log_sheets")
@Data
public class LogSheet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // server-generated incremental id, returned to client as serverId

    @Column(unique = true)
    private String localId;

    private Long templateId;
    private String templateName;
    private String scopeSummary;
    private Long operationalUnitId;

    @Enumerated(EnumType.STRING)
    private LogSheetStatus status;

    @Enumerated(EnumType.STRING)
    private GenerationMode origin;

    private Long assigneeUserId;

    @Enumerated(EnumType.STRING)
    private AssignmentType assignmentType;

    private Long assignedByUserId;
    private Long completedByUserId;
    private String operatorName;

    // lifecycle timestamps (epoch millis)
    private Long dueAt;
    private Long assignedAt;
    private Long claimedAt;
    private Long startedAt;
    private Long completedAt; // device-authoritative
    private Long expiredAt;
    private Long submittedAt;
    private Long syncedAt;    // server receive time

    private String syncStatus;
    private String syncError;
    private Long createdAt;
    private Long updatedAt;
}
