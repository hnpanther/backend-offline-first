package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.FormSubmissionRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.service.FormCatalogService;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormHistoryService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@WithAppUser(roles = "ADMIN", authorities = {"CAP:SCOPE_PLANT_WIDE", "CAP:SUPERVISE_ANY_UNIT"})
class FormSubmissionConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired FormSubmissionService service;
    @Autowired FormHistoryService history;
    @Autowired FormSubmissionRepository submissions;
    @Autowired OperationalUnitRepository units;
    @Autowired FormDefinitionService definitions;
    @Autowired FormCatalogService catalog;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired FormFieldService fields;
    @Autowired TransactionTemplate transactions;

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeCommittedFixtures() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM form_submissions WHERE form_definition_id IN (SELECT id FROM form_definitions WHERE code ILIKE ?)", localId + "%");
            jdbc.update("DELETE FROM form_definitions WHERE code ILIKE ?", localId + "%");
            if (unitId != null) units.deleteById(unitId);
        });
    }

    @Value("${local.server.port}") int port;

    private Long unitId;
    private Long definitionId;
    private final String localId = UUID.randomUUID().toString();

    @BeforeEach
    void prepare() {
        var unit = new OperationalUnit();
        unit.setCode("race-" + localId);
        unit.setName("Race unit");
        unit.setCreatedAt(1L);
        unit.setUpdatedAt(1L);
        unitId = units.save(unit).getId();
        definitionId = definitions.create(localId, "Concurrent form", null, List.of(unitId), true).getId();
        fields.create(definitionId, "note", "Note", "text", null, false, Map.of(), 1);
    }

    @Test
    void simultaneousEditsOfTheSameDisplayedVersionHaveExactlyOneWinner() throws Exception {
        var first = save("original", 0L);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(editAfterRead("panel", first, barrier));
            var b = pool.submit(editAfterRead("tablet", first, barrier));
            var left = a.get(15, TimeUnit.SECONDS);
            var right = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(left.getOutcome(), right.getOutcome())).containsExactlyInAnyOrder("OK", "CONFLICT");
            var stored = submissions.findById(first.getSubmissionId()).orElseThrow();
            assertThat(stored.getFormData()).containsEntry("note", left.getOutcome().equals("OK") ? "panel" : "tablet");
            assertThat(stored.getUpdatedAt()).isGreaterThan(first.getUpdatedAt());
        }
    }

    private Callable<FormSubmitResult> editAfterRead(String answer, FormSubmitResult first, CyclicBarrier barrier) {
        return new DelegatingSecurityContextCallable<>(() -> transactions.execute(status -> {
            submissions.findById(first.getSubmissionId()).orElseThrow();
            await(barrier);
            return save(answer, first.getUpdatedAt());
        }));
    }

    @Test
    void simultaneousFirstDeliveriesCannotCreateTwoRowsOrOverwriteTheWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<FormSubmitResult> create = () -> {
                await(barrier);
                return save("offline", 0L);
            };
            var a = pool.submit(new DelegatingSecurityContextCallable<>(create));
            var b = pool.submit(new DelegatingSecurityContextCallable<>(create));
            var left = a.get(15, TimeUnit.SECONDS);
            var right = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(left.getOutcome(), right.getOutcome())).containsExactlyInAnyOrder("OK", "CONFLICT");
            assertThat(left.getSubmissionId()).isEqualTo(right.getSubmissionId());
        }
    }

    @Test
    void lostDraftAcknowledgementCanBeRetriedAndAnEarlierFinalCannotReplaceANewerDraft() {
        FormDraftRequest first = draft("original", 0L);
        var accepted = service.upsertDraft(1L, first, ActionSource.WEB);
        assertThat(service.upsertDraft(1L, first, ActionSource.WEB).getUpdatedAt()).isEqualTo(accepted.getUpdatedAt());
        assertThat(save("newer", accepted.getUpdatedAt()).getOutcome()).isEqualTo("OK");
        var finish = new FormSubmitRequest();
        finish.setClientActionId(UUID.randomUUID().toString());
        finish.setSubmissionId(accepted.getSubmissionId());
        finish.setExpectedUpdatedAt(accepted.getUpdatedAt());
        finish.setFormData(Map.of("note", "outdated"));
        assertThat(service.submitFinal(1L, finish, ActionSource.WEB).getOutcome()).isEqualTo("CONFLICT");
        assertThat(submissions.findById(accepted.getSubmissionId()).orElseThrow().getFormData())
                .containsEntry("note", "newer");
    }

    @Test
    void finalReceiptsSurviveReopenAndIdentifyOneAuthorTargetAndCycle() throws Exception {
        var first = save("original", 0L);
        var submit = new FormSubmitRequest();
        submit.setSubmissionId(first.getSubmissionId());
        submit.setClientActionId(UUID.randomUUID().toString());
        submit.setExpectedUpdatedAt(first.getUpdatedAt());
        submit.setFormData(Map.of("note", "first"));
        var barrier = new CyclicBarrier(2);
        Callable<FormSubmitResult> send = () -> {
            await(barrier);
            return service.submitFinal(1L, submit, ActionSource.WEB);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(new DelegatingSecurityContextCallable<>(send));
            var b = pool.submit(new DelegatingSecurityContextCallable<>(send));
            assertThat(List.of(a.get(15, TimeUnit.SECONDS).getOutcome(), b.get(15, TimeUnit.SECONDS).getOutcome()))
                    .containsExactlyInAnyOrder("OK", "DUPLICATE");
        }
        var refused = service.submitFinal(2L, submit, ActionSource.WEB);
        assertThat(refused.getOutcome()).isEqualTo("DENIED");
        assertThat(refused.getSubmissionId()).isNull();
        submit.setSubmissionId(-1L);
        assertThat(service.submitFinal(1L, submit, ActionSource.WEB).getOutcome()).isEqualTo("DENIED");
        submit.setSubmissionId(first.getSubmissionId());
        var reopened = service.reopen(first.getSubmissionId(), 1L, ActionSource.WEB, "Correction");
        assertThat(service.submitFinal(1L, submit, ActionSource.WEB).getOutcome()).isEqualTo("CONFLICT");
        String oldKey = submit.getClientActionId();
        submit.setClientActionId(UUID.randomUUID().toString());
        submit.setExpectedUpdatedAt(null);
        assertThat(service.submitFinal(1L, submit, ActionSource.MOBILE).getOutcome()).isEqualTo("CONFLICT");
        submit.setExpectedUpdatedAt(reopened.getUpdatedAt());
        submit.setFormData(Map.of("note", "corrected"));
        assertThat(service.submitFinal(1L, submit, ActionSource.WEB).getOutcome()).isEqualTo("OK");
        submit.setClientActionId(oldKey);
        assertThat(service.submitFinal(1L, submit, ActionSource.WEB).getOutcome()).isEqualTo("CONFLICT");
        assertThat(submissions.findById(first.getSubmissionId()).orElseThrow().getFormData()).containsEntry("note", "corrected");
    }

    @Test
    void historyPagesResumeAtTheFenceAndDeliverUpdatesMadeBetweenPages() {
        long start = submissions.historyHead(1L);
        var first = save("first", 0L);
        var other = draft("second", 0L);
        other.setLocalId(UUID.randomUUID().toString());
        var second = service.upsertDraft(1L, other, ActionSource.WEB);
        var page = history.historyPage(1L, start, null, 0, 1);
        assertThat(page.items()).extracting("id").containsExactly(first.getSubmissionId());
        assertThat(page.hasMore()).isTrue();
        save("edited", first.getUpdatedAt());
        var last = history.historyPage(1L, page.next(), page.through(), 0, 1);
        assertThat(last.items()).extracting("id").containsExactly(second.getSubmissionId());
        assertThat(last.hasMore()).isFalse();
        var delta = history.historyPage(1L, last.next(), null, 0, 1);
        assertThat(delta.items()).hasSize(1);
        assertThat(delta.items().getFirst().getFormData()).containsEntry("note", "edited");
        assertThat(history.historyPage(1L, delta.next(), null, 0, 1).items()).isEmpty();
        assertThat(history.historyPage(999999L, 0, null, 0, 1).items()).isEmpty();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> history.historyPage(1L, 0, null, 0, 101));
    }

    @Test
    void historyEndpointsKeepTheLegacyArrayAndReturnTheDeltaOverAuthenticatedHttp() throws Exception {
        long start = submissions.historyHead(1L);
        var first = save("original", 0L);
        var other = draft("second", 0L);
        other.setLocalId(UUID.randomUUID().toString());
        var second = service.upsertDraft(1L, other, ActionSource.WEB);
        var mapper = new ObjectMapper();
        var root = URI.create("http://localhost:" + port);
        try (var http = HttpClient.newHttpClient()) {
            var login = HttpRequest.newBuilder(root.resolve("/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("username", "admin", "password", "admin123")))).build();
            var loginResponse = http.send(login, HttpResponse.BodyHandlers.ofString());
            assertThat(loginResponse.statusCode()).isEqualTo(200);
            String token = mapper.readTree(loginResponse.body()).get("accessToken").asText();
            var path = "/api/form-submissions";
            var legacy = HttpRequest.newBuilder(root.resolve(path)).header("Authorization", "Bearer " + token).build();
            var response = http.send(legacy, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var json = mapper.readTree(response.body());
            assertThat(json.isArray()).isTrue();
            assertThat(json.get(0).get("id").asLong()).isEqualTo(second.getSubmissionId());
            assertThat(json.get(1).get("id").asLong()).isEqualTo(first.getSubmissionId());
            var delta = HttpRequest.newBuilder(root.resolve(path + "/changes?size=1&after=" + start))
                    .header("Authorization", "Bearer " + token).build();
            var page = http.send(delta, HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);
            var data = mapper.readTree(page.body());
            assertThat(data.get("hasMore").asBoolean()).isTrue();
            assertThat(data.at("/items/0/id").asLong()).isEqualTo(first.getSubmissionId());
        }
    }

    @Test
    void warmCatalogReadsStayConstantAsFormsAreAdded() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            catalog.fillable(1L, null);
            long before = statistics.getPrepareStatementCount();
            var baseline = catalog.fillable(1L, null);
            long queries = statistics.getPrepareStatementCount() - before;
            for (int i = 0; i < 3; i++) {
                definitions.create(localId + i, "Extra form " + i, null, List.of(unitId), true);
            }
            var published = catalog.fillable(1L, null);
            before = statistics.getPrepareStatementCount();
            var warm = catalog.fillable(1L, null);
            assertThat(warm).hasSize(baseline.size() + 3).isEqualTo(published);
            assertThat(statistics.getPrepareStatementCount() - before).isEqualTo(queries);
        } finally {
            statistics.setStatisticsEnabled(previous);
        }
    }

    private FormSubmitResult save(String answer, Long expected) {
        return service.upsertDraft(1L, draft(answer, expected), ActionSource.WEB);
    }

    private FormDraftRequest draft(String answer, Long expected) {
        var request = new FormDraftRequest();
        request.setExpectedUpdatedAt(expected);
        request.setFormDefinitionId(definitionId);
        request.setReporterUnitId(unitId);
        request.setLocalId(localId);
        request.setClientActionId(UUID.randomUUID().toString());
        request.setFormData(Map.of("note", answer));
        return request;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Competing transaction did not reach the barrier", e);
        }
    }
}
