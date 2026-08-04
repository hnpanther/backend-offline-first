package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.UnitSupervisor;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserAuthType;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.UnitSupervisorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The searchable option endpoints added for the ~600-unit dataset. The interesting part is
 * not that they return JSON — it is that the template one is <strong>unit-scoped</strong>:
 * a supervisor must not be able to enumerate units they do not supervise through it.
 */
class OptionEndpointScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired UnitSupervisorRepository unitSupervisorRepository;
    @Autowired UserRepository userRepository;

    MockMvc mockMvc;

    /** Matches the id WithAppUser injects, so the supervisor scope lookup finds our links. */
    private static final long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(authorities = "GET:/locations")
    void locationUnitPickerSearchesByNameAndCode() throws Exception {
        long t = System.nanoTime();
        saveUnit("OU-OPT-A-" + t, "Alpha option unit " + t);
        saveUnit("OU-OPT-B-" + t, "Beta option unit " + t);

        // by name
        mockMvc.perform(get("/locations/options/operational-units").param("q", "Alpha option unit " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Alpha option unit " + t + " (OU-OPT-A-" + t + ")"));

        // by code
        mockMvc.perform(get("/locations/options/operational-units").param("q", "OU-OPT-B-" + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithAppUser(authorities = "GET:/locations")
    void locationUnitPickerNeverDumpsTheWholeTable() throws Exception {
        // Seed comfortably more than the limit so the cap is what is being measured,
        // not however many units happen to exist in the shared test database.
        long t = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            saveUnit("OU-CAP-" + i + "-" + t, "Cap unit " + i + " " + t);
        }
        // No query at all still returns a bounded page, not every unit in the plant.
        mockMvc.perform(get("/locations/options/operational-units").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void locationUnitPickerRequiresThePermission() throws Exception {
        mockMvc.perform(get("/locations/options/operational-units"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheet-templates", roles = "ADMIN")
    void adminSeesEveryUnitInTheTemplateUnitPicker() throws Exception {
        long t = System.nanoTime();
        OperationalUnit mine = saveUnit("OU-TPL-MINE-" + t, "Mine " + t);
        OperationalUnit other = saveUnit("OU-TPL-OTHER-" + t, "Other " + t);

        mockMvc.perform(get("/log-sheet-templates/options/operational-units").param("q", "OU-TPL-MINE-" + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value(String.valueOf(mine.getId())));
        mockMvc.perform(get("/log-sheet-templates/options/operational-units").param("q", "OU-TPL-OTHER-" + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value(String.valueOf(other.getId())));
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheet-templates", roles = "SUPERVISOR")
    void supervisorCannotEnumerateUnitsTheyDoNotSuperviseThroughTheTemplatePicker() throws Exception {
        long t = System.nanoTime();
        OperationalUnit supervised = saveUnit("OU-SUP-YES-" + t, "Supervised " + t);
        OperationalUnit foreign = saveUnit("OU-SUP-NO-" + t, "Foreign " + t);
        ensureTestUser();
        linkSupervisor(TEST_USER_ID, supervised.getId());

        // Their own unit is offered…
        mockMvc.perform(get("/log-sheet-templates/options/operational-units").param("q", "OU-SUP-YES-" + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value(String.valueOf(supervised.getId())));

        // …a foreign one is not, even when named exactly.
        mockMvc.perform(get("/log-sheet-templates/options/operational-units").param("q", "OU-SUP-NO-" + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // And an unfiltered listing leaks nothing either.
        mockMvc.perform(get("/log-sheet-templates/options/operational-units").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.value == '" + foreign.getId() + "')]").isEmpty());
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheet-templates", roles = "OPERATOR")
    void aUserWithNoSupervisedUnitsGetsAnEmptyTemplateUnitPicker() throws Exception {
        ensureTestUser();
        mockMvc.perform(get("/log-sheet-templates/options/operational-units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- helpers ----

    private OperationalUnit saveUnit(String code, String name) {
        long now = System.currentTimeMillis();
        OperationalUnit u = new OperationalUnit();
        u.setCode(code);
        u.setName(name);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return operationalUnitRepository.saveAndFlush(u);
    }

    private void ensureTestUser() {
        if (userRepository.findById(TEST_USER_ID).isPresent()) {
            return;
        }
        long now = System.currentTimeMillis();
        User u = new User();
        u.setId(TEST_USER_ID);
        u.setUsername("tester-" + now);
        u.setFullName("Test User");
        u.setActive(true);
        u.setAuthType(UserAuthType.LOCAL);
        u.setPasswordHash("x");
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        userRepository.saveAndFlush(u);
    }

    private void linkSupervisor(Long userId, Long unitId) {
        UnitSupervisor link = new UnitSupervisor();
        link.setUserId(userId);
        link.setUnitId(unitId);
        unitSupervisorRepository.saveAndFlush(link);
    }
}
