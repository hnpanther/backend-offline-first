package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import com.hnp.backendofflinefirst.ui.FaMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Update/delete of field definitions must be scoped to the classId in the URL.
 */
@Transactional
class AssetClassFieldOwnershipIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = {
            "POST:/asset-classes/{classId}/fields/{fieldId}",
            "POST:/asset-classes/{classId}/fields/{fieldId}/delete"
    })
    void updateAndDeleteRejectFieldFromAnotherClass() throws Exception {
        long t = System.currentTimeMillis();
        AssetClass owner = saveClass("Owner-" + t, t);
        AssetClass other = saveClass("Other-" + t, t);
        FieldDefinition field = saveField(owner.getId(), "temp-" + t, t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", other.getId(), field.getId())
                        .param("key", "hacked")
                        .param("label", "Hacked")
                        .param("dataType", "number")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/asset-classes/" + other.getId() + "/fields"))
                .andExpect(flash().attribute("errorMessage", FaMessages.fieldDefinitionNotInClass()));

        FieldDefinition unchanged = fieldDefinitionRepository.findById(field.getId()).orElseThrow();
        assertThat(unchanged.getKey()).isEqualTo(field.getKey());
        assertThat(unchanged.getClassId()).isEqualTo(owner.getId());

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}/delete", other.getId(), field.getId())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/asset-classes/" + other.getId() + "/fields"))
                .andExpect(flash().attribute("errorMessage", FaMessages.fieldDefinitionNotInClass()));

        assertThat(fieldDefinitionRepository.findById(field.getId())).isPresent();
    }

    @Test
    @WithAppUser(authorities = {
            "POST:/asset-classes/{classId}/fields/{fieldId}",
            "POST:/asset-classes/{classId}/fields/{fieldId}/delete"
    })
    void updateAndDeleteSucceedWhenFieldBelongsToClass() throws Exception {
        long t = System.currentTimeMillis();
        AssetClass owner = saveClass("Owner-ok-" + t, t);
        FieldDefinition field = saveField(owner.getId(), "pressure-" + t, t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}", owner.getId(), field.getId())
                        .param("key", "pressure-updated-" + t)
                        .param("label", "Pressure")
                        .param("dataType", "number")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/asset-classes/" + owner.getId() + "/fields"))
                .andExpect(flash().attribute("successMessage", FaMessages.fieldDefinitionUpdated()));

        assertThat(fieldDefinitionRepository.findById(field.getId()).orElseThrow().getKey())
                .isEqualTo("pressure-updated-" + t);

        mockMvc.perform(post("/asset-classes/{classId}/fields/{fieldId}/delete", owner.getId(), field.getId())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/asset-classes/" + owner.getId() + "/fields"))
                .andExpect(flash().attribute("successMessage", FaMessages.fieldDefinitionDeleted()));

        assertThat(fieldDefinitionRepository.findById(field.getId())).isEmpty();
    }

    private AssetClass saveClass(String name, long t) {
        AssetClass ac = new AssetClass();
        ac.setName(name);
        ac.setCreatedAt(t);
        ac.setUpdatedAt(t);
        return assetClassRepository.saveAndFlush(ac);
    }

    private FieldDefinition saveField(Long classId, String key, long t) {
        FieldDefinition fd = new FieldDefinition();
        fd.setClassId(classId);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setRequired(false);
        fd.setDeleted(false);
        fd.setSynced(false);
        fd.setVersion(1);
        fd.setOrder(1);
        fd.setCreatedAt(t);
        fd.setUpdatedAt(t);
        return fieldDefinitionRepository.saveAndFlush(fd);
    }
}
