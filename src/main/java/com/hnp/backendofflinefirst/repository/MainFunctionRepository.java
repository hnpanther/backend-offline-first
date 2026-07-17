package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.MainFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MainFunctionRepository extends JpaRepository<MainFunction, Long> {

    @Query("""
            SELECT m FROM MainFunction m
            WHERE LOWER(m.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<MainFunction> search(@Param("q") String q, Pageable pageable);
    List<MainFunction> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<MainFunction> findByCode(String code);
    Optional<MainFunction> findByName(String name);
    List<MainFunction> findAllByOrderByIdDesc();

    List<MainFunction> findBySystemId(Long systemId);

    List<MainFunction> findBySystemIdAndParentIdIsNull(Long systemId);

    List<MainFunction> findByParentId(Long parentId);
    boolean existsByParentId(Long parentId);
    boolean existsBySystemId(Long systemId);
    boolean existsByLocationId(Long locationId);

    @Query("SELECT mf.id FROM MainFunction mf WHERE mf.locationId IN :locationIds")
    List<Long> findIdsByLocationIdIn(@Param("locationIds") Collection<Long> locationIds);

    @Query("SELECT mf.id FROM MainFunction mf WHERE mf.systemId IN :systemIds")
    List<Long> findIdsBySystemIdIn(@Param("systemIds") Collection<Long> systemIds);

    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id FROM main_functions WHERE id IN (:rootIds)
                UNION ALL
                SELECT child.id FROM main_functions child
                INNER JOIN tree parent ON child.parent_id = parent.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<Long> findDescendantIdsIncludingRoots(@Param("rootIds") Collection<Long> rootIds);

    @Query("""
            SELECT mf.systemId AS systemId, mf.locationId AS locationId, mf.parentId AS parentId
            FROM MainFunction mf WHERE mf.id = :id
            """)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    Optional<MainFunctionAncestry> findPersistedAncestryById(@Param("id") Long id);
}
