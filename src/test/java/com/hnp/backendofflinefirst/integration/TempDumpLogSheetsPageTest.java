package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** TEMPORARY — dumps the real rendered /log-sheets page so it can be opened in a browser. */
class TempDumpLogSheetsPageTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = {"GET:/log-sheets", "POST:/log-sheets/generate",
            "POST:/log-sheets/custom", "GET:/log-sheets/options/units", "GET:/log-sheets/options/assets"})
    void dump() throws Exception {
        String html = mockMvc.perform(get("/log-sheets?size=25"))
                .andReturn().getResponse().getContentAsString();
        Path out = Path.of("target/classes/static/vendor/__firstpaint-check.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
        System.out.println("DUMPED " + html.length() + " chars to " + out.toAbsolutePath());
    }
}
