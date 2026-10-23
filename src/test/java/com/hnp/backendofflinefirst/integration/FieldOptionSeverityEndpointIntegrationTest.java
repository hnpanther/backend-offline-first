package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.FieldValidationSeverity;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Option severities must survive the screen that saves them.
 *
 * <h2>Why this exists rather than only a unit test of {@code build()}</h2>
 *
 * <p>{@code FieldValidationSupport.build(...)} <strong>rebuilds</strong> the whole
 * {@code validation} object from the form on every field save. Anything the controller does not
 * hand it is gone — not rejected, not warned about, gone — the first time an administrator edits
 * anything else about that field. {@code allowNegative} died exactly that way, and AGENTS.md
 * records it.
 *
 * <p>So the method being correct proves nothing on its own: the risk is a <em>parameter that was
 * never threaded through the endpoint</em>. These go through the real
 * {@code POST /asset-classes/.../fields} to close that gap, and the second case is the one that
 * matters — an edit that changes something unrelated and must leave the severities standing.
 */
@Transactional
class FieldOptionSeverityEndpointIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    @DisplayName("creating a select field stores which options alert")
    void creatingAChoiceFieldStoresItsSeverities() throws Exception {
        AssetClass ac = saveClass();

        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                .param("key", "state")
                .param("label", "State")
                .param("dataType", "select")
                .param("order", "1")
                .param("selectOptions", "OF,ON,Idle")
                .param("warningOptions", "Idle")
                .param("dangerOptions", "OF")
                .with(csrf()));

        FieldDefinition stored = onlyField(ac);
        assertThat(warningOptions(stored)).containsExactly("Idle");
        assertThat(dangerOptions(stored)).containsExactly("OF");
        assertThat(FieldValidationSupport.optionList(
                stored.getValidation(), FieldValidationSupport.KEY_OPTIONS))
                .containsExactly("OF", "ON", "Idle");
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    @DisplayName("editing the label of a select field does not wipe its severities")
    void anUnrelatedEditKeepsTheSeverities() throws Exception {
        AssetClass ac = saveClass();
        FieldDefinition field = saveChoiceField(ac);

        // The administrator came to fix a typo in the label. Everything else is posted back
        // exactly as the form rendered it, which is the point: if the endpoint does not take
        // the severities, the form cannot post them and the rebuild drops them.
        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                .param("key", "state")
                .param("label", "State corrected")
                .param("dataType", "select")
                .param("order", "1")
                .param("selectOptions", "OF,ON,Idle")
                .param("warningOptions", "Idle")
                .param("dangerOptions", "OF")
                .with(csrf()));

        FieldDefinition stored = onlyField(ac);
        assertThat(stored.getLabel()).isEqualTo("State corrected");
        assertThat(dangerOptions(stored))
                .as("the edit was about the label; the thresholds were not up for discussion")
                .containsExactly("OF");
        assertThat(warningOptions(stored)).containsExactly("Idle");
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    @DisplayName("deleting an option drops the severity that named it")
    void removingAnOptionRemovesItsSeverity() throws Exception {
        AssetClass ac = saveClass();
        FieldDefinition field = saveChoiceField(ac);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                .param("key", "state")
                .param("label", "State")
                .param("dataType", "select")
                .param("order", "1")
                .param("selectOptions", "OF,ON")
                .param("warningOptions", "Idle")
                .param("dangerOptions", "OF")
                .with(csrf()));

        FieldDefinition stored = onlyField(ac);
        assertThat(warningOptions(stored))
                .as("a rule for an option that no longer exists can never match, so it is dropped")
                .isEmpty();
        assertThat(dangerOptions(stored)).containsExactly("OF");
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    @DisplayName("clearing every marking leaves a vocabulary that alerts on nothing")
    void clearingTheSeveritiesIsStored() throws Exception {
        AssetClass ac = saveClass();
        FieldDefinition field = saveChoiceField(ac);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                .param("key", "state")
                .param("label", "State")
                .param("dataType", "select")
                .param("order", "1")
                .param("selectOptions", "OF,ON,Idle")
                .param("warningOptions", "")
                .param("dangerOptions", "")
                .with(csrf()));

        FieldDefinition stored = onlyField(ac);
        assertThat(FieldValidationSupport.optionList(
                stored.getValidation(), FieldValidationSupport.KEY_OPTIONS))
                .containsExactly("OF", "ON", "Idle");
        assertThat(FieldValidationSupport.evaluateValue("select", "OF", stored.getValidation()))
                .as("the vocabulary stands; nothing in it is an alert any more")
                .isEqualTo(FieldValidationSeverity.OK);
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    @DisplayName("a number field ignores option severities posted alongside it")
    void aNumberFieldIgnoresOptionSeverities() throws Exception {
        AssetClass ac = saveClass();

        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                .param("key", "bar")
                .param("label", "Pressure")
                .param("dataType", "number")
                .param("order", "1")
                .param("warningMin", "1")
                .param("warningMax", "5")
                .param("warningOptions", "OF")
                .param("dangerOptions", "ON")
                .with(csrf()));

        FieldDefinition stored = onlyField(ac);
        assertThat(stored.getValidation())
                .as("a rule nothing would ever read is not stored at all")
                .doesNotContainKeys(FieldValidationSupport.KEY_WARNING_OPTIONS,
                        FieldValidationSupport.KEY_DANGER_OPTIONS);
        assertThat(FieldValidationSupport.warningRange(stored.getValidation()).max())
                .isEqualTo(5.0);
    }

    // ---------------------------------------------------------------------------------------

    private List<String> warningOptions(FieldDefinition fd) {
        return FieldValidationSupport.optionList(
                fd.getValidation(), FieldValidationSupport.KEY_WARNING_OPTIONS);
    }

    private List<String> dangerOptions(FieldDefinition fd) {
        return FieldValidationSupport.optionList(
                fd.getValidation(), FieldValidationSupport.KEY_DANGER_OPTIONS);
    }

    private AssetClass saveClass() {
        long t = System.nanoTime();
        AssetClass ac = new AssetClass();
        ac.setName("OptSev-" + t);
        ac.setCreatedAt(t);
        ac.setUpdatedAt(t);
        return assetClassRepository.save(ac);
    }

    private FieldDefinition saveChoiceField(AssetClass ac) {
        long t = System.currentTimeMillis();
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(ac.getId());
        fd.setKey("state");
        fd.setLabel("State");
        fd.setDataType("select");
        fd.setOrder(1);
        fd.setVersion(1);
        fd.setCreatedAt(t);
        fd.setUpdatedAt(t);
        fd.setValidation(FieldValidationSupport.build(
                "select", "OF,ON,Idle", null, null, null, null, "Idle", "OF"));
        return fieldDefinitionRepository.save(fd);
    }

    private FieldDefinition onlyField(AssetClass ac) {
        List<FieldDefinition> fields = fieldDefinitionRepository.findByClassId(ac.getId());
        assertThat(fields).hasSize(1);
        return fields.getFirst();
    }
}
