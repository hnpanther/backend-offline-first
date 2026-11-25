package com.hnp.backendofflinefirst.controller;

import com.hnp.backendofflinefirst.dto.AttachmentDto;
import com.hnp.backendofflinefirst.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * Upload / download / delete for log-sheet attachments.
 *
 * <p>Separate from {@code POST /api/log-sheets/batch} on purpose: a submission must stay small
 * and atomic, so a dropped connection costs one photo rather than a whole shift's readings.
 * Each file is its own request with its own retry, keyed by a client-minted id that makes the
 * retry idempotent.
 *
 * <p>Every method is additionally gated inside {@link AttachmentService} by the owning log
 * sheet's access rule; the permissions here only open the endpoint.
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * @param id client-minted UUID — re-sending it after a failed response returns the
     *           existing attachment instead of storing a second copy.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('POST:/api/attachments')")
    public ResponseEntity<AttachmentDto> upload(@RequestParam("id") String id,
                                                @RequestParam(value = "logSheetId", required = false) Long logSheetId,
                                                // Optional since V10: absent means the media
                                                // answers the ROUND's own question rather than
                                                // an asset's. An older tablet always sends it,
                                                // so nothing about its behaviour changes.
                                                @RequestParam(value = "assetId", required = false) Long assetId,
                                                // V29: XOR with logSheetId — ad-hoc form media.
                                                @RequestParam(value = "formSubmissionId", required = false) Long formSubmissionId,
                                                @RequestParam("fieldKey") String fieldKey,
                                                @RequestParam(value = "width", required = false) Integer width,
                                                @RequestParam(value = "height", required = false) Integer height,
                                                @RequestParam(value = "durationMs", required = false) Long durationMs,
                                                @RequestParam("file") MultipartFile file) throws IOException {
        // getInputStream(), not getBytes(): the size ceiling is applied while reading, so an
        // oversized upload is refused instead of being held in heap and then rejected.
        if (formSubmissionId != null) {
            if (logSheetId != null) {
                throw new IllegalArgumentException(
                        "Provide either logSheetId or formSubmissionId, not both.");
            }
            return ResponseEntity.ok(AttachmentDto.from(attachmentService.uploadForFormSubmission(
                    id, formSubmissionId, fieldKey, file.getInputStream(), width, height, durationMs)));
        }
        if (logSheetId == null) {
            throw new IllegalArgumentException("logSheetId or formSubmissionId is required.");
        }
        return ResponseEntity.ok(AttachmentDto.from(attachmentService.upload(
                id, logSheetId, assetId, fieldKey, file.getInputStream(), width, height, durationMs)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GET:/api/attachments/{id}')")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        AttachmentService.DownloadedAttachment result = attachmentService.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.attachment().getMimeType()))
                // inline: these are meant to be viewed in place, not downloaded as files.
                .header("Content-Disposition", ContentDisposition.inline()
                        .filename(result.attachment().getId()).build().toString())
                // Content is immutable once uploaded (a correction is a new id), so it can be
                // cached hard. Private: it is scoped to one viewer's access, never shared.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                // A Resource, not a byte[] (issue B-15): streamed from disk, and a Range request
                // is answered with 206 by Spring itself.
                .body(result.content());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELETE:/api/attachments/{id}')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        attachmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/forms/{submissionId}/{id}")
    @PreAuthorize("hasAuthority('DELETE:/api/attachments/{id}')")
    public ResponseEntity<Void> deleteFormFile(@PathVariable Long submissionId, @PathVariable String id,
                                                @RequestParam String fieldKey) {
        attachmentService.deleteFormFile(submissionId, fieldKey, id);
        return ResponseEntity.noContent().build();
    }

}
