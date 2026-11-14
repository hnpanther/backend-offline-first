package com.hnp.backendofflinefirst.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnp.backendofflinefirst.domain.ActionSource;
import com.hnp.backendofflinefirst.domain.AssignmentType;
import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetField;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.entity.UnitOperator;
import com.hnp.backendofflinefirst.entity.User;
import com.hnp.backendofflinefirst.entity.UserRole;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetFieldRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRevisionRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.RoleRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.repository.UnitOperatorRepository;
import com.hnp.backendofflinefirst.repository.UserRepository;
import com.hnp.backendofflinefirst.repository.UserRoleRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hnp.backendofflinefirst.domain.SignatureValues;
import com.hnp.backendofflinefirst.security.AppUserDetails;
import com.hnp.backendofflinefirst.service.IntegrationLogSheetService;
import com.hnp.backendofflinefirst.service.LogSheetFieldService;
import com.hnp.backendofflinefirst.support.TestPrincipals;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * The signature field end to end (roadmap §32): a tablet signs the round's own questions, the
 * server stamps who and the hash, the drawing arrives afterwards without disturbing the stamp, the
 * panel says whether it still vouches for the answers, and the integration feed publishes the
 * signer as a person — never the internal user id stored in the reading.
 */
@Transactional
class SignatureFieldIntegrationTest extends AbstractPostgresIntegrationTest {

