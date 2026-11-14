package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.LookupImportMode;
import jakarta.persistence.*;
import lombok.Data;

/**
 * One applied Excel import into a lookup list: who, when, which file, and what it did — the
 * numbers the administrator confirmed on the preview (V22).
 */
@Entity
@Table(name = "lookup_list_imports")
@Data
public class LookupListImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private LookupImportMode mode;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "rows_read", nullable = false)
    private int rowsRead;

    @Column(name = "added", nullable = false)
    private int added;

    @Column(name = "updated", nullable = false)
    private int updated;

    @Column(name = "unchanged", nullable = false)
    private int unchanged;

    @Column(name = "deactivated", nullable = false)
    private int deactivated;

    @Column(name = "reactivated", nullable = false)
    private int reactivated;

    @Column(name = "revision_after", nullable = false)
    private long revisionAfter;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;
}
