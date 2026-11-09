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

    /**
     * The asset this media belongs to, or <b>null</b> when it answers the <b>round's own</b>
     * question — one asked once per sheet rather than once per asset (V10).
     *
     * <p>Those are the only two owners an attachment inside a log sheet can have, and
     * {@code log_sheet_id} is NOT NULL either way, so null carries the whole discriminator. A
     * separate {@code owner_type} column would hold one bit this already holds and every
     * existing query would have to learn to read it.
     */
    @Column(name = "asset_id")
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

    /**
     * Set when this file is <b>evidence for one voided submission</b> rather than part of the live
     * round (V15): a photograph the departed operator's void names, delivered after the sheet had
     * moved on. Everything that reads the live round leaves such a row out — the per-field
     * ceiling, the web fill page's reference adoption, the bundle, the integration API — and it
     * cannot be deleted. Null for every ordinary attachment.
     */
    @Column(name = "void_submission_id")
    private Long voidSubmissionId;

    /** Whether this row belongs to the live round, as opposed to a void's evidence. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @jakarta.persistence.Transient
    public boolean isLive() {
        return voidSubmissionId == null;
    }
}
