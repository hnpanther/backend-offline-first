package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.dto.ScopedAssetPreviewRow;
import com.hnp.backendofflinefirst.entity.LogSheetTemplate;
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
 * The asset preview renders its table in <strong>both</strong> modes.
 *
 * <p>It did not. The convert-to-explicit form was wrapped around the card, so a template
 * already in {@code EXPLICIT} — where the convert action is rightly unavailable — lost the
 * whole table with the form, and the page came up blank for exactly the templates whose frozen
 * list a reader most wants to see. Nothing failed; the markup was simply absent.
 */
class TemplateAssetPreviewRenderTest {

    @Test
    @DisplayName("a scoped template shows the table, the tick column and the convert button")
    void scopedTemplateOffersTheConversion() {
        String html = render(true);

        assertThat(html)
                .contains("AST-1")
                .contains("AST-2")
                .contains("name=\"assetIds\"")
                .contains("id=\"convertButton\"");
    }

    @Test
    @DisplayName("an explicit template still shows the table — only the convert controls go")
    void explicitTemplateStillShowsItsAssets() {
        String html = render(false);

        assertThat(html)
                .as("the frozen list is the whole reason to open this page")
                .contains("AST-1")
                .contains("AST-2")
                .contains("2 دارایی");
        assertThat(html)
                .doesNotContain("name=\"assetIds\"")
                .doesNotContain("id=\"convertButton\"");
    }

    @Test
    @DisplayName("the posting controls reach the form by attribute, not by nesting")
    void theControlsAreAssociatedWithTheFormByAttribute() {
        String html = render(true);

        // The form is emptied on purpose so that `th:if` on it can never remove the table.
        assertThat(html)
                .contains("id=\"convertForm\"")
                .contains("form=\"convertForm\"");
    }

    // ---------------------------------------------------------------------------------------

    private String render(boolean canConvert) {
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
        request.setRequestURI("/log-sheet-templates/1/preview-assets");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);

        LogSheetTemplate template = new LogSheetTemplate();
        template.setId(1L);
        template.setName("راند آزمایشی");

        try (StaticApplicationContext beans = new StaticApplicationContext()) {
            beans.getBeanFactory().registerSingleton("dateUtils", new DateUtils());
            context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new ThymeleafEvaluationContext(beans, null));
            context.setVariable("homePath", "/");
            context.setVariable("template", template);
            context.setVariable("scopeLabel", "محدوده");
            context.setVariable("assets", List.of(
                    new ScopedAssetPreviewRow(11L, "AST-1", "پمپ یک", "TAG-1", "SF-1", "T-1"),
                    new ScopedAssetPreviewRow(12L, "AST-2", "پمپ دو", "TAG-2", "SF-2", "T-2")));
            context.setVariable("assetLimitExceeded", false);
            context.setVariable("assetLimitWarning", false);
            context.setVariable("assetLimitMax", 300);
            context.setVariable("canConvertToExplicit", canConvert);
            return engine.process("log-sheet-template-assets-preview", context);
        }
    }
}
