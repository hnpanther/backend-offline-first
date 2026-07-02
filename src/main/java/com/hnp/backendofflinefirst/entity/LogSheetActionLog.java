package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import jakarta.persistence.*;
import lombok.Data;

/**
 * Immutable audit record of a single lifecycle action on a log sheet.
 * {@code actionAt} is when the action truly happened (device clock when offline);
 * {@code recordedAt} is when the server persisted it. {@code clientActionId} makes
 * offline action replay idempotent on sync.
 */
@Entity
@Table(name = "log_sheet_action_log")
@Data
public class LogSheetActionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long logSheetId;

    @Enumerated(EnumType.STRING)
    private LogSheetActionType action;

    private Long actorUserId;
    private Long fromUserId;
    private Long toUserId;

    @Enumerated(EnumType.STRING)
    private ActionSource source;

    private Long actionAt;
    private Long recordedAt;

    @Column(unique = true)
    private String clientActionId;
}
