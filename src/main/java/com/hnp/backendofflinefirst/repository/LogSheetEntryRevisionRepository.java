package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetEntryRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface LogSheetEntryRevisionRepository extends JpaRepository<LogSheetEntryRevision, Long> {

    /**
     * One entry's superseded values, oldest first.
     *
     * <p>Ordered by {@code id}, not by {@code superseded_at}. The identity column is monotonic
     * within the table, whereas two writes in the same millisecond — a web save that replaces
     * several entries at once — would tie on the timestamp and render in an arbitrary order.
     */
    List<LogSheetEntryRevision> findByLogSheetEntryIdOrderByIdAsc(Long logSheetEntryId);

    /**
     * Every superseded value on one sheet, in one query.
     *
     * <p>The detail page renders a history section per asset; asking per entry would be one
     * query per row on a page that can carry 300 of them. Same batch-map pattern as the rest of
     * the panel — see {@code docs/performance.md} §3.
     */
    List<LogSheetEntryRevision> findByLogSheetIdOrderByIdAsc(Long logSheetId);

    long countByLogSheetId(Long logSheetId);

    List<LogSheetEntryRevision> findByLogSheetEntryIdInOrderByIdAsc(Collection<Long> logSheetEntryIds);
}
