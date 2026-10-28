package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
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
                "note", "توضیح", null, "text", "", "", "", "", false, false, false, false, false, java.util.List.of(), java.util.Map.of())), "RANGES_ONLY");

        assertThat(html)
                .as("a text field has neither a range nor a vocabulary, and the cell says which")
                .contains("این نوع پارامتر بازه یا شدت ندارد")
                .doesNotContain("rows[0].warningMin")
                .doesNotContain("rows[0].warningOptions");
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
                "vibration", "لرزش", "mm/s", "number", "0", "7", "", "", true, true, true, false, false, java.util.List.of(), java.util.Map.of())), "FULL");

        assertThat(html).contains("افزوده");
        assertThat(html).contains("rows[0].custom");
    }

    @Test
    @DisplayName("an added field posts its own definition back, or a save renames it to its key")
    void anAddedFieldRoundTripsItsDefinition() {
        String html = render(List.of(new AssetParameterRow(
                "vibration", "لرزش", "mm/s", "number", "0", "7", "", "", true, true, true, false, false, java.util.List.of(), java.util.Map.of())), "FULL");

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

    /**
     * The whole reason «افزودن پارامتر تازه» did nothing.
     *
     * <p>The script was after the closing {@code </div>} of {@code #pageContent}, which the
     * layout's {@code th:replace="~{::#pageContent}"} discards — silently, with no error and
     * nothing missing from the visible page. The button rendered, looked enabled, and had no
     * listener. This renders through the real composition and asserts the handler survived it.
     *
     * <p>{@code TemplateLayoutContractTest} enforces the same rule across every template; this
     * one is page-specific because the symptom was page-specific and worth naming.
     */
    @Test
    @DisplayName("the script survives composition, so the add button actually has a handler")
    void theBehaviourScriptIsNotDiscardedByTheLayout() {
        String html = render(rows(), "FULL");

        assertThat(html)
                .as("the markup the layout kept")
                .contains("id=\"addRowButton\"");
        assertThat(html)
                .as("and the handler, which lived outside #pageContent and never reached the browser")
                .contains("addButton.addEventListener")
                .contains("newRowTemplate");
    }

    @Test
    @DisplayName("a field the asset already removed renders with its box ticked")
    void anAlreadyRemovedFieldRendersTicked() {
        String html = render(List.of(
                new AssetParameterRow("ampere", "Ampere", "A", "number",
                        "", "", "", "", true, false, false, true, false, java.util.List.of(), java.util.Map.of()),
                new AssetParameterRow("note", "Note", null, "text",
                        "", "", "", "", false, false, false, false, false, java.util.List.of(), java.util.Map.of())), "FULL");

        // Untickable means unrecoverable: without this the only way back is resetting the whole
        // asset to «از کلاس». It is also what makes the next save keep the removal.
        assertThat(removalBox(html, 0)).contains("checked");
        assertThat(removalBox(html, 1)).doesNotContain("checked");
    }

    // ---------------------------------------------------------------------------------------

    private static List<AssetParameterRow> rows() {
        return List.of(
                new AssetParameterRow("Ampere", "Ampere", "A", "number",
                        "18", "42", "12", "55", true, true, false, false, false, java.util.List.of(), java.util.Map.of()),
                new AssetParameterRow("Pressure", "Pressure", "Bar", "number",
                        "", "", "", "", true, false, false, false, false, java.util.List.of(), java.util.Map.of()));
    }

    /**
     * A control that is unavailable has to say so.
     *
     * <p>This is the whole of the "the add-parameter button does not work" report. It was never
     * broken: adding or removing a parameter only means anything once the asset owns its list,
     * so both controls live behind {@code FULL} — and outside it they were simply absent, with
     * nothing on the page connecting them to the mode. An administrator on «فقط بازه» saw a
     * footer with nothing in it and reasonably concluded the feature was faulty.
     */
    @Test
    @DisplayName("outside FULL the page explains why adding a parameter is unavailable")
    void theLimitedModesSayWhyTheyCannotAddParameters() {
        for (String mode : List.of("INHERIT", "RANGES_ONLY")) {
            String html = render(rows(), mode);
            assertThat(html)
                    .as("%s must carry the explanation, not an empty footer", mode)
                    .contains("not-full-only")
                    .contains("پارامترهای سفارشی");
            assertThat(html)
                    .as("%s must point an administrator at the class page for a shared parameter", mode)
                    .contains("صفحهٔ کلاس‌هاست");
        }
    }

    /**
     * The mode is stamped by the server, so the page does not show the FULL-only controls and
     * then take them away a frame later (AGENTS.md #126).
     */
    @Test
    @DisplayName("the chosen mode is already on the form at the first paint")
    void theModeIsServerRenderedRatherThanAppliedByScript() {
        assertThat(render(rows(), "FULL")).contains("js-mode-full");
        assertThat(render(rows(), "RANGES_ONLY")).contains("js-mode-limited");
        assertThat(render(rows(), "INHERIT")).contains("js-mode-limited");
    }

    /**
     * The switch column is a verb, not a bare box that reads as "select" — and it says
     * «غیرفعال», the same word the class field page uses. Two words for one idea at two
     * levels is how an administrator learns to distrust both, and «حذف» would also be a lie:
     * the row's bands and definition survive the tick.
     */
    @Test
    @DisplayName("the disable column is labelled, and with the word the class page uses")
    void theRemovalColumnSaysWhatTickingItDoes() {
        String html = render(rows(), "FULL");
        assertThat(html).contains("غیرفعال در این دارایی");
        assertThat(html)
                .as("«delete» promises something this control does not do")
                .doesNotContain("حذف از این دارایی");
    }

    /**
     * An added parameter may be any type the renderer understands.
     *
     * <p>The row template had no type control at all, and {@code AssetFieldResolver.fromOverride}
     * falls back to {@code "number"} when an override names none — so every parameter an asset
     * added was numeric, with nothing on the screen to say why or to change it.
     */
    @Test
    @DisplayName("the added-row template offers the full type vocabulary, not just numbers")
    void theAddedRowCanDeclareItsType() {
        String html = render(rows(), "FULL");

        assertThat(html).contains("data-field=\"dataType\"");
        // Every type the class editor offers has to be reachable here too, or the two screens
        // disagree about what a parameter may be.
        for (String type : FieldDataTypes.labels().keySet()) {
            assertThat(html).as("type %s", type).contains("value=\"" + type + "\"");
        }
        assertThat(html)
                .as("a select needs a vocabulary, and the script reveals this block for one")
                .contains("data-field=\"selectOptions\"")
                .contains("row-options-block");
    }

    // -----------------------------------------------------------------------------------
    // Option severities on the asset's own screen
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a choice parameter offers per-option severities instead of a numeric band")
    void aChoiceRowOffersOptionSeverities() {
        String html = render(List.of(choiceRow("status",
                java.util.List.of("OF", "ON", "Idle"),
                java.util.Map.of("OF", "danger", "ON", "ok", "Idle", "warning"))), "RANGES_ONLY");

        assertThat(html)
                .as("a numeric band on a select is refused server-side, so it is not offered")
                .doesNotContain("rows[0].warningMin");
        assertThat(html)
                .as("these two are what the server reads")
                .contains("rows[0].warningOptions")
                .contains("rows[0].dangerOptions")
                .contains("rows[0].choiceRow");

        // Server-rendered and already ticked (AGENTS.md #126), per option.
        assertThat(rowFor(html, "Idle")).containsPattern("value=\"warning\"[^>]*checked");
        assertThat(rowFor(html, "OF")).containsPattern("value=\"danger\"[^>]*checked");
        assertThat(rowFor(html, "ON")).containsPattern("value=\"ok\"[^>]*checked");

        // ...and the hidden inputs carry the same answer, so a save that touches nothing keeps it.
        assertThat(hiddenValue(html, "rows[0].warningOptions")).isEqualTo("Idle");
        assertThat(hiddenValue(html, "rows[0].dangerOptions")).isEqualTo("OF");
    }

    @Test
    @DisplayName("the collapsed row says how many options carry an alert")
    void theCollapsedRowSummarisesTheMarkedOptions() {
        String html = render(List.of(choiceRow("status",
                java.util.List.of("OF", "ON", "Idle"),
                java.util.Map.of("OF", "danger", "ON", "ok", "Idle", "warning"))), "RANGES_ONLY");

        assertThat(html).contains("3 گزینه");
        assertThat(html)
                .as("two of three are marked, and that is visible without opening the panel")
                .contains("2 علامت‌دار");
    }

    @Test
    @DisplayName("a choice parameter with no vocabulary yet says where options come from")
    void aChoiceRowWithoutOptionsExplainsItself() {
        String html = render(List.of(choiceRow("status",
                java.util.List.of(), java.util.Map.of())), "RANGES_ONLY");

        assertThat(html)
                .as("a class parameter's vocabulary is defined on the class page")
                .contains("در صفحهٔ کلاس تعریف می‌شوند");
        assertThat(html).doesNotContain("rows[0].warningOptions");
    }

    @Test
    @DisplayName("an ADDED choice parameter with no options is told to fix it right here")
    void anAddedChoiceRowWithoutOptionsPointsAtItsOwnBox() {
        // Sending an administrator to the class page would send them somewhere this field does
        // not exist. This is the state the lost-vocabulary bug left behind.
        String html = render(List.of(customChoiceRow("voice_in",
                java.util.List.of(), java.util.Map.of())), "FULL");

        assertThat(html).doesNotContain("در صفحهٔ کلاس تعریف می‌شوند");
        assertThat(html).contains("خالی است");
        assertThat(html)
                .as("and the box to type them into is right there")
                .contains("rows[0].selectOptions");
    }

    @Test
    @DisplayName("option severities are offered in RANGES_ONLY too, like a numeric band")
    void optionSeveritiesAreNotFullOnly() {
        String html = render(List.of(choiceRow("status",
                java.util.List.of("OF", "ON"), java.util.Map.of("OF", "danger"))), "RANGES_ONLY");

        int cell = html.indexOf("option-severity-cell");
        assertThat(cell).isNotNegative();
        int rowStart = html.lastIndexOf("<tr", cell);
        assertThat(html.substring(rowStart, cell))
                .as("the control must not sit inside a full-only cell; a threshold is not structure")
                .doesNotContain("full-only");
    }

    /**
     * A parameter the ASSET added keeps its vocabulary nowhere else, so the form must carry it.
     *
     * <p>Rendered read-only it posts nothing, {@code toOverrides} writes no {@code options} key,
     * and the vocabulary is gone on the next save — which is how marking the severities erased
     * the very options that had just been marked. Same rule as the removal checkbox: <b>the form
     * is the complete statement of this asset's layer, so it must render every key the stored
     * value may contain.</b>
     */
    @Test
    @DisplayName("a choice parameter the asset ADDED can edit its own vocabulary")
    void anAddedChoiceRowRendersItsVocabulary() {
        String html = render(List.of(customChoiceRow("voice_in",
                java.util.List.of("بلی", "خیر"),
                java.util.Map.of("بلی", "danger", "خیر", "ok"))), "FULL");

        assertThat(html)
                .as("without this the next save drops the options and keeps the severity")
                .contains("rows[0].selectOptions");
        // Pre-filled, so a save that changes nothing changes nothing.
        String textarea = html.substring(html.indexOf("rows[0].selectOptions"));
        textarea = textarea.substring(0, textarea.indexOf("</textarea>"));
        assertThat(textarea).contains("بلی").contains("خیر");
        // ...and it still offers the severities for the same row.
        assertThat(html).contains("rows[0].dangerOptions");
    }

    @Test
    @DisplayName("a CLASS choice parameter does not offer to edit the vocabulary here")
    void aClassChoiceRowDoesNotEditTheVocabulary() {
        String html = render(List.of(choiceRow("Status",
                java.util.List.of("OF", "ON"), java.util.Map.of("OF", "danger"))), "FULL");

        assertThat(html)
                .as("the vocabulary belongs to the class, and the class page is where it lives")
                .doesNotContain("rows[0].selectOptions");
        assertThat(html)
                .as("but the asset may still say which of those choices mean trouble")
                .contains("rows[0].dangerOptions");
    }

    /** A choice row the asset itself added — it owns both its vocabulary and its marking. */
    private static AssetParameterRow customChoiceRow(String key, java.util.List<String> options,
                                                     java.util.Map<String, String> severities) {
        return new AssetParameterRow(key, key, null, "select", "", "", "", "",
                false, false, true, false, true, options, severities);
    }

    /** One hidden input's value, by name. */
    private static String hiddenValue(String html, String name) {
        int at = html.indexOf(name + "\"");
        assertThat(at).as("no input named %s", name).isNotNegative();
        int tag = html.lastIndexOf("<input", at);
        String input = html.substring(tag, html.indexOf(">", at));
        int v = input.indexOf("value=\"");
        if (v < 0) return "";
        return input.substring(v + 7, input.indexOf("\"", v + 7));
    }

    /** The table row that renders one option, so an assertion cannot read another's radio. */
    private static String rowFor(String html, String option) {
        int at = html.indexOf(">" + option + "<");
        assertThat(at).as("no row for %s", option).isNotNegative();
        return html.substring(html.lastIndexOf("<tr", at), html.indexOf("</tr>", at));
    }

    /**
     * A choice row with its vocabulary and this asset's severities.
     *
     * <p>A factory rather than the constructor: {@code AssetParameterRow} has grown twice now,
     * and every growth broke every call site that spelled out its arguments.
     */
    private static AssetParameterRow choiceRow(String key, java.util.List<String> options,
                                               java.util.Map<String, String> severities) {
        return new AssetParameterRow(key, key, null, "select", "", "", "", "",
                false, false, false, false, true, options, severities);
    }

    /** The removal checkbox tag for one row, whitespace and attribute order aside. */
    private static String removalBox(String html, int index) {
        String marker = "rows[" + index + "].removed";
        int at = html.indexOf(marker);
        assertThat(at).as("row %s has no removal checkbox at all", index).isNotNegative();
        return html.substring(html.lastIndexOf("<input", at), html.indexOf(">", at));
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
            context.setVariable("dataTypeLabels", FieldDataTypes.labels());
            return engine.process("asset-entry-parameters", context);
        }
    }
}
