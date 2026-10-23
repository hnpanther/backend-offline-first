package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.FieldValidationViewHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The option-severity editor, rendered through the real template composition.
 *
 * <p>The table has to arrive in its <strong>final</strong> shape: a configured field must show
 * its severities at the first paint, not after a script runs (AGENTS.md #126). A model-level
 * assertion cannot see that, only rendering the page can, and the failure mode is quiet,
 * because an administrator who sees every option as normal for a frame has no reason to doubt
 * it and every reason to re-tick what was already ticked.
 */
class FieldSeverityFormRenderTest {

    @Test
    @DisplayName("the add form offers an empty severity table and the two hidden inputs")
    void theAddFormCarriesTheSeverityBlock() {
        String html = render(null);

        assertThat(html).contains("option-severity-block");
        assertThat(html)
                .as("these two are what the server reads; the radios are display only")
                .contains("name=\"warningOptions\"")
                .contains("name=\"dangerOptions\"");
        assertThat(html)
                .as("nothing to mark until a vocabulary exists, and it says so")
                .contains("option-severity-empty");
    }

    @Test
    @DisplayName("a configured select renders each option with its own severity already ticked")
    void theEditFormArrivesWithTheSeveritiesChecked() {
        String html = render(selectField("Idle", "OF"));

        for (String option : List.of("OF", "ON", "Idle")) {
            assertThat(html).as("row for %s", option).contains(">" + option + "<");
        }

        String idleRow = rowFor(html, "Idle");
        assertThat(idleRow)
                .as("Idle is the class warning option, so its warning radio is pre-checked")
                .containsPattern("value=\"warning\"[^>]*checked");
        assertThat(idleRow).doesNotContainPattern("value=\"ok\"[^>]*checked");

        String ofRow = rowFor(html, "OF");
        assertThat(ofRow).containsPattern("value=\"danger\"[^>]*checked");

        String onRow = rowFor(html, "ON");
        assertThat(onRow)
                .as("ON carries no severity, so only its normal radio is checked")
                .containsPattern("value=\"ok\"[^>]*checked");
        assertThat(onRow).doesNotContainPattern("value=\"danger\"[^>]*checked");
    }

    @Test
    @DisplayName("a number field renders its ranges and no option rows")
    void aNumberFieldGetsRangesInstead() {
        FieldDefinition number = new FieldDefinition();
        number.setId(2L);
        number.setKey("Bar");
        number.setLabel("Pressure");
        number.setDataType("number");
        number.setOrder(1);
        number.setValidation(FieldValidationSupport.build(
                "number", null, 1.0, 5.0, null, 10.0, null, null));

        String html = render(number);

        assertThat(html).contains("name=\"warningMin\"");
        assertThat(html)
                .as("the block is present but has no rows, so it can post no severity")
                .contains("option-severity-empty");
    }

    /** The row that renders one option, so a per-row assertion cannot read another radio. */
    private static String rowFor(String html, String option) {
        int at = html.indexOf(">" + option + "<");
        assertThat(at).as("no row for %s", option).isNotNegative();
        return html.substring(html.lastIndexOf("<tr", at), html.indexOf("</tr>", at));
    }

    private static FieldDefinition selectField(String warn, String danger) {
        FieldDefinition fd = new FieldDefinition();
        fd.setId(1L);
        fd.setKey("Status");
        fd.setLabel("Status");
        fd.setDataType("select");
        fd.setRequired(false);
        fd.setOrder(1);
        fd.setValidation(FieldValidationSupport.build(
                "select", "OF,ON,Idle", null, null, null, null, warn, danger));
        return fd;
    }

    private String render(FieldDefinition editEntity) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        MockServletContext servlet = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servlet);
        request.setRequestURI("/asset-classes/1/fields");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);

        AssetClass assetClass = new AssetClass();
        assetClass.setId(1L);
        assetClass.setName("Pump");

        FieldValidationViewHelper helper = new FieldValidationViewHelper();
        try (StaticApplicationContext beans = new StaticApplicationContext()) {
            beans.getBeanFactory().registerSingleton("dateUtils", new DateUtils());
            beans.getBeanFactory().registerSingleton("fieldValidation", helper);
            context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new ThymeleafEvaluationContext(beans, null));
            context.setVariable("homePath", "/");
            context.setVariable("assetClass", assetClass);
            context.setVariable("dataTypeLabels", FieldDataTypes.labels());
            Page<FieldDefinition> page = new PageImpl<>(
                    editEntity == null ? List.of() : List.of(editEntity));
            context.setVariable("fieldDefinitions", page);
            context.setVariable("page", page);
            context.setVariable("editEntity", editEntity);
            context.setVariable("editSelectOptions",
                    editEntity == null ? "" : helper.optionsText(editEntity.getValidation()));
            context.setVariable("listState", Map.of());
            return engine.process("field-definitions", context);
        }
    }
}
