package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Attachment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    List<Attachment> findByLogSheetIdOrderByUploadedAtAsc(Long logSheetId);

    List<Attachment> findByLogSheetIdInOrderByUploadedAtAsc(Collection<Long> logSheetIds);

    List<Attachment> findByLogSheetIdAndAssetIdAndFieldKey(Long logSheetId, Long assetId, String fieldKey);

    /**
     * Which of these storage keys a row still references.
     *
     * <p>The orphan sweep's one hot query: it asks in batches rather than per file, because a
     * year of uploads is a six-figure file count and one round trip each would be untenable.
     */
    @Query("SELECT a.storageKey FROM Attachment a WHERE a.storageKey IN :keys")
    List<String> findStorageKeysIn(@Param("keys") Collection<String> keys);

    /**
     * Every row's storage key, one page at a time.
     *
     * <p>The sweep has to ask the filesystem about each row, and the caller that needed this used
     * {@code findAll()} — every attachment row hydrated into heap before the first question was
     * asked. That is the shape that took the login page down with {@code OutOfMemoryError}
     * (gotcha 9b-2); it only looks harmless here because the table is small today. A key is a
     * short string and a page of them is bounded, so the sweep's memory no longer tracks the
     * plant's photo count.
     */
    @Query("SELECT a.storageKey FROM Attachment a ORDER BY a.id")
    Page<String> findAllStorageKeys(Pageable pageable);
}
