package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, String> {

    List<Attachment> findByLogSheetIdOrderByUploadedAtAsc(Long logSheetId);

    List<Attachment> findByLogSheetIdInOrderByUploadedAtAsc(Collection<Long> logSheetIds);

    List<Attachment> findByLogSheetIdAndAssetIdAndFieldKey(Long logSheetId, Long assetId, String fieldKey);
}
