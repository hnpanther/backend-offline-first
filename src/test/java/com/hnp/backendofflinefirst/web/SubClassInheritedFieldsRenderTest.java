package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldDataTypes;
import com.hnp.backendofflinefirst.entity.AssetClass;
import com.hnp.backendofflinefirst.entity.FieldDefinition;
import com.hnp.backendofflinefirst.util.DateUtils;
import com.hnp.backendofflinefirst.util.FieldValidationViewHelper;
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
 * A sub-class's field page shows what it inherits.
 *
 * <p>It did not. Inheritance is resolved when a schema is read, not stored in
 * {@code field_definitions}, so a brand-new sub-class holds no rows — and this page, which
 * reads that table directly, rendered "no fields defined" for a class that was already
 * carrying every parameter of its parent. The log sheets were right; only the admin screen
 * was lying, which is the kind of disagreement that gets a schema rebuilt by hand.
 */
class SubClassInheritedFieldsRenderTest {

    @Test
    @DisplayName("a sub-class with no rows of its own still shows the parent's, marked inherited")
    void inheritedRowsAreShown() {
        String html = render(List.of(), List.of(parentField("ampere", "Ampere")));

        assertThat(html)
                .contains("ampere")
                .contains("ارثی")
                .doesNotContain("هیچ فیلدی تعریف نشده است");
    }

    @Test
    @DisplayName("an inherited row offers an override, not an edit or a delete")
    void inheritedRowsAreReadOnly() {
        String html = render(List.of(), List.of(parentField("ampere", "Ampere")));

        assertThat(html)
                .as("the row belongs to the parent; editing it there is the only honest place")
                .contains("بازنویسی")
                .contains("inheritKey=ampere");
    }

    @Test
    @DisplayName("a top-level class is unchanged — no inherited section at all")
    void aTopLevelClassShowsNothingExtra() {
        String html = render(List.of(ownField("ampere")), List.of());

        assertThat(html).doesNotContain("ارثی");
    }

    @Test
    @DisplayName("only a class with neither its own nor inherited rows says it is empty")
    void theEmptyMessageAccountsForInheritance() {
        assertThat(render(List.of(), List.of())).contains("هیچ فیلدی تعریف نشده است");
        assertThat(render(List.of(), List.of(parentField("ampere", "Ampere"))))
                .doesNotContain("هیچ فیلدی تعریف نشده است");
    }

    // ---------------------------------------------------------------------------------------

    private static FieldDefinition ownField(String key) {
        FieldDefinition fd = new FieldDefinition();
        fd.setId(1L);
        fd.setClassId(9L);
        fd.setKey(key);
        fd.setLabel(key);
        fd.setDataType("number");
        fd.setOrder(1);
        return fd;
    }

    private static FieldDefinition parentField(String key, String label) {
        FieldDefinition fd = ownField(key);
        fd.setId(2L);
        fd.setClassId(1L);
        fd.setLabel(label);
        return fd;
    }

    private String render(List<FieldDefinition> own, List<FieldDefinition> inherited) {
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
        request.setRequestURI("/asset-classes/9/fields");
        var exchange = JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(request, new MockHttpServletResponse());
        WebContext context = new WebContext(exchange);

        AssetClass sub = new AssetClass();
        sub.setId(9L);
        sub.setName("Pumps HP");
        AssetClass parent = new AssetClass();
        parent.setId(1L);
        parent.setName("Pumps");

        try (StaticApplicationContext beans = new StaticApplicationContext()) {
            beans.getBeanFactory().registerSingleton("dateUtils", new DateUtils());
            beans.getBeanFactory().registerSingleton("fieldValidation", new FieldValidationViewHelper());
            context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                    new ThymeleafEvaluationContext(beans, null));
            context.setVariable("homePath", "/");
            context.setVariable("assetClass", sub);
            context.setVariable("parentClass", inherited.isEmpty() ? null : parent);
            context.setVariable("fieldDefs", own);
            context.setVariable("inheritedFields", inherited);
            context.setVariable("dataTypeLabels", FieldDataTypes.labels());
            context.setVariable("pageNumber", 0);
            return engine.process("field-definitions", context);
        }
    }
}
