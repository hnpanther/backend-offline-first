package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetActionType;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetActionLog;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.LogSheetVoidSubmission;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
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
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetAssignmentService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.service.LogSheetService;
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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issues B-01, B-02, B-03 and B-04 (§1) against a real database, through the real endpoints — and
 * the backlog report the tablet sends (V25).
 *
 * <p>The rule under test: <b>work that reaches the server after its deadline is refused and
 * kept, never refused and dropped</b>; a replay is answered with what was actually recorded; and
 * only somebody with a claim on a sheet can leave anything on it. Plus the lifecycle that has to
 * keep working around it: a supervisor extending the deadline makes the kept work deliverable
 * again, and a round handed to somebody else still keeps the first operator's work.
 */
@Transactional
@SuppressWarnings("unchecked")
class LateWorkKeptIntegrationTest extends AbstractPostgresIntegrationTest {

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
    @Autowired LogSheetActionLogRepository actionLogRepository;
    @Autowired LogSheetVoidSubmissionRepository voidRepository;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired LogSheetAssignmentService assignmentService;
    @Autowired JwtService jwtService;
    @Autowired com.hnp.backendofflinefirst.repository.UnitSupervisorRepository unitSupervisorRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // =====================================================================================
    // B-01 — a round finished after its deadline is refused AND kept
    // =====================================================================================

    @Test
    void aRoundFinishedAfterItsDeadlineIsRefusedAndKept() throws Exception {
        Fixture f = seed();
        pastDeadline(f);

        JsonNode r = submit(f, f.token(), "late-1", System.currentTimeMillis(),
                entry(f.asset1(), Map.of("temp", 71)));

        assertThat(r.get("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(r.get("kept").asBoolean()).isTrue();
        assertThat(r.get("error").asText()).isEqualTo("مهلت تکمیل این لاگ‌شیت به پایان رسیده است.");

        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        assertThat(sheet.getStatus()).isEqualTo(LogSheetStatus.EXPIRED);
        // Not applied to the round…
        assertThat(entryFor(f, f.asset1()).getFormData()).isNullOrEmpty();
        // …but kept, with the reading, for the supervisor.
        List<LogSheetVoidSubmission> voids = voidRepository.findByLogSheetId(f.sheetId());
        assertThat(voids).hasSize(1);
        assertThat(voids.get(0).isLateProgress()).isFalse();
        assertThat(voids.get(0).getReason()).isEqualTo(LogSheetService.DEADLINE_PASSED);
        assertThat(voids.get(0).getSubmittedByUserId()).isEqualTo(f.operatorId());
        assertThat(voids.get(0).getPayload()).singleElement()
                .satisfies(p -> assertThat((Map<String, Object>) (Map<?, ?>) p.get("formData")).containsEntry("temp", 71));
        // The history says both things: the round expired, and a completion was set aside.
        assertThat(actions(f)).contains(LogSheetActionType.EXPIRE, LogSheetActionType.SUPERSEDE);
    }

    @Test
    void aLateRoundOnAnAlreadyExpiredSheetIsKeptToo() throws Exception {
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        long due = System.currentTimeMillis() - 3_600_000L;
        sheet.setDueAt(due);
        sheet.setStatus(LogSheetStatus.EXPIRED);
        logSheetRepository.saveAndFlush(sheet);

        JsonNode r = submit(f, f.token(), "late-2", due + 60_000L, entry(f.asset1(), Map.of("temp", 9)));

        assertThat(r.get("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(r.get("kept").asBoolean()).isTrue();
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).hasSize(1);
    }

    @Test
    void aRoundFinishedInTimeIsStillAcceptedAfterTheSchedulerExpiredIt() throws Exception {
        // The rule the late-work change must not disturb: device completion time decides.
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        long due = System.currentTimeMillis() - 3_600_000L;
        sheet.setDueAt(due);
        sheet.setStatus(LogSheetStatus.EXPIRED);
        logSheetRepository.saveAndFlush(sheet);

        JsonNode r = submit(f, f.token(), "ontime-1", due - 60_000L, entry(f.asset1(), Map.of("temp", 5)));

        assertThat(r.get("outcome").asText()).isEqualTo("SUBMITTED");
        assertThat(r.get("kept").isNull() || r.get("kept").isMissingNode()).isTrue();
        assertThat(entryFor(f, f.asset1()).getFormData()).containsEntry("temp", 5);
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).isEmpty();
    }

    @Test
    void extendingTheDeadlineLetsTheKeptRoundThroughOnItsNextSend() throws Exception {
        // What a supervisor does with a kept late round: extend. The tablet re-queues the
        // completion with a NEW key on the extension, and it is then accepted as it was walked.
        Fixture f = seed();
        pastDeadline(f);
        long walkedAt = System.currentTimeMillis();
        assertThat(submit(f, f.token(), "late-3", walkedAt, entry(f.asset1(), Map.of("temp", 33)))
                .get("outcome").asText()).isEqualTo("EXPIRED");

        assignmentService.extend(f.sheetId(), supervisorOf(f), System.currentTimeMillis() + 3_600_000L,
                ActionSource.WEB);
        JsonNode r = submit(f, f.token(), "late-3-after-extend", walkedAt,
                entry(f.asset1(), Map.of("temp", 33)));

        assertThat(r.get("outcome").asText()).isEqualTo("SUBMITTED");
        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getStatus())
                .isEqualTo(LogSheetStatus.SUBMITTED);
        assertThat(entryFor(f, f.asset1()).getFormData()).containsEntry("temp", 33);
        // The kept row stays — it is the record that the round first arrived late.
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).hasSize(1);
    }

