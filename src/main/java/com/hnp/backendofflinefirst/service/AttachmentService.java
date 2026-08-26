package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.domain.AttachmentIds;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRevisionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.LinkedHashMap;
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
    private final LogSheetEntryRevisionRepository revisionRepository;
    private final LogSheetVoidSubmissionRepository voidSubmissionRepository;
    private final LogSheetEntryRepository logSheetEntryRepository;
    private final LogSheetAccessService logSheetAccessService;
    private final LogSheetFieldDefinitionsService fieldDefinitionsService;
    private final AttachmentStorageService storageService;

    private final AppSettingsService appSettingsService;

    @Value("${app.attachments.max-file-size-bytes}")
    private long maxFileSizeBytes;

    /**
     * Per-kind byte ceilings.
     *
     * <p>Deliberately <em>not</em> in the admin Settings page: an administrator reasons in
     * "how many photos", not in megabytes, and a byte cap set by hand is far likelier to be
     * wrong than useful. These are properties so an operator of the server can still raise
     * them, with the global {@code max-file-size-bytes} as the outer ceiling.
     *
     * <p>The video number is the one that matters. A 480p / 700 kbps capture runs about
     * 90 KB/s, so two minutes lands near 11 MB; 20 MB leaves headroom for a high-motion scene
     * where the encoder overshoots its bitrate hint, without letting a 100 MB file through.
     */
    @Value("${app.attachments.max-image-bytes:5242880}")
    private long maxImageBytes;

    @Value("${app.attachments.max-audio-bytes:5242880}")
    private long maxAudioBytes;

    @Value("${app.attachments.max-video-bytes:20971520}")
    private long maxVideoBytes;

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
    /**
     * Reads an upload straight from the request, refusing an oversized one before it is buffered.
     *
     * <p><b>This is the overload every controller should call.</b> The {@code byte[]} form below
     * checks the size only after the whole body is already in heap, and the container's own
     * {@code spring.servlet.multipart.max-file-size} is 50 MB because the Excel import needs it —
     * twice this service's own 25 MB ceiling. So a caller that materialises first hands the JVM
     * up to 50 MB per concurrent request to hold and then throw away, and the protection that
     * exists to prevent exactly that ({@link AttachmentStorageService#readAtMost}) was written,
     * tested, and then not used by anything.
     *
     * <p>The cap applied here is the outer ceiling. The per-kind limits (5 MB image, 20 MB video)
     * are checked further down, once the content is known to be small enough to look at.
     */
    public Attachment upload(String attachmentId,
                             Long logSheetId,
                             Long assetId,
                             String fieldKey,
                             InputStream content,
                             Integer width,
                             Integer height,
                             Long durationMs) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("Attachment file is empty.");
        }
        return upload(attachmentId, logSheetId, assetId, fieldKey,
                AttachmentStorageService.readAtMost(content, maxFileSizeBytes),
                width, height, durationMs);
    }

    /**
     * The same upload from bytes already in memory.
     *
     * <p>Kept for tests and for callers that genuinely hold the content. Prefer the stream
     * overload above from anything reading a request: by the time this is called the memory has
     * already been spent, and the size check below can only decide whether to keep it.
     */
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

        // The id becomes a file name, so it must be one already rather than be made into one.
        // Silently stripping unusable characters let two ids name a single file; see
        // {@link AttachmentIds} for what that cost.
        String canonicalId = AttachmentIds.canonicalise(attachmentId);

        // Looked up as sent first: a row written before ids were canonicalised may be in upper
        // case, and it must still resolve to an idempotent hit rather than a second insert that
        // would collide on the storage key.
        Optional<Attachment> existing = attachmentRepository.findById(attachmentId);
        if (existing.isEmpty() && !canonicalId.equals(attachmentId)) {
            existing = attachmentRepository.findById(canonicalId);
        }
        if (existing.isPresent()) {
            // Re-check access even on the idempotent path — an id alone must never be a key.
            logSheetAccessService.requireVisibleLogSheet(existing.get().getLogSheetId());
            return existing.get();
        }

        LogSheet sheet = logSheetAccessService.requireVisibleLogSheet(logSheetId);
        LogSheetEntry entry = requireEntry(sheet, assetId);
        AttachmentKind kind = resolveKindForField(sheet, entry, fieldKey);

        // Everything below is re-checked here even though the clients check it too. The client
        // checks exist to give a good message before the operator wastes a capture; these exist
        // because a client is not a trust boundary — a stale tablet, a replayed request or a
        // hand-rolled call must not be able to plant a 200 MB file or a 30th photo.
        AppSettingsService.AttachmentLimits limits = appSettingsService.getAttachmentLimits();
        enforceSizeForKind(kind, content.length);
        enforceDuration(kind, durationMs, limits);
        enforceCount(entry, fieldKey, kind, limits);

        String detected = AttachmentStorageService.detectMimeType(content);
        detected = AttachmentStorageService.resolveWebmType(detected, kind);
        if (detected == null) {
            throw new IllegalArgumentException("Unsupported attachment file type.");
        }
        if (!AttachmentStorageService.matchesKind(detected, kind)) {
            throw new IllegalArgumentException(
                    "Attachment type does not match the field: expected " + kind + ".");
        }

        String storageKey = storageService.store(canonicalId, content, detected);

        Attachment attachment = new Attachment();
        attachment.setId(canonicalId);
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

    private void enforceSizeForKind(AttachmentKind kind, int length) {
        long cap = switch (kind) {
            case IMAGE -> maxImageBytes;
            case AUDIO -> maxAudioBytes;
            case VIDEO -> maxVideoBytes;
        };
        if (length > cap) {
            throw new IllegalArgumentException(
                    "حجم فایل بیش از حد مجاز برای این نوع پیوست است (حداکثر "
                            + (cap / (1024 * 1024)) + " مگابایت).");
        }
    }

    /**
     * Rejects a clip longer than the configured ceiling.
     *
     * <p>A missing duration is accepted rather than rejected: not every container lets a client
     * measure it reliably, and refusing an otherwise valid recording because its metadata was
     * absent would lose real evidence. The byte cap above is the backstop for that case — a
     * clip long enough to matter is also large enough to trip it.
     */
    private void enforceDuration(AttachmentKind kind, Long durationMs,
                                 AppSettingsService.AttachmentLimits limits) {
        Long cap = limits.maxDurationMsFor(kind);
        if (cap == null || durationMs == null) {
            return;
        }
        // One second of slack: browsers report a duration a few ms past a clean stop.
        if (durationMs > cap + 1000L) {
            throw new IllegalArgumentException(
                    "مدت این فایل بیش از حد مجاز است (حداکثر " + (cap / 1000) + " ثانیه).");
        }
    }

    /**
     * Rejects an upload that would exceed the per-field count for its kind, reclaiming a
     * leftover first if that is what is standing in the way.
     *
     * <p>Counted per (sheet, asset, field, kind) — the unit an operator actually experiences.
     * Only the same kind counts: an audio note must not consume a photo slot on a field that
     * somehow accepts both.
     *
     * <h2>The dead end this exists to end</h2>
     *
     * <p>The ceiling used to count every row for the triple, whatever it was for. An operator on
     * a one-video field recorded a clip, it uploaded, they re-recorded — and the device's
     * server-side delete of the first one never landed (see the PWA's {@code removeAttachment}).
     * The server then held one video that <b>nothing referenced</b>, the field was full forever,
     * and the replacement was refused on every sync pass for the rest of the round: {@code 409},
     * retryable by design, retried eleven times in the log and unable to ever succeed. There was
     * no way out from the tablet, because the operator cannot delete a file the device has
     * forgotten.
     *
     * <p>So a row this entry's {@code form_data} does not point at is not evidence — it is a
     * leftover from a capture that was replaced — and when the ceiling is otherwise reached the
     * <b>oldest such leftover is deleted to make room</b>. The total for a field never exceeds
     * the configured maximum, so a hand-rolled client gains nothing: it can replace, not
     * accumulate.
     *
     * <h2>What is never reclaimed</h2>
     *
     * <p>Anything some reading points at: this entry's current {@code form_data}, any of its
     * <b>revisions</b> (a superseded photo is what «مقادیر پیشین» shows a reviewer), and any
     * <b>void submission</b> on the sheet (a refused payload is kept precisely so a supervisor
     * can look at what arrived). If every row for the field is spoken for, the upload is refused
     * exactly as before — the field really is full.
     *
     * <p>What is bounded is <b>rows</b>, not ids the reading mentions. A reference with no row
     * is ambiguous — a capture still queued on a device, or a pointer left behind by a delete —
     * and counting it would turn the second case into a fresh dead end. The total stays bounded
     * anyway, because a queued capture is judged by this same rule the moment its bytes land.
     *
     * <p>The reference normally arrives <em>before</em> the bytes: the id is minted on the device
     * at capture time and written into {@code form_data} immediately, while the progress push
     * runs earlier in the sync pass than the attachment queue. So on the shipping client an
     * upload's own reference is already here when it lands, and an unreferenced row really is a
     * leftover rather than a sibling still on its way.
     */
    private void enforceCount(LogSheetEntry entry, String fieldKey, AttachmentKind kind,
                              AppSettingsService.AttachmentLimits limits) {
        int max = limits.maxCountFor(kind);
        List<Attachment> forField = attachmentRepository
                .findByLogSheetIdAndAssetIdAndFieldKey(
                        entry.getLogSheetId(), entry.getAssetId(), fieldKey).stream()
                .filter(a -> a.getKind() == kind)
                .toList();

        Set<String> referenced = referencedIds(entry, fieldKey);

        // Rows are what is bounded — not ids the reading mentions.
        //
        // A reference with no row is ambiguous: it is either a capture still queued on a device
        // or a leftover pointer to something already deleted, and nothing here can tell those
        // apart. Counting it would make the second case a fresh dead end — a supervisor deleting
        // a photo in the panel would wedge the field until somebody rewrote `form_data`. Not
        // counting it costs nothing, because the total is still bounded: the moment that queued
        // capture's bytes arrive it becomes a row and is judged by exactly this rule. That path
        // is self-healing, too — an unreferenced row admitted while a referenced one was in
        // flight is a leftover by the time the referenced one lands, so it is the row reclaimed.
        //
        // `+ 1` is this upload: reaching here means its id has no row, because an id that does
        // returned on the idempotent path far above.
        long eventual = forField.size() + 1;
        if (eventual <= max) {
            return;
        }

        Set<String> protectedIds = protectedAttachmentIds(entry, referenced);
        List<Attachment> leftovers = forField.stream()
                .filter(a -> !protectedIds.contains(a.getId()))
                .sorted(java.util.Comparator.comparing(
                        Attachment::getUploadedAt, java.util.Comparator.nullsFirst(Long::compareTo)))
                .toList();

        // Only as many as the ceiling actually requires, oldest first. Reclaiming every leftover
        // whenever one exists would be tidier and wrong: on a three-photo field a sibling whose
        // reference has not been pushed yet looks exactly like a leftover, and there is no
        // reason to touch it while there is room for both.
        long needed = eventual - max;
        if (leftovers.size() < needed) {
            // IllegalStateException → 409, not IllegalArgumentException → 400, and the
            // difference is load-bearing for the mobile client. Every other refusal in this
            // method is about the payload: the same bytes will be refused forever, so the
            // upload queue parks the file and stops retrying. This one is about *state* — the
            // field is full right now — and it stops being true the moment a slot frees. A
            // client that cannot tell them apart either retries a doomed file forever or
            // permanently buries one that would succeed on the next pass.
            throw new IllegalStateException(
                    "تعداد پیوست این فیلد به حد مجاز رسیده است (حداکثر " + max + ").");
        }

        for (int i = 0; i < needed; i++) {
            Attachment leftover = leftovers.get(i);
            log.info("[ATTACHMENT] reclaiming unreferenced {} on sheet={} asset={} field={} id={}",
                    kind, entry.getLogSheetId(), entry.getAssetId(), fieldKey, leftover.getId());
            attachmentRepository.delete(leftover);
            storageService.delete(leftover.getStorageKey());
        }
    }

    /**
     * Every attachment id on this entry that some reading still points at.
     *
     * <p>Three sources, and each is a place a reviewer can open the file from: the entry's own
     * {@code form_data}, its revisions (the «مقادیر پیشین» panel), and the sheet's void
     * submissions (a refused payload, kept so a supervisor can see what arrived).
     */
    private Set<String> protectedAttachmentIds(LogSheetEntry entry, Set<String> referenced) {
        Set<String> out = new LinkedHashSet<>(referenced);
        revisionRepository.findByLogSheetEntryIdOrderByIdAsc(entry.getId())
                .forEach(rev -> collectIds(rev.getFormData(), out));
        voidSubmissionRepository.findByLogSheetId(entry.getLogSheetId())
                .forEach(sub -> {
                    if (sub.getPayload() == null) return;
                    sub.getPayload().forEach(row -> collectIds(row, out));
                });
        return out;
    }

    /** Every attachment id anywhere in one form-data-shaped map, canonicalised. */
    private static void collectIds(Map<String, Object> data, Set<String> into) {
        if (data == null) return;
        AttachmentReferences.extract(data).values()
                .forEach(ids -> ids.forEach(id -> {
                    if (id != null && !id.isBlank()) into.add(AttachmentIds.canonicalise(id));
                }));
        // A void submission payload is one whole entry, so its readings sit under `formData`.
        Object nested = data.get("formData");
        if (nested instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((k, v) -> typed.put(String.valueOf(k), v));
            collectIds(typed, into);
        }
    }

    /**
     * The ids this entry's {@code form_data} currently points at for one field.
     *
     * <p>Canonicalised, because a reference minted before ids were canonicalised may differ in
     * case from the row it names, and one file must not hold two slots.
     */
    private static Set<String> referencedIds(LogSheetEntry entry, String fieldKey) {
        List<String> ids = AttachmentReferences.idsOf(
                entry.getFormData() == null ? null : entry.getFormData().get(fieldKey));
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(AttachmentIds.canonicalise(id));
            }
        }
        return out;
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
    public boolean delete(String attachmentId) {
        // Idempotent: deleting something that is not there IS the requested end state, and this
        // is the only sane answer for a queue that retries.
        //
        // It used to throw, which the handler turned into 400. The tablet's delete queue treats
        // 404 as "already gone, done" and everything else as "leave it queued and stop the
        // pass" — so a row the server had never heard of wedged the whole queue on every future
        // pass, and every deletion behind it with it. A deletion that cannot drain leaves the
        // server's copy counting against the field's ceiling, which is the dead end
        // `enforceCount` documents.
        Optional<Attachment> found = attachmentRepository.findById(attachmentId);
        if (found.isEmpty()) {
            return false;
        }
        Attachment attachment = found.get();
        logSheetAccessService.requireVisibleLogSheet(attachment.getLogSheetId());

        attachmentRepository.delete(attachment);
        storageService.delete(attachment.getStorageKey());
        return true;
    }

    @Transactional(readOnly = true)
    public List<Attachment> findForLogSheet(Long logSheetId) {
        return attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(logSheetId);
    }


    /**
     * The kind this field accepts, verified against the sheet's own frozen definitions.
     *
     * <p>Reading the kind from the sheet's snapshot rather than from the request is what stops
     * a client attaching a photo to a numeric field, or claiming a field is an image field
     * when it is not. It also means the answer matches the form the operator actually saw.
     */
    private LogSheetEntry requireEntry(LogSheet sheet, Long assetId) {
        if (assetId == null) {
            throw new IllegalArgumentException("Attachment asset and field are required.");
        }
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Asset is not part of this log sheet."));
    }

    private AttachmentKind resolveKindForField(LogSheet sheet, LogSheetEntry entry, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            throw new IllegalArgumentException("Attachment asset and field are required.");
        }
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


    public record DownloadedAttachment(Attachment attachment, byte[] content) {}
}
