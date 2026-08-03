package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.LogSheetTemplateAsset;
import com.hnp.backendofflinefirst.entity.LogSheetTemplateAssetId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LogSheetTemplateAssetRepository
        extends JpaRepository<LogSheetTemplateAsset, LogSheetTemplateAssetId> {

    List<LogSheetTemplateAsset> findByTemplateId(Long templateId);

    void deleteByTemplateId(Long templateId);

    /** Delete-guard: an asset frozen into a template must not be hard-deleted. */
    boolean existsByAssetId(Long assetId);

    @Query("SELECT ta.assetId FROM LogSheetTemplateAsset ta WHERE ta.templateId = :templateId")
    List<Long> findAssetIdsByTemplateId(@Param("templateId") Long templateId);
}
