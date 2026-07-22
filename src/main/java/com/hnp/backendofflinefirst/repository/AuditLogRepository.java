package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            SELECT a FROM AuditLog a
            WHERE LOWER(COALESCE(a.entityType, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(a.entityId, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(a.actorUsername, '')) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<AuditLog> search(@Param("q") String q, Pageable pageable);
    List<AuditLog> findTop200ByOrderByRecordedAtDesc();
    List<AuditLog> findByEntityTypeAndEntityIdOrderByRecordedAtDesc(String entityType, String entityId);

    boolean existsByActorUserId(Long actorUserId);

    long countByRecordedAtLessThan(long recordedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            DELETE FROM audit_log
            WHERE id IN (
                SELECT id FROM audit_log
                WHERE recorded_at < :cutoff
                ORDER BY id
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteBatchBefore(@Param("cutoff") long cutoff, @Param("limit") int limit);
}
