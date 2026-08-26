package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetEntryRevision;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRevisionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sheet's page lists every parameter of an asset, not only the ones somebody answered.
 *
 * <p><b>The gap.</b> {@code form_data} holds only fields that carry a real answer — that is what
 * makes {@code max_severity IS NOT NULL} an exact has-a-reading test, and what stops one
 * supervisor save writing {@code {"Bar": "", "Status": ""}} onto forty entries. But the detail
 * page rendered its rows straight off those keys, so an asset with three of seven parameters
 * recorded showed three rows and the four the operator skipped were <b>indistinguishable from
 * parameters the class does not define</b> — which is the one question a supervisor opens the
 * page to answer. An asset with nothing at all rendered a bare dash.
 *
 * <p>These assert against the rendered HTML rather than the helper, because that is where the
 * defect actually lived: {@code FormDataViewHelper} was doing exactly what it was asked, and the
 * fragment was asking the wrong question. A unit test on the helper alone would have passed
 * throughout.
 */
@Transactional
class LogSheetDetailParametersIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetEntryRevisionRepository revisionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void listsTheParametersNobodyAnsweredAlongsideTheOnesTheyDid() throws Exception {
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        assertThat(html).contains("دما");
        // The two the operator skipped. Before this they were absent from the page entirely.
        assertThat(html).contains("فشار");
        assertThat(html).contains("توضیح");
        assertThat(html).contains("ثبت نشده");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void marksEachParameterRowSoTheFilterCanHideTheEmptyOnes() throws Exception {
        // The «فقط دارای مقدار» toggle is client-side: every row is rendered and tagged, and a
        // class on the container hides the empty ones. Without the tag there is nothing to hide.
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        assertThat(html).contains("data-param-state=\"filled\"");
        assertThat(html).contains("data-param-state=\"empty\"");
        assertThat(html).contains("data-detail-param-filter=\"all\"");
        assertThat(html).contains("data-detail-param-filter=\"filled\"");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void showsWhatAnUntouchedAssetWasSupposedToCarry() throws Exception {
        // Previously a bare «—», which says nothing about what the round asked for.
        Long sheetId = seed(Map.of());

        String html = render(sheetId);

        assertThat(html).contains("دما");
        assertThat(html).contains("فشار");
        assertThat(html).contains("توضیح");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void stillRendersAReadingWhoseFieldTheClassNoLongerDefines() throws Exception {
        // A sheet frozen before a field was retired can hold a value with no definition left.
        // Enumerating the schema must not become a way of hiding it.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("temp", "42");
        data.put("retired_param", "7");
        Long sheetId = seed(data);

        String html = render(sheetId);

        assertThat(html).contains("retired_param");
        assertThat(html).contains("7");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void doesNotFlagAnUnansweredNumberAsOutOfRange() throws Exception {
        // «فشار» has a danger band starting above zero. Evaluating an absent reading against it
        // would paint every unfilled row red, which is worse than not showing the row at all.
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        assertThat(html).doesNotContain("خارج از بازه خطر است");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String render(Long sheetId) throws Exception {
        return mockMvc.perform(get("/log-sheets/{id}", sheetId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** A generated one-asset sheet whose class defines three parameters, filled as given. */
    private Long seed(Map<String, Object> formData) {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("PRM-BU-" + nano);
        unit.setName("Parameter Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("PRM-LOC-" + nano);
        location.setName("Parameter Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("PRM-SF-" + nano);
        subFunction.setName("Parameter Sub");
        subFunction.setTag("NFC-PRM-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Parameter Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        saveField(assetClass.getId(), "temp", "دما", "number", 1, false);
        saveField(assetClass.getId(), "bar", "فشار", "number", 2, true);
        saveField(assetClass.getId(), "note", "توضیح", "text", 3, false);
        // A media field, so a revision can carry an attachment id the panel has to describe.
        saveField(assetClass.getId(), "voice", "یادداشت صوتی", "audio", 4, false);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("PRM-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Parameter Template " + nano);
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
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedAt(now);
        logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        // Written straight in, exactly as `storableFormData` leaves it: only answered keys.
        entry.setFormData(formData.isEmpty() ? Map.of() : new LinkedHashMap<>(formData));
        entry.setMaxSeverity(formData.isEmpty() ? null : "OK");
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        logSheetEntryRepository.saveAndFlush(entry);

        return sheet.getId();
    }

    // -- payload weight ---------------------------------------------------------

    /**
     * No explanatory comment may be copied into the response once per row.
     *
     * <p>Measured on a real 23-asset sheet: <b>301 KB of a 639 KB page was HTML comments</b>, and
     * 247 KB of that was one paragraph repeated 329 times — the note about bidi and {@code <bdi>}
     * that sits inside the per-row loop in {@code fragments/form-data-display.html}. Thymeleaf
     * copies {@code <!-- … -->} into the output verbatim, so a comment inside a {@code th:each}
     * is emitted once per iteration.
     *
     * <p>The fix is not to delete the explanations — they are the reason the markup is
     * maintainable — but to write them as <b>parser-level</b> comments, {@code <!--/* … *\/-->},
     * which Thymeleaf removes when it parses the template. The text stays exactly where the next
     * reader needs it and never reaches the browser.
     *
     * <p>The threshold is deliberately loose. This is not a byte budget; it is a tripwire for the
     * specific mistake of putting a paragraph inside a loop, which is invisible in review and
     * only ever shows up as a page that is mysteriously large.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void noCommentIsCopiedIntoTheResponseOncePerRow() throws Exception {
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<!--.*?-->", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        while (m.find()) {
            counts.merge(m.group(), 1, Integer::sum);
        }

        // This fixture renders ONE asset with four parameters. A comment appearing more than a
        // handful of times can only be inside a loop, and on a real sheet that is hundreds.
        assertThat(counts).allSatisfy((comment, times) ->
                assertThat(times)
                        .as("comment repeated %d times — make it a parser-level comment "
                                + "<!--/* … */--> so Thymeleaf strips it: %s",
                                times, comment.substring(0, Math.min(90, comment.length())))
                        .isLessThanOrEqualTo(3));
    }

    // -- the revision panel, and an attachment a correction removed -------------

    /**
     * A photo a correction deleted, described rather than linked.
     *
     * <p>The panel resolves a revision's attachment ids against the sheet's <b>live</b> rows, and
     * a correction that removed a photo removed those rows too — so it could only ever say «فایل
     * پیوست در دسترس نیست», which reads exactly like storage having lost the file and says nothing
     * about what the deleted evidence was. `attachment_snapshot` (V6) is the only surviving
     * description, and `tableRevision` is the only fragment that reads it.
     *
     * <p>This is the wiring test. `FormDataViewHelperTest` proves the merge and
     * `LogSheetEntryRevisionIntegrationTest` proves the capture; neither would notice the detail
     * page still calling `tableAll`, which is what it did before and what would silently lose all
     * of it.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void aRevisionDescribesAnAttachmentTheCorrectionDeleted() throws Exception {
        Long sheetId = seed(Map.of("temp", "42"));
        seedRevisionWithDeletedAttachment(sheetId);

        String html = render(sheetId);

        assertThat(html).contains("entry-revisions");
        assertThat(html).contains("att-removed");
        assertThat(html).contains("حذف‌شده");
        // What it was: 40 KB, twenty seconds. That is the part «در دسترس نیست» could never say.
        assertThat(html).contains("40 KB · 0:20");
        // And it is NOT offered as a link or an image — the bytes are gone, and a thumbnail
        // pointing at a 404 is worse than a description.
        assertThat(html).doesNotContain("data-att-url=\"/log-sheets/" + sheetId + "/attachments/gone-");
    }

    /**
     * A revision whose snapshot is null — written before V6, or by a build that did not capture
     * one. It must degrade to the old «در دسترس نیست» rather than claim a deliberate deletion,
     * because nothing here knows that happened.
     */
    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void aRevisionWithoutASnapshotStillRenders() throws Exception {
        Long sheetId = seed(Map.of("temp", "42"));
        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheetId).get(0);

        LogSheetEntryRevision revision = new LogSheetEntryRevision();
        revision.setLogSheetEntryId(entry.getId());
        revision.setLogSheetId(sheetId);
        revision.setAssetId(entry.getAssetId());
        revision.setFormData(Map.of("temp", "10"));
        revision.setSupersededAt(System.currentTimeMillis());
        revisionRepository.saveAndFlush(revision);

        String html = render(sheetId);

        assertThat(html).contains("entry-revisions");
        // The superseded reading is there...
        assertThat(html).contains("مقادیر پیشین");
        // ...and nothing claims an attachment was removed.
        assertThat(html).doesNotContain("att-removed");
    }

    private void seedRevisionWithDeletedAttachment(Long sheetId) {
        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheetId).get(0);
        String attachmentId = "gone-" + System.nanoTime();

        LogSheetEntryRevision revision = new LogSheetEntryRevision();
        revision.setLogSheetEntryId(entry.getId());
        revision.setLogSheetId(sheetId);
        revision.setAssetId(entry.getAssetId());
        revision.setFormData(Map.of("voice", java.util.List.of(attachmentId)));
        revision.setSupersededAt(System.currentTimeMillis());
        // No `attachments` row for this id anywhere: that is the whole point — the correction
        // deleted it, and the snapshot is all that is left.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kind", "AUDIO");
        meta.put("mimeType", "audio/webm");
        meta.put("sizeBytes", 40_960L);
        meta.put("durationMs", 20_000L);
        revision.setAttachmentSnapshot(Map.of(attachmentId, meta));
        revisionRepository.saveAndFlush(revision);
    }

    private void saveField(Long classId, String key, String label, String dataType, int order,
                           boolean withDangerBand) {
        long now = System.currentTimeMillis();
        FieldDefinition def = new FieldDefinition();
        def.setClassId(classId);
        def.setKey(key);
        def.setLabel(label);
        def.setDataType(dataType);
        def.setRequired(false);
        def.setOrder(order);
        if (withDangerBand) {
            def.setValidation(com.hnp.backendofflinefirst.domain.FieldValidationSupport
                    .build("number", null, 20.0, 80.0, 10.0, 90.0));
        }
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);
    }
}
