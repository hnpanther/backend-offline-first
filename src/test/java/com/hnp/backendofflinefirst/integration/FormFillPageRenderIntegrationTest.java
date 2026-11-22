package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AttachmentKind;
import com.hnp.backendofflinefirst.dto.FormDraftRequest;
import com.hnp.backendofflinefirst.dto.FormSubmitResult;
import com.hnp.backendofflinefirst.entity.Attachment;
import com.hnp.backendofflinefirst.entity.FormDefinition;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.security.Capabilities;
import com.hnp.backendofflinefirst.service.FormDefinitionService;
import com.hnp.backendofflinefirst.service.FormFieldService;
import com.hnp.backendofflinefirst.service.FormSubmissionService;
import com.hnp.backendofflinefirst.service.LookupListService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
        "GET:/form-submissions", "GET:/form-submissions/all", "GET:/form-submissions/{id}",
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
    @Autowired FormSubmissionService formSubmissionService;
    @Autowired LookupListService lookupListService;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired OperationalUnitRepository unitRepository;
    @Autowired JdbcTemplate jdbc;

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

    @Test
    void lookupPickerLoadsItemsOnNewFillAndDraftEdit() throws Exception {
        String listCode = "crew" + UUID.randomUUID().toString().substring(0, 8);
        var list = lookupListService.createList(listCode, "پرسنل آزمون", null, null);
        lookupListService.createItem(list.getId(), "P-1001", "علی رضایی", Map.of(), 1, null);
        formFieldService.create(form.getId(), "crew", "پرسنل", "lookup", null, false,
                Map.of("lookupList", listCode), 40);
        // Draft before any MockMvc call: the security filter clears SecurityContextHolder
        // after a request, and upsertDraft reads that holder for unit scope.
        FormSubmitResult drafted = saveDraft();

        String newUrl = "/forms/" + form.getId() + "/lookups/" + listCode;
        mockMvc.perform(get("/form-submissions/new").param("formId", form.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lookup-field")))
                .andExpect(content().string(containsString("lookup-search")))
                .andExpect(content().string(containsString(newUrl)));

        mockMvc.perform(get(newUrl).param("q", "علی"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("P-1001")))
                .andExpect(content().string(containsString("علی رضایی")));

        String editUrl = "/form-submissions/" + drafted.getSubmissionId() + "/lookups/" + listCode;
        mockMvc.perform(get("/form-submissions/{id}/edit", drafted.getSubmissionId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lookup-search")))
                .andExpect(content().string(containsString(editUrl)));
        mockMvc.perform(get(editUrl).param("q", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("P-1001")));
    }

    @Test
    void draftFillRendersAndUploadsImageAudioAndVideo() throws Exception {
        formFieldService.create(form.getId(), "pic", "عکس", "image", null, false, Map.of(), 40);
        formFieldService.create(form.getId(), "voice", "صوت", "audio", null, false, Map.of(), 50);
        formFieldService.create(form.getId(), "clip", "ویدئو", "video", null, false, Map.of(), 60);
        FormSubmitResult drafted = saveDraft();

        mockMvc.perform(get("/form-submissions/new").param("formId", form.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("برای بارگذاری فایل ابتدا")))
                .andExpect(content().string(not(containsString("name=\"fd_pic\""))))
                .andExpect(content().string(not(containsString("data-upload-url=\"/log-sheets/"))));
        Long id = drafted.getSubmissionId();
        String uploadUrl = "/form-submissions/" + id + "/attachments";
        mockMvc.perform(get("/form-submissions/{id}/edit", id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("att-uploader")))
                .andExpect(content().string(containsString("data-kind=\"IMAGE\"")))
                .andExpect(content().string(containsString("data-kind=\"AUDIO\"")))
                .andExpect(content().string(containsString("data-kind=\"VIDEO\"")))
                .andExpect(content().string(containsString("data-upload-url=\"" + uploadUrl + "\"")))
                .andExpect(content().string(containsString("data-field-key=\"pic\"")))
                .andExpect(content().string(containsString("data-field-key=\"voice\"")))
                .andExpect(content().string(containsString("data-field-key=\"clip\"")))
                .andExpect(content().string(not(containsString("data-upload-url=\"/log-sheets/"))));

        upload(id, "pic", "shot.png", MediaType.IMAGE_PNG_VALUE, PNG, "IMAGE");
        upload(id, "voice", "note.wav", "audio/wav", WAV, "AUDIO");
        upload(id, "clip", "clip.mp4", "video/mp4", MP4, "VIDEO");

        assertThat(attachmentRepository.findByFormSubmissionIdOrderByUploadedAtAsc(id))
                .extracting(Attachment::getKind)
                .containsExactlyInAnyOrder(AttachmentKind.IMAGE, AttachmentKind.AUDIO, AttachmentKind.VIDEO);
        assertThat(attachmentRepository.findByFormSubmissionIdOrderByUploadedAtAsc(id))
                .allSatisfy(row -> {
                    assertThat(row.getFormSubmissionId()).isEqualTo(id);
                    assertThat(row.getLogSheetId()).isNull();
                });
    }

    @Test
    void allSubmissionsPageLetsNewPickAnyForm() throws Exception {
        FormDefinition other = formDefinitionService.create(
                "FILL2-" + UUID.randomUUID().toString().substring(0, 8),
                "فرم دوم برای انتخاب",
                null,
                List.of(unit.getId()),
                true);
        mockMvc.perform(get("/form-submissions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("dropdown-toggle")))
                .andExpect(content().string(containsString("<select name=\"formId\"")))
                .andExpect(content().string(containsString(
                        "/form-submissions/new?formId=" + form.getId())))
                .andExpect(content().string(containsString(
                        "/form-submissions/new?formId=" + other.getId())));
    }

    @Test
    @WithAppUser(authorities = {
            "GET:/form-submissions", "POST:/form-submissions/draft",
            Capabilities.SCOPE_PLANT_WIDE
    })
    void formMenuLocksNewSubmissionAndHidesAllSubmissionsPage() throws Exception {
        FormDefinition other = formDefinitionService.create(
                "FILL3-" + UUID.randomUUID().toString().substring(0, 8),
                "فرم دیگر",
                null,
                List.of(unit.getId()),
                true);

        // The panel's access-denied handler redirects home; it does not answer 403.
        mockMvc.perform(get("/form-submissions"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/form-submissions").param("formId", form.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("فرم‌های عملیاتی")))
                .andExpect(content().string(containsString(form.getTitle())))
                .andExpect(content().string(containsString("type=\"hidden\" name=\"formId\"")))
                .andExpect(content().string(not(containsString("<select name=\"formId\""))))
                .andExpect(content().string(not(containsString("dropdown-toggle"))))
                .andExpect(content().string(containsString(
                        "/form-submissions/new?formId=" + form.getId())))
                .andExpect(content().string(not(containsString(
                        "/form-submissions/new?formId=" + other.getId()))))
                .andExpect(content().string(not(containsString("همهٔ ارسال‌های فرم"))));
    }

    @Test
    void allSubmissionsPermissionIsAdminByDefaultAndUnitViewStays() {
        assertThat(grantCount("ADMIN", "GET:/form-submissions/all")).isEqualTo(1);
        assertThat(grantCount("HIGH_USER", "GET:/form-submissions/all")).isZero();
        assertThat(grantCount("SUPERVISOR", "GET:/form-submissions/all")).isZero();
        assertThat(grantCount("SENIOR_OPERATOR", "GET:/form-submissions/all")).isZero();
        assertThat(grantCount("OPERATOR", "GET:/form-submissions/all")).isZero();
        assertThat(grantCount("SUPERVISOR", "GET:/form-submissions")).isEqualTo(1);
        assertThat(grantCount("OPERATOR", "GET:/form-submissions")).isEqualTo(1);
    }

    private FormSubmitResult saveDraft() {
        FormDraftRequest draft = new FormDraftRequest();
        draft.setFormDefinitionId(form.getId());
        draft.setReporterUnitId(unit.getId());
        draft.setLocalId("web-" + UUID.randomUUID());
        draft.setFormData(Map.of());
        FormSubmitResult saved = formSubmissionService.upsertDraft(1L, draft, ActionSource.WEB);
        assertThat(saved.getOutcome()).isEqualTo(FormSubmissionService.OUTCOME_OK);
        return saved;
    }

    private void upload(Long submissionId, String fieldKey, String filename, String mime, byte[] bytes, String kind)
            throws Exception {
        mockMvc.perform(multipart("/form-submissions/{id}/attachments", submissionId)
                        .file(new MockMultipartFile("file", filename, mime, bytes))
                        .param("fieldKey", fieldKey)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"kind\":\"" + kind + "\"")))
                .andExpect(content().string(containsString("\"fieldKey\":\"" + fieldKey + "\"")));
    }

    private int grantCount(String roleCode, String permissionCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = ? AND p.code = ?
                """, Integer.class, roleCode, permissionCode);
        return count == null ? 0 : count;
    }

    private static byte[] concat(byte[] head, int padding) {
        byte[] out = new byte[head.length + padding];
        System.arraycopy(head, 0, out, 0, head.length);
        return out;
    }

    private static final byte[] PNG = concat(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 8);
    private static final byte[] WAV = concat("RIFF____WAVE".getBytes(StandardCharsets.US_ASCII), 4);
    private static final byte[] MP4 = concat("____ftypisom".getBytes(StandardCharsets.US_ASCII), 4);
}
