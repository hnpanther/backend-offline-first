package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The "manual entry is also allowed" checkbox must survive the round trip through the form.
 *
 * <h2>The bug</h2>
 *
 * <p>The checkbox was posted the PHP/Rails way — a hidden input of the <b>same name</b> carrying
 * {@code false}, followed by the checkbox carrying {@code true}, on the assumption that the
 * server reads the last value. Spring reads the <b>first</b>: {@code @RequestParam boolean} on a
 * parameter that arrives twice converts the array by taking element zero. So a ticked box
 * arrived as {@code false} every time, and scan-only was the only reachable state.
 *
 * <p>Nothing in {@link FieldValidationSupport}'s own tests could see it. They call {@code build}
 * with a Java boolean, which was always handled correctly; the defect was entirely in how the
 * value reached it. That is why this test posts real form parameters, in the order a browser
 * sends them, instead of calling the builder.
 *
 * <h2>The default this must not lose</h2>
 *
 * <p>Typing stays allowed unless it is explicitly switched off — a label in a plant is
 * scratched, greasy, behind glass or missing, and a field that can only be scanned is a round
 * that cannot be finished. So a post that does not carry the checkbox <em>at all</em> (an older
 * form, an API caller) must still mean "typing allowed". That is why the fix is a separate
 * marker parameter and not a default flipped to false.
 */
