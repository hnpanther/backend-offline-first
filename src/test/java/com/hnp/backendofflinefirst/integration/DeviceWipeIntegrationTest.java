package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceAuthError;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.entity.ApiSession;
import com.hnp.backendofflinefirst.entity.Device;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.ApiSessionRepository;
import com.hnp.backendofflinefirst.repository.DeviceRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.security.DeviceKeys;
import com.hnp.backendofflinefirst.security.JwtService;
import com.hnp.backendofflinefirst.service.ApiSessionService;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.service.DeviceEnrollmentService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wipe on revoke (V28), end to end: the order given in the panel, its delivery in the answer to
 * {@code /api/auth/challenge}, the tablet's signed confirmation, and every way each step is
 * refused.
 *
 * <p>Not {@code @Transactional}, like {@link DeviceEnrollmentIntegrationTest}: delivery and
 * withdrawal are two transactions that must see each other's commits, and that only exists
 * across real commits.
 *
 * <p>The "tablet" signs with {@code SHA256withECDSAinP1363Format}, the format WebCrypto emits,
 * so these exercise the server's real verification path.
 */
class DeviceWipeIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The bootstrap administrator; {@code @WithAppUser} acts as this id. */
    private static final long ADMIN = 1L;

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired DeviceEnrollmentService deviceEnrollmentService;
    @Autowired DeviceRepository deviceRepository;
    @Autowired AppSettingsService appSettingsService;
    @Autowired ApiSessionService apiSessionService;
    @Autowired ApiSessionRepository apiSessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        appSettingsService.saveDeviceEnrollmentEnabled(true);
    }

    @AfterEach
    void cleanUp() {
        // The switch first: every other class in this JVM logs in through the API.
        appSettingsService.saveDeviceEnrollmentEnabled(false);
        jdbc.update("DELETE FROM api_sessions WHERE device_id IS NOT NULL");
        jdbc.update("DELETE FROM device_auth_challenges");
        jdbc.update("DELETE FROM devices");
        jdbc.update("DELETE FROM users WHERE username LIKE 'wipe-%'");
    }

    // =====================================================================================
    // The order, delivered
    // =====================================================================================

    @Test
    void aRevokeWithoutAWipeIsAnsweredExactlyAsBefore() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now());

        JsonNode answer = challenge(deviceId, fingerprint(deviceId));

        assertThat(answer.get("enforced").asBoolean()).isTrue();
        assertThat(answer.get("deviceStatus").asText()).isEqualTo("REVOKED");
        // Absent, not false: a tablet built before V28 sees the same answer it always did.
        assertThat(answer.has("wipe")).isFalse();
        assertThat(answer.has("wipeOrderedAt")).isFalse();
        assertThat(answer.has("nonce")).isFalse();
    }

    @Test
    void aRevokeWithAWipeOrdersItAndTheChallengeDeliversIt() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        long before = now();

        DeviceEnrollmentService.RevokeResult result = deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);

        assertThat(result.wipeOrdered()).isTrue();
        Device ordered = device(deviceId);
        assertThat(ordered.getStatus()).isEqualTo(DeviceStatus.REVOKED);
        assertThat(ordered.getWipeRequestedAt()).isBetween(before, now());
        assertThat(ordered.getWipeRequestedBy()).isEqualTo(ADMIN);
        assertThat(ordered.getWipeSignalledAt()).as("not handed to the tablet yet").isNull();

        JsonNode answer = challenge(deviceId, fingerprint(deviceId));

        assertThat(answer.get("deviceStatus").asText()).isEqualTo("REVOKED");
        assertThat(answer.get("wipe").asBoolean()).isTrue();
        assertThat(answer.get("wipeOrderedAt").asLong()).isEqualTo(ordered.getWipeRequestedAt());
        assertThat(answer.has("nonce")).as("a revoked device is never given a nonce").isFalse();
        assertThat(device(deviceId).getWipeSignalledAt()).as("recorded as delivered").isNotNull();
    }

    @Test
    void askingAgainIsToldAgainAndKeepsTheFirstDeliveryTime() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);

        challenge(deviceId, fingerprint(deviceId));
        Long first = device(deviceId).getWipeSignalledAt();
        JsonNode again = challenge(deviceId, fingerprint(deviceId));

        assertThat(again.get("wipe").asBoolean()).isTrue();
        assertThat(device(deviceId).getWipeSignalledAt()).isEqualTo(first);
    }

    @Test
    void theOrderIsDeliveredWithEnrolmentSwitchedOffToo() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        appSettingsService.saveDeviceEnrollmentEnabled(false);

        JsonNode answer = challenge(deviceId, fingerprint(deviceId));

        // The switch says what it says — and the order is still handed over, because an
        // administrator's order to empty a tablet must not vanish when someone turns the switch off.
        assertThat(answer.get("enforced").asBoolean()).isFalse();
        assertThat(answer.get("wipe").asBoolean()).isTrue();
        assertThat(device(deviceId).getWipeSignalledAt()).isNotNull();
    }

    @Test
    void withEnrolmentOffAndNoOrderTheChallengeStillSaysOnlyThatAndWritesNothing() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now());
        appSettingsService.saveDeviceEnrollmentEnabled(false);

        JsonNode answer = challenge(deviceId, fingerprint(deviceId));

        assertThat(answer.get("enforced").asBoolean()).isFalse();
        assertThat(answer.has("deviceStatus")).isFalse();
        assertThat(answer.has("wipe")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_auth_challenges", Long.class)).isZero();
    }

    @Test
    void theOrderIsToldOnlyToACallerPresentingTheDevicesFingerprint() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);

        JsonNode wrongPrint = challenge(deviceId, "0".repeat(64));
        assertThat(wrongPrint.get("deviceStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(wrongPrint.has("wipe")).isFalse();

        Map<String, Object> noPrint = new HashMap<>();
        noPrint.put("deviceId", deviceId);
        JsonNode missing = json(postJson("/api/auth/challenge", noPrint));
        assertThat(missing.has("wipe")).isFalse();

        assertThat(device(deviceId).getWipeSignalledAt()).as("nobody was told").isNull();
    }

    @Test
    void anApprovedDeviceIsNeverToldToWipe() throws Exception {
        long deviceId = approvedDevice(new Tablet());

        JsonNode answer = challenge(deviceId, fingerprint(deviceId));

        assertThat(answer.get("deviceStatus").asText()).isEqualTo("APPROVED");
        assertThat(answer.has("nonce")).isTrue();
        assertThat(answer.has("wipe")).isFalse();
    }

    // =====================================================================================
    // Ordering on a revoked device, and what is refused
    // =====================================================================================

    @Test
    void aDeviceRevokedFirstCanBeOrderedToWipeLater() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now());

        deviceEnrollmentService.orderWipe(deviceId, ADMIN, now());

        assertThat(device(deviceId).getWipeRequestedAt()).isNotNull();
        assertThat(challenge(deviceId, fingerprint(deviceId)).get("wipe").asBoolean()).isTrue();
    }

    @Test
    void aWipeCannotBeOrderedOnADeviceThatIsStillApproved() throws Exception {
        long deviceId = approvedDevice(new Tablet());

        assertThatThrownBy(() -> deviceEnrollmentService.orderWipe(deviceId, ADMIN, now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(device(deviceId).getWipeRequestedAt()).isNull();
        assertThat(device(deviceId).getStatus()).isEqualTo(DeviceStatus.APPROVED);
    }

    @Test
    void aRequestThatWasNeverApprovedCannotBeWiped() throws Exception {
        long requestId = pendingRequest(new Tablet());

        // Neither in the same act as turning it down …
        assertThatThrownBy(() -> deviceEnrollmentService.revoke(requestId, ADMIN, now(), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(device(requestId).getStatus()).as("refused entirely, not rejected without the wipe")
                .isEqualTo(DeviceStatus.PENDING);

        // … nor afterwards.
        deviceEnrollmentService.revoke(requestId, ADMIN, now());
        assertThatThrownBy(() -> deviceEnrollmentService.orderWipe(requestId, ADMIN, now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(device(requestId).getWipeRequestedAt()).isNull();
    }

    @Test
    void oneOrderAtATime() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        Long first = device(deviceId).getWipeRequestedAt();

        assertThatThrownBy(() -> deviceEnrollmentService.orderWipe(deviceId, ADMIN, now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(device(deviceId).getWipeRequestedAt()).isEqualTo(first);
    }

    @Test
    void theDatabaseRefusesAnOrderOnADeviceThatIsNotRevoked() throws Exception {
        long deviceId = approvedDevice(new Tablet());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE devices SET wipe_requested_at = 1, wipe_requested_by = 1 WHERE id = ?", deviceId))
                .hasMessageContaining("ck_devices_wipe_only_when_revoked");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE devices SET wipe_signalled_at = 1 WHERE id = ?", deviceId))
                .hasMessageContaining("ck_devices_wipe_signal_needs_order");
    }

    // =====================================================================================
    // Withdrawing an order, and the race with delivery
    // =====================================================================================

    @Test
    void anOrderNotYetDeliveredCanBeWithdrawnAndIsThenNeverDelivered() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);

        deviceEnrollmentService.cancelWipe(deviceId, ADMIN, now());

        Device withdrawn = device(deviceId);
        assertThat(withdrawn.getWipeRequestedAt()).isNull();
        assertThat(withdrawn.getWipeRequestedBy()).isNull();
        JsonNode answer = challenge(deviceId, fingerprint(deviceId));
        assertThat(answer.get("deviceStatus").asText()).isEqualTo("REVOKED");
        assertThat(answer.has("wipe")).isFalse();
        assertThat(device(deviceId).getWipeSignalledAt()).isNull();
    }

    @Test
    void anOrderAlreadyDeliveredCannotBeWithdrawn() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        challenge(deviceId, fingerprint(deviceId));

        assertThatThrownBy(() -> deviceEnrollmentService.cancelWipe(deviceId, ADMIN, now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(device(deviceId).getWipeRequestedAt()).isNotNull();
    }

    @Test
    void aDeliveryReadingAWithdrawnOrderDeliversNothing() throws Exception {
        // The half of the race the row lock decides: the challenge read the order, then the
        // withdrawal committed before it recorded the delivery. The conditional UPDATE must see
        // the withdrawal and answer «no order».
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();

        deviceEnrollmentService.cancelWipe(deviceId, ADMIN, now());

        assertThat(jdbc.update("""
                UPDATE devices SET wipe_signalled_at = COALESCE(wipe_signalled_at, ?)
                 WHERE id = ? AND status = 'REVOKED' AND wipe_requested_at = ?
                """, now(), deviceId, orderedAt)).isZero();
        assertThat(device(deviceId).getWipeSignalledAt()).isNull();
    }

    @Test
    void aWithdrawnOrderCanBeGivenAgainAndIsANewOrder() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long first = device(deviceId).getWipeRequestedAt();
        deviceEnrollmentService.cancelWipe(deviceId, ADMIN, now());

        Thread.sleep(5);
        deviceEnrollmentService.orderWipe(deviceId, ADMIN, now());

        assertThat(device(deviceId).getWipeRequestedAt()).isGreaterThan(first);
    }

    // =====================================================================================
    // The tablet's confirmation
    // =====================================================================================

    @Test
    void theTabletConfirmsWithItsOwnKeyAndTheConfirmationIsRecordedOnce() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = challenge(deviceId, fingerprint(deviceId)).get("wipeOrderedAt").asLong();

        MvcResult first = confirmWipe(deviceId, orderedAt, tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt)));

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        long confirmedAt = json(first).get("confirmedAt").asLong();
        assertThat(device(deviceId).getWipeConfirmedAt()).isEqualTo(confirmedAt);

        // Its answer was lost on a weak link; it asks again and is told the same.
        Thread.sleep(5);
        MvcResult repeat = confirmWipe(deviceId, orderedAt, tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt)));
        assertThat(repeat.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(repeat).get("confirmedAt").asLong()).isEqualTo(confirmedAt);
        assertThat(device(deviceId).getWipeConfirmedAt()).isEqualTo(confirmedAt);
    }

    @Test
    void aConfirmationArrivingBeforeTheDeliveryWasRecordedAlsoRecordsTheDelivery() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();

        confirmWipe(deviceId, orderedAt, tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt)));

        Device confirmed = device(deviceId);
        assertThat(confirmed.getWipeConfirmedAt()).isNotNull();
        assertThat(confirmed.getWipeSignalledAt()).as("never «confirmed» and «not delivered» at once").isNotNull();
    }

    @Test
    void anotherKeyCannotConfirm() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();

        MvcResult forged = confirmWipe(deviceId, orderedAt,
                new Tablet().sign(DeviceKeys.wipeMessage(deviceId, orderedAt)));

        assertError(forged, DeviceAuthError.SIGNATURE_INVALID);
        assertThat(device(deviceId).getWipeConfirmedAt()).isNull();
    }

    @Test
    void anUnknownDeviceIsAnsweredExactlyLikeABadSignature() throws Exception {
        Tablet tablet = new Tablet();

        MvcResult unknown = confirmWipe(987_654_321L, 1L, tablet.sign(DeviceKeys.wipeMessage(987_654_321L, 1L)));
        assertError(unknown, DeviceAuthError.SIGNATURE_INVALID);

        Map<String, Object> empty = new HashMap<>();
        assertError(postJson("/api/devices/enrollment-requests/wipe-confirm", empty), DeviceAuthError.SIGNATURE_INVALID);
    }

    @Test
    void aConfirmationForADifferentOrderIsRefusedOnlyAfterTheKeyIsProved() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();
        long otherOrder = orderedAt - 1000;

        // A bad signature over the wrong order: still only «signature invalid».
        assertError(confirmWipe(deviceId, otherOrder, new Tablet().sign(DeviceKeys.wipeMessage(deviceId, otherOrder))),
                DeviceAuthError.SIGNATURE_INVALID);
        // The right key, the wrong order: now it may be told.
        assertError(confirmWipe(deviceId, otherOrder, tablet.sign(DeviceKeys.wipeMessage(deviceId, otherOrder))),
                DeviceAuthError.WIPE_ORDER_UNKNOWN);
        assertThat(device(deviceId).getWipeConfirmedAt()).isNull();
    }

    @Test
    void aSignatureOverTheOrderCannotBeReplayedForAnother() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();
        String signatureForThisOrder = tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt));

        // Presented with a different order's timestamp, the signature no longer verifies.
        assertError(confirmWipe(deviceId, orderedAt + 1, signatureForThisOrder), DeviceAuthError.SIGNATURE_INVALID);
    }

    @Test
    void aConfirmationOfAWithdrawnOrderIsRefused() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();
        deviceEnrollmentService.cancelWipe(deviceId, ADMIN, now());

        assertError(confirmWipe(deviceId, orderedAt, tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt))),
                DeviceAuthError.WIPE_ORDER_UNKNOWN);
    }

    @Test
    void aConfirmedWipeCannotBeOrderedAgain() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = device(deviceId).getWipeRequestedAt();
        confirmWipe(deviceId, orderedAt, tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt)));

        assertThatThrownBy(() -> deviceEnrollmentService.orderWipe(deviceId, ADMIN, now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> deviceEnrollmentService.cancelWipe(deviceId, ADMIN, now()))
                .isInstanceOf(IllegalStateException.class);
    }

    // =====================================================================================
    // The panel
    // =====================================================================================

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke", "POST:/devices/{id}/wipe"})
    void revokingWithAWipeFromThePanelEndsTheSessionsAndRecordsTheOrder() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        JwtService.JwtToken token = sessionOn(deviceId);

        MvcResult result = mockMvc.perform(post("/devices/{id}/revoke", deviceId).param("wipe", "true").with(csrf()))
                .andExpect(redirectedUrl("/devices"))
                .andReturn();

        assertThat((String) result.getFlashMap().get("successMessage")).contains("دستور پاک‌سازی");
        Device revoked = device(deviceId);
        assertThat(revoked.getStatus()).isEqualTo(DeviceStatus.REVOKED);
        assertThat(revoked.getWipeRequestedBy()).isEqualTo(ADMIN);
        ApiSession session = apiSessionRepository.findByJti(token.jti()).orElseThrow();
        assertThat(session.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.DEVICE_REVOKED);
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke"})
    void withoutTheWipePermissionARevokeWithAWipeIsRefusedEntirely() throws Exception {
        long deviceId = approvedDevice(new Tablet());

        MvcResult result = mockMvc.perform(post("/devices/{id}/revoke", deviceId).param("wipe", "true").with(csrf()))
                .andExpect(redirectedUrl("/devices/" + deviceId + "/revoke"))
                .andReturn();

        assertThat((String) result.getFlashMap().get("errorMessage")).contains("لغو نشد");
        // Not revoked-without-the-wipe: an administrator would believe a lost tablet will be emptied.
        Device untouched = device(deviceId);
        assertThat(untouched.getStatus()).isEqualTo(DeviceStatus.APPROVED);
        assertThat(untouched.getWipeRequestedAt()).isNull();

        // A plain revoke is still theirs to make.
        mockMvc.perform(post("/devices/{id}/revoke", deviceId).with(csrf())).andExpect(redirectedUrl("/devices"));
        assertThat(device(deviceId).getStatus()).isEqualTo(DeviceStatus.REVOKED);
        assertThat(device(deviceId).getWipeRequestedAt()).isNull();
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke", "POST:/devices/{id}/wipe",
            "POST:/devices/{id}/wipe/cancel"})
    void theConfirmationPageShowsTheTabletsBacklogBeforeTheDecision() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        JwtService.JwtToken token = sessionOn(deviceId);
        AppUserDetails holder = holderOf(token);
        jdbc.update("UPDATE api_sessions SET undelivered_count = 7, undelivered_oldest_at = ?, backlog_reported_at = ? WHERE jti = ?",
                now() - 3_600_000L, now(), token.jti());

        String html = page("/devices/" + deviceId + "/revoke");

        assertThat(html).contains("id=\"revokeOnly\"").contains("id=\"revokeAndWipe\"");
        assertThat(html).contains("برای همیشه از بین می‌روند");
        assertThat(html).contains(">7<");
        assertThat(html).contains(holder.getUsername());

        // And the list shows the same number for that device.
        assertThat(page("/devices")).contains(">7<");
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke"})
    void withoutTheWipePermissionThePageOffersOnlyThePlainRevoke() throws Exception {
        long deviceId = approvedDevice(new Tablet());

        String html = page("/devices/" + deviceId + "/revoke");

        assertThat(html).contains("id=\"revokeOnly\"").doesNotContain("id=\"revokeAndWipe\"");
        assertThat(html).as("a tablet that never reported is not shown as zero").contains("گزارش نشده");
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke", "POST:/devices/{id}/wipe",
            "POST:/devices/{id}/wipe/cancel"})
    void aRevokedDeviceCanBeOrderedToWipeAndTheOrderWithdrawnFromThePanel() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now());

        assertThat(page("/devices/" + deviceId + "/revoke")).contains("id=\"orderWipe\"");
        mockMvc.perform(post("/devices/{id}/wipe", deviceId).with(csrf()))
                .andExpect(redirectedUrl("/devices?filter=revoked"));
        assertThat(device(deviceId).getWipeRequestedAt()).isNotNull();
        assertThat(page("/devices?filter=revoked")).contains("در انتظار اتصال تبلت");

        String ordered = page("/devices/" + deviceId + "/revoke");
        assertThat(ordered).contains("id=\"cancelWipe\"");
        mockMvc.perform(post("/devices/{id}/wipe/cancel", deviceId).with(csrf()))
                .andExpect(redirectedUrl("/devices?filter=revoked"));
        assertThat(device(deviceId).getWipeRequestedAt()).isNull();
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke", "POST:/devices/{id}/wipe",
            "POST:/devices/{id}/wipe/cancel"})
    void theListAndThePageSayWhereADeliveredOrderStands() throws Exception {
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);
        long orderedAt = challenge(deviceId, fingerprint(deviceId)).get("wipeOrderedAt").asLong();

        assertThat(page("/devices?filter=revoked")).contains("به تبلت ابلاغ شد");
        String delivered = page("/devices/" + deviceId + "/revoke");
        assertThat(delivered).doesNotContain("id=\"cancelWipe\"").contains("دیگر قابل لغو نیست");

        confirmWipe(deviceId, orderedAt, tablet.sign(DeviceKeys.wipeMessage(deviceId, orderedAt)));
        assertThat(page("/devices?filter=revoked")).contains("پاک‌سازی تأیید شد");
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices"})
    void theWipeEndpointsNeedTheirOwnPermissions() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now());

        mockMvc.perform(get("/devices/{id}/revoke", deviceId)).andExpect(redirectedUrl("/"));
        mockMvc.perform(post("/devices/{id}/wipe", deviceId).with(csrf())).andExpect(redirectedUrl("/"));
        mockMvc.perform(post("/devices/{id}/wipe/cancel", deviceId).with(csrf())).andExpect(redirectedUrl("/"));
        assertThat(device(deviceId).getWipeRequestedAt()).isNull();
    }

    @Test
    void theWipePermissionsAreSeededToWhoeverMayRevoke() {
        for (String code : List.of("POST:/devices/{id}/wipe", "POST:/devices/{id}/wipe/cancel")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permissions WHERE code = ?", Long.class, code))
                    .as("permission row %s", code).isEqualTo(1L);
            List<String> holders = jdbc.queryForList("""
                    SELECT r.code FROM roles r
                      JOIN role_permissions rp ON rp.role_id = r.id
                      JOIN permissions p ON p.id = rp.permission_id
                     WHERE p.code = ?
                    """, String.class, code);
            List<String> revokers = jdbc.queryForList("""
                    SELECT r.code FROM roles r
                      JOIN role_permissions rp ON rp.role_id = r.id
                      JOIN permissions p ON p.id = rp.permission_id
                     WHERE p.code = 'POST:/devices/{id}/revoke'
                    """, String.class);
            assertThat(holders).as("holders of %s", code).containsExactlyInAnyOrderElementsOf(revokers);
            assertThat(holders).contains("ADMIN");
        }
    }

    @Test
    void whoeverOrderedAWipeCannotBeDeleted() throws Exception {
        User orderer = seedUser();
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, orderer.getId(), now(), true);

        assertThat(userRepository.isReferencedAnywhere(orderer.getId())).isTrue();
        // The device row is never deleted, so the attribution must outlive the person.
        jdbc.update("UPDATE devices SET revoked_by = ? WHERE id = ?", ADMIN, deviceId);
        assertThat(userRepository.isReferencedAnywhere(orderer.getId()))
                .as("the wipe order alone holds the reference").isTrue();
    }

    // =====================================================================================
    // With the switch off (V28): what a revoke does not do, and what still reaches the tablet
    // =====================================================================================

    @Test
    void aSessionOpenedWithTheSwitchOffIsNotEndedByARevokeButTheOrderStillReachesTheTablet() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        appSettingsService.saveDeviceEnrollmentEnabled(false);
        // Off: the login reads no device field, so the session carries no device id.
        User user = seedUser();
        AppUserDetails principal = TestPrincipals.of(user, Set.of("OPERATOR"), List.of());
        JwtService.JwtToken token = jwtService.issueToken(principal);
        apiSessionService.register(principal, token, "tablet", null, null, null);

        DeviceEnrollmentService.RevokeResult result = deviceEnrollmentService.revoke(deviceId, ADMIN, now(), true);

        // The premise the tablet's own status check exists for: the revoke cannot reach this session.
        assertThat(result.sessionsRevoked()).isZero();
        assertThat(apiSessionRepository.findByJti(token.jti()).orElseThrow().isRevoked()).isFalse();
        // And what the check finds instead: the order, with enforced = false.
        JsonNode answer = challenge(deviceId, fingerprint(deviceId));
        assertThat(answer.get("enforced").asBoolean()).isFalse();
        assertThat(answer.get("wipe").asBoolean()).isTrue();
    }

    @Test
    void aRevokeWithTheSwitchOnIsReportedToTheTabletsCheck() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        deviceEnrollmentService.revoke(deviceId, ADMIN, now());

        JsonNode answer = challenge(deviceId, fingerprint(deviceId));

        assertThat(answer.get("enforced").asBoolean()).isTrue();
        assertThat(answer.get("deviceStatus").asText()).isEqualTo("REVOKED");
        assertThat(answer.has("wipe")).isFalse();
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke", "POST:/devices/{id}/wipe"})
    void withTheSwitchOffThePanelSaysARevokeStopsNoLoginAndWhenAWipeLands() throws Exception {
        long deviceId = approvedDevice(new Tablet());
        appSettingsService.saveDeviceEnrollmentEnabled(false);

        String page = page("/devices/" + deviceId + "/revoke");
        assertThat(page).contains("id=\"revokeSwitchOff\"").contains("جلوی ورود دوباره با این تبلت را");
        assertThat(page).contains("حداکثر نیم ساعت پس از آنلاین بودن");
        assertThat(page("/devices")).contains("فقط نشست‌هایی را می‌بندد که در زمان روشن بودن ثبت دستگاه باز شده‌اند");

        MvcResult result = mockMvc.perform(post("/devices/{id}/revoke", deviceId).with(csrf()))
                .andExpect(redirectedUrl("/devices"))
                .andReturn();
        assertThat((String) result.getFlashMap().get("successMessage"))
                .contains("جلوی ورود دوباره با این تبلت را نمی‌گیرد")
                .doesNotContain("دیگر هیچ کاربری با این دستگاه وارد نمی‌شود");
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke", "POST:/devices/{id}/wipe"})
    void withTheSwitchOnThePanelPromisesWhatARevokeDoes() throws Exception {
        long deviceId = approvedDevice(new Tablet());

        String page = page("/devices/" + deviceId + "/revoke");
        assertThat(page).doesNotContain("id=\"revokeSwitchOff\"").contains("از این پس هیچ کاربری با آن وارد نمی‌شود");

        MvcResult result = mockMvc.perform(post("/devices/{id}/revoke", deviceId).param("wipe", "true").with(csrf()))
                .andExpect(redirectedUrl("/devices"))
                .andReturn();
        assertThat((String) result.getFlashMap().get("successMessage"))
                .contains("دیگر هیچ کاربری با این دستگاه وارد نمی‌شود")
                .contains("حداکثر نیم ساعت");
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

    private long pendingRequest(Tablet tablet) {
        return deviceEnrollmentService.requestEnrollment(tablet.publicKey(), "wipe-test", "JUnit",
                "10.9.8." + (int) (Math.random() * 200), now()).requestId();
    }

    private long approvedDevice(Tablet tablet) throws Exception {
        long requestId = pendingRequest(tablet);
        String code = deviceEnrollmentService.issueCode(requestId, ADMIN, now()).enrollmentCode();
        deviceEnrollmentService.confirmEnrollment(requestId, code,
                tablet.sign(DeviceKeys.enrollMessage(requestId, code)), now());
        return requestId;
    }

    private JwtService.JwtToken sessionOn(long deviceId) {
        User user = seedUser();
        AppUserDetails principal = TestPrincipals.of(user, Set.of("OPERATOR"), List.of());
        JwtService.JwtToken token = jwtService.issueToken(principal);
        apiSessionService.register(principal, token, "tablet", null, null, deviceId);
        return token;
    }

    private AppUserDetails holderOf(JwtService.JwtToken token) {
        ApiSession session = apiSessionRepository.findByJti(token.jti()).orElseThrow();
        User user = userRepository.findById(session.getUserId()).orElseThrow();
        return TestPrincipals.of(user, Set.of("OPERATOR"), List.of());
    }

    private User seedUser() {
        long now = now();
        User user = new User();
        user.setUsername("wipe-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Wipe Test User");
        user.setPasswordHash("x");
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    private Device device(long id) {
        return deviceRepository.findById(id).orElseThrow();
    }

    private String fingerprint(long deviceId) {
        return device(deviceId).getKeyFingerprint();
    }

    private JsonNode challenge(long deviceId, String fingerprint) throws Exception {
        MvcResult result = postJson("/api/auth/challenge", Map.of("deviceId", deviceId, "keyFingerprint", fingerprint));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private MvcResult confirmWipe(long deviceId, long orderedAt, String signature) throws Exception {
        return postJson("/api/devices/enrollment-requests/wipe-confirm",
                Map.of("deviceId", deviceId, "wipeOrderedAt", orderedAt, "signature", signature));
    }

    private MvcResult postJson(String path, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String page(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertError(MvcResult result, DeviceAuthError expected) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("status for %s: %s", expected, result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expected.status().value());
        JsonNode body = json(result);
        assertThat(body.get("code").asText()).isEqualTo(expected.name());
        assertThat(body.get("message").asText()).isEqualTo(expected.messageFa());
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
