package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.ApiSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiSessionRepository extends JpaRepository<ApiSession, Long> {

    Optional<ApiSession> findByJti(String jti);

    /** Sessions that may still authenticate a request for this user. */
    @Query("""
            SELECT s FROM ApiSession s
            WHERE s.userId = :userId
              AND s.revokedAt IS NULL
              AND s.expiresAt > :now
            """)
    List<ApiSession> findActiveByUserId(@Param("userId") Long userId, @Param("now") long now);

    /**
     * Serialises session changes for one user, for the rest of the calling transaction.
     *
     * <h2>Why a lock and not just the unique index</h2>
     *
     * <p>Registering a session is read-then-write: close whatever the user still holds, then
     * insert the new row. Under READ COMMITTED two concurrent logins both read "nothing active"
     * — neither sees the other's uncommitted insert — and both insert, leaving one user with two
     * live tokens and the documented one-device rule quietly false.
     *
     * <p>{@code ux_api_sessions_one_active} makes that impossible, but on its own it turns the
     * race into a <b>failed login</b>: the loser's insert violates the index. The lock is what
     * makes the loser wait, see the winner's row, supersede it, and succeed — so the last login
     * wins, which is the behaviour the rule promises.
     *
     * <p>Transaction-scoped, so it is released on commit or rollback with nothing to unwind.
     * The first argument namespaces the lock to this concern; the second is the user id, so two
     * different users never wait on each other.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:namespace, :userId)) AS locked",
           nativeQuery = true)
    Integer lockUserForSessionChange(@Param("namespace") int namespace, @Param("userId") int userId);

    /**
     * Closes <b>every</b> unrevoked session of one user, expired ones included.
     *
     * <h2>Two reasons this is a bulk statement and not a load-then-save</h2>
     *
     * <p><b>Ordering.</b> {@code ApiSession#id} is {@code IDENTITY}, so {@code save()} of the new
     * row issues its INSERT immediately, while dirty entities are only flushed later — Hibernate
     * runs inserts before updates. Revoking by loading entities therefore left the old row still
     * unrevoked at the moment the new one was inserted, which the unique index would reject on
     * every ordinary re-login. A {@code @Modifying} statement runs at once, in the right order.
     *
     * <p><b>Expiry.</b> The index is {@code WHERE revoked_at IS NULL}, and a partial index cannot
     * consult the clock, so an <em>expired but unrevoked</em> row is still in it. Superseding only
     * the unexpired ones — which is what {@link #findActiveByUserId} returns — would leave such a
     * row behind to block the next login. Marking it superseded changes nothing observable: a
     * session past its expiry already authenticates nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ApiSession s
               SET s.revokedAt = :now, s.revokeReason = :reason
             WHERE s.userId = :userId
               AND s.revokedAt IS NULL
            """)
    int supersedeAllForUser(@Param("userId") Long userId,
                            @Param("now") long now,
                            @Param("reason") ApiSessionRevokeReason reason);

    @Query("""
            SELECT s FROM ApiSession s
            WHERE LOWER(COALESCE(s.username, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(s.deviceLabel, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(s.ipAddress, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<ApiSession> search(@Param("q") String q, Pageable pageable);

    /**
     * Active-only listing (the default admin view).
     * <p>
     * Kept separate from {@link #searchActive} rather than folded into one query with a
     * nullable term: PostgreSQL infers {@code bytea} for an untyped null bind and then
     * {@code lower(?)} fails to resolve.
     */
    @Query("""
            SELECT s FROM ApiSession s
            WHERE s.revokedAt IS NULL
              AND s.expiresAt > :now
            """)
    Page<ApiSession> findActive(@Param("now") long now, Pageable pageable);

    @Query("""
            SELECT s FROM ApiSession s
            WHERE s.revokedAt IS NULL
              AND s.expiresAt > :now
              AND (LOWER(COALESCE(s.username, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(s.deviceLabel, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(COALESCE(s.ipAddress, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<ApiSession> searchActive(@Param("q") String q, @Param("now") long now, Pageable pageable);

    long countByRevokedAtIsNullAndExpiresAtGreaterThan(long now);
}
