package com.hnp.backendofflinefirst.repository;

import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.entity.Device;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByKeyFingerprint(String keyFingerprint);

    /**
     * The row, locked against a concurrent revoke or approval.
     *
     * <p>Every state change of a device goes through this lock — request, confirmation, code
     * issue, revoke — so two of them can never interleave on one row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Device d WHERE d.id = :id")
    Optional<Device> findByIdForUpdate(@Param("id") Long id);

    /**
     * The device's status, read under a SHARE lock, for the session being registered.
     *
     * <p>This is what closes the race between a login and a revoke. The login checks the device
     * and only then inserts its session; a revoke marks the device and then revokes its
     * sessions. Without a lock the revoke can land in between — its bulk update finds no session
     * yet, the login then inserts one, and a revoked tablet holds a live session. A SHARE lock
     * here conflicts with the revoke's {@link #findByIdForUpdate}: whichever commits first, the
     * other sees it.
     *
     * <p>The entity, not its status column: a pessimistic lock is applied to the root entity of
     * a query, and a scalar projection is not guaranteed to carry it.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT d FROM Device d WHERE d.id = :id")
    Optional<Device> findByIdForShare(@Param("id") Long id);

    Page<Device> findByStatusIn(Collection<DeviceStatus> statuses, Pageable pageable);

    long countByStatus(DeviceStatus status);

    /** Unexpired pending requests, for the global cap on how many may wait at once. */
    @Query("SELECT COUNT(d) FROM Device d WHERE d.status = :pending AND d.requestExpiresAt > :now")
    long countLivePending(@Param("pending") DeviceStatus pending, @Param("now") long now);

    /** Unexpired pending requests from one address, for the per-address cap. */
    @Query("""
            SELECT COUNT(d) FROM Device d
             WHERE d.status = :pending AND d.requestExpiresAt > :now AND d.requestIp = :ip
            """)
    long countLivePendingFromIp(@Param("pending") DeviceStatus pending,
                                @Param("ip") String ip,
                                @Param("now") long now);

    /**
     * Records that an approved device just logged in.
     *
     * <p>A bulk update rather than {@code save}, so a login does not write an audit row: the
     * session row already records the login, and {@code last_seen_at} is not a change anybody
     * needs a history of.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Device d SET d.lastSeenAt = :now WHERE d.id = :id")
    int touchLastSeen(@Param("id") Long id, @Param("now") long now);

    /**
     * Records that a wipe order is being handed to the tablet, and answers whether the order
     * <b>still stands</b> — 1 if it does (first delivery or a repeat), 0 if it was withdrawn or
     * replaced since the caller read the row.
     *
     * <p>One conditional UPDATE, and that is what makes it safe against a cancel racing it. A
     * cancel locks the row and refuses once {@code wipe_signalled_at} is set; this statement
     * re-checks {@code wipe_requested_at} on the row version it updates, so whichever commits
     * first, the other sees it: either the order is withdrawn and the tablet is never told, or the
     * tablet is told and the withdrawal is refused. {@code COALESCE} keeps the <i>first</i>
     * delivery time — a tablet that asks again is told again, and that changes nothing here.
     *
     * <p>Native, so a delivery is not an audit row of its own: the order (who, when) is audited
     * where it was given, and the page shows when it reached the tablet.
     */
    @Modifying
    @Query(value = """
            UPDATE devices
               SET wipe_signalled_at = COALESCE(wipe_signalled_at, :now)
             WHERE id = :id
               AND status = 'REVOKED'
               AND wipe_requested_at = :orderedAt
            """, nativeQuery = true)
    int markWipeSignalled(@Param("id") Long id,
                          @Param("orderedAt") long orderedAt,
                          @Param("now") long now);

    // ------------------------------------------------------------------ login challenges
    //
    // Native, and on purpose: a challenge is written and consumed on every login, and routing
    // that through an entity would put two audit rows in audit_log per login for a value that
    // is worthless one minute later (the repository aspect audits every save and delete).

    @Modifying
    @Query(value = """
            INSERT INTO device_auth_challenges (nonce, device_id, created_at, expires_at)
            VALUES (:nonce, :deviceId, :now, :expiresAt)
            """, nativeQuery = true)
    int insertChallenge(@Param("nonce") String nonce,
                        @Param("deviceId") Long deviceId,
                        @Param("now") long now,
                        @Param("expiresAt") long expiresAt);

    /**
     * Consumes a challenge: true exactly once, and only for the device it was issued to and only
     * before it expires.
     *
     * <p>A single DELETE, so the check and the consumption cannot be separated by a second login
     * presenting the same nonce — the loser deletes nothing.
     */
    @Modifying
    @Query(value = """
            DELETE FROM device_auth_challenges
             WHERE nonce = :nonce AND device_id = :deviceId AND expires_at > :now
            """, nativeQuery = true)
    int consumeChallenge(@Param("nonce") String nonce,
                         @Param("deviceId") Long deviceId,
                         @Param("now") long now);

    /** Drops every expired challenge. Run on each issue, so the table never needs a job. */
    @Modifying
    @Query(value = "DELETE FROM device_auth_challenges WHERE expires_at <= :now", nativeQuery = true)
    int deleteExpiredChallenges(@Param("now") long now);

    /**
     * Keeps at most {@code keep} outstanding challenges per device, newest first.
     *
     * <p>The challenge endpoint is public, so without this anybody who learned a device's id and
     * fingerprint could fill the table for two minutes at a time. A real tablet needs one.
     */
    @Modifying
    @Query(value = """
            DELETE FROM device_auth_challenges
             WHERE device_id = :deviceId
               AND nonce NOT IN (SELECT c.nonce FROM device_auth_challenges c
                                  WHERE c.device_id = :deviceId
                                  ORDER BY c.created_at DESC, c.nonce
                                  LIMIT :keep)
            """, nativeQuery = true)
    int trimChallenges(@Param("deviceId") Long deviceId, @Param("keep") int keep);
}