@Transactional
class ScanFieldFormBindingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ----------------------------------------------------------- the reported bug

    /**
     * The shape of the defect, asserted on the markup the page actually renders.
     *
     * <p>Two inputs of one name is the whole bug, and it is invisible from the Java side: a
     * browser posts {@code scanManual=false&scanManual=true} for a ticked box, Spring converts
     * that two-element array by taking element <b>zero</b>, and the answer is always the hidden
     * one. A functional test can only catch it by replaying both values, which is easy to write
     * incorrectly; this catches the cause instead, and fails the moment anybody reintroduces the
     * same-name hidden field on any of the three forms that include this fragment.
     */
    @Test
    @WithAppUser(authorities = "GET:/asset-classes/{classId}/fields")
    void theFormNeverPostsTwoValuesForOneBoolean() throws Exception {
        AssetClass ac = saveClass();

        String html = mockMvc.perform(get("/asset-classes/{classId}/fields", ac.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(inputsNamed(html, "scanManual"))
                .as("one name, one value: a second input of this name is read instead of the box")
                .hasSize(1)
                .allSatisfy(tag -> assertThat(tag).contains("type=\"checkbox\""));
        // The marker that says the control was on the form carries its own name, which is the
        // convention the settings page already uses for exactly this reason.
        assertThat(inputsNamed(html, "_scanManual")).hasSize(1);
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    void aTickedManualBoxIsStoredAsManualAllowed() throws Exception {
        AssetClass ac = saveClass();
        String key = "serial_" + System.nanoTime();

        // Exactly what a browser sends for a ticked checkbox that has a marker beside it.
        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", key)
                        .param("label", "Serial")
                        .param("dataType", "text")
                        .param("order", "0")
                        .param("scanSources", "QR")
                        .param("_scanManual", "on")
                        .param("scanManual", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        FieldDefinition saved = fieldByKey(ac.getId(), key);
        assertThat(FieldValidationSupport.scanSources(saved.getValidation())).containsExactly("QR");
        assertThat(FieldValidationSupport.scanAllowsManual(saved.getValidation()))
                .as("the administrator ticked the manual-entry box")
                .isTrue();
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    void anUntickedManualBoxIsStoredAsScanOnly() throws Exception {
        AssetClass ac = saveClass();
        String key = "sealed_" + System.nanoTime();

        // An unticked checkbox posts nothing at all; only the marker arrives.
        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", key)
                        .param("label", "Seal code")
                        .param("dataType", "text")
                        .param("order", "0")
                        .param("scanSources", "QR")
                        .param("_scanManual", "on")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(FieldValidationSupport.scanAllowsManual(
                fieldByKey(ac.getId(), key).getValidation())).isFalse();
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    void aPostWithNoCheckboxAtAllStillAllowsTyping() throws Exception {
        AssetClass ac = saveClass();
        String key = "legacy_" + System.nanoTime();

        // No marker: this form never carried the control. The safe direction is the default, so
        // an older page or an API caller cannot accidentally create an un-fillable field.
        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", key)
                        .param("label", "Nameplate")
                        .param("dataType", "text")
                        .param("order", "0")
                        .param("scanSources", "BARCODE")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(FieldValidationSupport.scanAllowsManual(
                fieldByKey(ac.getId(), key).getValidation())).isTrue();
    }

    // ------------------------------------------- and it survives being reopened

    @Test
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields",
                                "GET:/asset-classes/{classId}/fields"})
    void theReopenedEditorShowsTheBoxTicked() throws Exception {
        AssetClass ac = saveClass();
        String key = "roundtrip_" + System.nanoTime();

        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", key).param("label", "Round trip")
                        .param("dataType", "text").param("order", "0")
                        .param("scanSources", "QR")
                        .param("_scanManual", "on").param("scanManual", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        FieldDefinition saved = fieldByKey(ac.getId(), key);
        String html = mockMvc.perform(get("/asset-classes/{classId}/fields", ac.getId())
                        .param("editId", String.valueOf(saved.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The reported symptom was the box coming back unticked, so it is asserted on the
        // rendered page — which is where the administrator saw it.
        assertThat(manualCheckboxIsChecked(html))
                .as("the manual-entry checkbox in the edit modal must come back ticked")
                .isTrue();
    }

    @Test
    @WithAppUser(authorities = {"POST:/asset-classes/{classId}/fields",
                                "POST:/asset-classes/{classId}/fields/{fieldId}"})
    void anEditCanTurnManualEntryBackOnAndOffAgain() throws Exception {
        AssetClass ac = saveClass();
        String key = "toggle_" + System.nanoTime();

        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", key).param("label", "Toggle")
                        .param("dataType", "text").param("order", "0")
                        .param("scanSources", "QR").param("_scanManual", "on")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        Long fieldId = fieldByKey(ac.getId(), key).getId();
        assertThat(manualOf(fieldId)).isFalse();

        // On.
        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), fieldId)
                        .param("key", key).param("label", "Toggle")
                        .param("dataType", "text").param("order", "0")
                        .param("scanSources", "QR")
                        .param("_scanManual", "on").param("scanManual", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(manualOf(fieldId)).isTrue();

        // And off again, because a one-way switch is the same defect wearing the other face.
        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), fieldId)
                        .param("key", key).param("label", "Toggle")
                        .param("dataType", "text").param("order", "0")
                        .param("scanSources", "QR").param("_scanManual", "on")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(manualOf(fieldId)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private boolean manualOf(Long fieldId) {
        return FieldValidationSupport.scanAllowsManual(
                fieldDefinitionRepository.findById(fieldId).orElseThrow().getValidation());
    }

    /** Every {@code <input>} tag on the page carrying exactly this name. */
    private static java.util.List<String> inputsNamed(String html, String name) {
        java.util.List<String> found = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String tag = m.group();
            if (tag.contains("name=\"" + name + "\"")) {
                found.add(tag);
            }
        }
        return found;
    }

    /** The checkbox named {@code scanManual} — ticked when its own tag carries {@code checked}. */
    private static boolean manualCheckboxIsChecked(String html) {
        Matcher m = Pattern.compile("<input[^>]*name=\"scanManual\"[^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String tag = m.group();
            if (tag.contains("type=\"checkbox\"")) {
                return tag.contains("checked");
            }
        }
        throw new AssertionError("no scanManual checkbox on the page at all");
    }

    private FieldDefinition fieldByKey(Long classId, String key) {
        return fieldDefinitionRepository.findByClassId(classId).stream()
                .filter(f -> key.equals(f.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field " + key + " was not saved"));
    }

    private AssetClass saveClass() {
        long now = System.currentTimeMillis();
        AssetClass ac = new AssetClass();
        ac.setName("ScanBind-" + System.nanoTime());
        ac.setCreatedAt(now);
        ac.setUpdatedAt(now);
        return assetClassRepository.saveAndFlush(ac);
    }
}
