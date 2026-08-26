package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetActionLogRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.LogSheetVoidSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetScheduler;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Approval: a supervisor reads a completed round and accepts it.
 *
 * <p><b>The rule this file exists to hold.</b> Approval is a review laid on top of completion, not
 * a different kind of completion. An approved round's readings are exactly as real as an
 * unapproved one's, so every report, export, feed and sync path must treat the two identically —
 * and a place that forgets is <b>silent</b>: no error, no log line, just a smaller number in a
 * report about a plant, or a tablet quietly holding work the server has already closed.
 *
 * <p>{@code CompletedStatusConditionTest} guards the ~40 status conditions mechanically. This file
 * guards the things a text scan cannot see: the transitions themselves, the mobile sync outcomes,
 * and — most importantly — that each report counts an approved round exactly as it counts a
 * submitted one.
 */
@Transactional
class LogSheetApprovalIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired LogSheetScheduler scheduler;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetActionLogRepository actionLogRepository;
    @Autowired LogSheetVoidSubmissionRepository voidSubmissionRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // -----------------------------------------------------------------------
    // The transition
    // -----------------------------------------------------------------------

    @Test
    void approvingRecordsWhoAcceptedTheRoundAndWhen() {
        Fixture f = seed();

        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, "مطابق گزارش شیفت");

        LogSheet sheet = reload(f);
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.APPROVED);
        assertThat(sheet.getApprovedAt()).isNotNull();
        assertThat(sheet.getApprovedByUserId()).isEqualTo(f.supervisorId());
        // Nothing about the work itself moves — approval is a statement about the review.
        assertThat(sheet.getCompletedAt()).isNotNull();
        assertThat(sheet.getCompletedByUserId()).isEqualTo(f.operatorId());
        assertThat(actionLogRepository.findByLogSheetIdOrderByActionAtAsc(f.sheetId()))
                .filteredOn(a -> a.getAction() == LogSheetActionType.APPROVE)
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.getActorUserId()).isEqualTo(f.supervisorId());
                    assertThat(a.getComment()).isEqualTo("مطابق گزارش شیفت");
                });
    }

    @Test
    void theApproverMayBeThePersonWhoCompletedTheRound() {
        // A deliberate decision by the plant: on a small shift the supervisor walks the round
        // themselves, and refusing would leave those sheets permanently unapprovable.
        Fixture f = seed();
        LogSheet sheet = reload(f);
        sheet.setCompletedByUserId(f.supervisorId());
        logSheetRepository.saveAndFlush(sheet);

        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        assertThat(reload(f).getStatus()).isEqualTo(LogSheetStatus.APPROVED);
    }

    @Test
    void withdrawingApprovalReturnsTheRoundToCompletedAndClearsTheStamp() {
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        assignmentService.unapprove(f.sheetId(), f.supervisorId(), ActionSource.WEB, "نیاز به بررسی مجدد");

        LogSheet sheet = reload(f);
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.SUBMITTED);
        // The column and the status can never disagree about whether a review stands.
        assertThat(sheet.getApprovedAt()).isNull();
        assertThat(sheet.getApprovedByUserId()).isNull();
        assertThat(actionLogRepository.findByLogSheetIdOrderByActionAtAsc(f.sheetId()))
                .extracting(a -> a.getAction())
                .containsSequence(LogSheetActionType.APPROVE, LogSheetActionType.UNAPPROVE);
    }

    @Test
    void onlyACompletedRoundCanBeApproved() {
        for (LogSheetStatus from : List.of(LogSheetStatus.PENDING, LogSheetStatus.ASSIGNED,
                LogSheetStatus.IN_PROGRESS, LogSheetStatus.VOIDED,
                LogSheetStatus.EXPIRED, LogSheetStatus.CANCELLED)) {
            Fixture f = seed();
            setStatus(f, from);

            assertThatThrownBy(() ->
                    assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(reload(f).getStatus()).isEqualTo(from);
        }
    }

    @Test
    void approvingTwiceIsRefusedRatherThanRestamped() {
        // Otherwise a second click would move approvedAt and lose who actually reviewed it first.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        assertThatThrownBy(() ->
                assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------------
    // One door in, one door out
    // -----------------------------------------------------------------------

    @Test
    void voidReopenAndExtendAllRefuseAnApprovedRound() {
        // A supervisor who wants to invalidate or correct an approved round has to withdraw the
        // approval first, which makes the sequence visible in the action log instead of letting a
        // reviewed round quietly become an unreviewed one.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);
        long future = System.currentTimeMillis() + 3_600_000L;

        assertThatThrownBy(() -> assignmentService.voidSubmitted(f.sheetId(), f.supervisorId(), ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignmentService.reopenSubmittedWithExtend(
                f.sheetId(), f.supervisorId(), future, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignmentService.extend(
                f.sheetId(), f.supervisorId(), future, ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reload(f).getStatus()).isEqualTo(LogSheetStatus.APPROVED);
    }

    @Test
    void withdrawingApprovalOpensTheDoorToVoidAgain() {
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);
        assignmentService.unapprove(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        assignmentService.voidSubmitted(f.sheetId(), f.supervisorId(), ActionSource.WEB, "اشتباه بود");

        assertThat(reload(f).getStatus()).isEqualTo(LogSheetStatus.VOIDED);
    }

    @Test
    void releaseReassignAndTakeoverAllRefuseAnApprovedRound() {
        // `isTerminal()` covers APPROVED, which is what stops an approved round being handed to
        // somebody as though it were still open work.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        assertThatThrownBy(() -> assignmentService.release(f.sheetId(), f.supervisorId(), ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignmentService.takeover(f.sheetId(), f.supervisorId(), ActionSource.WEB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theExpirySchedulerIgnoresAnApprovedRoundEvenPastItsDeadline() {
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);
        LogSheet sheet = reload(f);
        sheet.setDueAt(System.currentTimeMillis() - 3_600_000L);
        logSheetRepository.saveAndFlush(sheet);

        scheduler.expireOverdueSheets();

        assertThat(reload(f).getStatus()).isEqualTo(LogSheetStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // Mobile sync — the paths that could lose an operator's work
    // -----------------------------------------------------------------------

    @Test
    void aLateOfflineSubmitOnAnApprovedRoundIsVoidedRatherThanOverwritingIt() throws Exception {
        // The dangerous case: a tablet out of coverage for a week comes back holding a completion
        // for a round that has since been completed by somebody else and approved. It must not
        // overwrite the approved readings, and it must not be discarded either.
        Fixture f = seed();
        // Somebody else finished it — a takeover while this tablet was away. Without this the
        // submitter IS the completer and the honest answer is DUPLICATE, which the next test
        // covers.
        LogSheet taken = reload(f);
        taken.setCompletedByUserId(f.supervisorId());
        taken.setAssigneeUserId(f.supervisorId());
        logSheetRepository.saveAndFlush(taken);
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);
        long voidsBefore = voidSubmissionRepository.count();

        JsonNode result = submitFromDevice(f, 999);

        assertThat(result.get("outcome").asText()).isEqualTo("SUPERSEDED");
        assertThat(voidSubmissionRepository.count()).isEqualTo(voidsBefore + 1);
        LogSheet sheet = reload(f);
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.APPROVED);
        assertThat(entry(f).getFormData()).containsEntry("temp", "42");
    }

    @Test
    void theOriginalCompleterResubmittingAnApprovedRoundGetsDuplicateNotAnError() throws Exception {
        // A retry of their own accepted submission. Answering anything else would have the device
        // treat delivered work as failed and keep it queued forever.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        JsonNode result = submitFromDevice(f, 777);

        // The operator IS the completer here — seed() completes as the operator.
        assertThat(result.get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(entry(f).getFormData()).containsEntry("temp", "42");
    }

    @Test
    void aProgressPushOnAnApprovedRoundIsRefusedAsSuperseded() throws Exception {
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        MvcResult res = mockMvc.perform(post("/api/log-sheets/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken())
                        .content("""
                                {"logSheets":[{"serverId":%d,"localId":"l1","entries":[
                                  {"assetId":%d,"classId":%d,"formData":{"temp":"9"}}]}]}
                                """.formatted(f.sheetId(), f.assetId(), f.classId())))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString()).get(0);
        assertThat(body.get("outcome").asText()).isEqualTo("SUPERSEDED");
        assertThat(entry(f).getFormData()).containsEntry("temp", "42");
    }

    @Test
    void theMobileBundleCarriesTheApprovedStatusSoTheDeviceCanGiveUpItsCopy() throws Exception {
        // The device reads this to decide `mark-synced`. An unrecognised status there leaves a
        // stale draft alive and editable for a round the server has closed.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        MvcResult res = mockMvc.perform(get("/api/log-sheets/{id}/bundle", f.sheetId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(objectMapper.readTree(res.getResponse().getContentAsString())
                .get("sheet").get("status").asText()).isEqualTo("APPROVED");
    }

    // -----------------------------------------------------------------------
    // Report parity — the silent failure this whole design guards against
    // -----------------------------------------------------------------------

    @Test
    void everyEntryLevelReportCountsAnApprovedRoundExactlyAsASubmittedOne() {
        // Two identical rounds, one approved. Every one of these numbers must be 2, not 1.
        Fixture a = seed();
        Fixture b = seed();
        assignmentService.approve(b.sheetId(), b.supervisorId(), ActionSource.WEB, null);

        List<Long> unitIds = List.of(a.unitId(), b.unitId());
        long from = 0L;
        long to = System.currentTimeMillis() + 60_000L;

        assertThat(logSheetEntryRepository.entrySourceSplitByUnit(unitIds, from, to))
                .as("منبع ثبت — data quality")
                .hasSize(2);

        assertThat(logSheetEntryRepository.lastSubmittedReadingPerAsset(
                List.of(a.assetId(), b.assetId())))
                .as("silent assets — an approved reading is still a reading")
                .hasSize(2);

        assertThat(logSheetEntryRepository.countBreachedEntries(unitIds, from, to, false))
                .as("exceptions report")
                .isEqualTo(2);

        assertThat(logSheetEntryRepository.countBreachesBySeverity(unitIds, from, to))
                .as("overview severity cards")
                .isNotEmpty();
    }

    @Test
    void complianceCountsAnApprovedRoundAsDeliveredWork() {
        Fixture a = seed();
        Fixture b = seed();
        assignmentService.approve(b.sheetId(), b.supervisorId(), ActionSource.WEB, null);

        List<Object[]> rows = logSheetRepository.complianceByUnit(
                List.of(a.unitId(), b.unitId()), 0L, System.currentTimeMillis() + 60_000L);

        // Column 2 is "submitted". Both rounds are delivered work; only one is reviewed.
        long delivered = rows.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        assertThat(delivered).isEqualTo(2);
    }

    @Test
    void theAssetParameterReportShowsReadingsFromApprovedRounds() {
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        assertThat(logSheetEntryRepository.findSubmittedReadingRowsByAssetIdAsc(
                f.assetId(), LogSheetStatus.COMPLETED_STATUSES, 0L,
                System.currentTimeMillis() + 60_000L))
                .as("a reviewed reading must not disappear from the asset's trend")
                .hasSize(1);
    }

    @Test
    void theIntegrationFeedIncludesApprovedRoundsByDefault() throws Exception {
        // If it did not, every external consumer on the default silently stopped receiving rounds
        // the day approval was switched on — with no error and no way to tell it from a quiet
        // plant.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        List<LogSheet> exposable = logSheetRepository.findExposableToIntegration(
                com.hnp.backendofflinefirst.domain.IntegrationLogSheetQuery.DEFAULT_STATUSES,
                0L, System.currentTimeMillis() + 60_000L, null, null,
                org.springframework.data.domain.Pageable.ofSize(50)).getContent();

        assertThat(exposable).extracting(LogSheet::getId).contains(f.sheetId());
    }

    // -----------------------------------------------------------------------
    // The panel
    // -----------------------------------------------------------------------

    @Test
    void theSheetPageStillOffersItsActionsAndColumnsOnAnApprovedRound() throws Exception {
        // Every `status.name() == 'SUBMITTED'` in the template was a place that silently stopped
        // being true: the operations column and the asset report links vanished.
        Fixture f = seed();
        assignmentService.approve(f.sheetId(), f.supervisorId(), ActionSource.WEB, null);

        String html = mockMvc.perform(get("/log-sheets/{id}", f.sheetId())
                        .with(supervisorSession(f)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("تأییدشده");
        assertThat(html).contains("unapproveModal");
        // Void and reopen are gone by design — approval has to be withdrawn first.
        assertThat(html).doesNotContain("data-bs-target=\"#voidModal\"");
        assertThat(html).doesNotContain("data-bs-target=\"#reopenModal\"");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record Fixture(Long sheetId, Long entryId, Long assetId, Long classId, Long unitId,
                           Long operatorId, Long supervisorId, String operatorToken) {}

    private org.springframework.test.web.servlet.request.RequestPostProcessor supervisorSession(Fixture f) {
        User supervisor = userRepository.findById(f.supervisorId()).orElseThrow();
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user(new com.hnp.backendofflinefirst.security.AppUserDetails(
                        supervisor,
                        java.util.Set.of("SUPERVISOR"),
                        java.util.Set.of("GET:/log-sheets/{id}",
                                "POST:/log-sheets/{id}/approve",
                                "POST:/log-sheets/{id}/unapprove",
                                "POST:/log-sheets/{id}/void",
                                "POST:/log-sheets/{id}/reopen",
                                "CAP:SUPERVISE_ANY_UNIT")));
    }

    private JsonNode submitFromDevice(Fixture f, int value) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/log-sheets/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.operatorToken())
                        .content("""
                                {"logSheets":[{"serverId":%d,"localId":"l1","clientActionId":"%s",
                                  "completedAt":%d,
                                  "entries":[{"assetId":%d,"classId":%d,"formData":{"temp":"%d"}}]}]}
                                """.formatted(f.sheetId(), UUID.randomUUID(),
                                System.currentTimeMillis() - 1000, f.assetId(), f.classId(), value)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get(0);
    }

    private LogSheet reload(Fixture f) {
        return logSheetRepository.findById(f.sheetId()).orElseThrow();
    }

    private LogSheetEntry entry(Fixture f) {
        return logSheetEntryRepository.findById(f.entryId()).orElseThrow();
    }

    private void setStatus(Fixture f, LogSheetStatus status) {
        LogSheet sheet = reload(f);
        sheet.setStatus(status);
        logSheetRepository.saveAndFlush(sheet);
    }

    /** A completed round with one asset carrying a reading that breaches its danger band. */
    private Fixture seed() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("APV-BU-" + nano);
        unit.setName("Approval Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("APV-LOC-" + nano);
        location.setName("Approval Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("APV-SF-" + nano);
        subFunction.setName("Approval Sub");
        subFunction.setTag("NFC-APV-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Approval Pump " + nano);
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
        // A danger band the seeded reading breaches, so the exceptions report has something to
        // count in the parity test.
        def.setValidation(com.hnp.backendofflinefirst.domain.FieldValidationSupport
                .build("number", null, 0.0, 10.0, 0.0, 20.0));
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("APV-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Approval Template " + nano);
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

        User operator = user(unit.getId(), "apv-op-" + nano, "OPERATOR", false);
        User supervisor = user(unit.getId(), "apv-sup-" + nano, "SUPERVISOR", true);

        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedAt(now);
        sheet.setSubmittedAt(now);
        sheet.setCompletedByUserId(operator.getId());
        logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        entry.setFormData(Map.of("temp", "42"));
        entry.setMaxSeverity("DANGER");
        entry.setBreachedFields(List.of("temp"));
        entry.setEntrySource(com.hnp.backendofflinefirst.domain.LogSheetEntrySource.PWA_NFC);
        entry.setFilledByUserId(operator.getId());
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        logSheetEntryRepository.saveAndFlush(entry);

        String token = loginToken(operator.getUsername());
        return new Fixture(sheet.getId(), entry.getId(), entry.getAssetId(), assetClass.getId(),
                unit.getId(), operator.getId(), supervisor.getId(), token);
    }

    private User user(Long unitId, String username, String roleCode, boolean supervisor) {
        long now = System.currentTimeMillis();
        User u = new User();
        u.setUsername(username);
        u.setPersonnelCode("PC-" + UUID.randomUUID());
        u.setFullName(username);
        u.setPasswordHash(passwordEncoder.encode("pw123456"));
        u.setActive(true);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        u = userRepository.saveAndFlush(u);

        UserRole ur = new UserRole();
        ur.setUserId(u.getId());
        ur.setRoleId(roleRepository.findByCode(roleCode).orElseThrow().getId());
        userRoleRepository.saveAndFlush(ur);

        if (supervisor) {
            UnitSupervisor link = new UnitSupervisor();
            link.setUnitId(unitId);
            link.setUserId(u.getId());
            unitSupervisorRepository.saveAndFlush(link);
        } else {
            UnitOperator link = new UnitOperator();
            link.setUnitId(unitId);
            link.setUserId(u.getId());
            unitOperatorRepository.saveAndFlush(link);
        }
        return u;
    }

    private String loginToken(String username) {
        try {
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("username", username, "password", "pw123456"))))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(login.getResponse().getContentAsString())
                    .get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
