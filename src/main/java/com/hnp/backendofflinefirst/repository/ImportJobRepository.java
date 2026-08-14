package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.ImportJobStatus;
import com.hnp.backendofflinefirst.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Optional<ImportJob> findByJobUuid(String jobUuid);

    List<ImportJob> findTop50BySubmittedByUserIdOrderByCreatedAtDesc(Long userId);

    List<ImportJob> findBySubmittedByUserIdAndStatusInOrderByCreatedAtDesc(
            Long userId, Collection<ImportJobStatus> statuses);

    List<ImportJob> findByStatus(ImportJobStatus status);

    boolean existsByStatusIn(Collection<ImportJobStatus> statuses);

    boolean existsBySubmittedByUserId(Long submittedByUserId);

    /**
     * Writes a terminal status without going through the entity, and only if the job is
     * still active.
     * <p>
     * This is the last-resort path used when the ordinary {@code save} of the failure status
     * has itself thrown — the exact scenario that used to strand a job at RUNNING forever.
     * A native statement touches no persistence context, triggers no
     * {@code RepositoryAuditAspect} advice and enqueues nothing on any executor, so there is
     * nothing left that can fail while recording that something failed.
     * <p>
     * The {@code status IN} guard makes it a no-op against an already-terminal job, so a
     * late-arriving worker cannot overwrite a decision the watchdog or an admin already made.
     */
    @Modifying
    @Query(value = """
            UPDATE import_jobs
               SET status = :status, error_message = :message, completed_at = :completedAt
             WHERE id = :id AND status IN ('PENDING', 'RUNNING')
            """, nativeQuery = true)
    int forceTerminalStatus(@Param("id") Long id,
                            @Param("status") String status,
                            @Param("message") String message,
                            @Param("completedAt") long completedAt);

    /**
     * RUNNING jobs that have not reported progress since {@code cutoff}.
     * <p>
     * {@code heartbeat_at} is null for jobs written before that column existed and for a job that died before
     * its first tick, so {@code started_at} is the fallback — otherwise the very failures
     * this query exists to catch would be invisible to it.
     */
    @Query("""
            SELECT j FROM ImportJob j
             WHERE j.status = com.hnp.backendofflinefirst.domain.ImportJobStatus.RUNNING
               AND COALESCE(j.heartbeatAt, j.startedAt, j.createdAt) < :cutoff
            """)
    List<ImportJob> findStaleRunning(@Param("cutoff") long cutoff);
}
