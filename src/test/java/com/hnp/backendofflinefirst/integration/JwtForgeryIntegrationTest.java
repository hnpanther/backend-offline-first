package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.Permission;
import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.RolePermission;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.repository.PermissionRepository;
import com.hnp.backendofflinefirst.repository.RolePermissionRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What somebody holding the signing key can and cannot do.
 *
 * <h2>Why this is worth an integration test of its own</h2>
 *
 * <p>The signing key ships in {@code application.properties} as a working default, so "the
 * attacker knows the key" is not a hypothetical for this system — it is the state of any
 * deployment where nobody replaced it, and {@code ProductionReadinessRunner} warns about exactly
 * that on every boot. The tests below therefore <b>sign real tokens with the real key</b> and
 * send them over a real request, rather than asserting about intentions.
 *
 * <h2>What used to happen</h2>
 *
 * <p>Authorities came from {@code roles} and {@code perms} claims inside the token, and the only
 * server-side check was that the {@code jti} named a live session. Both halves were needed and
 * both were missing:
 *
 * <ul>
 *   <li>an operator could re-sign their own token with {@code perms} listing every endpoint in
 *       the system, and every {@code @PreAuthorize} would pass;</li>
 *   <li>or keep their own {@code jti} — genuinely live — and change {@code uid} to somebody
 *       else's, because nothing checked that the session belonged to the user named.</li>
 * </ul>
 *
 * <p>Now the token carries identity only, the {@code jti} is bound to the {@code uid}, and the
 * authorities are read from the database per request. The consequence, which the tests here
 * state one at a time, is that a leaked key buys nothing: every token its holder can mint is a
 * token for themselves, with exactly the permissions they already had.
 *
 * <p>Not {@code @Transactional} — the session registry and the permission grants have to be
 * committed for a second request to see them, which is the whole point.
 */
class JwtForgeryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "forgery-pass-1";

    /**
     * An endpoint the seeded OPERATOR role does not carry, whose {@code @PreAuthorize} is the
     * only thing between the request and a 400.
     *
     * <p>Chosen so the two outcomes are unambiguous: <b>403</b> means the authority was missing,
     * <b>400</b> means it was present and the (deliberately empty) body was rejected instead.
     * An endpoint with a second access check of its own could not tell those apart.
     */
    private static final String PRIVILEGED_ENDPOINT = "/api/log-sheets/1/assign";
    private static final String PRIVILEGED_PERMISSION = "POST:/api/log-sheets/{id}/assign";

    /** An endpoint the OPERATOR role does carry, for the "still works" half of each check. */
    private static final String ALLOWED_ENDPOINT = "/api/bootstrap";

    @Value("${app.auth.jwt.secret}")
    String jwtSecret;

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;
    User operator;
    User bystander;
    Role ownRole;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ownRole = seedRoleCopyingOperator();
        operator = seedUser("forge-op", ownRole);
        bystander = seedUser("forge-by", ownRole);
    }

    @AfterEach
    void cleanUp() {
        List.of(operator, bystander).forEach(this::deleteUser);
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", ownRole.getId());
        roleRepository.deleteById(ownRole.getId());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The baseline both halves are measured against
    // ─────────────────────────────────────────────────────────────────────────

    /** A genuine login authenticates, and is authorised for exactly what its role holds. */
    @Test
    void aGenuineTokenIsAcceptedAndCarriesOnlyItsOwnPermissions() throws Exception {
        String token = login(operator);

        expect(get(ALLOWED_ENDPOINT), token, 200);
        expect(assignRequest(), token, 403);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forging authority
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The escalation, attempted exactly as it used to succeed: the operator's own live session,
     * their own id, and a {@code perms} claim listing the endpoint they are not allowed to call.
     */
    @Test
    void aForgedPermissionClaimGrantsNothing() throws Exception {
        login(operator);
        String forged = forge(operator.getId(), operator.getUsername(), liveJti(operator),
                Map.of("perms", List.of(PRIVILEGED_PERMISSION, "GET:/users", "POST:/users")));

        expect(get(ALLOWED_ENDPOINT), forged, 200);
        expect(assignRequest(), forged, 403);
    }

    /** The same through the {@code roles} claim, which is the other half of the old pair. */
    @Test
    void aForgedRolesClaimGrantsNothing() throws Exception {
        login(operator);
        String forged = forge(operator.getId(), operator.getUsername(), liveJti(operator),
                Map.of("roles", List.of("ADMIN", "HIGH_USER"),
                       "perms", List.of(PRIVILEGED_PERMISSION)));

        expect(assignRequest(), forged, 403);
    }

    /**
     * And the permissions really are the database's: granting the same authority to the user's
     * role makes the <em>unforged</em> token reach the endpoint.
     *
     * <p>Without this, every assertion above would also pass if the endpoint were simply
     * unreachable — a test that cannot distinguish "denied" from "broken" proves nothing.
     */
    @Test
    void theSameCallSucceedsOnceTheRoleActuallyHoldsThePermission() throws Exception {
        String token = login(operator);
        expect(assignRequest(), token, 403);

        grant(PRIVILEGED_PERMISSION);

        expect(assignRequest(), token, 400);
    }

    /**
     * A token in the OLD shape still authenticates — nobody has to log in again after the upgrade.
     *
     * <p>This is a deployment question, not a security one, and it is the reason the change can
     * ship without a fleet-wide re-login: tokens minted before the claims were removed still carry
     * `uid` and `jti`, which is everything the new path reads. Their `roles` and `perms` are
     * ignored rather than rejected, so a tablet mid-round keeps working and simply starts getting
     * its authorities from the database.
     *
     * <p>Rejecting them instead would be defensible and is the wrong trade here: it would log out
     * every device at once, offline ones included, and buy nothing — the claims are not read.
     */
    @Test
    void aTokenStillCarryingTheOldClaimsKeepsWorking() throws Exception {
        login(operator);
        String oldShape = forge(operator.getId(), operator.getUsername(), liveJti(operator),
                Map.of("roles", List.of("OPERATOR"),
                       "perms", List.of("GET:/api/bootstrap"),
                       "fullName", "Forgery Fixture"));

        expect(get(ALLOWED_ENDPOINT), oldShape, 200);
        expect(assignRequest(), oldShape, 403);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forging identity
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * <b>The jti binding.</b> The operator's own live session, re-signed with somebody else's id.
     *
     * <p>Every ingredient is genuine except the pairing: the signature verifies, the session
     * exists and is live, and the user named exists and is active. Only the fact that this
     * session was not issued to that user stands in the way — which is the check that was
     * missing.
     */
    @Test
    void aTokenPairingOwnSessionWithAnotherUsersIdIsRejected() throws Exception {
        login(operator);
        String forged = forge(bystander.getId(), bystander.getUsername(), liveJti(operator), Map.of());

        expect(get(ALLOWED_ENDPOINT), forged, 401);
    }

    /** And the reverse pairing, so the check is not accidentally one-directional. */
    @Test
    void aTokenPairingAnotherUsersSessionWithOwnIdIsRejected() throws Exception {
        login(operator);
        login(bystander);
        String forged = forge(operator.getId(), operator.getUsername(), liveJti(bystander), Map.of());

        expect(get(ALLOWED_ENDPOINT), forged, 401);
    }

    /** A session id that was never issued — the forger has the key but no login of their own. */
    @Test
    void aTokenWithAnInventedSessionIdIsRejected() throws Exception {
        String forged = forge(operator.getId(), operator.getUsername(),
                UUID.randomUUID().toString(), Map.of());

        expect(get(ALLOWED_ENDPOINT), forged, 401);
    }

    /** No {@code uid} at all: there is no user to resolve, so there is nobody to be. */
    @Test
    void aTokenWithNoUserIdClaimIsRejected() throws Exception {
        login(operator);
        String forged = signed(builder -> builder
                .id(liveJti(operator))
                .subject(operator.getUsername())
                .expiration(new Date(System.currentTimeMillis() + 600_000)));

        expect(get(ALLOWED_ENDPOINT), forged, 401);
    }

    /** Without the key none of this starts: a different signature is not a token. */
    @Test
    void aTokenSignedWithADifferentKeyIsRejected() throws Exception {
        login(operator);
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "another-secret-key-of-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .id(liveJti(operator))
                .subject(operator.getUsername())
                .claim(JwtService.CLAIM_UID, operator.getId())
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(wrongKey)
                .compact();

        expect(get(ALLOWED_ENDPOINT), forged, 401);
    }

    /**
     * A forged expiry does not outlive the session row.
     *
     * <p>Worth stating because it is the one thing the key genuinely buys: its holder can mint a
     * token that claims to be valid for a decade. The registry caps it anyway — {@code
     * api_sessions.expires_at} is what {@code isSessionActive} reads, and the JWT's own
     * {@code exp} is only ever able to make a token expire *earlier*.
     */
    @Test
    void aForgedExpiryCannotOutliveTheSessionRow() throws Exception {
        login(operator);
        String jti = liveJti(operator);
        String immortal = forge(operator.getId(), operator.getUsername(), jti,
                Map.of(), System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);

        expect(get(ALLOWED_ENDPOINT), immortal, 200);

        expireSessionRow(jti);

        expect(get(ALLOWED_ENDPOINT), immortal, 401);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Authorities are current, not frozen at login
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A permission granted after login is usable without a new token.
     *
     * <p>The security fix and this are the same change seen from two sides: because nothing is
     * read from the token, everything is read from the database, and the database is current.
     */
    @Test
    void aPermissionGrantedAfterLoginTakesEffectWithoutANewToken() throws Exception {
        String token = login(operator);
        expect(assignRequest(), token, 403);

        grant(PRIVILEGED_PERMISSION);

        expect(assignRequest(), token, 400);
    }

    /**
     * And a permission revoked after login stops working on the very next request.
     *
     * <p>This is the one that used to be a real operational problem rather than only a
     * theoretical one: taking an access away did nothing until the token expired, which could be
     * hours, and nothing in the UI said so.
     */
    @Test
    void aPermissionRevokedAfterLoginStopsWorkingOnTheNextRequest() throws Exception {
        grant(PRIVILEGED_PERMISSION);
        String token = login(operator);
        expect(assignRequest(), token, 400);

        revoke(PRIVILEGED_PERMISSION);

        expect(assignRequest(), token, 403);
    }

    /** Removing the user's role entirely leaves the token authenticated and unauthorised. */
    @Test
    void removingTheUsersRoleLeavesThemAuthenticatedWithNoAuthorities() throws Exception {
        String token = login(operator);
        expect(get(ALLOWED_ENDPOINT), token, 200);

        detachAllRoles(operator);

        expect(get(ALLOWED_ENDPOINT), token, 403);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The account behind the token
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deactivating the account rejects the token even if its session somehow survived.
     *
     * <p>Deactivation revokes sessions, so this is normally caught a step earlier. The row is
     * therefore deactivated directly here, to exercise the barrier that does not depend on the
     * revocation having run.
     */
    @Test
    void aTokenOfADeactivatedUserIsRejectedEvenWithALiveSession() throws Exception {
        String token = login(operator);
        expect(get(ALLOWED_ENDPOINT), token, 200);

        operator.setActive(false);
        userRepository.save(operator);

        expect(get(ALLOWED_ENDPOINT), token, 401);
    }

    /**
     * Renaming the account does not invalidate the token.
     *
     * <p>The token's {@code sub} is the name at issue time, and it is now stale. Resolution is by
     * {@code uid}, so this keeps working — which is both correct and the reason {@code sub} must
     * never become the thing looked up. Same bug class as gotcha #82.
     */
    @Test
    void renamingTheUserDoesNotInvalidateTheirToken() throws Exception {
        String token = login(operator);

        operator.setUsername("forge-op-renamed-" + System.nanoTime());
        userRepository.save(operator);

        expect(get(ALLOWED_ENDPOINT), token, 200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixtures and helpers
    // ─────────────────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assignRequest() {
        // An empty body: with the authority this is a 400, without it a 403. See PRIVILEGED_ENDPOINT.
        return post(PRIVILEGED_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private void expect(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        String token, int expectedStatus) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }

    /** A token signed with the application's own key — the forger's whole advantage. */
    private String forge(Long userId, String username, String jti, Map<String, Object> extraClaims) {
        return forge(userId, username, jti, extraClaims, System.currentTimeMillis() + 600_000);
    }

    private String forge(Long userId, String username, String jti,
                         Map<String, Object> extraClaims, long expiresAt) {
        return signed(builder -> {
            builder.id(jti)
                    .subject(username)
                    .claim(JwtService.CLAIM_UID, userId)
                    .issuedAt(new Date(System.currentTimeMillis()))
                    .expiration(new Date(expiresAt));
            extraClaims.forEach(builder::claim);
        });
    }

    private String signed(java.util.function.Consumer<io.jsonwebtoken.JwtBuilder> configure) {
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder();
        configure.accept(builder);
        return builder.signWith(
                Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private String liveJti(User user) {
        return apiSessionRepository.findActiveByUserId(user.getId(), System.currentTimeMillis())
                .stream().findFirst().orElseThrow().getJti();
    }

    private void expireSessionRow(String jti) {
        ApiSession session = apiSessionRepository.findByJti(jti).orElseThrow();
        session.setExpiresAt(System.currentTimeMillis() - 1);
        apiSessionRepository.save(session);
    }

    private String login(User user) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", user.getUsername(),
                                "password", PASSWORD,
                                "deviceLabel", "Tablet " + user.getUsername()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        String token = json.get("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void grant(String permissionCode) {
        Permission permission = permissionRepository.findByCode(permissionCode).orElseThrow();
        RolePermission link = new RolePermission();
        link.setRoleId(ownRole.getId());
        link.setPermissionId(permission.getId());
        rolePermissionRepository.saveAndFlush(link);
    }

    private void revoke(String permissionCode) {
        Permission permission = permissionRepository.findByCode(permissionCode).orElseThrow();
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ? AND permission_id = ?",
                ownRole.getId(), permission.getId());
    }

    /**
     * A private role holding the same permissions as OPERATOR.
     *
     * <p>These tests grant and revoke permissions, and they commit. Doing that to a seeded role
     * would change what every other test in the suite sees — the shared container makes that a
     * real risk rather than a tidy-minded one.
     */
    private Role seedRoleCopyingOperator() {
        long now = System.currentTimeMillis();
        Role role = new Role();
        role.setCode("FORGERY-TEST-" + System.nanoTime());
        role.setName("نقش آزمون جعل");
        role.setSystemRole(false);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        role = roleRepository.saveAndFlush(role);

        Long operatorRoleId = roleRepository.findByCode("OPERATOR").orElseThrow().getId();
        List<RolePermission> copies = new ArrayList<>();
        for (RolePermission source : rolePermissionRepository.findByRoleId(operatorRoleId)) {
            RolePermission copy = new RolePermission();
            copy.setRoleId(role.getId());
            copy.setPermissionId(source.getPermissionId());
            copies.add(copy);
        }
        rolePermissionRepository.saveAllAndFlush(copies);
        return role;
    }

    private User seedUser(String prefix, Role role) {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername(prefix + "-" + System.nanoTime());
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Forgery Fixture");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);

        UserRole link = new UserRole();
        link.setUserId(user.getId());
        link.setRoleId(role.getId());
        userRoleRepository.saveAndFlush(link);
        return user;
    }

    /**
     * Removes a user's role links with SQL.
     *
     * <p>{@code UserRoleRepository.deleteByUserId} is a derived delete and needs a transaction;
     * this class is deliberately not {@code @Transactional} — every request under test has to see
     * committed state — so calling it here fails with "No EntityManager with actual transaction".
     */
    private void detachAllRoles(User user) {
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", user.getId());
    }

    /**
     * Same reason as {@link #detachAllRoles}: every delete in this class runs outside a
     * transaction, so derived {@code deleteBy…} methods cannot be used. Reads and saves are fine —
     * only removals need a transaction to be reliable.
     */

    private void deleteUser(User user) {
        if (user == null || user.getId() == null) {
            return;
        }
        apiSessionRepository.deleteAll(apiSessionRepository.findAll().stream()
                .filter(s -> user.getId().equals(s.getUserId()))
                .toList());
        detachAllRoles(user);
        userRepository.deleteById(user.getId());
    }
}
