package com.hnp.backendofflinefirst.security;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies endpoint-level @PreAuthorize permissions. */
class EndpointSecurityTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousCannotAccessUsersPage() throws Exception {
        mockMvc.perform(get("/users")).andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "GET:/users")
    void userWithListPermissionCanOpenUsersPage() throws Exception {
        mockMvc.perform(get("/users")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/locations")
    void userWithLocationListPermissionCanOpenLocationsPage() throws Exception {
        mockMvc.perform(get("/locations")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/")
    void dashboardRequiresDashboardPermission() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets")
    void operatorCanOpenLogSheetsWithoutDashboard() throws Exception {
        mockMvc.perform(get("/log-sheets")).andExpect(status().isOk());
    }
}
