package com.hnp.backendofflinefirst.security;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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

    @Test
    @WithAppUser(authorities = "GET:/")
    void bootstrapForbiddenWithoutPermission() throws Exception {
        mockMvc.perform(get("/api/bootstrap")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(authorities = "GET:/api/bootstrap")
    void bootstrapAllowedWithPermission() throws Exception {
        mockMvc.perform(get("/api/bootstrap")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/api/log-sheets/inbox")
    void bundleForbiddenWithoutBundlePermission() throws Exception {
        mockMvc.perform(get("/api/log-sheets/1/bundle")).andExpect(status().isForbidden());
    }

    @Test
    @WithAppUser(authorities = "GET:/api/log-sheets/{id}/bundle")
    void bundleAllowedWithBundlePermission() throws Exception {
        mockMvc.perform(get("/api/log-sheets/1/bundle")).andExpect(status().is4xxClientError());
    }

    @Test
    @WithAppUser(authorities = "POST:/locations/{id}/delete")
    void bulkDeleteLocationsAllowedWithDeletePermission() throws Exception {
        mockMvc.perform(post("/locations/delete-bulk").param("ids", "1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/locations"));
    }

    @Test
    @WithAppUser(authorities = "GET:/locations")
    void bulkDeleteLocationsForbiddenWithoutDeletePermission() throws Exception {
        mockMvc.perform(post("/locations/delete-bulk").param("ids", "1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheets/custom")
    void customLogSheetCreateAllowedWithPermission() throws Exception {
        // Missing required params → 3xx redirect via WebExceptionHandler (IllegalArgumentException)
        mockMvc.perform(post("/log-sheets/custom")
                        .param("name", "x")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets")
    void customLogSheetCreateForbiddenWithoutPermission() throws Exception {
        mockMvc.perform(post("/log-sheets/custom")
                        .param("unitId", "1")
                        .param("name", "x")
                        .param("assetIds", "1")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets/options/assets")
    void customAssetOptionsAllowedWithPermission() throws Exception {
        mockMvc.perform(get("/log-sheets/options/assets").param("unitId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheets/{id}/void")
    void voidLogSheetAllowedWithPermission() throws Exception {
        mockMvc.perform(post("/log-sheets/1/void").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "GET:/log-sheets")
    void voidLogSheetForbiddenWithoutPermission() throws Exception {
        // Method-security denial is redirected back to the sheet detail URL (not "/").
        mockMvc.perform(post("/log-sheets/1/void").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/log-sheets/1"));
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheets/{id}/unvoid")
    void unvoidLogSheetAllowedWithPermission() throws Exception {
        mockMvc.perform(post("/log-sheets/1/unvoid").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "POST:/log-sheets/{id}/reopen")
    void reopenLogSheetAllowedWithPermission() throws Exception {
        mockMvc.perform(post("/log-sheets/1/reopen").param("dueAt", "2099-01-01T12:00").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithAppUser(authorities = "GET:/api-sessions")
    void apiSessionsPageAllowedWithPermission() throws Exception {
        mockMvc.perform(get("/api-sessions")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/users")
    void apiSessionsPageForbiddenWithoutPermission() throws Exception {
        mockMvc.perform(get("/api-sessions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithAppUser(authorities = "POST:/api-sessions/{id}/revoke")
    void revokeApiSessionAllowedWithPermission() throws Exception {
        // Unknown id → IllegalArgumentException → redirect back with a flash error.
        mockMvc.perform(post("/api-sessions/999999/revoke").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/api-sessions"));
    }

    @Test
    @WithAppUser(authorities = "GET:/api-sessions")
    void revokeApiSessionForbiddenWithoutRevokePermission() throws Exception {
        mockMvc.perform(post("/api-sessions/1/revoke").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithAppUser(authorities = "POST:/api-sessions/revoke-user/{userId}")
    void revokeAllSessionsOfUserAllowedWithPermission() throws Exception {
        mockMvc.perform(post("/api-sessions/revoke-user/1").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/api-sessions"));
    }

    @Test
    @WithAppUser(authorities = "GET:/web-sessions")
    void webSessionsPageAllowedWithPermission() throws Exception {
        mockMvc.perform(get("/web-sessions")).andExpect(status().isOk());
    }

    @Test
    @WithAppUser(authorities = "GET:/users")
    void webSessionsPageForbiddenWithoutPermission() throws Exception {
        mockMvc.perform(get("/web-sessions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithAppUser(authorities = "POST:/web-sessions/{key}/expire")
    void expireWebSessionAllowedWithPermission() throws Exception {
        // Unknown key → IllegalArgumentException → redirect back with a flash error.
        mockMvc.perform(post("/web-sessions/deadbeef/expire").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/web-sessions"));
    }

    @Test
    @WithAppUser(authorities = "GET:/web-sessions")
    void expireWebSessionForbiddenWithoutExpirePermission() throws Exception {
        mockMvc.perform(post("/web-sessions/deadbeef/expire").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }
}
