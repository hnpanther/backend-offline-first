package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AttachmentStorageService;
import com.hnp.backendofflinefirst.service.LogSheetBundleService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * End-to-end cover for the attachment endpoints.
 *
 * <p>Three things are being protected here, in order of how badly they would hurt:
 * <ol>
 *   <li><b>Access.</b> An attachment id is a UUID in a URL, not a capability. Every route
 *       resolves the owning log sheet and applies the same unit-scope rule as everything else,
 *       so an operator in another unit gets 403 even with a valid id.</li>
 *   <li><b>Content typing.</b> The declared type is ignored; magic bytes decide. A field that
 *       expects a photo must refuse audio, and unrecognised bytes must be refused outright —
 *       otherwise the download endpoint would later serve attacker-chosen bytes under an
 *       attacker-chosen content type.</li>
 *   <li><b>Idempotency.</b> A tablet on a weak link retries an upload whose response it never
 *       saw. The client-minted id has to make that a no-op rather than a second photo.</li>
 * </ol>
 */
@Transactional
class AttachmentApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired AttachmentStorageService storageService;
    @Autowired LogSheetBundleService bundleService;
    @Autowired AppSettingsService appSettingsService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    void uploadsAPhotoAndRecordsWhatWasActuallyStored() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();

        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("IMAGE"))
                // Not the declared type from the request — the type read off the bytes.
                .andExpect(jsonPath("$.mimeType").value("image/png"))
                .andExpect(jsonPath("$.fieldKey").value("pump_photo"));

        Attachment stored = attachmentRepository.findById(id).orElseThrow();
        assertThat(stored.getLogSheetId()).isEqualTo(f.sheetId());
        assertThat(stored.getSizeBytes()).isEqualTo(64L);
        assertThat(stored.getSha256()).hasSize(64);
        assertThat(storageService.exists(stored.getStorageKey())).isTrue();
        // Date-sharded so no single directory ever accumulates a year of uploads.
        assertThat(stored.getStorageKey()).matches("\\d{4}/\\d{2}/\\d{2}/[A-Za-z0-9-]+\\.png");
    }

    @Test
    void downloadsTheBytesBackWithTheDetectedContentType() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        byte[] bytes = png(64);
        mockMvc.perform(upload(f, id, "pump_photo", bytes, f.assetId())).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isOk())
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("image/png");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
        // Private, because the bytes are scoped to one viewer's access and must never be
        // held by a shared cache.
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("private");
    }

    @Test
    void acceptsAnAudioClipOnAnAudioField() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();

        mockMvc.perform(upload(f, id, "pump_sound", webmAudio(64), f.assetId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("AUDIO"))
                // The EBML header is shared by audio and video webm; the field's kind decides.
                .andExpect(jsonPath("$.mimeType").value("audio/webm"));

        assertThat(attachmentRepository.findById(id).orElseThrow().getKind())
                .isEqualTo(AttachmentKind.AUDIO);
    }

    @Test
    void keepsImageDimensionsAndDropsDurationForAPhoto() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();

        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())
                        .param("width", "1600")
                        .param("height", "1200")
                        .param("durationMs", "5000"))
                .andExpect(status().isOk());

        Attachment stored = attachmentRepository.findById(id).orElseThrow();
        assertThat(stored.getWidth()).isEqualTo(1600);
        assertThat(stored.getHeight()).isEqualTo(1200);
        // A photo has no duration; accepting the client's 5000 would produce nonsense metadata.
        assertThat(stored.getDurationMs()).isNull();
    }

    @Test
    void keepsDurationAndDropsDimensionsForAnAudioClip() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();

        mockMvc.perform(upload(f, id, "pump_sound", webmAudio(64), f.assetId())
                        .param("width", "1600")
                        .param("durationMs", "5000"))
                .andExpect(status().isOk());

        Attachment stored = attachmentRepository.findById(id).orElseThrow();
        assertThat(stored.getDurationMs()).isEqualTo(5000L);
        assertThat(stored.getWidth()).isNull();
    }

    @Test
    void theSheetBundleCarriesAttachmentMetadataButNeverTheBytes() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        var bundle = bundleService.buildFullBundle(logSheetRepository.findById(f.sheetId()).orElseThrow());

        // Metadata only, by design: this is what lets a device that re-downloads a sheet know
        // an attachment exists and fetch it on demand, without the bundle carrying megabytes.
        assertThat(bundle.getAttachments()).hasSize(1);
        assertThat(bundle.getAttachments().get(0).getId()).isEqualTo(id);
        assertThat(bundle.getAttachments().get(0).getFieldKey()).isEqualTo("pump_photo");
        assertThat(bundle.getAttachments().get(0).getSizeBytes()).isEqualTo(64L);
    }

    // -----------------------------------------------------------------------
    // Idempotency
    // -----------------------------------------------------------------------

    @Test
    void reUploadingTheSameIdReturnsTheExistingRowInsteadOfADuplicate() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());
        String firstKey = attachmentRepository.findById(id).orElseThrow().getStorageKey();

        // The retry a tablet sends when the first response never arrived. Different bytes on
        // purpose: the first upload won, and the row must not silently change underneath.
        mockMvc.perform(upload(f, id, "pump_photo", jpeg(80), f.assetId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.mimeType").value("image/png"));

        assertThat(attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(f.sheetId())).hasSize(1);
        assertThat(attachmentRepository.findById(id).orElseThrow().getStorageKey()).isEqualTo(firstKey);
    }

    @Test
    void theIdempotentPathStillChecksAccess() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        // Knowing an existing id must not be enough to have it handed back. This is the branch
        // that would be easy to leave unguarded, since the row is returned before any lookup.
        String outsiderToken = tokenForOperatorInAnotherUnit();
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.png", "image/png", png(64)))
                        .param("id", id)
                        .param("logSheetId", String.valueOf(f.sheetId()))
                        .param("assetId", String.valueOf(f.assetId()))
                        .param("fieldKey", "pump_photo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    // -----------------------------------------------------------------------
    // Content typing
    // -----------------------------------------------------------------------

    @Test
    void refusesAudioBytesOnAPhotoField() throws Exception {
        Fixture f = seed();
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", oggAudio(64), f.assetId()))
                .andExpect(status().isBadRequest());
        assertThat(attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(f.sheetId())).isEmpty();
    }

    @Test
    void refusesBytesItCannotIdentify() throws Exception {
        Fixture f = seed();
        // An executable relabelled as a photo. The declared "image/png" is ignored entirely;
        // accepting this would mean the download route later serves it back to a browser.
        byte[] payload = new byte[64];
        System.arraycopy("MZ ".getBytes(StandardCharsets.ISO_8859_1), 0, payload, 0, 4);

        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", payload, f.assetId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnEmptyFile() throws Exception {
        Fixture f = seed();
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", new byte[0], f.assetId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAFileOverTheConfiguredCap() throws Exception {
        Fixture f = seed();
        // Valid PNG bytes, simply too many of them — the cap is what keeps one tablet from
        // filling the server's disk through an endpoint that is otherwise wide open to it.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(8192), f.assetId()))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Field and sheet resolution
    // -----------------------------------------------------------------------

    @Test
    void refusesAFieldThatDoesNotTakeAttachments() throws Exception {
        Fixture f = seed();
        // "temp" is numeric. The kind comes from the sheet's own frozen definitions, so a
        // client cannot declare a field to be an image field when it is not.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "temp", png(64), f.assetId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAFieldThatIsNotInTheClassAtAll() throws Exception {
        Fixture f = seed();
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "invented_field", png(64), f.assetId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnAssetThatIsNotOnThisSheet() throws Exception {
        Fixture f = seed();
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId() + 9999))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnUnknownLogSheet() throws Exception {
        Fixture f = seed();
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.png", "image/png", png(64)))
                        .param("id", UUID.randomUUID().toString())
                        .param("logSheetId", "999999")
                        .param("assetId", String.valueOf(f.assetId()))
                        .param("fieldKey", "pump_photo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Access control
    // -----------------------------------------------------------------------

    @Test
    void refusesAnUploadToAnotherUnitsSheet() throws Exception {
        Fixture f = seed();
        String outsiderToken = tokenForOperatorInAnotherUnit();

        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.png", "image/png", png(64)))
                        .param("id", UUID.randomUUID().toString())
                        .param("logSheetId", String.valueOf(f.sheetId()))
                        .param("assetId", String.valueOf(f.assetId()))
                        .param("fieldKey", "pump_photo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesADownloadByAnyoneOutsideTheOwningUnit() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        mockMvc.perform(get("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenForOperatorInAnotherUnit()))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesADeleteByAnyoneOutsideTheOwningUnit() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        mockMvc.perform(delete("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenForOperatorInAnotherUnit()))
                .andExpect(status().isForbidden());

        assertThat(attachmentRepository.findById(id)).isPresent();
    }

    @Test
    void refusesEverythingWithoutAToken() throws Exception {
        Fixture f = seed();
        mockMvc.perform(get("/api/attachments/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "x.png", "image/png", png(64)))
                        .param("id", UUID.randomUUID().toString())
                        .param("logSheetId", String.valueOf(f.sheetId()))
                        .param("assetId", String.valueOf(f.assetId()))
                        .param("fieldKey", "pump_photo"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Configured ceilings
    //
    // These are enforced on the client too, for a decent message before an operator wastes a
    // capture. The point of repeating them here is that a client is not a trust boundary: a
    // stale tablet, a replayed request or a hand-rolled call must not get past them.
    // -----------------------------------------------------------------------

    @Test
    void refusesAPhotoOnceTheConfiguredCountIsReached() throws Exception {
        Fixture f = seed();
        setLimits(2, 1, 1, 120, 120);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                    .andExpect(status().isOk());
        }
        // 409, not 400, and the mobile client depends on the difference: a full field is a
        // state that stops being true when a slot frees, so the upload queue keeps the file and
        // retries. Every other refusal here is about the payload and is parked for good.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                .andExpect(status().isConflict());

        assertThat(attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(f.sheetId())).hasSize(2);
    }

    @Test
    void countsPerFieldPerAssetRatherThanPerSheet() throws Exception {
        Fixture f = seed();
        setLimits(1, 1, 1, 120, 120);

        // A photo on one field must not consume the slot belonging to a different field of the
        // same asset - the ceiling an operator experiences is "per field", not "per sheet".
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                .andExpect(status().isOk());
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo_2", png(64), f.assetId()))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotLetAnAudioClipConsumeAPhotoSlot() throws Exception {
        Fixture f = seed();
        setLimits(1, 1, 1, 120, 120);

        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_sound", webmAudio(64), f.assetId()))
                .andExpect(status().isOk());
        // Different field and different kind: the photo ceiling is untouched by the recording.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                .andExpect(status().isOk());
    }

    @Test
    void refusesAClipLongerThanTheConfiguredDuration() throws Exception {
        Fixture f = seed();
        setLimits(3, 1, 1, 30, 30);

        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_sound", webmAudio(64), f.assetId())
                        .param("durationMs", "45000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsAClipSittingOnTheDurationLimit() throws Exception {
        Fixture f = seed();
        setLimits(3, 1, 1, 30, 30);

        // Browsers report a duration a few milliseconds past a clean stop, so an exact-length
        // recording must not be rejected for arriving at 30_012 ms.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_sound", webmAudio(64), f.assetId())
                        .param("durationMs", "30500"))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsAClipWhoseDurationTheClientCouldNotMeasure() throws Exception {
        Fixture f = seed();
        setLimits(3, 1, 1, 30, 30);

        // Not every container yields a reliable duration. Refusing here would discard real
        // evidence over missing metadata; the byte ceiling is the backstop for that case.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_sound", webmAudio(64), f.assetId()))
                .andExpect(status().isOk());
    }

    @Test
    void appliesTheKindsOwnByteCeiling() throws Exception {
        // application-test.properties caps every kind at 4 KB, so 8 KB of valid PNG is over it.
        Fixture f = seed();
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(8192), f.assetId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAnAttachmentFreesItsSlot() throws Exception {
        Fixture f = seed();
        setLimits(1, 1, 1, 120, 120);
        String id = UUID.randomUUID().toString();

        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isNoContent());

        // Otherwise an operator who took a bad photo at the ceiling could never replace it.
        //
        // This is the server half of the fix for a real field bug. The endpoint always behaved
        // correctly — a deletion here has always freed a slot — but the PWA only ever deleted
        // its own row and never called it, so the server kept counting a file the operator
        // could no longer see. Every local delete permanently consumed a slot, and with audio
        // and video (ceiling of one) a single retake locked the field for good.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                .andExpect(status().isOk());
    }

    @Test
    void freeingASlotWorksForAudioAndVideoToo() throws Exception {
        // Their ceiling is one, so the divergence bit immediately there rather than on the
        // fourth photo — worth pinning that the delete-then-replace path is not image-only.
        Fixture f = seed();
        setLimits(3, 1, 1, 120, 120);

        String audioId = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, audioId, "pump_sound", webmAudio(64), f.assetId()))
                .andExpect(status().isOk());
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_sound", webmAudio(64), f.assetId()))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/attachments/" + audioId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_sound", webmAudio(64), f.assetId()))
                .andExpect(status().isOk());
    }

    @Test
    void anIdempotentRetryAtTheCeilingIsNotRefused() throws Exception {
        Fixture f = seed();
        setLimits(1, 1, 1, 120, 120);
        String id = UUID.randomUUID().toString();

        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        // The retry a tablet sends when it never saw the first response. Counting it as a
        // second photo would strand that file permanently at the limit.
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId()))
                .andExpect(status().isOk());
        assertThat(attachmentRepository.findByLogSheetIdOrderByUploadedAtAsc(f.sheetId())).hasSize(1);
    }

    @Test
    void shipsTheLimitsToTheAppOnBootstrap() throws Exception {
        Fixture f = seed();
        setLimits(4, 2, 1, 45, 90);

        // This is the whole delivery mechanism: an administrator edits the panel, and every
        // tablet picks the change up on its next bootstrap without anyone touching the device.
        mockMvc.perform(get("/api/bootstrap")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentLimits.maxImagesPerField").value(4))
                .andExpect(jsonPath("$.attachmentLimits.maxAudiosPerField").value(2))
                .andExpect(jsonPath("$.attachmentLimits.maxVideosPerField").value(1))
                .andExpect(jsonPath("$.attachmentLimits.maxAudioSeconds").value(45))
                .andExpect(jsonPath("$.attachmentLimits.maxVideoSeconds").value(90));
    }

    // -----------------------------------------------------------------------
    // Admin panel display
    //
    // The panel is a server-rendered Thymeleaf app on the *web* security chain, authenticated
    // by session. `/api/**` is a separate, stateless, JWT-only chain — so an <img src> pointing
    // at /api/attachments/{id} would simply 401. Hence a second route on the web chain.
    // -----------------------------------------------------------------------

    @Test
    // The annotation supplies the security context for the whole request, overriding the
    // Bearer token the upload helper sends — so it needs the upload authority as well.
    @WithAppUser(roles = "ADMIN",
            authorities = {"GET:/log-sheets/{id}", "POST:/api/attachments"})
    void servesAttachmentBytesToTheAdminPanelOverTheWebChain() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        byte[] bytes = png(64);
        mockMvc.perform(upload(f, id, "pump_photo", bytes, f.assetId())).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(
                        get("/log-sheets/" + f.sheetId() + "/attachments/" + id))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("image/png");
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    // The annotation supplies the security context for the whole request, overriding the
    // Bearer token the upload helper sends — so it needs the upload authority as well.
    @WithAppUser(roles = "ADMIN",
            authorities = {"GET:/log-sheets/{id}", "POST:/api/attachments"})
    void refusesAnAttachmentRequestedUnderTheWrongSheet() throws Exception {
        Fixture f = seed();
        Fixture other = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        // The sheet id in the path is what the access check is applied to, so it must be the
        // attachment's real owner — otherwise a viewer of any one sheet could read every file
        // in the system by pairing their sheet id with someone else's attachment id.
        //
        // 3xx rather than 400: this is the web chain, where the panel's error handler redirects
        // to a message page instead of returning a status body. What matters is that the bytes
        // are not served — an <img> pointed here simply fails to load.
        MvcResult denied = mockMvc.perform(
                        get("/log-sheets/" + other.sheetId() + "/attachments/" + id))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(denied.getResponse().getContentAsByteArray()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Voided sheets
    //
    // Voiding is a soft, reversible status change: the readings are excluded from parameter
    // reports but preserved, and `restoreVoided` puts the sheet back. So the evidence must
    // survive too — destroying photos on void would make the un-void meaningless.
    // -----------------------------------------------------------------------

    @Test
    void keepsAttachmentsWhenTheSheetIsVoided() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        voidSheet(f.sheetId());

        Attachment stored = attachmentRepository.findById(id).orElseThrow();
        assertThat(storageService.exists(stored.getStorageKey())).isTrue();
        mockMvc.perform(get("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isOk());
    }

    @Test
    void stillAcceptsAQueuedUploadAfterTheSheetWasVoided() throws Exception {
        Fixture f = seed();
        voidSheet(f.sheetId());

        // The realistic race: the sheet synced, a supervisor voided it, and only then did the
        // tablet get signal for the photo. Refusing here would strand the file forever and
        // leave the entry's form_data pointing at a reference that never resolves.
        mockMvc.perform(upload(f, UUID.randomUUID().toString(), "pump_photo", png(64), f.assetId()))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Deletion and missing files
    // -----------------------------------------------------------------------

    @Test
    void deleteRemovesBothTheRowAndTheFile() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());
        String key = attachmentRepository.findById(id).orElseThrow().getStorageKey();

        mockMvc.perform(delete("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isNoContent());

        assertThat(attachmentRepository.findById(id)).isEmpty();
        assertThat(storageService.exists(key)).isFalse();
    }

    @Test
    void reportsAMissingFileAsAConflictRatherThanAServerError() throws Exception {
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        mockMvc.perform(upload(f, id, "pump_photo", png(64), f.assetId())).andExpect(status().isOk());

        // Simulate the file being lost underneath us (restore, disk swap, manual tidy-up).
        Path onDisk = storageService.getRoot()
                .resolve(attachmentRepository.findById(id).orElseThrow().getStorageKey());
        Files.delete(onDisk);

        // 409, not 500: the row is intact and the fix is a re-upload, which is worth telling
        // the client rather than hiding behind a generic failure.
        mockMvc.perform(get("/api/attachments/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void reportsAnUnknownAttachmentAsABadRequest() throws Exception {
        Fixture f = seed();
        mockMvc.perform(get("/api/attachments/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Moves the fixture's sheet to SUBMITTED and then VOIDED, as a supervisor would. */
    private void voidSheet(Long sheetId) {
        LogSheet sheet = logSheetRepository.findById(sheetId).orElseThrow();
        sheet.setStatus(LogSheetStatus.VOIDED);
        sheet.setUpdatedAt(System.currentTimeMillis());
        logSheetRepository.save(sheet);
    }

    private MockMultipartHttpServletRequestBuilder upload(
            Fixture f, String id, String fieldKey, byte[] content, Long assetId) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/attachments");
        builder.file(new MockMultipartFile("file", "capture.bin", "application/octet-stream", content));
        builder.param("id", id);
        builder.param("logSheetId", String.valueOf(f.sheetId()));
        builder.param("assetId", String.valueOf(assetId));
        builder.param("fieldKey", fieldKey);
        builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken());
        return builder;
    }

    /** A byte array that a magic-byte sniffer reads as a PNG of the requested length. */
    private static byte[] png(int length) {
        byte[] out = new byte[length];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, out, 0, Math.min(magic.length, length));
        return out;
    }

    private static byte[] jpeg(int length) {
        byte[] out = new byte[length];
        byte[] magic = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        System.arraycopy(magic, 0, out, 0, Math.min(magic.length, length));
        return out;
    }

    private static byte[] webmAudio(int length) {
        byte[] out = new byte[length];
        byte[] magic = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
        System.arraycopy(magic, 0, out, 0, Math.min(magic.length, length));
        return out;
    }

    private static byte[] oggAudio(int length) {
        byte[] out = new byte[length];
        System.arraycopy("OggS".getBytes(StandardCharsets.ISO_8859_1), 0, out, 0, 4);
        return out;
    }

    private void setLimits(int images, int audios, int videos, int audioSec, int videoSec) {
        appSettingsService.saveAttachmentLimits(new AppSettingsService.AttachmentLimits(
                images, audios, videos, audioSec, videoSec));
    }

    private String tokenForOperatorInAnotherUnit() throws Exception {
        long now = System.nanoTime();
        OperationalUnit other = new OperationalUnit();
        other.setCode("ATT-OTHER-" + now);
        other.setName("Other Unit");
        other.setCreatedAt(System.currentTimeMillis());
        other.setUpdatedAt(System.currentTimeMillis());
        other = operationalUnitRepository.save(other);

        User outsider = createOperator(other.getId(), "att-outsider-" + now, "op12345");
        return loginToken(outsider.getUsername(), "op12345");
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Attachment Operator");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.save(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.save(link);
        return user;
    }

    /** A generated, assigned sheet with one asset whose class has image, audio and number fields. */
    private Fixture seed() throws Exception {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("ATT-BU-" + nano);
        unit.setName("Attachment Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.save(unit);

        Location location = new Location();
        location.setCode("ATT-LOC-" + nano);
        location.setName("Attachment Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("ATT-SF-" + nano);
        subFunction.setName("Attachment Sub");
        subFunction.setTag("NFC-ATT-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Attachment Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);

        saveField(assetClass.getId(), "temp", "Temperature", "number", 1);
        saveField(assetClass.getId(), "pump_photo", "Pump Photo", "image", 2);
        saveField(assetClass.getId(), "pump_photo_2", "Pump Photo 2", "image", 4);
        saveField(assetClass.getId(), "pump_clip", "Pump Clip", "video", 5);
        saveField(assetClass.getId(), "pump_sound", "Pump Sound", "audio", 3);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("ATT-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Attachment Template " + nano);
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setClassId(assetClass.getId());
        template.setOperationalUnitId(unit.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.save(template);

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);

        User operator = createOperator(unit.getId(), "att-op-" + nano, "op12345");
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.save(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        return new Fixture(sheet.getId(), entry.getAssetId(), loginToken(operator.getUsername(), "op12345"));
    }

    private void saveField(Long classId, String key, String label, String dataType, int order) {
        long now = System.currentTimeMillis();
        FieldDefinition def = new FieldDefinition();
        def.setClassId(classId);
        def.setKey(key);
        def.setLabel(label);
        def.setDataType(dataType);
        def.setRequired(false);
        def.setOrder(order);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.save(def);
    }

    private record Fixture(Long sheetId, Long assetId, String operatorToken) {}
}
