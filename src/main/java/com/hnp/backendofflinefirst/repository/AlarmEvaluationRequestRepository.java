package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AlarmEvaluationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AlarmEvaluationRequestRepository extends JpaRepository<AlarmEvaluationRequest, Long> {

    /**
     * Locks the due requests, skipping any another processor already holds.
     *
     * <p>{@code SKIP LOCKED} is what makes two processors — two JVMs behind one database, or the
     * scheduled pass overlapping an after-commit one — take different rows instead of the same
     * row twice (roadmap §11 point 4, §19d). Must run inside a transaction, which then leases
     * the rows with {@link #lease}.
     */
    @Query(value = """
            SELECT id FROM alarm_evaluation_requests
            WHERE next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDue(@Param("now") long now, @Param("limit") int limit);

    /**
     * Pushes the claimed rows' next attempt past the time a processor needs, so a processor that
     * dies mid-way leaves them to be picked up again rather than lost or doubled.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AlarmEvaluationRequest r SET r.nextAttemptAt = :leaseUntil WHERE r.id IN :ids")
    int lease(@Param("ids") Collection<Long> ids, @Param("leaseUntil") long leaseUntil);
}
