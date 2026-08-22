package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import com.hnp.backendofflinefirst.ui.FaMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing a class field must not change its data type behind the operator's back.
 *
 * <h2>The bug</h2>
 *
 * <p>The edit modal built its {@code <select>} from its own hardcoded list of six types; the
 * create modal had a different hardcoded list of ten. So a field whose type was {@code image},
 * {@code audio}, {@code video} or {@code location} had no matching {@code <option>} when reopened
 * — and a {@code <select>} with nothing selected submits its **first** option, which was
 * {@code number}. Opening a photo field and pressing save retyped it to numeric.
 *
 * <p>That is data loss, not a cosmetic slip. Readings already stored against the field are
 * attachment references; under {@code number} the server can no longer validate them, and the
 * PWA renders a number box where the photographs used to be. The reverse — a numeric field
 * quietly acquiring a media type — would orphan every reading in the same way.
 *
 * <h2>What is checked</h2>
 *
 * <p>Rendering, because the failure was entirely in what the page offered: for every type the
 * system supports, reopening a field of that type must produce exactly one selected option and
 * it must be that type. Plus the write side, which had no whitelist at all.
 */
@Transactional
class FieldDataTypeEditIntegrationTest extends AbstractPostgresIntegrationTest {

    /** `<option ... value="x" ... selected ...>` in either attribute order. */
    private static final Pattern OPTION =
            Pattern.compile("<option\\b[^>]*\\bvalue=\"([^\"]*)\"[^>]*>", Pattern.CASE_INSENSITIVE);

