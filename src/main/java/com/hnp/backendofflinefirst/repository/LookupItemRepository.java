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

    /**
     * The items a set of answers names, by lower-cased code — one query per list per save, served
     * by {@code ux_lookup_items_list_code_lower}. {@code lowerCodes} is never empty here.
     */
    @Query("SELECT i FROM LookupItem i WHERE i.listId = :listId AND LOWER(i.code) IN :lowerCodes")
    List<LookupItem> findByListIdAndLowerCodeIn(@Param("listId") Long listId,
                                                @Param("lowerCodes") Collection<String> lowerCodes);

    /**
     * Active items whose {@code search_text} holds an already-normalised term — what a person
     * filling a form may pick. A retired item is never offered, though one already recorded keeps
     * its label. {@code q} is never null (AGENTS.md #13); empty matches every item.
     */
    @Query("""
            SELECT i FROM LookupItem i
            WHERE i.listId = :listId AND i.active = true AND i.searchText LIKE CONCAT('%', :q, '%')
            ORDER BY i.sortOrder ASC, i.label ASC, i.id ASC
            """)
    List<LookupItem> searchActive(@Param("listId") Long listId, @Param("q") String q, Pageable pageable);

    /**
     * One page of a list for a device, half-open over {@code (updated_at, id)}: strictly after the
     * cursor, so consecutive pages neither overlap nor leave a gap even when many rows share one
     * {@code updated_at} — an import writes thousands with the same instant. Deactivated items are
     * included: deactivation is an update, and the device must learn of it.
     */
    @Query("""
            SELECT i FROM LookupItem i
            WHERE i.listId = :listId
              AND (i.updatedAt > :since OR (i.updatedAt = :since AND i.id > :afterId))
            ORDER BY i.updatedAt ASC, i.id ASC
            """)
    List<LookupItem> pageForDevice(@Param("listId") Long listId, @Param("since") long since,
                                   @Param("afterId") long afterId, Pageable pageable);

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

    /**
     * The jsonpath for «any object, at any depth, whose {@code code} is {@code $c}». Passed as a
     * bind parameter rather than written into the SQL: its filter's question mark would otherwise
     * be read as a JDBC placeholder.
     */
    String ANY_CODE_PATH = "$.** ? (@.code == $c)";

    /**
     * Whether any stored answer names this code — the current answers, the round's own answers,
     * every superseded value kept as a revision, and what a voided tablet sync carried (roadmap §31,
     * «A real delete»). Six columns, scanned in full: this runs only when an administrator asks to
     * hard-delete an item, and a wrong «no» would let a recorded answer outlive the item it names.
     * Conservative by construction — a code of another list that happens to match also says yes,
     * and the item is then deactivated rather than deleted, which loses nothing.
     */
    @Query(value = """
            SELECT (
                 EXISTS (SELECT 1 FROM log_sheet_entries
                          WHERE form_data IS NOT NULL
                            AND jsonb_path_exists(form_data, CAST(:path AS jsonpath), jsonb_build_object('c', CAST(:code AS text))))
              OR EXISTS (SELECT 1 FROM log_sheets
                          WHERE sheet_form_data IS NOT NULL
                            AND jsonb_path_exists(sheet_form_data, CAST(:path AS jsonpath), jsonb_build_object('c', CAST(:code AS text))))
              OR EXISTS (SELECT 1 FROM log_sheet_entry_revisions
                          WHERE jsonb_path_exists(form_data, CAST(:path AS jsonpath), jsonb_build_object('c', CAST(:code AS text))))
              OR EXISTS (SELECT 1 FROM log_sheet_revisions
                          WHERE jsonb_path_exists(form_data, CAST(:path AS jsonpath), jsonb_build_object('c', CAST(:code AS text))))
              OR EXISTS (SELECT 1 FROM log_sheet_void_submissions
                          WHERE payload IS NOT NULL
                            AND jsonb_path_exists(payload, CAST(:path AS jsonpath), jsonb_build_object('c', CAST(:code AS text))))
              OR EXISTS (SELECT 1 FROM log_sheet_void_submissions
                          WHERE sheet_form_data IS NOT NULL
                            AND jsonb_path_exists(sheet_form_data, CAST(:path AS jsonpath), jsonb_build_object('c', CAST(:code AS text))))
            )
            """, nativeQuery = true)
    boolean anyAnswerNamesCode(@Param("path") String path, @Param("code") String code);

    /** Whether any field — a class's parameter or a round's question — draws from this list. */
    @Query(value = """
            SELECT (
                 EXISTS (SELECT 1 FROM field_definitions
                          WHERE lower(validation ->> 'lookupList') = lower(CAST(:code AS text)))
              OR EXISTS (SELECT 1 FROM log_sheet_fields
                          WHERE lower(validation ->> 'lookupList') = lower(CAST(:code AS text)))
            )
            """, nativeQuery = true)
    boolean anyFieldDrawsFromList(@Param("code") String code);

    /** How many items hold a value for one column — shown before the column is removed. */
    @Query(value = """
            SELECT COUNT(*) FROM lookup_items
            WHERE list_id = :listId AND COALESCE(attributes ->> CAST(:key AS text), '') <> ''
            """, nativeQuery = true)
    long countWithAttribute(@Param("listId") Long listId, @Param("key") String key);
}
