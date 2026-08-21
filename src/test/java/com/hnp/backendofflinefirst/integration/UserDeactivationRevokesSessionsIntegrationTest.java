package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.service.UserService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deactivating an account cuts its access off immediately; changing its roles does not.
 *
 * <h2>What was wrong</h2>
 *
 * <p>A mobile token carries the user's roles and permissions as claims, {@code JwtService}
 * rebuilds the principal from those claims with {@code active = true} hard-coded, and the
 * per-request gate only asks whether the token's {@code jti} still has a live
 * {@code api_sessions} row. Nothing consulted the account. So a deactivated user went on
 * working with their full previous access for the remaining life of their token — up to
 * {@code auth.jwt.expiry_minutes}, 8 hours by default — unless an administrator happened to
 * know they also had to visit {@code /api-sessions} and revoke by hand.
 *
 * <h2>What is deliberately unchanged</h2>
 *
 * <p>A role edit still does not close sessions. Roles are edited routinely, and logging every
 * affected operator out of their tablet mid-round — on a fleet that is offline-first precisely
 * because reconnecting is not always possible — would make the system hostile to administer.
 * The page warns instead, and that warning is asserted here too: an undocumented gap and a
 * documented one are very different things.
 *
 * <p>Not {@code @Transactional}: the API session revocation has to be committed and re-read
 * through a real request to prove the filter rejects the token.
 */
class UserDeactivationRevokesSessionsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "op-secret-12345";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserService userService;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired RoleRepository roleRepository;

    MockMvc mockMvc;

    private User operator;
    private Long operatorRoleId;
    private Long supervisorRoleId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        operatorRoleId = roleRepository.findByCode("OPERATOR").orElseThrow().getId();
        supervisorRoleId = roleRepository.findByCode("SUPERVISOR").orElseThrow().getId();
        String suffix = String.valueOf(System.nanoTime());
        operator = userService.create("revoke-op-" + suffix, "اپراتور آزمون", "PC-" + suffix, null,
                null, null, null, null, null,
                PASSWORD, UserAuthType.LOCAL, true, List.of(operatorRoleId));
    }

    @AfterEach
    void tearDown() {
        if (operator != null) {
            apiSessionRepository.deleteAll(apiSessionRepository.findAll().stream()
                    .filter(s -> operator.getId().equals(s.getUserId()))
                    .toList());
            try {
                userService.delete(operator.getId());
            } catch (RuntimeException ignored) {
                // Already gone in the delete test, or blocked — neither should mask a failure.
            }
            operator = null;
        }
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", operator.getUsername(), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private void update(boolean active, List<Long> roleIds) {
        userService.update(operator.getId(), operator.getUsername(), operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, active, roleIds);
    }

    // ── The fix ──────────────────────────────────────────────────────────────

    @Test
    void aDeactivatedUsersTokenStopsWorkingOnTheVeryNextRequest() throws Exception {
        String token = login();
        // Proof the token was genuinely usable first — otherwise the assertion below could pass
        // against a token that never worked at all.
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        update(false, List.of(operatorRoleId));

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivationRecordsWhyTheSessionWasRevoked() throws Exception {
        login();

        update(false, List.of(operatorRoleId));

        List<ApiSession> sessions = apiSessionRepository.findAll().stream()
                .filter(s -> operator.getId().equals(s.getUserId()))
                .toList();
        assertThat(sessions).isNotEmpty();
        assertThat(sessions).allSatisfy(session -> {
            assertThat(session.getRevokedAt()).isNotNull();
            // Not ADMIN: every rejected request looks identical to the device, so this column is
            // the only place that can distinguish a manual revoke from a deactivation.
            assertThat(session.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.USER_DEACTIVATED);
        });
    }

    @Test
    void theOutcomeReportsWhatWasClosed() throws Exception {
        login();

        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), operator.getUsername(), operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, false, List.of(operatorRoleId));

        assertThat(outcome.deactivated()).isTrue();
        assertThat(outcome.revokedApiSessions()).isEqualTo(1);
        assertThat(outcome.needsSessionWarning())
                .as("a deactivation needs no warning — it already took effect")
                .isFalse();
    }

    @Test
    void deletingAUserAlsoKillsTheirToken() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        userService.delete(operator.getId());

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        assertThat(apiSessionRepository.findAll().stream()
                .filter(s -> operator.getId().equals(s.getUserId())))
                .allSatisfy(s -> assertThat(s.getRevokeReason())
                        .isEqualTo(ApiSessionRevokeReason.USER_DELETED));
        operator = null; // already deleted; nothing for teardown to do
    }

    // ── Rename, which is where the first version of this got it wrong ────────

    /**
     * The scenario the first fix missed entirely: rename and deactivate in the <b>same</b> edit.
     *
     * <p>Sessions were looked up by username, and the lookup ran after the entity had already
     * been renamed — so it searched under the new name, matched nothing, and left the old
     * session live. The comment above the call even claimed the opposite. Sessions are found by
     * user id now, which a rename cannot move.
     */
    @Test
    void renamingAndDeactivatingInOneEditStillClosesTheSession() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        String renamed = operator.getUsername() + "-renamed";
        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), renamed, operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, false, List.of(operatorRoleId));

        assertThat(outcome.deactivated()).isTrue();
        assertThat(outcome.revokedApiSessions())
                .as("the mobile session must be closed even though the name changed in the same edit")
                .isEqualTo(1);
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        operator.setUsername(renamed); // so teardown finds the right row
    }

    /** Rename in one edit, deactivate in a later one — the same trap, a step apart. */
    @Test
    void deactivatingAfterAnEarlierRenameStillClosesTheSession() throws Exception {
        String renamed = operator.getUsername() + "-renamed";
        userService.update(operator.getId(), renamed, operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of(operatorRoleId));
        operator.setUsername(renamed);

        String token = login();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), renamed, operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, false, List.of(operatorRoleId));

        assertThat(outcome.revokedApiSessions()).isEqualTo(1);
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** And deleting after a rename. */
    @Test
    void deletingAfterAnEarlierRenameStillClosesTheSession() throws Exception {
        String renamed = operator.getUsername() + "-renamed";
        userService.update(operator.getId(), renamed, operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of(operatorRoleId));
        operator.setUsername(renamed);

        String token = login();
        Long id = operator.getId();
        userService.delete(id);
        operator = null; // gone; nothing for teardown to do

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // The rows are gone rather than revoked: api_sessions.user_id is ON DELETE CASCADE, so
        // deleting the user takes its sessions with it. The token is dead either way — the
        // filter needs a live row and there is none — which is what the 401 above proves. The
        // explicit revoke in UserService.delete is belt-and-braces for the day that FK changes.
        assertThat(apiSessionRepository.findAll().stream()
                .filter(s -> id.equals(s.getUserId())))
                .isEmpty();
    }

    /**
     * A rename alone changes nothing about access, so it must not close anything.
     *
     * <p>The opposite failure of the one above, and just as unwelcome: an administrator fixing a
     * typo in somebody's username should not log them out of a round.
     */
    @Test
    void aRenameOnItsOwnClosesNothing() throws Exception {
        String token = login();

        String renamed = operator.getUsername() + "-typo-fixed";
        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), renamed, operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of(operatorRoleId));
        operator.setUsername(renamed);

        assertThat(outcome.deactivated()).isFalse();
        assertThat(outcome.revokedApiSessions()).isZero();
        assertThat(outcome.rolesChanged()).isFalse();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ── The deliberate non-change ────────────────────────────────────────────

    /**
     * A role change keeps the tablet logged in <b>and</b> gives it the new access at once.
     *
     * <p>These used to be alternatives. Authorities came from the token's claims, so the only way
     * to apply a role change to a live device was to revoke its session and make somebody log in
     * again mid-round — which this system deliberately would not do, and therefore left the
     * change waiting until the token expired. Resolving authorities from the database per request
     * removes the choice: the session survives, and the permissions are current.
     */
    @Test
    void aRoleChangeLeavesTheSessionOpenAndSaysSo() throws Exception {
        String token = login();

        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), operator.getUsername(), operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of(supervisorRoleId));

        assertThat(outcome.rolesChanged()).isTrue();
        assertThat(outcome.deactivated()).isFalse();
        assertThat(outcome.revokedApiSessions()).isZero();
        assertThat(outcome.needsSessionWarning())
                .as("the administrator must be told the browser still holds the old access")
                .isTrue();

        // The session survives the edit — this is the documented behaviour, not an accident.
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // And the token now carries the NEW role's access, with no new login. SUPERVISOR holds
        // POST:/api/log-sheets/{id}/assign and OPERATOR does not, and that @PreAuthorize is the
        // only thing between this empty body and a 400 — so 400 means the authority is there,
        // and it can only have come from the database.
        mockMvc.perform(post("/api/log-sheets/1/assign")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** The mirror image: an access taken away is gone on the next request, not at expiry. */
    @Test
    void aRoleChangeThatNarrowsAccessAppliesToTheOpenSessionImmediately() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // No roles at all: the account stays active and its session stays open.
        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), operator.getUsername(), operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of());

        assertThat(outcome.revokedApiSessions())
                .as("narrowing access does not close the session either")
                .isZero();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEditThatChangesNeitherRolesNorActiveStateWarnsAboutNothing() throws Exception {
        login();

        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), operator.getUsername(), "نام تازه",
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of(operatorRoleId));

        assertThat(outcome.rolesChanged()).isFalse();
        assertThat(outcome.deactivated()).isFalse();
        assertThat(outcome.needsSessionWarning()).isFalse();
    }

    @Test
    void reactivatingAUserDoesNotRevokeAnything() throws Exception {
        update(false, List.of(operatorRoleId));

        UserService.UserUpdateOutcome outcome = userService.update(
                operator.getId(), operator.getUsername(), operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, true, List.of(operatorRoleId));

        assertThat(outcome.deactivated())
                .as("going inactive → active is not a deactivation")
                .isFalse();
        assertThat(outcome.revokedApiSessions()).isZero();
    }

    @Test
    void deactivatingAnAlreadyInactiveUserRevokesNothingTwice() throws Exception {
        login();
        update(false, List.of(operatorRoleId));

        UserService.UserUpdateOutcome second = userService.update(
                operator.getId(), operator.getUsername(), operator.getFullName(),
                operator.getPersonnelCode(), null, null, null, null, null, null,
                UserAuthType.LOCAL, false, List.of(operatorRoleId));

        assertThat(second.deactivated()).isFalse();
        assertThat(second.revokedApiSessions()).isZero();
    }
}
