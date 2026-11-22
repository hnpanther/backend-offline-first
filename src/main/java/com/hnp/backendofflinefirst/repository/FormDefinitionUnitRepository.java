package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.FormDefinitionUnit;
import com.hnp.backendofflinefirst.entity.FormDefinitionUnitId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface FormDefinitionUnitRepository extends JpaRepository<FormDefinitionUnit, FormDefinitionUnitId> {

    List<FormDefinitionUnit> findByFormDefinitionId(Long formDefinitionId);

    @Query("SELECT u.operationalUnitId FROM FormDefinitionUnit u WHERE u.formDefinitionId = :formId")
    Set<Long> findUnitIdsByFormDefinitionId(@Param("formId") Long formId);

    boolean existsByFormDefinitionIdAndOperationalUnitId(Long formDefinitionId, Long operationalUnitId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM FormDefinitionUnit u WHERE u.formDefinitionId = :formId")
    void deleteByFormDefinitionId(@Param("formId") Long formId);

    @Query("""
            SELECT DISTINCT u.formDefinitionId FROM FormDefinitionUnit u
            WHERE u.operationalUnitId IN :unitIds
            """)
    Set<Long> findFormIdsLinkedToAnyUnit(@Param("unitIds") Collection<Long> unitIds);
}
