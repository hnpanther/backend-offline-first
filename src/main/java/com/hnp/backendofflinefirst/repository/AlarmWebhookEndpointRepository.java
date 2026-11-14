package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.entity.AlarmWebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AlarmWebhookEndpointRepository extends JpaRepository<AlarmWebhookEndpoint, Long> {

    List<AlarmWebhookEndpoint> findAllByOrderByNameAsc();

    /** The receivers an event fans out to; the caller filters by subscription. */
    List<AlarmWebhookEndpoint> findByActiveTrueOrderByIdAsc();

    boolean existsByActiveTrue();

    @Query("SELECT e FROM AlarmWebhookEndpoint e WHERE LOWER(e.name) = LOWER(:name)")
    Optional<AlarmWebhookEndpoint> findByNameIgnoreCase(@Param("name") String name);
}
