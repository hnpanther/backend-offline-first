package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap §41 — how long a round stays on the tablet, end to end on the server: the seeded rows,
 * the Settings page, and what {@code /api/bootstrap} actually sends.
 *
 * <p>Not {@code @Transactional}: the bootstrap case signs in through {@code /api/auth/login},
 * which writes its session and audit rows the way production does. Every case instead puts the
 * three rows back to their seeded values afterwards.
 */
class DeviceRetentionSettingsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AppSettingsService appSettingsService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void restoreSeededValues() {
        for (String[] row : new String[][]{
                {AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS, "24"},
                {AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED, "false"},
                {AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS, "168"},
                // Only moved if the half-save guard were broken; restored so that failure
                // cannot leak into another class's assertions.
                {AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS, "10000"}}) {
            jdbcTemplate.update("INSERT INTO app_settings (setting_key, value, updated_at) VALUES (?, ?, 0) "
                    + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value", row[0], row[1]);
        }
    }

    /** The numeric half of the Settings form, which every save carries — at today's defaults. */
    private static MockHttpServletRequestBuilder settingsForm() {
        return post("/settings").with(csrf())
                .param("excelExportMaxRows", "10000")
                .param("auditRetentionDays", "90")
                .param("jwtExpiryMinutes", "480")
                .param("maxImagesPerField", "3")
                .param("maxAudiosPerField", "1")
                .param("maxVideosPerField", "1")
                .param("maxAudioSeconds", "120")
                .param("maxVideoSeconds", "120");
    }

    private String stored(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT value FROM app_settings WHERE setting_key = ?", String.class, key);
    }

    // ---------------------------------------------------------------------------------------
    // V24
    // ---------------------------------------------------------------------------------------

    @Test
    void v24SeedsTheThreeRowsWithThePurgeOfUndeliveredRoundsOff() {
        assertThat(stored(AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS)).isEqualTo("24");
        assertThat(stored(AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED)).isEqualTo("false");
        assertThat(stored(AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS)).isEqualTo("168");

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(24);
        assertThat(retention.unsyncedPurgeEnabled()).isFalse();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(168);
    }

    @Test
    void reRunningTheSeedNeverOverwritesAnAdministratorsChoice() {
        appSettingsService.saveDeviceUnsyncedPurgeEnabled(true);
        appSettingsService.saveDeviceRetentionHours(12, 240);

        jdbcTemplate.update("INSERT INTO app_settings (setting_key, value, updated_at) VALUES "
                + "('device.synced_retention_hours', '24', 0), "
                + "('device.unsynced_purge_enabled', 'false', 0), "
                + "('device.unsynced_retention_hours', '168', 0) "
                + "ON CONFLICT (setting_key) DO NOTHING");

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(12);
        assertThat(retention.unsyncedPurgeEnabled()).isTrue();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(240);
    }

    @Test
    void aDatabaseWithoutTheRowsStillAnswersWithTheDefaults() {
        // A database restored from before V24 and not yet migrated, or a row deleted by hand.
        jdbcTemplate.update("DELETE FROM app_settings WHERE setting_key LIKE 'device.%'");

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(24);
        assertThat(retention.unsyncedPurgeEnabled()).isFalse();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(168);
    }

