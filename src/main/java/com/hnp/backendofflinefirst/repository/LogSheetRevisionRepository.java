package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LogSheetRevisionRepository extends JpaRepository<LogSheetRevision, Long> {

    /**
     * One sheet's superseded round-level answers, oldest first.
     *
     * <p>By id rather than by {@code superseded_at}: the identity column is monotonic within the
     * table, so it orders one sheet's corrections correctly without depending on a timestamp two
     * paths could write out of order. {@code idx_lsr_sheet} serves it either way.
     */
    List<LogSheetRevision> findByLogSheetIdOrderByIdAsc(Long logSheetId);
}
