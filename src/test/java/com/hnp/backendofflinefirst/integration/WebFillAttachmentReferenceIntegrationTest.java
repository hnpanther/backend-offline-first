package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.*;
import com.hnp.backendofflinefirst.repository.*;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AttachmentReferences;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A file and the reading that names it are written together.
 *
 * <h2>The defect these were written against</h2>
 *
 * <p>On the web fill page a file uploads the moment it is chosen — its own endpoint, its own
 * transaction — while {@code form_data} was rewritten only when the dialog's save button was
 * pressed. One screen could therefore say two different things about one photograph:
 *
 * <ul>
 *   <li><b>Upload, then close the dialog.</b> Row and file exist, nothing names them. The dialog
 *       showed the photograph (it lists the {@code attachments} table); the card underneath said
 *       «ثبت نشده» (it reads {@code form_data}). Nothing collected it either —
 *       {@code AttachmentSweepService} removes files with no row, and here the row was the
 *       unwanted thing — so every abandoned upload was kept forever.</li>
 *   <li><b>Delete, then close the dialog.</b> The file was gone and {@code form_data} still named
 *       it, and «تأیید نهایی» would seal the sheet on that dead reference.</li>
 * </ul>
 *
 * <p>Most of this predated the dialog: the old full-page form also uploaded immediately and wrote
 * {@code form_data} only on submit. What the dialog added was a button that <em>read</em> as
 * though it undid the upload.
 *
 * <h2>The rule these pin: adopt, never drop</h2>
 *
 * <p>The obvious fix — rebuild the id list from the rows, the way the PWA does on the device — is
 * wrong on the server, and {@link #anIdWithNoRowYetIsLeftAloneBecauseItsFileMayStillBeUploading()}
 * is the case that says why. A tablet pushes the sheet <b>first</b> and uploads its attachments
 * afterwards, so between those two steps the server legitimately holds a reading naming ids it has
 * no rows for. Rebuilding would delete exactly those.
 *
 * <p>So: an id is added when a row exists, and removed only by the deletion of that row.
 */
class WebFillAttachmentReferenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetEntryRevisionRepository revisionRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired com.hnp.backendofflinefirst.service.AttachmentService attachmentService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static final String FILL = "CAP:LOGSHEET_COMPLETE_WEB_ANY";
    private static final String COMPLETE = "POST:/log-sheets/{id}/complete";
    private static final String VIEW = "GET:/log-sheets/{id}";
    private static final String FIELD = "photo";

    // ── upload names the file on the reading, with no save ───────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void uploadingAFileMakesTheReadingNameItWithoutAnySave() throws Exception {
        Fixture f = seed();

        String id = upload(f, f.firstAssetId());

        assertThat(idsOn(f.firstEntryId()))
                .as("the dialog was never saved; the reference must exist anyway")
                .containsExactly(id);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE, VIEW})
    void theSheetsOwnPageShowsTheFileInsteadOfAnUnansweredField() throws Exception {
        // The half a reader sees, and the sharpest statement of the defect: the detail page draws
        // attachments **only** through `form-data-display`, from the reading itself. A photograph
        // the reading does not name is not merely unlabelled there — it does not exist. Before
        // this, an upload the operator never followed with a save was invisible on the record
        // while sitting in the table and on the disk.
        Fixture f = seed();
        String id = upload(f, f.firstAssetId());

        mockMvc.perform(get("/log-sheets/{id}", f.sheetId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(id)));
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aSecondUploadIsAppendedRatherThanReplacingTheFirst() throws Exception {
        Fixture f = seed();

        String first = upload(f, f.firstAssetId());
        String second = upload(f, f.firstAssetId());

        assertThat(idsOn(f.firstEntryId())).containsExactly(first, second);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void reUploadingTheSameIdRepairsAReferenceThatWentMissing() throws Exception {
        // The idempotent branch — reached by a tablet retrying an upload whose response never
        // arrived, and driven here through the service because only the mobile API lets a client
        // choose the id. It is also the one route by which a sheet carrying an orphan from before
        // this existed can be put right without a migration.
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        attachmentService.uploadFromWebFill(id, f.sheetId(), f.firstAssetId(), FIELD,
                new ByteArrayInputStream(png(64)), null, null, null);
        clearFormData(f.firstEntryId());

        attachmentService.uploadFromWebFill(id, f.sheetId(), f.firstAssetId(), FIELD,
                new ByteArrayInputStream(png(64)), null, null, null);

        assertThat(idsOn(f.firstEntryId())).containsExactly(id);
    }

    // ── deletion removes it in the same transaction ─────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void deletingAFileStopsTheReadingNamingIt() throws Exception {
        Fixture f = seed();
        String id = upload(f, f.firstAssetId());

        mockMvc.perform(post("/log-sheets/{id}/attachments/{attachmentId}/delete", f.sheetId(), id)
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(entry(f.firstEntryId()).getFormData())
                .as("the last file went, so the key goes — not an empty reference, which reads as "
                        + "an answer to anything scanning form_data")
                .doesNotContainKey(FIELD);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void deletingOneOfTwoLeavesTheOther() throws Exception {
        Fixture f = seed();
        String first = upload(f, f.firstAssetId());
        String second = upload(f, f.firstAssetId());

        mockMvc.perform(post("/log-sheets/{id}/attachments/{attachmentId}/delete", f.sheetId(), first)
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(idsOn(f.firstEntryId())).containsExactly(second);
    }

    // ── the rule that makes this safe on a server rather than a device ──────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void anIdWithNoRowYetIsLeftAloneBecauseItsFileMayStillBeUploading() throws Exception {
        // **The case that decides the whole design.** A tablet pushes the sheet first and uploads
        // its attachments afterwards — the upload queue is gated on the sheet having a server id —
        // so a reading naming an id the server has no row for is normal, not corrupt. Rebuilding
        // the list from the rows would delete that reference, and the photograph would arrive with
        // nothing pointing at it.
        // A real UUID, because ids are canonicalised wherever they are compared and a
        // hand-written string is refused long before this rule is reached.
        String queued = UUID.randomUUID().toString();
        Fixture f = seed();
        setFormData(f.firstEntryId(), Map.of(FIELD, AttachmentReferences.toValue(List.of(queued))));

        String uploaded = upload(f, f.firstAssetId());

        assertThat(idsOn(f.firstEntryId()))
                .as("the queued id must survive an unrelated upload to the same field")
                .containsExactly(queued, uploaded);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void deletingOneFileDoesNotDisturbAQueuedIdBesideIt() throws Exception {
        String queued = UUID.randomUUID().toString();
        Fixture f = seed();
        String uploaded = upload(f, f.firstAssetId());
        setFormData(f.firstEntryId(), Map.of(FIELD,
                AttachmentReferences.toValue(List.of(queued, uploaded))));

        mockMvc.perform(post("/log-sheets/{id}/attachments/{attachmentId}/delete", f.sheetId(), uploaded)
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(idsOn(f.firstEntryId())).containsExactly(queued);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void twoUploadsLandingTogetherBothEndUpNamedByTheReading() throws Exception {
        // Reconciliation is a read-modify-write of `form_data`. Under READ COMMITTED the second
        // transaction cannot see the first's uncommitted row, so without a lock it rebuilds the
        // id list without it and its write drops the other's reference — one file kept, nothing
        // naming it, which is the exact state this whole feature exists to prevent.
        //
        // The page cannot produce this today: its input takes one file and the button is disabled
        // while the upload is in flight. But that is a property of the markup, not of the data,
        // and two tabs or a retried request are enough. The entry row is locked instead.
        Fixture f = seed();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch together = new CountDownLatch(2);
        SecurityContext ctx = SecurityContextHolder.getContext();

        try {
            List<Future<String>> uploads = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                uploads.add(pool.submit(() -> {
                    // The worker threads need the caller's identity: access is re-checked inside.
                    SecurityContextHolder.setContext(ctx);
                    String id = UUID.randomUUID().toString();
                    together.countDown();
                    together.await(5, TimeUnit.SECONDS);
                    attachmentService.uploadFromWebFill(id, f.sheetId(), f.firstAssetId(), FIELD,
                            new ByteArrayInputStream(png(64)), null, null, null);
                    return id;
                }));
            }
            List<String> ids = new ArrayList<>();
            for (Future<String> upload : uploads) {
                ids.add(upload.get(30, TimeUnit.SECONDS));
            }

            assertThat(attachmentRepository.findAll().stream()
                    .filter(a -> f.sheetId().equals(a.getLogSheetId()))
                    .map(Attachment::getId))
                    .as("both files were stored")
                    .containsAll(ids);
            assertThat(idsOn(f.firstEntryId()))
                    .as("and the reading names both, whichever order they committed in")
                    .containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── what it must not touch ──────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void attachingAFileWritesNoRevision() throws Exception {
        // A revision means "this replaced a reading". Attaching evidence is not that, and one row
        // per photograph would bury the corrections «مقادیر پیشین» exists to show.
        Fixture f = seed();
        save(f.sheetId(), f.firstEntryId(), "temp", "42");

        String id = upload(f, f.firstAssetId());
        mockMvc.perform(post("/log-sheets/{id}/attachments/{attachmentId}/delete", f.sheetId(), id)
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(revisionRepository.findByLogSheetEntryIdOrderByIdAsc(f.firstEntryId()))
                .as("neither the upload nor the deletion is a superseded reading")
                .isEmpty();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void attachingAFileDoesNotReassignWhoTookTheReading() throws Exception {
        // AGENTS.md gotcha #20 by another route. The uploader is on the attachment row's own
        // created_by_user_id; entry_source and filled_by_user_id answer a different question.
        Fixture f = seed();
        save(f.sheetId(), f.firstEntryId(), "temp", "42");
        LogSheetEntry before = entry(f.firstEntryId());

        upload(f, f.firstAssetId());

        LogSheetEntry after = entry(f.firstEntryId());
        assertThat(after.getEntrySource()).isEqualTo(before.getEntrySource());
        assertThat(after.getFilledByUserId()).isEqualTo(before.getFilledByUserId());
        assertThat(after.getFormData()).containsEntry("temp", "42");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void anUploadThatChangesNothingDoesNotRestampTheReading() throws Exception {
        // Writing on every call would churn `updated_at`, and `updated_at` is half of the pair a
        // tablet echoes back as its base — see wouldBlankUnseenAnswer. Only a real change writes.
        Fixture f = seed();
        String id = UUID.randomUUID().toString();
        attachmentService.uploadFromWebFill(id, f.sheetId(), f.firstAssetId(), FIELD,
                new ByteArrayInputStream(png(64)), null, null, null);
        Long stamped = entry(f.firstEntryId()).getUpdatedAt();

        attachmentService.uploadFromWebFill(id, f.sheetId(), f.firstAssetId(), FIELD,
                new ByteArrayInputStream(png(64)), null, null, null);

        assertThat(entry(f.firstEntryId()).getUpdatedAt()).isEqualTo(stamped);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void aRealChangeDoesRestampTheReading() throws Exception {
        // The counterweight, and the reason the stamp is not skipped altogether: leaving it would
        // let a tablet holding the older base submit over the new reference with nothing noticing.
        Fixture f = seed();
        // Twice: the first fill only stamps `created_at`, so one save leaves `updated_at` null and
        // there would be nothing to compare against.
        save(f.sheetId(), f.firstEntryId(), "temp", "41");
        save(f.sheetId(), f.firstEntryId(), "temp", "42");
        Long before = entry(f.firstEntryId()).getUpdatedAt();
        assertThat(before).isNotNull();
        Thread.sleep(5);

        upload(f, f.firstAssetId());

        assertThat(entry(f.firstEntryId()).getUpdatedAt()).isGreaterThan(before);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void anApprovedSheetKeepsTheFileAndIsNotRewritten() throws Exception {
        // Uploads stay allowed on an APPROVED sheet on purpose — a tablet offline at sign-off
        // still holds real evidence. Writing into the signed-off reading is a different act, and
        // not one an upload may perform silently. The row is kept; the record is not touched.
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setStatus(LogSheetStatus.APPROVED);
        logSheetRepository.saveAndFlush(sheet);

        String id = upload(f, f.firstAssetId());

        assertThat(attachmentRepository.findById(id)).as("the file is kept").isPresent();
        assertThat(entry(f.firstEntryId()).getFormData()).doesNotContainKey(FIELD);
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void uploadingToOneAssetLeavesTheOtherAlone() throws Exception {
        Fixture f = seed();
        LogSheetEntry otherBefore = entry(f.secondEntryId());

        upload(f, f.firstAssetId());

        LogSheetEntry otherAfter = entry(f.secondEntryId());
        assertThat(otherAfter.getFormData()).isNullOrEmpty();
        assertThat(otherAfter.getUpdatedAt()).isEqualTo(otherBefore.getUpdatedAt());
    }

    // ── it must not fight the dialog's own save ─────────────────────────────────────────────

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {FILL, COMPLETE})
    void savingTheDialogAfterAnUploadKeepsTheFile() throws Exception {
        // The widget adds a hidden input for each uploaded file, so a save posts the same ids this
        // reference already holds. Asserted because the two writers now overlap: if the dialog's
        // save did not carry the id, it would silently undo the upload's own bookkeeping.
        Fixture f = seed();
        String id = upload(f, f.firstAssetId());

        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", f.sheetId(), f.firstEntryId())
                        .param("fd_present_" + f.firstEntryId(), "1")
                        .param("fd_" + f.firstEntryId() + "_temp", "42")
                        .param("fd_" + f.firstEntryId() + "_" + FIELD, id)
                        .with(csrf()))
                .andExpect(status().isOk());

        assertThat(idsOn(f.firstEntryId())).containsExactly(id);
        assertThat(entry(f.firstEntryId()).getFormData()).containsEntry("temp", "42");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private List<String> idsOn(Long entryId) {
        Map<String, Object> formData = entry(entryId).getFormData();
        return AttachmentReferences.idsOf(formData == null ? null : formData.get(FIELD));
    }

    /**
      * Uploads through the page's own endpoint and returns the id it minted.
      *
      * <p>The id is the server's to choose here — the web endpoint generates one, unlike the
      * mobile API where the tablet supplies it — so it is read back out of the response rather
      * than passed in.
      */
    private String upload(Fixture f, Long assetId) throws Exception {
        String body = mockMvc.perform(uploadRequest(f, assetId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile("\"id\"\s*:\s*\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the upload response must name the stored file: %s", body).isTrue();
        return matcher.group(1);
    }

    private RequestBuilder uploadRequest(Fixture f, Long assetId) {
        return multipart("/log-sheets/{id}/attachments", f.sheetId())
                .file(new MockMultipartFile("file", "shot.png", MediaType.IMAGE_PNG_VALUE, png(64)))
                .param("assetId", String.valueOf(assetId))
                .param("fieldKey", FIELD)
                .with(csrf());
    }

    private void save(Long sheetId, Long entryId, String key, String value) throws Exception {
        mockMvc.perform(post("/log-sheets/{id}/entries/{entryId}/draft", sheetId, entryId)
                        .param("fd_present_" + entryId, "1")
                        .param("fd_" + entryId + "_" + key, value)
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private void setFormData(Long entryId, Map<String, Object> formData) {
        LogSheetEntry entry = entry(entryId);
        entry.setFormData(formData);
        logSheetEntryRepository.saveAndFlush(entry);
    }

    private void clearFormData(Long entryId) {
        setFormData(entryId, Map.of());
    }

    private LogSheetEntry entry(Long entryId) {
        return logSheetEntryRepository.findById(entryId).orElseThrow();
    }

    /** Bytes whose magic number says PNG — the server never trusts the declared type. */
    private static byte[] png(int length) {
        byte[] out = new byte[length];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, out, 0, magic.length);
        return out;
    }

    private record Fixture(Long sheetId, Long firstEntryId, Long secondEntryId,
                           Long firstAssetId, Long secondAssetId) {}

    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("ATT-BU-" + nano);
        unit.setName("Attachment Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("ATT-LOC-" + nano);
        location.setName("Attachment Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Attachment Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        field(assetClass.getId(), FIELD, "Photo", "image", 1, now);
        field(assetClass.getId(), "temp", "Temperature", "text", 2, now);

        // One sub-function per asset: `ux_asset_entries_active_sub_function` allows a single
        // active asset per position. See docs/hierarchy.md.
        for (int i = 1; i <= 2; i++) {
            SubFunction subFunction = new SubFunction();
            subFunction.setCode("ATT-SF" + i + "-" + nano);
            subFunction.setName("Attachment Sub " + i);
            subFunction.setTag("NFC-ATT" + i + "-" + nano);
            subFunction.setCreatedAt(now);
            subFunction.setUpdatedAt(now);
            hierarchyService.applySubFunctionParent(
                    subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
            subFunction = hierarchyService.saveSubFunction(subFunction);

            AssetEntry asset = new AssetEntry();
            asset.setAssetCode("ATT-A" + i + "-" + nano);
            asset.setAssetName("Pump " + i);
            asset.setClassId(assetClass.getId());
            asset.setSubFunctionId(subFunction.getId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            assetEntryRepository.saveAndFlush(asset);
        }

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
        template = templateRepository.saveAndFlush(template);

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);

        User operator = operator(unit.getId(), nano);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(sheet);

        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId()).stream()
                .sorted(Comparator.comparing(LogSheetEntry::getId))
                .toList();
        assertThat(entries).as("the fixture needs two assets on the sheet").hasSize(2);
        return new Fixture(sheet.getId(), entries.get(0).getId(), entries.get(1).getId(),
                entries.get(0).getAssetId(), entries.get(1).getAssetId());
    }

    private void field(Long classId, String key, String label, String dataType, int order, long now) {
        FieldDefinition def = new FieldDefinition();
        def.setClassId(classId);
        def.setKey(key);
        def.setLabel(label);
        def.setDataType(dataType);
        def.setRequired(false);
        def.setOrder(order);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);
    }

    private User operator(Long unitId, long nano) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("att-op-" + nano);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Attachment Operator");
        user.setPasswordHash("{noop}x");
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(userRole);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(user.getId());
        unitOperatorRepository.saveAndFlush(link);
        return user;
    }
}
