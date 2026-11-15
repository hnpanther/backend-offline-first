package com.hnp.backendofflinefirst.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * One page of a reference list a device fetched (V23, roadmap §31 «A download log»). Written by
 * {@code LookupDeviceService} on every page, never updated, never deleted with the list.
 *
 * <p>Two questions it answers: what a lost tablet held — by user and API session — and whether an
 * item can still be hard-deleted: one created after its list's last download never reached a
 * device, so no offline draft can name it.
 */
@Entity
@Table(name = "lookup_downloads")
@Data
public class LookupDownload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @Column(name = "user_id")
    private Long userId;

    /** The API session's {@code jti}, which names the device's session row. */
    @Column(name = "session_jti", length = 64)
    private String sessionJti;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "since_updated_at", nullable = false)
    private long sinceUpdatedAt;

    @Column(name = "since_id", nullable = false)
    private long sinceId;

    @Column(name = "fetched_at", nullable = false)
    private long fetchedAt;
}
