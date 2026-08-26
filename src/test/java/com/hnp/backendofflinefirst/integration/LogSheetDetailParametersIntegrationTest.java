package com.hnp.backendofflinefirst.integration;

import com.hnp.backendofflinefirst.domain.GenerationMode;
import com.hnp.backendofflinefirst.domain.LogSheetStatus;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.entity.Location;
import com.hnp.backendofflinefirst.entity.LogSheet;
import com.hnp.backendofflinefirst.entity.LogSheetEntry;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
import com.hnp.backendofflinefirst.entity.OperationalUnit;
import com.hnp.backendofflinefirst.entity.SubFunction;
import com.hnp.backendofflinefirst.repository.AssetClassRepository;
import com.hnp.backendofflinefirst.repository.AssetEntryRepository;
import com.hnp.backendofflinefirst.repository.FieldDefinitionRepository;
import com.hnp.backendofflinefirst.repository.LocationRepository;
import com.hnp.backendofflinefirst.repository.LogSheetEntryRepository;
import com.hnp.backendofflinefirst.repository.LogSheetRepository;
import com.hnp.backendofflinefirst.repository.LogSheetTemplateRepository;
import com.hnp.backendofflinefirst.repository.OperationalUnitRepository;
import com.hnp.backendofflinefirst.repository.SubFunctionRepository;
import com.hnp.backendofflinefirst.service.AssetHierarchyService;
import com.hnp.backendofflinefirst.service.LogSheetGenerationService;
import com.hnp.backendofflinefirst.support.AbstractPostgresIntegrationTest;
import com.hnp.backendofflinefirst.support.WithAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sheet's page lists every parameter of an asset, not only the ones somebody answered.
 *
 * <p><b>The gap.</b> {@code form_data} holds only fields that carry a real answer — that is what
 * makes {@code max_severity IS NOT NULL} an exact has-a-reading test, and what stops one
 * supervisor save writing {@code {"Bar": "", "Status": ""}} onto forty entries. But the detail
 * page rendered its rows straight off those keys, so an asset with three of seven parameters
 * recorded showed three rows and the four the operator skipped were <b>indistinguishable from
 * parameters the class does not define</b> — which is the one question a supervisor opens the
 * page to answer. An asset with nothing at all rendered a bare dash.
 *
 * <p>These assert against the rendered HTML rather than the helper, because that is where the
 * defect actually lived: {@code FormDataViewHelper} was doing exactly what it was asked, and the
 * fragment was asking the wrong question. A unit test on the helper alone would have passed
 * throughout.
 */
