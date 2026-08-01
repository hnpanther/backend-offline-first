package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AuditAction;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/**
 * Append-only audit trail for master/operational entity changes.
 * {@code changes} holds field-level diffs as JSON: [{field, oldValue, newValue}].
 */
@Entity
@Table(name = "audit_log")
@Data
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "entity_type")
    private String entityType;
    @Column(name = "entity_id")
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private AuditAction action;

    @Column(name = "actor_user_id")
    private Long actorUserId;
    @Column(name = "actor_username")
    private String actorUsername;
    @Column(name = "source")
    private String source;
    @Column(name = "request_id")
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", columnDefinition = "jsonb")
    private List<Map<String, String>> changes;

    @Column(name = "recorded_at")
    private Long recordedAt;
}