    /** A 1x1 PNG: real magic bytes, so the server's sniffing accepts it as an image. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired LogSheetFieldRepository logSheetFieldRepository;
    @Autowired LogSheetRevisionRepository logSheetRevisionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UnitOperatorRepository unitOperatorRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LogSheetFieldService sheetFieldService;
    @Autowired IntegrationLogSheetService integrationLogSheetService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theServerStampsTheSessionsUserAndIgnoresASignerTheDeviceClaims() throws Exception {
        Fixture f = seedWithSignature();
        String drawing = UUID.randomUUID().toString();

        JsonNode result = submit(f, """
                "sheetFormData":{"note":"سالم","sign":{"type":"signature","ids":["%s"],"signedAt":%d,
                                 "signedByUserId":99999,"answersHash":"sha256:forged"}},
                """.formatted(drawing, System.currentTimeMillis() - 5000));
        assertThat(result.get("outcome").asText()).isEqualTo("SUBMITTED");

        LogSheet saved = logSheetRepository.findById(f.sheetId()).orElseThrow();
        Map<?, ?> sign = (Map<?, ?>) saved.getSheetFormData().get("sign");
        assertThat(((Number) sign.get("signedByUserId")).longValue()).isEqualTo(f.operatorId());
        assertThat(String.valueOf(sign.get("answersHash"))).startsWith("sha256:").isNotEqualTo("sha256:forged");
        assertThat(SignatureValues.state(sign, saved.getSheetFormData(), sheetFieldService.resolveForSheet(saved)))
                .isEqualTo(SignatureValues.State.VALID);
    }

    @Test
    void theDrawingArrivingAfterTheRoundLeavesTheStampExactlyAsItWas() throws Exception {
        Fixture f = seedWithSignature();
        String drawing = UUID.randomUUID().toString();
        submit(f, """
                "sheetFormData":{"note":"سالم","sign":{"type":"signature","ids":["%s"]}},
                """.formatted(drawing));
        Map<String, Object> before = new LinkedHashMap<>(
                logSheetRepository.findById(f.sheetId()).orElseThrow().getSheetFormData());

        mockMvc.perform(multipart("/api/attachments")
                        .file(new MockMultipartFile("file", "signature.png", "image/png", PNG))
                        .param("id", drawing)
                        .param("logSheetId", String.valueOf(f.sheetId()))
                        .param("fieldKey", "sign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token()))
                .andExpect(status().is2xxSuccessful());

        assertThat(logSheetRepository.findById(f.sheetId()).orElseThrow().getSheetFormData()).isEqualTo(before);
    }

    @Test
    void thePanelSaysWhoSignedAndThatACorrectionMadeTheSignatureStale() throws Exception {
        Fixture f = seedWithSignature();
        submit(f, """
                "sheetFormData":{"note":"سالم","sign":{"type":"signature","ids":["%s"]}},
                """.formatted(UUID.randomUUID()));
        AppUserDetails admin = TestPrincipals.of(userRepository.findById(f.operatorId()).orElseThrow(),
                Set.of("ADMIN"), Set.of("GET:/log-sheets/{id}"));

        String valid = mockMvc.perform(get("/log-sheets/{id}", f.sheetId()).with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(valid).contains("امضا: ").doesNotContain("پیش از تغییر پاسخ‌ها");

        LogSheet sheet = logSheetRepository.findById(f.sheetId()).orElseThrow();
        Map<String, Object> corrected = new LinkedHashMap<>(sheet.getSheetFormData());
        corrected.put("note", "نشتی");
        sheet.setSheetFormData(corrected);
        logSheetRepository.saveAndFlush(sheet);

        String stale = mockMvc.perform(get("/log-sheets/{id}", f.sheetId()).with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stale).contains("امضا پیش از تغییر پاسخ‌ها گرفته شده");
    }

    @Test
    void theIntegrationFeedPublishesTheSignerAsAPersonAndNeverTheInternalId() throws Exception {
        Fixture f = seedWithSignature();
        submit(f, """
                "sheetFormData":{"note":"سالم","sign":{"type":"signature","ids":["%s"]}},
                """.formatted(UUID.randomUUID()));

        var detail = integrationLogSheetService.findDetail(f.sheetId()).orElseThrow();
        String json = objectMapper.writeValueAsString(detail);
        assertThat(json).doesNotContain("signedByUserId").doesNotContain("answersHash");

        var sign = detail.sheetForm().values().stream().filter(v -> "sign".equals(v.key())).findFirst().orElseThrow();
        Map<?, ?> value = (Map<?, ?>) sign.value();
        assertThat(value.get("state")).isEqualTo("VALID");
        assertThat(json).contains("sig-op-");
        assertThat(sign.attachments()).hasSize(1);
    }

    private record Fixture(Long sheetId, Long assetId, Long classId, Long operatorId, String token) {}

    private JsonNode submit(Fixture f, String sheetFormFragment) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/log-sheets/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token())
                        .content("""
                                {"logSheets":[{"serverId":%d,"localId":"l1","clientActionId":"%s",
                                  "completedAt":%d, %s
                                  "entries":[{"assetId":%d,"classId":%d,"formData":{"temp":"41"}}]}]}
                                """.formatted(f.sheetId(), UUID.randomUUID(),
                                System.currentTimeMillis() - 1000, sheetFormFragment,
                                f.assetId(), f.classId())))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get(0);
    }

    private Fixture seedWithSignature() {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("SIG-BU-" + nano);
        unit.setName("Signature Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("SIG-LOC-" + nano);
        location.setName("Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("SIG-SF-" + nano);
        subFunction.setName("Sub");
        subFunction.setTag("NFC-SFM-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("SIG Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        FieldDefinition def = new FieldDefinition();
        def.setClassId(assetClass.getId());
        def.setKey("temp");
        def.setLabel("دما");
        def.setDataType("number");
        def.setRequired(false);
        def.setOrder(1);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("SIG-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("SIG Template " + nano);
        template.setScopeType(AssetHierarchyService.SCOPE_LOCATION);
        template.setScopeId(location.getId());
        template.setClassId(assetClass.getId());
        template.setOperationalUnitId(unit.getId());
        template.setGenerationMode(GenerationMode.MANUAL);
        template.setScheduleActive(false);
        template.setActive(true);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template = templateRepository.saveAndFlush(template);

        // The round-level question, owned by the template and frozen onto the sheet below.
        saveQuestion(template.getId(), "note", "یادداشت تحویل شیفت", "text", 1, now);
        saveQuestion(template.getId(), "sign", "امضای اپراتور", "signature", 2, now);

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);

        User operator = operator(unit.getId(), "sig-op-" + nano);
        sheet.setAssigneeUserId(operator.getId());
        sheet.setAssignmentType(AssignmentType.SUPERVISOR_ASSIGNED);
        sheet.setAssignedAt(now);
        sheet.setDueAt(now + 3_600_000L);
        sheet.setStatus(LogSheetStatus.IN_PROGRESS);
        logSheetRepository.saveAndFlush(sheet);

        return new Fixture(sheet.getId(), asset.getId(), assetClass.getId(), operator.getId(),
                loginToken(operator.getUsername()));
    }

    private User operator(Long unitId, String username) {
        long now = System.currentTimeMillis();
        User u = new User();
        u.setUsername(username);
        u.setPersonnelCode("PC-" + UUID.randomUUID());
        u.setFullName(username);
        u.setPasswordHash(passwordEncoder.encode("pw123456"));
        u.setActive(true);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        u = userRepository.saveAndFlush(u);

        UserRole ur = new UserRole();
        ur.setUserId(u.getId());
        ur.setRoleId(roleRepository.findByCode("OPERATOR").orElseThrow().getId());
        userRoleRepository.saveAndFlush(ur);

        UnitOperator link = new UnitOperator();
        link.setUnitId(unitId);
        link.setUserId(u.getId());
        unitOperatorRepository.saveAndFlush(link);
        return u;
    }

    private String loginToken(String username) {
        try {
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("username", username, "password", "pw123456"))))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(login.getResponse().getContentAsString())
                    .get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void saveQuestion(Long templateId, String key, String label, String type, int order, long now) {
        LogSheetField question = new LogSheetField();
        question.setTemplateId(templateId);
        question.setKey(key);
        question.setLabel(label);
        question.setDataType(type);
        question.setRequired(false);
        question.setOrder(order);
        question.setVersion(1);
        question.setDeleted(false);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);
        logSheetFieldRepository.saveAndFlush(question);
    }
}