@Transactional
class LogSheetDetailParametersIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OperationalUnitRepository operationalUnitRepository;
    @Autowired LocationRepository locationRepository;
    @Autowired SubFunctionRepository subFunctionRepository;
    @Autowired AssetClassRepository assetClassRepository;
    @Autowired AssetEntryRepository assetEntryRepository;
    @Autowired FieldDefinitionRepository fieldDefinitionRepository;
    @Autowired LogSheetTemplateRepository templateRepository;
    @Autowired LogSheetRepository logSheetRepository;
    @Autowired LogSheetEntryRepository logSheetEntryRepository;
    @Autowired AssetHierarchyService hierarchyService;
    @Autowired LogSheetGenerationService generationService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void listsTheParametersNobodyAnsweredAlongsideTheOnesTheyDid() throws Exception {
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        assertThat(html).contains("دما");
        // The two the operator skipped. Before this they were absent from the page entirely.
        assertThat(html).contains("فشار");
        assertThat(html).contains("توضیح");
        assertThat(html).contains("ثبت نشده");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void marksEachParameterRowSoTheFilterCanHideTheEmptyOnes() throws Exception {
        // The «فقط دارای مقدار» toggle is client-side: every row is rendered and tagged, and a
        // class on the container hides the empty ones. Without the tag there is nothing to hide.
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        assertThat(html).contains("data-param-state=\"filled\"");
        assertThat(html).contains("data-param-state=\"empty\"");
        assertThat(html).contains("data-detail-param-filter=\"all\"");
        assertThat(html).contains("data-detail-param-filter=\"filled\"");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void showsWhatAnUntouchedAssetWasSupposedToCarry() throws Exception {
        // Previously a bare «—», which says nothing about what the round asked for.
        Long sheetId = seed(Map.of());

        String html = render(sheetId);

        assertThat(html).contains("دما");
        assertThat(html).contains("فشار");
        assertThat(html).contains("توضیح");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void stillRendersAReadingWhoseFieldTheClassNoLongerDefines() throws Exception {
        // A sheet frozen before a field was retired can hold a value with no definition left.
        // Enumerating the schema must not become a way of hiding it.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("temp", "42");
        data.put("retired_param", "7");
        Long sheetId = seed(data);

        String html = render(sheetId);

        assertThat(html).contains("retired_param");
        assertThat(html).contains("7");
    }

    @Test
    @WithAppUser(roles = "ADMIN", authorities = "GET:/log-sheets/{id}")
    void doesNotFlagAnUnansweredNumberAsOutOfRange() throws Exception {
        // «فشار» has a danger band starting above zero. Evaluating an absent reading against it
        // would paint every unfilled row red, which is worse than not showing the row at all.
        Long sheetId = seed(Map.of("temp", "42"));

        String html = render(sheetId);

        assertThat(html).doesNotContain("خارج از بازه خطر است");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String render(Long sheetId) throws Exception {
        return mockMvc.perform(get("/log-sheets/{id}", sheetId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /** A generated one-asset sheet whose class defines three parameters, filled as given. */
    private Long seed(Map<String, Object> formData) {
        long now = System.currentTimeMillis();
        long nano = System.nanoTime();

        OperationalUnit unit = new OperationalUnit();
        unit.setCode("PRM-BU-" + nano);
        unit.setName("Parameter Unit");
        unit.setCreatedAt(now);
        unit.setUpdatedAt(now);
        unit = operationalUnitRepository.saveAndFlush(unit);

        Location location = new Location();
        location.setCode("PRM-LOC-" + nano);
        location.setName("Parameter Hall");
        location.setCreatedAt(now);
        location.setUpdatedAt(now);
        location = locationRepository.saveAndFlush(location);

        SubFunction subFunction = new SubFunction();
        subFunction.setCode("PRM-SF-" + nano);
        subFunction.setName("Parameter Sub");
        subFunction.setTag("NFC-PRM-" + nano);
        subFunction.setCreatedAt(now);
        subFunction.setUpdatedAt(now);
        hierarchyService.applySubFunctionParent(
                subFunction, AssetHierarchyService.SCOPE_LOCATION, location.getId());
        subFunction = hierarchyService.saveSubFunction(subFunction);

        AssetClass assetClass = new AssetClass();
        assetClass.setName("Parameter Pump " + nano);
        assetClass.setCreatedAt(now);
        assetClass.setUpdatedAt(now);
        assetClass = assetClassRepository.saveAndFlush(assetClass);

        saveField(assetClass.getId(), "temp", "دما", "number", 1, false);
        saveField(assetClass.getId(), "bar", "فشار", "number", 2, true);
        saveField(assetClass.getId(), "note", "توضیح", "text", 3, false);

        AssetEntry asset = new AssetEntry();
        asset.setAssetCode("PRM-A1-" + nano);
        asset.setAssetName("Pump");
        asset.setClassId(assetClass.getId());
        asset.setSubFunctionId(subFunction.getId());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        asset = assetEntryRepository.saveAndFlush(asset);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setName("Parameter Template " + nano);
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

        LogSheet sheet = generationService.generateFromTemplate(
                template, GenerationMode.MANUAL, null, now);
        sheet.setStatus(LogSheetStatus.SUBMITTED);
        sheet.setCompletedAt(now);
        logSheetRepository.saveAndFlush(sheet);

        LogSheetEntry entry = logSheetEntryRepository.findByLogSheetId(sheet.getId()).get(0);
        // Written straight in, exactly as `storableFormData` leaves it: only answered keys.
        entry.setFormData(formData.isEmpty() ? Map.of() : new LinkedHashMap<>(formData));
        entry.setMaxSeverity(formData.isEmpty() ? null : "OK");
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        logSheetEntryRepository.saveAndFlush(entry);

        return sheet.getId();
    }

    private void saveField(Long classId, String key, String label, String dataType, int order,
                           boolean withDangerBand) {
        long now = System.currentTimeMillis();
        FieldDefinition def = new FieldDefinition();
        def.setClassId(classId);
        def.setKey(key);
        def.setLabel(label);
        def.setDataType(dataType);
        def.setRequired(false);
        def.setOrder(order);
        if (withDangerBand) {
            def.setValidation(com.hnp.backendofflinefirst.domain.FieldValidationSupport
                    .build("number", null, 20.0, 80.0, 10.0, 90.0));
        }
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        fieldDefinitionRepository.saveAndFlush(def);
    }
}
