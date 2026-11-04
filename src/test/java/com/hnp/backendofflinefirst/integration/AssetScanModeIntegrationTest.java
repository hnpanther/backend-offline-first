package com.hnp.backendofflinefirst.integration;


import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.dto.LogSheetDto;
import com.hnp.backendofflinefirst.dto.LogSheetEntryDto;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
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
import com.hnp.backendofflinefirst.service.CustomLogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetTemplateService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How a round says its assets must be identified — NFC, QR, either, or nothing.
 *
 * <p>Until V11 every round was an NFC round, because a tag was the only thing a tablet could
 * read. A plant has positions a chip cannot survive and positions nobody will ever label, so the
 * requirement became a property of the round.
 *
 * <p><b>The value a QR code carries is {@code asset_entries.nfc_tag_id}</b> — the identifier the
 * tag already holds. No second column and no second identity to keep in step, so a position
 * labelled with both answers with one value and every query, report and fault report that names
 * an asset by its tag id keeps working untouched. Nothing in this class tests a QR *payload*
 * format for that reason: there is nothing new to parse.
 *
 * <p>What is worth testing is what the server actually owns:
 *
 * <ul>
 *   <li>the mode is <b>frozen</b> onto the round, so a template changed later cannot change the
 *       rules under a tablet that is already walking it;</li>
 *   <li>a round with no equipment is forced to {@code NONE} whatever the form said, because a
 *       requirement to scan on a round with nothing to scan can never be met;</li>
 *   <li>a QR reading is <b>labelled</b> as one and survives being corrected — the correction is
 *       where V4's CHECK would have refused it, days later, in front of a supervisor who did
 *       nothing unusual.</li>
 * </ul>
 *
 * <p>What is deliberately <b>not</b> tested, because it does not exist: a server-side refusal of
 * the wrong kind of scan. The server never witnessed any scan and has never claimed to; a client
 * that lies about scanning can equally lie about which kind. {@code scan_mode} is an instruction
 * to the device, the same standing {@code nfcStrictSerialMatch} has always had.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "POST:/log-sheet-templates",
        "POST:/log-sheets/custom"})
class AssetScanModeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LogSheetTemplateService templateService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired CustomLogSheetService customLogSheetService;
    @Autowired LogSheetService logSheetService;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetEntryRevisionRepository entryRevisionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired com.hnp.backendofflinefirst.service.LogSheetBundleService bundleService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // =========================================================== the default stays the default

    @Test
    void aTemplateSavedWithoutTheFieldIsAnNfcTemplate() {
        Fixture f = seed();
        LogSheetTemplate form = templateForm(f);
        form.setScanMode(null);

        LogSheetTemplate saved = templateService.create(form);

        // Every template that existed before V11 is an NFC template and must stay one. A null
        // here is an older client or an older test, not a request to change anything.
        assertThat(saved.getScanMode()).isEqualTo(AssetScanMode.NFC);
    }

    @Test
    void aRoundRaisedFromAnUntouchedTemplateIsAnNfcRound() {
        Fixture f = seed();
        LogSheet sheet = generate(templateService.create(templateForm(f)));

        assertThat(sheet.getScanMode()).isEqualTo(AssetScanMode.NFC);
    }

    // =========================================================== the choice, and the freeze

    @Test
    void theRoundIsRaisedWithTheModeItsTemplateCarried() {
        Fixture f = seed();
        LogSheetTemplate form = templateForm(f);
        form.setScanMode(AssetScanMode.QR);

        LogSheet sheet = generate(templateService.create(form));

        assertThat(sheet.getScanMode()).isEqualTo(AssetScanMode.QR);
    }

    @Test
    void changingTheTemplateDoesNotChangeARoundAlreadyRaised() {
        Fixture f = seed();
        LogSheetTemplate template = templateService.create(templateForm(f));
        LogSheet sheet = generate(template);
        assertThat(sheet.getScanMode()).isEqualTo(AssetScanMode.NFC);

        LogSheetTemplate edit = templateForm(f);
        edit.setName(template.getName());
        edit.setScanMode(AssetScanMode.QR);
        templateService.update(template.getId(), edit);

        // THE WHOLE POINT. A supervisor switching the template to QR on Tuesday is describing
        // Wednesday's rounds — not the one an operator is walking right now on a tablet that
        // downloaded its bundle on Monday and has been offline since.
        assertThat(logSheetRepository.findById(sheet.getId()).orElseThrow().getScanMode())
                .isEqualTo(AssetScanMode.NFC);
        // And the next round does carry the new choice.
        assertThat(generate(templateRepository.findById(template.getId()).orElseThrow())
                .getScanMode()).isEqualTo(AssetScanMode.QR);
    }

    @Test
    void eitherKindOfScanIsItsOwnMode() {
        Fixture f = seed();
        LogSheetTemplate form = templateForm(f);
        form.setScanMode(AssetScanMode.NFC_OR_QR);

        assertThat(generate(templateService.create(form)).getScanMode())
                .isEqualTo(AssetScanMode.NFC_OR_QR);
        assertThat(AssetScanMode.NFC_OR_QR.allowsNfc()).isTrue();
        assertThat(AssetScanMode.NFC_OR_QR.allowsQr()).isTrue();
    }

    // =========================================================== nothing to scan

    @Test
    void anAssetLessTemplateIsForcedToNoneWhateverTheFormSaid() {
        Fixture f = seed();
        LogSheetTemplate form = templateForm(f);
        form.setAssetSelectionMode(AssetSelectionMode.NONE);
        form.setScopeType(null);
        form.setScopeId(null);
        form.setClassId(null);
        // The two selects sit side by side and nothing stops a supervisor leaving «NFC» chosen
        // while switching to «بدون دارایی». Of the two quiet resolutions only one is honest:
        // there is nothing to scan, so a stored requirement to scan can never be met and the
        // tablet would offer a button for a round with no asset behind any tag.
        form.setScanMode(AssetScanMode.NFC);

        LogSheetTemplate saved = templateService.create(form);

        assertThat(saved.getScanMode()).isEqualTo(AssetScanMode.NONE);
        assertThat(generate(saved).getScanMode()).isEqualTo(AssetScanMode.NONE);
    }

    @Test
    void aQuestionsOnlyCustomSheetIsForcedToNoneToo() {
        Fixture f = seed();
        long now = System.currentTimeMillis();

        LogSheet sheet = customLogSheetService.createCustom(
                f.unitId(), "بازدید محوطه " + System.nanoTime(), now + 3_600_000L,
                List.of(), true, AssetScanMode.NFC, 1L, now);

        assertThat(sheet.getScanMode()).isEqualTo(AssetScanMode.NONE);
    }

    @Test
    void aCustomSheetWithAssetsKeepsTheModeItWasGiven() {
        Fixture f = seed();
        long now = System.currentTimeMillis();

        LogSheet sheet = customLogSheetService.createCustom(
                f.unitId(), "دور QR " + System.nanoTime(), now + 3_600_000L,
                List.of(f.assetId()), false, AssetScanMode.QR, 1L, now);

        assertThat(sheet.getScanMode()).isEqualTo(AssetScanMode.QR);
    }

    @Test
    void aCustomSheetGivenNoModeIsAnNfcRound() {
        Fixture f = seed();
        long now = System.currentTimeMillis();

        LogSheet sheet = customLogSheetService.createCustom(
                f.unitId(), "دور پیش‌فرض " + System.nanoTime(), now + 3_600_000L,
                List.of(f.assetId()), false, null, 1L, now);

        assertThat(sheet.getScanMode()).isEqualTo(AssetScanMode.NFC);
    }

    @Test
    void theCustomSheetFormPassesTheChoiceThrough() throws Exception {
        Fixture f = seed();

        mockMvc.perform(post("/log-sheets/custom").with(csrf())
                        .param("name", "دور فرم " + System.nanoTime())
                        .param("unitId", String.valueOf(f.unitId()))
                        .param("assetIds", String.valueOf(f.assetId()))
                        .param("scanMode", "NFC_OR_QR")
                        .param("dueAt", futureInput()))
                .andExpect(status().is3xxRedirection());

        assertThat(logSheetRepository.findAll().stream()
                .filter(s -> s.getTemplateId() == null)
                .map(LogSheet::getScanMode))
                .contains(AssetScanMode.NFC_OR_QR);
    }

    @Test
    void theModeReachesTheDeviceInsideTheBundleWithNoContractChange() {
        Fixture f = seed();
        LogSheetTemplate form = templateForm(f);
        form.setScanMode(AssetScanMode.NFC_OR_QR);
        LogSheet sheet = claimed(generate(templateService.create(form)));

        // `LogSheetBundleDto.sheet` serialises the whole entity, so a new column travels with no
        // DTO change — the same piece of luck the round-level questions got. This asserts it
        // rather than assuming it: the day somebody replaces that field with a hand-written DTO,
        // the tablet silently stops being told which scan the round wants and falls back to NFC
        // for a plant that has printed QR labels.
        assertThat(bundleService.buildFullBundle(sheet.getId()).getSheet().getScanMode())
                .isEqualTo(AssetScanMode.NFC_OR_QR);
    }

    // =========================================================== labelling a QR reading

    @Test
    void aReadingReportedAsQrIsStoredAsQr() {
        Fixture f = seed();
        LogSheet sheet = claimed(generate(templateService.create(templateForm(f))));

        submit(sheet, entryDto(f.assetId(), "42", "PWA_QR", null));

        assertThat(entryOf(sheet).getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_QR);
    }

    @Test
    void aQrReadingSurvivesBeingCorrected() {
        Fixture f = seed();
        LogSheet sheet = claimed(generate(templateService.create(templateForm(f))));
        submit(sheet, entryDto(f.assetId(), "42", "PWA_QR", null));

        // THE ONE THAT WOULD HAVE FAILED. V4 pinned `log_sheet_entry_revisions.entry_source` to
        // the three values that existed then, while `log_sheet_entries.entry_source` has no
        // CHECK at all — so a QR reading stored without complaint and its FIRST correction blew
        // up on a constraint, days later, with nothing unusual having happened.
        reopen(sheet);
        submit(sheet, entryDto(f.assetId(), "43", "PWA_QR", null));

        assertThat(entryRevisionRepository.findByLogSheetEntryIdOrderByIdAsc(entryOf(sheet).getId()))
                .singleElement()
                .satisfies(rev -> assertThat(rev.getEntrySource())
                        .isEqualTo(LogSheetEntrySource.PWA_QR));
        assertThat(entryOf(sheet).getFormData()).containsEntry("temp", "43");
    }

    @Test
    void anOlderTabletThatSendsOnlyTheBooleanIsLabelledExactlyAsBefore() {
        Fixture f = seed();
        LogSheet sheet = claimed(generate(templateService.create(templateForm(f))));

        // No `entrySource` at all — the only contract that existed before V11.
        submit(sheet, entryDto(f.assetId(), "42", null, Boolean.TRUE));
        assertThat(entryOf(sheet).getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_MANUAL);

        reopen(sheet);
        submit(sheet, entryDto(f.assetId(), "43", null, null));
        assertThat(entryOf(sheet).getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_NFC);
    }

    @Test
    void anUnrecognisedChannelFallsBackRatherThanRefusingTheReading() {
        Fixture f = seed();
        LogSheet sheet = claimed(generate(templateService.create(templateForm(f))));

        // This is a LABEL, not a credential. A client sending a channel this build has not heard
        // of must not have its operator's readings refused — the reading is the work.
        submit(sheet, entryDto(f.assetId(), "42", "PWA_TELEPATHY", Boolean.TRUE));

        assertThat(entryOf(sheet).getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_MANUAL);
        assertThat(entryOf(sheet).getFormData()).containsEntry("temp", "42");
    }

    @Test
    void aTabletCannotClaimTheReadingWasTypedIntoTheAdminPanel() {
        Fixture f = seed();
        LogSheet sheet = claimed(generate(templateService.create(templateForm(f))));

        // `WEB` is this server's own word for a reading entered on a screen. A tablet claiming it
        // would make the sheet's page say the round was filled somewhere nobody opened.
        submit(sheet, entryDto(f.assetId(), "42", "WEB", null));

        assertThat(entryOf(sheet).getEntrySource()).isEqualTo(LogSheetEntrySource.PWA_NFC);
    }

    // =================================================================== helpers

    private LogSheetEntry entryOf(LogSheet sheet) {
        return logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
    }

    private LogSheetEntryDto entryDto(Long assetId, String temp, String entrySource, Boolean manual) {
        LogSheetEntryDto dto = new LogSheetEntryDto();
        dto.setAssetId(assetId);
        dto.setFormData(Map.of("temp", temp));
        dto.setEntrySource(entrySource);
        dto.setManualEntry(manual);
        return dto;
    }

    private void submit(LogSheet sheet, LogSheetEntryDto... entries) {
        LogSheetDto dto = new LogSheetDto();
        dto.setId(sheet.getId());
        dto.setLocalId("local-" + System.nanoTime());
        dto.setClientActionId("action-" + System.nanoTime());
        dto.setEntries(List.of(entries));
        dto.setCompletedAt(System.currentTimeMillis());
        logSheetService.submitBatch(List.of(dto));
    }

    /** Back to IN_PROGRESS so the next submit is a correction rather than a first fill. */
    private void reopen(LogSheet sheet) {
        LogSheet fresh = logSheetRepository.findById(sheet.getId()).orElseThrow();
        fresh.setStatus(LogSheetStatus.IN_PROGRESS);
        fresh.setSubmittedAt(null);
        fresh.setCompletedAt(null);
        fresh.setCompletedByUserId(null);
        fresh.setDueAt(System.currentTimeMillis() + 3_600_000L);
        logSheetRepository.saveAndFlush(fresh);
    }

    private LogSheet claimed(LogSheet sheet) {
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        sheet.setAssigneeUserId(1L);
        sheet.setDueAt(System.currentTimeMillis() + 3_600_000L);
        return logSheetRepository.saveAndFlush(sheet);
    }

    private LogSheet generate(LogSheetTemplate template) {
        return generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, System.currentTimeMillis());
    }

    private static String futureInput() {
        // The web form posts a Jalali datetime; this is the hidden ISO value beside it.
        return java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 7_200_000L)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .withNano(0)
                .toString();
    }

    private LogSheetTemplate templateForm(Fixture f) {
        LogSheetTemplate form = new LogSheetTemplate();
        form.setName("SM Template " + System.nanoTime());
        form.setOperationalUnitId(f.unitId());
        form.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        form.setScopeId(f.locationId());
        form.setClassId(f.classId());
        form.setAssetSelectionMode(AssetSelectionMode.SCOPE);
        form.setGenerationMode(GenerationMode.MANUAL);
        form.setScheduleActive(false);
        form.setActive(true);
        return form;
    }

    private record Fixture(Long unitId, Long locationId, Long classId, Long assetId) {}

    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("SM-BU-" + nano);
        unit.setName("Scan Mode Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("SM-LOC-" + nano);
        location.setName("Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);
        hierarchyService.replaceLocationUnits(location.getId(), List.of(unit.getId()));

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SM-SF-" + nano);
        subFunction.setName("Sub");
        subFunction.setTag("NFC-SM-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("SM Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey("temp");
        def.setLabel("دما");
        def.setDataType("number");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("SM-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        return new Fixture(unit.getId(), location.getId(), assetClass.getId(), asset.getId());
    }
}
