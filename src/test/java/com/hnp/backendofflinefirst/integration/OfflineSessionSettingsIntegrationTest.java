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

import java.nio.charset.StandardCharsets;
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
 * The offline-session ceiling (V28): seeded, edited on the Settings page, and delivered to the
 * tablet on bootstrap — plus the server time a login now carries, which is what lets the tablet
 * measure its own clock from the first moment of a session.
 *
 * <p>The server never acts on the ceiling; the tablet does (the PWA's {@code offlineSession.ts}).
 * What is pinned here is that the value the panel shows is the value every tablet receives, that a
 * stale page cannot switch it on or off, and that a hand-edited row never reaches a tablet out of
 * range.
 */
class OfflineSessionSettingsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AppSettingsService appSettingsService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        // Before as well as after: another class in this JVM may have deleted the rows to prove
        // its own fallback, and a hand-edit below that matches no row would test nothing.
        restoreSeededValues();
    }

    @AfterEach
    void restoreSeededValues() {
        for (String[] row : new String[][]{
                {AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED, "false"},
                {AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS, "72"},
                {AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS, "10000"}}) {
            jdbcTemplate.update("INSERT INTO app_settings (setting_key, value, updated_at) VALUES (?, ?, 0) "
                    + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value", row[0], row[1]);
        }
    }

    // ---------------------------------------------------------------------------------- V28

    @Test
    void v28SeedsTheCeilingOffWithSeventyTwoHours() {
        assertThat(stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED)).isEqualTo("false");
        assertThat(stored(AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS)).isEqualTo("72");
        assertThat(appSettingsService.getOfflineSessionLimit())
                .isEqualTo(new AppSettingsService.OfflineSessionLimit(false, 72));
    }

    @Test
    void aDatabaseWithoutTheRowsStillAnswersWithTheDefaults() {
        jdbcTemplate.update("DELETE FROM app_settings WHERE setting_key IN (?, ?)",
                AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED,
                AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS);

        assertThat(appSettingsService.getOfflineSessionLimit())
                .isEqualTo(new AppSettingsService.OfflineSessionLimit(false, 72));
    }

    // ---------------------------------------------------------------------------- the page

    @Test
    @WithAppUser(authorities = {"GET:/settings", "POST:/settings"})
    void thePageRendersTheSwitchItsMarkerAndTheHours() throws Exception {
        String html = page();

        assertThat(html).contains("id=\"offlineSessionLimitCard\"");
        assertThat(html).contains("name=\"_offlineSessionLimitEnabled\"");
        assertThat(html).containsPattern("name=\"offlineSessionLimitHours\"[^>]*value=\"72\"");
        assertThat(html).containsPattern("min=\"8\"[^>]*max=\"720\"|max=\"720\"[^>]*min=\"8\"");
        assertThat(html).contains("هیچ داده‌ای پاک نمی‌شود");
    }

    @Test
    @WithAppUser(authorities = {"GET:/settings", "POST:/settings"})
    void savingStoresBothAndUntickingTurnsTheCeilingOffAgain() throws Exception {
        mockMvc.perform(settingsForm()
                        .param("_offlineSessionLimitEnabled", "on")
                        .param("offlineSessionLimitEnabled", "true")
                        .param("offlineSessionLimitHours", "24"))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("successMessage"));
        assertThat(appSettingsService.getOfflineSessionLimit())
                .isEqualTo(new AppSettingsService.OfflineSessionLimit(true, 24));
        assertThat(page()).containsPattern("id=\"offlineSessionLimitEnabled\"[^>]*checked");

        mockMvc.perform(settingsForm().param("_offlineSessionLimitEnabled", "on"))
                .andExpect(redirectedUrl("/settings"));
        assertThat(appSettingsService.getOfflineSessionLimit().enabled()).isFalse();
        assertThat(appSettingsService.getOfflineSessionLimit().hours()).as("hours not asked, kept").isEqualTo(24);
    }

    @Test
    @WithAppUser(authorities = {"GET:/settings", "POST:/settings"})
    void aStalePageThatNeverHadTheseFieldsLeavesThemAlone() throws Exception {
        appSettingsService.saveOfflineSessionLimitEnabled(true);
        appSettingsService.saveOfflineSessionLimitHours(48);

        mockMvc.perform(settingsForm()).andExpect(redirectedUrl("/settings"));

        assertThat(appSettingsService.getOfflineSessionLimit())
                .isEqualTo(new AppSettingsService.OfflineSessionLimit(true, 48));
    }

    @Test
    @WithAppUser(authorities = {"GET:/settings", "POST:/settings"})
    void anOutOfRangeValueIsRefusedAndNothingElseOnThePageIsSaved() throws Exception {
        mockMvc.perform(settingsForm()
                        .param("excelExportMaxRows", "20000")
                        .param("_offlineSessionLimitEnabled", "on")
                        .param("offlineSessionLimitEnabled", "true")
                        .param("offlineSessionLimitHours", "2"))
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attributeExists("errorMessage"));

        assertThat(appSettingsService.getOfflineSessionLimit())
                .isEqualTo(new AppSettingsService.OfflineSessionLimit(false, 72));
        assertThat(stored(AppSettingsService.KEY_EXCEL_EXPORT_MAX_ROWS)).as("no half-save").isEqualTo("10000");
    }

    // ------------------------------------------------------------------- what the tablet gets

    @Test
    void bootstrapSendsTheCeilingAndAChangeReachesTheNextOneWithoutARestart() throws Exception {
        String token = signInAsAdmin();

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offlineSession.limitEnabled").value(false))
                .andExpect(jsonPath("$.offlineSession.limitHours").value(72))
                .andExpect(jsonPath("$.deviceRetention.syncedRetentionHours").isNumber());

        appSettingsService.saveOfflineSessionLimitEnabled(true);
        appSettingsService.saveOfflineSessionLimitHours(12);

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.offlineSession.limitEnabled").value(true))
                .andExpect(jsonPath("$.offlineSession.limitHours").value(12));
    }

    @Test
    void aHandEditedRowNeverReachesTheTabletOutOfRange() throws Exception {
        jdbcTemplate.update("UPDATE app_settings SET value = '1' WHERE setting_key = ?",
                AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_HOURS);
        jdbcTemplate.update("UPDATE app_settings SET value = 'yes please' WHERE setting_key = ?",
                AppSettingsService.KEY_DEVICE_OFFLINE_SESSION_LIMIT_ENABLED);
        String token = signInAsAdmin();

        mockMvc.perform(get("/api/bootstrap").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.offlineSession.limitEnabled").value(false))
                .andExpect(jsonPath("$.offlineSession.limitHours").value(8));
    }

    @Test
    void aLoginCarriesTheServersClock() throws Exception {
        long before = System.currentTimeMillis();
        MvcResult login = login();
        long after = System.currentTimeMillis();

        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("serverTime").asLong()).isBetween(before, after);
        // Everything an older app reads is where it always was.
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("expiresAt").asLong()).isGreaterThan(after);
    }

    // ------------------------------------------------------------------------------ helpers

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

    private String page() throws Exception {
        return mockMvc.perform(get("/settings")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String stored(String key) {
        return jdbcTemplate.queryForObject("SELECT value FROM app_settings WHERE setting_key = ?", String.class, key);
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "admin", "password", "admin123"))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String signInAsAdmin() throws Exception {
        return objectMapper.readTree(login().getResponse().getContentAsString()).get("accessToken").asText();
    }
}
