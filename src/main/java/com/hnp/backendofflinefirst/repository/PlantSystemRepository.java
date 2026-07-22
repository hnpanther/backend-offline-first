package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.PlantSystem;
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

public interface PlantSystemRepository extends JpaRepository<PlantSystem, Long> {

    @Query("""
            SELECT p FROM PlantSystem p
            WHERE LOWER(p.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    Page<PlantSystem> search(@Param("q") String q, Pageable pageable);

    @Query("""
            SELECT p FROM PlantSystem p
            WHERE p.locationId IN :locationIds
              AND (LOWER(p.code) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<PlantSystem> searchByLocationIdIn(@Param("q") String q,
                                           @Param("locationIds") Collection<Long> locationIds,
                                           Pageable pageable);

    Page<PlantSystem> findByLocationIdIn(Collection<Long> locationIds, Pageable pageable);
    List<PlantSystem> findByUpdatedAtGreaterThanEqual(Long since);
    Optional<PlantSystem> findByCode(String code);
    Optional<PlantSystem> findByCodeIgnoreCase(String code);
    Optional<PlantSystem> findByName(String name);
    List<PlantSystem> findAllByOrderByIdDesc();
    List<PlantSystem> findByParentId(Long parentId);
    boolean existsByParentId(Long parentId);
    boolean existsByLocationId(Long locationId);

    @Query("SELECT ps.id FROM PlantSystem ps WHERE ps.locationId IN :locationIds")
    List<Long> findIdsByLocationIdIn(@Param("locationIds") Collection<Long> locationIds);

    @Query(value = """
            WITH RECURSIVE tree AS (
                SELECT id FROM plant_systems WHERE id IN (:rootIds)
                UNION ALL
                SELECT child.id FROM plant_systems child
                INNER JOIN tree parent ON child.parent_id = parent.id
            )
            SELECT id FROM tree
            """, nativeQuery = true)
    List<Long> findDescendantIdsIncludingRoots(@Param("rootIds") Collection<Long> rootIds);

    @Query("""
            SELECT ps.locationId AS locationId, ps.parentId AS parentId
            FROM PlantSystem ps WHERE ps.id = :id
            """)
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    Optional<PlantSystemAncestry> findPersistedAncestryById(@Param("id") Long id);
}
