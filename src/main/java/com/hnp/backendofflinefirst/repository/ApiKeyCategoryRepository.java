package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.ApiKeyCategory;
import com.hnp.backendofflinefirst.entity.ApiKeyCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApiKeyCategoryRepository extends JpaRepository<ApiKeyCategory, ApiKeyCategoryId> {

    List<ApiKeyCategory> findByApiKeyId(Long apiKeyId);

    /** Every key's categories for one page of the key list — one statement, not one per key. */
    List<ApiKeyCategory> findByApiKeyIdIn(Collection<Long> apiKeyIds);

    /** The category delete guard: a key still limited to it. */
    boolean existsByCategoryId(Long categoryId);
}
