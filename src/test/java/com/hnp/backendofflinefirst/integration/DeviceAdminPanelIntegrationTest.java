package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ApiSessionRevokeReason;
import com.hnp.backendofflinefirst.domain.DeviceStatus;
import com.hnp.backendofflinefirst.dto.device.DeviceDtos;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The approver's side of device enrolment: the panel page, its permissions, and the switch.
 *
 * <p>Committed state, like {@link DeviceEnrollmentIntegrationTest}, and for the same reason —
 * a revoke has to reach sessions that exist outside the test's own transaction.
 */
class DeviceAdminPanelIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String[] NEW_PERMISSIONS = {
            "GET:/devices", "POST:/devices/{id}/code", "POST:/devices/{id}/revoke"
    };

    @Autowired WebApplicationContext context;
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
        appSettingsService.saveDeviceEnrollmentEnabled(false);
    }

    @AfterEach
    void cleanUp() {
        appSettingsService.saveDeviceEnrollmentEnabled(false);
        jdbc.update("DELETE FROM api_sessions WHERE device_id IS NOT NULL");
        jdbc.update("DELETE FROM device_auth_challenges");
        jdbc.update("DELETE FROM devices");
        jdbc.update("DELETE FROM users WHERE username LIKE 'panel-dev-%'");
    }

    // ------------------------------------------------------------------ the page

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/code"})
    void aPendingRequestIsListedAndItsCodeIsShownOnceAndNeverInAUrl() throws Exception {
        appSettingsService.saveDeviceEnrollmentEnabled(true);
        long requestId = pendingRequest(new Tablet(), "تبلت انبار");

        String list = page(get("/devices"));
        assertThat(list).contains("تبلت انبار").contains(">" + requestId + "<");

        MvcResult issued = mockMvc.perform(post("/devices/{id}/code", requestId).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        DeviceDtos.IssuedCode code = (DeviceDtos.IssuedCode) issued.getFlashMap().get("issuedCode");
        assertThat(code).isNotNull();
        assertThat(code.enrollmentCode()).matches("\\d{8}");
        // Flash, not a parameter: a code in a URL ends up in history and access logs.
        assertThat(issued.getResponse().getRedirectedUrl()).isEqualTo("/devices");
        // Only the hash is stored.
        Device stored = deviceRepository.findById(requestId).orElseThrow();
        assertThat(stored.getCodeHash()).isEqualTo(DeviceKeys.sha256Hex(code.enrollmentCode()));
        assertThat(stored.getCodeIssuedBy()).isEqualTo(1L);

        String shown = page(get("/devices").flashAttrs(issued.getFlashMap()));
        assertThat(shown).contains(code.enrollmentCode().substring(0, 4) + " " + code.enrollmentCode().substring(4));

        // And the next request does not show it again.
        assertThat(page(get("/devices"))).doesNotContain(code.enrollmentCode().substring(0, 4) + " ");
    }

    @Test
    @WithAppUser(authorities = "GET:/devices")
    void thePageSaysPlainlyWhenTheSwitchIsOff() throws Exception {
        assertThat(page(get("/devices"))).contains("ثبت دستگاه خاموش است");

        appSettingsService.saveDeviceEnrollmentEnabled(true);
        assertThat(page(get("/devices"))).doesNotContain("ثبت دستگاه خاموش است");
    }

    @Test
    @WithAppUser(authorities = {"GET:/devices", "POST:/devices/{id}/revoke"})
    void revokingFromThePanelEndsTheDevicesSessions() throws Exception {
        appSettingsService.saveDeviceEnrollmentEnabled(true);
        Tablet tablet = new Tablet();
        long deviceId = approvedDevice(tablet);
        User operator = seedUser();
        AppUserDetails principal = TestPrincipals.of(operator, Set.of("OPERATOR"), List.of());
        JwtService.JwtToken token = jwtService.issueToken(principal);
        apiSessionService.register(principal, token, "tablet", null, null, deviceId);

        MvcResult result = mockMvc.perform(post("/devices/{id}/revoke", deviceId).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat((String) result.getFlashMap().get("successMessage")).contains("1 نشست");
        assertThat(deviceRepository.findById(deviceId).orElseThrow().getStatus()).isEqualTo(DeviceStatus.REVOKED);
        ApiSession session = apiSessionRepository.findByJti(token.jti()).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
        assertThat(session.getRevokeReason()).isEqualTo(ApiSessionRevokeReason.DEVICE_REVOKED);
        assertThat(session.getRevokedBy()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    @WithAppUser(authorities = "GET:/api-sessions")
    void everyPanelEndpointNeedsItsOwnPermission() throws Exception {
        appSettingsService.saveDeviceEnrollmentEnabled(true);
        long requestId = pendingRequest(new Tablet(), "untouchable");

        // The panel answers a missing permission by sending the user home (webAccessDeniedHandler),
        // as for every other page — what matters is that nothing behind it ran.
        mockMvc.perform(get("/devices")).andExpect(redirectedUrl("/"));
        mockMvc.perform(post("/devices/{id}/code", requestId).with(csrf())).andExpect(redirectedUrl("/"));
        mockMvc.perform(post("/devices/{id}/revoke", requestId).with(csrf())).andExpect(redirectedUrl("/"));

        Device untouched = deviceRepository.findById(requestId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(untouched.getCodeHash()).isNull();
    }

    @Test
    void theNewPermissionsAreSeededAndHeldByAdministratorsOnly() {
        for (String code : NEW_PERMISSIONS) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permissions WHERE code = ?", Long.class, code))
                    .as("permission row %s", code).isEqualTo(1L);
            List<String> holders = jdbc.queryForList("""
                    SELECT r.code FROM roles r
                      JOIN role_permissions rp ON rp.role_id = r.id
                      JOIN permissions p ON p.id = rp.permission_id
                     WHERE p.code = ?
                    """, String.class, code);
            // Derived from who may revoke a mobile session — ADMIN in the seed. Not HIGH_USER,
            // whose blanket grant explicitly excludes the 'admin' category, and not a supervisor
            // until an administrator decides so on the Roles page.
            assertThat(holders).as("holders of %s", code).containsExactly("ADMIN");
        }
    }

    // ------------------------------------------------------------------ the switch

    @Test
    @WithAppUser(authorities = {"GET:/settings", "POST:/settings"})
    void theSettingsPageCarriesTheSwitchAndItsSeparatelyNamedMarker() throws Exception {
        String html = page(get("/settings"));

        // One name, one value — AGENTS.md #171. A second input named deviceEnrollmentEnabled
        // would be read instead of the box.
        assertThat(inputsNamed(html, "deviceEnrollmentEnabled")).hasSize(1)
                .allSatisfy(tag -> assertThat(tag).contains("type=\"checkbox\""));
        assertThat(inputsNamed(html, "_deviceEnrollmentEnabled")).hasSize(1);
        assertThat(checkboxIsChecked(html, "deviceEnrollmentEnabled")).as("seeded off").isFalse();
    }

    @Test
    @WithAppUser(authorities = {"GET:/settings", "POST:/settings"})
    void theSwitchRoundTripsAndAStalePageLeavesItAlone() throws Exception {
        mockMvc.perform(settingsForm().param("_deviceEnrollmentEnabled", "on")
                        .param("deviceEnrollmentEnabled", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(appSettingsService.isDeviceEnrollmentEnabled()).isTrue();
        assertThat(checkboxIsChecked(page(get("/settings")), "deviceEnrollmentEnabled")).isTrue();

        // A page from before this switch existed: no marker, no value — must change nothing.
        mockMvc.perform(settingsForm()).andExpect(status().is3xxRedirection());
        assertThat(appSettingsService.isDeviceEnrollmentEnabled()).isTrue();

        // Unticked: the marker alone.
        mockMvc.perform(settingsForm().param("_deviceEnrollmentEnabled", "on"))
                .andExpect(status().is3xxRedirection());
        assertThat(appSettingsService.isDeviceEnrollmentEnabled()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

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

    private long pendingRequest(Tablet tablet, String label) {
        return deviceEnrollmentService.requestEnrollment(tablet.publicKey(), label, "JUnit",
                "10.1.2." + (int) (Math.random() * 200), System.currentTimeMillis()).requestId();
    }

    private long approvedDevice(Tablet tablet) throws Exception {
        long requestId = pendingRequest(tablet, "approved");
        String code = deviceEnrollmentService.issueCode(requestId, 1L, System.currentTimeMillis()).enrollmentCode();
        deviceEnrollmentService.confirmEnrollment(requestId, code,
                tablet.sign(DeviceKeys.enrollMessage(requestId, code)), System.currentTimeMillis());
        return requestId;
    }

    private User seedUser() {
        long now = System.currentTimeMillis();
        User user = new User();
        user.setUsername("panel-dev-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPersonnelCode("PC-" + UUID.randomUUID());
        user.setFullName("Panel Device User");
        user.setPasswordHash("x");
        user.setActive(true);
        user.setAuthType(UserAuthType.LOCAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user);
    }

    private MockHttpServletRequestBuilder settingsForm() {
        return post("/settings").with(csrf())
                .param("excelExportMaxRows", String.valueOf(appSettingsService.getExcelExportMaxRows()))
                .param("auditRetentionDays", String.valueOf(appSettingsService.getAuditRetentionDays()))
                .param("jwtExpiryMinutes", String.valueOf(appSettingsService.getJwtExpiryMinutes()))
                .param("maxImagesPerField", "3")
                .param("maxAudiosPerField", "1")
                .param("maxVideosPerField", "1")
                .param("maxAudioSeconds", "120")
                .param("maxVideoSeconds", "120");
    }

    private String page(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> inputsNamed(String html, String name) {
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            if (m.group().contains("name=\"" + name + "\"")) {
                found.add(m.group());
            }
        }
        return found;
    }

    private static boolean checkboxIsChecked(String html, String name) {
        return inputsNamed(html, name).stream()
                .filter(tag -> tag.contains("type=\"checkbox\""))
                .findFirst()
                .orElseThrow()
                .contains("checked");
    }
}
