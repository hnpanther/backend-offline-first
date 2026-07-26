package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.ApiSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