    // =====================================================================================
    // B-03 — a replay is answered with what it was recorded as
    // =====================================================================================

    @Test
    void aReplayOfAKeptLateRoundIsNotToldItWasDelivered() throws Exception {
        Fixture f = seed();
        pastDeadline(f);
        long walkedAt = System.currentTimeMillis();

        JsonNode first = submit(f, f.token(), "replay-late", walkedAt, entry(f.asset1(), Map.of("temp", 1)));
        // The response was lost on a weak link; the tablet sends the same item again.
        JsonNode second = submit(f, f.token(), "replay-late", walkedAt, entry(f.asset1(), Map.of("temp", 1)));

        assertThat(first.get("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(second.get("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(second.get("kept").asBoolean()).isTrue();
        // Kept once, not twice; one SUPERSEDE row carrying the key.
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).hasSize(1);
        assertThat(actionRows(f).stream().filter(a -> a.getAction() == LogSheetActionType.SUPERSEDE))
                .hasSize(1);
    }

    @Test
    void aReplayOfASupersededRoundIsAnsweredSupersededAndKeptOnce() throws Exception {
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedByUserId(adminId());
        logSheetRepository.saveAndFlush(sheet);

        JsonNode first = submit(f, f.token(), "replay-sup", System.currentTimeMillis(),
                entry(f.asset1(), Map.of("temp", 2)));
        JsonNode second = submit(f, f.token(), "replay-sup", System.currentTimeMillis(),
                entry(f.asset1(), Map.of("temp", 2)));

        assertThat(first.get("outcome").asText()).isEqualTo("SUPERSEDED");
        // Before V25 this was DUPLICATE, and the tablet marked a refused round delivered.
        assertThat(second.get("outcome").asText()).isEqualTo("SUPERSEDED");
        assertThat(second.get("kept").asBoolean()).isTrue();
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).hasSize(1);
    }

    @Test
    void aReplayOfADeliveredRoundIsStillDuplicate() throws Exception {
        Fixture f = seed();
        long walkedAt = System.currentTimeMillis();

        assertThat(submit(f, f.token(), "replay-ok", walkedAt, entry(f.asset1(), Map.of("temp", 3)))
                .get("outcome").asText()).isEqualTo("SUBMITTED");
        JsonNode again = submit(f, f.token(), "replay-ok", walkedAt, entry(f.asset1(), Map.of("temp", 3)));

        assertThat(again.get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).isEmpty();
        assertThat(actionRows(f).stream().filter(a -> a.getAction() == LogSheetActionType.COMPLETE))
                .hasSize(1);
    }

