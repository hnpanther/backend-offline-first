package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.service.AppSettingsService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two mobile switches on the Settings page: the annotate-before-save step and the NFC
 * scan rule. Both default to <b>on</b>, and that is what makes this worth an integration test.
 *
 * <p>An unchecked HTML checkbox submits nothing at all — and so does a form that never carried
 * the field. Binding the parameter with {@code defaultValue = "false"} makes those two cases
 * identical, so a save from an older cached page, a re-submitted bookmark, or a script that
 * posts only the numeric fields silently switches off a rule nobody touched. That is not
 * hypothetical: it happened during development, turning the annotation step off between two
 * runs with no one having unticked anything.
 *
 * <p>The hidden {@code _field} marker beside each switch is the fix, and these cases pin all
 * three behaviours it has to produce: ticked → on, unticked-but-present → off, absent → left
 * exactly as it was.
 */
@Transactional
class SettingsSwitchesIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AppSettingsService appSettingsService;
    @Autowired JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** The numeric half of the form, which is required on every save. */
    private static MockHttpServletRequestBuilderHelper limits() {
        return new MockHttpServletRequestBuilderHelper();
    }

    /** Small builder so each case reads as "the form, plus the switch bits it carries". */
    private static final class MockHttpServletRequestBuilderHelper {
        private final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                post("/settings").with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("excelExportMaxRows", "10000")
                        .param("auditRetentionDays", "90")
                        .param("jwtExpiryMinutes", "480")
                        .param("maxImagesPerField", "3")
                        .param("maxAudiosPerField", "1")
                        .param("maxVideosPerField", "1")
                        .param("maxAudioSeconds", "120")
                        .param("maxVideoSeconds", "120");

        MockHttpServletRequestBuilderHelper with(String name, String value) {
            builder.param(name, value);
            return this;
        }

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder build() {
            return builder;
        }
    }

    @Test
    void bothPoliciesExistAsSeededRowsRatherThanCodeOnlyDefaults() {
        // V5 seeds them so `SELECT * FROM app_settings` answers "what are the tablets running
        // under" without reading Java, and so a pg_dump carries an explicit value. The service
        // default still covers a database restored from before V5 — that is tested separately
        // in AppSettingsServiceTest, with no row at all.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT value FROM app_settings WHERE setting_key = ?", String.class,
                AppSettingsService.KEY_IMAGE_ANNOTATION_ENABLED)).isEqualTo("true");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT value FROM app_settings WHERE setting_key = ?", String.class,
                AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH)).isEqualTo("true");
    }

    @Test
    void theSeedNeverOverwritesAChoiceAnAdministratorAlreadyMade() {
        // ON CONFLICT DO NOTHING. Re-running the seed statement against a row someone set to
        // false must leave it false — an upsert here would silently re-tighten (or re-relax) a
        // deliberate decision on every upgrade.
        appSettingsService.saveNfcStrictSerialMatch(false);

        jdbcTemplate.update("INSERT INTO app_settings (setting_key, value, updated_at) VALUES (?, 'true', 0) "
                + "ON CONFLICT (setting_key) DO NOTHING", AppSettingsService.KEY_NFC_STRICT_SERIAL_MATCH);

        assertThat(appSettingsService.isNfcStrictSerialMatch()).isFalse();
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN",
            authorities = {"GET:/settings", "POST:/settings"})
    void bothSwitchesAreOnBeforeAnybodyTouchesThem() throws Exception {
        assertThat(appSettingsService.isImageAnnotationEnabled()).isTrue();
        assertThat(appSettingsService.isNfcStrictSerialMatch()).isTrue();

        // And the page renders them ticked, so an admin is not told the opposite of the truth.
        String html = mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("name=\"imageAnnotationEnabled\"");
        assertThat(html).contains("name=\"nfcStrictSerialMatch\"");
        assertThat(html).contains("name=\"_imageAnnotationEnabled\"");
        assertThat(html).contains("name=\"_nfcStrictSerialMatch\"");
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void untickingASwitchTurnsItOff() throws Exception {
        mockMvc.perform(limits()
                        .with("_imageAnnotationEnabled", "on")
                        .with("_nfcStrictSerialMatch", "on")
                        .build())
                .andExpect(status().is3xxRedirection());

        assertThat(appSettingsService.isImageAnnotationEnabled()).isFalse();
        assertThat(appSettingsService.isNfcStrictSerialMatch()).isFalse();
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void tickingItAgainTurnsItBackOnWithoutARestart() throws Exception {
        appSettingsService.saveImageAnnotationEnabled(false);
        appSettingsService.saveNfcStrictSerialMatch(false);

        mockMvc.perform(limits()
                        .with("_imageAnnotationEnabled", "on")
                        .with("imageAnnotationEnabled", "true")
                        .with("_nfcStrictSerialMatch", "on")
                        .with("nfcStrictSerialMatch", "true")
                        .build())
                .andExpect(status().is3xxRedirection());

        assertThat(appSettingsService.isImageAnnotationEnabled()).isTrue();
        assertThat(appSettingsService.isNfcStrictSerialMatch()).isTrue();
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void aFormThatNeverCarriedTheSwitchesLeavesThemAlone() throws Exception {
        // The regression this whole marker mechanism exists for. Posting the numeric fields
        // only — a stale page, a bookmark, a script — must not be read as "untick everything".
        mockMvc.perform(limits().build())
                .andExpect(status().is3xxRedirection());

        assertThat(appSettingsService.isImageAnnotationEnabled()).isTrue();
        assertThat(appSettingsService.isNfcStrictSerialMatch()).isTrue();
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void anOldFormCannotRelaxTheScanRuleWhileAnAdminIsChangingSomethingElse() throws Exception {
        // The dangerous asymmetry: the scan rule is an integrity rule, so it must survive a
        // save that only meant to change the annotation step.
        mockMvc.perform(limits()
                        .with("_imageAnnotationEnabled", "on")
                        .build())
                .andExpect(status().is3xxRedirection());

        assertThat(appSettingsService.isImageAnnotationEnabled()).isFalse();
        assertThat(appSettingsService.isNfcStrictSerialMatch()).isTrue();
    }

    @Test
    @WithAppUser(username = "settings-admin", roles = "ADMIN", authorities = "POST:/settings")
    void theSwitchesAreIndependentOfEachOther() throws Exception {
        mockMvc.perform(limits()
                        .with("_imageAnnotationEnabled", "on")
                        .with("_nfcStrictSerialMatch", "on")
                        .with("nfcStrictSerialMatch", "true")
                        .build())
                .andExpect(status().is3xxRedirection());

        assertThat(appSettingsService.isImageAnnotationEnabled()).isFalse();
        assertThat(appSettingsService.isNfcStrictSerialMatch()).isTrue();
    }
}
