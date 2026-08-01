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
    @Column(name = "id")
    private Long id;

    @Column(name = "log_sheet_id")
    private Long logSheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private LogSheetActionType action;

    @Column(name = "actor_user_id")
    private Long actorUserId;
    @Column(name = "from_user_id")
    private Long fromUserId;
    @Column(name = "to_user_id")
    private Long toUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source")
    private ActionSource source;

    @Column(name = "action_at")
    private Long actionAt;
    @Column(name = "recorded_at")
    private Long recordedAt;

    @Column(name = "client_action_id", unique = true)
    private String clientActionId;
}
