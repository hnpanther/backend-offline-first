package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.AlarmState;
import com.hnp.backendofflinefirst.entity.Alarm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    /**
     * The live alarm for one parameter of one asset, if any. The partial unique index
     * {@code ux_alarms_one_live_per_asset_and_field} (V18) allows at most one — whichever rule
     * raised it.
     */
    @Query("""
            SELECT a FROM Alarm a
            WHERE a.assetId = :assetId AND a.fieldDefinitionId = :fieldDefinitionId
              AND a.state <> com.hnp.backendofflinefirst.domain.AlarmState.CLOSED
            """)
    Optional<Alarm> findLive(@Param("assetId") Long assetId,
                             @Param("fieldDefinitionId") Long fieldDefinitionId);

    /**
     * Every live alarm of these assets — one query for a whole round, so the evaluation knows
     * which (asset, parameter) pairs need re-judging before it asks anything else.
     */
    @Query("""
            SELECT a FROM Alarm a
            WHERE a.assetId IN :assetIds
              AND a.state <> com.hnp.backendofflinefirst.domain.AlarmState.CLOSED
            """)
    List<Alarm> findLiveForAssets(@Param("assetIds") Collection<Long> assetIds);

    /**
     * The newest reading any closed alarm of this parameter on this asset has already used up.
     *
     * <p>Readings at or before it cannot raise a new alarm — otherwise closing an alarm whose
     * parameter is still out of range would see it raised again by the very readings it was
     * closed over. Withdrawn evidence is excluded: those readings were taken back, not used.
     * Asked per (asset, parameter), whichever rule raised the closed alarm: a reading spent is
     * spent, even if the parameter has since moved under another rule.
     */
    @Query("""
            SELECT MAX(a.lastReadingAt) FROM Alarm a
            WHERE a.assetId = :assetId AND a.fieldDefinitionId = :fieldDefinitionId
              AND a.state = com.hnp.backendofflinefirst.domain.AlarmState.CLOSED
              AND a.closeReason <> com.hnp.backendofflinefirst.domain.AlarmCloseReason.EVIDENCE_WITHDRAWN
            """)
    Long consumedWatermark(@Param("assetId") Long assetId,
                           @Param("fieldDefinitionId") Long fieldDefinitionId);

    boolean existsByAlarmRuleId(Long alarmRuleId);

    List<Alarm> findByAlarmRuleIdAndStateIn(Long alarmRuleId, Collection<AlarmState> states);

    /**
     * One page of alarms in scope, most urgent first: unacknowledged before acknowledged before
     * closed, then priority, then severity, then the newest reading.
     *
     * <p>{@code unitIds == null} is unrestricted, the {@code visibleUnitIds()} convention. An alarm
     * with no unit is therefore visible only to an unrestricted reader — it cannot be placed in
     * anybody's scope, and guessing would show it to the wrong supervisor.
     */
    @Query(value = """
            SELECT a FROM Alarm a
            WHERE (:unitIds IS NULL OR a.operationalUnitId IN :unitIds)
              AND (:states IS NULL OR a.state IN :states)
              AND (:severity IS NULL OR a.severity = :severity)
            ORDER BY
              CASE a.state
                WHEN com.hnp.backendofflinefirst.domain.AlarmState.OPEN THEN 0
                WHEN com.hnp.backendofflinefirst.domain.AlarmState.ACKNOWLEDGED THEN 1
                ELSE 2 END,
              CASE a.priority
                WHEN com.hnp.backendofflinefirst.domain.AlarmPriority.HIGH THEN 0
                WHEN com.hnp.backendofflinefirst.domain.AlarmPriority.MEDIUM THEN 1
                ELSE 2 END,
              CASE a.severity
                WHEN com.hnp.backendofflinefirst.domain.FieldValidationSeverity.DANGER THEN 0
                ELSE 1 END,
              a.lastReadingAt DESC,
              a.id DESC
            """,
            countQuery = """
            SELECT COUNT(a) FROM Alarm a
            WHERE (:unitIds IS NULL OR a.operationalUnitId IN :unitIds)
              AND (:states IS NULL OR a.state IN :states)
              AND (:severity IS NULL OR a.severity = :severity)
            """)
    Page<Alarm> search(@Param("unitIds") Collection<Long> unitIds,
                       @Param("states") Collection<AlarmState> states,
                       @Param("severity") com.hnp.backendofflinefirst.domain.FieldValidationSeverity severity,
                       Pageable pageable);

    @Query("""
            SELECT COUNT(a) FROM Alarm a
            WHERE (:unitIds IS NULL OR a.operationalUnitId IN :unitIds)
              AND a.state = :state
            """)
    long countInScope(@Param("unitIds") Collection<Long> unitIds, @Param("state") AlarmState state);

    /** Live alarms (either state) of one severity, in scope — the dashboard's «باز در محدوده خطر». */
    @Query("""
            SELECT COUNT(a) FROM Alarm a
            WHERE (:unitIds IS NULL OR a.operationalUnitId IN :unitIds)
              AND a.state <> com.hnp.backendofflinefirst.domain.AlarmState.CLOSED
              AND a.severity = :severity
            """)
    long countLiveInScopeWithSeverity(@Param("unitIds") Collection<Long> unitIds,
                                      @Param("severity") com.hnp.backendofflinefirst.domain.FieldValidationSeverity severity);
}
