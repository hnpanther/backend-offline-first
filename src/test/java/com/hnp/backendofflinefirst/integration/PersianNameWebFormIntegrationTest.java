package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.PlantSystemRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The optional Persian name must survive the full web round-trip: create → list → edit → save.
 * Update handlers copy fields one by one, so a missing {@code setNameFa} would silently drop
 * the value on every edit while create still appeared to work.
 */
class PersianNameWebFormIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired LocationRepository locationRepository;
    @Autowired PlantSystemRepository plantSystemRepository;
    @Autowired AssetHierarchyService hierarchyService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = {"POST:/locations", "POST:/locations/{id}", "GET:/locations"}, roles = "ADMIN")
    void locationPersianNameSurvivesCreateListAndEdit() throws Exception {
        String code = "LOC-FA-" + System.nanoTime();

        mockMvc.perform(post("/locations").with(csrf())
                        .param("code", code)
                        .param("name", "Pump house")
                        .param("nameFa", "اتاق پمپ"))
                .andExpect(status().is3xxRedirection());

        Location saved = locationRepository.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getNameFa()).isEqualTo("اتاق پمپ");

        // It is rendered in the list…
        mockMvc.perform(get("/locations").param("q", code))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("اتاق پمپ")));

        // …and an edit that changes it is persisted rather than dropped.
        mockMvc.perform(post("/locations/{id}", saved.getId()).with(csrf())
                        .param("code", code)
                        .param("name", "Pump house")
                        .param("nameFa", "اتاق پمپ اصلی"))
                .andExpect(status().is3xxRedirection());

        assertThat(locationRepository.findById(saved.getId()).orElseThrow().getNameFa())
                .isEqualTo("اتاق پمپ اصلی");
    }

    @Test
    @WithAppUser(authorities = {"POST:/locations", "POST:/locations/{id}"}, roles = "ADMIN")
    void locationPersianNameIsOptional() throws Exception {
        String code = "LOC-FA-NONE-" + System.nanoTime();

        mockMvc.perform(post("/locations").with(csrf())
                        .param("code", code)
                        .param("name", "No Persian name"))
                .andExpect(status().is3xxRedirection());

        Location saved = locationRepository.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getNameFa()).isNull();
    }

    @Test
    @WithAppUser(authorities = {"POST:/plant-systems", "POST:/plant-systems/{id}"}, roles = "ADMIN")
    void plantSystemPersianNameSurvivesCreateAndEdit() throws Exception {
        long t = System.nanoTime();
        Location loc = new Location();
        loc.setCode("LOC-SYSFA-" + t);
        loc.setName("Host location");
        loc.setCreatedAt(System.currentTimeMillis());
        loc.setUpdatedAt(System.currentTimeMillis());
        loc = hierarchyService.saveLocation(loc, List.of());

        String code = "SYS-FA-" + t;
        mockMvc.perform(post("/plant-systems").with(csrf())
                        .param("code", code)
                        .param("name", "Cooling system")
                        .param("nameFa", "سیستم خنک‌کننده")
                        .param("locationId", String.valueOf(loc.getId())))
                .andExpect(status().is3xxRedirection());

        var saved = plantSystemRepository.findByCodeIgnoreCase(code).orElseThrow();
        assertThat(saved.getNameFa()).isEqualTo("سیستم خنک‌کننده");

        mockMvc.perform(post("/plant-systems/{id}", saved.getId()).with(csrf())
                        .param("code", code)
                        .param("name", "Cooling system")
                        .param("nameFa", "سیستم سرمایش")
                        .param("locationId", String.valueOf(loc.getId())))
                .andExpect(status().is3xxRedirection());

        assertThat(plantSystemRepository.findById(saved.getId()).orElseThrow().getNameFa())
                .isEqualTo("سیستم سرمایش");
    }
}