    // =====================================================================================
    // B-04 §1 — nothing is kept for somebody with no claim on the sheet
    // =====================================================================================

    @Test
    void anOperatorWithNoClaimOnTheSheetLeavesNothingOnIt() throws Exception {
        Fixture f = seed();
        Fixture other = seed();   // a different unit, a different operator
        pastDeadline(f);
        long voidsBefore = voidRepository.count();
        int actionsBefore = actionRows(f).size();

        JsonNode r = submit(f, other.token(), "stranger-1", System.currentTimeMillis(),
                entry(f.asset1(), Map.of("temp", 666)));

        assertThat(r.get("outcome").asText()).isEqualTo("ERROR");
        // Indistinguishable from a sheet that does not exist: no oracle for ids or states.
        assertThat(r.get("error").asText()).isEqualTo("لاگ‌شیت روی سرور یافت نشد.");
        assertThat(voidRepository.count()).isEqualTo(voidsBefore);
        assertThat(actionRows(f)).hasSize(actionsBefore);
    }

    @Test
    void anOperatorWhoHeldTheSheetAndWasMovedAwayStillHasTheirWorkKept() throws Exception {
        // The case voids exist for: assigned, walked it offline, reassigned AND moved to another
        // unit before reconnecting. Their completion must still be kept, not turned away.
        Fixture f = seed();
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        // The history of the assignment the operator was given.
        LogSheetActionLog assign = new LogSheetActionLog();
        assign.setLogSheetId(f.sheetId());
        assign.setAction(LogSheetActionType.ASSIGN);
        assign.setSource(ActionSource.WEB);
        assign.setActorUserId(adminId());
        assign.setToUserId(f.operatorId());
        assign.setActionAt(System.currentTimeMillis() - 7_200_000L);
        assign.setRecordedAt(System.currentTimeMillis() - 7_200_000L);
        actionLogRepository.saveAndFlush(assign);
        // Handed to somebody else, and the first operator removed from the unit.
        Fixture second = seed();
        sheet.setAssigneeUserId(second.operatorId());
        logSheetRepository.saveAndFlush(sheet);
        unitOperatorRepository.findAll().stream()
                .filter(u -> u.getUserId().equals(f.operatorId()))
                .forEach(unitOperatorRepository::delete);
        unitOperatorRepository.flush();

        JsonNode r = submit(f, f.token(), "moved-1", System.currentTimeMillis(),
                entry(f.asset1(), Map.of("temp", 12)));

        assertThat(r.get("outcome").asText()).isEqualTo("SUPERSEDED");
        assertThat(r.get("kept").asBoolean()).isTrue();
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).hasSize(1);
    }

    // =====================================================================================
    // B-02 — readings of an unfinished round that arrive after the deadline are kept
    // =====================================================================================

    @Test
    void lateProgressIsKeptAsOneRowAndMergedByAssetButNeverApplied() throws Exception {
        Fixture f = seed();
        pastDeadline(f);

        JsonNode a = progress(f, f.token(), entry(f.asset1(), Map.of("temp", 40)));
        assertThat(a.get("outcome").asText()).isEqualTo("EXPIRED");
        assertThat(a.get("kept").asBoolean()).isTrue();
        assertThat(a.get("savedAt").asLong()).isPositive();

        // Re-sends carry only what changed: a second asset, then a correction of the first.
        progress(f, f.token(), entry(f.asset2(), Map.of("temp", 50)));
        progress(f, f.token(), entry(f.asset1(), Map.of("temp", 41)));

        List<LogSheetVoidSubmission> rows = voidRepository.findByLogSheetId(f.sheetId());
        assertThat(rows).hasSize(1);
        LogSheetVoidSubmission row = rows.get(0);
        assertThat(row.isLateProgress()).isTrue();
        assertThat(row.getReason()).isEqualTo(LogSheetService.LATE_PROGRESS_REASON);
        assertThat(row.getPayload()).hasSize(2);
        assertThat(row.getPayload()).anySatisfy(p -> {
            assertThat(String.valueOf(p.get("assetId"))).isEqualTo(String.valueOf(f.asset1()));
            assertThat((Map<String, Object>) (Map<?, ?>) p.get("formData")).containsEntry("temp", 41);
        });
        assertThat(row.getPayload()).anySatisfy(p ->
                assertThat((Map<String, Object>) (Map<?, ?>) p.get("formData")).containsEntry("temp", 50));
        // Not applied: the round's state at expiry is what reports read.
        assertThat(entryFor(f, f.asset1()).getFormData()).isNullOrEmpty();
        // One history row when the readings first arrived, not one per re-send.
        assertThat(actionRows(f).stream().filter(x -> x.getAction() == LogSheetActionType.SUPERSEDE))
                .hasSize(1);
    }

    @Test
    void theReviewPageShowsALateProgressRowForWhatItIs() throws Exception {
        Fixture f = seed();
        pastDeadline(f);
        progress(f, f.token(), entry(f.asset1(), Map.of("temp", 40)));
        Long voidId = voidRepository.findByLogSheetId(f.sheetId()).get(0).getId();

        String html = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/log-sheets/{id}/void-submissions/{voidId}", f.sheetId(), voidId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user(com.hnp.backendofflinefirst.support.TestPrincipals.of(
                                        userRepository.findByUsername("admin").orElseThrow(),
                                        java.util.Set.of("ADMIN"),
                                        java.util.List.of("GET:/log-sheets/{id}")))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("پس از مهلت رسید — راند ناتمام");
        assertThat(html).contains("آخرین خوانش در دستگاه");
        assertThat(html).doesNotContain(">تکمیل در دستگاه<");
    }

    @Test
    void lateProgressFromSomebodyElseIsNotKept() throws Exception {
        Fixture f = seed();
        Fixture other = seed();
        pastDeadline(f);
        long before = voidRepository.count();

        JsonNode r = progress(f, other.token(), entry(f.asset1(), Map.of("temp", 1)));

        assertThat(r.get("outcome").asText()).isEqualTo("SUPERSEDED");
        assertThat(r.get("kept").isNull() || r.get("kept").isMissingNode()).isTrue();
        assertThat(voidRepository.count()).isEqualTo(before);
    }

    @Test
    void lateProgressNamingAnAssetTheRoundDoesNotHaveIsRefusedNotKept() throws Exception {
        Fixture f = seed();
        pastDeadline(f);

        JsonNode r = progress(f, f.token(), entry(999_999L, Map.of("temp", 1)));

        assertThat(r.get("outcome").asText()).isEqualTo("ERROR");
        assertThat(voidRepository.findByLogSheetId(f.sheetId())).isEmpty();
    }

    @Test
    void afterAnExtensionTheSameReadingsArriveAsOrdinaryProgress() throws Exception {
        Fixture f = seed();
        pastDeadline(f);
        progress(f, f.token(), entry(f.asset1(), Map.of("temp", 44)));

        assignmentService.extend(f.sheetId(), supervisorOf(f), System.currentTimeMillis() + 3_600_000L,
                ActionSource.WEB);
        JsonNode r = progress(f, f.token(), entry(f.asset1(), Map.of("temp", 44)));

        assertThat(r.get("outcome").asText()).isEqualTo("SAVED");
        assertThat(entryFor(f, f.asset1()).getFormData()).containsEntry("temp", 44);
    }

    @Test
    void lateProgressStillOpenRoundAfterReassignmentGoesToTheNewHolderNotTheOld() throws Exception {
        // Handed to somebody else after the deadline passed and then extended: the new holder's
        // progress is applied; the first operator's late readings stay in their kept row.
        Fixture f = seed();
        pastDeadline(f);
        progress(f, f.token(), entry(f.asset1(), Map.of("temp", 70)));

        User second = createOperator(f.unitId(), "late-op2-" + System.nanoTime(), "op12345");
        Long supervisor = supervisorOf(f);
        assignmentService.extend(f.sheetId(), supervisor, System.currentTimeMillis() + 3_600_000L,
                ActionSource.WEB);
        assignmentService.reassign(f.sheetId(), second.getId(), supervisor, ActionSource.WEB);
        String secondToken = loginToken(second.getUsername(), "op12345");

        assertThat(progress(f, f.token(), entry(f.asset1(), Map.of("temp", 71))).get("outcome").asText())
                .isEqualTo("SUPERSEDED");
        assertThat(progress(f, secondToken, entry(f.asset2(), Map.of("temp", 72))).get("outcome").asText())
                .isEqualTo("SAVED");

        assertThat(entryFor(f, f.asset2()).getFormData()).containsEntry("temp", 72);
        LogSheetVoidSubmission kept = voidRepository.findByLogSheetId(f.sheetId()).stream()
                .filter(LogSheetVoidSubmission::isLateProgress).findFirst().orElseThrow();
        assertThat(kept.getSubmittedByUserId()).isEqualTo(f.operatorId());
        assertThat(kept.getPayload()).singleElement()
                .satisfies(p -> assertThat((Map<String, Object>) (Map<?, ?>) p.get("formData")).containsEntry("temp", 70));
    }

    // =====================================================================================
    // The tablet's backlog report (V25)
    // =====================================================================================

    @Test
    void aTabletReportsItsUndeliveredRoundsOnItsOwnSessionRow() throws Exception {
        Fixture f = seed();
        long oldest = System.currentTimeMillis() - 86_400_000L;

        mockMvc.perform(post("/api/sync/backlog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content("{\"undeliveredCount\":3,\"oldestUndeliveredAt\":" + oldest + "}"))
                .andExpect(status().isNoContent());

        ApiSession session = sessionOf(f.token());
        assertThat(session.getUndeliveredCount()).isEqualTo(3);
        assertThat(session.getUndeliveredOldestAt()).isEqualTo(oldest);
        assertThat(session.getBacklogReportedAt()).isPositive();

        // Zero clears the oldest stamp, whatever the body says.
        mockMvc.perform(post("/api/sync/backlog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content("{\"undeliveredCount\":0,\"oldestUndeliveredAt\":" + oldest + "}"))
                .andExpect(status().isNoContent());
        ApiSession cleared = sessionOf(f.token());
        assertThat(cleared.getUndeliveredCount()).isZero();
        assertThat(cleared.getUndeliveredOldestAt()).isNull();
    }

    @Test
    void aNonsenseBacklogReportIsRefusedAndWritesNothing() throws Exception {
        Fixture f = seed();

        for (String body : List.of("{\"undeliveredCount\":-1}", "{}", "{\"undeliveredCount\":100001}")) {
            mockMvc.perform(post("/api/sync/backlog")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(sessionOf(f.token()).getUndeliveredCount()).isNull();
    }

    @Test
    void theBacklogEndpointNeedsASignedInTablet() throws Exception {
        mockMvc.perform(post("/api/sync/backlog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"undeliveredCount\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    record Fixture(Long sheetId, Long asset1, Long asset2, Long unitId, Long operatorId, String token) {}

    private void pastDeadline(Fixture f) {
        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        sheet.setDueAt(System.currentTimeMillis() - 600_000L);
        logSheetRepository.saveAndFlush(sheet);
    }

    /**
     * A real supervisor of the round's unit — extend and reassign are supervisor actions, and
     * reassign has no plant-wide bypass, so the lifecycle is driven exactly as the panel drives it.
     */
    private Long supervisorOf(Fixture f) {
        User sup = createOperator(f.unitId(), "late-sup-" + System.nanoTime(), "sup12345");
        userRoleRepository.findAll().stream()
                .filter(r -> r.getUserId().equals(sup.getId()))
                .forEach(userRoleRepository::delete);
        UserRole role = new UserRole();
        role.setUserId(sup.getId());
        role.setRoleId(roleRepository.findByCode("SUPERVISOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(role);
        com.hnp.backendofflinefirst.entity.UnitSupervisor link = new com.hnp.backendofflinefirst.entity.UnitSupervisor();
        link.setUnitId(f.unitId());
        link.setUserId(sup.getId());
        unitSupervisorRepository.saveAndFlush(link);
        return sup.getId();
    }

    private Long adminId() {
        return userRepository.findByUsername("admin").orElseThrow().getId();
    }

    private ApiSession sessionOf(String token) {
        String jti = jwtService.verify(token).orElseThrow().jti();
        return apiSessionRepository.findByJti(jti).orElseThrow();
    }

    private List<LogSheetActionLog> actionRows(Fixture f) {
        return actionLogRepository.findByLogSheetIdOrderByActionAtAsc(f.sheetId());
    }

    private List<LogSheetActionType> actions(Fixture f) {
        return actionRows(f).stream().map(LogSheetActionLog::getAction).toList();
    }

    private JsonNode submit(Fixture f, String token, String key, long completedAt, String entriesJson)
            throws Exception {
        String body = """
                {"logSheets":[{"serverId":%d,"localId":"local-1","clientActionId":"%s",
                  "completedAt":%d,"submittedAt":%d,"operatorName":"Op","entries":[%s]}]}
                """.formatted(f.sheetId(), key + "-" + f.sheetId(), completedAt, completedAt, entriesJson);
        MvcResult result = mockMvc.perform(post("/api/log-sheets/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
    }

    private JsonNode progress(Fixture f, String token, String entriesJson) throws Exception {
        String body = """
                {"logSheets":[{"serverId":%d,"localId":"local-1","operatorName":"Op","entries":[%s]}]}
                """.formatted(f.sheetId(), entriesJson);
        MvcResult result = mockMvc.perform(post("/api/log-sheets/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
    }

    private String entry(Long assetId, Map<String, Object> formData) throws Exception {
        return """
                {"assetId":%d,"classId":null,"formData":%s,"updatedAt":%d}
                """.formatted(assetId, objectMapper.writeValueAsString(formData), System.currentTimeMillis());
    }

    private LogSheetEntry entryFor(Fixture f, Long assetId) {
        return logSheetEntryRepository.findByLogSheetId(f.sheetId()).stream()
                .filter(e -> assetId.equals(e.getAssetId()))
                .findFirst()
                .orElseThrow();
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private User createOperator(Long unitId, String username, String rawPassword) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(username);
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Late Work Operator");
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
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

    /** A generated sheet ASSIGNED to a fresh operator of a fresh unit, two assets, due in an hour. */
    private Fixture seed() throws Exception {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("LATE-U-" + nano);
        unit.setName("Late Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("LATE-L-" + nano);
        location.setName("Late Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Late Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);
        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey("temp");
        def.setLabel("Temperature");
        def.setDataType("number");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        for (int i = 1; i <= 2; i++) {
            SubFunction subFunction = new SubFunction();
            subFunction.setCode("LATE-SF-" + i + "-" + nano);
            subFunction.setName("Late Sub " + i);
            subFunction.setTag("NFC-LATE-" + i + "-" + nano);
            subFunction.setCreatedAt(now);
            subFunction.setUpdatedAt(now);
            hierarchyService.applySubFunctionParent(
                    subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
            subFunction = hierarchyService.saveSubFunction(subFunction);

            AssetEntry asset = new AssetEntry();
            asset.setAssetCode("LATE-A" + i + "-" + nano);
            asset.setAssetName("Pump " + i);
            asset.setClassId(assetClass.getId());
            asset.setSubFunctionId(subFunction.getId());
            asset.setCreatedAt(now);
            asset.setUpdatedAt(now);
            assetEntryRepository.saveAndFlush(asset);
        }

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Late Template " + nano);
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

        LogSheet sheet = generationService.generateFromTemplate(template, GenerationMode.MANUAL, null, now);

        User operator = createOperator(unit.getId(), "late-op-" + nano, "op12345");
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.ASSIGNED);
        logSheetRepository.saveAndFlush(sheet);

        List<LogSheetEntry> entries = logSheetEntryRepository.findByLogSheetId(sheet.getId());
        assertThat(entries).hasSize(2);
        return new Fixture(sheet.getId(), entries.get(0).getAssetId(), entries.get(1).getAssetId(),
                unit.getId(), operator.getId(), loginToken(operator.getUsername(), "op12345"));
    }
}
