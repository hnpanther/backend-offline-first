package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LookupList;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LookupListRepository extends JpaRepository<LookupList, Long> {

    @Query("SELECT l FROM LookupList l WHERE LOWER(l.code) = LOWER(:code)")
    Optional<LookupList> findByCodeIgnoreCase(@Param("code") String code);

    /** Active first, then by name — the order the parent-list picker shows. */
    @Query("SELECT l FROM LookupList l ORDER BY l.active DESC, l.name ASC, l.id ASC")
    List<LookupList> findAllForPicker();

    /** The list page's search; {@code q} is never null here (AGENTS.md #13). */
    @Query("""
            SELECT l FROM LookupList l
            WHERE LOWER(l.code) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<LookupList> search(@Param("q") String q, Pageable pageable);

    boolean existsByParentListId(Long parentListId);

    /**
     * The list row, locked for the rest of the transaction — an import holds it so that two
     * imports into one list run one after the other, each against what the other left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM LookupList l WHERE l.id = :id")
    Optional<LookupList> findForUpdate(@Param("id") Long id);

    /**
     * The only writer of {@code revision}. Atomic in SQL, so two concurrent edits each add one;
     * the entity maps the column read-only and can never write a stale value back over it.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE LookupList l SET l.revision = l.revision + 1, l.updatedAt = :now WHERE l.id = :id")
    int bumpRevision(@Param("id") Long id, @Param("now") long now);

    /** A fresh read — an entity loaded earlier in the transaction still holds the old number. */
    @Query("SELECT l.revision FROM LookupList l WHERE l.id = :id")
    long currentRevision(@Param("id") Long id);
}