    @Autowired WebApplicationContext context;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering — the half that caused it
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "a {0} field reopens as {0}")
    @ValueSource(strings = {
            "number", "text", "select", "multiselect", "checkbox",
            "textarea", "image", "audio", "video", "location"
    })
    @WithAppUser(authorities = "GET:/asset-classes/{classId}/fields")
    void everySupportedTypeSurvivesBeingReopened(String dataType) throws Exception {
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("Reopen-" + dataType + "-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "f_" + dataType + "_" + t, dataType, t);

        String html = mockMvc.perform(get("/asset-classes/{classId}/fields", ac.getId())
                        .param("editId", String.valueOf(field.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(selectedOptionsIn(editModalOf(html)))
                .as("the edit modal must preselect the field's own type, and only that one")
                .containsExactly(dataType);
    }

    @Test
    @WithAppUser(authorities = "GET:/asset-classes/{classId}/fields")
    void theEditModalOffersEveryTypeTheCreateModalOffers() throws Exception {
        // The drift itself. Two hardcoded lists is the defect; one list is the fix, and this is
        // what fails if somebody reintroduces a second one.
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("Offer-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "f_offer_" + t, "image", t);

        String html = mockMvc.perform(get("/asset-classes/{classId}/fields", ac.getId())
                        .param("editId", String.valueOf(field.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(dataTypeOptionsIn(editModalOf(html)))
                .containsExactlyElementsOf(FieldDataTypes.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Writing — a dropdown is not a boundary
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    void anEditThatKeepsTheMediaTypeKeepsIt() throws Exception {
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("Keep-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "photo_" + t, "image", t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                        .param("key", "photo_" + t)
                        .param("label", "تصویر پمپ")
                        .param("dataType", "image")
                        .param("order", "0")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("successMessage", FaMessages.fieldDefinitionUpdated()));

        assertThat(fieldDefinitionRepository.findById(field.getId()).orElseThrow().getDataType())
                .isEqualTo("image");
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    void anUnknownDataTypeIsRefusedAndChangesNothing() throws Exception {
        // There was no server-side check at all: whatever the form posted was stored. A type
        // nothing can render leaves the field unanswerable on the tablet.
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("Junk-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "sound_" + t, "audio", t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                        .param("key", "renamed_" + t)
                        .param("label", "Renamed")
                        .param("dataType", "hologram")
                        .param("order", "0")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/asset-classes/" + ac.getId() + "/fields"))
                .andExpect(flash().attribute("errorMessage",
                        FaMessages.fieldDefinitionInvalidDataType("hologram")));

        FieldDefinition unchanged = fieldDefinitionRepository.findById(field.getId()).orElseThrow();
        assertThat(unchanged.getDataType()).isEqualTo("audio");
        assertThat(unchanged.getKey()).isEqualTo("sound_" + t);
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    void creatingWithAnUnknownDataTypeIsRefusedToo() throws Exception {
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("JunkNew-" + t, t);

        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", "bogus_" + t)
                        .param("label", "Bogus")
                        .param("dataType", "")
                        .param("order", "0")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("errorMessage",
                        FaMessages.fieldDefinitionInvalidDataType("")));

        assertThat(fieldDefinitionRepository.findByClassId(ac.getId())).isEmpty();
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    void everySupportedTypeCanActuallyBeCreated() throws Exception {
        // The whitelist and the dropdown must agree: a type the page offers has to be accepted.
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("CreateAll-" + t, t);

        for (String dataType : FieldDataTypes.values()) {
            mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                            .param("key", "k_" + dataType + "_" + t)
                            .param("label", dataType)
                            .param("dataType", dataType)
                            .param("order", "0")
                            .with(csrf()))
                    .andExpect(flash().attribute("successMessage",
                            FaMessages.fieldDefinitionCreated()));
        }

        assertThat(fieldDefinitionRepository.findByClassId(ac.getId()))
                .extracting(FieldDefinition::getDataType)
                .containsExactlyInAnyOrderElementsOf(FieldDataTypes.values());
    }

    // ──────────────────────────────────────────────────────────────────
    // A row whose type this build no longer offers
    // ──────────────────────────────────────────────────────────────────

    @Test
    @WithAppUser(authorities = "GET:/asset-classes/{classId}/fields")
    void aLegacyTypeIsStillPreselectedWhenTheFieldIsReopened() throws Exception {
        // `schema.md` documented `date` and `boolean`, which the editor never offered. Without
        // this the whitelist would turn a documented legacy row into an uneditable one — or
        // worse, retype it on save, which is the reported bug all over again.
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("Legacy-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "installed_" + t, "date", t);

        String html = mockMvc.perform(get("/asset-classes/{classId}/fields", ac.getId())
                        .param("editId", String.valueOf(field.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String modal = editModalOf(html);
        assertThat(selectedOptionsIn(modal)).containsExactly("date");
        assertThat(dataTypeOptionsIn(modal)).endsWith("date");
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    void aLegacyFieldCanStillBeRenamedWithoutChangingItsType() throws Exception {
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("LegacyEdit-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "installed_" + t, "date", t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                        .param("key", "installed_" + t)
                        .param("label", "تاریخ نصب")
                        .param("dataType", "date")
                        .param("order", "0")
                        .with(csrf()))
                .andExpect(flash().attribute("successMessage", FaMessages.fieldDefinitionUpdated()));

        FieldDefinition saved = fieldDefinitionRepository.findById(field.getId()).orElseThrow();
        assertThat(saved.getDataType()).isEqualTo("date");
        assertThat(saved.getLabel()).isEqualTo("تاریخ نصب");
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields")
    void aLegacyTypeCannotBeIntroducedOnANewField() throws Exception {
        // Tolerated where it already exists, never newly created.
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("LegacyNew-" + t, t);

        mockMvc.perform(post("/asset-classes/{classId}/fields", ac.getId())
                        .param("key", "when_" + t)
                        .param("label", "When")
                        .param("dataType", "date")
                        .param("order", "0")
                        .with(csrf()))
                .andExpect(flash().attribute("errorMessage",
                        FaMessages.fieldDefinitionInvalidDataType("date")));

        assertThat(fieldDefinitionRepository.findByClassId(ac.getId())).isEmpty();
    }

    @Test
    @WithAppUser(authorities = "POST:/asset-classes/{classId}/fields/{fieldId}")
    void aNormalFieldCannotBeGivenALegacyType() throws Exception {
        long t = System.currentTimeMillis();
        AssetClass ac = saveClass("NoDowngrade-" + t, t);
        FieldDefinition field = saveField(ac.getId(), "temp_" + t, "number", t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", ac.getId(), field.getId())
                        .param("key", "temp_" + t)
                        .param("label", "Temp")
                        .param("dataType", "date")
                        .param("order", "0")
                        .with(csrf()))
                .andExpect(flash().attribute("errorMessage",
                        FaMessages.fieldDefinitionInvalidDataType("date")));

        assertThat(fieldDefinitionRepository.findById(field.getId()).orElseThrow().getDataType())
                .isEqualTo("number");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Just the edit modal's markup.
     *
     * <p>The page carries the create modal too, whose options are never preselected — scanning
     * the whole document would mix the two and let a regression in one hide behind the other.
     */
    private static String editModalOf(String html) {
        int start = html.indexOf("id=\"editModal\"");
        assertThat(start).as("the edit modal must be rendered when editId is given").isGreaterThan(-1);
        return html.substring(start);
    }

    private static java.util.List<String> dataTypeOptionsIn(String modalHtml) {
        return optionsIn(modalHtml, false);
    }

    private static java.util.List<String> selectedOptionsIn(String modalHtml) {
        return optionsIn(modalHtml, true);
    }

    private static java.util.List<String> optionsIn(String modalHtml, boolean selectedOnly) {
        String select = sliceDataTypeSelect(modalHtml);
        java.util.List<String> found = new java.util.ArrayList<>();
        Matcher m = OPTION.matcher(select);
        while (m.find()) {
            if (!selectedOnly || m.group().contains("selected")) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    private static String sliceDataTypeSelect(String modalHtml) {
        int start = modalHtml.indexOf("name=\"dataType\"");
        assertThat(start).as("the edit modal must contain a dataType select").isGreaterThan(-1);
        int end = modalHtml.indexOf("</select>", start);
        assertThat(end).isGreaterThan(start);
        return modalHtml.substring(start, end);
    }

    private AssetClass saveClass(String name, long t) {
        AssetClass ac = new AssetClass();
        ac.setName(name);
        ac.setCreatedAt(t);
        ac.setUpdatedAt(t);
        return assetClassRepository.saveAndFlush(ac);
    }

    private FieldDefinition saveField(Long classId, String key, String dataType, long t) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType(dataType);
        fd.setRequired(false);
        fd.setOrder(0);
        fd.setVersion(1);
        fd.setDeleted(false);
        fd.setSynced(false);
        fd.setCreatedAt(t);
        fd.setUpdatedAt(t);
        return fieldDefinitionRepository.saveAndFlush(fd);
    }
}
