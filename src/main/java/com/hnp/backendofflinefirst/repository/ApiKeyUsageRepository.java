package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.ApiKeyUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyUsageRepository extends JpaRepository<ApiKeyUsage, Long> {

    Page<ApiKeyUsage> findAllByOrderByRequestedAtDesc(Pageable pageable);

    Page<ApiKeyUsage> findByApiKeyIdOrderByRequestedAtDesc(Long apiKeyId, Pageable pageable);

    long countByRequestedAtLessThan(long cutoff);

    /**
     * Deletes one batch of expired rows.
     *
     * <p>Batched rather than a single {@code DELETE ... WHERE requested_at < ?} because a year
     * of a minute-polling integration is half a million rows, and one statement over that
     * holds a long transaction and a lot of WAL. The caller loops until a pass deletes
     * nothing.
     */
    @Modifying
    @Query(value = """
            DELETE FROM api_key_usage
             WHERE id IN (SELECT id FROM api_key_usage
                           WHERE requested_at < :cutoff
                           ORDER BY id
                           LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") long cutoff, @Param("batchSize") int batchSize);
}
