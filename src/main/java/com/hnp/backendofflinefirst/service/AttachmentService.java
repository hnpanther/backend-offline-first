package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creating, reading and deleting log-sheet attachments.
 *
 * <p><b>Access is always decided by the owning log sheet</b>, never by knowing an id. Every
 * method here starts by resolving the sheet through {@link LogSheetAccessService}, which
 * applies the same unit-scope rule the rest of the app uses. A UUID in a URL is not a
 * capability: without this, anyone who saw one photo's link could enumerate plant imagery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final AttachmentStorageService storageService;

    @Value("${app.attachments.max-file-size-bytes}")
    private long maxFileSizeBytes;

    /**
     * Stores an uploaded file and records it.
     *
     * <p><b>Idempotent by the client-minted id.</b> An upload that succeeded server-side but
     * whose response never reached the tablet will be retried with the same id; returning the
     * existing row keeps that from producing a duplicate photo. The bytes are not rewritten:
     * the first upload won, and a differing retry almost certainly means a client bug rather
     * than a genuine correction.
     */
    @Transactional
    public Attachment upload(String attachmentId,
                             Long logSheetId,
                             Long assetId,
                             String fieldKey,
                             byte[] content,
                             Integer width,
                             Integer height,
                             Long durationMs) throws IOException {
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new IllegalArgumentException("Attachment id is required.");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Attachment file is empty.");
        }
        if (content.length > maxFileSizeBytes) {
            throw new IllegalArgumentException("Attachment exceeds the maximum allowed size.");
        }

        Optional<Attachment> existing = attachmentRepository.findById(attachmentId);
        if (existing.isPresent()) {
            // Re-check access even on the idempotent path — an id alone must never be a key.
            logSheetAccessService.requireVisibleLogSheet(existing.get().getLogSheetId());
            return existing.get();
        }

        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(logSheetId);
        AttachmentKind kind = resolveKindForField(sheet, assetId, fieldKey);

        String detected = AttachmentStorageService.detectMimeType(content);
        detected = AttachmentStorageService.resolveWebmType(detected, kind);
        if (detected == null) {
            throw new IllegalArgumentException("Unsupported attachment file type.");
        }
        if (!AttachmentStorageService.matchesKind(detected, kind)) {
            throw new IllegalArgumentException(
                    "Attachment type does not match the field: expected " + kind + ".");
        }

        String storageKey = storageService.store(attachmentId, content, detected);

        Attachment attachment = new Attachment();
        attachment.setId(attachmentId);
        attachment.setLogSheetId(sheet.getId());
        attachment.setAssetId(assetId);
        attachment.setFieldKey(fieldKey);
        attachment.setKind(kind);
        attachment.setMimeType(detected);
        attachment.setSizeBytes((long) content.length);
        attachment.setSha256(AttachmentStorageService.sha256Hex(content));
        attachment.setWidth(kind == AttachmentKind.IMAGE ? width : null);
        attachment.setHeight(kind == AttachmentKind.IMAGE ? height : null);
        attachment.setDurationMs(kind == AttachmentKind.IMAGE ? null : durationMs);
        attachment.setStorageKey(storageKey);
        attachment.setUploadedAt(System.currentTimeMillis());
        attachment.setCreatedByUserId(SecurityUtils.currentUserId());
        return attachmentRepository.save(attachment);
    }

    /** Metadata plus bytes, refused unless the caller may see the owning sheet. */
    @Transactional(readOnly = true)
    public DownloadedAttachment download(String attachmentId) throws IOException {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found."));
        logSheetAccessService.requireVisibleLogSheet(attachment.getLogSheetId());

        if (!storageService.exists(attachment.getStorageKey())) {
            // Row without bytes: recoverable only by re-upload, so say so rather than 500.
            throw new IllegalStateException("Attachment file is missing from storage.");
        }
        return new DownloadedAttachment(attachment, storageService.read(attachment.getStorageKey()));
    }

    /**
     * Deletes an attachment and its file.
     *
     * <p>Row first, then the file. If the file delete fails the row is already gone and a
     * sweep can reclaim the bytes later; the reverse order would leave a row pointing at
     * nothing, which every reader would have to defend against.
     */
    @Transactional
    public void delete(String attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found."));
        logSheetAccessService.requireVisibleLogSheet(attachment.getLogSheetId());

        attachmentRepository.delete(attachment);
        storageService.delete(attachment.getStorageKey());
    }

    @Transactional(readOnly = true)
    public List<Attachment> findForLogSheet(Long logSheetId) {
        return attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(logSheetId);
    }

    @Transactional(readOnly = true)
    public List<Attachment> findForLogSheets(Collection<Long> logSheetIds) {
        if (logSheetIds == null || logSheetIds.isEmpty()) {
            return List.of();
        }
        return attachmentRepository.findByLogSheetIdInOrderByUploadedAtAsc(logSheetIds);
    }

    /**
     * The kind this field accepts, verified against the sheet's own frozen definitions.
     *
     * <p>Reading the kind from the sheet's snapshot rather than from the request is what stops
     * a client attaching a photo to a numeric field, or claiming a field is an image field
     * when it is not. It also means the answer matches the form the operator actually saw.
     */
    private AttachmentKind resolveKindForField(LogSheet sheet, Long assetId, String fieldKey) {
        if (assetId == null || fieldKey == null || fieldKey.isBlank()) {
            throw new IllegalArgumentException("Attachment asset and field are required.");
        }
        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Asset is not part of this log sheet."));

        List<FieldDefinition> defs = fieldDefinitionsService.resolveForClass(sheet, entry.getClassId());
        FieldDefinition field = defs.stream()
                .filter(fd -> fieldKey.equals(fd.getKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Field '" + fieldKey + "' is not part of this asset's class."));

        AttachmentKind kind = AttachmentKind.forFieldDataType(field.getDataType());
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Field '" + fieldKey + "' does not accept attachments.");
        }
        return kind;
    }

    /** Attachment ids referenced by an entry's form data, grouped per field key. */
    public static Map<String, List<String>> referencedIds(Map<String, Object> formData) {
        return AttachmentReferences.extract(formData);
    }

    public record DownloadedAttachment(Attachment attachment, byte[] content) {}
}
