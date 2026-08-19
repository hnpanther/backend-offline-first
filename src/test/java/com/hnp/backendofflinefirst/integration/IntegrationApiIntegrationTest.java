package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ApiKeyUsageOutcome;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetEntrySource;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.ApiKey;
import com.hnp.backendofflinefirst.entity.ApiKeyUsage;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.domain.FieldDefinitionSnapshot;
import com.hnp.backendofflinefirst.repository.ApiKeyRepository;
import com.hnp.backendofflinefirst.repository.ApiKeyUsageRepository;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.service.ApiKeyService;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.AttachmentReferences;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The third-party integration API, end to end.
 *
 * <p><b>Deliberately not {@code @Transactional.</b>} Usage rows are written on
 * {@code auditExecutor} in a {@code REQUIRES_NEW} transaction, on another thread — it cannot
 * see a test transaction that has not committed, and the {@code api_key_usage → api_keys}
 * foreign key would fail against an uncommitted key row. A rolled-back test would therefore
 * prove the auditing works while it silently did not. Fixtures are cleaned up in
 * {@link #tearDown()} instead.
 */
class IntegrationApiIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/integration/v1/log-sheets";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired ApiKeyUsageRepository apiKeyUsageRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired UserRepository userRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    MockMvc mockMvc;

    /** Everything this test created, torn down newest-first so foreign keys stay satisfied. */
    private final List<Runnable> cleanups = new ArrayList<>();

    private String apiKey;
    private Long apiKeyId;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var issued = apiKeyService.create("ERP " + UUID.randomUUID(), "integration test", null, null);
        apiKey = issued.apiKey();
        apiKeyId = issued.key().getId();
        cleanups.add(() -> {
            apiKeyUsageRepository.deleteAll(apiKeyUsageRepository.findAll().stream()
                    .filter(u -> apiKeyId.equals(u.getApiKeyId()))
                    .toList());
            apiKeyRepository.deleteById(apiKeyId);
        });
        fixture = seedFixture();
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

    // ── Authentication ───────────────────────────────────────────────────────

    @Test
    void aRequestWithNoKeyIsRefused() throws Exception {
        mockMvc.perform(get(BASE).param("from", "2020-01-01").param("to", "2100-01-01"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void aMalformedOrUnknownKeyIsRefused() throws Exception {
        for (String bad : new String[]{"not-a-key", "lsk_deadbeefdeadbeef_wrongsecret", ""}) {
            mockMvc.perform(get(BASE).header("X-API-Key", bad)
                            .param("from", "2020-01-01").param("to", "2100-01-01"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("unauthorized"));
        }
    }

    @Test
    void theRightKeyIdWithTheWrongSecretIsRefused() throws Exception {
        // The half of the key that is public must not be enough on its own.
        String keyId = apiKeyRepository.findById(apiKeyId).orElseThrow().getKeyId();

        mockMvc.perform(get(BASE).header("X-API-Key", "lsk_" + keyId + "_wrong-secret")
                        .param("from", "2020-01-01").param("to", "2100-01-01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDisabledKeyIsRefusedAndWorksAgainWhenReEnabled() throws Exception {
        apiKeyService.setActive(apiKeyId, false, null);
        callList().andExpect(status().isUnauthorized());

        apiKeyService.setActive(apiKeyId, true, null);
        callList().andExpect(status().isOk());
    }

    @Test
    void aRevokedKeyIsRefusedAndCannotBeReEnabled() throws Exception {
        apiKeyService.revoke(apiKeyId, "leaked in a ticket", null);

        callList().andExpect(status().isUnauthorized());
        assertThat(catchThrowable(() -> apiKeyService.setActive(apiKeyId, true, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredKeyIsRefused() throws Exception {
        ApiKey key = apiKeyRepository.findById(apiKeyId).orElseThrow();
        key.setExpiresAt(System.currentTimeMillis() - 1_000L);
        apiKeyRepository.save(key);

        callList().andExpect(status().isUnauthorized());
    }

    @Test
    void revokingOneKeyDoesNotAffectAnother() throws Exception {
        var second = apiKeyService.create("MES " + UUID.randomUUID(), null, null, null);
        cleanups.add(() -> apiKeyRepository.deleteById(second.key().getId()));

        apiKeyService.revoke(apiKeyId, "rotation", null);

        callList().andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE).header("X-API-Key", second.apiKey())
                        .param("from", "2020-01-01").param("to", "2100-01-01"))
                .andExpect(status().isOk());
    }

    /**
     * The separation the requirement asks for, tested from both sides: a user credential must
     * not open the integration API, and an API key must not open the user API.
     */
    @Test
    void theIntegrationApiAndTheUserApisDoNotShareCredentials() throws Exception {
        String jwt = loginToken();

        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                        .param("from", "2020-01-01").param("to", "2100-01-01"))
                .andExpect(status().isUnauthorized());

        // And the reverse: the key is not a session and not a token.
        mockMvc.perform(get("/api/bootstrap").header("X-API-Key", apiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminWebSessionCannotReachTheIntegrationApi() throws Exception {
        // A logged-in administrator is the most privileged principal there is, and this chain
        // still does not know what a user is.
        mockMvc.perform(get(BASE)
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("admin").authorities(
                                        new org.springframework.security.core.authority.SimpleGrantedAuthority("GET:/log-sheets")))
                        .param("from", "2020-01-01").param("to", "2100-01-01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theIntegrationChainNeverMintsASession() throws Exception {
        MvcResult result = callList().andExpect(status().isOk()).andReturn();

        assertThat(result.getRequest().getSession(false))
                .as("a key must not be tradeable for a cookie that outlives its revocation")
                .isNull();
    }

    // ── The list endpoint ────────────────────────────────────────────────────

    @Test
    void returnsASubmittedSheetInsideTheRange() throws Exception {
        callList()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + fixture.submitted + ")]").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.hasNext").isBoolean());
    }

    /**
     * The property a polling integration depends on: yesterday's {@code to} is usable verbatim
     * as today's {@code from}, with no row counted twice and none lost between the two calls.
     */
    @Test
    void theRangeIsHalfOpen() throws Exception {
        long completedAt = logSheetRepository.findById(fixture.submitted).orElseThrow().getCompletedAt();
        String at = Instant.ofEpochMilli(completedAt).toString();
        String oneBefore = Instant.ofEpochMilli(completedAt - 1_000L).toString();
        String oneAfter = Instant.ofEpochMilli(completedAt + 1L).toString();

        // `from` is inclusive: a sheet completed at exactly `from` is in the window …
        assertThat(idsFrom(list(at, oneAfter))).contains(fixture.submitted);
        // … and `to` is exclusive: the same sheet is NOT in the window that ends at its instant.
        assertThat(idsFrom(list(oneBefore, at))).doesNotContain(fixture.submitted);
    }

    @Test
    void aSheetOutsideTheRangeIsNotReturned() throws Exception {
        assertThat(idsFrom(list("2000-01-01", "2000-02-01"))).doesNotContain(fixture.submitted);
    }

    /** The core promise: half-finished work never leaves the building. */
    @Test
    void inFlightSheetsAreNeverReturnedByAnyFilter() throws Exception {
        List<Long> everything = idsFrom(list("2000-01-01", "2100-01-01",
                "SUBMITTED,VOIDED,EXPIRED,CANCELLED"));

        assertThat(everything)
                .doesNotContain(fixture.pending)
                .doesNotContain(fixture.inProgress);
    }

    @Test
    void requestingAnInFlightStatusIsRefusedRatherThanSilentlyIgnored() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("statuses", "IN_PROGRESS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("finished")));
    }

    @Test
    void defaultsToSubmittedOnlySoVoidedReadingsAreNotImportedByAccident() throws Exception {
        List<Long> defaulted = idsFrom(list("2000-01-01", "2100-01-01"));

        assertThat(defaulted).contains(fixture.submitted);
        assertThat(defaulted)
                .as("a voided sheet holds readings this plant explicitly invalidated")
                .doesNotContain(fixture.voided);
    }

    @Test
    void expiredSheetsAreReturnedWhenAskedForByName() throws Exception {
        // The user's own requirement: "completed and expired" in one call. An expired sheet has
        // no completedAt, so this also proves the COALESCE window works per status.
        List<Long> both = idsFrom(list("2000-01-01", "2100-01-01", "SUBMITTED,EXPIRED"));

        assertThat(both).contains(fixture.submitted, fixture.expired);
    }

    @Test
    void filtersByOperationalUnit() throws Exception {
        MvcResult mine = mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("unitId", String.valueOf(fixture.unitId)))
                .andExpect(status().isOk()).andReturn();

        assertThat(idsFrom(mine)).contains(fixture.submitted);
        assertThat(idsFrom(mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("unitId", String.valueOf(fixture.otherUnitId)))
                .andExpect(status().isOk()).andReturn()))
                .doesNotContain(fixture.submitted);
    }

    @Test
    void filtersByTemplate() throws Exception {
        assertThat(idsFrom(mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("templateId", "999999"))
                .andExpect(status().isOk()).andReturn()))
                .doesNotContain(fixture.submitted);
    }

    @Test
    void withNoUnitOrTemplateFilterEverySheetIsReturned() throws Exception {
        // The user asked for exactly this default: no filter means the whole plant.
        List<Long> all = idsFrom(list("2000-01-01", "2100-01-01", "SUBMITTED"));

        assertThat(all).contains(fixture.submitted, fixture.submittedOtherUnit);
    }

    @Test
    void paginatesAndClampsAnOversizedPage() throws Exception {
        MvcResult firstPage = mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("statuses", "SUBMITTED")
                        .param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andReturn();
        assertThat(idsFrom(firstPage)).hasSize(1);

        // The effective size is echoed, so a caller can see it is being clamped rather than
        // walking a page size the server never applied.
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
    }

    @Test
    void pagesDoNotRepeatOrSkipRows() throws Exception {
        List<Long> pageZero = idsFrom(page(0, 1));
        List<Long> pageOne = idsFrom(page(1, 1));

        assertThat(pageZero).hasSize(1);
        assertThat(pageOne).hasSize(1);
        assertThat(pageZero).doesNotContainAnyElementsOf(pageOne);
    }

    @Test
    void rejectsAnUnparseableRangeWithAMessageThatNamesTheParameter() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "01/08/2026").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'from'")));
    }

    @Test
    void requiresBothEndsOfTheRange() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey).param("from", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("'to'")));
    }

    /**
     * The list must not carry anything internal. Written against the serialised JSON rather
     * than against the DTO so that adding a field to a shared type — which is exactly how a
     * leak happens — fails here.
     */
    @Test
    void theListLeaksNoInternalField() throws Exception {
        String body = callList().andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("syncStatus")
                .doesNotContain("syncedAt")
                .doesNotContain("fieldDefinitionsSnapshot")
                .doesNotContain("draftSavedAt")
                .doesNotContain("assigneeUserId")
                .doesNotContain("completedByUserId")
                .doesNotContain("assignedByUserId")
                .doesNotContain("nfcSerial")
                .doesNotContain("nationalCode")
                .doesNotContain("phoneNumber")
                .doesNotContain("passwordHash");
    }

    @Test
    void publishesPeopleByPersonnelCodeAndNotByInternalId() throws Exception {
        JsonNode row = itemFor(fixture.submitted, callList().andReturn());

        assertThat(row.get("completedBy").get("personnelCode").asText()).isEqualTo(fixture.personnelCode);
        assertThat(row.get("completedBy").get("username").asText()).isEqualTo(fixture.username);
        assertThat(row.get("completedBy").has("id")).isFalse();
        assertThat(row.get("completedBy").has("userId")).isFalse();
    }

    @Test
    void timestampsAreIso8601Utc() throws Exception {
        JsonNode row = itemFor(fixture.submitted, callList().andReturn());

        String completedAt = row.get("completedAt").asText();
        assertThat(completedAt).endsWith("Z");
        assertThat(Instant.parse(completedAt).toEpochMilli())
                .isEqualTo(logSheetRepository.findById(fixture.submitted).orElseThrow().getCompletedAt());
        // finalizedAt is the instant the window matched on — for a submitted sheet, completedAt.
        assertThat(row.get("finalizedAt").asText()).isEqualTo(completedAt);
    }

    @Test
    void anExpiredSheetIsWindowedOnItsExpiryInstant() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("statuses", "EXPIRED"))
                .andExpect(status().isOk()).andReturn();

        JsonNode row = itemFor(fixture.expired, result);
        LogSheet sheet = logSheetRepository.findById(fixture.expired).orElseThrow();

        assertThat(row.get("completedAt").isNull()).isTrue();
        assertThat(Instant.parse(row.get("finalizedAt").asText()).toEpochMilli())
                .isEqualTo(sheet.getExpiredAt());
    }

    @Test
    void carriesTheUnitAndTheCounts() throws Exception {
        JsonNode row = itemFor(fixture.submitted, callList().andReturn());

        assertThat(row.get("unit").get("id").asLong()).isEqualTo(fixture.unitId);
        assertThat(row.get("unit").get("code").asText()).isEqualTo(fixture.unitCode);
        assertThat(row.get("assetCount").asInt()).isEqualTo(1);
        assertThat(row.get("attachmentCount").asInt()).isEqualTo(1);
    }

    // ── The detail endpoint ──────────────────────────────────────────────────

    @Test
    void returnsFullDetailForAFinishedSheet() throws Exception {
        mockMvc.perform(get(BASE + "/" + fixture.submitted).header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.id").value(fixture.submitted))
                .andExpect(jsonPath("$.summary.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fields[0].key").value("temp"))
                .andExpect(jsonPath("$.fields[0].label").value("دما"))
                .andExpect(jsonPath("$.fields[0].unit").value("°C"))
                .andExpect(jsonPath("$.assets[0].asset.id").value(fixture.assetId))
                .andExpect(jsonPath("$.assets[0].asset.code").value(fixture.assetCode))
                .andExpect(jsonPath("$.assets[0].asset.className").value(fixture.className))
                .andExpect(jsonPath("$.assets[0].asset.nfcTagId").value(fixture.nfcTagId))
                .andExpect(jsonPath("$.assets[0].entrySource").value("PWA_NFC"))
                .andExpect(jsonPath("$.assets[0].filledBy.personnelCode").value(fixture.personnelCode));
    }

    @Test
    void publishesTheRecordedValuesWithTheirLabelsAndUnits() throws Exception {
        JsonNode detail = json(mockMvc.perform(get(BASE + "/" + fixture.submitted)
                .header("X-API-Key", apiKey)).andReturn());

        JsonNode temp = valueNamed(detail, "temp");
        assertThat(temp.get("value").asDouble()).isEqualTo(72.5);
        assertThat(temp.get("unit").asText()).isEqualTo("°C");
        assertThat(temp.get("dataType").asText()).isEqualTo("number");
    }

    @Test
    void aParameterTheOperatorLeftBlankIsPublishedAsNullRatherThanOmitted() throws Exception {
        // Driven by the frozen schema, not by the keys present in form_data — a missing reading
        // is information, and it disappears entirely if the map drives the loop.
        JsonNode detail = json(mockMvc.perform(get(BASE + "/" + fixture.submitted)
                .header("X-API-Key", apiKey)).andReturn());

        JsonNode pressure = valueNamed(detail, "pressure");
        assertThat(pressure).isNotNull();
        assertThat(pressure.get("value").isNull()).isTrue();
    }

    @Test
    void anAttachmentIsAnnouncedByIdAndNeverAsBytes() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/" + fixture.submitted)
                .header("X-API-Key", apiKey)).andReturn();
        JsonNode photo = valueNamed(json(result), "photo");

        assertThat(photo.get("value").isNull()).as("the reading itself is the attachment").isTrue();
        assertThat(photo.get("attachments")).hasSize(1);
        JsonNode attachment = photo.get("attachments").get(0);
        assertThat(attachment.get("id").asText()).isEqualTo(fixture.attachmentId);
        assertThat(attachment.get("kind").asText()).isEqualTo("IMAGE");
        assertThat(attachment.get("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(attachment.get("sizeBytes").asLong()).isEqualTo(2048L);

        // Nothing resembling file content, and no field that could carry it.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("storageKey").doesNotContain("data:image").doesNotContain("base64");
    }

    @Test
    void theDetailLeaksNoInternalField() throws Exception {
        String body = mockMvc.perform(get(BASE + "/" + fixture.submitted).header("X-API-Key", apiKey))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("syncStatus")
                .doesNotContain("nfcSerial")
                .doesNotContain("storageKey")
                .doesNotContain("filledByUserId")
                .doesNotContain("createdByUserId")
                .doesNotContain("passwordHash")
                .doesNotContain("nationalCode");
    }

    @Test
    void anInFlightSheetIsNotFoundThroughTheDetailEndpoint() throws Exception {
        // The same answer as a nonexistent id, deliberately: distinguishing them would turn the
        // endpoint into a probe for which ids are live work.
        mockMvc.perform(get(BASE + "/" + fixture.inProgress).header("X-API-Key", apiKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(get(BASE + "/99999999").header("X-API-Key", apiKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void theDetailEndpointStillRequiresAKey() throws Exception {
        mockMvc.perform(get(BASE + "/" + fixture.submitted))
                .andExpect(status().isUnauthorized());
    }

    // ── Usage auditing ───────────────────────────────────────────────────────

    @Test
    void aServedRequestIsRecordedWithItsClientAndRowCount() throws Exception {
        // The query is inline rather than built with .param(...) on purpose: MockMvc's
        // .param() populates getParameter() but leaves getQueryString() null, so a usage row
        // asserted through .param() would report an empty filter list and the test would pass
        // against a recorder that never captured one.
        mockMvc.perform(get(BASE + "?from=2000-01-01&to=2100-01-01").header("X-API-Key", apiKey))
                .andExpect(status().isOk());

        ApiKeyUsage usage = awaitUsage(u -> u.getOutcome() == ApiKeyUsageOutcome.OK);

        assertThat(usage.getApiKeyId()).isEqualTo(apiKeyId);
        assertThat(usage.getClientName()).isNotBlank();
        assertThat(usage.getMethod()).isEqualTo("GET");
        assertThat(usage.getPath()).isEqualTo(BASE);
        assertThat(usage.getStatusCode()).isEqualTo(200);
        assertThat(usage.getResultCount()).isNotNull();
        assertThat(usage.getRequestedAt()).isPositive();
        assertThat(usage.getQueryString()).contains("from=");
    }

    @Test
    void aRefusedRequestIsRecordedToo() throws Exception {
        // The rows worth having: a run of these from one address is the only evidence anybody
        // gets that somebody is guessing keys.
        mockMvc.perform(get(BASE).param("from", "2020-01-01").param("to", "2100-01-01"))
                .andExpect(status().isUnauthorized());

        ApiKeyUsage usage = awaitAnyUsage(u -> u.getOutcome() == ApiKeyUsageOutcome.MISSING_KEY
                && BASE.equals(u.getPath()));
        assertThat(usage.getStatusCode()).isEqualTo(401);
        cleanups.add(() -> apiKeyUsageRepository.deleteById(usage.getId()));
    }

    @Test
    void aRefusedKeyIsRecordedWithTheRealReasonEvenThoughTheCallerIsNotToldIt() throws Exception {
        apiKeyService.setActive(apiKeyId, false, null);
        callList().andExpect(status().isUnauthorized())
                // The caller learns only "unauthorized" …
                .andExpect(jsonPath("$.error").value("unauthorized"));

        // … while the administrator can see it was a disabled key and not a guess.
        ApiKeyUsage usage = awaitUsage(u -> u.getOutcome() == ApiKeyUsageOutcome.DISABLED_KEY);
        assertThat(usage.getApiKeyId()).isEqualTo(apiKeyId);
    }

    @Test
    void theUsageRowNeverCarriesTheKeyItself() throws Exception {
        mockMvc.perform(get(BASE + "?from=2000-01-01&to=2100-01-01").header("X-API-Key", apiKey))
                .andExpect(status().isOk());
        ApiKeyUsage usage = awaitUsage(u -> u.getOutcome() == ApiKeyUsageOutcome.OK);

        // The key travels in a header, so it is structurally impossible for it to reach the
        // query string. This pins that, because "put it in a query param for convenience" is
        // the change that would break it.
        assertThat(usage.getQueryString() == null ? "" : usage.getQueryString()).doesNotContain(apiKey);
        assertThat(usage.getPath()).doesNotContain(apiKey);
    }

    @Test
    void aBadRequestIsRecordedAsSuchAndNotAsAnAuthFailure() throws Exception {
        mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "nonsense").param("to", "2100-01-01"))
                .andExpect(status().isBadRequest());

        ApiKeyUsage usage = awaitUsage(u -> u.getOutcome() == ApiKeyUsageOutcome.BAD_REQUEST);
        assertThat(usage.getStatusCode()).isEqualTo(400);
    }

    @Test
    void usingAKeyStampsItsLastUsedAt() throws Exception {
        assertThat(apiKeyRepository.findById(apiKeyId).orElseThrow().getLastUsedAt()).isNull();

        callList().andExpect(status().isOk());

        assertThat(apiKeyRepository.findById(apiKeyId).orElseThrow().getLastUsedAt()).isNotNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions callList() throws Exception {
        return mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                .param("from", "2000-01-01").param("to", "2100-01-01"));
    }

    private MvcResult list(String from, String to) throws Exception {
        return mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", from).param("to", to))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult list(String from, String to, String statuses) throws Exception {
        return mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", from).param("to", to).param("statuses", statuses)
                        .param("size", "200"))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult page(int page, int size) throws Exception {
        return mockMvc.perform(get(BASE).header("X-API-Key", apiKey)
                        .param("from", "2000-01-01").param("to", "2100-01-01")
                        .param("statuses", "SUBMITTED")
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> idsFrom(MvcResult result) throws Exception {
        List<Long> ids = new ArrayList<>();
        json(result).get("items").forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    private JsonNode itemFor(Long sheetId, MvcResult result) throws Exception {
        for (JsonNode item : json(result).get("items")) {
            if (item.get("id").asLong() == sheetId) {
                return item;
            }
        }
        throw new AssertionError("log sheet " + sheetId + " not in the response");
    }

    private static JsonNode valueNamed(JsonNode detail, String key) {
        for (JsonNode value : detail.get("assets").get(0).get("values")) {
            if (key.equals(value.get("key").asText())) {
                return value;
            }
        }
        throw new AssertionError("no value published for '" + key + "'");
    }

    private String loginToken() throws Exception {
        MvcResult login = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    /** Usage rows are written on another thread; wait briefly rather than racing them. */
    private ApiKeyUsage awaitUsage(java.util.function.Predicate<ApiKeyUsage> matcher) {
        return awaitAnyUsage(u -> apiKeyId.equals(u.getApiKeyId()) && matcher.test(u));
    }

    private ApiKeyUsage awaitAnyUsage(java.util.function.Predicate<ApiKeyUsage> matcher) {
        for (int attempt = 0; attempt < 100; attempt++) {
            Optional<ApiKeyUsage> found = apiKeyUsageRepository.findAll().stream()
                    .filter(matcher)
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no matching api_key_usage row was written within 5s");
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private record Fixture(Long unitId, String unitCode, Long otherUnitId,
                           Long assetId, String assetCode, String className, String nfcTagId,
                           String username, String personnelCode, String attachmentId,
                           Long submitted, Long submittedOtherUnit, Long voided,
                           Long expired, Long pending, Long inProgress) {}

    private Fixture seedFixture() {
        long now = System.currentTimeMillis();
        String suffix = String.valueOf(now) + UUID.randomUUID().toString().substring(0, 6);

        User operator = new User();
        operator.setUsername("int-op-" + suffix);
        operator.setFullName("اپراتور یکپارچه‌سازی");
        operator.setPersonnelCode("PC-" + suffix);
        // NOT NULL in the schema. This operator never logs in — it exists to be the person a
        // completed sheet points at — but the column still has to hold something.
        operator.setPasswordHash(passwordEncoder.encode("not-used-" + suffix));
        operator.setActive(true);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        operator = userRepository.save(operator);
        final Long operatorId = operator.getId();

        OperationalUnit unit = saveUnit("IU-" + suffix, "واحد یکپارچه‌سازی", now);
        OperationalUnit otherUnit = saveUnit("IU2-" + suffix, "واحد دیگر", now);

        Location location = new Location();
        location.setCode("ILOC-" + suffix);
        location.setName("Integration Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.save(location);
        final Long locationId = location.getId();

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("ISF-" + suffix);
        subFunction.setName("Integration Sub");
        subFunction.setTag("NFC-INT-" + suffix);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);
        final Long subFunctionId = subFunction.getId();

        AssetClass assetClass = new AssetClass();
        assetClass.setName("پمپ یکپارچه‌سازی " + suffix);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.save(assetClass);
        final Long classId = assetClass.getId();

        List<FieldDefinition> definitions = List.of(
                saveField(classId, "temp", "دما", "number", "°C", 1, now),
                saveField(classId, "pressure", "فشار", "number", "bar", 2, now),
                saveField(classId, "photo", "عکس", "image", null, 3, now));

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("IAST-" + suffix);
        asset.setAssetName("Integration Pump");
        asset.setClassId(classId);
        asset.setSubFunctionId(subFunction.getId());
        asset.setNfcTagId("NFC-INT-" + suffix);
        // Must never appear in a response: it is an anti-cloning control.
        asset.setNfcSerial("04:11:22:33:44:55");
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.save(asset);
        final Long assetId = asset.getId();

        List<FieldDefinitionSnapshot> snapshot = definitions.stream()
                .map(FieldDefinitionSnapshot::from)
                .toList();

        String attachmentId = UUID.randomUUID().toString();

        Long submitted = saveSheet(unit.getId(), LogSheetStatus.SUBMITTED, snapshot, now, s -> {
            s.setCompletedAt(now - 60_000L);
            s.setSubmittedAt(now - 30_000L);
            s.setCompletedByUserId(operatorId);
        });
        Long submittedOther = saveSheet(otherUnit.getId(), LogSheetStatus.SUBMITTED, snapshot, now, s -> {
            s.setCompletedAt(now - 55_000L);
            s.setSubmittedAt(now - 25_000L);
        });
        Long voided = saveSheet(unit.getId(), LogSheetStatus.VOIDED, snapshot, now, s -> {
            s.setCompletedAt(now - 50_000L);
            s.setSubmittedAt(now - 20_000L);
        });
        // No completedAt at all — the row that proves the window falls through to expiredAt.
        Long expired = saveSheet(unit.getId(), LogSheetStatus.EXPIRED, snapshot, now,
                s -> s.setExpiredAt(now - 40_000L));
        Long pending = saveSheet(unit.getId(), LogSheetStatus.PENDING, snapshot, now, s -> {});
        Long inProgress = saveSheet(unit.getId(), LogSheetStatus.IN_PROGRESS, snapshot, now, s -> {});

        Map<String, Object> formData = new LinkedHashMap<>();
        formData.put("temp", 72.5);
        // "pressure" deliberately absent — an unanswered parameter.
        formData.put("photo", AttachmentReferences.toValue(List.of(attachmentId)));

        for (Long sheetId : List.of(submitted, submittedOther, voided, expired, pending, inProgress)) {
            LogSheetEntry entry = new LogSheetEntry();
            entry.setLogSheetId(sheetId);
            entry.setAssetId(assetId);
            entry.setAssetName("Integration Pump");
            entry.setSubFunctionCode("ISF-" + suffix);
            entry.setSubFunctionTag("NFC-INT-" + suffix);
            entry.setNfcTagId("NFC-INT-" + suffix);
            entry.setNfcSerial("04:11:22:33:44:55");
            entry.setClassId(classId);
            entry.setFormData(formData);
            entry.setMaxSeverity("OK");
            entry.setEntrySource(LogSheetEntrySource.PWA_NFC);
            entry.setFilledByUserId(operatorId);
            entry.setCreatedAt(now - 70_000L);
            entry.setUpdatedAt(now - 65_000L);
            logSheetEntryRepository.save(entry);
        }

        Attachment attachment = new Attachment();
        attachment.setId(attachmentId);
        attachment.setLogSheetId(submitted);
        attachment.setAssetId(assetId);
        attachment.setFieldKey("photo");
        attachment.setKind(AttachmentKind.IMAGE);
        attachment.setMimeType("image/jpeg");
        attachment.setSizeBytes(2048L);
        attachment.setStorageKey("integration-test/" + attachmentId + ".jpg");
        attachment.setUploadedAt(now - 62_000L);
        attachment.setCreatedByUserId(operatorId);
        attachmentRepository.save(attachment);

        List<Long> sheetIds = List.of(submitted, submittedOther, voided, expired, pending, inProgress);
        final Long finalOperatorId = operatorId;
        cleanups.add(() -> {
            attachmentRepository.deleteById(attachmentId);
            sheetIds.forEach(id ->
                    logSheetEntryRepository.deleteAll(logSheetEntryRepository.findByLogSheetId(id)));
            sheetIds.forEach(logSheetRepository::deleteById);
            assetEntryRepository.deleteById(assetId);
            definitions.forEach(fieldDefinitionRepository::delete);
            assetClassRepository.deleteById(classId);
            subFunctionRepository.deleteById(subFunctionId);
            locationRepository.deleteById(locationId);
            operationalUnitRepository.deleteById(otherUnit.getId());
            operationalUnitRepository.deleteById(unit.getId());
            userRepository.deleteById(finalOperatorId);
        });

        return new Fixture(unit.getId(), unit.getCode(), otherUnit.getId(),
                assetId, "IAST-" + suffix, assetClass.getName(), "NFC-INT-" + suffix,
                operator.getUsername(), operator.getPersonnelCode(), attachmentId,
                submitted, submittedOther, voided, expired, pending, inProgress);
    }

    private OperationalUnit saveUnit(String code, String name, long now) {
        OperationalUnit unit = new OperationalUnit();
        unit.setCode(code);
        unit.setName(name);
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        return operationalUnitRepository.save(unit);
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

    private Long saveSheet(Long unitId, LogSheetStatus status,
                           List<FieldDefinitionSnapshot> snapshot, long now,
                           java.util.function.Consumer<LogSheet> customise) {
        LogSheet sheet = new LogSheet();
        sheet.setTemplateName("Integration Round");
        sheet.setScopeSummary("integration test");
        sheet.setOperationalUnitId(unitId);
        sheet.setStatus(status);
        sheet.setOrigin(GenerationMode.MANUAL);
        sheet.setFieldDefinitionsSnapshot(snapshot);
        sheet.setSyncStatus("SYNCED");
        sheet.setCreatedAt(now - 100_000L);
        sheet.setUpdatedAt(now);
        customise.accept(sheet);
        return logSheetRepository.save(sheet).getId();
    }
}
