package com.hnp.backendofflinefirst.dto;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.entity.Attachment;
import lombok.Builder;
import lombok.Data;

/**
 * Attachment metadata as the PWA and web UI see it. Deliberately no bytes — clients fetch
 * those from {@code GET /api/attachments/{id}} only when they actually need to display one.
 */
@Data
@Builder
public class AttachmentDto {
    private String id;
    private Long logSheetId;
    private Long assetId;
    private String fieldKey;
    private AttachmentKind kind;
    private String mimeType;
    private Long sizeBytes;
    private String sha256;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private Long uploadedAt;
    private Long createdByUserId;

    public static AttachmentDto from(Attachment a) {
        return AttachmentDto.builder()
                .id(a.getId())
                .logSheetId(a.getLogSheetId())
                .assetId(a.getAssetId())
                .fieldKey(a.getFieldKey())
                .kind(a.getKind())
                .mimeType(a.getMimeType())
                .sizeBytes(a.getSizeBytes())
                .sha256(a.getSha256())
                .width(a.getWidth())
                .height(a.getHeight())
                .durationMs(a.getDurationMs())
                .uploadedAt(a.getUploadedAt())
                .createdByUserId(a.getCreatedByUserId())
                .build();
    }
}
