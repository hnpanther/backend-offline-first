package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogSheetTemplateRepository extends JpaRepository<LogSheetTemplate, Long> {

    @Query("SELECT t FROM LogSheetTemplate t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<LogSheetTemplate> search(@Param("q") String q, Pageable pageable);

    /**
     * Moves a template's schedule cursor forward <b>only if nobody else has moved it</b>.
     *
     * <p>This is what stops two instances of the application generating every round twice. The
     * generation tick reads due templates with a plain {@code SELECT}, so two instances read the
     * same {@code next_run_at}, and before this statement existed both of them generated the
     * occurrence and both advanced the cursor with a {@code save()} that asked no questions —
     * leaving two identical rounds in the operators' pool, with nothing in any log to say why.
     * Generation and this update share one transaction, so the loser's rounds roll back with it
     * and the winner's stand.
     *
     * <p>{@code expected} is the cursor as the row had it when the template was picked up, and it
     * may be {@code null} — a template whose schedule has never run. The null-safe comparison is
     * the same idiom {@code LogSheetRepository.takeover} uses for an assignee.
     *
     * <p>It replaced {@code templateRepository.save(template)}, which also fixed something nobody
     * had noticed: the tick receives a <b>detached</b> template (its {@code SELECT} ran outside a
     * transaction), so that {@code save} was a {@code merge} and wrote <em>every</em> column back
     * from a snapshot that could be seconds old — quietly reverting any edit an administrator made
     * to the same template while the tick was running. This writes two columns and nothing else.
     *
     * @return {@code 1} when this caller advanced the cursor, {@code 0} when somebody else had
     *         already moved it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LogSheetTemplate t
            SET t.nextRunAt = :boundary,
                t.lastRunAt = :now
            WHERE t.id = :id
              AND ((:expected IS NULL AND t.nextRunAt IS NULL) OR t.nextRunAt = :expected)
            """)
    int advanceCursorIfUnchanged(@Param("id") Long id,
                                 @Param("expected") Long expected,
                                 @Param("boundary") long boundary,
                                 @Param("now") long now);

    @Query("""
            SELECT t FROM LogSheetTemplate t
            WHERE t.operationalUnitId IN :unitIds
              AND LOWER(t.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<LogSheetTemplate> searchInUnits(@Param("q") String q,
                                         @Param("unitIds") Collection<Long> unitIds,
                                         Pageable pageable);

    Page<LogSheetTemplate> findByOperationalUnitIdIn(Collection<Long> unitIds, Pageable pageable);

    List<LogSheetTemplate> findAllByOrderByIdDesc();

    List<LogSheetTemplate> findByOperationalUnitIdInOrderByIdDesc(Collection<Long> unitIds);

    boolean existsByOperationalUnitId(Long operationalUnitId);
    Optional<LogSheetTemplate> findByNameIgnoreCase(String name);

    /** Active scheduled templates whose next run is due at or before {@code now}. */
    List<LogSheetTemplate> findByGenerationModeAndScheduleActiveTrueAndNextRunAtLessThanEqual(
            GenerationMode generationMode, Long now);
}
