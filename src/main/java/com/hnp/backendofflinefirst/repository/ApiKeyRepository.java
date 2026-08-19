package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.ApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** The per-request lookup: one read on the unique {@code key_id} index. */
    Optional<ApiKey> findByKeyId(String keyId);

    /**
     * Guards the "one live key per client" rule before the partial unique index does.
     *
     * <p>The index is the real enforcement; this exists so an administrator gets a readable
     * Persian message instead of a constraint violation.
     */
    @Query("""
            SELECT COUNT(k) > 0 FROM ApiKey k
            WHERE LOWER(k.clientName) = LOWER(:clientName)
              AND k.revokedAt IS NULL
            """)
    boolean existsLiveByClientName(@Param("clientName") String clientName);

    /**
     * Touches {@code last_used_at} without loading or saving the entity.
     *
     * <p>A {@code @Modifying} query rather than {@code save()} on purpose: {@code save()} goes
     * through {@code RepositoryAuditAspect}, and an integration polling every minute would
     * then write an {@code audit_log} row per request — for a field nobody ever wants a change
     * history of. Combined with {@code ApiKeyService.LAST_USED_THROTTLE_MS}, a busy key costs
     * at most one small UPDATE a minute.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    int touchLastUsed(@Param("id") Long id, @Param("now") long now);

    @Query("""
            SELECT k FROM ApiKey k
            WHERE LOWER(k.clientName) LIKE LOWER(CONCAT('%', COALESCE(:q, ''), '%'))
               OR LOWER(k.keyId)      LIKE LOWER(CONCAT('%', COALESCE(:q, ''), '%'))
            """)
    Page<ApiKey> search(@Param("q") String q, Pageable pageable);
}
