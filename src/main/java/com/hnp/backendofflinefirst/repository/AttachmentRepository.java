package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Attachment;
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
}