    // ---------------------------------------------------------------------------------------
    // The Settings page
    // ---------------------------------------------------------------------------------------

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN",
            authorities = {"GET:/settings", "POST:/settings"})
    void thePageRendersTheThreeFieldsWithTheirCurrentValuesAndTheSwitchMarker() throws Exception {
        appSettingsService.saveDeviceRetentionHours(36, 200);

        String html = mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"deviceRetentionCard\"");
        assertThat(html).containsPattern("name=\"deviceSyncedRetentionHours\"[^>]*value=\"36\"");
        assertThat(html).containsPattern("name=\"deviceUnsyncedRetentionHours\"[^>]*value=\"200\"");
        assertThat(html).containsPattern("name=\"deviceSyncedRetentionHours\"[^>]*min=\"1\"[^>]*max=\"720\"");
        assertThat(html).containsPattern("name=\"deviceUnsyncedRetentionHours\"[^>]*min=\"72\"[^>]*max=\"8760\"");
        assertThat(html).contains("name=\"_deviceUnsyncedPurgeEnabled\"");
        // Off by default, so the switch must render unticked — not told the opposite of the truth.
        assertThat(html).doesNotContainPattern("id=\"deviceUnsyncedPurgeEnabled\"[^>]*checked");
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN",
            authorities = {"GET:/settings", "POST:/settings"})
    void theSwitchRendersTickedOnceItIsOn() throws Exception {
        appSettingsService.saveDeviceUnsyncedPurgeEnabled(true);

        String html = mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("id=\"deviceUnsyncedPurgeEnabled\"[^>]*checked");
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void savingTheFormStoresAllThree() throws Exception {
        mockMvc.perform(settingsForm()
                        .param("deviceSyncedRetentionHours", "48")
                        .param("deviceUnsyncedRetentionHours", "336")
                        .param("_deviceUnsyncedPurgeEnabled", "on")
                        .param("deviceUnsyncedPurgeEnabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(48);
        assertThat(retention.unsyncedPurgeEnabled()).isTrue();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(336);
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void untickingTheSwitchTurnsThePurgeOffAgain() throws Exception {
        appSettingsService.saveDeviceUnsyncedPurgeEnabled(true);

        mockMvc.perform(settingsForm()
                        .param("deviceSyncedRetentionHours", "24")
                        .param("deviceUnsyncedRetentionHours", "168")
                        .param("_deviceUnsyncedPurgeEnabled", "on"))
                .andExpect(status().is3xxRedirection());

        assertThat(appSettingsService.getDeviceRetention().unsyncedPurgeEnabled()).isFalse();
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void aStalePageThatNeverHadTheseFieldsLeavesThemAlone() throws Exception {
        // A page cached before V24, or a script that posts only the older fields. It must neither
        // fail the save nor reset the retention — and above all must not switch the purge off or
        // on by itself.
        appSettingsService.saveDeviceRetentionHours(12, 240);
        appSettingsService.saveDeviceUnsyncedPurgeEnabled(true);

        mockMvc.perform(settingsForm())
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("successMessage"));

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(12);
        assertThat(retention.unsyncedPurgeEnabled()).isTrue();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(240);
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void anEmptiedFieldIsReadAsNotAskedRatherThanZero() throws Exception {
        // An empty number input posts "", which binds to null. Zero would be "purge now".
        appSettingsService.saveDeviceRetentionHours(30, 100);

        mockMvc.perform(settingsForm()
                        .param("deviceSyncedRetentionHours", "")
                        .param("deviceUnsyncedRetentionHours", ""))
                .andExpect(status().is3xxRedirection());

        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.syncedRetentionHours()).isEqualTo(30);
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(100);
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void anOutOfRangeValueIsRefusedAndNothingElseOnThePageIsSaved() throws Exception {
        // Validated before the first write, so the page is not left half-saved.
        mockMvc.perform(post("/settings").with(csrf())
                        .param("excelExportMaxRows", "20000")
                        .param("auditRetentionDays", "90")
                        .param("jwtExpiryMinutes", "480")
                        .param("maxImagesPerField", "3")
                        .param("maxAudiosPerField", "1")
                        .param("maxVideosPerField", "1")
                        .param("maxAudioSeconds", "120")
                        .param("maxVideoSeconds", "120")
                        .param("deviceSyncedRetentionHours", "24")
                        .param("deviceUnsyncedRetentionHours", "48")
                        .param("_deviceUnsyncedPurgeEnabled", "on")
                        .param("deviceUnsyncedPurgeEnabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(appSettingsService.getExcelExportMaxRows()).isEqualTo(10_000);
        AppSettingsService.DeviceRetention retention = appSettingsService.getDeviceRetention();
        assertThat(retention.unsyncedPurgeEnabled()).isFalse();
        assertThat(retention.unsyncedRetentionHours()).isEqualTo(168);

        mockMvc.perform(settingsForm().param("deviceSyncedRetentionHours", "0"))
                .andExpect(flash().attributeExists("errorMessage"));
        mockMvc.perform(settingsForm().param("deviceSyncedRetentionHours", "721"))
                .andExpect(flash().attributeExists("errorMessage"));
        mockMvc.perform(settingsForm().param("deviceUnsyncedRetentionHours", "8761"))
                .andExpect(flash().attributeExists("errorMessage"));
        assertThat(appSettingsService.getDeviceRetention().syncedRetentionHours()).isEqualTo(24);
    }

    @Test
    @WithAppUser(username = "settings-reader", roles = "SUPERVISOR", authorities = "GET:/settings")
    void savingNeedsThePostAuthority() throws Exception {
        mockMvc.perform(settingsForm()
                        .param("_deviceUnsyncedPurgeEnabled", "on")
                        .param("deviceUnsyncedPurgeEnabled", "true"))
                // The panel answers a refused action by sending the user home, as every page does.
                .andExpect(redirectedUrl("/"));

        assertThat(appSettingsService.getDeviceRetention().unsyncedPurgeEnabled()).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // What the tablet receives
    // ---------------------------------------------------------------------------------------

    private String signInAsAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    @Test
    void bootstrapSendsTheSeededRetentionToTheTablet() throws Exception {
        String token = signInAsAdmin();

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceRetention.syncedRetentionHours").value(24))
                .andExpect(jsonPath("$.deviceRetention.unsyncedPurgeEnabled").value(false))
                .andExpect(jsonPath("$.deviceRetention.unsyncedRetentionHours").value(168))
                // The blocks that were already there are untouched by the new one.
                .andExpect(jsonPath("$.attachmentLimits.maxImagesPerField").isNumber())
                .andExpect(jsonPath("$.mobilePolicy.nfcStrictSerialMatch").isBoolean());
    }

    @Test
    void aChangeInThePanelReachesTheNextBootstrapWithoutARestart() throws Exception {
        String token = signInAsAdmin();
        appSettingsService.saveDeviceRetentionHours(6, 96);
        appSettingsService.saveDeviceUnsyncedPurgeEnabled(true);

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceRetention.syncedRetentionHours").value(6))
                .andExpect(jsonPath("$.deviceRetention.unsyncedPurgeEnabled").value(true))
                .andExpect(jsonPath("$.deviceRetention.unsyncedRetentionHours").value(96));
    }

    @Test
    void aHandEditedRowNeverReachesTheTabletOutOfRange() throws Exception {
        jdbcTemplate.update("UPDATE app_settings SET value = '0' WHERE setting_key = ?",
                AppSettingsService.KEY_DEVICE_SYNCED_RETENTION_HOURS);
        jdbcTemplate.update("UPDATE app_settings SET value = '5' WHERE setting_key = ?",
                AppSettingsService.KEY_DEVICE_UNSYNCED_RETENTION_HOURS);
        jdbcTemplate.update("UPDATE app_settings SET value = 'maybe' WHERE setting_key = ?",
                AppSettingsService.KEY_DEVICE_UNSYNCED_PURGE_ENABLED);
        String token = signInAsAdmin();

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceRetention.syncedRetentionHours").value(24))
                .andExpect(jsonPath("$.deviceRetention.unsyncedPurgeEnabled").value(false))
                .andExpect(jsonPath("$.deviceRetention.unsyncedRetentionHours").value(72));
    }
}
