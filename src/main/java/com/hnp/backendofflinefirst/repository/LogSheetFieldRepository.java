package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LogSheetFieldRepository extends JpaRepository<LogSheetField, Long> {

    /**
     * Every question a template owns, switched-off ones included — the editor shows both.
     *
     * <p>Ordered the way {@code FieldDefinitionSnapshot.displayOrder()} orders the per-asset
     * ones: by {@code sort_order} with <b>nulls last</b>, then by id. A derived query name would
     * leave the null placement to the dialect, and the two lists would drift apart on exactly
     * the rows nobody numbered.
     */
    @Query("""
            SELECT f FROM LogSheetField f
            WHERE f.templateId = :templateId
            ORDER BY COALESCE(f.order, 2147483647), f.id
            """)
    List<LogSheetField> findForTemplate(@Param("templateId") Long templateId);

    /** Every question one sheet owns, switched-off ones included, in the same order. */
    @Query("""
            SELECT f FROM LogSheetField f
            WHERE f.logSheetId = :logSheetId
            ORDER BY COALESCE(f.order, 2147483647), f.id
            """)
    List<LogSheetField> findForLogSheet(@Param("logSheetId") Long logSheetId);

    /**
     * One question, but only if it belongs to the template in the path.
     *
     * <p>Without this the path id decides access while the field id decides what is written, so
     * somebody holding a template they may edit could name a field from one they may not. The
     * class-field editor learned this the same way; see
     * {@code AssetClassFieldOwnershipIntegrationTest}.
     */
    Optional<LogSheetField> findByIdAndTemplateId(Long id, Long templateId);

    Optional<LogSheetField> findByTemplateIdAndKeyIgnoreCase(Long templateId, String key);

    Optional<LogSheetField> findByLogSheetIdAndKeyIgnoreCase(Long logSheetId, String key);

    /**
     * Whether any stored answer on a round raised from this template still names the key — the
     * guard on a hard delete.
     *
     * <p>Two places hold answers to round-level questions: the sheet itself, and the history of
     * what corrections replaced. Both are checked, and both are scoped to <b>this template's</b>
     * rounds: two templates may legitimately each have a question called {@code note}, and one
     * template's readings must not freeze the other's editor.
     *
     * <p>A sheet's frozen {@code sheet_field_definitions_snapshot} deliberately does <b>not</b>
     * count. That round renders from its own copy and never reads this row, so deleting it
     * cannot affect the sheet — and counting it would block the one case a hard delete exists
     * for: a question created, never answered, and removed again.
     *
     * <p>{@code jsonb_exists(...)} rather than the {@code ?} operator: Hibernate reads {@code ?}
     * in a native query as an ordinal parameter placeholder, so the statement never reaches
     * PostgreSQL in the shape it was written (AGENTS.md #160).
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM log_sheets s
                 WHERE s.template_id = :templateId
                   AND jsonb_exists(s.sheet_form_data, :fieldKey)
                 UNION ALL
                SELECT 1
                  FROM log_sheet_revisions r
                  JOIN log_sheets s2 ON s2.id = r.log_sheet_id
                 WHERE s2.template_id = :templateId
                   AND jsonb_exists(r.form_data, :fieldKey)
            )
            """, nativeQuery = true)
    boolean isTemplateFieldKeyAnswered(@Param("templateId") Long templateId,
                                       @Param("fieldKey") String fieldKey);
}
