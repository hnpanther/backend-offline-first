package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetActionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LogSheetActionLogRepository extends JpaRepository<LogSheetActionLog, Long> {
    List<LogSheetActionLog> findByLogSheetIdOrderByActionAtAsc(Long logSheetId);
    boolean existsByClientActionId(String clientActionId);
    boolean existsByActorUserId(Long actorUserId);
    boolean existsByFromUserId(Long fromUserId);
    boolean existsByToUserId(Long toUserId);

    /**
     * Actions that carry a written explanation, newest first.
     *
     * <p>Filters on {@code comment IS NOT NULL} rather than on the action type: only
     * EXTEND / CANCEL / VOID / UNVOID / ADMIN_REOPEN can record one today, but the comment is
     * optional even for those, and keying off its presence keeps this query correct if a
     * further action is given a reason later. Blank is normalised to null on write, so there
     * is no empty-string case to exclude here.
     */
    @Query("""
            SELECT l.logSheetId, s.templateName, l.action, l.actorUserId, l.actionAt, l.comment
            FROM LogSheetActionLog l, LogSheet s
            WHERE l.logSheetId = s.id
              AND l.comment IS NOT NULL
              AND (:unitIds IS NULL OR s.operationalUnitId IN :unitIds)
              AND (:from IS NULL OR l.actionAt >= :from)
              AND (:to IS NULL OR l.actionAt <= :to)
            ORDER BY l.actionAt DESC
            """)
    List<Object[]> findExplainedActions(@Param("unitIds") Collection<Long> unitIds,
                                        @Param("from") Long from,
                                        @Param("to") Long to,
                                        Pageable pageable);
}
