package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.AssetParameterRow;
import com.hnp.backendofflinefirst.entity.AssetEntry;
import com.hnp.backendofflinefirst.util.DateUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the asset parameter page for real, with no application startup and no database.
 *
 * <p>A Thymeleaf expression that names a model attribute which does not exist, or a field that
 * was renamed, compiles perfectly and fails at request time — which on this project has twice
 * meant a feature that was completely dead on the screen while every test was green
 * (CLAUDE.md). Rendering the composed template is the only check that sees it.
 */
class AssetParameterPageRenderTest {

    @Test
    @DisplayName("the page renders, and the rows arrive filled in before any script runs")
    void theParameterPageRendersWithItsRows() {
        String html = render(rows(), "RANGES_ONLY");

        assertThat(html)
                .contains("پارامترها و بازه‌های")
                .contains("AST-001")
                // the three modes, with the selected one checked by the server, not by script
                .contains("value=\"INHERIT\"")
                .contains("value=\"RANGES_ONLY\"")
                .contains("value=\"FULL\"")
                // indexed binding names, which is what AssetFieldOverrideForm binds
                .contains("rows[0].fieldKey")
                .contains("rows[0].warningMin")
                .contains("rows[0].dangerMax");
    }

    @Test
    @DisplayName("an existing override's numbers are in the inputs at the first paint")
    void theBandsArePrefilled() {
        String html = render(rows(), "RANGES_ONLY");

        assertThat(html)
                .contains("value=\"18\"")
                .contains("value=\"42\"")
                .as("a row the asset already overrides is marked, so nobody compares eight numbers by eye")
                .contains("اختصاصی");
    }

    @Test
    @DisplayName("a non-numeric parameter offers no band inputs at all")
    void aTextFieldGetsNoBandInputs() {
        String html = render(List.of(new AssetParameterRow(
                "note", "توضیح", null, "text", "", "", "", "", false, false, false)), "RANGES_ONLY");

        assertThat(html)
                .contains("بازه فقط برای پارامتر عددی معنا دارد")
                .doesNotContain("rows[0].warningMin");
    }

    @Test
    @DisplayName("a class with no parameters says so instead of rendering an empty table")
    void anEmptyClassExplainsItself() {
        assertThat(render(List.of(), "INHERIT")).contains("این کلاس هنوز هیچ پارامتری ندارد");
    }

    @Test
    @DisplayName("the FULL mode warning is in the markup, not added later by a script")
    void theDetachmentWarningIsServerRendered() {
        String html = render(rows(), "INHERIT");

        assertThat(html)
                .as("an engineer adding a field to a class must be able to see which assets will "
                        + "not receive it; the warning cannot wait for a click")
                .contains("نمی‌رسد");
    }

    @Test
    @DisplayName("FULL mode's add control and its row template are in the markup")
    void theAddControlIsServerRendered() {
        String html = render(rows(), "FULL");

        assertThat(html)
                .contains("id=\"addRowButton\"")
                .contains("افزودن پارامتر تازه")
                // The shape of an added row lives in a <template>, so its inputs carry no name
                // until the script clones one — nothing half-built is ever submitted.
                .contains("id=\"newRowTemplate\"")
                .contains("data-field=\"fieldKey\"")
                // The hyphen is escaped: an unescaped trailing `-` in a character class is a
                // syntax error under the regex `v` flag, and a pattern that does not compile is
                // IGNORED — the input would validate nothing while looking correct.
                // FormPatternAttributeTest guards this repository-wide.
                .contains("pattern=\"[A-Za-z0-9_\\-]+\"")
                .doesNotContain("name=\"rows[].fieldKey\"");
    }

    @Test
    @DisplayName("an added field is marked as the asset's own, not merely as re-banded")
    void anAddedFieldIsLabelledDistinctly() {
        String html = render(List.of(new AssetParameterRow(
                "vibration", "لرزش", "mm/s", "number", "0", "7", "", "", true, true, true)), "FULL");

        assertThat(html).contains("افزوده");
        assertThat(html).contains("rows[0].custom");
    }

    @Test
    @DisplayName("an added field posts its own definition back, or a save renames it to its key")
    void anAddedFieldRoundTripsItsDefinition() {
        String html = render(List.of(new AssetParameterRow(
                "vibration", "لرزش", "mm/s", "number", "0", "7", "", "", true, true, true)), "FULL");

        assertThat(html)
                .as("this field exists nowhere but on the asset, so the form is its only source")
                .contains("rows[0].label")
                .contains("rows[0].unit")
                .contains("rows[0].dataType")
                .contains("value=\"لرزش\"")
                .contains("value=\"mm/s\"");
    }

    @Test
    @DisplayName("a class field is read-only here — the class page owns it")
    void aClassFieldIsNotEditableOnTheAssetScreen() {
        String html = render(rows(), "FULL");

        assertThat(html)
                .doesNotContain("rows[0].label")
                .doesNotContain("rows[0].unit");
    }

    @Test
    @DisplayName("every row can be removed in FULL, and the checkbox posts the right name")
    void removalIsBoundPerRow() {
        String html = render(rows(), "FULL");

        assertThat(html)
                .contains("rows[0].removed")
                .contains("rows[1].removed");
    }

    // ---------------------------------------------------------------------------------------

    private static List<AssetParameterRow> rows() {
        return List.of(
                new AssetParameterRow("Ampere", "Ampere", "A", "number",
                        "18", "42", "12", "55", true, true, false),
                new AssetParameterRow("Pressure", "Pressure", "Bar", "number",
                        "", "", "", "", true, false, false));
    }

    private String render(List<AssetParameterRow> rows, String fieldSource) {
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
        request.setRequestURI("/asset-entries/1/parameters");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);

        AssetEntry asset = new AssetEntry();
        asset.setId(1L);
        asset.setAssetCode("AST-001");
        asset.setAssetName("پمپ آزمایشی");
        asset.setClassId(3L);

        try (StaticApplicationContext beans = new StaticApplicationContext()) {
            beans.getBeanFactory().registerSingleton("dateUtils", new DateUtils());
            context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new ThymeleafEvaluationContext(beans, null));
            // HomePathAdvice puts this on every real request (AGENTS.md #128).
            context.setVariable("homePath", "/");
            context.setVariable("asset", asset);
            context.setVariable("assetClassLabel", "پمپ");
            context.setVariable("fieldSource", fieldSource);
            context.setVariable("rows", rows);
            return engine.process("asset-entry-parameters", context);
        }
    }
}
