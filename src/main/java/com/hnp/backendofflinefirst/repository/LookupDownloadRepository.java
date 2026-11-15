package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LookupDownload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LookupDownloadRepository extends JpaRepository<LookupDownload, Long> {

    /** When a device last fetched any page of this list, if ever. */
    @Query("SELECT MAX(d.fetchedAt) FROM LookupDownload d WHERE d.listId = :listId")
    Optional<Long> lastFetchedAt(@Param("listId") Long listId);

    boolean existsByListId(Long listId);

    long countByListId(Long listId);
}
