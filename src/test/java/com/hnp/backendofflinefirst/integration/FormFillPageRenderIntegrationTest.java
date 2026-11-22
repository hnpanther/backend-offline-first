package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithSecurityContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live crash: {@code GET /form-submissions/new?formId=} 500'd when the form had a lookup field —
 * {@code @{...}} nested inside a SpEL ternary in {@code fill-field-input} is unparseable.
 * Also pins the create/edit unit picker wiring and the top-level «فرم‌های عملیاتی» nav section.
 */
@Transactional
@WithAppUser(roles = "ADMIN", authorities = {
        "GET:/forms", "POST:/forms", "POST:/forms/{id}",
        "GET:/forms/{id}/fields", "POST:/forms/{id}/fields",
        "GET:/form-submissions", "GET:/form-submissions/{id}",
        "POST:/form-submissions/draft", "POST:/form-submissions/submit",
        Capabilities.SCOPE_PLANT_WIDE
})
@TestExecutionListeners(listeners = {
        DependencyInjectionTestExecutionListener.class,
        DirtiesContextTestExecutionListener.class,
        TransactionalTestExecutionListener.class,
        WithSecurityContextTestExecutionListener.class
}, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class FormFillPageRenderIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired FormDefinitionService formDefinitionService;
    @Autowired FormFieldService formFieldService;
    @Autowired OperationalUnitRepository unitRepository;

    MockMvc mockMvc;
    FormDefinition form;
    OperationalUnit unit;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        unit = new OperationalUnit();
        unit.setCode("OU-FILL-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setName("واحد پر کردن");
        unit.setCreatedAt(System.currentTimeMillis());
        unit.setUpdatedAt(System.currentTimeMillis());
        unit = unitRepository.save(unit);

        form = formDefinitionService.create(
                "FILL-" + UUID.randomUUID().toString().substring(0, 8),
                "فرم با lookup و امضا",
                null,
                List.of(unit.getId()),
                true);
        formFieldService.create(form.getId(), "who", "پرسنل", "lookup", null, false,
                Map.of("lookupList", "personnel"), 10);
        formFieldService.create(form.getId(), "sig", "امضا", "signature", null, false,
                Map.of("signatureScope", "ENTRY"), 20);
        formFieldService.create(form.getId(), "note", "توضیح", "text", null, false, Map.of(), 30);
    }

    @Test
    void newFillPageRendersLookupAndSignatureWithoutThymeleafCrash() throws Exception {
        mockMvc.perform(get("/form-submissions/new").param("formId", form.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lookup-field")))
                .andExpect(content().string(containsString(
                        "/forms/" + form.getId() + "/lookups/personnel")))
                .andExpect(content().string(containsString("ذخیره پیش‌نویس")))
                // New fill has no draft yet — signature pad asks for a draft rather than /log-sheets/0.
                .andExpect(content().string(containsString("برای امضا ابتدا")))
                .andExpect(content().string(not(containsString("/log-sheets/0/lookups"))))
                // Filing unit only — no separate subject / «واحد عملیاتی» picker.
                .andExpect(content().string(containsString("واحد ثبت‌کننده")))
                .andExpect(content().string(containsString("name=\"reporterUnitId\"")))
                .andExpect(content().string(not(containsString("name=\"subjectUnitId\""))));
    }

    @Test
    void formsCreateModalWiresOperationalUnitRemoteMultiSelect() throws Exception {
        mockMvc.perform(get("/forms"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-remote-url=\"/forms/options/operational-units\"")))
                .andExpect(content().string(containsString("واحدهای عملیاتی")))
                .andExpect(content().string(not(containsString("data-url=\"/operational-units/options/parents\""))));
    }

    @Test
    void formsOperationalUnitOptionsEndpointAnswers() throws Exception {
        mockMvc.perform(get("/forms/options/operational-units").param("q", unit.getCode()).param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(unit.getCode())));
    }

    @Test
    void sidebarShowsTopLevelOperationalFormsSection() throws Exception {
        mockMvc.perform(get("/form-submissions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("فرم‌های عملیاتی")))
                .andExpect(content().string(containsString(form.getTitle())))
                .andExpect(content().string(containsString("همهٔ ارسال‌های فرم")))
                // Nested under داده‌های عملیاتی is gone.
                .andExpect(content().string(not(containsString("nav-group-parent"))));
    }
}
