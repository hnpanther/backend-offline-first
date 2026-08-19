package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.ApiKeyUsageOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One request that reached the integration chain — including the ones it rejected.
 *
 * <p>Rejected requests are the point, not a side effect: a run of {@code INVALID_KEY} rows
 * from one address is the only evidence anybody will ever get that somebody is guessing keys,
 * and a {@code REVOKED_KEY} row is how you find the integration nobody told about the
 * rotation.
 *
 * <p>{@link #apiKeyId} is null when the presented key matched no row. {@link #keyId} and
 * {@link #clientName} are copied rather than joined so the row still reads correctly on its
 * own, and so listing a day of traffic needs no join.
 *
 * <p>Excluded from the entity audit trail ({@code AuditEntitySupport.EXCLUDED_TYPES}) — this
 * table <em>is</em> an audit trail, and auditing it would write two rows for every request.
 */
@Entity
@Table(name = "api_key_usage")
@Data
public class ApiKeyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Null when the presented key matched no row — deliberately, see the class javadoc. */
    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Column(name = "key_id", length = 64)
    private String keyId;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    /** The filters the caller asked for. Never carries the key itself — it is a header. */
    @Column(name = "query_string", length = 1000)
    private String queryString;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ApiKeyUsageOutcome outcome;

    /** Rows returned in the response body; null for a request that returned no collection. */
    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "requested_at", nullable = false)
    private long requestedAt;
}
