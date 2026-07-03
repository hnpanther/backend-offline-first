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
    private Long id;

    private String entityType;
    private String entityId;

    @Enumerated(EnumType.STRING)
    private AuditAction action;

    private Long actorUserId;
    private String actorUsername;
    private String source;
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, String>> changes;

    private Long recordedAt;
}
