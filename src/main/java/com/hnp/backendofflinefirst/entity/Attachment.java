package com.hnp.backendofflinefirst.entity;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Metadata for one photo or voice note. The bytes live on disk at {@link #storageKey};
 * see the {@code attachments} table comment in V1 for why.
 */
@Data
@Entity
@Table(name = "attachments")
public class Attachment {

    /**
     * UUID minted by the client, which is what makes upload idempotent — a retry after a
     * dropped connection re-sends the same id and gets the existing row back rather than
     * creating a second copy of the same photo.
     */
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "log_sheet_id", nullable = false)
    private Long logSheetId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private AttachmentKind kind;

    /** Verified against the file's magic bytes on upload — never the client's claim. */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Path relative to {@code app.attachments.storage-dir}. */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "uploaded_at", nullable = false)
    private Long uploadedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;
}
