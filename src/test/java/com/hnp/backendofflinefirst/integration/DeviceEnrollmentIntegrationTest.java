package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceAuthError;
import com.hnp.backendofflinefirst.domain.DeviceRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.Device;
import com.hnp.backendofflinefirst.entity.Role;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.repository.DeviceRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.DeviceKeys;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.DeviceAuthException;
import com.hnp.backendofflinefirst.service.DeviceEnrollmentService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Device enrolment, end to end, through the same HTTP endpoints a tablet calls.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. Three of the properties under test only
 * exist across real commits: a wrong code that must stay counted although the call is refused, a
 * nonce that must stay consumed although the login fails, and a revoke that must reach a session
 * a concurrent login is about to insert. A test transaction rolled back at the end would hide all
 * three.
 *
 * <p>The "tablet" here signs with Java's {@code SHA256withECDSAinP1363Format} — the format
 * WebCrypto emits, which {@code DeviceKeysTest} pins against a vector produced by WebCrypto
 * itself. So these tests exercise the server's real verification path, not a stand-in.
 */
class DeviceEnrollmentIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "device-pass-1";
    /** Bootstrap admin in the test database; @WithAppUser and the panel act as user 1. */
    private static final long APPROVER = 1L;

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired ApiSessionService apiSessionService;
    @Autowired DeviceRepository deviceRepository;
    @Autowired DeviceEnrollmentService deviceEnrollmentService;
    @Autowired AppSettingsService appSettingsService;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;
    User operator;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        operator = seedOperator();
        appSettingsService.saveDeviceEnrollmentEnabled(false);
    }

    @AfterEach
    void cleanUp() {
        // The switch first: every other class in this JVM logs in through the API, and a switch
        // left on would refuse all of them.
        appSettingsService.saveDeviceEnrollmentEnabled(false);
        jdbc.update("DELETE FROM api_sessions WHERE device_id IS NOT NULL OR user_id = ?", operator.getId());
        jdbc.update("DELETE FROM device_auth_challenges");
        jdbc.update("DELETE FROM devices");
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", operator.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", operator.getId());
    }

    // =====================================================================================
    // Off: exactly as before
    // =====================================================================================

    @Test
    void theSwitchIsSeededOff() {
        assertThat(jdbc.queryForObject(
                "SELECT value FROM app_settings WHERE setting_key = 'auth.device_enrollment.enabled'",
                String.class)).isNotNull();
        assertThat(AppSettingsService.DEFAULT_DEVICE_ENROLLMENT_ENABLED).isFalse();
        assertThat(appSettingsService.isDeviceEnrollmentEnabled()).isFalse();
    }

    @Test
    void offALoginIsExactlyWhatItAlwaysWas() throws Exception {
        MvcResult result = login(Map.of("username", operator.getUsername(), "password", PASSWORD));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        ApiSession session = onlyActiveSession();
        assertThat(session.getDeviceId()).as("no device is recorded while enrolment is off").isNull();
    }

    @Test
    void offDeviceFieldsAreIgnoredEntirely() throws Exception {
        // A tablet that was enrolled while the switch was on still sends its proof; with the
        // switch off the server must not even read it — a junk proof is not an error.
        MvcResult result = login(Map.of("username", operator.getUsername(), "password", PASSWORD,
                "deviceId", 999999, "nonce", "junk", "signature", "junk"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(onlyActiveSession().getDeviceId()).isNull();
    }

    @Test
    void offTheEnrolmentEndpointsRefuseAndTheChallengeSaysSo() throws Exception {
        Tablet tablet = new Tablet();

        MvcResult request = postJson("/api/devices/enrollment-requests",
                Map.of("publicKey", tablet.publicKey()));
        assertError(request, DeviceAuthError.ENROLLMENT_DISABLED);

        MvcResult confirm = postJson("/api/devices/enrollment-requests/confirm",
                Map.of("requestId", 1, "enrollmentCode", "12345678", "signature", "x"));
        assertError(confirm, DeviceAuthError.ENROLLMENT_DISABLED);

        JsonNode challenge = json(postJson("/api/auth/challenge",
                Map.of("deviceId", 1, "keyFingerprint", "x")));
        assertThat(challenge.get("enforced").asBoolean()).isFalse();
        assertThat(challenge.has("nonce")).isFalse();

        assertThat(deviceRepository.count()).as("off writes nothing").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_auth_challenges", Long.class)).isZero();
    }

    // =====================================================================================
    // On: the whole flow
    // =====================================================================================

    @Test
    void theWholeFlowEndsInASessionBoundToTheDevice() throws Exception {
        enable();
        Tablet tablet = new Tablet();

        long requestId = requestEnrollment(tablet, "تبلت ۷");
        Device pending = deviceRepository.findById(requestId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(pending.getLabel()).isEqualTo("تبلت ۷");

        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();
        assertThat(code).matches("\\d{8}");

        JsonNode confirmed = json(confirm(tablet, requestId, code));
        assertThat(confirmed.get("status").asText()).isEqualTo("APPROVED");

        Device approved = deviceRepository.findById(requestId).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(DeviceStatus.APPROVED);
        assertThat(approved.getApprovedBy()).as("the approver is whoever issued the code").isEqualTo(APPROVER);
        assertThat(approved.getCodeHash()).as("a used code is gone").isNull();

        String token = signedLogin(tablet, requestId, approved.getKeyFingerprint(), operator.getUsername());

        ApiSession session = onlyActiveSession();
        assertThat(session.getDeviceId()).isEqualTo(requestId);
        assertThat(deviceRepository.findById(requestId).orElseThrow().getLastSeenAt()).isNotNull();
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
    }

    @Test
    void onAPlainLoginIsRefusedBeforeThePasswordIsEvaluated() throws Exception {
        enable();

        // A WRONG password, and the answer is still the device error, not 401: an unenrolled
        // device learns nothing about any account by trying.
        MvcResult result = login(Map.of("username", operator.getUsername(), "password", "wrong"));

        assertError(result, DeviceAuthError.ENROLLMENT_REQUIRED);
        assertThat(apiSessionRepository.findActiveByUserId(operator.getId(), now())).isEmpty();
    }

    @Test
    void theRefusalCarriesAPersianMessageForOlderBuilds() throws Exception {
        enable();

        JsonNode body = json(login(Map.of("username", operator.getUsername(), "password", PASSWORD)));

        // `message` is the one field every PWA build already reads; a tablet that predates
        // enrolment must still show the operator the right sentence.
        assertThat(body.get("message").asText()).isEqualTo(DeviceAuthError.ENROLLMENT_REQUIRED.messageFa());
        assertThat(body.get("code").asText()).isEqualTo("ENROLLMENT_REQUIRED");
    }

    // =====================================================================================
    // The code
    // =====================================================================================

    @Test
    void theCodeIsAcceptedOnlyFromTheDeviceThatAsked() throws Exception {
        enable();
        Tablet owner = new Tablet();
        Tablet stranger = new Tablet();
        long requestId = requestEnrollment(owner, "owner");
        requestEnrollment(stranger, "stranger");
        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();

        // The stranger overheard the code and knows the request number — and still cannot sign
        // as the key that opened it.
        MvcResult attempt = postJson("/api/devices/enrollment-requests/confirm",
                Map.of("requestId", requestId, "enrollmentCode", code,
                        "signature", stranger.sign(DeviceKeys.enrollMessage(requestId, code))));
        assertError(attempt, DeviceAuthError.SIGNATURE_INVALID);

        Device untouched = deviceRepository.findById(requestId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(untouched.getCodeFailedAttempts())
                .as("a stranger cannot even spend this request's attempts").isZero();

        assertThat(json(confirm(owner, requestId, code)).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void aWrongCodeIsCountedAndTheFifthDestroysTheCode() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);
        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();
        String wrong = code.equals("00000000") ? "11111111" : "00000000";

        for (int left = 4; left >= 1; left--) {
            JsonNode body = json(confirm(tablet, requestId, wrong));
            assertThat(body.get("code").asText()).isEqualTo("CODE_INVALID");
            assertThat(body.get("attemptsLeft").asInt()).isEqualTo(left);
        }
        assertError(confirm(tablet, requestId, wrong), DeviceAuthError.CODE_LOCKED);

        // Counted although every call was refused — that is what noRollbackFor is for.
        assertThat(deviceRepository.findById(requestId).orElseThrow().getCodeHash()).isNull();
        // The right code is now useless too; a new one has to be issued.
        assertError(confirm(tablet, requestId, code), DeviceAuthError.CODE_NOT_ISSUED);

        String fresh = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();
        assertThat(json(confirm(tablet, requestId, fresh)).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void aCodeTypedInPersianDigitsWithGroupingIsAccepted() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);
        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();
        String typed = toPersianDigits(code.substring(0, 4)) + " " + toPersianDigits(code.substring(4));

        // The tablet signs what it will send after normalising, exactly as the PWA does.
        MvcResult result = postJson("/api/devices/enrollment-requests/confirm",
                Map.of("requestId", requestId, "enrollmentCode", typed,
                        "signature", tablet.sign(DeviceKeys.enrollMessage(requestId, DeviceKeys.normalizeCode(typed)))));

        assertThat(json(result).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void anExpiredCodeIsRefusedAndDestroyed() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);
        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();
        jdbc.update("UPDATE devices SET code_expires_at = ? WHERE id = ?", now() - 1, requestId);

        assertError(confirm(tablet, requestId, code), DeviceAuthError.CODE_EXPIRED);
        assertThat(deviceRepository.findById(requestId).orElseThrow().getCodeHash()).isNull();
    }

    @Test
    void confirmingBeforeAnyCodeIsIssuedSaysSo() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);

        assertError(confirm(tablet, requestId, "12345678"), DeviceAuthError.CODE_NOT_ISSUED);
    }

    @Test
    void aCodeCannotBeIssuedForAnythingButALivePendingRequest() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);
        approve(tablet, requestId);

        assertThatThrownBy(() -> deviceEnrollmentService.issueCode(requestId, APPROVER, now()))
                .isInstanceOf(IllegalStateException.class);
    }

    // =====================================================================================
    // Login proof
    // =====================================================================================

    @Test
    void aNonceAuthenticatesExactlyOneLogin() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));
        String fingerprint = fingerprint(deviceId);

        String nonce = challengeNonce(deviceId, fingerprint);
        Map<String, Object> body = proofBody(tablet, deviceId, nonce, operator.getUsername());
        assertThat(login(body).getResponse().getStatus()).isEqualTo(200);

        // The very same request again — a replay — is refused.
        assertError(login(body), DeviceAuthError.CHALLENGE_INVALID);
    }

    @Test
    void aNonceIsBoundToTheDeviceItWasIssuedTo() throws Exception {
        enable();
        Tablet first = new Tablet();
        Tablet second = new Tablet();
        long firstId = approve(first, requestEnrollment(first, null));
        long secondId = approve(second, requestEnrollment(second, null));

        String firstNonce = challengeNonce(firstId, fingerprint(firstId));

        assertError(login(proofBody(second, secondId, firstNonce, operator.getUsername())),
                DeviceAuthError.CHALLENGE_INVALID);
    }

    @Test
    void anExpiredNonceIsRefused() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));
        String nonce = challengeNonce(deviceId, fingerprint(deviceId));
        jdbc.update("UPDATE device_auth_challenges SET expires_at = ? WHERE nonce = ?", now() - 1, nonce);

        assertError(login(proofBody(tablet, deviceId, nonce, operator.getUsername())),
                DeviceAuthError.CHALLENGE_INVALID);
    }

    @Test
    void aProofForAnotherUsernameIsRefused() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));
        String nonce = challengeNonce(deviceId, fingerprint(deviceId));

        Map<String, Object> body = new HashMap<>(proofBody(tablet, deviceId, nonce, "somebody.else"));
        body.put("username", operator.getUsername());

        assertError(login(body), DeviceAuthError.SIGNATURE_INVALID);
    }

    @Test
    void theChallengeAnswersOnlyToTheRightFingerprint() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));

        JsonNode wrong = json(postJson("/api/auth/challenge",
                Map.of("deviceId", deviceId, "keyFingerprint", "0".repeat(64))));
        assertThat(wrong.get("enforced").asBoolean()).isTrue();
        assertThat(wrong.get("deviceStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(wrong.has("nonce")).isFalse();

        JsonNode right = json(postJson("/api/auth/challenge",
                Map.of("deviceId", deviceId, "keyFingerprint", fingerprint(deviceId))));
        assertThat(right.get("deviceStatus").asText()).isEqualTo("APPROVED");
        assertThat(right.get("nonce").asText()).isNotBlank();
    }

    @Test
    void outstandingChallengesPerDeviceAreCapped() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));
        String fingerprint = fingerprint(deviceId);

        for (int i = 0; i < 12; i++) {
            challengeNonce(deviceId, fingerprint);
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_auth_challenges WHERE device_id = ?", Long.class, deviceId))
                .isLessThanOrEqualTo(5L);
    }

    @Test
    void aPendingDeviceCannotLogIn() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);

        JsonNode challenge = json(postJson("/api/auth/challenge",
                Map.of("deviceId", requestId, "keyFingerprint", fingerprint(requestId))));
        assertThat(challenge.get("deviceStatus").asText()).isEqualTo("PENDING");

        assertError(login(proofBody(tablet, requestId, "made-up", operator.getUsername())),
                DeviceAuthError.DEVICE_NOT_APPROVED);
    }

    // =====================================================================================
    // Revocation
    // =====================================================================================

    @Test
    void revokingADeviceEndsItsSessionsAndEveryLaterLogin() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));
        String token = signedLogin(tablet, deviceId, fingerprint(deviceId), operator.getUsername());

        DeviceEnrollmentService.RevokeResult result = deviceEnrollmentService.revoke(deviceId, APPROVER, now());
        assertThat(result.reason()).isEqualTo(DeviceRevokeReason.ADMIN);
        assertThat(result.sessionsRevoked()).isEqualTo(1);

        // The running session ends at its next call.
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
        ApiSession ended = apiSessionRepository.findAll().stream()
                .filter(s -> Long.valueOf(deviceId).equals(s.getDeviceId())).findFirst().orElseThrow();
        assertThat(ended.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.DEVICE_REVOKED);

        // No one logs in on it again, with any password.
        JsonNode challenge = json(postJson("/api/auth/challenge",
                Map.of("deviceId", deviceId, "keyFingerprint", fingerprint(deviceId))));
        assertThat(challenge.get("deviceStatus").asText()).isEqualTo("REVOKED");
        assertError(login(proofBody(tablet, deviceId, "any", operator.getUsername())), DeviceAuthError.DEVICE_REVOKED);

        // And it cannot quietly re-enter the queue under its old key.
        assertError(postJson("/api/devices/enrollment-requests", Map.of("publicKey", tablet.publicKey())),
                DeviceAuthError.DEVICE_REVOKED);
        // A new key is a new request — which an approver must approve again.
        Tablet rekeyed = new Tablet();
        assertThat(requestEnrollment(rekeyed, null)).isNotEqualTo(deviceId);
    }

    @Test
    void aRejectedRequestCannotBeConfirmed() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);
        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();

        assertThat(deviceEnrollmentService.revoke(requestId, APPROVER, now()).reason())
                .isEqualTo(DeviceRevokeReason.REJECTED);

        assertError(confirm(tablet, requestId, code), DeviceAuthError.DEVICE_REVOKED);
        assertThat(deviceRepository.findById(requestId).orElseThrow().getCodeHash()).isNull();
    }

    @Test
    void aSessionCannotBeRegisteredOnADeviceRevokedAfterItsProofWasChecked() throws Exception {
        // The race the SHARE lock in register() closes, reproduced by its outcome: the login's
        // proof check passed, a revoke then landed, and only then does the session insert run.
        enable();
        Tablet tablet = new Tablet();
        long deviceId = approve(tablet, requestEnrollment(tablet, null));
        deviceEnrollmentService.revoke(deviceId, APPROVER, now());

        AppUserDetails principal = TestPrincipals.of(operator, Set.of("OPERATOR"), List.of());
        JwtService.JwtToken token = jwtService.issueToken(principal);

        assertThatThrownBy(() -> apiSessionService.register(principal, token, null, null, null, deviceId))
                .isInstanceOf(DeviceAuthException.class)
                .extracting(e -> ((DeviceAuthException) e).getError())
                .isEqualTo(DeviceAuthError.DEVICE_REVOKED);
        assertThat(apiSessionRepository.findByJti(token.jti())).as("nothing was written").isEmpty();
    }

    @Test
    void turningEnrolmentOnDoesNotCutOffASessionAlreadyRunning() throws Exception {
        String token = json(login(Map.of("username", operator.getUsername(), "password", PASSWORD)))
                .get("accessToken").asText();

        enable();

        // Enforced at login only: a round in progress is never interrupted by the switch.
        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
    }

    // =====================================================================================
    // Requests
    // =====================================================================================

    @Test
    void askingAgainWithTheSameKeyIsIdempotent() throws Exception {
        enable();
        Tablet tablet = new Tablet();

        long first = requestEnrollment(tablet, "a");
        long second = requestEnrollment(tablet, "b");
        assertThat(second).as("one row per key").isEqualTo(first);

        approve(tablet, first);
        JsonNode again = json(postJson("/api/devices/enrollment-requests", Map.of("publicKey", tablet.publicKey())));
        // The response that approved it may have been lost; asking again must say so.
        assertThat(again.get("status").asText()).isEqualTo("APPROVED");
        assertThat(again.get("requestId").asLong()).isEqualTo(first);
    }

    @Test
    void anExpiredRequestIsReArmedRatherThanDuplicated() throws Exception {
        enable();
        Tablet tablet = new Tablet();
        long requestId = requestEnrollment(tablet, null);
        jdbc.update("UPDATE devices SET request_expires_at = ? WHERE id = ?", now() - 1, requestId);

        assertThat(requestEnrollment(tablet, null)).isEqualTo(requestId);
        assertThat(deviceRepository.findById(requestId).orElseThrow().isPendingAt(now())).isTrue();
    }

    @Test
    void pendingRequestsFromOneAddressAreCapped() throws Exception {
        enable();
        String address = "10.77." + (int) (Math.random() * 200) + ".9";

        for (int i = 0; i < 5; i++) {
            MvcResult ok = postJson("/api/devices/enrollment-requests",
                    Map.of("publicKey", new Tablet().publicKey()), address);
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        }
        assertError(postJson("/api/devices/enrollment-requests",
                Map.of("publicKey", new Tablet().publicKey()), address), DeviceAuthError.TOO_MANY_PENDING);
    }

    @Test
    void aMalformedKeyIsRefused() throws Exception {
        enable();

        assertError(postJson("/api/devices/enrollment-requests", Map.of("publicKey", "not-a-key")),
                DeviceAuthError.INVALID_KEY);
        assertThat(deviceRepository.count()).isZero();
    }

    @Test
    void anUnknownRequestIsReportedAsSuch() throws Exception {
        enable();
        Tablet tablet = new Tablet();

        assertError(confirm(tablet, 987654321L, "12345678"), DeviceAuthError.REQUEST_UNKNOWN);
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    /** A handheld: a P-256 key pair that signs in the format WebCrypto does. */
    private static final class Tablet {
        private final KeyPair keys;

        Tablet() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = generator.generateKeyPair();
        }

        String publicKey() {
            return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        }

        String sign(String message) throws Exception {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(keys.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        }
    }

    private void enable() {
        appSettingsService.saveDeviceEnrollmentEnabled(true);
    }

    private long requestEnrollment(Tablet tablet, String label) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("publicKey", tablet.publicKey());
        body.put("label", label);
        JsonNode state = json(postJson("/api/devices/enrollment-requests", body));
        assertThat(state.has("requestId")).as("request refused: %s", state).isTrue();
        return state.get("requestId").asLong();
    }

    private MvcResult confirm(Tablet tablet, long requestId, String code) throws Exception {
        return postJson("/api/devices/enrollment-requests/confirm",
                Map.of("requestId", requestId, "enrollmentCode", code,
                        "signature", tablet.sign(DeviceKeys.enrollMessage(requestId, DeviceKeys.normalizeCode(code)))));
    }

    /** Request → code → confirm, returning the device id. */
    private long approve(Tablet tablet, long requestId) throws Exception {
        String code = deviceEnrollmentService.issueCode(requestId, APPROVER, now()).enrollmentCode();
        assertThat(json(confirm(tablet, requestId, code)).get("status").asText()).isEqualTo("APPROVED");
        return requestId;
    }

    private String fingerprint(long deviceId) {
        return deviceRepository.findById(deviceId).orElseThrow().getKeyFingerprint();
    }

    private String challengeNonce(long deviceId, String fingerprint) throws Exception {
        JsonNode challenge = json(postJson("/api/auth/challenge",
                Map.of("deviceId", deviceId, "keyFingerprint", fingerprint)));
        assertThat(challenge.get("enforced").asBoolean()).isTrue();
        return challenge.get("nonce").asText();
    }

    private Map<String, Object> proofBody(Tablet tablet, long deviceId, String nonce, String signedUsername)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", signedUsername);
        body.put("password", PASSWORD);
        body.put("deviceId", deviceId);
        body.put("nonce", nonce);
        body.put("signature", tablet.sign(DeviceKeys.loginMessage(nonce, signedUsername)));
        return body;
    }

    private String signedLogin(Tablet tablet, long deviceId, String fingerprint, String username) throws Exception {
        String nonce = challengeNonce(deviceId, fingerprint);
        MvcResult result = login(proofBody(tablet, deviceId, nonce, username));
        assertThat(result.getResponse().getStatus())
                .as("signed login refused: %s", result.getResponse().getContentAsString()).isEqualTo(200);
        return json(result).get("accessToken").asText();
    }

    private MvcResult login(Map<String, ?> body) throws Exception {
        return postJson("/api/auth/login", body);
    }

    private MvcResult postJson(String path, Map<String, ?> body) throws Exception {
        return postJson(path, body, null);
    }

    private MvcResult postJson(String path, Map<String, ?> body, String forwardedFor) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertError(MvcResult result, DeviceAuthError expected) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("status for %s: %s", expected, result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expected.status().value());
        JsonNode body = json(result);
        assertThat(body.get("code").asText()).isEqualTo(expected.name());
        assertThat(body.get("message").asText()).isEqualTo(expected.messageFa());
    }

    private ApiSession onlyActiveSession() {
        List<ApiSession> active = apiSessionRepository.findActiveByUserId(operator.getId(), now());
        assertThat(active).hasSize(1);
        return active.getFirst();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String toPersianDigits(String ascii) {
        StringBuilder out = new StringBuilder();
        for (char c : ascii.toCharArray()) {
            out.append((char) ('۰' + (c - '0')));
        }
        return out.toString();
    }

    private User seedOperator() {
        long now = now();
        User user = new User();
        user.setUsername("dev-op-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Device Operator");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.save(user);

        Role operatorRole = roleRepository.findByCode("OPERATOR").orElseThrow();
        UserRole link = new UserRole();
        link.setUserId(user.getId());
        link.setRoleId(operatorRole.getId());
        userRoleRepository.save(link);
        return user;
    }
}
