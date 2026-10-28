package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FieldDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, Long> {

    Page<FieldDefinition> findByClassId(Long classId, Pageable pageable);

    @Query("""
            SELECT f FROM FieldDefinition f
            WHERE f.classId = :classId
              AND (LOWER(f.key) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(f.label) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<FieldDefinition> searchByClassId(@Param("classId") Long classId,
                                          @Param("q") String q,
                                          Pageable pageable);
    List<FieldDefinition> findByClassId(Long classId);
    List<FieldDefinition> findByClassIdOrderByIdDesc(Long classId);

    List<FieldDefinition> findByClassIdIn(Collection<Long> classIds);

    Optional<FieldDefinition> findByClassIdAndKeyIgnoreCase(Long classId, String key);

    Optional<FieldDefinition> findByIdAndClassId(Long id, Long classId);

    /**
     * Whether any stored data still refers to this field key, for the classes that could carry
     * it — the delete-guard behind «غیرفعال به‌جای حذف».
     *
     * <p><strong>What counts as a reference.</strong> Only the places where removing the
     * definition would strand something:
     * <ul>
     *   <li>{@code log_sheet_entries.form_data} — a reading taken under this key;</li>
     *   <li>{@code log_sheet_entry_revisions.form_data} — a reading that was later corrected,
     *       which is still shown on the sheet and is still evidence;</li>
     *   <li>{@code asset_entries.field_overrides} — a band an administrator set on one machine
     *       for this parameter, which would vanish with no trace.</li>
     * </ul>
     *
     * <p><strong>What deliberately does not count:</strong> a sheet's
     * {@code field_definitions_snapshot}. A raised sheet renders from its own frozen copy and
     * does not consult {@code field_definitions} at all, so deleting the row cannot affect it.
     * Counting it would block the delete of a parameter that was offered once and answered
     * never, which is exactly the case the hard delete is kept for.
     *
     * <p><strong>Scoped by class, and the caller must pass the sub-classes too.</strong> A key
     * is unique per class, not globally: {@code Ampere} on Pumps and {@code Ampere} on
     * Compressors are different parameters, and one must not block the other's delete. A
     * parent's field is inherited, so its readings are stored on entries carrying the
     * <em>sub-class's</em> id — leaving those out would answer "unreferenced" for a parameter
     * with a thousand readings.
     *
     * <p>Three jsonb containment scans with no index behind them, run when an administrator
     * clicks delete. {@code EXISTS} stops at the first hit and the class filter is indexed, so
     * the common case is cheap; the expensive case is a key nothing uses, which is also the
     * case the administrator is about to be allowed to proceed with.
     *
     * <p><strong>{@code jsonb_exists(col, :key)}, never {@code col ? :key}.</strong> They are
     * the same operator, but in a <em>native</em> query Hibernate reads {@code ?} as an ordinal
     * parameter placeholder and the statement fails to bind. The function form has no such
     * ambiguity. (The migrations use {@code ?} freely — they are raw SQL and never pass through
     * a parameter parser.)
     *
     * <p>{@code classIds} must not be empty: PostgreSQL rejects {@code IN ()}.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM log_sheet_entries e
                 WHERE e.class_id IN (:classIds) AND jsonb_exists(e.form_data, :fieldKey)
                 UNION ALL
                SELECT 1 FROM log_sheet_entry_revisions r
                 INNER JOIN asset_entries ra ON ra.id = r.asset_id
                 WHERE ra.class_id IN (:classIds) AND jsonb_exists(r.form_data, :fieldKey)
                 UNION ALL
                SELECT 1 FROM asset_entries a
                 WHERE a.class_id IN (:classIds) AND jsonb_exists(a.field_overrides, :fieldKey)
            )
            """, nativeQuery = true)
    boolean isFieldKeyReferenced(@Param("classIds") Collection<Long> classIds,
                                 @Param("fieldKey") String fieldKey);
}
