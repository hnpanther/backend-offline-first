package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.AssetScanMode;
import com.hnp.backendofflinefirst.domain.AssetSelectionMode;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
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
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.ApiKeyService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Choosing <b>which</b> finished rounds a third party receives.
 *
 * <p>The date window and the status set were never enough. A log sheet is one of two quite
 * different things — a walk round forty pumps, or a questionnaire with no equipment in it at all
 * ({@code AssetSelectionMode.NONE}, roadmap §22) — and a consumer almost always wants exactly
 * one of them. An ERP importing gauge readings has no use for a shift handover; a
 * form-processing system has no use for 47 pumps. Then, among the equipment rounds: this class
 * of machine, this machine, the rounds somebody actually flagged.
 *
 * <p>Everything here is asserted <b>through HTTP</b> rather than against the repository, because
 * the half that breaks is the parsing: a filter that reaches the query as null is a filter that
 * silently returns the whole plant, and only the wire shows that.
 *
 * <p>Not {@code @Transactional}, for the reason {@link IntegrationApiIntegrationTest} gives:
 * usage rows are written on another thread in a new transaction and cannot see an uncommitted
 * fixture. Cleanup is explicit instead.
 */
class IntegrationApiShapeFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/integration/v1/log-sheets";

    /** Wide enough to hold every fixture sheet, narrow enough to exclude the live database. */
    private static final String FROM = "2000-01-01";
    private static final String TO = "2100-01-01";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyUsageRepository apiKeyUsageRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired PasswordEncoder passwordEncoder;

    MockMvc mockMvc;

    private final List<Runnable> cleanups = new ArrayList<>();

    private String apiKey;
    private Long apiKeyId;
    private Fixture fix;

    /**
     * Five finished rounds covering every shape this API can be asked to separate.
     *
     * @param pumpRound     a scheduled NFC round over one pump, read and within range (OK)
     * @param valveRound    a manual QR round over one valve, read and flagged (DANGER)
     * @param questionnaire a round with no assets at all — its content is its own questions
     * @param unreadRound   an NFC round whose single pump nobody reached: an entry with no
     *                      severity, which is what makes {@code severity} different from
     *                      {@code assets=WITH}
     * @param otherUnit     a round belonging to a different operational unit
     * @param approvedRound a round a supervisor has since accepted
     */
    private record Fixture(Long unitId, Long otherUnitId,
                           Long pumpClassId, Long valveClassId,
                           Long pumpAssetId, Long valveAssetId,
                           Long pumpTemplateId, Long questionTemplateId,
                           Long pumpRound, Long valveRound, Long questionnaire,
                           Long unreadRound, Long otherUnit, Long approvedRound,
                           String personnelCode) {
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var issued = apiKeyService.create("CMMS " + UUID.randomUUID(), "shape filter test", null, null);
        apiKey = issued.apiKey();
        apiKeyId = issued.key().getId();
        cleanups.add(() -> {
            apiKeyUsageRepository.deleteAll(apiKeyUsageRepository.findAll().stream()
                    .filter(u -> apiKeyId.equals(u.getApiKeyId()))
                    .toList());
            apiKeyRepository.deleteById(apiKeyId);
        });
        fix = seed();
    }

    @AfterEach
    void tearDown() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException ignored) {
                // A cleanup that cannot run must not mask the assertion that already failed.
            }
        }
        cleanups.clear();
    }

    // ── Equipment or questions ───────────────────────────────────────────────

    @Test
    void withNoShapeFilterEveryFinishedRoundIsReturned() {
        // The compatibility statement: a consumer written before these filters existed sends
        // from and to, and must still receive exactly what it received then.
        assertThat(ids(list(params -> {})))
                .contains(fix.pumpRound, fix.valveRound, fix.questionnaire, fix.unreadRound,
                        fix.otherUnit, fix.approvedRound);
    }

    @Test
    void assetsWithReturnsTheEquipmentRoundsAndNotTheQuestionnaire() {
        List<Long> result = ids(list(p -> p.put("assets", "WITH")));

        assertThat(result).contains(fix.pumpRound, fix.valveRound, fix.unreadRound);
        assertThat(result).doesNotContain(fix.questionnaire);
    }

    @Test
    void assetsWithoutReturnsOnlyTheQuestionsOnlyRounds() {
        List<Long> result = ids(list(p -> p.put("assets", "WITHOUT")));

        assertThat(result).contains(fix.questionnaire);
        assertThat(result).doesNotContain(fix.pumpRound, fix.valveRound, fix.unreadRound);
    }

    @Test
    void anAssetLessRoundPublishesItsOwnQuestionsAndAnEmptyAssetList() throws Exception {
        // The whole point of admitting it to the feed: the content is in sheetForm, and a
        // consumer that only looked at `assets` would read the round as empty.
        mockMvc.perform(get(BASE + "/" + fix.questionnaire).header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.assetCount").value(0))
                .andExpect(jsonPath("$.summary.scanMode").value("NONE"))
                .andExpect(jsonPath("$.assets").isEmpty())
                .andExpect(jsonPath("$.sheetForm.fields[0].key").value("shift"))
                .andExpect(jsonPath("$.sheetForm.values[0].value").value("شب"));
    }

    // ── By class, and by one machine ─────────────────────────────────────────

    @Test
    void filtersByAssetClass() {
        List<Long> pumps = ids(list(p -> p.put("classId", String.valueOf(fix.pumpClassId))));

        assertThat(pumps).contains(fix.pumpRound, fix.unreadRound);
        assertThat(pumps).doesNotContain(fix.valveRound, fix.questionnaire);
    }

    @Test
    void takesSeveralClassesAtOnce() {
        List<Long> both = ids(list(p -> p.put("classId",
                fix.pumpClassId + "," + fix.valveClassId)));

        assertThat(both).contains(fix.pumpRound, fix.valveRound, fix.unreadRound);
        assertThat(both).doesNotContain(fix.questionnaire);
    }

    @Test
    void filtersByOneMachinesWholeHistory() {
        List<Long> result = ids(list(p -> p.put("assetId", String.valueOf(fix.valveAssetId))));

        assertThat(result).containsExactly(fix.valveRound);
    }

    @Test
    void aWellFormedIdThatMatchesNothingIsAnEmptyPageRatherThanAnError() {
        // Deliberate: refusing an unknown id would turn every filter into a probe for which
        // units, templates, classes and assets this plant has.
        assertThat(ids(list(p -> p.put("assetId", "99999999")))).isEmpty();
    }

    // ── How the round was walked, and where it came from ─────────────────────

    @Test
    void filtersByTheFrozenScanMode() {
        assertThat(ids(list(p -> p.put("scanMode", "QR")))).containsExactly(fix.valveRound);
        assertThat(ids(list(p -> p.put("scanMode", "NONE")))).containsExactly(fix.questionnaire);

        List<Long> nfc = ids(list(p -> p.put("scanMode", "NFC")));
        assertThat(nfc).contains(fix.pumpRound, fix.unreadRound);
        assertThat(nfc).doesNotContain(fix.valveRound, fix.questionnaire);
    }

    @Test
    void publishesTheScanModeOnEveryRow() throws Exception {
        // Without it a consumer cannot interpret entrySource at all: a round in QR mode cannot
        // produce PWA_NFC, and PWA_MANUAL on a round that was meant to be scanned is a different
        // fact from PWA_MANUAL on a round where nothing was ever meant to be scanned.
        assertThat(row(fix.valveRound).get("scanMode").asText()).isEqualTo("QR");
        assertThat(row(fix.pumpRound).get("scanMode").asText()).isEqualTo("NFC");

        mockMvc.perform(get(BASE + "/" + fix.valveRound).header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.scanMode").value("QR"))
                // The device half of V11: a QR read is its own entry source, because a chip read
                // can be checked against a hardware serial and a QR read cannot.
                .andExpect(jsonPath("$.assets[0].entrySource").value("PWA_QR"));
    }

    @Test
    void filtersByOrigin() {
        assertThat(ids(list(p -> p.put("origin", "SCHEDULED")))).containsExactly(fix.pumpRound);

        List<Long> manual = ids(list(p -> p.put("origin", "MANUAL")));
        assertThat(manual).contains(fix.valveRound, fix.questionnaire, fix.unreadRound);
        assertThat(manual).doesNotContain(fix.pumpRound);
    }

    @Test
    void filtersByTemplateIncludingSeveralAtOnce() {
        assertThat(ids(list(p -> p.put("templateId", String.valueOf(fix.questionTemplateId)))))
                .containsExactly(fix.questionnaire);

        List<Long> both = ids(list(p -> p.put("templateId",
                fix.pumpTemplateId + "," + fix.questionTemplateId)));
        assertThat(both).contains(fix.pumpRound, fix.questionnaire);
    }

    @Test
    void filtersByOneUnitOrSeveral() {
        assertThat(ids(list(p -> p.put("unitId", String.valueOf(fix.otherUnitId)))))
                .containsExactly(fix.otherUnit);

        List<Long> both = ids(list(p -> p.put("unitId", fix.unitId + "," + fix.otherUnitId)));
        assertThat(both).contains(fix.pumpRound, fix.otherUnit);
    }

    // ── Rounds somebody should look at ───────────────────────────────────────

    @Test
    void filtersBySeverityRecordedAgainstAnAsset() {
        assertThat(ids(list(p -> p.put("severity", "DANGER")))).containsExactly(fix.valveRound);

        List<Long> problems = ids(list(p -> p.put("severity", "WARNING,DANGER")));
        assertThat(problems).contains(fix.valveRound);
        assertThat(problems).doesNotContain(fix.pumpRound, fix.unreadRound);
    }

    @Test
    void aRoundNobodyActuallyReadMatchesNoSeverityAtAll() {
        // max_severity IS NULL is the has-no-reading state — the predicate three partial indexes
        // are built on — so `severity=OK` means "read, and fine", not "no restriction". An entry
        // row exists for every asset in scope whether or not the operator reached it, which is
        // exactly why assets=WITH and severity are different questions.
        List<Long> fine = ids(list(p -> p.put("severity", "OK")));
        assertThat(fine).contains(fix.pumpRound);
        assertThat(fine).doesNotContain(fix.unreadRound, fix.valveRound, fix.questionnaire);

        List<Long> any = ids(list(p -> p.put("severity", "OK,WARNING,DANGER")));
        assertThat(any).doesNotContain(fix.unreadRound);
        assertThat(ids(list(p -> p.put("assets", "WITH")))).contains(fix.unreadRound);
    }

    // ── Combining them ───────────────────────────────────────────────────────

    @Test
    void theFiltersCombineWithAnd() {
        assertThat(ids(list(p -> {
            p.put("assets", "WITH");
            p.put("scanMode", "QR");
            p.put("severity", "DANGER");
            p.put("classId", String.valueOf(fix.valveClassId));
        }))).containsExactly(fix.valveRound);

        // A pump round with a danger reading: there is none, and the empty page is the answer.
        assertThat(ids(list(p -> {
            p.put("classId", String.valueOf(fix.pumpClassId));
            p.put("severity", "DANGER");
        }))).isEmpty();
    }

    @Test
    void aCombinationThatCouldNeverMatchIsRefusedRatherThanAnsweredEmpty() throws Exception {
        // The failure this prevents is a silent one: a poller on assets=WITHOUT&severity=DANGER
        // would see nothing for ever and read it as a plant with no problems.
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", FROM).param("to", TO)
                        .param("assets", "WITHOUT").param("severity", "DANGER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("assets=WITHOUT")));
    }

    @Test
    void refusesAFilterValueItCannotUnderstandAndNamesTheParameter() throws Exception {
        for (String[] bad : List.of(
                new String[]{"assets", "yes"},
                new String[]{"scanMode", "BARCODE"},
                new String[]{"origin", "ROBOT"},
                new String[]{"severity", "CRITICAL"},
                new String[]{"classId", "abc"})) {
            mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                            .param("from", FROM).param("to", TO)
                            .param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.message")
                            .value(org.hamcrest.Matchers.containsString(bad[0])));
        }
    }

    @Test
    void anApprovedRoundSaysWhenAndByWhomItWasReviewed() {
        // Approval does not move completedAt, so a forward-only poller has already passed the
        // round by the time somebody reviews it. approvedAt is what lets a consumer re-reading a
        // trailing window tell a newly approved round from one it already knew about.
        JsonNode reviewed = row(fix.approvedRound);

        assertThat(reviewed.get("status").asText()).isEqualTo("APPROVED");
        assertThat(reviewed.get("approvedAt").asText()).endsWith("Z");
        assertThat(reviewed.get("approvedBy").get("personnelCode").asText())
                .isEqualTo(fix.personnelCode);
        // And an unreviewed round says so rather than inventing a reviewer.
        assertThat(row(fix.pumpRound).get("approvedAt").isNull()).isTrue();
        assertThat(row(fix.pumpRound).get("approvedBy").isNull()).isTrue();
    }

    @Test
    void theShapeFiltersStillRequireAKey() throws Exception {
        mockMvc.perform(get(BASE).param("from", FROM).param("to", TO).param("assets", "WITH"))
                .andExpect(status().isUnauthorized());
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private MvcResult list(Consumer<Map<String, String>> extraParams) {
        Map<String, String> params = new LinkedHashMap<>();
        extraParams.accept(params);
        var request = get(BASE).header("X-API-Key", apiKey).param("from", FROM).param("to", TO);
        params.forEach(request::param);
        try {
            return mockMvc.perform(request.param("size", "200"))
                    .andExpect(status().isOk())
                    .andReturn();
        } catch (Exception e) {
            throw new IllegalStateException("list request failed", e);
        }
    }

    private List<Long> ids(MvcResult result) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : json(result).get("items")) {
            ids.add(item.get("id").asLong());
        }
        // Only this test's rows: the container is shared and an operational dump may be loaded.
        List<Long> mine = List.of(fix.pumpRound, fix.valveRound, fix.questionnaire,
                fix.unreadRound, fix.otherUnit, fix.approvedRound);
        return ids.stream().filter(mine::contains).toList();
    }

    private JsonNode row(Long sheetId) {
        for (JsonNode item : json(list(p -> {})).get("items")) {
            if (item.get("id").asLong() == sheetId) {
                return item;
            }
        }
        throw new AssertionError("sheet " + sheetId + " is not in the feed");
    }

    private JsonNode json(MvcResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response", e);
        }
    }

    private Fixture seed() {
        long now = System.currentTimeMillis();
        String suffix = now + UUID.randomUUID().toString().substring(0, 6);

        User operator = new User();
        operator.setUsername("shape-op-" + suffix);
        operator.setFullName("اپراتور");
        operator.setPersonnelCode("SPC-" + suffix);
        operator.setPasswordHash(passwordEncoder.encode("not-used-" + suffix));
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        final Long operatorId = userRepository.save(operator).getId();

        OperationalUnit unit = saveUnit("SU-" + suffix, "واحد شکل", now);
        OperationalUnit otherUnit = saveUnit("SU2-" + suffix, "واحد دیگر", now);

        Location location = new Location();
        location.setCode("SLOC-" + suffix);
        location.setName("Shape Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        final Long locationId = locationRepository.save(location).getId();

        // One sub-function per asset: ux_asset_entries_active_sub_function allows exactly one
        // active asset at a functional position, which is the hierarchy's whole point.
        final Long pumpSubId = saveSubFunction("SSFP-" + suffix, locationId, now);
        final Long valveSubId = saveSubFunction("SSFV-" + suffix, locationId, now);

        Long pumpClassId = saveClass("پمپ " + suffix, now);
        Long valveClassId = saveClass("شیر " + suffix, now);

        FieldDefinition pumpField = saveField(pumpClassId, "temp", "دما", "number", "°C", 1, now);
        FieldDefinition valveField = saveField(valveClassId, "pos", "وضعیت", "number", "%", 1, now);

        Long pumpAssetId = saveAsset("SPA-" + suffix, "Shape Pump", pumpClassId, pumpSubId, now);
        Long valveAssetId = saveAsset("SVA-" + suffix, "Shape Valve", valveClassId, valveSubId, now);

        Long pumpTemplateId = saveTemplate("راند پمپ " + suffix, unit.getId(),
                AssetSelectionMode.SCOPE, AssetScanMode.NFC, pumpClassId, now);
        // The template that produces the questions-only round: no scope, no class, nothing to scan.
        Long questionTemplateId = saveTemplate("تحویل شیفت " + suffix, unit.getId(),
                AssetSelectionMode.NONE, AssetScanMode.NONE, null, now);

        List<FieldDefinitionSnapshot> pumpSnapshot =
                List.of(FieldDefinitionSnapshot.from(pumpField));
        List<FieldDefinitionSnapshot> valveSnapshot =
                List.of(FieldDefinitionSnapshot.from(valveField));

        Long pumpRound = saveSheet(unit.getId(), now - 90_000L, s -> {
            s.setTemplateId(pumpTemplateId);
            s.setOrigin(GenerationMode.SCHEDULED);
            s.setScanMode(AssetScanMode.NFC);
            s.setFieldDefinitionsSnapshot(pumpSnapshot);
            s.setCompletedByUserId(operatorId);
        });
        Long valveRound = saveSheet(unit.getId(), now - 80_000L, s -> {
            s.setOrigin(GenerationMode.MANUAL);
            s.setScanMode(AssetScanMode.QR);
            s.setFieldDefinitionsSnapshot(valveSnapshot);
            s.setCompletedByUserId(operatorId);
        });
        Long unreadRound = saveSheet(unit.getId(), now - 70_000L, s -> {
            s.setTemplateId(pumpTemplateId);
            s.setOrigin(GenerationMode.MANUAL);
            s.setScanMode(AssetScanMode.NFC);
            s.setFieldDefinitionsSnapshot(pumpSnapshot);
        });
        Long otherUnitRound = saveSheet(otherUnit.getId(), now - 60_000L, s -> {
            s.setOrigin(GenerationMode.MANUAL);
            s.setScanMode(AssetScanMode.NFC);
            s.setFieldDefinitionsSnapshot(pumpSnapshot);
        });
        // Reviewed after the fact: its completion instant did not move, which is the whole
        // reason approvedAt is published.
        Long approvedRound = saveSheet(unit.getId(), now - 65_000L, s -> {
            s.setStatus(LogSheetStatus.APPROVED);
            s.setOrigin(GenerationMode.MANUAL);
            s.setScanMode(AssetScanMode.NFC);
            s.setFieldDefinitionsSnapshot(pumpSnapshot);
            s.setCompletedByUserId(operatorId);
            s.setApprovedAt(now - 10_000L);
            s.setApprovedByUserId(operatorId);
        });

        // A round-level question is frozen onto the sheet, not read from field_definitions —
        // which is a class table whose class_id is NOT NULL and could not hold one anyway. The
        // snapshot is built directly, exactly as the freeze writes it.
        FieldDefinitionSnapshot shiftQuestion = new FieldDefinitionSnapshot();
        shiftQuestion.setKey("shift");
        shiftQuestion.setLabel("شیفت");
        shiftQuestion.setDataType("text");
        shiftQuestion.setRequired(true);
        shiftQuestion.setOrder(1);
        shiftQuestion.setVersion(1);

        Long questionnaire = saveSheet(unit.getId(), now - 50_000L, s -> {
            s.setTemplateId(questionTemplateId);
            s.setOrigin(GenerationMode.MANUAL);
            // Forced to NONE for an asset-less round: there is nothing to scan.
            s.setScanMode(AssetScanMode.NONE);
            s.setSheetFieldDefinitionsSnapshot(List.of(shiftQuestion));
            Map<String, Object> answers = new LinkedHashMap<>();
            answers.put("shift", "شب");
            s.setSheetFormData(answers);
            s.setSheetFormSource("MOBILE");
            s.setSheetFormFilledByUserId(operatorId);
            s.setSheetFormUpdatedAt(now - 51_000L);
        });

        Map<String, Object> pumpReading = new LinkedHashMap<>();
        pumpReading.put("temp", 61.0);
        Map<String, Object> valveReading = new LinkedHashMap<>();
        valveReading.put("pos", 140.0);

        saveEntry(pumpRound, pumpAssetId, pumpClassId, "Shape Pump", pumpReading,
                FieldValidationSeverity.OK.name(), LogSheetEntrySource.PWA_NFC, operatorId, now);
        saveEntry(valveRound, valveAssetId, valveClassId, "Shape Valve", valveReading,
                FieldValidationSeverity.DANGER.name(), LogSheetEntrySource.PWA_QR, operatorId, now);
        // No form data and no severity: the asset was on the round and nobody reached it.
        saveEntry(unreadRound, pumpAssetId, pumpClassId, "Shape Pump", null,
                null, null, null, now);
        saveEntry(otherUnitRound, pumpAssetId, pumpClassId, "Shape Pump", pumpReading,
                FieldValidationSeverity.OK.name(), LogSheetEntrySource.PWA_NFC, operatorId, now);

        saveEntry(approvedRound, pumpAssetId, pumpClassId, "Shape Pump", pumpReading,
                FieldValidationSeverity.OK.name(), LogSheetEntrySource.PWA_NFC, operatorId, now);

        List<Long> sheetIds = List.of(pumpRound, valveRound, unreadRound, otherUnitRound,
                questionnaire, approvedRound);
        cleanups.add(() -> {
            sheetIds.forEach(id ->
                    logSheetEntryRepository.deleteAll(logSheetEntryRepository.findByLogSheetId(id)));
            sheetIds.forEach(logSheetRepository::deleteById);
            templateRepository.deleteById(pumpTemplateId);
            templateRepository.deleteById(questionTemplateId);
            assetEntryRepository.deleteById(pumpAssetId);
            assetEntryRepository.deleteById(valveAssetId);
            fieldDefinitionRepository.delete(pumpField);
            fieldDefinitionRepository.delete(valveField);
            assetClassRepository.deleteById(pumpClassId);
            assetClassRepository.deleteById(valveClassId);
            subFunctionRepository.deleteById(pumpSubId);
            subFunctionRepository.deleteById(valveSubId);
            locationRepository.deleteById(locationId);
            operationalUnitRepository.deleteById(otherUnit.getId());
            operationalUnitRepository.deleteById(unit.getId());
            userRepository.deleteById(operatorId);
        });

        return new Fixture(unit.getId(), otherUnit.getId(), pumpClassId, valveClassId,
                pumpAssetId, valveAssetId, pumpTemplateId, questionTemplateId,
                pumpRound, valveRound, questionnaire, unreadRound, otherUnitRound,
                approvedRound, operator.getPersonnelCode());
    }

    private OperationalUnit saveUnit(String code, String name, long now) {
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(name);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.save(unit);
    }

    private Long saveSubFunction(String code, Long locationId, long now) {
        SubFunction subFunction = new SubFunction();
        subFunction.setCode(code);
        subFunction.setName(code);
        subFunction.setTag("NFC-" + code);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, locationId);
        return hierarchyService.saveSubFunction(subFunction).getId();
    }

    private Long saveClass(String name, long now) {
        AssetClass assetClass = new AssetClass();
        assetClass.setName(name);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        return assetClassRepository.save(assetClass).getId();
    }

    private FieldDefinition saveField(Long classId, String key, String label, String dataType,
                                      String unit, int order, long now) {
        FieldDefinition field = new FieldDefinition();
        field.setClassId(classId);
        field.setKey(key);
        field.setLabel(label);
        field.setDataType(dataType);
        field.setUnit(unit);
        field.setRequired(false);
        field.setOrder(order);
        field.setVersion(1);
        field.setCreatedAt(now);
        field.setUpdatedAt(now);
        return fieldDefinitionRepository.save(field);
    }

    private Long saveAsset(String code, String name, Long classId, Long subFunctionId, long now) {
        AssetEntry asset = new AssetEntry();
        asset.setAssetCode(code);
        asset.setAssetName(name);
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunctionId);
        asset.setNfcTagId(code);
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return assetEntryRepository.save(asset).getId();
    }

    private Long saveTemplate(String name, Long unitId, AssetSelectionMode selection,
                              AssetScanMode scan, Long classId, long now) {
        LogSheetTemplate template = new LogSheetTemplate();
        template.setName(name);
        template.setOperationalUnitId(unitId);
        template.setAssetSelectionMode(selection);
        template.setScanMode(scan);
        template.setClassId(classId);
        template.setGenerationMode(GenerationMode.MANUAL);
        // NOT NULL in the schema and no default on the entity: a template nobody scheduled is
        // explicitly not scheduled.
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        return templateRepository.save(template).getId();
    }

    /** A SUBMITTED round, finished at {@code completedAt}, so the default status set finds it. */
    private Long saveSheet(Long unitId, long completedAt, Consumer<LogSheet> customise) {
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Shape Round");
        sheet.setScopeSummary("shape filter test");
        sheet.setOperationalUnitId(unitId);
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setCompletedAt(completedAt);
        sheet.setSubmittedAt(completedAt + 1_000L);
        sheet.setSyncStatus("SYNCED");
        sheet.setCreatedAt(completedAt - 100_000L);
        sheet.setUpdatedAt(completedAt);
        customise.accept(sheet);
        return logSheetRepository.save(sheet).getId();
    }

    /**
     * One asset row, shaped the way the write path shapes it.
     *
     * <p>Gotcha #186: a severity is never written without the keys that breached, so a fixture
     * must not either. A {@code DANGER} row with null {@code breached_fields} is a row that
     * cannot exist, and it has broken the exception report in test classes that had nothing to
     * do with it.
     */
    private void saveEntry(Long sheetId, Long assetId, Long classId, String assetName,
                           Map<String, Object> formData, String maxSeverity,
                           LogSheetEntrySource source, Long filledBy, long now) {
        LogSheetEntry entry = new LogSheetEntry();
        entry.setLogSheetId(sheetId);
        entry.setAssetId(assetId);
        entry.setAssetName(assetName);
        entry.setClassId(classId);
        entry.setFormData(formData);
        entry.setMaxSeverity(maxSeverity);
        if (maxSeverity != null && !FieldValidationSeverity.OK.name().equals(maxSeverity)) {
            entry.setBreachedFields(List.copyOf(formData.keySet()));
        }
        entry.setEntrySource(source);
        entry.setFilledByUserId(filledBy);
        entry.setCreatedAt(now - 95_000L);
        entry.setUpdatedAt(now - 94_000L);
        logSheetEntryRepository.save(entry);
    }
}
