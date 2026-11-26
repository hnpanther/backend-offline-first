package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FormFieldRepository extends JpaRepository<FormField, Long> {

    List<FormField> findByFormDefinitionIdInAndDeletedFalseOrderByOrderAscIdAsc(List<Long> ids);

    List<FormField> findByFormDefinitionIdOrderByOrderAscIdAsc(Long formDefinitionId);

    List<FormField> findByFormDefinitionIdAndDeletedFalseOrderByOrderAscIdAsc(Long formDefinitionId);

    Optional<FormField> findByIdAndFormDefinitionId(Long id, Long formDefinitionId);

    boolean existsByFormDefinitionIdAndKeyIgnoreCase(Long formDefinitionId, String key);

    @Query("""
            SELECT CASE WHEN COUNT(f) > 0 THEN TRUE ELSE FALSE END
            FROM FormField f
            WHERE f.formDefinitionId = :formId
              AND LOWER(f.key) = LOWER(:key)
              AND f.id <> :excludeId
            """)
    boolean existsOtherWithKey(@Param("formId") Long formId,
                               @Param("key") String key,
                               @Param("excludeId") Long excludeId);

    boolean existsByFormDefinitionId(Long formDefinitionId);
}
