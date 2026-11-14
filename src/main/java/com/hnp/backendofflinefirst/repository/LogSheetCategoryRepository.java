package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LogSheetCategoryRepository extends JpaRepository<LogSheetCategory, Long> {

    /** Active first, then by name — the order every picker shows. */
    @Query("SELECT c FROM LogSheetCategory c ORDER BY c.active DESC, c.name ASC, c.id ASC")
    List<LogSheetCategory> findAllForPicker();

    @Query("SELECT c FROM LogSheetCategory c WHERE LOWER(c.code) = LOWER(:code)")
    Optional<LogSheetCategory> findByCodeIgnoreCase(@Param("code") String code);

    /** The integration API's {@code category} filter; the codes arrive upper-cased. */
    @Query("SELECT c FROM LogSheetCategory c WHERE UPPER(c.code) IN :codes")
    List<LogSheetCategory> findByCodeUpperIn(@Param("codes") Collection<String> codes);

    /**
     * The list page's search. {@code q} is never null here — the caller passes the term only
     * when there is one (AGENTS.md #13: a null inside LOWER(CONCAT(…)) is inferred as bytea).
     */
    @Query("""
            SELECT c FROM LogSheetCategory c
            WHERE LOWER(c.code) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<LogSheetCategory> search(@Param("q") String q, Pageable pageable);
}
