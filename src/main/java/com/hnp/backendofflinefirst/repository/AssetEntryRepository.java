package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AssetEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetEntryRepository extends JpaRepository<AssetEntry, Long> {
    Optional<AssetEntry> findByNfcTagId(String nfcTagId);
    boolean existsByAssetCode(String assetCode);
    boolean existsByAssetCodeAndIdNot(String assetCode, Long id);
    boolean existsByNfcTagId(String nfcTagId);
    boolean existsByNfcTagIdAndIdNot(String nfcTagId, Long id);
    List<AssetEntry> findByUpdatedAtGreaterThanEqual(Long since);
    List<AssetEntry> findAllByOrderByIdDesc();
}
