package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LookupListImport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LookupListImportRepository extends JpaRepository<LookupListImport, Long> {

    /** The last few imports of a list, newest first — shown under the import form. */
    List<LookupListImport> findTop10ByListIdOrderByCreatedAtDescIdDesc(Long listId);
}
