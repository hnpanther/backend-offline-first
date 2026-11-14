package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LookupItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LookupItemRepository extends JpaRepository<LookupItem, Long> {

    /** Every item of a list, active first, in the administrator's order. */
    @Query(value = """
            SELECT i FROM LookupItem i WHERE i.listId = :listId
            ORDER BY i.active DESC, i.sortOrder ASC, i.label ASC, i.id ASC
            """,
            countQuery = "SELECT COUNT(i) FROM LookupItem i WHERE i.listId = :listId")
    Page<LookupItem> pageByList(@Param("listId") Long listId, Pageable pageable);

    /**
     * The same, filtered by an already-normalised term against {@code search_text}.
     * {@code q} is never null here (AGENTS.md #13).
     */
    @Query(value = """
            SELECT i FROM LookupItem i
            WHERE i.listId = :listId AND i.searchText LIKE CONCAT('%', :q, '%')
            ORDER BY i.active DESC, i.sortOrder ASC, i.label ASC, i.id ASC
            """,
            countQuery = """
            SELECT COUNT(i) FROM LookupItem i
            WHERE i.listId = :listId AND i.searchText LIKE CONCAT('%', :q, '%')
            """)
    Page<LookupItem> searchByList(@Param("listId") Long listId, @Param("q") String q, Pageable pageable);

    @Query("SELECT i FROM LookupItem i WHERE i.listId = :listId AND LOWER(i.code) = LOWER(:code)")
    Optional<LookupItem> findByListIdAndCodeIgnoreCase(@Param("listId") Long listId, @Param("code") String code);

    @Query("SELECT i FROM LookupItem i WHERE i.listId = :listId ORDER BY i.id")
    List<LookupItem> findAllByListId(@Param("listId") Long listId);

    @Query("SELECT i FROM LookupItem i WHERE i.id IN :ids")
    List<LookupItem> findAllByIdIn(@Param("ids") Collection<Long> ids);

    boolean existsByListId(Long listId);

    boolean existsByParentItemId(Long parentItemId);

    boolean existsByListIdAndParentItemIdIsNotNull(Long listId);

    long countByListId(Long listId);

    long countByListIdAndActiveTrue(Long listId);

    /** {listId, all, active} for a page of lists, in one grouped query rather than two per row. */
    @Query("""
            SELECT i.listId, COUNT(i), SUM(CASE WHEN i.active = true THEN 1 ELSE 0 END)
            FROM LookupItem i WHERE i.listId IN :listIds GROUP BY i.listId
            """)
    List<Object[]> countsByListIds(@Param("listIds") Collection<Long> listIds);

    /** How many items hold a value for one column — shown before the column is removed. */
    @Query(value = """
            SELECT COUNT(*) FROM lookup_items
            WHERE list_id = :listId AND COALESCE(attributes ->> CAST(:key AS text), '') <> ''
            """, nativeQuery = true)
    long countWithAttribute(@Param("listId") Long listId, @Param("key") String key);
}
